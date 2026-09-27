package app.photovault;

import android.webkit.CookieManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Amazon Photos, through the requests its own website makes (unofficial; same endpoints as
 * github.com/trevorhobenshield/amazon_photos). Auth = the session cookies of the in-app sign-in page.
 */
final class Amazon extends Cloud {
    static final String WEB = "https://www.amazon.it";
    static final String DRIVE = WEB + "/drive/v1";
    static final String CDPROXY = "https://content-eu.drive.amazonaws.com/cdproxy/nodes";
    static final String BASE = "asset=ALL&tempLink=false&resourceVersion=V2&ContentType=JSON";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    private final Store st;

    Amazon(Store st) { this.st = st; }

    @Override String name() { return "Amazon Photos"; }

    @Override String trashName() { return "Amazon Photos trash"; }

    /** Amazon's store/sign-in domains. The in-app sign-in page only shows these; other links open in the normal browser. */
    static boolean isAmazonHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).matches("(.+\\.)?amazon\\.(it|com|co\\.uk|de|fr|es|nl|se|pl|com\\.be|ie|ca|com\\.mx|com\\.br|co\\.jp|com\\.au|in|sg|ae|sa|eg|com\\.tr)");
    }

    static String cookies() { String c = CookieManager.getInstance().getCookie(WEB); return c == null ? "" : c; }

    @Override boolean hasSession() {
        String c = cookies();
        return c.contains("session-id=") && (c.contains("at-acb") || c.contains("at-main") || c.contains("at_main"));
    }

    @Override void signOut() {
        android.webkit.WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(null);
    }

    private static String cookie(String name) {
        for (String p : cookies().split(";")) {
            p = p.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return "";
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new RuntimeException(e); }
    }

    private static HttpURLConnection open(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(180_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Cookie", cookies());
        c.setRequestProperty("x-amzn-sessionid", cookie("session-id"));
        c.setRequestProperty("Accept", "application/json, */*");
        return c;
    }

    /** Logs method, path and status only: never cookies, keys or file names. */
    private static String finish(HttpURLConnection c, String method, String url, long t0, boolean allow409) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) try (InputStream i = in) { body = new String(readAll(i), "UTF-8"); }
        String path = url.split("\\?")[0].replaceFirst("https://[^/]+", "");
        Journal.add(method + " " + path + " -> HTTP " + code + " (" + (System.currentTimeMillis() - t0) + " ms)");
        if (code >= 400 && !(allow409 && code == 409)) {
            Journal.add("  body: " + body.substring(0, Math.min(300, body.length())));
            throw new ApiError(code, "Amazon answered HTTP " + code + " on " + path);
        }
        return body;
    }

    private static String call(String method, String url, JSONObject json, boolean allow409) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = open(method, url);
        if (json != null) {
            byte[] b = json.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }
        try { return finish(c, method, url, t0, allow409); } finally { c.disconnect(); }
    }

    /** Folder "PhotoVault" at the top of the Amazon Drive behind Amazon Photos. */
    @Override String vaultFolder() throws Exception {
        JSONObject root = new JSONObject(call("GET", DRIVE + "/nodes?filters=" + enc("isRoot:true") + "&" + BASE, null, false))
                .getJSONArray("data").getJSONObject(0);
        st.prefs.edit().putString("owner", root.optString("ownerId")).apply();
        return folder(root.getString("id"), "PhotoVault");
    }

    @Override String folder(String parentId, String name) throws IOException, JSONException {
        JSONObject b = new JSONObject().put("kind", "FOLDER").put("name", name)
                .put("parents", new JSONArray().put(parentId)).put("resourceVersion", "V2").put("ContentType", "JSON");
        JSONObject r = new JSONObject(call("POST", DRIVE + "/nodes", b, true));
        String id = r.optString("id", "");
        if (id.isEmpty() && r.optJSONObject("info") != null) id = r.getJSONObject("info").optString("nodeId", "");
        if (id.isEmpty()) throw new IOException("could not create or find folder " + name);
        return id;
    }

    private static volatile String fileFilter = "kind:FILE AND status:AVAILABLE";

    @Override Listing listFiles(String folderId, int max) throws IOException, JSONException {
        Listing res = new Listing();
        List<JSONObject> out = res.files;
        Set<String> seen = new HashSet<>();
        String token = "";
        int offset = 0;
        while (out.size() < max) {
            int limit = Math.min(200, max - out.size());
            String q = "&limit=" + limit + "&" + BASE + (token.isEmpty() ? "&offset=" + offset : "&startToken=" + enc(token));
            JSONObject r;
            try { r = new JSONObject(call("GET", DRIVE + "/nodes/" + folderId + "/children?filters=" + enc(fileFilter) + q, null, false)); }
            catch (ApiError e) {
                if (e.code != 400 || fileFilter.equals("kind:FILE")) throw e;
                fileFilter = "kind:FILE"; // status filter refused: the status check below still skips trashed files
                continue;
            }
            JSONArray d = r.getJSONArray("data");
            int added = 0;
            for (int i = 0; i < d.length(); i++) {
                JSONObject n = d.getJSONObject(i);
                if (!seen.add(n.getString("id"))) continue;
                added++;
                if ("AVAILABLE".equals(n.optString("status", "AVAILABLE"))) // skip anything in the Amazon trash
                    out.add(node(n.getString("id"), n.optString("name"), n.optString("createdDate")));
            }
            token = r.isNull("nextToken") ? "" : r.optString("nextToken", "");
            if (token.isEmpty() && d.length() < limit) { res.complete = true; break; }
            if (added == 0) break;
            if (token.isEmpty()) offset += d.length();
            if (offset > 9799) break; // Amazon refuses offset + limit > 9999
        }
        return res;
    }

    @Override JSONObject upload(File png, String name, String parentId, Progress p) throws IOException, JSONException {
        String url = CDPROXY + "?name=" + enc(name) + "&kind=FILE&parentNodeId=" + enc(parentId);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = open("POST", url);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "image/png");
        c.setFixedLengthStreamingMode(png.length());
        try (InputStream in = new FileInputStream(png); OutputStream o = c.getOutputStream()) { copy(in, o, png.length(), p); }
        JSONObject n;
        try { n = new JSONObject(finish(c, "POST", url, t0, false)); } finally { c.disconnect(); }
        JSONObject cp = n.optJSONObject("contentProperties");
        return new JSONObject().put("id", n.getString("id")).put("type", cp == null ? "" : cp.optString("contentType"));
    }

    @Override void download(String id, File dst, Progress p) throws IOException {
        try {
            get(DRIVE + "/nodes/" + id + "/contentRedirection?querySuffix=" + enc("?download=true") + "&ownerId=" + enc(st.owner()), dst, p);
        } catch (ApiError e) {
            if (e.isAuth()) throw e;
            Journal.add("  retrying download via content endpoint");
            get(CDPROXY + "/" + id + "/content", dst, p);
        }
    }

    private static void get(String url, File dst, Progress p) throws IOException {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = open("GET", url);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code >= 400) { finish(c, "GET", url, t0, false); return; }
            save(c.getInputStream(), c.getContentLengthLong(), dst, p);
            Journal.add("GET " + url.split("\\?")[0].replaceFirst("https://[^/]+", "") + " -> HTTP " + code + ", "
                    + dst.length() / 1024 + " KB (" + (System.currentTimeMillis() - t0) + " ms)");
        } finally { c.disconnect(); }
    }

    /** Moves nodes to the Amazon Photos trash (recoverable there for a while, like a normal delete). */
    @Override void trash(List<String> ids) throws IOException, JSONException {
        JSONArray v = new JSONArray();
        for (String id : ids) v.put(id);
        call("PATCH", DRIVE + "/trash", new JSONObject().put("recurse", "true").put("op", "add").put("filters", "")
                .put("conflictResolution", "RENAME").put("value", v).put("resourceVersion", "V2").put("ContentType", "JSON"), false);
    }

    /** Prime: photos are free. Anything billed as "photo" means unlimited photo storage isn't active. */
    @Override String[] storage() throws Exception {
        JSONObject u = new JSONObject(call("GET", DRIVE + "/account/usage?" + BASE, null, false)), ph = u.optJSONObject("photo");
        if (ph == null) return new String[]{"warn", "Amazon didn't report photo storage"};
        long bill = ph.optJSONObject("billable") == null ? -1 : ph.getJSONObject("billable").optLong("bytes", -1);
        long total = ph.optJSONObject("total") == null ? -1 : ph.getJSONObject("total").optLong("bytes", -1);
        if (bill == 0) return new String[]{"ok", "Photos stored: " + Store.human(total) + ", billed: 0 B (Prime unlimited photos active)"};
        return new String[]{"warn", "Photos billed: " + Store.human(bill) + " of " + Store.human(total) + ". Prime unlimited photos may not be active on this account"};
    }
}
