package ru.argeda.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Temporary "direct mode": while the site is down the owner pastes his own generation key
 * on his phone, and the app starts paid generations straight from the phone.
 * The key never reaches JS, the repo or the chat: it is encrypted with an Android Keystore key
 * and stored only in this app's private prefs (backup is off). "Удалить ключ" wipes it.
 */
class Pryamoy {
    private static final String API = "https://api.kie.ai/api/v1/jobs/";
    private static final String ALIAS = "argeda-pryamoy";
    private final MainActivity a;
    private final WebView view;
    private final SharedPreferences p;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    Pryamoy(MainActivity a, WebView v) {
        this.a = a; this.view = v;
        p = a.getSharedPreferences("pryamoy", Context.MODE_PRIVATE);
        v.addJavascriptInterface(new Most(), "ArgedaPryamoy");
    }

    // ---------- key storage ----------
    private SecretKey shifr() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private boolean sohranitKlyuch(String k) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, shifr());
            byte[] ct = c.doFinal(k.getBytes(StandardCharsets.UTF_8));
            p.edit().putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                    .putString("k", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    private String klyuch() {
        try {
            String iv = p.getString("iv", null), ct = p.getString("k", null);
            if (iv == null || ct == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, shifr(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private void udalitKlyuch() {
        p.edit().clear().apply();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS); } catch (Exception ignored) {}
    }

    // ---------- HTTP ----------
    private JSONObject zapros(String metod, String put, JSONObject telo) throws Exception {
        String k = klyuch();
        if (k == null) throw new Exception("Ключ не сохранён");
        HttpURLConnection c = (HttpURLConnection) new URL(API + put).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(40000);
        c.setRequestMethod(metod);
        c.setRequestProperty("Authorization", "Bearer " + k);
        c.setRequestProperty("Accept", "application/json");
        if (telo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(telo.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        int kod = c.getResponseCode();
        InputStream in = kod >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        c.disconnect();
        String s = b.toString("UTF-8");
        JSONObject j;
        try { j = new JSONObject(s); } catch (Exception e) { throw new Exception("Сервис ответил " + kod); }
        int code = j.optInt("code", kod);
        if (code != 200) throw new Exception(oshibka(code, j.optString("msg", "")));
        return j;
    }

    /** Text for the person: no provider names or hosts, network failures in plain words. */
    static String chisto(Throwable e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException)
            return "Нет связи с сервисом — проверьте интернет";
        if (e instanceof java.net.SocketTimeoutException) return "Сервис долго не отвечает, попробуйте ещё раз";
        if (e instanceof javax.net.ssl.SSLException) return "Не удалось установить защищённое соединение";
        if (e instanceof org.json.JSONException) return "Сервис ответил непонятно, попробуйте ещё раз";
        return chisto(String.valueOf(e.getMessage()));
    }
    static String chisto(String m) {
        if (m == null || m.isEmpty() || "null".equals(m)) return "Что-то пошло не так, попробуйте ещё раз";
        m = m.replaceAll("(?i)https?://\\S+", "").replaceAll("(?i)[a-z0-9.-]*(kie|redpanda)[a-z0-9.-]*", "сервис").trim();
        return m.length() > 160 ? m.substring(0, 157) + "…" : m;
    }

    private static String oshibka(int code, String msg) {
        switch (code) {
            case 401: return "Ключ не подходит — проверьте его";
            case 402: return "На счёте закончились кредиты";
            case 429: return "Слишком много запросов, подождите минуту";
            case 455: return "Сервис на обслуживании, попробуйте позже";
            default: return "Ошибка " + code + (msg == null || msg.isEmpty() ? "" : ": " + chisto(msg));
        }
    }

    // ---------- tasks ----------
    private JSONArray zadachi() { try { return new JSONArray(p.getString("z", "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void sohranitZadachi(JSONArray z) {
        JSONArray o = new JSONArray();
        for (int i = Math.max(0, z.length() - 40); i < z.length(); i++) o.put(z.opt(i));
        p.edit().putString("z", o.toString()).apply();
    }

    private static JSONObject telo(String tip, String prompt, String format) throws Exception {
        JSONObject in = new JSONObject();
        String model;
        if ("video".equals(tip)) {
            model = "wan/3-0-video";
            in.put("prompt", prompt).put("resolution", "720P").put("duration", 5)
              .put("aspect_ratio", format.isEmpty() ? "9:16" : format).put("audio", true);
        } else if ("muzyka".equals(tip)) {
            model = "ai-music-api/generate";
            in.put("custom_mode", false).put("instrumental", false).put("model", "V6")
              .put("prompt", prompt).put("style", prompt.length() > 900 ? prompt.substring(0, 900) : prompt);
        } else {
            model = "nano-banana-2-1";
            in.put("prompt", prompt).put("resolution", "1K").put("output_format", "jpg")
              .put("aspect_ratio", format.isEmpty() ? "3:4" : format);
        }
        return new JSONObject().put("model", model).put("input", in);
    }

    private static final Pattern URL_RE = Pattern.compile("https?://[^\"'\\s\\\\]+?\\.(?:jpg|jpeg|png|webp|mp4|mov|mp3|wav|m4a)(?:\\?[^\"'\\s\\\\]*)?", Pattern.CASE_INSENSITIVE);

    private static JSONArray ssylki(JSONObject data) {
        Set<String> u = new LinkedHashSet<>();
        try {
            String rj = data.optString("resultJson", "");
            if (!rj.isEmpty()) {
                JSONArray r = new JSONObject(rj).optJSONArray("resultUrls");
                if (r != null) for (int i = 0; i < r.length(); i++) u.add(r.optString(i));
            }
        } catch (Exception ignored) {}
        if (u.isEmpty()) {
            try { audio(new JSONObject(data.optString("resultJson", "{}")), u, 0); } catch (Exception ignored) {}   // music: audio_url per track, at any depth
        }
        if (u.isEmpty()) {
            JSONObject d2; try { d2 = new JSONObject(data.toString()); d2.remove("param"); d2.remove("paramJson"); } catch (Exception e) { d2 = data; }
            Matcher m = URL_RE.matcher(d2.toString().replace("\\/", "/"));
            while (m.find()) { String s = m.group(); if (!s.contains("stream")) u.add(s); }
        }
        JSONArray o = new JSONArray(); for (String s : u) o.put(s); return o;
    }

    private static void audio(Object o, Set<String> u, int d) {
        if (d > 6 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o; String au = j.optString("audio_url", "");
            if (au.startsWith("http")) u.add(au);
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) audio(j.opt(it.next()), u, d + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o; for (int i = 0; i < a.length(); i++) audio(a.opt(i), u, d + 1);
        }
    }

    /** Music tracks: {id, url, title, dur} for each object that has both audio_url and id. */
    private static void treki(Object o, JSONArray out, int d) {
        if (d > 6 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o; String au = j.optString("audio_url", ""), id = j.optString("id", "");
            if (au.startsWith("http") && !id.isEmpty()) {
                try { out.put(new JSONObject().put("id", id).put("url", au).put("title", j.optString("title", "")).put("dur", j.optDouble("duration", 0)).put("img", j.optString("image_url", "")).put("tekst", j.optString("prompt", "")).put("stil", j.optString("tags", ""))); } catch (Exception ignored) {}
                return;
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) treki(j.opt(it.next()), out, d + 1);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o; for (int i = 0; i < a.length(); i++) treki(a.opt(i), out, d + 1);
        }
    }

    private void js(String fn) { view.post(() -> view.evaluateJavascript("window." + fn + "&&window." + fn + "()", null)); }
    private void toast(String t) { view.post(() -> view.evaluateJavascript("window.toast&&toast(" + JSONObject.quote(t) + ")", null)); }

    private String otpravitFayl(byte[] data, String name, String mime) throws Exception {
        String k = klyuch(); if (k == null) throw new Exception("Ключ не сохранён");
        String gr = "----argeda" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL("https://kieai.redpandaai.co/api/file-stream-upload").openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(120000); c.setDoOutput(true); c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + k);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + gr);
        String fn = (name == null || name.isEmpty() ? "file" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        try (OutputStream o = c.getOutputStream()) {
            o.write(("--" + gr + "\r\nContent-Disposition: form-data; name=\"uploadPath\"\r\n\r\nargeda-app\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(("--" + gr + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fn + "\"\r\nContent-Type: " + (mime == null || mime.isEmpty() ? "application/octet-stream" : mime) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.write(data);
            o.write(("\r\n--" + gr + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int kod = c.getResponseCode();
        InputStream in = kod >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        JSONObject j = new JSONObject(b.toString("UTF-8"));
        if (j.optInt("code", kod) != 200) throw new Exception(oshibka(j.optInt("code", kod), j.optString("msg", "")));
        return j.getJSONObject("data").getString("downloadUrl");
    }

    private void otvetZagruzki(String id, String u, String er) {
        view.post(() -> view.evaluateJavascript("window.pzZagr&&pzZagr(" + JSONObject.quote(id) + "," + (u == null ? "null" : JSONObject.quote(u)) + "," + (er == null ? "null" : JSONObject.quote(er)) + ")", null));
    }

    private class Most {
        @JavascriptInterface public boolean est() { return p.getString("k", null) != null; }
        @JavascriptInterface public boolean sohranit(String k) {
            k = k == null ? "" : k.trim();
            return k.length() >= 8 && sohranitKlyuch(k) && k.equals(klyuch());
        }
        @JavascriptInterface public void udalit() { udalitKlyuch(); }
        @JavascriptInterface public String zadachi() { return Pryamoy.this.zadachi().toString(); }
        @JavascriptInterface public void ubrat(String id) {
            JSONArray z = Pryamoy.this.zadachi(), o = new JSONArray();
            for (int i = 0; i < z.length(); i++) { JSONObject x = z.optJSONObject(i); if (x != null && !id.equals(x.optString("id"))) o.put(x); }
            sohranitZadachi(o);
        }

        /** Starts one paid generation. Called only from the "Создать" button after a confirm. */
        @JavascriptInterface public void zapusk(String tip, String prompt, String format) {
            final String t = tip == null ? "kartinka" : tip, pr = prompt == null ? "" : prompt.trim(), f = format == null ? "" : format;
            if (pr.isEmpty()) return;
            bg.execute(() -> {
                JSONObject x = new JSONObject();
                try {
                    x.put("tip", t).put("prompt", pr).put("t", System.currentTimeMillis());
                    JSONObject j = zapros("POST", "createTask", telo(t, pr, f));
                    String id = j.optJSONObject("data") != null ? j.optJSONObject("data").optString("taskId", "") : "";
                    if (id.isEmpty()) throw new Exception("Сервис не вернул номер задачи");
                    x.put("id", id).put("st", "idet");
                } catch (Exception e) {
                    try { x.put("id", "err-" + System.currentTimeMillis()).put("st", "oshibka").put("msg", chisto(e)); } catch (Exception ignored) {}
                }
                synchronized (Pryamoy.this) { JSONArray z = Pryamoy.this.zadachi(); z.put(x); sohranitZadachi(z); }
                js("pryamoyObnovit");
            });
        }

        /** Polls unfinished tasks once; the page calls it every few seconds while it is open. */
        @JavascriptInterface public void proverit() {
            bg.execute(() -> {
                boolean izm = false;
                JSONArray z = Pryamoy.this.zadachi();
                for (int i = 0; i < z.length(); i++) {
                    JSONObject x = z.optJSONObject(i);
                    if (x == null || !"idet".equals(x.optString("st"))) continue;
                    try {
                        JSONObject d = zapros("GET", "recordInfo?taskId=" + x.optString("id"), null).optJSONObject("data");
                        if (d == null) continue;
                        String s = d.optString("state", "");
                        if ("success".equals(s)) {
                            JSONArray u = ssylki(d);
                            x.put("st", u.length() > 0 ? "gotovo" : "oshibka").put("urls", u);
                            JSONArray tk = new JSONArray(); try { treki(new JSONObject(d.optString("resultJson", "{}")), tk, 0); } catch (Exception ignored) {} if (tk.length() > 0) x.put("treki", tk);
                            if (u.length() == 0) x.put("msg", "Готово, но файла в ответе нет");
                            izm = true;
                        } else if ("fail".equals(s)) {
                            String m = d.optString("failMsg", "");
                            x.put("st", "oshibka").put("msg", m.isEmpty() ? "Не получилось, кредиты не списаны" : chisto(m));
                            izm = true;
                        } else if (System.currentTimeMillis() - x.optLong("t") > 30 * 60_000L) {
                            x.put("st", "oshibka").put("msg", "Нет ответа 30 минут"); izm = true;
                        }
                    } catch (Exception e) {
                        if (String.valueOf(e.getMessage()).startsWith("Ключ")) { try { x.put("st", "oshibka").put("msg", e.getMessage()); izm = true; } catch (Exception ignored) {} }
                    }
                }
                if (izm) { synchronized (Pryamoy.this) { sohranitZadachi(z); } js("pryamoyObnovit"); }
            });
        }

        /** Any catalog model: body {model, input} built on the page from the model's schema. */
        @JavascriptInterface public void zapuskModel(String model, String inputJson, String nazv, String tip, String prompt) {
            bg.execute(() -> {
                JSONObject x = new JSONObject();
                try {
                    x.put("tip", tip).put("nazv", nazv).put("prompt", prompt == null ? "" : prompt).put("t", System.currentTimeMillis());
                    JSONObject telo = new JSONObject().put("model", model).put("input", new JSONObject(inputJson));
                    JSONObject j = zapros("POST", "createTask", telo);
                    String id = j.optJSONObject("data") != null ? j.optJSONObject("data").optString("taskId", "") : "";
                    if (id.isEmpty()) throw new Exception("Сервис не вернул номер задачи");
                    x.put("id", id).put("st", "idet");
                } catch (Exception e) {
                    try { x.put("id", "err-" + System.currentTimeMillis()).put("st", "oshibka").put("msg", chisto(e)); } catch (Exception ignored) {}
                }
                synchronized (Pryamoy.this) { JSONArray z = Pryamoy.this.zadachi(); z.put(x); sohranitZadachi(z); }
                js("pryamoyObnovit");
            });
        }

        /** Uploads a file picked on the page (base64) and returns its temporary URL to window.pzZagr(id, url, err). */
        @JavascriptInterface public void zagruzit(String id, String b64, String name, String mime) {
            bg.execute(() -> {
                String url = null, err = null;
                try { url = otpravitFayl(Base64.decode(b64, Base64.DEFAULT), name, mime); } catch (Exception e) { err = chisto(e); }
                otvetZagruzki(id, url, err);
            });
        }

        /** Uploads one of the app's own example clips (viral templates) so a model can use it. */
        @JavascriptInterface public void zagruzitAsset(String id, String put, String mime) {
            bg.execute(() -> {
                String url = null, err = null;
                try {
                    if (put == null || !put.startsWith("karkas/primery/") || put.contains("..")) throw new Exception("Нет такого файла");
                    InputStream in = a.getAssets().open(put);
                    ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close();
                    url = otpravitFayl(b.toByteArray(), put.substring(put.lastIndexOf('/') + 1), mime);
                } catch (Exception e) { err = chisto(e); }
                otvetZagruzki(id, url, err);
            });
        }

        /** Text chat; answer goes to window.pzChat(text, err). */
        @JavascriptInterface public void chat(String model, String messagesJson) {
            bg.execute(() -> {
                String txt = null, err = null;
                try {
                    String k = klyuch(); if (k == null) throw new Exception("Ключ не сохранён");
                    JSONObject telo = new JSONObject().put("model", model).put("messages", new JSONArray(messagesJson)).put("stream", false).put("max_tokens", 4096);
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.kie.ai/claude/v1/messages").openConnection();
                    c.setConnectTimeout(20000); c.setReadTimeout(180000); c.setDoOutput(true); c.setRequestMethod("POST");
                    c.setRequestProperty("Authorization", "Bearer " + k);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) { o.write(telo.toString().getBytes(StandardCharsets.UTF_8)); }
                    int kod = c.getResponseCode();
                    InputStream in = kod >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
                    JSONObject j = new JSONObject(b.toString("UTF-8"));
                    JSONArray ct = j.optJSONArray("content");
                    if (ct == null) throw new Exception(oshibka(j.optInt("code", kod), j.optString("msg", j.optJSONObject("error") != null ? j.optJSONObject("error").optString("message") : "")));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ct.length(); i++) { JSONObject bl = ct.optJSONObject(i); if (bl != null && "text".equals(bl.optString("type"))) sb.append(bl.optString("text")); }
                    txt = sb.toString();
                } catch (Exception e) { err = chisto(e); }
                final String t = txt, er = err;
                view.post(() -> view.evaluateJavascript("window.pzChat&&pzChat(" + (t == null ? "null" : JSONObject.quote(t)) + "," + (er == null ? "null" : JSONObject.quote(er)) + ")", null));
            });
        }

        @JavascriptInterface public void skachat(String url, String mime) {
            if (url == null || !url.startsWith("https://")) return;
            a.runOnUiThread(() -> a.download(view, url, view.getSettings().getUserAgentString(), null, mime));
        }
    }
}
