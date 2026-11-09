package ru.argeda.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

/**
 * Temporary "direct mode": while the site is down the owner pastes his own generation key
 * on his phone, and the app starts paid generations straight from the phone.
 * The key never reaches JS, the repo or the chat: it is encrypted with an Android Keystore key
 * and stored only in this app's private prefs (backup is off). "Удалить ключ" wipes it.
 */
class Pryamoy(private val a: MainActivity, private val view: WebView) {
    companion object {
        private const val API = "https://api.kie.ai/api/v1/jobs/"
        private const val ALIAS = "argeda-pryamoy"

        /** Text for the person: no provider names or hosts, network failures in plain words. */
        fun chisto(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Нет связи с сервисом — проверьте интернет"
            is SocketTimeoutException -> "Сервис долго не отвечает, попробуйте ещё раз"
            is SSLException -> "Не удалось установить защищённое соединение"
            is JSONException -> "Сервис ответил непонятно, попробуйте ещё раз"
            else -> chisto(e.message.toString())
        }

        fun chisto(msg: String?): String {
            if (msg.isNullOrEmpty() || msg == "null") return "Что-то пошло не так, попробуйте ещё раз"
            val m = msg.replace(Regex("(?i)https?://\\S+"), "")
                .replace(Regex("(?i)[a-z0-9.-]*(kie|redpanda)[a-z0-9.-]*"), "сервис").trim()
            return if (m.length > 160) m.substring(0, 157) + "…" else m
        }

        private fun oshibka(code: Int, msg: String?): String = when (code) {
            401 -> "Ключ не подходит — проверьте его"
            402 -> "На счёте закончились кредиты"
            429 -> "Слишком много запросов, подождите минуту"
            455 -> "Сервис на обслуживании, попробуйте позже"
            else -> "Ошибка " + code + (if (msg.isNullOrEmpty()) "" else ": " + chisto(msg))
        }

        private fun telo(tip: String, prompt: String, format: String): JSONObject {
            val inp = JSONObject()
            val model: String
            when (tip) {
                "video" -> {
                    model = "wan/3-0-video"
                    inp.put("prompt", prompt).put("resolution", "720P").put("duration", 5)
                        .put("aspect_ratio", format.ifEmpty { "9:16" }).put("audio", true)
                }
                "muzyka" -> {
                    model = "ai-music-api/generate"
                    inp.put("custom_mode", false).put("instrumental", false).put("model", "V6")
                        .put("prompt", prompt).put("style", if (prompt.length > 900) prompt.substring(0, 900) else prompt)
                }
                else -> {
                    model = "nano-banana-2-1"
                    inp.put("prompt", prompt).put("resolution", "1K").put("output_format", "jpg")
                        .put("aspect_ratio", format.ifEmpty { "3:4" })
                }
            }
            return JSONObject().put("model", model).put("input", inp)
        }

        private val URL_RE = Regex("https?://[^\"'\\s\\\\]+?\\.(?:jpg|jpeg|png|webp|mp4|mov|mp3|wav|m4a)(?:\\?[^\"'\\s\\\\]*)?", RegexOption.IGNORE_CASE)

        private fun ssylki(data: JSONObject): JSONArray {
            val u = LinkedHashSet<String>()
            try {
                val rj = data.optString("resultJson", "")
                if (rj.isNotEmpty()) {
                    val r = JSONObject(rj).optJSONArray("resultUrls")
                    if (r != null) for (i in 0 until r.length()) u.add(r.optString(i))
                }
            } catch (ignored: Exception) {}
            if (u.isEmpty()) {
                // music: audio_url per track, at any depth
                try { audio(JSONObject(data.optString("resultJson", "{}")), u, 0) } catch (ignored: Exception) {}
            }
            if (u.isEmpty()) {
                val d2 = try { JSONObject(data.toString()).also { it.remove("param"); it.remove("paramJson") } } catch (e: Exception) { data }
                for (m in URL_RE.findAll(d2.toString().replace("\\/", "/"))) { val s = m.value; if (!s.contains("stream")) u.add(s) }
            }
            val o = JSONArray(); for (s in u) o.put(s); return o
        }

        private fun audio(o: Any?, u: MutableSet<String>, d: Int) {
            if (d > 6 || o == null) return
            if (o is JSONObject) {
                val au = o.optString("audio_url", "")
                if (au.startsWith("http")) u.add(au)
                val it = o.keys(); while (it.hasNext()) audio(o.opt(it.next()), u, d + 1)
            } else if (o is JSONArray) {
                for (i in 0 until o.length()) audio(o.opt(i), u, d + 1)
            }
        }

        /** Music tracks: {id, url, title, dur} for each object that has both audio_url and id. */
        private fun treki(o: Any?, out: JSONArray, d: Int) {
            if (d > 6 || o == null) return
            if (o is JSONObject) {
                val au = o.optString("audio_url", ""); val id = o.optString("id", "")
                if (au.startsWith("http") && id.isNotEmpty()) {
                    try {
                        out.put(JSONObject().put("id", id).put("url", au).put("title", o.optString("title", ""))
                            .put("dur", o.optDouble("duration", 0.0)).put("img", o.optString("image_url", ""))
                            .put("tekst", o.optString("prompt", "")).put("stil", o.optString("tags", "")))
                    } catch (ignored: Exception) {}
                    return
                }
                val it = o.keys(); while (it.hasNext()) treki(o.opt(it.next()), out, d + 1)
            } else if (o is JSONArray) {
                for (i in 0 until o.length()) treki(o.opt(i), out, d + 1)
            }
        }

        private fun chitat(c: HttpURLConnection, kod: Int): String {
            val inp: InputStream? = if (kod >= 400) c.errorStream else c.inputStream
            val b = ByteArrayOutputStream()
            inp?.use { it.copyTo(b, 8192) }
            return b.toString("UTF-8")
        }
    }

    private val p: SharedPreferences = a.getSharedPreferences("pryamoy", Context.MODE_PRIVATE)
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()

    init { view.addJavascriptInterface(Most(), "ArgedaPryamoy") }

    // ---------- key storage ----------
    private fun shifr(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(ALIAS)) {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            g.generateKey()
        }
        return ks.getKey(ALIAS, null) as SecretKey
    }

    private fun sohranitKlyuch(k: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, shifr())
        val ct = c.doFinal(k.toByteArray(Charsets.UTF_8))
        p.edit().putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("k", Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        true
    } catch (e: Exception) { false }

    private fun klyuch(): String? = try {
        val iv = p.getString("iv", null); val ct = p.getString("k", null)
        if (iv == null || ct == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, shifr(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun udalitKlyuch() {
        p.edit().clear().apply()
        try { val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS) } catch (ignored: Exception) {}
    }

    // ---------- HTTP ----------
    private fun zapros(metod: String, put: String, telo: JSONObject?): JSONObject {
        val k = klyuch() ?: throw Exception("Ключ не сохранён")
        val c = URL(API + put).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 40000
        c.requestMethod = metod
        c.setRequestProperty("Authorization", "Bearer $k")
        c.setRequestProperty("Accept", "application/json")
        if (telo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
        }
        val kod = c.responseCode
        val s = chitat(c, kod)
        c.disconnect()
        val j = try { JSONObject(s) } catch (e: Exception) { throw Exception("Сервис ответил $kod") }
        val code = j.optInt("code", kod)
        if (code != 200) throw Exception(oshibka(code, j.optString("msg", "")))
        return j
    }

    // ---------- tasks ----------
    private fun zadachi(): JSONArray = try { JSONArray(p.getString("z", "[]")) } catch (e: Exception) { JSONArray() }

    private fun sohranitZadachi(z: JSONArray) {
        val o = JSONArray()
        for (i in maxOf(0, z.length() - 40) until z.length()) o.put(z.opt(i))
        p.edit().putString("z", o.toString()).apply()
    }

    private fun js(fn: String) { view.post { view.evaluateJavascript("window.$fn&&window.$fn()", null) } }

    @Suppress("unused")
    private fun toast(t: String) { view.post { view.evaluateJavascript("window.toast&&toast(" + JSONObject.quote(t) + ")", null) } }

    private fun otpravitFayl(data: ByteArray, name: String?, mime: String?): String {
        val k = klyuch() ?: throw Exception("Ключ не сохранён")
        val gr = "----argeda" + System.currentTimeMillis()
        val c = URL("https://kieai.redpandaai.co/api/file-stream-upload").openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 120000; c.doOutput = true; c.requestMethod = "POST"
        c.setRequestProperty("Authorization", "Bearer $k")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$gr")
        val fn = (if (name.isNullOrEmpty()) "file" else name).replace(Regex("[^A-Za-z0-9._-]"), "_")
        c.outputStream.use { o ->
            o.write("--$gr\r\nContent-Disposition: form-data; name=\"uploadPath\"\r\n\r\nargeda-app\r\n".toByteArray(Charsets.UTF_8))
            o.write(("--$gr\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fn\"\r\nContent-Type: " +
                    (if (mime.isNullOrEmpty()) "application/octet-stream" else mime) + "\r\n\r\n").toByteArray(Charsets.UTF_8))
            o.write(data)
            o.write("\r\n--$gr--\r\n".toByteArray(Charsets.UTF_8))
        }
        val kod = c.responseCode
        val j = JSONObject(chitat(c, kod))
        if (j.optInt("code", kod) != 200) throw Exception(oshibka(j.optInt("code", kod), j.optString("msg", "")))
        return j.getJSONObject("data").getString("downloadUrl")
    }

    private fun q(s: String?): String = if (s == null) "null" else JSONObject.quote(s)

    private fun otvetZagruzki(id: String, u: String?, er: String?) {
        view.post { view.evaluateJavascript("window.pzZagr&&pzZagr(" + JSONObject.quote(id) + "," + q(u) + "," + q(er) + ")", null) }
    }

    private fun dobavit(x: JSONObject) {
        synchronized(lock) { val z = zadachi(); z.put(x); sohranitZadachi(z) }
        js("pryamoyObnovit")
    }

    private fun sozdat(x: JSONObject, telo: JSONObject) {
        try {
            val j = zapros("POST", "createTask", telo)
            val id = j.optJSONObject("data")?.optString("taskId", "") ?: ""
            if (id.isEmpty()) throw Exception("Сервис не вернул номер задачи")
            x.put("id", id).put("st", "idet")
        } catch (e: Exception) {
            try { x.put("id", "err-" + System.currentTimeMillis()).put("st", "oshibka").put("msg", chisto(e)) } catch (ignored: Exception) {}
        }
        dobavit(x)
    }

    private inner class Most {
        @JavascriptInterface fun est(): Boolean = p.getString("k", null) != null

        @JavascriptInterface fun sohranit(key: String?): Boolean {
            val k = key?.trim() ?: ""
            return k.length >= 8 && sohranitKlyuch(k) && k == klyuch()
        }

        @JavascriptInterface fun udalit() { udalitKlyuch() }

        @JavascriptInterface fun zadachi(): String = this@Pryamoy.zadachi().toString()

        @JavascriptInterface fun ubrat(id: String?) {
            val z = this@Pryamoy.zadachi(); val o = JSONArray()
            for (i in 0 until z.length()) { val x = z.optJSONObject(i); if (x != null && id != x.optString("id")) o.put(x) }
            sohranitZadachi(o)
        }

        /** Starts one paid generation. Called only from the "Создать" button after a confirm. */
        @JavascriptInterface fun zapusk(tip: String?, prompt: String?, format: String?) {
            val t = tip ?: "kartinka"; val pr = prompt?.trim() ?: ""; val f = format ?: ""
            if (pr.isEmpty()) return
            bg.execute {
                val x = JSONObject()
                try { x.put("tip", t).put("prompt", pr).put("t", System.currentTimeMillis()) } catch (ignored: Exception) {}
                sozdat(x, telo(t, pr, f))
            }
        }

        /** Polls unfinished tasks once; the page calls it every few seconds while it is open. */
        @JavascriptInterface fun proverit() {
            bg.execute {
                var izm = false
                val z = this@Pryamoy.zadachi()
                for (i in 0 until z.length()) {
                    val x = z.optJSONObject(i)
                    if (x == null || x.optString("st") != "idet") continue
                    try {
                        val d = zapros("GET", "recordInfo?taskId=" + x.optString("id"), null).optJSONObject("data") ?: continue
                        val s = d.optString("state", "")
                        if (s == "success") {
                            val u = ssylki(d)
                            x.put("st", if (u.length() > 0) "gotovo" else "oshibka").put("urls", u)
                            val tk = JSONArray()
                            try { treki(JSONObject(d.optString("resultJson", "{}")), tk, 0) } catch (ignored: Exception) {}
                            if (tk.length() > 0) x.put("treki", tk)
                            if (u.length() == 0) x.put("msg", "Готово, но файла в ответе нет")
                            izm = true
                        } else if (s == "fail") {
                            val m = d.optString("failMsg", "")
                            x.put("st", "oshibka").put("msg", if (m.isEmpty()) "Не получилось, кредиты не списаны" else chisto(m))
                            izm = true
                        } else if (System.currentTimeMillis() - x.optLong("t") > 30 * 60_000L) {
                            x.put("st", "oshibka").put("msg", "Нет ответа 30 минут"); izm = true
                        }
                    } catch (e: Exception) {
                        if (e.message.toString().startsWith("Ключ")) {
                            try { x.put("st", "oshibka").put("msg", e.message); izm = true } catch (ignored: Exception) {}
                        }
                    }
                }
                if (izm) { synchronized(lock) { sohranitZadachi(z) }; js("pryamoyObnovit") }
            }
        }

        /** Any catalog model: body {model, input} built on the page from the model's schema. */
        @JavascriptInterface fun zapuskModel(model: String?, inputJson: String?, nazv: String?, tip: String?, prompt: String?) {
            bg.execute {
                val x = JSONObject()
                try {
                    x.put("tip", tip).put("nazv", nazv).put("prompt", prompt ?: "").put("t", System.currentTimeMillis())
                    sozdat(x, JSONObject().put("model", model).put("input", JSONObject(inputJson ?: "")))
                } catch (e: Exception) {
                    try { x.put("id", "err-" + System.currentTimeMillis()).put("st", "oshibka").put("msg", chisto(e)) } catch (ignored: Exception) {}
                    dobavit(x)
                }
            }
        }

        /** Uploads a file picked on the page (base64) and returns its temporary URL to window.pzZagr(id, url, err). */
        @JavascriptInterface fun zagruzit(id: String, b64: String?, name: String?, mime: String?) {
            bg.execute {
                var url: String? = null; var err: String? = null
                try { url = otpravitFayl(Base64.decode(b64, Base64.DEFAULT), name, mime) } catch (e: Exception) { err = chisto(e) }
                otvetZagruzki(id, url, err)
            }
        }

        /** Uploads one of the app's own example clips (viral templates) so a model can use it. */
        @JavascriptInterface fun zagruzitAsset(id: String, put: String?, mime: String?) {
            bg.execute {
                var url: String? = null; var err: String? = null
                try {
                    if (put == null || !put.startsWith("karkas/primery/") || put.contains("..")) throw Exception("Нет такого файла")
                    val bytes = a.assets.open(put).use { it.readBytes() }
                    url = otpravitFayl(bytes, put.substring(put.lastIndexOf('/') + 1), mime)
                } catch (e: Exception) { err = chisto(e) }
                otvetZagruzki(id, url, err)
            }
        }

        /** Text chat; answer goes to window.pzChat(text, err). */
        @JavascriptInterface fun chat(model: String?, messagesJson: String?) {
            bg.execute {
                var txt: String? = null; var err: String? = null
                try {
                    val k = klyuch() ?: throw Exception("Ключ не сохранён")
                    val telo = JSONObject().put("model", model).put("messages", JSONArray(messagesJson ?: "")).put("stream", false).put("max_tokens", 4096)
                    val c = URL("https://api.kie.ai/claude/v1/messages").openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 180000; c.doOutput = true; c.requestMethod = "POST"
                    c.setRequestProperty("Authorization", "Bearer $k")
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
                    val kod = c.responseCode
                    val j = JSONObject(chitat(c, kod))
                    val ct = j.optJSONArray("content")
                        ?: throw Exception(oshibka(j.optInt("code", kod), j.optString("msg", j.optJSONObject("error")?.optString("message") ?: "")))
                    val sb = StringBuilder()
                    for (i in 0 until ct.length()) { val bl = ct.optJSONObject(i); if (bl != null && bl.optString("type") == "text") sb.append(bl.optString("text")) }
                    txt = sb.toString()
                } catch (e: Exception) { err = chisto(e) }
                val t = txt; val er = err
                view.post { view.evaluateJavascript("window.pzChat&&pzChat(" + q(t) + "," + q(er) + ")", null) }
            }
        }

        @JavascriptInterface fun skachat(url: String?, mime: String?) {
            if (url == null || !url.startsWith("https://")) return
            a.runOnUiThread { a.download(view, url, view.settings.userAgentString, null, mime) }
        }
    }
}
