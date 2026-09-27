package app.photovault;

import java.io.*;
import java.util.*;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where the encrypted PNGs are kept. Only encrypted files ever go through here.
 * Implementations: Amazon Photos (its website's own requests, with the web session) and OneDrive (Microsoft Graph).
 * Files are identified by the cloud's own ids; listings return JSONObjects with "id", "name" and "createdDate".
 */
abstract class Cloud {
    interface Progress { void on(long done, long total); }

    static final class ApiError extends IOException {
        final int code;
        ApiError(int code, String msg) { super(msg); this.code = code; }
        boolean isAuth() { return code == 401 || code == 403; }
    }

    static final class Listing {
        final List<JSONObject> files = new ArrayList<>();
        boolean complete; // true only if the cloud said there is nothing more
    }

    /** Shown in the app: "Amazon Photos", "OneDrive". */
    abstract String name();

    /** Where deleted files go: "Amazon Photos trash", "OneDrive recycle bin". */
    abstract String trashName();

    /** Signed in (a session or token is stored). */
    abstract boolean hasSession();

    /** Finds or creates the vault folder, remembers what it needs, returns the folder id. Background thread. */
    abstract String vaultFolder() throws Exception;

    /** Id of folder `name` under `parentId`, created if missing. */
    abstract String folder(String parentId, String name) throws Exception;

    /** Files in a folder (up to `max`), not the ones in the trash. */
    abstract Listing listFiles(String folderId, int max) throws Exception;

    /** Uploads an (already encrypted) PNG. Returns {"id": ..., "type": mime type the cloud reports}. */
    abstract JSONObject upload(File png, String name, String parentId, Progress p) throws Exception;

    /** Downloads a file's exact bytes to `dst`. */
    abstract void download(String id, File dst, Progress p) throws Exception;

    /** Moves files to the cloud's trash / recycle bin. Throws ApiError(404) if one is already gone. */
    abstract void trash(List<String> ids) throws Exception;

    /** One line about storage use for the self-test: {"ok" or "warn", text}. */
    abstract String[] storage() throws Exception;

    /** Forgets the stored session on this phone. */
    abstract void signOut();

    /** Trash in batches of 50; if the cloud refuses a batch, one by one, counting "not found" (already gone) as done. */
    void trashAll(List<String> ids) throws Exception {
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
            try { trash(batch); }
            catch (ApiError e) {
                if (e.isAuth()) throw e;
                for (String one : batch)
                    try { trash(Collections.singletonList(one)); }
                    catch (ApiError x) { if (x.code != 404) throw x; }
            }
        }
    }

    // ---------------------------------------------------------------- small helpers

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

    /** Writes a download to `dst` through a ".part" file: stopped or failed, no half file is left behind. */
    static void save(InputStream body, long length, File dst, Progress p) throws IOException {
        File tmp = new File(dst.getPath() + ".part");
        boolean done = false;
        try {
            try (InputStream in = body; OutputStream o = new FileOutputStream(tmp)) { copy(in, o, length, p); }
            if (!tmp.renameTo(dst)) throw new IOException("cannot write " + dst);
            done = true;
        } finally { if (!done) tmp.delete(); }
    }

    static JSONObject node(String id, String name, String created) throws JSONException {
        return new JSONObject().put("id", id).put("name", name == null ? "" : name).put("createdDate", utc(created));
    }

    /** "yyyy-MM-ddTHH:mm:ss.SSSZ" whatever the cloud sends (no or more fraction digits), so dates compare as text. */
    static String utc(String s) {
        if (s == null || s.length() < 19) return "";
        String frac = s.length() > 20 && s.charAt(19) == '.' ? s.substring(20).replaceAll("[^0-9].*", "") : "";
        return s.substring(0, 19) + "." + (frac + "000").substring(0, 3) + "Z";
    }
}
