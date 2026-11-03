package ru.argeda.ai;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Offline shell: the app's own screen (assets/karkas/index.html) shown when the site does not answer.
 * It keeps working without the internet: sections, files saved from the site, snapshots of recently opened pages.
 */
class Karkas {
    static final String HOST = "ai.argeda.ru";
    private static final int MAX_FILES = 100, MAX_PAGES = 12;
    private static final String[] NE_SOHRANYAT = {"/api", "/vhod", "/oplata", "/registratsiya", "/vyhod", "/admin"};

    private final MainActivity a;
    final WebView view;
    private final SharedPreferences prefs;
    private final File dir;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable snimokZadacha;
    private boolean zagruzhen;
    private String sost = "proverka";

    Karkas(MainActivity a, WebView v) {
        this.a = a; this.view = v;
        prefs = a.getSharedPreferences("karkas", Context.MODE_PRIVATE);
        dir = new File(a.getFilesDir(), "stranitsy");
        dir.mkdirs();
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);          // assets are still readable; nothing else on disk
        s.setAllowContentAccess(true);        // thumbnails of saved files (content://)
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // muted example clips loop on their own
        v.setBackgroundColor(0xFF0B0C0E);
        v.addJavascriptInterface(new Most(), "ArgedaKarkas");
        v.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { return true; }
        });
    }

    boolean vidim() { return view.getVisibility() == View.VISIBLE; }

    /** Show the shell. sostoyanie: "net" — no connection, "proverka" — checking. */
    void pokazat(String sostoyanie) {
        sost = sostoyanie;
        if (!zagruzhen) { view.loadUrl("file:///android_asset/karkas/index.html"); zagruzhen = true; }
        else js("window.karkasObnovit&&window.karkasObnovit(" + JSONObject.quote(sostoyanie) + ")");
        view.setVisibility(View.VISIBLE);
    }

    void skryt() { if (zakrep) { sost = "ok"; js("window.karkasObnovit&&window.karkasObnovit('ok')"); return; } view.setVisibility(View.GONE); }
    void skrytVsegda() { zakrep = false; view.setVisibility(View.GONE); }

    /** Held open on purpose (text shared into the app): the user picks a section first, even if the site is up. */
    boolean zakrep;
    private String startEkran;
    /** Open one of the shell's own screens: glavnaya, sozdat, razdel (k), fayly, kabinet; text — prefill. */
    void ekran(String name, String k, String text) {
        try {
            startEkran = new JSONObject().put("n", name).put("k", k == null ? "" : k).put("t", text == null ? "" : text).toString();
        } catch (Exception e) { return; }
        if (zagruzhen) js("window.karkasEkran&&window.karkasEkran(" + startEkran + ")");
    }

    /** The site failed again while the shell is open: tell the page so the button comes back. */
    void neUdalos() { sost = "net"; if (vidim()) js("window.karkasObnovit&&window.karkasObnovit('net')"); }

    static boolean estSet(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.getActiveNetwork() != null;
        } catch (Exception e) { return true; }
    }

    private void js(String s) { view.post(() -> view.evaluateJavascript(s, null)); }

    // ---------- saved files journal ----------

    void zapisatFayl(Uri u, String mime, String name) {
        if (u == null) return;
        try {
            JSONArray old = new JSONArray(prefs.getString("fayly", "[]")), nov = new JSONArray();
            JSONObject f = new JSONObject().put("uri", u.toString()).put("mime", mime == null ? "" : mime)
                    .put("name", name == null ? "fayl" : name).put("t", System.currentTimeMillis());
            nov.put(f);
            for (int i = 0; i < old.length() && nov.length() < MAX_FILES; i++)
                if (!u.toString().equals(old.getJSONObject(i).optString("uri"))) nov.put(old.getJSONObject(i));
            prefs.edit().putString("fayly", nov.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---------- tasks prepared without the server ----------

    JSONArray zadaniya() { try { return new JSONArray(prefs.getString("zadaniya", "[]")); } catch (Exception e) { return new JSONArray(); } }
    void sohranitZadaniya(JSONArray a) { prefs.edit().putString("zadaniya", a.toString()).apply(); }
    JSONObject vzyatZadanie(int i) {
        JSONArray a = zadaniya(), b = new JSONArray(); JSONObject o = null;
        for (int j = 0; j < a.length(); j++) { if (j == i) o = a.optJSONObject(j); else b.put(a.opt(j)); }
        sohranitZadaniya(b); return o;
    }

    // ---------- page snapshots ----------

    /** Save a copy of the page a few seconds after it settles (so the content has rendered). */
    void zaplanirovatSnimok(WebView w, String url) {
        if (snimokZadacha != null) h.removeCallbacks(snimokZadacha);
        Uri u = url == null ? null : Uri.parse(url);
        if (u == null || !HOST.equals(u.getHost()) || !"https".equals(u.getScheme())) return;
        String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
        for (String p : NE_SOHRANYAT) if (path.startsWith(p)) return;
        snimokZadacha = () -> {
            if (w.getUrl() == null || !w.getUrl().equals(url) || w.getProgress() < 100) return;
            String title = w.getTitle() == null ? path : w.getTitle();
            String file = new File(dir, imya(path) + ".mht").getAbsolutePath();
            try {
                w.saveWebArchive(file, false, saved -> { if (saved != null) zapisatStranitsu(path, title, saved); });
            } catch (Exception ignored) {}
        };
        h.postDelayed(snimokZadacha, 4000);
    }

    private static String imya(String path) {
        String s = path.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "glavnaya" : s.length() > 80 ? s.substring(0, 80) : s;
    }

    private void zapisatStranitsu(String path, String title, String file) {
        try {
            JSONArray old = new JSONArray(prefs.getString("stranitsy", "[]")), nov = new JSONArray();
            nov.put(new JSONObject().put("path", path).put("title", title).put("file", new File(file).getName()).put("t", System.currentTimeMillis()));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (path.equals(o.optString("path"))) continue;
                if (nov.length() < MAX_PAGES) nov.put(o);
                else new File(dir, o.optString("file")).delete();
            }
            prefs.edit().putString("stranitsy", nov.toString()).apply();
        } catch (Exception ignored) {}
    }

    private File arhiv(String path) {
        try {
            JSONArray a = new JSONArray(prefs.getString("stranitsy", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (path.equals(o.optString("path"))) {
                    File f = new File(dir, o.optString("file"));
                    return f.exists() ? f : null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** A saved page opens in the second window, read-only: no scripts, links lead back to the live site. */
    private void otkrytArhiv(File f, String title) {
        WebView p = new WebView(a);
        WebSettings s = p.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        p.setBackgroundColor(0xFF0B0C0E);
        p.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u != null && HOST.equals(u.getHost())) {
                    String path = u.getPath() == null ? "/" : u.getPath();
                    File other = arhiv(path);
                    if (other != null) { w.loadUrl(Uri.fromFile(other).toString()); return true; }
                    a.zakrytOkno();
                    a.otkrytSayt(u.toString());
                    return true;
                }
                return true;
            }
        });
        a.otkrytOkno(p, "Сохранённая копия · " + title);
        p.loadUrl(Uri.fromFile(f).toString());
    }

    // ---------- bridge for the shell page ----------

    private class Most {
        @JavascriptInterface public String fayly() { return prefs.getString("fayly", "[]"); }
        @JavascriptInterface public String stranitsy() { return prefs.getString("stranitsy", "[]"); }
        @JavascriptInterface public boolean set() { return estSet(a); }
        @JavascriptInterface public String versiya() {
            try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }

        /** Try the site again; the shell hides itself once the page has loaded. */
        @JavascriptInterface public void povtorit() { a.runOnUiThread(a::povtoritSayt); }

        @JavascriptInterface public String sostoyanie() { return sost; }
        @JavascriptInterface public String startEkran() { String e = startEkran; startEkran = null; return e == null ? "" : e; }
        @JavascriptInterface public void otpustit() { a.runOnUiThread(() -> { zakrep = false; if ("ok".equals(sost)) skryt(); }); }

        /** A section of the live site; prompt (may be empty) is typed into the page's first text field. */
        @JavascriptInterface public void razdel(String path, String prompt) {
            if (path == null || !path.startsWith("/")) return;
            a.runOnUiThread(() -> a.otkrytRazdel(path, prompt));
        }

        @JavascriptInterface public String zadaniya() { return Karkas.this.zadaniya().toString(); }
        @JavascriptInterface public void dobavitZadanie(String k, String path, String text) {
            if (path == null || !path.startsWith("/") || text == null || text.trim().isEmpty()) return;
            try {
                JSONArray z = Karkas.this.zadaniya();
                if (z.length() >= 30) return;
                z.put(new JSONObject().put("k", k).put("path", path).put("text", text.trim()).put("t", System.currentTimeMillis()));
                sohranitZadaniya(z);
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void ubratZadanie(int i) { vzyatZadanie(i); }
        @JavascriptInterface public void otpravitZadanie(int i) {
            a.runOnUiThread(() -> {
                JSONObject o = vzyatZadanie(i);
                if (o != null) a.otkrytRazdel(o.optString("path", "/"), o.optString("text", ""));
            });
        }
        @JavascriptInterface public void pochta() {
            a.runOnUiThread(() -> {
                try { a.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:info@argeda.ru"))); }
                catch (Exception e) { Toast.makeText(a, "info@argeda.ru", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface public void kopiya(String path) {
            a.runOnUiThread(() -> {
                File f = arhiv(path);
                if (f != null) otkrytArhiv(f, path);
                else Toast.makeText(a, "Копии этой страницы нет — откройте её, когда сайт заработает", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface public void otkrytFayl(String uri, String mime) {
            a.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime == null || mime.isEmpty() ? "*/*" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(i);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(a, "Нет приложения, чтобы открыть файл", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(a, "Файл удалён или недоступен", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public void podelitsya(String uri, String mime) {
            a.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType(mime == null || mime.isEmpty() ? "*/*" : mime)
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(i, "Поделиться"));
                } catch (Exception e) { Toast.makeText(a, "Не получилось поделиться", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface public void galereya() {
            a.runOnUiThread(() -> {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW).setType("image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Toast.makeText(a, "Откройте «Галерея» → альбом ARGEDA AI", Toast.LENGTH_LONG).show(); }
            });
        }
    }
}
