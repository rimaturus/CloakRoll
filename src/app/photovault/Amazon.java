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
 * Amazon Photos web API as used by the amazon.it website (unofficial; same endpoints as
 * github.com/trevorhobenshield/amazon_photos). Auth = the session cookies of the in-app login page.
 * Only encrypted PNGs ever go through here.
 */
final class Amazon {
    static final String WEB = "https://www.amazon.it";
    static final String DRIVE = WEB + "/drive/v1";
    static final String CDPROXY = "https://content-eu.drive.amazonaws.com/cdproxy/nodes";
    static final String BASE = "asset=ALL&tempLink=false&resourceVersion=V2&ContentType=JSON";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    interface Progress { void on(long done, long total); }

    static final class ApiError extends IOException {
        final int code;
        ApiError(int code, String msg) { super(msg); this.code = code; }
        boolean isAuth() { return code == 401 || code == 403; }
    }

    static String cookies() { String c = CookieManager.getInstance().getCookie(WEB); return c == null ? "" : c; }

    static boolean hasSession() {
        String c = cookies();
        return c.contains("session-id=") && (c.contains("at-acb") || c.contains("at-main") || c.contains("at_main"));
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

    /** Root node of the Amazon Drive behind Amazon Photos: {id, ownerId, ...}. */
    static JSONObject root() throws IOException, JSONException {
        return new JSONObject(call("GET", DRIVE + "/nodes?filters=" + enc("isRoot:true") + "&" + BASE, null, false))
                .getJSONArray("data").getJSONObject(0);
    }

    /** Id of folder `name` under `parentId`, created if missing. */
    static String folder(String parentId, String name) throws IOException, JSONException {
        JSONObject b = new JSONObject().put("kind", "FOLDER").put("name", name)
                .put("parents", new JSONArray().put(parentId)).put("resourceVersion", "V2").put("ContentType", "JSON");
        JSONObject r = new JSONObject(call("POST", DRIVE + "/nodes", b, true));
        String id = r.optString("id", "");
        if (id.isEmpty() && r.optJSONObject("info") != null) id = r.getJSONObject("info").optString("nodeId", "");
        if (id.isEmpty()) throw new IOException("could not create or find folder " + name);
        return id;
    }

    /** Files in a folder (up to `max`). */
    static List<JSONObject> listFiles(String folderId, int max) throws IOException, JSONException {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = "";
        int offset = 0;
        while (out.size() < max) {
            int limit = Math.min(200, max - out.size());
            String url = DRIVE + "/nodes/" + folderId + "/children?filters=" + enc("kind:FILE") + "&limit=" + limit + "&" + BASE
                    + (token.isEmpty() ? "&offset=" + offset : "&startToken=" + enc(token));
            JSONObject r = new JSONObject(call("GET", url, null, false));
            JSONArray d = r.getJSONArray("data");
            int added = 0;
            for (int i = 0; i < d.length(); i++) {
                JSONObject n = d.getJSONObject(i);
                if (seen.add(n.getString("id"))) { out.add(n); added++; }
            }
            token = r.isNull("nextToken") ? "" : r.optString("nextToken", "");
            if (added == 0 || (token.isEmpty() && d.length() < limit)) break;
            if (token.isEmpty()) offset += d.length();
            if (offset > 9799) break; // Amazon refuses offset + limit > 9999
        }
        return out;
    }

    /** Uploads an (already encrypted) PNG. Returns the new node. */
    static JSONObject upload(File png, String name, String parentId, Progress p) throws IOException, JSONException {
        String url = CDPROXY + "?name=" + enc(name) + "&kind=FILE&parentNodeId=" + enc(parentId);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = open("POST", url);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "image/png");
        c.setFixedLengthStreamingMode(png.length());
        try (InputStream in = new FileInputStream(png); OutputStream o = c.getOutputStream()) { copy(in, o, png.length(), p); }
        try { return new JSONObject(finish(c, "POST", url, t0, false)); } finally { c.disconnect(); }
    }

    /** Downloads a node's original bytes to `dst`. */
    static void download(String id, String ownerId, File dst, Progress p) throws IOException {
        try {
            get(DRIVE + "/nodes/" + id + "/contentRedirection?querySuffix=" + enc("?download=true") + "&ownerId=" + enc(ownerId), dst, p);
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
            File tmp = new File(dst.getPath() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) { copy(in, o, c.getContentLengthLong(), p); }
            if (!tmp.renameTo(dst)) throw new IOException("cannot write " + dst);
            Journal.add("GET " + url.split("\\?")[0].replaceFirst("https://[^/]+", "") + " -> HTTP " + code + ", "
                    + dst.length() / 1024 + " KB (" + (System.currentTimeMillis() - t0) + " ms)");
        } finally { c.disconnect(); }
    }

    /** Moves nodes to the Amazon Photos trash (recoverable there for a while, like a normal delete). */
    static void trash(List<String> ids) throws IOException, JSONException {
        JSONArray v = new JSONArray();
        for (String id : ids) v.put(id);
        call("PATCH", DRIVE + "/trash", new JSONObject().put("recurse", "true").put("op", "add").put("filters", "")
                .put("conflictResolution", "RENAME").put("value", v).put("resourceVersion", "V2").put("ContentType", "JSON"), false);
    }

    /** Storage usage per category: {photo:{billable:{bytes,count}, total:{...}}, video:..., ...}. */
    static JSONObject usage() throws IOException, JSONException {
        return new JSONObject(call("GET", DRIVE + "/account/usage?" + BASE, null, false));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        copy(in, b, -1, null);
        return b.toByteArray();
    }

    static void copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0, last = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && done - last > (1 << 20)) { p.on(done, total); last = done; }
        }
    }
}
