package tw.lrc.downloader;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 透過 Android WebView（Chromium 瀏覽器核心）抓 mulanci.org 網頁。
 * mulanci.org 在 Cloudflare 後面，非瀏覽器的連線會被回 403；
 * 先讓 WebView 開啟首頁（必要時讓使用者完成人機驗證），再在頁面內用 fetch() 取同網域的網頁。
 * 所有 fetch() 呼叫都在背景執行緒，等待 UI 執行緒上的 WebView 回傳結果。
 */
public class WebFetcher implements MulanciClient.Fetcher {

    public interface UiListener {
        /** 需要使用者在 WebView 中完成 Cloudflare 驗證時顯示 / 隱藏 WebView。 */
        void showWebView(boolean show);
        void log(String msg);
    }

    private static final String HOME = MulanciClient.BASE + "/";

    private final WebView web;
    private final UiListener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Slot> pending = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile CountDownLatch pageLatch;
    private volatile boolean ready = false;
    private volatile MulanciClient.Cancel cancel = () -> false;

    private static class Slot {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int status;
        volatile String body;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public WebFetcher(WebView web, UiListener listener) {
        this.web = web;
        this.listener = listener;
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadsImagesAutomatically(false);
        ws.setBlockNetworkImage(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "LrcBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                CountDownLatch l = pageLatch;
                if (l != null) l.countDown();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 只允許停留在木蘭詞與 Cloudflare 驗證頁，避免廣告把頁面導走
                return !allowed(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 擋掉廣告、追蹤等第三方資源，省流量、加快速度
                if (allowed(request.getUrl())) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        });
    }

    public void setCancel(MulanciClient.Cancel c) { this.cancel = c; }

    private static boolean allowed(Uri u) {
        String h = u.getHost();
        if (h == null) return true;
        h = h.toLowerCase(java.util.Locale.ROOT);
        return h.equals("mulanci.org") || h.endsWith(".mulanci.org")
                || h.endsWith("cloudflare.com") || h.endsWith("cloudflareinsights.com");
    }

    private class Bridge {
        @JavascriptInterface
        public void done(int id, int status, String body) {
            Slot s = pending.remove(id);
            if (s != null) {
                s.status = status;
                s.body = body;
                s.latch.countDown();
            }
        }
    }

    // ------------------------------------------------------------------ 公開介面

    @Override
    public MulanciClient.Page fetch(String url) throws IOException {
        if (!ready) openHome();
        MulanciClient.Page p = fetchInPage(url);
        if (p.status == 403 || isChallenge(p.body)) {
            // Cloudflare 擋下：重新開首頁，必要時請使用者完成驗證，再試一次
            ready = false;
            openHome();
            p = fetchInPage(url);
        }
        return p;
    }

    // ------------------------------------------------------------------ 內部

    private static boolean isChallenge(String body) {
        return body != null && body.length() < 30000
                && (body.contains("challenge-platform") || body.contains("cf-chl") || body.contains("Just a moment"));
    }

    /** 在 WebView 開啟木蘭詞首頁，若遇到 Cloudflare 驗證就顯示出來請使用者完成。 */
    private void openHome() throws IOException {
        CountDownLatch l = new CountDownLatch(1);
        pageLatch = l;
        ui.post(() -> web.loadUrl(HOME));
        await(l, 60, "開啟木蘭詞首頁逾時，請檢查網路");
        sleepQuiet(1500);

        if (onChallengePage()) {
            listener.log("🛡 木蘭詞要求人機驗證：請在下方網頁完成「確認您是人類」，完成後會自動繼續（最多等 5 分鐘）");
            ui.post(() -> listener.showWebView(true));
            long end = System.currentTimeMillis() + 5 * 60_000;
            while (onChallengePage()) {
                if (System.currentTimeMillis() > end) {
                    ui.post(() -> listener.showWebView(false));
                    throw new IOException("未完成木蘭詞人機驗證");
                }
                sleepQuiet(2000);
            }
            ui.post(() -> listener.showWebView(false));
            listener.log("✅ 驗證完成，繼續下載");
        }
        ready = true;
    }

    private boolean onChallengePage() throws IOException {
        String r = eval("(function(){var t=document.title||'';var h=location.hostname||'';"
                + "return (h.indexOf('mulanci.org')<0||/just a moment|請稍候|稍候|attention required|cloudflare/i.test(t)"
                + "||!!document.querySelector('#challenge-form,#challenge-stage,.cf-turnstile,iframe[src*=\"challenges.cloudflare.com\"]'))?'1':'0';})()");
        return r != null && r.contains("1");
    }

    private MulanciClient.Page fetchInPage(String url) throws IOException {
        int id = seq.incrementAndGet();
        Slot s = new Slot();
        pending.put(id, s);
        String js = "(function(){fetch(" + JSONObject.quote(url) + ",{credentials:'include'})"
                + ".then(function(r){return r.text().then(function(t){LrcBridge.done(" + id + ",r.status,t);});})"
                + ".catch(function(e){LrcBridge.done(" + id + ",-1,String(e));});})();";
        ui.post(() -> web.evaluateJavascript(js, null));
        try {
            await(s.latch, 90, "讀取網頁逾時：" + url);
        } finally {
            pending.remove(id);
        }
        if (s.status == -1) throw new IOException("網路錯誤：" + s.body);
        return new MulanciClient.Page(s.status, s.body == null ? "" : s.body);
    }

    /** 在 UI 執行緒執行 JavaScript 並同步取得結果（JSON 字串）。 */
    private String eval(String js) throws IOException {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        ui.post(() -> web.evaluateJavascript(js, v -> { out.set(v); l.countDown(); }));
        await(l, 20, "WebView 沒有回應");
        return out.get();
    }

    private void await(CountDownLatch l, int seconds, String err) throws IOException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        try {
            while (!l.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel.isCancelled()) throw new MulanciClient.CancelledException();
                if (System.currentTimeMillis() > end) throw new IOException(err);
            }
        } catch (InterruptedException e) {
            throw new MulanciClient.CancelledException();
        }
    }

    private void sleepQuiet(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel.isCancelled()) throw new MulanciClient.CancelledException();
            try { Thread.sleep(200); } catch (InterruptedException e) { throw new MulanciClient.CancelledException(); }
        }
    }
}
