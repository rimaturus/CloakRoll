package app.photovault;

import android.net.Uri;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Google Drive through the official Drive API v3, signed in with OAuth in the browser (OAuthCloud). The app asks only
 * for `drive.file`: it sees the files it created itself (the folder "Cloakroll") and nothing else in the user's Drive.
 * Google allows browser sign-in with PKCE only for its "iOS" client type, whose redirect is the bundle id as a URI
 * scheme; that client works from Android the same way (see PUBLISHING.md).
 */
final class GoogleDrive extends OAuthCloud {
    static final String API = "https://www.googleapis.com/drive/v3", UPLOAD = "https://www.googleapis.com/upload/drive/v3";
    static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String REDIRECT = "io.github.rimaturus.photovault:/oauth2redirect";
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder", VAULT = "Cloakroll";

    GoogleDrive(Store st) { super(st, "gd", "photovault_google_token", "https://oauth2.googleapis.com/token"); }

    /** The build has a Google OAuth client (see PUBLISHING.md). */
    static boolean configured() { return !Config.GOOGLE_CLIENT_ID.isEmpty(); }

    @Override String name() { return "Google Drive"; }

    @Override String company() { return "Google"; }

    @Override String clientId() { return Config.GOOGLE_CLIENT_ID; }

    @Override String redirect() { return REDIRECT; }

    @Override String scopeParam(String scope) { return ""; }

    @Override String trashName() { return st.app.getString(R.string.trash_google); }

    /** Google's sign-in page for the browser. The answer comes back to MainActivity through REDIRECT. */
    String authUrl() throws Exception {
        return AUTH + "?client_id=" + Uri.encode(Config.GOOGLE_CLIENT_ID) + "&response_type=code&redirect_uri=" + Uri.encode(REDIRECT)
                + "&scope=" + Uri.encode(SCOPE) + pkce(SCOPE) + "&prompt=select_account";
    }

    @Override synchronized String redeem(Uri answer) throws Exception {
        String scope = super.redeem(answer);
        Journal.add("Google Drive: signed in");
        return scope;
    }

    // ---------------------------------------------------------------- Drive requests

    /**
     * One Drive request, retried on rate limits and server errors (up to 4 times, waiting longer each time) and once
     * after a 401 with a fresh token. Logs method, path and status only: never tokens or file names.
     */
    private JSONObject call(String method, String url, Body body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long t0 = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
                else c.setRequestMethod(method);
                c.setConnectTimeout(30_000);
                c.setReadTimeout(180_000);
                c.setRequestProperty("Authorization", "Bearer " + token());
                c.setRequestProperty("Accept", "application/json");
                if (body != null) body.write(c);
                int code = c.getResponseCode();
                String t = text(c, code);
                Journal.add(method + " " + url.split("\\?")[0].replaceFirst("https://[^/]+", "") + " -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (code == 401 && attempt == 0) { dropToken(); continue; }
                String reason = code >= 400 ? reason(t) : "";
                // a POST that failed with a server error may still have been done: never repeated blindly
                if (retry(code, reason) && attempt < 4 && (!"POST".equals(method) || code == 429 || code == 403)) { Thread.sleep(2000L << attempt); continue; }
                if (code >= 400) throw error(code, reason);
                return t.isEmpty() ? new JSONObject() : new JSONObject(t);
            } finally { c.disconnect(); }
        }
    }

    /** Google's error reason ("storageQuotaExceeded", "rateLimitExceeded"...), logged. */
    private static String reason(String body) {
        try {
            JSONObject e = new JSONObject(body).getJSONObject("error");
            JSONArray es = e.optJSONArray("errors");
            String r = es != null && es.length() > 0 ? es.getJSONObject(0).optString("reason") : e.optString("status");
            Journal.add("  " + r + ": " + e.optString("message"));
            return r;
        } catch (Exception notJson) { return ""; }
    }

    private static boolean retry(int code, String reason) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504
                || (code == 403 && ("rateLimitExceeded".equals(reason) || "userRateLimitExceeded".equals(reason)));
    }

    /** 403 is also "Drive full": not a sign-in problem, so it doesn't ask to sign in again. */
    private ApiError error(int code, String reason) {
        if ("storageQuotaExceeded".equals(reason)) return new ApiError(507, st.app.getString(R.string.e_full, name()));
        // other 403s (daily limits, a disabled API...): signing in again wouldn't help, so not reported as a sign-in problem
        if (code == 403 && !"insufficientPermissions".equals(reason) && !"authError".equals(reason)) code = 400;
        return new ApiError(code, "Google Drive answered HTTP " + code + (reason.isEmpty() ? "" : " (" + reason + ")"));
    }

    private static String q(String s) { return Uri.encode(s); }

    /** The folder "Cloakroll" at the top of My Drive (one the app made; drive.file shows no other). */
    @Override String vaultFolder() throws Exception { return folder("root", VAULT); }

    /**
     * Drive allows several folders with the same name. If two ever exist (created at the same time on two phones, or
     * a create that failed but went through), every phone uses the oldest one.
     */
    @Override synchronized String folder(String parentId, String name) throws Exception {
        String found = oldest(parentId, name);
        if (found != null) return found;
        JSONObject f = new JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", new JSONArray().put(parentId));
        String made;
        try { made = call("POST", API + "/files?fields=id", json(f)).getString("id"); }
        catch (ApiError e) { if ((found = oldest(parentId, name)) != null) return found; throw e; } // it may have been made anyway
        found = oldest(parentId, name);
        return found != null ? found : made;
    }

    private String oldest(String parentId, String name) throws Exception {
        String query = "name = '" + name.replace("\\", "\\\\").replace("'", "\\'") + "' and mimeType = '" + FOLDER_MIME
                + "' and '" + parentId + "' in parents and trashed = false";
        JSONArray found = call("GET", API + "/files?q=" + q(query) + "&fields=files(id)&spaces=drive&orderBy=createdTime", null).optJSONArray("files");
        return found != null && found.length() > 0 ? found.getJSONObject(0).getString("id") : null;
    }

    @Override Listing listFiles(String folderId, int max) throws Exception {
        Listing res = new Listing();
        String query = "'" + folderId + "' in parents and trashed = false and mimeType != '" + FOLDER_MIME + "'";
        String page = "";
        while (res.files.size() < max) {
            JSONObject r = call("GET", API + "/files?q=" + q(query) + "&fields=" + q("nextPageToken,files(id,name,createdTime)")
                    + "&pageSize=1000&spaces=drive" + (page.isEmpty() ? "" : "&pageToken=" + q(page)), null);
            JSONArray v = r.optJSONArray("files");
            for (int i = 0; v != null && i < v.length() && res.files.size() < max; i++) {
                JSONObject n = v.getJSONObject(i);
                res.files.add(node(n.getString("id"), n.optString("name"), n.optString("createdTime")));
            }
            page = r.optString("nextPageToken", "");
            if (page.isEmpty()) { res.complete = true; break; }
        }
        return res;
    }

    /** Resumable upload: one request for the name and folder, then the bytes in one go to the address Google gives. */
    @Override JSONObject upload(final File png, String name, String parentId, final Progress p) throws Exception {
        final JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(parentId));
        for (int attempt = 0; ; attempt++) {
            long t0 = System.currentTimeMillis();
            String session;
            HttpURLConnection c = (HttpURLConnection) new URL(UPLOAD + "/files?uploadType=resumable&fields=id,mimeType").openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(30_000);
                c.setReadTimeout(60_000);
                c.setRequestProperty("Authorization", "Bearer " + token());
                c.setRequestProperty("X-Upload-Content-Type", "image/png");
                c.setRequestProperty("X-Upload-Content-Length", String.valueOf(png.length()));
                json(meta).write(c);
                int code = c.getResponseCode();
                String t = code == 200 ? "" : text(c, code);
                Journal.add("POST /upload/drive/v3/files (start) -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (code == 401 && attempt == 0) { dropToken(); continue; }
                String reason = code >= 400 ? reason(t) : "";
                if (retry(code, reason) && attempt < 4) { Thread.sleep(2000L << attempt); continue; }
                if (code != 200) throw error(code, reason);
                session = c.getHeaderField("Location");
                if (session == null) throw new IOException("Google Drive gave no upload address");
            } finally { c.disconnect(); }
            t0 = System.currentTimeMillis();
            HttpURLConnection d = (HttpURLConnection) new URL(session).openConnection();
            try {
                d.setRequestMethod("PUT");
                d.setConnectTimeout(30_000);
                d.setReadTimeout(180_000);
                d.setDoOutput(true);
                d.setRequestProperty("Content-Type", "image/png");
                d.setFixedLengthStreamingMode(png.length());
                try (InputStream in = new FileInputStream(png); OutputStream o = d.getOutputStream()) { copy(in, o, png.length(), p); }
                int code = d.getResponseCode();
                String t = text(d, code);
                Journal.add("PUT /upload/drive/v3/files (bytes) -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                String reason = code >= 400 ? reason(t) : "";
                if (retry(code, reason) && attempt < 4) { Thread.sleep(2000L << attempt); continue; } // a new session, from the start
                if (code != 200 && code != 201) throw error(code, reason);
                JSONObject n = new JSONObject(t);
                return new JSONObject().put("id", n.getString("id")).put("type", n.optString("mimeType"));
            } finally { d.disconnect(); }
        }
    }

    @Override void download(String id, File dst, Progress p) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long t0 = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(API + "/files/" + id + "?alt=media").openConnection();
            try {
                c.setConnectTimeout(30_000);
                c.setReadTimeout(180_000);
                c.setRequestProperty("Authorization", "Bearer " + token());
                int code = c.getResponseCode();
                Journal.add("GET /drive/v3/files/<id>?alt=media -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (code == 401 && attempt == 0) { dropToken(); continue; }
                if (code >= 400) {
                    String reason = reason(text(c, code));
                    if (retry(code, reason) && attempt < 4) { Thread.sleep(2000L << attempt); continue; }
                    throw error(code, reason);
                }
                save(c.getInputStream(), c.getContentLengthLong(), dst, p);
                return;
            } finally { c.disconnect(); }
        }
    }

    /** To the Google Drive trash, one by one. */
    @Override void trash(List<String> ids) throws Exception {
        for (String id : ids) call("PATCH", API + "/files/" + id + "?fields=id", json(new JSONObject().put("trashed", true)));
    }

    @Override String[] storage() throws Exception {
        JSONObject q = call("GET", API + "/about?fields=storageQuota", null).optJSONObject("storageQuota");
        if (q == null) return new String[]{"warn", st.app.getString(R.string.stor_none, name())};
        long used = q.optLong("usage"), limit = q.optLong("limit", 0);
        if (limit <= 0) return new String[]{"ok", st.app.getString(R.string.stor_used, name(), Store.human(used))};
        long free = limit - used;
        return new String[]{free < (1L << 30) ? "warn" : "ok",
                st.app.getString(R.string.stor_quota, name(), Store.human(used), Store.human(limit), Store.human(free))};
    }
}
