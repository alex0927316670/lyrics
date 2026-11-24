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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PICK_DIR = 1;
    private static final String PREFS = "settings";

    private TextView pathView, statusView, logView;
    private Button pickBtn, startBtn, reindexBtn;
    private CheckBox overwriteBox, subdirBox, bomBox;
    private ProgressBar progress;
    private ScrollView logScroll;
    private WebView webView;
    private WebFetcher webFetcher;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private Uri treeUri;
    private final SpannableStringBuilder logText = new SpannableStringBuilder();

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("MP3 動態歌詞下載");

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText("選擇存放 MP3 的資料夾，程式會找出底下所有 .mp3，到木蘭詞 (mulanci.org) 抓動態歌詞，"
                + "存成同檔名的 .lrc。\n建議檔名格式：「歌手 - 歌名.mp3」（也會讀 MP3 內的歌手/歌名標籤）。");
        hint.setTextSize(13);
        root.addView(hint, matchWrap());

        pickBtn = new Button(this);
        pickBtn.setText("📁 選擇資料夾");
        pickBtn.setOnClickListener(v -> pickFolder());
        root.addView(pickBtn, matchWrapTop(dp(12)));

        pathView = new TextView(this);
        pathView.setTextSize(14);
        pathView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(pathView, matchWrap());

        subdirBox = new CheckBox(this);
        subdirBox.setText("包含子資料夾");
        overwriteBox = new CheckBox(this);
        overwriteBox.setText("覆蓋已存在的 .lrc（不勾＝已有歌詞的略過）");
        bomBox = new CheckBox(this);
        bomBox.setText("UTF-8 加 BOM（部分車機／舊播放器需要）");
        root.addView(subdirBox, matchWrapTop(dp(8)));
        root.addView(overwriteBox, matchWrap());
        root.addView(bomBox, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = new Button(this);
        startBtn.setText("▶ 開始下載歌詞");
        startBtn.setOnClickListener(v -> { if (running) cancel(); else start(); });
        reindexBtn = new Button(this);
        reindexBtn.setText("更新歌手索引");
        reindexBtn.setOnClickListener(v -> {
            new MulanciClient(getFilesDir(), null).clearIndexCache();
            Toast.makeText(this, "已清除歌手索引，下次執行時會重新下載", Toast.LENGTH_SHORT).show();
        });
        row.addView(startBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        row.addView(reindexBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row, matchWrapTop(dp(8)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1);
        root.addView(progress, matchWrapTop(dp(8)));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, matchWrap());

        // 用來抓木蘭詞網頁的瀏覽器核心；平常幾乎看不見，遇到人機驗證時才展開
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

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setGravity(Gravity.TOP);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(8);
        root.addView(logScroll, lp);

        setContentView(root);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        subdirBox.setChecked(p.getBoolean("subdir", true));
        overwriteBox.setChecked(p.getBoolean("overwrite", false));
        bomBox.setChecked(p.getBoolean("bom", false));
        String saved = p.getString("tree", null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            boolean stillGranted = false;
            for (android.content.UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
                if (perm.getUri().equals(u) && perm.isWritePermission()) stillGranted = true;
            }
            if (stillGranted) treeUri = u;
        }
        updatePath();
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tree", u.toString()).apply();
            updatePath();
        }
    }

    private void updatePath() {
        if (treeUri == null) {
            pathView.setText("尚未選擇資料夾");
            startBtn.setEnabled(false);
        } else {
            pathView.setText("📂 " + prettyPath(treeUri));
            startBtn.setEnabled(true);
        }
    }

    private static String prettyPath(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int c = id.indexOf(':');
        if (c < 0) return id;
        String vol = id.substring(0, c), path = id.substring(c + 1);
        String volName = vol.equals("primary") ? "內部儲存空間" : "SD 卡 (" + vol + ")";
        return volName + (path.isEmpty() ? "" : "/" + path);
    }

    // ------------------------------------------------------------------ 執行

    private void start() {
        if (treeUri == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("subdir", subdirBox.isChecked())
                .putBoolean("overwrite", overwriteBox.isChecked())
                .putBoolean("bom", bomBox.isChecked()).apply();
        running = true;
        cancelled = false;
        setControls(false);
        logText.clear();
        logView.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final boolean subdirs = subdirBox.isChecked(), overwrite = overwriteBox.isChecked(), bom = bomBox.isChecked();
        new Thread(() -> {
            try {
                work(subdirs, overwrite, bom);
            } catch (MulanciClient.CancelledException e) {
                log("⏹ 已停止", Color.GRAY);
            } catch (Throwable t) {
                log("❌ 發生錯誤：" + t, Color.RED);
            } finally {
                ui.post(() -> {
                    running = false;
                    setControls(true);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
        }, "lrc-worker").start();
    }

    private void cancel() {
        cancelled = true;
        startBtn.setEnabled(false);
        startBtn.setText("停止中…");
    }

    private void setControls(boolean idle) {
        pickBtn.setEnabled(idle);
        reindexBtn.setEnabled(idle);
        overwriteBox.setEnabled(idle);
        subdirBox.setEnabled(idle);
        bomBox.setEnabled(idle);
        startBtn.setEnabled(treeUri != null);
        startBtn.setText(idle ? "▶ 開始下載歌詞" : "■ 停止");
    }

    static class Mp3 {
        String name;      // 顯示名稱 (含 .mp3)
        String dirLabel;  // 相對路徑
        Uri uri;          // mp3 文件
        Uri parentDoc;    // 所在資料夾 (tree document uri)
        Uri existingLrc;  // 已存在的同名 .lrc
    }

    private void work(boolean subdirs, boolean overwrite, boolean bom) throws Exception {
        ContentResolver cr = getContentResolver();
        log("🔍 掃描 " + prettyPath(treeUri) + " …", Color.DKGRAY);
        List<Mp3> files = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        scan(cr, rootId, "", subdirs, files);
        log("找到 " + files.size() + " 個 MP3", Color.DKGRAY);
        if (files.isEmpty()) return;

        MulanciClient client = new MulanciClient(getFilesDir(), msg -> log(msg, Color.DKGRAY));
        client.setCancel(() -> cancelled);
        webFetcher.setCancel(() -> cancelled);
        client.setFetcher(webFetcher);

        // 先準備好歌手索引；失敗就整批停止，不要每首歌都重試
        try {
            client.getIndex("t");
        } catch (MulanciClient.CancelledException e) {
            throw e;
        } catch (Exception e) {
            log("❌ 無法取得木蘭詞歌手索引，已停止：" + e.getMessage(), Color.RED);
            return;
        }
        log("🔎 開始逐首查詢（網站限速，每首約 3–5 秒；" + files.size() + " 首預估約 "
                + Math.max(1, files.size() * 4 / 60) + " 分鐘）", Color.DKGRAY);

        int ok = 0, skipped = 0, fail = 0, i = 0;
        List<String> failed = new ArrayList<>();
        final int total = files.size();
        for (Mp3 f : files) {
            i++;
            final int idx = i;
            final int fOk = ok, fSkip = skipped, fFail = fail;
            ui.post(() -> {
                progress.setMax(total);
                progress.setProgress(idx - 1);
                statusView.setText(String.format(Locale.TAIWAN, "進度 %d/%d　✅ 成功 %d　⏭ 略過 %d　⚠ 失敗 %d",
                        idx, total, fOk, fSkip, fFail));
            });
            if (cancelled) throw new MulanciClient.CancelledException();

            String label = f.dirLabel + f.name;
            if (f.existingLrc != null && !overwrite) {
                skipped++;
                log("⏭ " + label + "（已有 .lrc）", Color.GRAY);
                continue;
            }
            log("🎵 " + label, Color.BLACK);
            try {
                MulanciClient.Result r = lookup(client, f);
                if (r.status == MulanciClient.Status.OK) {
                    String outName = writeLrc(cr, f, r.lrc, bom);
                    ok++;
                    log("　✅ " + r.detail + " → " + outName, Color.rgb(0, 128, 0));
                } else {
                    fail++;
                    failed.add(label + "：" + r.detail);
                    log("　⚠ " + r.detail, Color.rgb(200, 100, 0));
                }
            } catch (MulanciClient.CancelledException e) {
                throw e;
            } catch (Exception e) {
                fail++;
                failed.add(label + "：" + e.getMessage());
                log("　❌ " + e.getMessage(), Color.RED);
            }
        }
        final int fOk = ok, fSkip = skipped, fFail = fail;
        ui.post(() -> {
            progress.setProgress(total);
            statusView.setText(String.format(Locale.TAIWAN, "完成 %d 首　✅ 成功 %d　⏭ 略過 %d　⚠ 失敗 %d",
                    total, fOk, fSkip, fFail));
        });
        log("\n🏁 完成！成功 " + ok + "、略過 " + skipped + "、失敗 " + fail, Color.BLUE);
        if (!failed.isEmpty()) {
            log("沒抓到的歌曲：", Color.BLUE);
            for (String s : failed) log("・" + s, Color.rgb(200, 100, 0));
        }
    }

    /** 先用 MP3 標籤 (ID3) 的歌手/歌名，再用檔名「歌手 - 歌名」及反過來的順序。 */
    private MulanciClient.Result lookup(MulanciClient client, Mp3 f) throws Exception {
        List<String[]> cands = new ArrayList<>();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, f.uri);
            String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String ti = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (ar != null && ti != null && !ar.trim().isEmpty() && !ti.trim().isEmpty())
                cands.add(new String[]{ar.trim(), ti.trim()});
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        String base = f.name.replaceFirst("(?i)\\.mp3$", "");
        for (String[] g : MulanciClient.guessFromFilename(base)) {
            boolean dup = false;
            for (String[] c : cands)
                if (MulanciClient.norm(c[0]).equals(MulanciClient.norm(g[0]))
                        && MulanciClient.norm(c[1]).equals(MulanciClient.norm(g[1]))) dup = true;
            if (!dup) cands.add(g);
        }
        if (cands.isEmpty()) {
            MulanciClient.Result r = client.find(null, null);
            r.detail = "檔名不是「歌手 - 歌名」格式，MP3 也沒有歌手/歌名標籤";
            return r;
        }
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

    private String writeLrc(ContentResolver cr, Mp3 f, String lrc, boolean bom) throws Exception {
        String lrcName = f.name.replaceFirst("(?i)\\.mp3$", "") + ".lrc";
        Uri target = f.existingLrc;
        if (target == null) {
            target = DocumentsContract.createDocument(cr, f.parentDoc, "application/octet-stream", lrcName);
            if (target == null) throw new Exception("無法建立檔案 " + lrcName);
        }
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) throw new Exception("無法寫入 " + lrcName);
            if (bom) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            os.write(lrc.getBytes(StandardCharsets.UTF_8));
        }
        String actual = queryName(cr, target);
        return actual != null ? actual : lrcName;
    }

    private static String queryName(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    /** 遞迴列出資料夾中的 mp3，並記下同資料夾已存在的 .lrc。 */
    private void scan(ContentResolver cr, String docId, String rel, boolean recurse, List<Mp3> out) {
        if (cancelled) throw new MulanciClient.CancelledException();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        List<String[]> dirs = new ArrayList<>();
        List<String[]> mp3s = new ArrayList<>();
        Map<String, String> lrcByLower = new HashMap<>();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!name.startsWith(".")) dirs.add(new String[]{id, name});
                } else if (lower.endsWith(".mp3")) {
                    mp3s.add(new String[]{id, name});
                } else if (lower.endsWith(".lrc")) {
                    lrcByLower.put(lower, id);
                }
            }
        } catch (Exception e) {
            log("無法讀取資料夾 " + rel + "：" + e.getMessage(), Color.RED);
            return;
        }
        mp3s.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        for (String[] m : mp3s) {
            Mp3 f = new Mp3();
            f.name = m[1];
            f.dirLabel = rel;
            f.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, m[0]);
            f.parentDoc = parentDoc;
            String lrcKey = m[1].replaceFirst("(?i)\\.mp3$", "").toLowerCase(Locale.ROOT) + ".lrc";
            String lrcId = lrcByLower.get(lrcKey);
            if (lrcId != null) f.existingLrc = DocumentsContract.buildDocumentUriUsingTree(treeUri, lrcId);
            out.add(f);
        }
        if (recurse) {
            dirs.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            for (String[] d : dirs) scan(cr, d[0], rel + d[1] + "/", true, out);
        }
    }

    // ------------------------------------------------------------------ log

    private void log(String msg, int color) {
        ui.post(() -> {
            int start = logText.length();
            logText.append(msg).append("\n");
            logText.setSpan(new ForegroundColorSpan(color), start, logText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 避免記錄過長拖慢畫面
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
