package ru.argeda.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update: checks the site for a newer APK (versiya.json, or the APK itself as fallback),
 * offers to update, downloads to cache and hands the file to the system installer.
 */
class Obnovlenie {
    /** Main source: the latest GitHub release (public repo). Fallback: the site. */
    static final String GH = "https://github.com/antennashopru-web/argeda-ai-android/releases/latest/download/";
    static final String BASE = "https://ai.argeda.ru/prilozhenie/";
    static final String JSON_URL = BASE + "versiya.json";
    static final String APK_URL = BASE + "ARGEDA-AI.apk";
    static final long INTERVAL_MS = 20 * 60_000L;   // every launch/return, at most once per 20 min
    static final long PAUZA_MS = 3 * 3600_000L;      // after "Позже"

    private final Activity a;
    private final SharedPreferences sp;
    private volatile boolean busy;
    private File readyApk;
    private int readyCode;
    private String readyName = "";

    Obnovlenie(Activity a) { this.a = a; sp = a.getSharedPreferences("obnovlenie", 0); }

    int myCode() {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Called on each resume; does network work at most once per INTERVAL_MS (or when forced from the site). */
    void proverit(boolean force) {
        long last = sp.getLong("last", 0);
        if (!force && System.currentTimeMillis() - last < INTERVAL_MS) return;
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                sp.edit().putLong("last", System.currentTimeMillis()).apply();
                int mine = myCode();
                int code = 0; String name = ""; String url = APK_URL; String chto = "";
                String json = get(GH + "versiya.json");
                boolean fromGh = json != null;
                if (json == null) json = get(JSON_URL);
                if (json != null) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(json);
                        code = o.optInt("versionCode", 0); name = o.optString("versionName", "");
                        if (fromGh) url = GH + "ARGEDA-AI.apk";
                        else if (o.has("url")) url = o.getString("url").startsWith("http") ? o.getString("url") : "https://ai.argeda.ru" + o.getString("url");
                        chto = o.optString("chto", "");
                    } catch (Exception ignored) {}
                }
                File f = null;
                if (code == 0) {
                    // no versiya.json yet: fetch the APK itself (≈150 KB) and read its version
                    f = download(url);
                    if (f == null) { busy = false; return; }
                    PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                    if (pi == null) { busy = false; return; }
                    code = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
                    name = pi.versionName == null ? "" : pi.versionName;
                }
                if (code <= mine) { if (f != null) f.delete(); busy = false; return; }
                // "Позже" for this version: do not download it again during the pause (saves mobile data)
                if (sp.getInt("skip", 0) == code && System.currentTimeMillis() - sp.getLong("skipT", 0) < PAUZA_MS) { if (f != null) f.delete(); busy = false; return; }
                if (f == null && readyApk != null && readyCode == code && readyApk.exists() && proverkaFayla(readyApk, code)) f = readyApk;
                if (f == null) f = download(url);
                if (f == null) { busy = false; return; }
                // a cut connection leaves half a file: the installer would say "error parsing the package"
                if (!proverkaFayla(f, code)) { f.delete(); busy = false; return; }
                readyApk = f; readyCode = code; readyName = name;
                final String what = chto;
                a.runOnUiThread(() -> predlozhit(what));
            } finally { busy = false; }
        }, "obnovlenie").start();
    }

    private void predlozhit(String chto) {
        if (a.isFinishing() || readyApk == null) return;
        int skipped = sp.getInt("skip", 0);
        // "Позже" for this version: ask again 3 hours later
        if (skipped == readyCode && System.currentTimeMillis() - sp.getLong("skipT", 0) < PAUZA_MS) return;
        new AlertDialog.Builder(a)
                .setTitle("Обновление ARGEDA AI " + readyName)
                .setMessage((chto == null || chto.isEmpty() ? "Доступна новая версия приложения." : chto) + "\n\nУстановить сейчас? Это займёт несколько секунд.")
                .setPositiveButton("Обновить", (d, w) -> ustanovit())
                .setNegativeButton("Позже", (d, w) -> sp.edit().putInt("skip", readyCode).putLong("skipT", System.currentTimeMillis()).apply())
                .show();
    }

    void ustanovit() {
        if (readyApk == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Разрешите установку из ARGEDA AI и вернитесь в приложение", Toast.LENGTH_LONG).show();
            sp.edit().putBoolean("pending", true).apply();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        sp.edit().putBoolean("pending", false).apply();
        Uri u = CameraFileProvider.uriFor("update", readyApk);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { a.startActivity(i); } catch (Exception e) { Toast.makeText(a, "Не удалось запустить установку", Toast.LENGTH_SHORT).show(); }
    }

    /** After returning from the "unknown sources" settings screen. */
    void posleNastroek() {
        if (sp.getBoolean("pending", false) && readyApk != null && Build.VERSION.SDK_INT >= 26 && a.getPackageManager().canRequestPackageInstalls()) ustanovit();
    }

    /** The file is a whole APK of this app with the expected version. */
    private boolean proverkaFayla(File f, int code) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !a.getPackageName().equals(pi.packageName)) return false;
            int c = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            return c == code;
        } catch (Exception e) { return false; }
    }

    private String get(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "ArgedaApp-obnovlenie");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192]; int n; java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 65536) return null; }
                return o.toString("UTF-8");
            }
        } catch (Exception e) { return null; }
    }

    private File download(String url) {
        try {
            File dir = new File(a.getCacheDir(), "update"); dir.mkdirs();
            File f = new File(dir, "ARGEDA-AI.apk");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "ArgedaApp-obnovlenie");
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType() == null ? "" : c.getContentType();
            if (ct.contains("text/html")) return null;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[16384]; int n; long total = 0;
                while ((n = in.read(b)) > 0) { out.write(b, 0, n); total += n; if (total > 50_000_000L) return null; }
            }
            return f;
        } catch (Exception e) { return null; }
    }
}
