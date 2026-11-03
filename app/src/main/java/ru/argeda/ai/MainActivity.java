package ru.argeda.ai;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME = "https://ai.argeda.ru/?source=app";
    private static final String HOST = "ai.argeda.ru";
    private static final int RC_FILE = 1;
    private static final int RC_PERM_WEB = 2;
    private static final int RC_PERM_CAMERA = 3;

    private WebView web;
    private ProgressBar progress;
    private Karkas karkas;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private PermissionRequest pendingWebPermission;
    private boolean loadFailed;
    private boolean stranitsaGotova;
    private final android.os.Handler zavis = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The site did not answer in 12 s (45 s if bytes are coming in): show the own screen instead of an empty page. */
    private long zagruzkaS;
    private final Runnable zavisCheck = new Runnable() {
        @Override public void run() {
            if (stranitsaGotova) return;
            if (web.getProgress() >= 25 && System.currentTimeMillis() - zagruzkaS < 45000) { zavis.postDelayed(this, 5000); return; }
            loadFailed = true;
            web.stopLoading();
            progress.setVisibility(View.GONE);
            karkas.pokazat("net");
            karkas.neUdalos();
        }
    };
    private Obnovlenie obnovlenie;
    private String hookJs = "";
    private WebChromeClient.FileChooserParams pendingChooser;
    private boolean cameraAsked;

    /** Called from page JS (only pages of our host get the hook). */
    private class Bridge {
        private final WebView w;
        Bridge(WebView w) { this.w = w; }
        @android.webkit.JavascriptInterface
        public void saveFile(String base64, String mime, String name) {
            if (!onOurHost()) return;
            byte[] data;
            try { data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT); } catch (Exception e) { saveFailed("base64"); return; }
            final String safe = name == null ? "fayl" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            final String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingSave = new Object[]{data, type, safe};
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_PERM_STORAGE);
                    return;
                }
                writeToDownloads(data, type, safe);
            });
        }
        @android.webkit.JavascriptInterface
        public void pleerPlay(String url, String title, int posMs, int durMs) {
            if (!onOurHost()) return;
            runOnUiThread(() -> { if (karkas != null) karkas.pleerNash = false; askNotifications(); PleerService.send(MainActivity.this, PleerService.A_PLAY, title, posMs, durMs); });
        }
        @android.webkit.JavascriptInterface
        public void pleerPause(int posMs) { if (onOurHost()) runOnUiThread(() -> PleerService.send(MainActivity.this, PleerService.A_PAUSE, null, posMs, 0)); }
        @android.webkit.JavascriptInterface
        public void pleerSeek(int posMs) { if (onOurHost()) runOnUiThread(() -> PleerService.send(MainActivity.this, PleerService.A_SEEK, null, posMs, 0)); }
        @android.webkit.JavascriptInterface
        public void share(String title, String text, String url) {
            if (!onOurHost()) return;
            String body = (text == null ? "" : text) + (url == null || url.isEmpty() ? "" : (text == null || text.isEmpty() ? "" : "\n") + url);
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, body);
            runOnUiThread(() -> openExternal(Intent.createChooser(i, "Поделиться")));
        }
        private boolean onOurHost() {
            // WebView methods may only be called on the UI thread; bridge calls arrive on the JavaBridge thread
            final String[] box = {null};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            w.post(() -> { try { box[0] = w.getUrl(); } finally { done.countDown(); } });
            try { if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) return false; } catch (InterruptedException e) { return false; }
            String cur = box[0];
            if (cur == null) return false;
            if (cur.startsWith("about:blank")) return true; // window.open("") + document.write from our page
            return HOST.equals(Uri.parse(cur).getHost());
        }
        /** http(s) link with the download attribute: stream it through DownloadManager with the session cookie. */
        @android.webkit.JavascriptInterface
        public void skachat(String url, String name) {
            if (!onOurHost() || url == null || !url.startsWith("https://" + HOST + "/")) return;
            final String n = name == null ? "" : name;
            runOnUiThread(() -> download(w, url, w.getSettings().getUserAgentString(), n.isEmpty() ? null : "attachment; filename=\"" + n + "\"", null));
        }
        /** navigator.share({files}) */
        @android.webkit.JavascriptInterface
        public void shareFile(String base64, String mime, String name, String text) {
            if (!onOurHost()) return;
            try {
                File dir = new File(getCacheDir(), "share"); dir.mkdirs();
                for (File old : dir.listFiles() == null ? new File[0] : dir.listFiles()) if (System.currentTimeMillis() - old.lastModified() > 3600_000) old.delete();
                String safe = (name == null || name.isEmpty() ? "fayl" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                File f = new File(dir, safe);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)); }
                Uri u = CameraFileProvider.uriFor("share", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime == null || mime.isEmpty() ? "*/*" : mime)
                        .putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, text == null ? "" : text)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> openExternal(Intent.createChooser(i, "Поделиться")));
            } catch (Exception e) { saveFailed("share"); }
        }
        /** The site may ask: ArgedaApp.versiya() -> "1.6", ArgedaApp.obnovit() -> check now. */
        @android.webkit.JavascriptInterface
        public String versiya() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
        @android.webkit.JavascriptInterface
        public void obnovit() { if (onOurHost()) runOnUiThread(() -> { Toast.makeText(MainActivity.this, "Проверяю обновление…", Toast.LENGTH_SHORT).show(); obnovlenie.proverit(true); }); }
        /** window.print() */
        @android.webkit.JavascriptInterface
        public void print() {
            if (!onOurHost()) return;
            runOnUiThread(() -> {
                android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
                pm.print("ARGEDA AI", w.createPrintDocumentAdapter("ARGEDA AI"), new android.print.PrintAttributes.Builder().build());
            });
        }
        @android.webkit.JavascriptInterface
        public void saveFailed(String why) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show());
        }
    }
    private Object[] pendingSave;
    private boolean notifAsked;

    void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    /** Notification buttons -> page player, so the site UI stays in sync. */
    void pleerMirror(String what, int ms) {
        // send to both pages: whichever one is playing reacts; on pause/stop also silence every media element
        String q = org.json.JSONObject.quote(what);
        boolean stop = "pause".equals(what) || "stop".equals(what);
        String tiho = stop ? "try{document.querySelectorAll('audio,video').forEach(function(m){m.pause()})}catch(e){}" : "";
        runOnUiThread(() -> {
            try { ((WebView) findViewById(R.id.offline)).evaluateJavascript("window.karkasPleer&&window.karkasPleer(" + q + "," + ms + ");" + tiho, null); } catch (Exception ignored) {}
            try { web.evaluateJavascript("window.__argedaPleerMirror&&window.__argedaPleerMirror(" + q + "," + ms + ");" + tiho, null); } catch (Exception ignored) {}
        });
    }
    private static final int RC_PERM_STORAGE = 4;

    private void writeToDownloads(byte[] data, String mime, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                boolean img = mime.startsWith("image/"), vid = mime.startsWith("video/"), aud = mime.startsWith("audio/");
                String dir = img ? Environment.DIRECTORY_PICTURES : vid ? Environment.DIRECTORY_MOVIES : aud ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOWNLOADS;
                v.put(MediaStore.Downloads.RELATIVE_PATH, dir + "/ARGEDA AI");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri coll = img ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : vid ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : aud ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri u = getContentResolver().insert(coll, v);
                if (u == null) throw new java.io.IOException("insert");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(u)) { out.write(data); }
                v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(u, v, null, null);
                karkas.zapisatFayl(u, mime, name);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ARGEDA AI");
                dir.mkdirs();
                File f = new File(dir, name);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(data); }
                DownloadManager dm0 = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                long did = dm0.addCompletedDownload(name, name, true, mime, f.getAbsolutePath(), f.length(), true);
                karkas.zapisatFayl(dm0.getUriForDownloadedFile(did), mime, name);
            }
            String where = mime.startsWith("image/") || mime.startsWith("video/") ? "в галерею (ARGEDA AI)" : "в Загрузки / ARGEDA AI";
            Toast.makeText(this, "Сохранено " + where + ": " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show();
        }
    }

    /** Android 15 draws under the system bars: pad the content by bars + keyboard ourselves. */
    private int imeBylo = 0;
    private void applyInsets() {
        View root = findViewById(android.R.id.content);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                if (imeBylo > 0 && ime.bottom == 0) {   // keyboard closed: let the offline page re-pin its sticky header
                    View off = findViewById(R.id.offline);
                    if (off instanceof WebView) off.postDelayed(() -> ((WebView) off).evaluateJavascript("window.karkasPerekladka&&karkasPerekladka()", null), 250);
                }
                if ((imeBylo > 0) != (ime.bottom > 0)) {   // tell the offline page: hide menu and mini player while typing
                    View off = findViewById(R.id.offline);
                    final boolean otkr = ime.bottom > 0;
                    if (off instanceof WebView) ((WebView) off).evaluateJavascript("window.karkasKlav&&karkasKlav(" + otkr + ")", null);
                }
                imeBylo = ime.bottom;
                return android.view.WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); PleerService.ui = this;
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        karkas = new Karkas(this, findViewById(R.id.offline));
        new Pryamoy(this, findViewById(R.id.offline));
        ((WebView) findViewById(R.id.offline)).setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb; openFileChooser(params); return true;
            }
        });

        setupWebView(web);
        WebSettings s = web.getSettings();
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager cm = CookieManager.getInstance();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (handleUrl(req.getUrl())) return true;
                return softNavigate(view, req);
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false;
                stranitsaGotova = false;
                progress.setVisibility(View.VISIBLE);
                zavis.removeCallbacks(zavisCheck);
                zagruzkaS = System.currentTimeMillis();
                zavis.postDelayed(zavisCheck, 12000);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                zavis.removeCallbacks(zavisCheck);
                progress.setVisibility(View.GONE);
                if (url != null && url.startsWith("https://" + HOST)) view.evaluateJavascript(hookJs, null);
                cm.flush();
                if (loadFailed) { karkas.neUdalos(); return; }
                // a blank page (server half-alive, empty answer) must not leave a black screen
                proveritSoderzhimoe(view, url, 0);
            }
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (!loadFailed) karkas.zaplanirovatSnimok(view, url);
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    loadFailed = true;
                    karkas.pokazat("net");
                }
            }
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                if (req.isForMainFrame() && resp != null && resp.getStatusCode() >= 500) {
                    loadFailed = true;
                    karkas.pokazat("net");
                }
            }
        });

        // (HTTP 5xx of the main page is shown by the site itself; only network failures show the offline screen)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (foto != null) {
                    Uri u = foto; foto = null;
                    cb.onReceiveValue(new Uri[]{u});
                    Toast.makeText(MainActivity.this, "Прикреплено фото из «Поделиться»", Toast.LENGTH_SHORT).show();
                    return true;
                }
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                openFileChooser(params);
                return true;
            }
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                List<String> need = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
                }
                List<String> missing = new ArrayList<>();
                for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
                if (missing.isEmpty()) { request.grant(request.getResources()); return; }
                pendingWebPermission = request;
                requestPermissions(missing.toArray(new String[0]), RC_PERM_WEB);
            }
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // target=_blank / window.open: a second window over the page (the form underneath keeps its state)
                WebView p = new WebView(MainActivity.this);
                setupWebView(p);
                p.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        if (handleUrl(req.getUrl())) { closePopup(); return true; }
                        return false;
                    }
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        popupTitle.setText(v.getTitle() == null || v.getTitle().isEmpty() ? url : v.getTitle());
                        if (url != null && (url.startsWith("https://" + HOST) || url.startsWith("about:blank"))) v.evaluateJavascript(hookJs, null);
                    }
                });
                p.setWebChromeClient(new WebChromeClient() {
                    @Override public void onCloseWindow(WebView w) { closePopup(); }
                    @Override public void onReceivedTitle(WebView w, String t) { if (t != null && !t.isEmpty()) popupTitle.setText(t); }
                    @Override
                    public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                        if (fileCallback != null) fileCallback.onReceiveValue(null);
                        fileCallback = cb; openFileChooser(params); return true;
                    }
                });
                p.setDownloadListener((url, ua, cd, mime, len) -> { download(p, url, ua, cd, mime); if (!url.startsWith("blob:")) closePopup(); });
                openPopup(p);
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(p);
                resultMsg.sendToTarget();
                return true;
            }
        });

        popup = findViewById(R.id.popup);
        popupTitle = findViewById(R.id.popup_title);
        findViewById(R.id.popup_close).setOnClickListener(v -> closePopup());
        try (java.io.InputStream in = getAssets().open("skachivanie.js")) {
            byte[] b = new byte[in.available()]; int n = in.read(b); hookJs = new String(b, 0, n, "UTF-8");
        } catch (java.io.IOException e) { hookJs = ""; }
        applyInsets();
        obnovlenie = new Obnovlenie(this);
        slushatSet();
        android.content.IntentFilter f = new android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED); else registerReceiver(downloadDone, f);

        web.setDownloadListener((url, ua, contentDisposition, mime, len) -> download(web, url, ua, contentDisposition, mime));

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            // own screen from the first second, instead of a black window while the site answers
            karkas.pokazat(Karkas.estSet(this) ? "proverka" : "net");
            String start = vhod(getIntent());
            web.loadUrl(start != null ? start : HOME);
        }
    }

    /**
     * While music plays, a full page load would kill the page's player. Our site is a Next.js app, so an
     * in-site link is handed to its client router instead (same as a Link click): the player keeps going.
     */
    private boolean softNavigate(WebView view, WebResourceRequest req) {
        if (!PleerService.playing || !req.isForMainFrame() || req.isRedirect() || !"GET".equalsIgnoreCase(req.getMethod())) return false;
        Uri u = req.getUrl();
        if (!HOST.equals(u.getHost()) || !"https".equals(u.getScheme())) return false;
        String path = u.getPath() == null ? "/" : u.getPath();
        if (path.startsWith("/api/") || path.endsWith(".html") || path.startsWith("/vhod") || path.startsWith("/vyhod")
                || path.startsWith("/registratsiya") || path.startsWith("/oplata") || path.contains("/.well-known/")
                || path.matches(".*\\.(pdf|zip|mp3|mp4|wav|png|jpe?g|webp|csv|xlsx?)$")) return false;
        String cur = view.getUrl();
        if (cur == null || !cur.startsWith("https://" + HOST)) return false;
        String target = u.toString();
        String js = "(function(){try{if(window.next&&window.next.router&&window.next.router.push){window.next.router.push("
                + org.json.JSONObject.quote(target) + ");return 'ok'}}catch(e){}return 'net'})()";
        view.evaluateJavascript(js, r -> { if (r == null || !r.contains("ok")) view.loadUrl(target); });
        return true;
    }

    private void setupWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ArgedaApp/4.1");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(w), "ArgedaApp");
        w.setBackgroundColor(0xFF0B0C0E);
    }

    private WebView popupWeb;
    private View popup;
    private android.widget.TextView popupTitle;

    void openPopup(WebView p) {
        closePopup();
        popupWeb = p;
        ((android.widget.FrameLayout) findViewById(R.id.popup_body)).addView(p, new android.widget.FrameLayout.LayoutParams(-1, -1));
        popupTitle.setText("");
        popup.setVisibility(View.VISIBLE);
    }

    void closePopup() {
        if (popupWeb == null) return;
        WebView p = popupWeb; popupWeb = null;
        ((android.widget.FrameLayout) findViewById(R.id.popup_body)).removeView(p);
        popup.setVisibility(View.GONE);
        p.post(p::destroy);
    }

    /** The page loaded: hide the shell only if the page really has something on it. */
    private void proveritSoderzhimoe(WebView view, String url, int popytka) {
        view.evaluateJavascript("(function(){var b=document.body;return b?(b.innerText||'').trim().length+b.querySelectorAll('img,video,canvas,svg').length:0})()", r -> {
            int n = 0;
            try { n = Integer.parseInt(r == null ? "0" : r.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            if (loadFailed) return;
            if (n >= 20) {
                stranitsaGotova = true;
                boolean bylKarkas = karkas.vidim();
                karkas.skryt();
                karkas.zaplanirovatSnimok(view, url);
                if (zhdetTekst != null) vstavitTekst(view, url);
                else if (bylKarkas) predlozhitZadaniya();
            } else if (popytka < 4) {
                view.postDelayed(() -> proveritSoderzhimoe(view, url, popytka + 1), 1500);
            } else {
                stranitsaGotova = true;
                loadFailed = true;
                karkas.pokazat("net");
                karkas.neUdalos();
            }
        });
    }

    // ---------- called by the offline shell (Karkas) ----------
    void povtoritSayt() {
        karkas.pokazat("proverka");
        String u = web.getUrl();
        if (u == null || !u.startsWith("https://" + HOST)) web.loadUrl(HOME); else web.reload();
    }
    boolean saytLezhit() { return loadFailed; }
    void otkrytSayt(String url) { karkas.pokazat("proverka"); web.loadUrl(url); }

    /** Text prepared in the app, typed into the section's first text field once the page is up. */
    private String zhdetTekst, zhdetPut;
    void otkrytRazdel(String path, String prompt) {
        karkas.zakrep = false;
        zhdetTekst = prompt == null || prompt.trim().isEmpty() ? null : prompt.trim();
        zhdetPut = path;
        otkrytSayt("https://" + HOST + path + (path.contains("?") ? "&" : "?") + "source=app");
    }
    private void vstavitTekst(WebView view, String url) {
        if (zhdetTekst == null) return;
        Uri u = Uri.parse(url);
        String put = zhdetPut == null ? "" : zhdetPut.split("\\?")[0];
        if (u.getPath() == null || !u.getPath().startsWith(put)) return;
        String t = zhdetTekst; zhdetTekst = null;
        view.evaluateJavascript("(function(t){var n=0;function go(){var el=document.querySelector('textarea')||document.querySelector('input[type=text],input:not([type])');"
                + "if(!el){if(++n<20)setTimeout(go,500);return}"
                + "var p=el.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "Object.getOwnPropertyDescriptor(p,'value').set.call(el,t);el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.scrollIntoView({block:'center'});el.focus()}go()})(" + org.json.JSONObject.quote(t) + ")", null);
    }

    /** The site is back: offer the tasks prepared while it was down. */
    private boolean zadaniyaPredlozheny;
    private void predlozhitZadaniya() {
        org.json.JSONArray z = karkas.zadaniya();
        if (z.length() == 0 || zadaniyaPredlozheny) return;
        zadaniyaPredlozheny = true;
        String[] items = new String[z.length()];
        for (int i = 0; i < z.length(); i++) {
            String t = z.optJSONObject(i).optString("text");
            items[i] = t.length() > 70 ? t.substring(0, 70) + "…" : t;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Сервер снова работает — отправить задание?")
                .setItems(items, (d, i) -> {
                    org.json.JSONObject o = karkas.vzyatZadanie(i);
                    if (o != null) otkrytRazdel(o.optString("path", "/"), o.optString("text", ""));
                    zadaniyaPredlozheny = false;
                })
                .setNegativeButton("Позже", null)
                .show();
    }
    void otkrytOkno(WebView p, String title) { openPopup(p); popupTitle.setText(title); }
    void zakrytOkno() { closePopup(); }

    /** The connection came back while the shell is shown: try the site by itself. */
    private android.net.ConnectivityManager.NetworkCallback setVernulas;
    private void slushatSet() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            setVernulas = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    web.postDelayed(() -> { if (karkas.vidim()) povtoritSayt(); }, 1500);
                }
            };
            cm.registerDefaultNetworkCallback(setVernulas);
        } catch (Exception ignored) {}
    }

    /** true = handled outside the WebView; false = let WebView load it. */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (scheme.equals("https") || scheme.equals("http")) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            // everything (site, bank, 3-D Secure pages) stays inside; only known foreign services go out
            if (isExternalHost(host)) { openExternal(new Intent(Intent.ACTION_VIEW, uri)); return true; }
            return false;
        }
        if (scheme.equals("intent")) {
            try {
                Intent i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null); i.setSelector(null);
                if (getPackageManager().resolveActivity(i, 0) != null) { startActivity(i); return true; }
                String fallback = i.getStringExtra("browser_fallback_url");
                if (fallback != null) web.loadUrl(fallback);
            } catch (Exception ignored) {}
            return true;
        }
        if (scheme.equals("javascript") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("about")) return false;
        // tel:, mailto:, tg:, bank apps (sbp, bank100000000004...), etc.
        openExternal(new Intent(Intent.ACTION_VIEW, uri));
        return true;
    }

    private static final String[] EXTERNAL_HOSTS = {
            "t.me", "telegram.me", "telegram.org", "wildberries.ru", "wb.ru", "ozon.ru", "market.yandex.ru",
            "youtube.com", "youtu.be", "vk.com", "rutube.ru", "dzen.ru", "argeda.ru", "crm.argeda.ru",
            "play.google.com", "apps.rustore.ru", "rustore.ru", "maps.google.com", "yandex.ru", "2gis.ru"
    };

    private boolean isExternalHost(String h) {
        if (h.equals(HOST)) return false;
        for (String e : EXTERNAL_HOSTS) {
            if (e.equals("argeda.ru") && h.equals("ai.argeda.ru")) continue;
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    private void openExternal(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show(); }
    }

    private final java.util.Set<Long> openWhenDone = new java.util.HashSet<>();
    private final java.util.Map<Long, String> nashi = new java.util.HashMap<>();
    private final android.content.BroadcastReceiver downloadDone = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            String nm = nashi.remove(id);
            if (nm == null) return;
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri u = dm.getUriForDownloadedFile(id);
            String t = dm.getMimeTypeForDownloadedFile(id);
            if (u == null) { openWhenDone.remove(id); return; }
            karkas.zapisatFayl(u, t, nm);
            if (!openWhenDone.remove(id)) return;
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, t == null ? "application/pdf" : t).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (t != null && t.contains("android.package-archive") && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(MainActivity.this, "Разрешите установку из ARGEDA AI, затем откройте файл из «Загрузок»", Toast.LENGTH_LONG).show();
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
            try { startActivity(v); } catch (ActivityNotFoundException e) { Toast.makeText(MainActivity.this, "Файл сохранён в «Загрузки»: нет приложения, чтобы его открыть", Toast.LENGTH_LONG).show(); }
        }
    };

    void proveritObnovlenie() {
        if (obnovlenie == null) return;
        Toast.makeText(this, "Проверяю обновление…", Toast.LENGTH_SHORT).show();
        obnovlenie.proverit(true);
    }

    void download(WebView owner, String url, String ua, String contentDisposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String js = "(function(){fetch(" + org.json.JSONObject.quote(url) + ").then(function(x){return x.blob()}).then(function(b){"
                        + "var r=new FileReader();r.onload=function(){var s=r.result;ArgedaApp.saveFile(s.slice(s.indexOf(',')+1),b.type||" + org.json.JSONObject.quote(mime == null ? "" : mime) + "," + org.json.JSONObject.quote(name) + ")};r.readAsDataURL(b)})"
                        + ".catch(function(e){ArgedaApp.saveFailed(String(e))})})()";
                owner.evaluateJavascript(js, null);
                return;
            }
            String m = mime == null ? "" : mime;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (!m.isEmpty()) r.setMimeType(m);
            r.addRequestHeader("User-Agent", ua);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String dir = m.startsWith("image/") ? Environment.DIRECTORY_PICTURES : m.startsWith("video/") ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
            r.setDestinationInExternalPublicDir(dir, "ARGEDA AI/" + name);
            r.allowScanningByMediaScanner();
            long id = ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            nashi.put(id, name);
            String ml = m.toLowerCase();
            boolean doc = ml.contains("pdf") || name.toLowerCase().endsWith(".pdf") || ml.contains("msword") || ml.contains("officedocument") || ml.contains("ms-excel")
                    || ml.contains("android.package-archive") || name.toLowerCase().endsWith(".apk");
            if (doc) openWhenDone.add(id);
            Toast.makeText(this, doc ? "Открываю документ…" : getString(R.string.download_started), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private static boolean allStartWith(List<String> l, String p) { for (String s : l) if (!s.startsWith(p)) return false; return true; }

    private static String mimeForExtension(String ext) {
        if (ext.startsWith(".")) ext = ext.substring(1);
        switch (ext) {
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls": return "application/vnd.ms-excel";
            case "csv": return "text/comma-separated-values";
            case "txt": return "text/plain";
            case "pdf": return "application/pdf";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc": return "application/msword";
            case "json": return "application/json";
            case "zip": return "application/zip";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "ogg": return "audio/ogg";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            default: return null;
        }
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        if (!cameraAsked && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraAsked = true;
            pendingChooser = params;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, RC_PERM_CAMERA);
            return;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        String[] types = params.getAcceptTypes();
        List<String> mimes = new ArrayList<>();
        boolean unknown = false;
        if (types != null) for (String raw : types) {
            if (raw == null) continue;
            for (String t : raw.split(",")) {
                t = t.trim().toLowerCase();
                if (t.isEmpty()) continue;
                if (t.contains("/")) { if (!mimes.contains(t)) mimes.add(t); continue; }
                String m = mimeForExtension(t);
                if (m == null) unknown = true; else if (!mimes.contains(m)) mimes.add(m);
            }
        }
        boolean onlyImages = !unknown && !mimes.isEmpty() && allStartWith(mimes, "image/");
        pick.setType(onlyImages ? "image/*" : "*/*");
        if (!unknown && mimes.size() > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        else if (!unknown && mimes.size() == 1) pick.setType(mimes.get(0));
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Intent chooser = Intent.createChooser(pick, getString(R.string.choose_file));
        List<Intent> extras = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            try {
                File dir = new File(getCacheDir(), "camera"); dir.mkdirs();
                File f = new File(dir, "foto-" + System.currentTimeMillis() + ".jpg");
                cameraUri = CameraFileProvider.uriFor(f);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                extras.add(cam);
            } catch (Exception ignored) { cameraUri = null; }
        }
        if (!extras.isEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toArray(new Intent[0]));
        try { startActivityForResult(chooser, RC_FILE); }
        catch (ActivityNotFoundException e) { fileCallback.onReceiveValue(null); fileCallback = null; }
    }

    @Override
    protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (rc != RC_FILE || fileCallback == null) return;
        Uri[] out = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                out = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
        cameraUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == RC_PERM_WEB && pendingWebPermission != null) {
            boolean all = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) all = false;
            if (all) pendingWebPermission.grant(pendingWebPermission.getResources()); else pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (rc == RC_PERM_CAMERA && pendingChooser != null) {
            WebChromeClient.FileChooserParams p = pendingChooser; pendingChooser = null;
            openFileChooser(p);
        }
        if (rc == RC_PERM_STORAGE && pendingSave != null) {
            Object[] p = pendingSave; pendingSave = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) writeToDownloads((byte[]) p[0], (String) p[1], (String) p[2]);
            else Toast.makeText(this, "Без разрешения файл сохранить нельзя", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String u = vhod(intent);
        if (u != null) otkrytSayt(u);
        else if (karkas.zakrep) karkas.pokazat(loadFailed ? "net" : "ok");
    }

    // ---------- icon shortcuts and «Поделиться → ARGEDA AI» ----------
    private Uri foto; // a photo shared into the app: handed to the site's next file picker

    /** @return the site address to open, or null to stay where we are */
    private String vhod(Intent i) {
        if (i == null) return null;
        String act = i.getAction();
        if ("ru.argeda.ai.RAZDEL".equals(act)) {
            String put = i.getStringExtra("put");
            karkas.ekran(i.getStringExtra("ekran"), i.getStringExtra("k"), null);
            return put != null && put.startsWith("/") ? "https://" + HOST + put + "?source=app" : null;
        }
        if (Intent.ACTION_SEND.equals(act)) {
            String type = i.getType() == null ? "" : i.getType();
            if (type.startsWith("image/")) {
                Uri src = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (src == null) return null;
                try {
                    File dir = new File(getCacheDir(), "camera"); dir.mkdirs();
                    File f = new File(dir, "podelilis-" + System.currentTimeMillis() + ".jpg");
                    try (java.io.InputStream in = getContentResolver().openInputStream(src); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] b = new byte[16384]; int n; long total = 0;
                        while ((n = in.read(b)) > 0) { out.write(b, 0, n); total += n; if (total > 40_000_000L) throw new java.io.IOException("big"); }
                    }
                    foto = CameraFileProvider.uriFor(f);
                    Toast.makeText(this, "Фото прикрепится, когда раздел попросит файл", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Не удалось взять фото", Toast.LENGTH_SHORT).show();
                    return null;
                }
                karkas.ekran("razdel", "kartinki", null);
                return "https://" + HOST + "/kartinki?source=app";
            }
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null || text.trim().isEmpty()) return null;
            text = text.trim();
            Uri asUrl = Uri.parse(text);
            if (HOST.equals(asUrl.getHost()) && text.indexOf(' ') < 0) return text;
            karkas.zakrep = true;
            karkas.ekran("sozdat", null, text);
            return null;
        }
        Uri data = i.getData();
        if (data != null && HOST.equals(data.getHost())) return data.toString();
        return null;
    }

    @Override
    public void onBackPressed() {
        if (popupWeb != null) { if (popupWeb.canGoBack()) popupWeb.goBack(); else closePopup(); return; }
        if (karkas.vidim()) {
            // inside the own screens: step back to «Главная» first, then leave the app
            karkas.view.evaluateJavascript("window.karkasNazad?window.karkasNazad():'net'", r -> {
                if (r != null && r.contains("ok")) return;
                if (karkas.zakrep && stranitsaGotova && !loadFailed) karkas.skrytVsegda(); else finish();
            });
            return;
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override
    protected void onResume() {
        super.onResume(); PleerService.ui = this;
        if (obnovlenie != null) { obnovlenie.posleNastroek(); obnovlenie.proverit(false); }
    }
    @Override
    protected void onDestroy() {
        if (PleerService.ui == this) PleerService.ui = null;
        // app closed: music stops with it
        PleerService.send(this, PleerService.A_STOP_TIHO, null, 0, 0);
        try { WebView off = findViewById(R.id.offline); off.evaluateJavascript("window.karkasPleer&&window.karkasPleer('pause',0)", null); off.destroy(); } catch (Exception ignored) {}
        try { unregisterReceiver(downloadDone); } catch (Exception ignored) {}
        try { if (setVernulas != null) ((android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(setVernulas); } catch (Exception ignored) {}
        closePopup(); web.destroy(); super.onDestroy();
    }
}
