package app.photovault;

import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Microsoft OneDrive through the official Microsoft Graph API. Sign-in: OAuth 2.0 authorization code with PKCE in the
 * phone's browser (no client secret, no Microsoft library). The app asks only for its own folder, OneDrive/Apps/PhotoVault,
 * not the rest of the user's files; if Microsoft refuses app folders for this app registration, the user can choose full
 * access instead and the vault goes in a normal folder "PhotoVault". The refresh token is kept encrypted with a key in the
 * phone's secure hardware.
 */
final class OneDrive extends Cloud {
    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0/";
    static final String REDIRECT = "io.github.rimaturus.photovault://auth";
    static final String SCOPE_APP_FOLDER = "Files.ReadWrite.AppFolder offline_access";
    static final String SCOPE_ALL_FILES = "Files.ReadWrite offline_access"; // fallback, only if the user chooses it
    private static final String KEY_ALIAS = "photovault_onedrive_token";

    private final Store st;
    private volatile String access; // in memory only
    private long expires;

    OneDrive(Store st) { this.st = st; }

    /** The build has a Microsoft app registration (see PUBLISHING.md). */
    static boolean configured() { return !Config.ONEDRIVE_CLIENT_ID.isEmpty(); }

    @Override String name() { return "OneDrive"; }

    @Override String trashName() { return "OneDrive recycle bin"; }

    /** The vault uses full OneDrive access (folder "PhotoVault") instead of the app folder. Kept across sign-outs. */
    boolean allFiles() { return st.prefs.getBoolean("od_all", false); }

    private String scope() { return allFiles() ? SCOPE_ALL_FILES : SCOPE_APP_FOLDER; }

    @Override boolean hasSession() { return st.prefs.contains("od_refresh"); }

    @Override void signOut() { // not synchronized: must not wait for a token refresh on a slow network (main thread)
        st.prefs.edit().remove("od_refresh").apply();
        access = null;
    }

    // ---------------------------------------------------------------- sign-in (browser, PKCE)

    /** Microsoft's sign-in page for the browser. The answer comes back to MainActivity through REDIRECT. */
    String authUrl(boolean allFiles) throws Exception {
        String verifier = b64url(Vault.random(32)), state = b64url(Vault.random(16)), scope = allFiles ? SCOPE_ALL_FILES : SCOPE_APP_FOLDER;
        st.prefs.edit().putString("od_verifier", verifier).putString("od_state", state).putString("od_scope_req", scope).commit();
        byte[] challenge = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        return LOGIN + "authorize?client_id=" + Uri.encode(Config.ONEDRIVE_CLIENT_ID) + "&response_type=code&response_mode=query"
                + "&redirect_uri=" + Uri.encode(REDIRECT) + "&scope=" + Uri.encode(scope) + "&state=" + state
                + "&code_challenge=" + b64url(challenge) + "&code_challenge_method=S256&prompt=select_account";
    }

    /** Exchanges the code from the redirect for tokens. Background thread. */
    synchronized void redeem(Uri answer) throws Exception {
        String state = answer.getQueryParameter("state"), code = answer.getQueryParameter("code");
        if (state == null || !state.equals(st.prefs.getString("od_state", null)))
            throw new IOException("the sign-in answer doesn't match the request: start again");
        String verifier = st.prefs.getString("od_verifier", ""), scope = st.prefs.getString("od_scope_req", SCOPE_APP_FOLDER);
        st.prefs.edit().remove("od_state").remove("od_verifier").remove("od_scope_req").commit(); // one answer per request
        String err = answer.getQueryParameter("error");
        if (err != null) throw new IOException("Microsoft answered " + err);
        if (code == null) throw new IOException("Microsoft sent no sign-in code");
        access = null;
        tokens("grant_type=authorization_code&client_id=" + Uri.encode(Config.ONEDRIVE_CLIENT_ID) + "&code=" + Uri.encode(code)
                + "&redirect_uri=" + Uri.encode(REDIRECT) + "&code_verifier=" + Uri.encode(verifier) + "&scope=" + Uri.encode(scope));
        st.prefs.edit().putBoolean("od_all", scope.equals(SCOPE_ALL_FILES)).commit();
        Journal.add("OneDrive: signed in (" + (scope.equals(SCOPE_APP_FOLDER) ? "app folder only" : "all files") + ")");
    }

    /** A valid access token, refreshed when needed (they last about an hour). */
    private synchronized String token() throws Exception {
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access;
        String rt = refreshToken();
        if (rt == null) throw new ApiError(401, "not signed in to OneDrive");
        tokens("grant_type=refresh_token&client_id=" + Uri.encode(Config.ONEDRIVE_CLIENT_ID) + "&refresh_token=" + Uri.encode(rt)
                + "&scope=" + Uri.encode(scope()));
        return access;
    }

    private void tokens(String form) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(LOGIN + "token").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] b = form.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            int code = c.getResponseCode();
            String body = text(c, code);
            Journal.add("POST /oauth2/v2.0/token -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
            JSONObject j = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            if (code >= 400) {
                String e = j.optString("error");
                Journal.add("  " + e + ": " + j.optString("error_description").split("\\r?\\n")[0]);
                if ("invalid_grant".equals(e) || "interaction_required".equals(e)) { signOut(); throw new ApiError(401, "OneDrive sign-in expired"); }
                throw new ApiError(code, "Microsoft sign-in answered HTTP " + code + " (" + e + ")");
            }
            access = j.getString("access_token");
            expires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
            String rt = j.optString("refresh_token", "");
            if (!rt.isEmpty()) st.prefs.edit().putString("od_refresh", seal(rt)).apply(); // rotated on every use
        } finally { c.disconnect(); }
    }

    // refresh token: AES-GCM with a key that never leaves the phone's secure hardware (no fingerprint needed)
    private static SecretKey tokenKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey k = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (k != null) return k;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }

    private static String seal(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, tokenKey());
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private String refreshToken() {
        String s = st.prefs.getString("od_refresh", "");
        int i = s.indexOf(':');
        if (i < 0) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, tokenKey(), new GCMParameterSpec(128, Base64.decode(s.substring(0, i), Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(s.substring(i + 1), Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) { Journal.add("OneDrive: stored sign-in unreadable: " + e); return null; }
    }

    static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }

    // ---------------------------------------------------------------- Graph requests

    private static String text(HttpURLConnection c, int code) throws IOException {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try (InputStream i = in) { return new String(readAll(i), "UTF-8"); }
    }

    interface Body { void write(HttpURLConnection c) throws IOException; }

    /**
     * One Graph request, retried on 429/503 (waiting as long as Microsoft asks, at most 60 s) and once after a 401
     * with a fresh token. Logs method, path and status only: never tokens or file names.
     */
    private JSONObject call(String method, String url, Body body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long t0 = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod(method);
                c.setConnectTimeout(30_000);
                c.setReadTimeout(180_000);
                c.setRequestProperty("Authorization", "Bearer " + token());
                c.setRequestProperty("Accept", "application/json");
                if (body != null) body.write(c);
                int code = c.getResponseCode();
                String t = text(c, code), path = url.split("\\?")[0].replaceFirst("https://[^/]+", "");
                Journal.add(method + " " + path.replaceAll(":/[^:]+(:|$)", ":/<name>$1") + " -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (code == 401 && attempt == 0) { synchronized (this) { access = null; } continue; }
                if ((code == 429 || code == 503) && attempt < 4) {
                    long wait = Math.min(60, Math.max(2, c.getHeaderFieldLong("Retry-After", 5)));
                    Journal.add("  OneDrive asks to wait " + wait + " s");
                    Thread.sleep(wait * 1000);
                    continue;
                }
                if (code >= 400) {
                    JSONObject e;
                    try { e = new JSONObject(t).optJSONObject("error"); } catch (Exception notJson) { e = null; }
                    String what = e == null ? "" : e.optString("code");
                    Journal.add("  " + what + ": " + (e == null ? "" : e.optString("message")));
                    throw new ApiError(code, "OneDrive answered HTTP " + code + (what.isEmpty() ? "" : " (" + what + ")"));
                }
                return t.isEmpty() ? new JSONObject() : new JSONObject(t);
            } finally { c.disconnect(); }
        }
    }

    private static Body json(final JSONObject j) {
        return new Body() { public void write(HttpURLConnection c) throws IOException {
            byte[] b = j.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }};
    }

    /** The app's own folder, OneDrive/Apps/PhotoVault (created by OneDrive on first use), or "PhotoVault" with full access. */
    @Override String vaultFolder() throws Exception {
        if (allFiles()) return folder(call("GET", GRAPH + "/me/drive/root?$select=id", null).getString("id"), "PhotoVault");
        return call("GET", GRAPH + "/me/drive/special/approot?$select=id", null).getString("id");
    }

    @Override String folder(String parentId, String name) throws Exception {
        String byPath = GRAPH + "/me/drive/items/" + parentId + ":/" + Uri.encode(name) + "?$select=id";
        try { return call("GET", byPath, null).getString("id"); }
        catch (ApiError e) { if (e.code != 404) throw e; }
        JSONObject f = new JSONObject().put("name", name).put("folder", new JSONObject()).put("@microsoft.graph.conflictBehavior", "fail");
        try { return call("POST", GRAPH + "/me/drive/items/" + parentId + "/children", json(f)).getString("id"); }
        catch (ApiError e) { if (e.code != 409) throw e; return call("GET", byPath, null).getString("id"); } // made meanwhile
    }

    @Override Listing listFiles(String folderId, int max) throws Exception {
        Listing res = new Listing();
        String url = GRAPH + "/me/drive/items/" + folderId + "/children?$top=200&$select=id,name,createdDateTime,file";
        while (url != null && res.files.size() < max) {
            JSONObject r = call("GET", url, null);
            JSONArray v = r.optJSONArray("value");
            for (int i = 0; v != null && i < v.length() && res.files.size() < max; i++) {
                JSONObject n = v.getJSONObject(i);
                if (n.has("file")) res.files.add(node(n.getString("id"), n.optString("name"), n.optString("createdDateTime")));
            }
            url = r.optString("@odata.nextLink", "");
            if (url.isEmpty()) { url = null; res.complete = true; }
        }
        return res;
    }

    @Override JSONObject upload(final File png, String name, String parentId, final Progress p) throws Exception {
        JSONObject n = call("PUT", GRAPH + "/me/drive/items/" + parentId + ":/" + Uri.encode(name) + ":/content?@microsoft.graph.conflictBehavior=fail",
                new Body() { public void write(HttpURLConnection c) throws IOException {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    c.setFixedLengthStreamingMode(png.length());
                    try (InputStream in = new FileInputStream(png); OutputStream o = c.getOutputStream()) { copy(in, o, png.length(), p); }
                }});
        JSONObject f = n.optJSONObject("file");
        return new JSONObject().put("id", n.getString("id")).put("type", f == null ? "" : f.optString("mimeType"));
    }

    /** Graph answers with a redirect to a short-lived download link; that link is fetched without our token. */
    @Override void download(String id, File dst, Progress p) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long t0 = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(GRAPH + "/me/drive/items/" + id + "/content").openConnection();
            String location;
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(30_000);
                c.setReadTimeout(180_000);
                c.setRequestProperty("Authorization", "Bearer " + token());
                int code = c.getResponseCode();
                Journal.add("GET /me/drive/items/<id>/content -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (code == 401 && attempt == 0) { synchronized (this) { access = null; } continue; }
                if ((code == 429 || code == 503) && attempt < 4) { Thread.sleep(1000 * Math.min(60, Math.max(2, c.getHeaderFieldLong("Retry-After", 5)))); continue; }
                if (code == 200) { save(c.getInputStream(), c.getContentLengthLong(), dst, p); return; }
                if (code / 100 != 3) { text(c, code); throw new ApiError(code, "OneDrive answered HTTP " + code + " on a download"); }
                location = c.getHeaderField("Location");
            } finally { c.disconnect(); }
            HttpURLConnection d = (HttpURLConnection) new URL(location).openConnection();
            try {
                d.setConnectTimeout(30_000);
                d.setReadTimeout(180_000);
                int code = d.getResponseCode();
                if (code >= 400) throw new ApiError(code == 401 || code == 403 ? 410 : code, "OneDrive download link answered HTTP " + code);
                save(d.getInputStream(), d.getContentLengthLong(), dst, p);
                Journal.add("  downloaded " + dst.length() / 1024 + " KB (" + (System.currentTimeMillis() - t0) + " ms)");
                return;
            } finally { d.disconnect(); }
        }
    }

    /** To the OneDrive recycle bin, one by one. */
    @Override void trash(List<String> ids) throws Exception {
        for (String id : ids) call("DELETE", GRAPH + "/me/drive/items/" + id, null);
    }

    @Override String[] storage() throws Exception {
        JSONObject q;
        try { q = call("GET", GRAPH + "/me/drive?$select=quota", null).optJSONObject("quota"); }
        catch (ApiError e) {
            if (e.code != 403) throw e;
            return new String[]{"warn", "OneDrive doesn't show the storage use to an app limited to its own folder: check it on onedrive.live.com"};
        }
        if (q == null) return new String[]{"warn", "OneDrive didn't report storage"};
        long used = q.optLong("used"), total = q.optLong("total"), free = q.optLong("remaining");
        return new String[]{free < (1L << 30) ? "warn" : "ok",
                "OneDrive: " + Store.human(used) + " used of " + Store.human(total) + " (" + Store.human(free) + " free)"};
    }
}
