package tw.lrc.downloader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 兩個功能：
 * ① 手動輸入檔名 → 到木蘭詞抓動態歌詞 → 存成 .lrc（有同名 mp3 就放在它旁邊，否則放在選擇的資料夾）
 * ② 匯出資料夾內所有 mp3 的清單（CSV），可分享到電腦
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_DIR = 1;
    private static final String PREFS = "settings";

    private TextView pathView, statusView, logView;
    private EditText nameInput;
    private Button pickBtn, fetchBtn, exportBtn, shareBtn, reindexBtn;
    private CheckBox subdirBox, tagBox;
    private ProgressBar progress;
    private ScrollView logScroll;
    private WebView webView;
    private WebFetcher webFetcher;
    private MulanciClient client;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private Uri treeUri;
    private Uri lastExport;
    private List<Mp3> scanCache;      // 手動抓歌詞時用來找同名 mp3
    private String scanCacheKey;
    private final SpannableStringBuilder logText = new SpannableStringBuilder();

    static class Mp3 {
        String name;      // 檔名 (含 .mp3)
        String dirLabel;  // 相對路徑，例如 "華語/"
        Uri uri;          // mp3 文件
        Uri parentDoc;    // 所在資料夾
        Uri existingLrc;  // 已存在的同名 .lrc
        long size;
    }

    // ================================================================== UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("MP3 動態歌詞");

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(8), pad, pad);

        pickBtn = new Button(this);
        pickBtn.setText("📁 選擇 MP3 資料夾");
        pickBtn.setOnClickListener(v -> pickFolder());
        root.addView(pickBtn, matchWrap());

        pathView = new TextView(this);
        pathView.setTextSize(14);
        pathView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(pathView, matchWrap());

        subdirBox = new CheckBox(this);
        subdirBox.setText("包含子資料夾");
        root.addView(subdirBox, matchWrap());

        // ---- ① 手動抓單首
        root.addView(section("① 輸入檔名抓歌詞"), matchWrapTop(dp(12)));
        nameInput = new EditText(this);
        nameInput.setHint("例如：蔡依林 - 聽說愛情回來過");
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        nameInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { fetchOne(); return true; }
            return false;
        });
        root.addView(nameInput, matchWrap());
        fetchBtn = new Button(this);
        fetchBtn.setText("🎵 抓歌詞並存成 .lrc");
        fetchBtn.setOnClickListener(v -> { if (running) cancel(); else fetchOne(); });
        root.addView(fetchBtn, matchWrap());

        // ---- ② 匯出清單
        root.addView(section("② 匯出 MP3 清單（給電腦程式用）"), matchWrapTop(dp(12)));
        tagBox = new CheckBox(this);
        tagBox.setText("同時讀取 MP3 內的歌手／歌名／長度（較慢）");
        root.addView(tagBox, matchWrap());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        exportBtn = new Button(this);
        exportBtn.setText("📄 匯出清單");
        exportBtn.setOnClickListener(v -> { if (running) cancel(); else exportList(); });
        shareBtn = new Button(this);
        shareBtn.setText("📤 分享清單");
        shareBtn.setEnabled(false);
        shareBtn.setOnClickListener(v -> shareExport());
        row.addView(exportBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(shareBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1);
        root.addView(progress, matchWrapTop(dp(8)));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, matchWrap());

        // 用來讀木蘭詞網頁的瀏覽器核心；平常幾乎看不見，遇到人機驗證時才展開
        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        webFetcher = new WebFetcher(webView, new WebFetcher.UiListener() {
            @Override public void showWebView(boolean show) {
                ViewGroup.LayoutParams lp = webView.getLayoutParams();
                lp.height = show ? dp(420) : 1;
                webView.setLayoutParams(lp);
            }
            @Override public void log(String msg) { MainActivity.this.log(msg, Color.rgb(160, 0, 160)); }
        });
        webFetcher.setCancel(() -> cancelled);
        client = new MulanciClient(getFilesDir(), msg -> log(msg, Color.DKGRAY));
        client.setCancel(() -> cancelled);
        client.setFetcher(webFetcher);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setGravity(Gravity.TOP);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(8);
        root.addView(logScroll, lp);

        reindexBtn = new Button(this);
        reindexBtn.setText("更新木蘭詞歌手索引");
        reindexBtn.setTextSize(12);
        reindexBtn.setOnClickListener(v -> {
            client.clearIndexCache();
            Toast.makeText(this, "已清除歌手索引，下次抓歌詞時會重新下載", Toast.LENGTH_SHORT).show();
        });
        root.addView(reindexBtn, matchWrap());

        setContentView(root);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        subdirBox.setChecked(p.getBoolean("subdir", true));
        tagBox.setChecked(p.getBoolean("tags", false));
        nameInput.setText(p.getString("lastName", ""));
        String saved = p.getString("tree", null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            for (android.content.UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
                if (perm.getUri().equals(u) && perm.isWritePermission()) treeUri = u;
            }
        }
        updatePath();
        log("使用說明：\n① 輸入「歌手 - 歌名」（可含 .mp3），按抓歌詞。找到同名 mp3 會把 .lrc 放在它旁邊，否則放在選擇的資料夾。"
                + "\n② 匯出清單會在選擇的資料夾產生 CSV（可用 Excel 開），並可分享到電腦。\n", Color.GRAY);
    }

    private TextView section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(31, 111, 235));
        return t;
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_DIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DIR && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            treeUri = u;
            scanCache = null;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tree", u.toString()).apply();
            updatePath();
        }
    }

    private void updatePath() {
        boolean has = treeUri != null;
        pathView.setText(has ? "📂 " + prettyPath(treeUri) : "⚠ 尚未選擇資料夾（請先按上方按鈕選擇）");
        pathView.setTextColor(has ? Color.BLACK : Color.RED);
    }

    private static String prettyPath(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int c = id.indexOf(':');
        if (c < 0) return id;
        String vol = id.substring(0, c), path = id.substring(c + 1);
        String volName = vol.equals("primary") ? "內部儲存空間" : "SD 卡 (" + vol + ")";
        return volName + (path.isEmpty() ? "" : "/" + path);
    }

    // ================================================================== 共用執行

    private interface Job { void run() throws Exception; }

    private void runJob(Button activeBtn, String stopText, Job job) {
        if (treeUri == null || running) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("subdir", subdirBox.isChecked())
                .putBoolean("tags", tagBox.isChecked())
                .putString("lastName", nameInput.getText().toString()).apply();
        running = true;
        cancelled = false;
        final CharSequence normalText = activeBtn.getText();
        setControls(false, activeBtn);
        activeBtn.setText(stopText);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            try {
                job.run();
            } catch (MulanciClient.CancelledException e) {
                log("⏹ 已停止", Color.GRAY);
            } catch (Throwable t) {
                log("❌ 發生錯誤：" + t.getMessage(), Color.RED);
            } finally {
                ui.post(() -> {
                    running = false;
                    activeBtn.setText(normalText);
                    setControls(true, null);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
        }, "lrc-worker").start();
    }

    private void cancel() { cancelled = true; }

    private void setControls(boolean idle, Button active) {
        pickBtn.setEnabled(idle);
        reindexBtn.setEnabled(idle);
        subdirBox.setEnabled(idle);
        tagBox.setEnabled(idle);
        nameInput.setEnabled(idle);
        fetchBtn.setEnabled(idle || fetchBtn == active);
        exportBtn.setEnabled(idle || exportBtn == active);
        shareBtn.setEnabled(idle && lastExport != null);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
    }

    // ================================================================== ① 手動抓單首

    private boolean needFolder() {
        if (treeUri != null) return false;
        Toast.makeText(this, "請先選擇 MP3 資料夾", Toast.LENGTH_LONG).show();
        pickFolder();
        return true;
    }

    private void fetchOne() {
        if (needFolder()) return;
        final String input = nameInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "請先輸入檔名，例如：蔡依林 - 聽說愛情回來過", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        final boolean subdirs = subdirBox.isChecked();
        runJob(fetchBtn, "■ 停止", () -> {
            final String base = input.replaceFirst("(?i)\\.(mp3|lrc)$", "").trim();
            log("────────────\n🎵 " + base, Color.BLACK);
            setStatus("查詢中…", true);

            // 找同名 mp3（第一次需要掃描資料夾）
            List<Mp3> all = getScan(subdirs);
            List<Mp3> matches = new ArrayList<>();
            for (Mp3 f : all) {
                if (f.name.replaceFirst("(?i)\\.mp3$", "").equalsIgnoreCase(base)) matches.add(f);
            }

            List<String[]> cands = new ArrayList<>();
            if (!matches.isEmpty()) addTagCandidate(matches.get(0).uri, cands);
            for (String[] g : MulanciClient.guessFromFilename(base)) addUnique(cands, g);
            if (cands.isEmpty()) {
                log("　⚠ 請用「歌手 - 歌名」的格式輸入", Color.rgb(200, 100, 0));
                setStatus("格式不符", false);
                return;
            }

            MulanciClient.Result r = lookup(cands);
            if (r.status != MulanciClient.Status.OK) {
                log("　⚠ " + r.detail + (r.url != null ? "\n　" + r.url : ""), Color.rgb(200, 100, 0));
                setStatus("沒有抓到歌詞", false);
                return;
            }

            ContentResolver cr = getContentResolver();
            String lrcName = base + ".lrc";
            if (matches.isEmpty()) {
                Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri));
                Uri existing = findChild(cr, DocumentsContract.getTreeDocumentId(treeUri), lrcName);
                String out = writeText(cr, rootDoc, existing, lrcName, r.lrc, false);
                log("　✅ " + r.detail + "\n　（資料夾內沒有同名 mp3）已存到：" + prettyPath(treeUri) + "/" + out,
                        Color.rgb(0, 128, 0));
            } else {
                for (Mp3 f : matches) {
                    String out = writeText(cr, f.parentDoc, f.existingLrc, lrcName, r.lrc, false);
                    log("　✅ " + r.detail + "\n　已存到：" + f.dirLabel + out
                            + (f.existingLrc != null ? "（覆蓋原有 .lrc）" : ""), Color.rgb(0, 128, 0));
                }
                scanCache = null; // .lrc 狀態已變，下次重新掃描
            }
            log("　來源：" + r.url, Color.GRAY);
            log(preview(r.lrc), Color.DKGRAY);
            setStatus("完成", false);
        });
    }

    private List<Mp3> getScan(boolean subdirs) {
        String key = treeUri + "|" + subdirs;
        if (scanCache != null && key.equals(scanCacheKey)) return scanCache;
        List<Mp3> list = new ArrayList<>();
        scan(getContentResolver(), DocumentsContract.getTreeDocumentId(treeUri), "", subdirs, list, null);
        scanCache = list;
        scanCacheKey = key;
        return list;
    }

    private void addTagCandidate(Uri mp3, List<String[]> cands) {
        String[] t = readTags(mp3);
        if (t[0] != null && t[1] != null) addUnique(cands, new String[]{t[0], t[1]});
    }

    private static void addUnique(List<String[]> cands, String[] g) {
        for (String[] c : cands)
            if (MulanciClient.norm(c[0]).equals(MulanciClient.norm(g[0]))
                    && MulanciClient.norm(c[1]).equals(MulanciClient.norm(g[1]))) return;
        cands.add(g);
    }

    private MulanciClient.Result lookup(List<String[]> cands) throws Exception {
        MulanciClient.Result best = null;
        for (String[] c : cands) {
            MulanciClient.Result r = client.find(c[0], c[1]);
            if (r.status == MulanciClient.Status.OK) return r;
            if (best == null || rank(r.status) > rank(best.status)) best = r;
        }
        return best;
    }

    private static int rank(MulanciClient.Status s) {
        switch (s) {
            case NO_LRC: return 3;
            case NO_SONG: return 2;
            case NO_ARTIST: return 1;
            default: return 0;
        }
    }

    private static String preview(String lrc) {
        String[] lines = lrc.split("\r?\n");
        StringBuilder sb = new StringBuilder("　預覽：\n");
        for (int i = 0; i < Math.min(8, lines.length); i++) sb.append("　").append(lines[i]).append("\n");
        if (lines.length > 8) sb.append("　…共 ").append(lines.length).append(" 行");
        return sb.toString();
    }

    // ================================================================== ② 匯出清單

    private void exportList() {
        if (needFolder()) return;
        final boolean subdirs = subdirBox.isChecked(), tags = tagBox.isChecked();
        runJob(exportBtn, "■ 停止", () -> {
            log("────────────\n📄 掃描 " + prettyPath(treeUri) + " …", Color.BLACK);
            setStatus("掃描資料夾中…", true);
            List<Mp3> all = new ArrayList<>();
            scan(getContentResolver(), DocumentsContract.getTreeDocumentId(treeUri), "", subdirs, all,
                    n -> setStatus("掃描中… 已找到 " + n + " 首", true));
            scanCache = all;
            scanCacheKey = treeUri + "|" + subdirs;
            log("找到 " + all.size() + " 個 MP3", Color.DKGRAY);
            if (all.isEmpty()) { setStatus("沒有 MP3", false); return; }

            StringBuilder csv = new StringBuilder();
            csv.append(tags ? "相對路徑,檔名,主檔名,已有LRC,大小(MB),歌手(標籤),歌名(標籤),專輯(標籤),長度(秒)\r\n"
                            : "相對路徑,檔名,主檔名,已有LRC,大小(MB)\r\n");
            int i = 0, withLrc = 0;
            for (Mp3 f : all) {
                if (cancelled) throw new MulanciClient.CancelledException();
                i++;
                if (f.existingLrc != null) withLrc++;
                csv.append(csvCell(f.dirLabel)).append(',')
                        .append(csvCell(f.name)).append(',')
                        .append(csvCell(f.name.replaceFirst("(?i)\\.mp3$", ""))).append(',')
                        .append(f.existingLrc != null ? "Y" : "N").append(',')
                        .append(String.format(Locale.US, "%.1f", f.size / 1048576.0));
                if (tags) {
                    String[] t = readTags(f.uri);
                    csv.append(',').append(csvCell(t[0])).append(',').append(csvCell(t[1]))
                            .append(',').append(csvCell(t[2])).append(',').append(t[3] == null ? "" : t[3]);
                    if (i % 20 == 0 || i == all.size()) {
                        final int n = i;
                        ui.post(() -> { progress.setMax(all.size()); progress.setProgress(n); });
                        setStatus("讀取標籤 " + i + "/" + all.size(), true);
                    }
                }
                csv.append("\r\n");
            }

            String name = "mp3清單_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv";
            ContentResolver cr = getContentResolver();
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String out = writeText(cr, rootDoc, null, name, csv.toString(), true);
            lastExport = findChild(cr, DocumentsContract.getTreeDocumentId(treeUri), out);
            log("✅ 已匯出 " + all.size() + " 首（其中 " + withLrc + " 首已有 .lrc）\n　檔案：" + prettyPath(treeUri) + "/" + out
                    + "\n　可按「📤 分享清單」傳到電腦，或用 USB 從手機資料夾複製。", Color.rgb(0, 128, 0));
            ui.post(() -> { progress.setMax(1); progress.setProgress(1); });
            setStatus("匯出完成：" + all.size() + " 首", false);
            ui.post(this::shareExport);
        });
    }

    private void shareExport() {
        if (lastExport == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, lastExport);
        send.putExtra(Intent.EXTRA_SUBJECT, "MP3 清單");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "分享 MP3 清單到…"));
        } catch (Exception e) {
            Toast.makeText(this, "無法分享：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /** 讀 MP3 標籤：{歌手, 歌名, 專輯, 長度秒} */
    private String[] readTags(Uri uri) {
        String[] r = new String[4];
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            r[0] = blankToNull(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            r[1] = blankToNull(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            r[2] = blankToNull(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) r[3] = String.valueOf(Math.round(Long.parseLong(d) / 1000.0));
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        return r;
    }

    private static String blankToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }

    // ================================================================== 檔案

    /** 寫文字檔；existing 不為 null 時覆蓋，否則新建。回傳實際檔名。 */
    private String writeText(ContentResolver cr, Uri parentDoc, Uri existing, String name, String text, boolean bom)
            throws Exception {
        Uri target = existing;
        if (target == null) {
            target = DocumentsContract.createDocument(cr, parentDoc, "application/octet-stream", name);
            if (target == null) throw new Exception("無法建立檔案 " + name);
        }
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) throw new Exception("無法寫入 " + name);
            if (bom) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        String actual = queryName(cr, target);
        return actual != null ? actual : name;
    }

    private static String queryName(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    /** 在某資料夾找指定檔名（不分大小寫），回傳文件 Uri 或 null。 */
    private Uri findChild(ContentResolver cr, String dirDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId);
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equalsIgnoreCase(c.getString(1)))
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private interface ScanProgress { void found(int n); }

    /** 遞迴列出資料夾中的 mp3，並記下同資料夾已存在的 .lrc。 */
    private void scan(ContentResolver cr, String docId, String rel, boolean recurse, List<Mp3> out, ScanProgress sp) {
        if (cancelled) throw new MulanciClient.CancelledException();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        List<String[]> dirs = new ArrayList<>();
        List<Object[]> mp3s = new ArrayList<>();
        Map<String, String> lrcByLower = new HashMap<>();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!name.startsWith(".")) dirs.add(new String[]{id, name});
                } else if (lower.endsWith(".mp3")) {
                    mp3s.add(new Object[]{id, name, c.isNull(3) ? 0L : c.getLong(3)});
                } else if (lower.endsWith(".lrc")) {
                    lrcByLower.put(lower, id);
                }
            }
        } catch (Exception e) {
            log("無法讀取資料夾 " + rel + "：" + e.getMessage(), Color.RED);
            return;
        }
        mp3s.sort((a, b) -> ((String) a[1]).compareToIgnoreCase((String) b[1]));
        for (Object[] m : mp3s) {
            Mp3 f = new Mp3();
            f.name = (String) m[1];
            f.dirLabel = rel;
            f.size = (Long) m[2];
            f.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, (String) m[0]);
            f.parentDoc = parentDoc;
            String lrcKey = f.name.replaceFirst("(?i)\\.mp3$", "").toLowerCase(Locale.ROOT) + ".lrc";
            String lrcId = lrcByLower.get(lrcKey);
            if (lrcId != null) f.existingLrc = DocumentsContract.buildDocumentUriUsingTree(treeUri, lrcId);
            out.add(f);
        }
        if (sp != null) sp.found(out.size());
        if (recurse) {
            dirs.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            for (String[] d : dirs) scan(cr, d[0], rel + d[1] + "/", true, out, sp);
        }
    }

    // ================================================================== log / 工具

    private void setStatus(String s, boolean busy) {
        ui.post(() -> {
            statusView.setText(s);
            progress.setIndeterminate(busy && !s.startsWith("讀取標籤"));
        });
    }

    private void log(String msg, int color) {
        ui.post(() -> {
            int start = logText.length();
            logText.append(msg).append("\n");
            logText.setSpan(new ForegroundColorSpan(color), start, logText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (logText.length() > 60000) logText.delete(0, logText.length() - 50000);
            logView.setText(logText);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        return lp;
    }
}
