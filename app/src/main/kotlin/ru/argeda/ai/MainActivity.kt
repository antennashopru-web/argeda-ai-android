package ru.argeda.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private const val HOME = "https://ai.argeda.ru/?source=app"
        private const val HOST = "ai.argeda.ru"
        private const val RC_FILE = 1
        private const val RC_PERM_WEB = 2
        private const val RC_PERM_CAMERA = 3
        private const val RC_PERM_STORAGE = 4

        private val EXTERNAL_HOSTS = arrayOf(
            "t.me", "telegram.me", "telegram.org", "wildberries.ru", "wb.ru", "ozon.ru", "market.yandex.ru",
            "youtube.com", "youtu.be", "vk.com", "rutube.ru", "dzen.ru", "argeda.ru", "crm.argeda.ru",
            "play.google.com", "apps.rustore.ru", "rustore.ru", "maps.google.com", "yandex.ru", "2gis.ru"
        )

        private fun mimeForExtension(e: String): String? = when (e.removePrefix(".")) {
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/comma-separated-values"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            else -> null
        }

        private fun bezopasnoeImya(name: String?): String =
            (if (name.isNullOrEmpty()) "fayl" else name).replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var karkas: Karkas
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var loadFailed = false
    private var stranitsaGotova = false
    private val zavis = Handler(Looper.getMainLooper())

    /** The site did not answer in 12 s (45 s if bytes are coming in): show the own screen instead of an empty page. */
    private var zagruzkaS = 0L
    private val zavisCheck: Runnable = object : Runnable {
        override fun run() {
            if (stranitsaGotova) return
            if (web.progress >= 25 && System.currentTimeMillis() - zagruzkaS < 45000) { zavis.postDelayed(this, 5000); return }
            loadFailed = true
            web.stopLoading()
            progress.visibility = View.GONE
            karkas.pokazat("net")
            karkas.neUdalos()
        }
    }
    private var obnovlenie: Obnovlenie? = null
    private var hookJs = ""
    private var pendingChooser: WebChromeClient.FileChooserParams? = null
    private var cameraAsked = false

    private class OzhidaetSohraneniya(val data: ByteArray, val mime: String, val name: String)
    private var pendingSave: OzhidaetSohraneniya? = null
    private var notifAsked = false

    private val offline: WebView get() = findViewById(R.id.offline)

    /** Called from page JS (only pages of our host get the hook). */
    private inner class Bridge(private val w: WebView) {
        @JavascriptInterface
        fun saveFile(base64: String?, mime: String?, name: String?) {
            if (!onOurHost()) return
            val data = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { saveFailed("base64"); return }
            val safe = if (name == null) "fayl" else bezopasnoeImya(name)
            val type = if (mime.isNullOrEmpty()) "application/octet-stream" else mime
            runOnUiThread {
                if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingSave = OzhidaetSohraneniya(data, type, safe)
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), RC_PERM_STORAGE)
                    return@runOnUiThread
                }
                writeToDownloads(data, type, safe)
            }
        }

        @JavascriptInterface
        fun pleerPlay(url: String?, title: String?, posMs: Int, durMs: Int) {
            if (!onOurHost()) return
            runOnUiThread {
                karkas.pleerNash = false
                askNotifications()
                PleerService.send(this@MainActivity, PleerService.A_PLAY, title, posMs, durMs)
            }
        }

        @JavascriptInterface
        fun pleerPause(posMs: Int) { if (onOurHost()) runOnUiThread { PleerService.send(this@MainActivity, PleerService.A_PAUSE, null, posMs, 0) } }

        @JavascriptInterface
        fun pleerSeek(posMs: Int) { if (onOurHost()) runOnUiThread { PleerService.send(this@MainActivity, PleerService.A_SEEK, null, posMs, 0) } }

        @JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            if (!onOurHost()) return
            val body = (text ?: "") + (if (url.isNullOrEmpty()) "" else (if (text.isNullOrEmpty()) "" else "\n") + url)
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, body)
            runOnUiThread { openExternal(Intent.createChooser(i, "Поделиться")) }
        }

        private fun onOurHost(): Boolean {
            // WebView methods may only be called on the UI thread; bridge calls arrive on the JavaBridge thread
            var cur: String? = null
            val done = CountDownLatch(1)
            w.post { try { cur = w.url } finally { done.countDown() } }
            try { if (!done.await(2, TimeUnit.SECONDS)) return false } catch (e: InterruptedException) { return false }
            val c = cur ?: return false
            if (c.startsWith("about:blank")) return true // window.open("") + document.write from our page
            return HOST == Uri.parse(c).host
        }

        /** http(s) link with the download attribute: stream it through DownloadManager with the session cookie. */
        @JavascriptInterface
        fun skachat(url: String?, name: String?) {
            if (!onOurHost() || url == null || !url.startsWith("https://$HOST/")) return
            val n = name ?: ""
            runOnUiThread { download(w, url, w.settings.userAgentString, if (n.isEmpty()) null else "attachment; filename=\"$n\"", null) }
        }

        /** navigator.share({files}) */
        @JavascriptInterface
        fun shareFile(base64: String?, mime: String?, name: String?, text: String?) {
            if (!onOurHost()) return
            try {
                val dir = File(cacheDir, "share"); dir.mkdirs()
                for (old in dir.listFiles() ?: emptyArray()) if (System.currentTimeMillis() - old.lastModified() > 3600_000) old.delete()
                val f = File(dir, bezopasnoeImya(name))
                FileOutputStream(f).use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
                val u = CameraFileProvider.uriFor("share", f)
                val i = Intent(Intent.ACTION_SEND).setType(if (mime.isNullOrEmpty()) "*/*" else mime)
                    .putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, text ?: "")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { openExternal(Intent.createChooser(i, "Поделиться")) }
            } catch (e: Exception) { saveFailed("share") }
        }

        /** The site may ask: ArgedaApp.versiya() -> "1.6", ArgedaApp.obnovit() -> check now. */
        @JavascriptInterface
        fun versiya(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

        @JavascriptInterface
        fun obnovit() {
            if (onOurHost()) runOnUiThread {
                Toast.makeText(this@MainActivity, "Проверяю обновление…", Toast.LENGTH_SHORT).show()
                obnovlenie?.proverit(true)
            }
        }

        /** window.print() */
        @JavascriptInterface
        fun print() {
            if (!onOurHost()) return
            runOnUiThread {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print("ARGEDA AI", w.createPrintDocumentAdapter("ARGEDA AI"), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun saveFailed(why: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, "Не удалось скачать файл", Toast.LENGTH_SHORT).show() }
        }
    }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    /** Notification buttons -> page player, so the site UI stays in sync. */
    fun pleerMirror(what: String, ms: Int) {
        // send to both pages: whichever one is playing reacts; on pause/stop also silence every media element
        val q = JSONObject.quote(what)
        val stop = what == "pause" || what == "stop"
        val tiho = if (stop) "try{document.querySelectorAll('audio,video').forEach(function(m){m.pause()})}catch(e){}" else ""
        runOnUiThread {
            try { offline.evaluateJavascript("window.karkasPleer&&window.karkasPleer($q,$ms);$tiho", null) } catch (ignored: Exception) {}
            try { web.evaluateJavascript("window.__argedaPleerMirror&&window.__argedaPleerMirror($q,$ms);$tiho", null) } catch (ignored: Exception) {}
        }
    }

    private fun writeToDownloads(data: ByteArray, mime: String, name: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Downloads.DISPLAY_NAME, name)
                v.put(MediaStore.Downloads.MIME_TYPE, mime)
                val img = mime.startsWith("image/"); val vid = mime.startsWith("video/"); val aud = mime.startsWith("audio/")
                val dir = when { img -> Environment.DIRECTORY_PICTURES; vid -> Environment.DIRECTORY_MOVIES; aud -> Environment.DIRECTORY_MUSIC; else -> Environment.DIRECTORY_DOWNLOADS }
                v.put(MediaStore.Downloads.RELATIVE_PATH, "$dir/ARGEDA AI")
                v.put(MediaStore.Downloads.IS_PENDING, 1)
                val coll = when {
                    img -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    vid -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    aud -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val u = contentResolver.insert(coll, v) ?: throw IOException("insert")
                (contentResolver.openOutputStream(u) ?: throw IOException("open")).use { it.write(data) }
                v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(u, v, null, null)
                karkas.zapisatFayl(u, mime, name)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ARGEDA AI")
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { it.write(data) }
                val dm0 = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                @Suppress("DEPRECATION")
                val did = dm0.addCompletedDownload(name, name, true, mime, f.absolutePath, f.length(), true)
                karkas.zapisatFayl(dm0.getUriForDownloadedFile(did), mime, name)
            }
            val where = if (mime.startsWith("image/") || mime.startsWith("video/")) "в галерею (ARGEDA AI)" else "в Загрузки / ARGEDA AI"
            Toast.makeText(this, "Сохранено $where: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android 15 draws under the system bars: pad the content by bars + keyboard ourselves. */
    private var imeBylo = 0
    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                val off = findViewById<View>(R.id.offline)
                if (imeBylo > 0 && ime.bottom == 0) {   // keyboard closed: let the offline page re-pin its sticky header
                    if (off is WebView) off.postDelayed({ off.evaluateJavascript("window.karkasPerekladka&&karkasPerekladka()", null) }, 250)
                }
                if ((imeBylo > 0) != (ime.bottom > 0)) {   // tell the offline page: hide menu and mini player while typing
                    val otkr = ime.bottom > 0
                    if (off is WebView) off.evaluateJavascript("window.karkasKlav&&karkasKlav($otkr)", null)
                }
                imeBylo = ime.bottom
                WindowInsets.CONSUMED
            }
        } else {
            @Suppress("DEPRECATION")
            root.fitsSystemWindows = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); PleerService.ui = this
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        karkas = Karkas(this, offline)
        Pryamoy(this, offline)
        offline.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb; openFileChooser(params); return true
            }
        }

        setupWebView(web)
        val s = web.settings
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        val cm = CookieManager.getInstance()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (handleUrl(req.url)) return true
                return softNavigate(view, req)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadFailed = false
                stranitsaGotova = false
                progress.visibility = View.VISIBLE
                zavis.removeCallbacks(zavisCheck)
                zagruzkaS = System.currentTimeMillis()
                zavis.postDelayed(zavisCheck, 12000)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                zavis.removeCallbacks(zavisCheck)
                progress.visibility = View.GONE
                if (url != null && url.startsWith("https://$HOST")) view.evaluateJavascript(hookJs, null)
                cm.flush()
                if (loadFailed) { karkas.neUdalos(); return }
                // a blank page (server half-alive, empty answer) must not leave a black screen
                proveritSoderzhimoe(view, url, 0)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!loadFailed) karkas.zaplanirovatSnimok(view, url)
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    loadFailed = true
                    karkas.pokazat("net")
                }
            }

            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse?) {
                if (req.isForMainFrame && resp != null && resp.statusCode >= 500) {
                    loadFailed = true
                    karkas.pokazat("net")
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val f = foto
                if (f != null) {
                    foto = null
                    cb.onReceiveValue(arrayOf(f))
                    Toast.makeText(this@MainActivity, "Прикреплено фото из «Поделиться»", Toast.LENGTH_SHORT).show()
                    return true
                }
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                openFileChooser(params)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val need = ArrayList<String>()
                for (r in request.resources) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == r) need.add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE == r) need.add(Manifest.permission.RECORD_AUDIO)
                }
                val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(request.resources); return }
                pendingWebPermission = request
                requestPermissions(missing.toTypedArray(), RC_PERM_WEB)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // target=_blank / window.open: a second window over the page (the form underneath keeps its state)
                val p = WebView(this@MainActivity)
                setupWebView(p)
                p.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                        if (handleUrl(req.url)) { closePopup(); return true }
                        return false
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        popupTitle.text = if (v.title.isNullOrEmpty()) url else v.title
                        if (url != null && (url.startsWith("https://$HOST") || url.startsWith("about:blank"))) v.evaluateJavascript(hookJs, null)
                    }
                }
                p.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(w: WebView) { closePopup() }
                    override fun onReceivedTitle(w: WebView, t: String?) { if (!t.isNullOrEmpty()) popupTitle.text = t }
                    override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = cb; openFileChooser(params); return true
                    }
                }
                p.setDownloadListener { url, ua, cd, mime, _ -> download(p, url, ua, cd, mime); if (!url.startsWith("blob:")) closePopup() }
                openPopup(p)
                (resultMsg.obj as WebView.WebViewTransport).webView = p
                resultMsg.sendToTarget()
                return true
            }
        }

        popup = findViewById(R.id.popup)
        popupTitle = findViewById(R.id.popup_title)
        findViewById<View>(R.id.popup_close).setOnClickListener { closePopup() }
        hookJs = try { assets.open("skachivanie.js").use { String(it.readBytes(), Charsets.UTF_8) } } catch (e: IOException) { "" }
        applyInsets()
        obnovlenie = Obnovlenie(this)
        slushatSet()
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED) else registerReceiver(downloadDone, f)

        web.setDownloadListener { url, ua, contentDisposition, mime, _ -> download(web, url, ua, contentDisposition, mime) }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            // own screen from the first second, instead of a black window while the site answers
            karkas.pokazat(if (Karkas.estSet(this)) "proverka" else "net")
            val start = vhod(intent)
            web.loadUrl(start ?: HOME)
        }
    }

    /**
     * While music plays, a full page load would kill the page's player. Our site is a Next.js app, so an
     * in-site link is handed to its client router instead (same as a Link click): the player keeps going.
     */
    private fun softNavigate(view: WebView, req: WebResourceRequest): Boolean {
        if (!PleerService.playing || !req.isForMainFrame || req.isRedirect || !"GET".equals(req.method, ignoreCase = true)) return false
        val u = req.url
        if (HOST != u.host || "https" != u.scheme) return false
        val path = u.path ?: "/"
        if (path.startsWith("/api/") || path.endsWith(".html") || path.startsWith("/vhod") || path.startsWith("/vyhod")
            || path.startsWith("/registratsiya") || path.startsWith("/oplata") || path.contains("/.well-known/")
            || path.matches(Regex(".*\\.(pdf|zip|mp3|mp4|wav|png|jpe?g|webp|csv|xlsx?)$"))) return false
        val cur = view.url
        if (cur == null || !cur.startsWith("https://$HOST")) return false
        val target = u.toString()
        val js = "(function(){try{if(window.next&&window.next.router&&window.next.router.push){window.next.router.push(" +
                JSONObject.quote(target) + ");return 'ok'}}catch(e){}return 'net'})()"
        view.evaluateJavascript(js) { r -> if (r == null || !r.contains("ok")) view.loadUrl(target) }
        return true
    }

    private fun setupWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = s.userAgentString + " ArgedaApp/4.7"
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(w), "ArgedaApp")
        w.setBackgroundColor(0xFF0B0C0E.toInt())
    }

    private var popupWeb: WebView? = null
    private lateinit var popup: View
    private lateinit var popupTitle: TextView

    fun openPopup(p: WebView) {
        closePopup()
        popupWeb = p
        findViewById<FrameLayout>(R.id.popup_body).addView(p, FrameLayout.LayoutParams(-1, -1))
        popupTitle.text = ""
        popup.visibility = View.VISIBLE
    }

    fun closePopup() {
        val p = popupWeb ?: return
        popupWeb = null
        findViewById<FrameLayout>(R.id.popup_body).removeView(p)
        popup.visibility = View.GONE
        p.post { p.destroy() }
    }

    /** The page loaded: hide the shell only if the page really has something on it. */
    private fun proveritSoderzhimoe(view: WebView, url: String?, popytka: Int) {
        view.evaluateJavascript("(function(){var b=document.body;return b?(b.innerText||'').trim().length+b.querySelectorAll('img,video,canvas,svg').length:0})()") { r ->
            val n = try { (r ?: "0").replace(Regex("[^0-9]"), "").toInt() } catch (ignored: Exception) { 0 }
            if (loadFailed) return@evaluateJavascript
            if (n >= 20) {
                stranitsaGotova = true
                val bylKarkas = karkas.vidim()
                karkas.skryt()
                karkas.zaplanirovatSnimok(view, url)
                if (zhdetTekst != null) vstavitTekst(view, url)
                else if (bylKarkas) predlozhitZadaniya()
            } else if (popytka < 4) {
                view.postDelayed({ proveritSoderzhimoe(view, url, popytka + 1) }, 1500)
            } else {
                stranitsaGotova = true
                loadFailed = true
                karkas.pokazat("net")
                karkas.neUdalos()
            }
        }
    }

    // ---------- called by the offline shell (Karkas) ----------
    fun povtoritSayt() {
        karkas.pokazat("proverka")
        val u = web.url
        if (u == null || !u.startsWith("https://$HOST")) web.loadUrl(HOME) else web.reload()
    }

    fun saytLezhit(): Boolean = loadFailed
    fun otkrytSayt(url: String) { karkas.pokazat("proverka"); web.loadUrl(url) }

    /** Text prepared in the app, typed into the section's first text field once the page is up. */
    private var zhdetTekst: String? = null
    private var zhdetPut: String? = null

    fun otkrytRazdel(path: String, prompt: String?) {
        karkas.zakrep = false
        zhdetTekst = if (prompt.isNullOrBlank()) null else prompt.trim()
        zhdetPut = path
        otkrytSayt("https://" + HOST + path + (if (path.contains("?")) "&" else "?") + "source=app")
    }

    private fun vstavitTekst(view: WebView, url: String?) {
        val t = zhdetTekst ?: return
        val u = Uri.parse(url ?: return)
        val put = zhdetPut?.split("?")?.get(0) ?: ""
        val path = u.path
        if (path == null || !path.startsWith(put)) return
        zhdetTekst = null
        view.evaluateJavascript("(function(t){var n=0;function go(){var el=document.querySelector('textarea')||document.querySelector('input[type=text],input:not([type])');" +
                "if(!el){if(++n<20)setTimeout(go,500);return}" +
                "var p=el.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "Object.getOwnPropertyDescriptor(p,'value').set.call(el,t);el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.scrollIntoView({block:'center'});el.focus()}go()})(" + JSONObject.quote(t) + ")", null)
    }

    /** The site is back: offer the tasks prepared while it was down. */
    private var zadaniyaPredlozheny = false

    private fun predlozhitZadaniya() {
        val z = karkas.zadaniya()
        if (z.length() == 0 || zadaniyaPredlozheny) return
        zadaniyaPredlozheny = true
        val items = Array(z.length()) { i ->
            val t = z.optJSONObject(i)?.optString("text") ?: ""
            if (t.length > 70) t.substring(0, 70) + "…" else t
        }
        AlertDialog.Builder(this)
            .setTitle("Сервер снова работает — отправить задание?")
            .setItems(items) { _, i ->
                val o = karkas.vzyatZadanie(i)
                if (o != null) otkrytRazdel(o.optString("path", "/"), o.optString("text", ""))
                zadaniyaPredlozheny = false
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    fun otkrytOkno(p: WebView, title: String) { openPopup(p); popupTitle.text = title }
    fun zakrytOkno() { closePopup() }

    /** The connection came back while the shell is shown: try the site by itself. */
    private var setVernulas: ConnectivityManager.NetworkCallback? = null

    private fun slushatSet() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    web.postDelayed({ if (karkas.vidim()) povtoritSayt() }, 1500)
                }
            }
            setVernulas = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (ignored: Exception) {}
    }

    /** true = handled outside the WebView; false = let WebView load it. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme == "https" || scheme == "http") {
            val host = (uri.host ?: "").lowercase()
            // everything (site, bank, 3-D Secure pages) stays inside; only known foreign services go out
            if (isExternalHost(host)) { openExternal(Intent(Intent.ACTION_VIEW, uri)); return true }
            return false
        }
        if (scheme == "intent") {
            try {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null; i.selector = null
                if (packageManager.resolveActivity(i, 0) != null) { startActivity(i); return true }
                val fallback = i.getStringExtra("browser_fallback_url")
                if (fallback != null) web.loadUrl(fallback)
            } catch (ignored: Exception) {}
            return true
        }
        if (scheme == "javascript" || scheme == "blob" || scheme == "data" || scheme == "about") return false
        // tel:, mailto:, tg:, bank apps (sbp, bank100000000004...), etc.
        openExternal(Intent(Intent.ACTION_VIEW, uri))
        return true
    }

    private fun isExternalHost(h: String): Boolean {
        if (h == HOST) return false
        for (e in EXTERNAL_HOSTS) {
            if (e == "argeda.ru" && h == "ai.argeda.ru") continue
            if (h == e || h.endsWith(".$e")) return true
        }
        return false
    }

    private fun openExternal(i: Intent) {
        try { startActivity(i) }
        catch (e: ActivityNotFoundException) { Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show() }
    }

    private val openWhenDone = HashSet<Long>()
    private val nashi = HashMap<Long, String>()
    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val nm = nashi.remove(id) ?: return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val u = dm.getUriForDownloadedFile(id)
            val t = dm.getMimeTypeForDownloadedFile(id)
            if (u == null) { openWhenDone.remove(id); return }
            karkas.zapisatFayl(u, t, nm)
            if (!openWhenDone.remove(id)) return
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(u, t ?: "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (t != null && t.contains("android.package-archive") && Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this@MainActivity, "Разрешите установку из ARGEDA AI, затем откройте файл из «Загрузок»", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            try { startActivity(v) } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "Файл сохранён в «Загрузки»: нет приложения, чтобы его открыть", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun proveritObnovlenie() {
        val o = obnovlenie ?: return
        Toast.makeText(this, "Проверяю обновление…", Toast.LENGTH_SHORT).show()
        o.proverit(true)
    }

    fun download(owner: WebView, url: String, ua: String?, contentDisposition: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val js = "(function(){fetch(" + JSONObject.quote(url) + ").then(function(x){return x.blob()}).then(function(b){" +
                        "var r=new FileReader();r.onload=function(){var s=r.result;ArgedaApp.saveFile(s.slice(s.indexOf(',')+1),b.type||" +
                        JSONObject.quote(mime ?: "") + "," + JSONObject.quote(name) + ")};r.readAsDataURL(b)})" +
                        ".catch(function(e){ArgedaApp.saveFailed(String(e))})})()"
                owner.evaluateJavascript(js, null)
                return
            }
            val m = mime ?: ""
            val r = DownloadManager.Request(Uri.parse(url))
            if (m.isNotEmpty()) r.setMimeType(m)
            r.addRequestHeader("User-Agent", ua)
            val cookies = CookieManager.getInstance().getCookie(url)
            if (cookies != null) r.addRequestHeader("Cookie", cookies)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val dir = when {
                m.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                m.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            r.setDestinationInExternalPublicDir(dir, "ARGEDA AI/$name")
            @Suppress("DEPRECATION")
            r.allowScanningByMediaScanner()
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            nashi[id] = name
            val ml = m.lowercase(); val nl = name.lowercase()
            val doc = ml.contains("pdf") || nl.endsWith(".pdf") || ml.contains("msword") || ml.contains("officedocument") || ml.contains("ms-excel") ||
                    ml.contains("android.package-archive") || nl.endsWith(".apk")
            if (doc) openWhenDone.add(id)
            Toast.makeText(this, if (doc) "Открываю документ…" else getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun openFileChooser(params: WebChromeClient.FileChooserParams) {
        if (!cameraAsked && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraAsked = true
            pendingChooser = params
            requestPermissions(arrayOf(Manifest.permission.CAMERA), RC_PERM_CAMERA)
            return
        }
        val pick = Intent(Intent.ACTION_GET_CONTENT)
        pick.addCategory(Intent.CATEGORY_OPENABLE)
        val mimes = ArrayList<String>()
        var unknown = false
        params.acceptTypes?.forEach { raw ->
            if (raw == null) return@forEach
            for (part in raw.split(",")) {
                val t = part.trim().lowercase()
                if (t.isEmpty()) continue
                if (t.contains("/")) { if (!mimes.contains(t)) mimes.add(t); continue }
                val m = mimeForExtension(t)
                if (m == null) unknown = true else if (!mimes.contains(m)) mimes.add(m)
            }
        }
        val onlyImages = !unknown && mimes.isNotEmpty() && mimes.all { it.startsWith("image/") }
        pick.type = if (onlyImages) "image/*" else "*/*"
        if (!unknown && mimes.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
        else if (!unknown && mimes.size == 1) pick.type = mimes[0]
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        val chooser = Intent.createChooser(pick, getString(R.string.choose_file))
        val extras = ArrayList<Intent>()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            try {
                val dir = File(cacheDir, "camera"); dir.mkdirs()
                val f = File(dir, "foto-" + System.currentTimeMillis() + ".jpg")
                val cu = CameraFileProvider.uriFor(f)
                cameraUri = cu
                val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                cam.putExtra(MediaStore.EXTRA_OUTPUT, cu)
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                extras.add(cam)
            } catch (ignored: Exception) { cameraUri = null }
        }
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(chooser, RC_FILE)
        } catch (e: ActivityNotFoundException) { fileCallback?.onReceiveValue(null); fileCallback = null }
    }

    @Deprecated("Activity result API is not used here: the app has no androidx")
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(rc, result, data)
        val cb = fileCallback
        if (rc != RC_FILE || cb == null) return
        var out: Array<Uri>? = null
        if (result == RESULT_OK) {
            val clip = data?.clipData
            val d = data?.data
            val cu = cameraUri
            if (clip != null) out = Array(clip.itemCount) { clip.getItemAt(it).uri }
            else if (d != null) out = arrayOf(d)
            else if (cu != null) out = arrayOf(cu)
        }
        cb.onReceiveValue(out)
        fileCallback = null
        cameraUri = null
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        val wp = pendingWebPermission
        if (rc == RC_PERM_WEB && wp != null) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) wp.grant(wp.resources) else wp.deny()
            pendingWebPermission = null
        }
        val pc = pendingChooser
        if (rc == RC_PERM_CAMERA && pc != null) {
            pendingChooser = null
            openFileChooser(pc)
        }
        val ps = pendingSave
        if (rc == RC_PERM_STORAGE && ps != null) {
            pendingSave = null
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) writeToDownloads(ps.data, ps.mime, ps.name)
            else Toast.makeText(this, "Без разрешения файл сохранить нельзя", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = vhod(intent)
        if (u != null) otkrytSayt(u)
        else if (karkas.zakrep) karkas.pokazat(if (loadFailed) "net" else "ok")
    }

    // ---------- icon shortcuts and «Поделиться → ARGEDA AI» ----------
    private var foto: Uri? = null // a photo shared into the app: handed to the site's next file picker

    /** @return the site address to open, or null to stay where we are */
    private fun vhod(i: Intent?): String? {
        if (i == null) return null
        val act = i.action
        if ("ru.argeda.ai.RAZDEL" == act) {
            val put = i.getStringExtra("put")
            karkas.ekran(i.getStringExtra("ekran"), i.getStringExtra("k"), null)
            return if (put != null && put.startsWith("/")) "https://$HOST$put?source=app" else null
        }
        if (Intent.ACTION_SEND == act) {
            val type = i.type ?: ""
            if (type.startsWith("image/")) {
                @Suppress("DEPRECATION")
                val src: Uri = i.getParcelableExtra(Intent.EXTRA_STREAM) ?: return null
                try {
                    val dir = File(cacheDir, "camera"); dir.mkdirs()
                    val f = File(dir, "podelilis-" + System.currentTimeMillis() + ".jpg")
                    (contentResolver.openInputStream(src) ?: throw IOException("open")).use { inp ->
                        FileOutputStream(f).use { out ->
                            val b = ByteArray(16384); var total = 0L
                            while (true) {
                                val n = inp.read(b); if (n <= 0) break
                                out.write(b, 0, n); total += n
                                if (total > 40_000_000L) throw IOException("big")
                            }
                        }
                    }
                    foto = CameraFileProvider.uriFor(f)
                    Toast.makeText(this, "Фото прикрепится, когда раздел попросит файл", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось взять фото", Toast.LENGTH_SHORT).show()
                    return null
                }
                karkas.ekran("razdel", "kartinki", null)
                return "https://$HOST/kartinki?source=app"
            }
            val raw = i.getStringExtra(Intent.EXTRA_TEXT)
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            val asUrl = Uri.parse(text)
            if (HOST == asUrl.host && text.indexOf(' ') < 0) return text
            karkas.zakrep = true
            karkas.ekran("sozdat", null, text)
            return null
        }
        val data = i.data
        if (data != null && HOST == data.host) return data.toString()
        return null
    }

    @Deprecated("Kept on purpose: the app has no androidx back dispatcher")
    override fun onBackPressed() {
        val pw = popupWeb
        if (pw != null) { if (pw.canGoBack()) pw.goBack() else closePopup(); return }
        if (karkas.vidim()) {
            // inside the own screens: step back to «Главная» first, then leave the app
            karkas.view.evaluateJavascript("window.karkasNazad?window.karkasNazad():'net'") { r ->
                if (r != null && r.contains("ok")) return@evaluateJavascript
                if (karkas.zakrep && stranitsaGotova && !loadFailed) karkas.skrytVsegda() else finish()
            }
            return
        }
        @Suppress("DEPRECATION")
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    override fun onResume() {
        super.onResume(); PleerService.ui = this
        obnovlenie?.let { it.posleNastroek(); it.proverit(false) }
    }

    override fun onDestroy() {
        if (PleerService.ui === this) PleerService.ui = null
        // app closed: music stops with it
        PleerService.send(this, PleerService.A_STOP_TIHO, null, 0, 0)
        try { val off = offline; off.evaluateJavascript("window.karkasPleer&&window.karkasPleer('pause',0)", null); off.destroy() } catch (ignored: Exception) {}
        try { unregisterReceiver(downloadDone) } catch (ignored: Exception) {}
        try { setVernulas?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (ignored: Exception) {}
        closePopup(); web.destroy(); super.onDestroy()
    }
}
