package ru.argeda.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline shell: the app's own screen (assets/karkas/index.html) shown when the site does not answer.
 * It keeps working without the internet: sections, files saved from the site, snapshots of recently opened pages.
 */
class Karkas(private val a: MainActivity, val view: WebView) {
    companion object {
        const val HOST = "ai.argeda.ru"
        private const val MAX_FILES = 100
        private const val MAX_PAGES = 12
        private val NE_SOHRANYAT = arrayOf("/api", "/vhod", "/oplata", "/registratsiya", "/vyhod", "/admin")

        fun estSet(c: Context): Boolean = try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            cm == null || cm.activeNetwork != null
        } catch (e: Exception) { true }

        private fun imya(path: String): String {
            val s = path.replace(Regex("[^A-Za-z0-9_-]+"), "_").replace(Regex("^_+|_+$"), "")
            return if (s.isEmpty()) "glavnaya" else if (s.length > 80) s.substring(0, 80) else s
        }
    }

    private val prefs: SharedPreferences = a.getSharedPreferences("karkas", Context.MODE_PRIVATE)
    private val dir = File(a.filesDir, "stranitsy").apply { mkdirs() }
    private val h = Handler(Looper.getMainLooper())
    private var snimokZadacha: Runnable? = null
    private var zagruzhen = false
    @Volatile private var sost = "proverka"

    /** The shell's own music player owns the notification (not the site's). */
    @Volatile var pleerNash = false

    /** Held open on purpose (text shared into the app): the user picks a section first, even if the site is up. */
    var zakrep = false
    @Volatile private var startEkran: String? = null

    init {
        val s = view.settings
        s.javaScriptEnabled = true
        s.allowFileAccess = false          // assets are still readable; nothing else on disk
        s.allowContentAccess = true        // thumbnails of saved files (content://)
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false  // muted example clips loop on their own
        view.setBackgroundColor(0xFF0B0C0E.toInt())
        view.addJavascriptInterface(Most(), "ArgedaKarkas")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean = true
        }
    }

    fun vidim(): Boolean = view.visibility == View.VISIBLE

    /** Show the shell. sostoyanie: "net" — no connection, "proverka" — checking. */
    fun pokazat(sostoyanie: String) {
        sost = sostoyanie
        if (!zagruzhen) { view.loadUrl("file:///android_asset/karkas/index.html"); zagruzhen = true }
        else js("window.karkasObnovit&&window.karkasObnovit(" + JSONObject.quote(sostoyanie) + ")")
        view.visibility = View.VISIBLE
    }

    fun igraet(): Boolean = pleerNash && PleerService.playing

    fun skryt() {
        if (zakrep || igraet()) { sost = "ok"; js("window.karkasObnovit&&window.karkasObnovit('ok')"); return }
        view.visibility = View.GONE
    }

    fun skrytVsegda() { zakrep = false; view.visibility = View.GONE }

    /** Open one of the shell's own screens: glavnaya, sozdat, razdel (k), fayly, kabinet; text — prefill. */
    fun ekran(name: String?, k: String?, text: String?) {
        val e = try {
            JSONObject().put("n", name).put("k", k ?: "").put("t", text ?: "").toString()
        } catch (ex: Exception) { return }
        startEkran = e
        if (zagruzhen) js("window.karkasEkran&&window.karkasEkran($e)")
    }

    /** The site failed again while the shell is open: tell the page so the button comes back. */
    fun neUdalos() { sost = "net"; if (vidim()) js("window.karkasObnovit&&window.karkasObnovit('net')") }

    private fun js(s: String) { view.post { view.evaluateJavascript(s, null) } }

    // ---------- saved files journal ----------

    fun zapisatFayl(u: Uri?, mime: String?, name: String?) {
        if (u == null) return
        try {
            val old = JSONArray(prefs.getString("fayly", "[]")); val nov = JSONArray()
            val f = JSONObject().put("uri", u.toString()).put("mime", mime ?: "")
                .put("name", name ?: "fayl").put("t", System.currentTimeMillis())
            nov.put(f)
            var i = 0
            while (i < old.length() && nov.length() < MAX_FILES) {
                if (u.toString() != old.getJSONObject(i).optString("uri")) nov.put(old.getJSONObject(i))
                i++
            }
            prefs.edit().putString("fayly", nov.toString()).apply()
        } catch (ignored: Exception) {}
    }

    // ---------- tasks prepared without the server ----------

    fun zadaniya(): JSONArray = try { JSONArray(prefs.getString("zadaniya", "[]")) } catch (e: Exception) { JSONArray() }
    fun sohranitZadaniya(z: JSONArray) { prefs.edit().putString("zadaniya", z.toString()).apply() }
    fun vzyatZadanie(i: Int): JSONObject? {
        val z = zadaniya(); val b = JSONArray(); var o: JSONObject? = null
        for (j in 0 until z.length()) { if (j == i) o = z.optJSONObject(j) else b.put(z.opt(j)) }
        sohranitZadaniya(b); return o
    }

    // ---------- page snapshots ----------

    /** Save a copy of the page a few seconds after it settles (so the content has rendered). */
    fun zaplanirovatSnimok(w: WebView, url: String?) {
        snimokZadacha?.let { h.removeCallbacks(it) }
        val u = if (url == null) null else Uri.parse(url)
        if (u == null || HOST != u.host || "https" != u.scheme) return
        val path = if (u.path.isNullOrEmpty()) "/" else u.path!!
        for (p in NE_SOHRANYAT) if (path.startsWith(p)) return
        val z = Runnable {
            if (w.url == null || w.url != url || w.progress < 100) return@Runnable
            val title = w.title ?: path
            val file = File(dir, imya(path) + ".mht").absolutePath
            try {
                w.saveWebArchive(file, false) { saved -> if (saved != null) zapisatStranitsu(path, title, saved) }
            } catch (ignored: Exception) {}
        }
        snimokZadacha = z
        h.postDelayed(z, 4000)
    }

    private fun zapisatStranitsu(path: String, title: String, file: String) {
        try {
            val old = JSONArray(prefs.getString("stranitsy", "[]")); val nov = JSONArray()
            nov.put(JSONObject().put("path", path).put("title", title).put("file", File(file).name).put("t", System.currentTimeMillis()))
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (path == o.optString("path")) continue
                if (nov.length() < MAX_PAGES) nov.put(o)
                else File(dir, o.optString("file")).delete()
            }
            prefs.edit().putString("stranitsy", nov.toString()).apply()
        } catch (ignored: Exception) {}
    }

    private fun arhiv(path: String?): File? {
        try {
            val arr = JSONArray(prefs.getString("stranitsy", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (path == o.optString("path")) {
                    val f = File(dir, o.optString("file"))
                    return if (f.exists()) f else null
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    /** A saved page opens in the second window, read-only: no scripts, links lead back to the live site. */
    private fun otkrytArhiv(f: File, title: String?) {
        val p = WebView(a)
        val s = p.settings
        s.javaScriptEnabled = false
        s.allowFileAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        p.setBackgroundColor(0xFF0B0C0E.toInt())
        p.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u != null && HOST == u.host) {
                    val other = arhiv(u.path ?: "/")
                    if (other != null) { w.loadUrl(Uri.fromFile(other).toString()); return true }
                    a.zakrytOkno()
                    a.otkrytSayt(u.toString())
                    return true
                }
                return true
            }
        }
        a.otkrytOkno(p, "Сохранённая копия · $title")
        p.loadUrl(Uri.fromFile(f).toString())
    }

    // ---------- bridge for the shell page ----------

    private inner class Most {
        @JavascriptInterface fun fayly(): String = prefs.getString("fayly", "[]") ?: "[]"
        @JavascriptInterface fun stranitsy(): String = prefs.getString("stranitsy", "[]") ?: "[]"
        @JavascriptInterface fun set(): Boolean = estSet(a)
        @JavascriptInterface fun temaSistemy(): String {
            val m = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (m == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        }
        @JavascriptInterface fun obnovlenie() { a.runOnUiThread { a.proveritObnovlenie() } }
        @JavascriptInterface fun versiya(): String =
            try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

        /** Try the site again; the shell hides itself once the page has loaded. */
        @JavascriptInterface fun povtorit() { a.runOnUiThread { a.povtoritSayt() } }

        /** Music of the shell: keeps the app alive in the background and mirrors to the notification. */
        @JavascriptInterface fun pleer(what: String?, title: String?, posMs: Int, durMs: Int) {
            a.runOnUiThread {
                if (what == "play") { pleerNash = true; a.askNotifications(); PleerService.send(a, PleerService.A_PLAY, title, posMs, durMs) }
                else if (!pleerNash) return@runOnUiThread
                else if (what == "pause") PleerService.send(a, PleerService.A_PAUSE, null, posMs, 0)
                else if (what == "seek") PleerService.send(a, PleerService.A_SEEK, null, posMs, 0)
                else if (what == "stop") { PleerService.send(a, PleerService.A_STOP_TIHO, null, 0, 0); pleerNash = false }
            }
        }

        @JavascriptInterface fun sostoyanie(): String = sost
        @JavascriptInterface fun startEkran(): String { val e = startEkran; startEkran = null; return e ?: "" }
        @JavascriptInterface fun otpustit() { a.runOnUiThread { zakrep = false; if (sost == "ok") skryt() } }

        /** A section of the live site; prompt (may be empty) is typed into the page's first text field. */
        @JavascriptInterface fun razdel(path: String?, prompt: String?) {
            if (path == null || !path.startsWith("/")) return
            a.runOnUiThread { a.otkrytRazdel(path, prompt) }
        }

        @JavascriptInterface fun zadaniya(): String = this@Karkas.zadaniya().toString()
        @JavascriptInterface fun dobavitZadanie(k: String?, path: String?, text: String?) {
            if (path == null || !path.startsWith("/") || text == null || text.trim().isEmpty()) return
            try {
                val z = this@Karkas.zadaniya()
                if (z.length() >= 30) return
                z.put(JSONObject().put("k", k).put("path", path).put("text", text.trim()).put("t", System.currentTimeMillis()))
                sohranitZadaniya(z)
            } catch (ignored: Exception) {}
        }
        @JavascriptInterface fun ubratZadanie(i: Int) { vzyatZadanie(i) }
        @JavascriptInterface fun otpravitZadanie(i: Int) {
            a.runOnUiThread {
                val o = vzyatZadanie(i)
                if (o != null) a.otkrytRazdel(o.optString("path", "/"), o.optString("text", ""))
            }
        }
        @JavascriptInterface fun pochta() {
            a.runOnUiThread {
                try { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:info@argeda.ru"))) }
                catch (e: Exception) { Toast.makeText(a, "info@argeda.ru", Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface fun kopiya(path: String?) {
            a.runOnUiThread {
                val f = arhiv(path)
                if (f != null) otkrytArhiv(f, path)
                else Toast.makeText(a, "Копии этой страницы нет — откройте её, когда сайт заработает", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface fun otkrytFayl(uri: String?, mime: String?) {
            a.runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), if (mime.isNullOrEmpty()) "*/*" else mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(a, "Нет приложения, чтобы открыть файл", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(a, "Файл удалён или недоступен", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface fun podelitsya(uri: String?, mime: String?) {
            a.runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType(if (mime.isNullOrEmpty()) "*/*" else mime)
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.startActivity(Intent.createChooser(i, "Поделиться"))
                } catch (e: Exception) { Toast.makeText(a, "Не получилось поделиться", Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface fun galereya() {
            a.runOnUiThread {
                try { a.startActivity(Intent(Intent.ACTION_VIEW).setType("image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { Toast.makeText(a, "Откройте «Галерея» → альбом ARGEDA AI", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
