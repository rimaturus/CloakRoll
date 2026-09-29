package app.photovault;

import android.net.Uri;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Microsoft OneDrive through the official Microsoft Graph API, signed in with OAuth in the browser (OAuthCloud). The app
 * asks only for its own folder, OneDrive/Apps/PhotoVault, not the rest of the user's files; if Microsoft refuses app
 * folders for this app registration, the user can choose full access instead and the vault goes in a normal folder
 * "PhotoVault".
 */
final class OneDrive extends OAuthCloud {
    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0/";
    static final String REDIRECT = "io.github.rimaturus.photovault://auth";
    static final String SCOPE_APP_FOLDER = "Files.ReadWrite.AppFolder offline_access";
    static final String SCOPE_ALL_FILES = "Files.ReadWrite offline_access"; // fallback, only if the user chooses it

    OneDrive(Store st) { super(st, "od", "photovault_onedrive_token", LOGIN + "token"); }

    /** The build has a Microsoft app registration (see PUBLISHING.md). */
    static boolean configured() { return !Config.ONEDRIVE_CLIENT_ID.isEmpty(); }

    @Override String name() { return "OneDrive"; }

    @Override String company() { return "Microsoft"; }

    @Override String clientId() { return Config.ONEDRIVE_CLIENT_ID; }

    @Override String redirect() { return REDIRECT; }

    @Override String scopeParam(String scope) { return "&scope=" + Uri.encode(scope.isEmpty() ? SCOPE_APP_FOLDER : scope); }

    @Override String refreshScope() { return allFiles() ? SCOPE_ALL_FILES : SCOPE_APP_FOLDER; }

    @Override String trashName() { return st.app.getString(R.string.trash_onedrive); }

    /** The vault uses full OneDrive access (folder "PhotoVault") instead of the app folder. Kept across sign-outs. */
    boolean allFiles() { return st.prefs.getBoolean("od_all", false); }

    // ---------------------------------------------------------------- sign-in (browser, PKCE)

    /** Microsoft's sign-in page for the browser. The answer comes back to MainActivity through REDIRECT. */
    String authUrl(boolean allFiles) throws Exception {
        String scope = allFiles ? SCOPE_ALL_FILES : SCOPE_APP_FOLDER;
        return LOGIN + "authorize?client_id=" + Uri.encode(Config.ONEDRIVE_CLIENT_ID) + "&response_type=code&response_mode=query"
                + "&redirect_uri=" + Uri.encode(REDIRECT) + "&scope=" + Uri.encode(scope) + pkce(scope) + "&prompt=select_account";
    }

    @Override synchronized String redeem(Uri answer) throws Exception {
        String scope = super.redeem(answer);
        st.prefs.edit().putBoolean("od_all", scope.equals(SCOPE_ALL_FILES)).commit();
        Journal.add("OneDrive: signed in (" + (scope.equals(SCOPE_ALL_FILES) ? "all files" : "app folder only") + ")");
        return scope;
    }

    // ---------------------------------------------------------------- Graph requests

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
                if (code == 401 && attempt == 0) { dropToken(); continue; }
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
                if (code == 401 && attempt == 0) { dropToken(); continue; }
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
            return new String[]{"warn", st.app.getString(R.string.stor_od_hidden)};
        }
        if (q == null) return new String[]{"warn", st.app.getString(R.string.stor_none, name())};
        long used = q.optLong("used"), total = q.optLong("total"), free = q.optLong("remaining");
        return new String[]{free < (1L << 30) ? "warn" : "ok",
                st.app.getString(R.string.stor_quota, name(), Store.human(used), Store.human(total), Store.human(free))};
    }
}
