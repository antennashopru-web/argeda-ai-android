package ru.argeda.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: checks the site for a newer APK (versiya.json, or the APK itself as fallback),
 * offers to update, downloads to cache and hands the file to the system installer.
 */
class Obnovlenie(private val a: Activity) {
    companion object {
        /** Main source: the latest GitHub release (public repo). Fallback: the site. */
        const val GH = "https://github.com/antennashopru-web/argeda-ai-android/releases/latest/download/"
        const val BASE = "https://ai.argeda.ru/prilozhenie/"
        const val JSON_URL = BASE + "versiya.json"
        const val APK_URL = BASE + "ARGEDA-AI.apk"
        const val INTERVAL_MS = 20 * 60_000L   // every launch/return, at most once per 20 min
        const val PAUZA_MS = 3 * 3600_000L      // after "Позже"

        @Suppress("DEPRECATION")
        private fun kod(pi: PackageInfo): Int = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    private val sp: SharedPreferences = a.getSharedPreferences("obnovlenie", 0)
    @Volatile private var busy = false
    @Volatile private var readyApk: File? = null
    @Volatile private var readyCode = 0
    @Volatile private var readyName = ""

    fun myCode(): Int = try { kod(a.packageManager.getPackageInfo(a.packageName, 0)) } catch (e: Exception) { 0 }

    /** Called on each resume; does network work at most once per INTERVAL_MS (or when forced from the site). */
    fun proverit(force: Boolean) {
        val last = sp.getLong("last", 0)
        if (!force && System.currentTimeMillis() - last < INTERVAL_MS) return
        if (busy) return
        busy = true
        Thread({
            try { rabota() } finally { busy = false }
        }, "obnovlenie").start()
    }

    private fun rabota() {
        sp.edit().putLong("last", System.currentTimeMillis()).apply()
        val mine = myCode()
        var code = 0; var name = ""; var url = APK_URL; var chto = ""
        var json = get(GH + "versiya.json")
        val fromGh = json != null
        if (json == null) json = get(JSON_URL)
        if (json != null) {
            try {
                val o = JSONObject(json)
                code = o.optInt("versionCode", 0); name = o.optString("versionName", "")
                if (fromGh) url = GH + "ARGEDA-AI.apk"
                else if (o.has("url")) { val u = o.getString("url"); url = if (u.startsWith("http")) u else "https://ai.argeda.ru$u" }
                chto = o.optString("chto", "")
            } catch (ignored: Exception) {}
        }
        var f: File? = null
        if (code == 0) {
            // no versiya.json yet: fetch the APK itself (≈150 KB) and read its version
            f = download(url) ?: return
            val pi = a.packageManager.getPackageArchiveInfo(f.absolutePath, 0) ?: return
            code = kod(pi)
            name = pi.versionName ?: ""
        }
        if (code <= mine) { f?.delete(); return }
        // "Позже" for this version: do not download it again during the pause (saves mobile data)
        if (sp.getInt("skip", 0) == code && System.currentTimeMillis() - sp.getLong("skipT", 0) < PAUZA_MS) { f?.delete(); return }
        val gotov = readyApk
        if (f == null && gotov != null && readyCode == code && gotov.exists() && proverkaFayla(gotov, code)) f = gotov
        if (f == null) f = download(url)
        if (f == null) return
        // a cut connection leaves half a file: the installer would say "error parsing the package"
        if (!proverkaFayla(f, code)) { f.delete(); return }
        readyApk = f; readyCode = code; readyName = name
        val what = chto
        a.runOnUiThread { predlozhit(what) }
    }

    private fun predlozhit(chto: String?) {
        if (a.isFinishing || readyApk == null) return
        val skipped = sp.getInt("skip", 0)
        // "Позже" for this version: ask again 3 hours later
        if (skipped == readyCode && System.currentTimeMillis() - sp.getLong("skipT", 0) < PAUZA_MS) return
        AlertDialog.Builder(a)
            .setTitle("Обновление ARGEDA AI $readyName")
            .setMessage((if (chto.isNullOrEmpty()) "Доступна новая версия приложения." else chto) + "\n\nУстановить сейчас? Это займёт несколько секунд.")
            .setPositiveButton("Обновить") { _, _ -> ustanovit() }
            .setNegativeButton("Позже") { _, _ -> sp.edit().putInt("skip", readyCode).putLong("skipT", System.currentTimeMillis()).apply() }
            .show()
    }

    fun ustanovit() {
        val apk = readyApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Разрешите установку из ARGEDA AI и вернитесь в приложение", Toast.LENGTH_LONG).show()
            sp.edit().putBoolean("pending", true).apply()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        sp.edit().putBoolean("pending", false).apply()
        if (Build.VERSION.SDK_INT >= 26 && cherezSessiyu(apk)) return
        val u = CameraFileProvider.uriFor("update", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { a.startActivity(i) } catch (e: Exception) { Toast.makeText(a, "Не удалось запустить установку", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Android's own package installer session. On Android 12+ an app updating itself may skip the
     * "Update this app?" screen (USER_ACTION_NOT_REQUIRED); if the system still wants a tap,
     * UstanovkaPriemnik shows its screen. Any failure falls back to the classic installer screen.
     */
    private fun cherezSessiyu(apk: File): Boolean {
        var s: PackageInstaller.Session? = null
        return try {
            val pi = a.packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            p.setAppPackageName(a.packageName)
            p.setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(p)
            val ses = pi.openSession(id); s = ses
            ses.openWrite("argeda.apk", 0, apk.length()).use { out ->
                FileInputStream(apk).use { inp -> inp.copyTo(out, 65536) }
                ses.fsync(out)
            }
            val cb = Intent(a, UstanovkaPriemnik::class.java).setAction(UstanovkaPriemnik.ACTION)
            val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            ses.commit(PendingIntent.getBroadcast(a, id, cb, fl).intentSender)
            ses.close()
            Toast.makeText(a, "Устанавливаю обновление…", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            try { s?.abandon() } catch (ignored: Exception) {}
            false
        }
    }

    /** After returning from the "unknown sources" settings screen. */
    fun posleNastroek() {
        if (sp.getBoolean("pending", false) && readyApk != null && Build.VERSION.SDK_INT >= 26 && a.packageManager.canRequestPackageInstalls()) ustanovit()
    }

    /** The file is a whole APK of this app with the expected version. */
    private fun proverkaFayla(f: File, code: Int): Boolean = try {
        val pi = a.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        pi != null && a.packageName == pi.packageName && kod(pi) == code
    } catch (e: Exception) { false }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "ArgedaApp-obnovlenie")
        if (c.responseCode != 200) null
        else c.inputStream.use { inp ->
            val b = ByteArray(8192); val o = ByteArrayOutputStream(); var big = false
            while (true) { val n = inp.read(b); if (n <= 0) break; o.write(b, 0, n); if (o.size() > 65536) { big = true; break } }
            if (big) null else o.toString("UTF-8")
        }
    } catch (e: Exception) { null }

    private fun download(url: String): File? = try {
        val dir = File(a.cacheDir, "update"); dir.mkdirs()
        val f = File(dir, "ARGEDA-AI.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "ArgedaApp-obnovlenie")
        if (c.responseCode != 200 || (c.contentType ?: "").contains("text/html")) null
        else {
            var ok = true
            c.inputStream.use { inp ->
                FileOutputStream(f).use { out ->
                    val b = ByteArray(16384); var total = 0L
                    while (true) { val n = inp.read(b); if (n <= 0) break; out.write(b, 0, n); total += n; if (total > 50_000_000L) { ok = false; break } }
                }
            }
            if (ok) f else null
        }
    } catch (e: Exception) { null }
}
