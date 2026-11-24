package tw.lrc.downloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 木蘭詞 (mulanci.org) 動態歌詞抓取核心。純 Java，不依賴 Android，方便測試。
 *
 * 流程：歌手名稱 → 從 A-Z 歌手索引找到歌手 ID → 讀歌手頁的全部歌曲清單 → 比對歌名
 *      → 讀歌詞頁 &lt;div id="track-lyrics"&gt; 中帶時間軸 [mm:ss.xx] 的行 → LRC。
 *
 * 網站有流量限制（太快會回 HTTP 429），所以所有請求都排隊、間隔、遇 429 自動等待重試。
 */
public class MulanciClient {

    public static final String BASE = "https://www.mulanci.org";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final String[] CATEGORIES = {"male", "female", "group"};
    private static final String[] LETTERS = {"popular", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"};
    private static final long INDEX_MAX_AGE_MS = 30L * 24 * 3600 * 1000;

    public interface Log { void log(String msg); }

    public enum Status { OK, NO_ARTIST, NO_SONG, NO_LRC, ERROR }

    public static class Result {
        public Status status;
        public String lrc;
        public String url;
        public String detail;
        Result(Status s, String detail) { this.status = s; this.detail = detail; }
    }

    public static class Artist {
        public final String lang; // "t" 繁體 / "s" 簡體
        public final String id;
        public final String name;
        public final String alias;
        Artist(String lang, String id, String name, String alias) {
            this.lang = lang; this.id = id; this.name = name; this.alias = alias;
        }
    }

    public static class Song {
        public final String id;
        public final String title;
        Song(String id, String title) { this.id = id; this.title = title; }
    }

    public interface Cancel { boolean isCancelled(); }

    private final File cacheDir;
    private final Log log;
    private Cancel cancel = () -> false;
    private long minIntervalMs = 1500;
    private long lastRequestAt = 0;

    private final Map<String, List<Artist>> indexByLang = new HashMap<>();
    private final Map<String, List<Song>> artistSongsCache = new HashMap<>();

    public MulanciClient(File cacheDir, Log log) {
        this.cacheDir = cacheDir;
        this.log = log != null ? log : m -> { };
    }

    public void setCancel(Cancel c) { this.cancel = c; }
    public void setMinIntervalMs(long ms) { this.minIntervalMs = ms; }

    // ------------------------------------------------------------------ HTTP

    public String get(String url) throws IOException {
        long[] backoff = {10_000, 20_000, 40_000, 60_000, 90_000};
        for (int attempt = 0; ; attempt++) {
            checkCancel();
            throttle();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9");
            try {
                int code = c.getResponseCode();
                if (code == 429 || code == 503) {
                    if (attempt >= backoff.length) throw new IOException("網站一直回 HTTP " + code + "（流量限制）");
                    log.log("　⏳ 網站流量限制 (HTTP " + code + ")，等待 " + backoff[attempt] / 1000 + " 秒後重試…");
                    sleep(backoff[attempt]);
                    continue;
                }
                if (code == 404) return null;
                if (code != 200) throw new IOException("HTTP " + code + " " + url);
                return readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
    }

    private synchronized void throttle() {
        long wait = lastRequestAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancel();
            try { Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis()))); }
            catch (InterruptedException e) { throw new CancelledException(); }
        }
    }

    private void checkCancel() { if (cancel.isCancelled()) throw new CancelledException(); }

    public static class CancelledException extends RuntimeException { }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 歌手索引

    private static final Pattern ARTIST_LINK =
            Pattern.compile("href=\"/lyric/(t|s)(\\d+)/\"[^>]*>\\s*([^<]+?)\\s*<");

    /** 解析索引頁中的歌手連結。名稱格式如「蔡依林( JOLIN )」。 */
    public static List<Artist> parseArtistIndex(String html, String lang) {
        List<Artist> list = new ArrayList<>();
        Matcher m = ARTIST_LINK.matcher(html);
        while (m.find()) {
            String raw = unescape(m.group(3)).trim();
            String name = raw, alias = "";
            Matcher p = Pattern.compile("^(.*?)\\(\\s*(.*?)\\s*\\)\\s*$").matcher(raw);
            if (p.find()) { name = p.group(1).trim(); alias = p.group(2).trim(); }
            list.add(new Artist(lang, m.group(2), name, alias));
        }
        return list;
    }

    public void clearIndexCache() {
        indexByLang.clear();
        for (String lang : new String[]{"t", "s"}) {
            File f = new File(cacheDir, "artists_" + lang + ".tsv");
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** 取得歌手索引（快取 30 天）。lang: "t" 繁體 / "s" 簡體。 */
    public List<Artist> getIndex(String lang) throws IOException {
        List<Artist> cached = indexByLang.get(lang);
        if (cached != null) return cached;
        File f = new File(cacheDir, "artists_" + lang + ".tsv");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < INDEX_MAX_AGE_MS) {
            List<Artist> list = loadIndex(f);
            if (list.size() > 100) {
                indexByLang.put(lang, list);
                return list;
            }
        }
        String langName = lang.equals("t") ? "繁體" : "簡體";
        log.log("📥 第一次使用：下載木蘭詞" + langName + "歌手索引（約 81 頁，需幾分鐘，之後會快取 30 天）");
        Map<String, Artist> byId = new java.util.LinkedHashMap<>();
        int total = CATEGORIES.length * LETTERS.length, done = 0;
        for (String cat : CATEGORIES) {
            for (String letter : LETTERS) {
                String html = get(BASE + "/zh" + (lang.equals("t") ? "t" : "s") + "/artists/" + cat + "-" + letter + "/");
                done++;
                if (html != null) {
                    for (Artist a : parseArtistIndex(html, lang)) byId.put(a.id, a);
                }
                if (done % 9 == 0 || done == total)
                    log.log("　索引進度 " + done + "/" + total + "，已收錄 " + byId.size() + " 位歌手");
            }
        }
        List<Artist> list = new ArrayList<>(byId.values());
        saveIndex(f, list);
        indexByLang.put(lang, list);
        return list;
    }

    private static List<Artist> loadIndex(File f) throws IOException {
        List<Artist> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length >= 4) list.add(new Artist(p[0], p[1], p[2], p[3]));
            }
        }
        return list;
    }

    private static void saveIndex(File f, List<Artist> list) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (Artist a : list) {
                w.write(a.lang + "\t" + a.id + "\t" + clean(a.name) + "\t" + clean(a.alias) + "\n");
            }
        }
    }

    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' '); }

    /** 把「蔡依林 & 周杰倫」「A feat. B」拆成多個歌手名。 */
    public static List<String> splitArtists(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        out.add(s.trim());
        for (String part : s.split("\\s*(?:,|，|&|＆|、|/|／|;|；|\\+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+x\\s+|\\s+X\\s+|\\s+vs\\.?\\s+)\\s*")) {
            String p = part.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    public List<Artist> findArtists(String artistText, String lang) throws IOException {
        List<Artist> index = getIndex(lang);
        List<Artist> found = new ArrayList<>();
        for (String part : splitArtists(artistText)) {
            String n = norm(part);
            if (n.isEmpty()) continue;
            for (Artist a : index) {
                if (n.equals(norm(a.name)) || (!a.alias.isEmpty() && n.equals(norm(a.alias)))
                        || n.equals(norm(a.name + a.alias)) || n.equals(norm(a.alias + a.name))) {
                    if (!found.contains(a)) found.add(a);
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------ 歌手歌曲清單

    private static final Pattern SONG_LINK =
            Pattern.compile("href=\"/lyric/(?:tl|sl)(\\d+)/\"[^>]*>\\s*([^<]+?)\\s*</a>");

    public static List<Song> parseArtistSongs(String html) {
        List<Song> list = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        Matcher m = SONG_LINK.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) continue;
            String title = unescape(m.group(2)).replaceAll("\\s+", " ").trim();
            title = title.replaceFirst("^\\d{1,4}\\.\\s*", "");
            list.add(new Song(id, title));
        }
        return list;
    }

    public List<Song> getArtistSongs(Artist a) throws IOException {
        String key = a.lang + a.id;
        List<Song> cached = artistSongsCache.get(key);
        if (cached != null) return cached;
        String html = get(BASE + "/lyric/" + a.lang + a.id + "/");
        List<Song> list = html == null ? new ArrayList<>() : parseArtistSongs(html);
        artistSongsCache.put(key, list);
        return list;
    }

    // ------------------------------------------------------------ 歌名比對

    /** 去掉括號內的版本說明、「 - 電影主題曲」之類的附註。 */
    public static String cleanTitle(String t) {
        String s = Normalizer.normalize(t, Normalizer.Form.NFKC);
        s = s.replaceAll("\\s+-\\s+.*$", "");
        s = s.replaceAll("[\\(\\[【《「『〈].*?[\\)\\]】》」』〉]", " ");
        s = s.replaceAll("(?i)\\b(live|mv|official|audio|lyrics?|hd|hq|320k|flac)\\b", " ");
        return norm(s);
    }

    /** 回傳依相似度排序的候選歌曲（最多 maxN 首）。 */
    public static List<Song> matchSongs(List<Song> songs, String title, int maxN) {
        String rawQ = norm(title);
        String q = cleanTitle(title);
        if (q.isEmpty()) q = rawQ;
        List<Object[]> scored = new ArrayList<>();
        for (Song s : songs) {
            String raw = norm(s.title);
            String c = cleanTitle(s.title);
            int score = 0;
            if (!rawQ.isEmpty() && raw.equals(rawQ)) score = 100;
            else if (!q.isEmpty() && c.equals(q)) score = 90;
            else if (q.length() >= 2 && c.length() >= 2 && (c.startsWith(q) || q.startsWith(c))) score = 40;
            else if (q.length() >= 3 && c.length() >= 3 && (c.contains(q) || q.contains(c))) score = 20;
            if (score > 0) scored.add(new Object[]{score, s});
        }
        Collections.sort(scored, (x, y) -> (Integer) y[0] - (Integer) x[0]);
        List<Song> out = new ArrayList<>();
        for (Object[] o : scored) {
            if (out.size() >= maxN) break;
            out.add((Song) o[1]);
        }
        return out;
    }

    // ------------------------------------------------------------ LRC 解析

    private static final Pattern LRC_LINE =
            Pattern.compile("^(\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\])+.*$");
    private static final Pattern LRC_TAG =
            Pattern.compile("^\\[(ti|ar|al|by|offset|au|length|re|ve):.*\\]$", Pattern.CASE_INSENSITIVE);

    /** 從歌詞頁擷取帶時間軸的行。若少於 5 行時間軸，回傳 null（代表沒有動態歌詞）。 */
    public static String extractLrc(String html) {
        if (html == null) return null;
        int start = html.indexOf("id=\"track-lyrics\"");
        if (start < 0) return null;
        start = html.indexOf('>', start) + 1;
        int end = html.indexOf("</div>", start);
        if (end < 0) end = html.length();
        String block = html.substring(start, end);
        block = block.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", "");
        block = unescape(block);
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        int timed = 0;
        for (String line : block.split("\\r?\\n")) {
            String l = line.replace('\u00a0', ' ').trim();
            if (LRC_LINE.matcher(l).matches()) { lines.add(l); timed++; }
            else if (LRC_TAG.matcher(l).matches()) lines.add(l);
        }
        if (timed < 5) return null;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\r\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 主要流程

    /**
     * 依歌手 + 歌名找動態歌詞。先查繁體索引，找不到歌手再查簡體索引。
     */
    public Result find(String artist, String title) throws IOException {
        if (artist == null || artist.trim().isEmpty() || title == null || title.trim().isEmpty())
            return new Result(Status.NO_ARTIST, "無法判斷歌手或歌名");
        Result best = null;
        for (String lang : new String[]{"t", "s"}) {
            List<Artist> artists = findArtists(artist, lang);
            if (artists.isEmpty()) continue;
            for (Artist a : artists) {
                List<Song> cands = matchSongs(getArtistSongs(a), title, 4);
                if (cands.isEmpty()) {
                    if (best == null) best = new Result(Status.NO_SONG, "歌手「" + a.name + "」的歌曲清單裡找不到「" + title + "」");
                    continue;
                }
                for (Song s : cands) {
                    String url = BASE + "/lyric/" + a.lang + "l" + s.id + "/";
                    String lrc = extractLrc(get(url));
                    if (lrc != null) {
                        Result r = new Result(Status.OK, a.name + " - " + s.title);
                        r.lrc = withHeader(lrc, s.title, a.name);
                        r.url = url;
                        return r;
                    }
                    if (best == null || best.status != Status.NO_LRC) {
                        best = new Result(Status.NO_LRC, "找到「" + a.name + " - " + s.title + "」但網站沒有動態歌詞");
                        best.url = url;
                    }
                }
            }
            // 已找到歌曲（有/無動態歌詞）就不必再查另一語系；找不到歌名時再試簡體（檔名可能是簡體字）
            if (best != null && best.status != Status.NO_SONG) return best;
        }
        return best != null ? best : new Result(Status.NO_ARTIST, "木蘭詞找不到歌手「" + artist + "」");
    }

    private static String withHeader(String lrc, String title, String artist) {
        StringBuilder sb = new StringBuilder();
        if (!lrc.toLowerCase(Locale.ROOT).contains("[ti:")) sb.append("[ti:").append(title).append("]\r\n");
        if (!lrc.toLowerCase(Locale.ROOT).contains("[ar:")) sb.append("[ar:").append(artist).append("]\r\n");
        sb.append("[by:mulanci.org]\r\n");
        sb.append(lrc);
        return sb.toString();
    }

    // ------------------------------------------------------------ 檔名解析

    /** 由檔名（不含副檔名）推測 [歌手, 歌名] 的可能組合。 */
    public static List<String[]> guessFromFilename(String base) {
        List<String[]> out = new ArrayList<>();
        String s = base.replace('_', ' ').trim();
        s = s.replaceFirst("^\\d{1,3}\\s*[.\\-、)]\\s*", "");
        s = s.replaceFirst("^\\d{1,3}\\s+", "");
        String[] parts = s.split("\\s*[-－—–]\\s*", 2);
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            out.add(new String[]{parts[0].trim(), parts[1].trim()});
            out.add(new String[]{parts[1].trim(), parts[0].trim()});
        }
        return out;
    }

    // ------------------------------------------------------------ 工具

    /** 正規化：全形轉半形、小寫、只留文字與數字。 */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            else {
                switch (e) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "nbsp": r = " "; break;
                    default: r = m.group(0);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
