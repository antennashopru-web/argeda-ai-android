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

    private static String oshibka(int code, String msg) {
        switch (code) {
            case 401: return "Ключ не подходит — проверьте его";
            case 402: return "На счёте закончились кредиты";
            case 429: return "Слишком много запросов, подождите минуту";
            case 455: return "Сервис на обслуживании, попробуйте позже";
            default: return "Ошибка " + code + (msg.isEmpty() ? "" : ": " + msg);
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
            Matcher m = URL_RE.matcher(data.toString().replace("\\/", "/"));
            while (m.find()) { String s = m.group(); if (!s.contains("stream")) u.add(s); }
        }
        JSONArray o = new JSONArray(); for (String s : u) o.put(s); return o;
    }

    private void js(String fn) { view.post(() -> view.evaluateJavascript("window." + fn + "&&window." + fn + "()", null)); }
    private void toast(String t) { view.post(() -> view.evaluateJavascript("window.toast&&toast(" + JSONObject.quote(t) + ")", null)); }

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
                    try { x.put("id", "err-" + System.currentTimeMillis()).put("st", "oshibka").put("msg", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
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
                            if (u.length() == 0) x.put("msg", "Готово, но файла в ответе нет");
                            izm = true;
                        } else if ("fail".equals(s)) {
                            String m = d.optString("failMsg", "");
                            x.put("st", "oshibka").put("msg", m.isEmpty() ? "Не получилось, кредиты не списаны" : m);
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

        @JavascriptInterface public void skachat(String url, String mime) {
            if (url == null || !url.startsWith("https://")) return;
            a.runOnUiThread(() -> a.download(view, url, view.getSettings().getUserAgentString(), null, mime));
        }
    }
}
