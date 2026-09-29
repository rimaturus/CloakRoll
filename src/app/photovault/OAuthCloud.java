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
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * A cloud with an official API and OAuth 2.0 sign-in in the phone's browser: authorization code with PKCE, no client
 * secret, no vendor library (OneDrive, Google Drive). The access token stays in memory; the refresh token is kept
 * encrypted with a key in the phone's secure hardware. Settings keys start with `pre` ("od", "gd").
 */
abstract class OAuthCloud extends Cloud {
    final Store st;
    private final String pre, alias, tokenUrl;
    private volatile String access; // in memory only
    private long expires;

    OAuthCloud(Store st, String pre, String alias, String tokenUrl) { this.st = st; this.pre = pre; this.alias = alias; this.tokenUrl = tokenUrl; }

    /** "Microsoft", "Google": who answers the sign-in. */
    abstract String company();

    abstract String clientId();

    abstract String redirect();

    /** Extra form fields of token requests for this scope ("" if the service doesn't want the scope there). */
    abstract String scopeParam(String scope);

    @Override boolean hasSession() { return st.prefs.contains(pre + "_refresh"); }

    @Override void signOut() { // not synchronized: must not wait for a token refresh on a slow network (main thread)
        st.prefs.edit().remove(pre + "_refresh").apply();
        access = null;
    }

    /** A new PKCE verifier and state for one browser sign-in. Returns the URL parameters that carry them. */
    String pkce(String scope) throws Exception {
        String verifier = b64url(Vault.random(32)), state = b64url(Vault.random(16));
        st.prefs.edit().putString(pre + "_verifier", verifier).putString(pre + "_state", state).putString(pre + "_scope_req", scope).commit();
        byte[] challenge = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        return "&state=" + state + "&code_challenge=" + b64url(challenge) + "&code_challenge_method=S256";
    }

    /** The browser's answer belongs to the sign-in started last on this phone. */
    boolean expects(Uri answer) {
        String state = answer.getQueryParameter("state");
        return state != null && state.equals(st.prefs.getString(pre + "_state", null));
    }

    /** Exchanges the code from the redirect for tokens. Returns the scope that was asked for. Background thread. */
    synchronized String redeem(Uri answer) throws Exception {
        if (!expects(answer)) throw new IOException(st.app.getString(R.string.od_err_state));
        String code = answer.getQueryParameter("code"), verifier = st.prefs.getString(pre + "_verifier", ""),
                scope = st.prefs.getString(pre + "_scope_req", "");
        st.prefs.edit().remove(pre + "_state").remove(pre + "_verifier").remove(pre + "_scope_req").commit(); // one answer per request
        String err = answer.getQueryParameter("error");
        if ("access_denied".equals(err)) throw new IOException(st.app.getString(R.string.od_err_cancelled));
        if (err != null || code == null) throw new IOException(st.app.getString(R.string.err_answer, company(), err != null ? err : "no code"));
        access = null;
        tokens("grant_type=authorization_code&client_id=" + Uri.encode(clientId()) + "&code=" + Uri.encode(code)
                + "&redirect_uri=" + Uri.encode(redirect()) + "&code_verifier=" + Uri.encode(verifier) + scopeParam(scope));
        return scope;
    }

    /** The scope a refresh asks for (OneDrive: app folder or all files). */
    String refreshScope() { return ""; }

    /** A valid access token, refreshed when needed (they last about an hour). */
    synchronized String token() throws Exception {
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access;
        String rt = refreshToken();
        if (rt == null) throw new ApiError(401, "not signed in to " + name());
        tokens("grant_type=refresh_token&client_id=" + Uri.encode(clientId()) + "&refresh_token=" + Uri.encode(rt) + scopeParam(refreshScope()));
        return access;
    }

    /** The next request gets a fresh token (after a 401). */
    synchronized void dropToken() { access = null; }

    private void tokens(String form) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(tokenUrl).openConnection();
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
            Journal.add("POST " + tokenUrl.replaceFirst("https://[^/]+", "") + " -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
            JSONObject j;
            try { j = body.isEmpty() ? new JSONObject() : new JSONObject(body); } catch (Exception notJson) { j = new JSONObject(); }
            if (code >= 400) {
                String e = j.optString("error");
                Journal.add("  " + e + ": " + j.optString("error_description").split("\\r?\\n")[0]);
                if ("invalid_grant".equals(e) || "interaction_required".equals(e)) { signOut(); throw new ApiError(401, name() + " sign-in expired"); }
                throw new ApiError(code, company() + " sign-in answered HTTP " + code + " (" + e + ")");
            }
            access = j.getString("access_token");
            expires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
            String rt = j.optString("refresh_token", "");
            if (!rt.isEmpty()) st.prefs.edit().putString(pre + "_refresh", seal(rt)).apply(); // Microsoft rotates it on every use
        } finally { c.disconnect(); }
    }

    // refresh token: AES-GCM with a key that never leaves the phone's secure hardware (no fingerprint needed)
    private SecretKey tokenKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey k = (SecretKey) ks.getKey(alias, null);
        if (k != null) return k;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }

    private String seal(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, tokenKey());
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private String refreshToken() {
        String s = st.prefs.getString(pre + "_refresh", "");
        int i = s.indexOf(':');
        if (i < 0) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, tokenKey(), new GCMParameterSpec(128, Base64.decode(s.substring(0, i), Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(s.substring(i + 1), Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) { Journal.add(name() + ": stored sign-in unreadable: " + e); return null; }
    }

    static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }

    // ---------------------------------------------------------------- request helpers

    static String text(HttpURLConnection c, int code) throws IOException {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try (InputStream i = in) { return new String(readAll(i), "UTF-8"); }
    }

    interface Body { void write(HttpURLConnection c) throws IOException; }

    static Body json(final JSONObject j) {
        return new Body() { public void write(HttpURLConnection c) throws IOException {
            byte[] b = j.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }};
    }
}
