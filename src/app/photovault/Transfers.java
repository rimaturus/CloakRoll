package app.photovault;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;

/**
 * The storage as the app uses it: the chosen cloud (or phone folder), with every upload and download timed for the
 * statistics and, if switched on, a copy of each encrypted PNG kept in a folder on the phone. Downloads come from
 * that copy when it's there. Copies are best effort: a failed copy never fails the upload.
 * A transfer cut short by the network is tried again here (3 times in all), so one bad moment doesn't throw away
 * the parts of a big file that are already through.
 */
final class Transfers extends Cloud {
    final Cloud c;
    final Local copy; // null: no copy on the phone
    private final Stats stats;

    Transfers(Cloud c, Local copy, Stats stats) { this.c = c; this.copy = copy; this.stats = stats; }

    /** Name of the phone copy of cloud file `id` (hex: cloud ids may differ only in upper/lower case). */
    static String copyName(String id) {
        try { return Store.hex(id.getBytes("UTF-8")) + ".png"; } catch (java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
    }

    /** Ids become file names on the phone (previews, downloads): one that would point outside their folder is refused. */
    static boolean safe(String id) { return !id.isEmpty() && id.indexOf('/') < 0; }

    /** The network failed (not: the storage said no, the phone is offline, a file is missing): worth another try. */
    private static boolean again(Exception e, int attempt) {
        return attempt < 2 && e instanceof IOException && !(e instanceof ApiError)
                && !(e instanceof java.net.UnknownHostException) && !(e instanceof java.io.FileNotFoundException);
    }

    @Override String name() { return c.name(); }
    @Override String trashName() { return c.trashName(); }
    @Override boolean hasSession() { return c.hasSession(); }
    @Override String vaultFolder() throws Exception { return c.vaultFolder(); }
    @Override String folder(String parentId, String name) throws Exception { return c.folder(parentId, name); }
    @Override String[] storage() throws Exception { return c.storage(); }
    @Override void signOut() { c.signOut(); }

    @Override Listing listFiles(String folderId, int max) throws Exception {
        Listing l = c.listFiles(folderId, max);
        for (Iterator<JSONObject> i = l.files.iterator(); i.hasNext(); )
            if (!safe(i.next().optString("id"))) { i.remove(); Journal.add("a file with an unusable id was ignored"); }
        return l;
    }

    @Override JSONObject upload(File png, String name, String parentId, Progress p) throws Exception {
        JSONObject r = null;
        for (int attempt = 0; r == null; attempt++) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                r = c.upload(png, name, parentId, p);
                stats.add(Stats.UP, png.length(), SystemClock.elapsedRealtime() - t0);
            } catch (Exception e) {
                if (!again(e, attempt)) throw e;
                Journal.add("upload interrupted, trying again: " + e.getClass().getSimpleName());
                Thread.sleep(3000L << attempt);
                for (JSONObject n : c.listFiles(parentId, 1_000_000).files) // it may have arrived all the same: no second copy
                    if (name.equals(n.optString("name"))) r = new JSONObject().put("id", n.getString("id")).put("type", "");
            }
        }
        if (!safe(r.getString("id"))) throw new IOException("the storage gave an unusable file id");
        if (copy != null) try { copy.put(png, copyName(r.getString("id"))); }
        catch (Exception e) { Journal.add("copy on the phone not saved: " + e.getClass().getSimpleName()); }
        return r;
    }

    @Override void download(String id, File dst, Progress p) throws Exception {
        if (copy != null) try { if (copy.get(copyName(id), dst, p)) return; }
        catch (CancellationException e) { throw e; } // the viewer was closed: no download either
        catch (Exception e) { Journal.add("copy on the phone not readable, downloading: " + e.getClass().getSimpleName()); }
        for (int attempt = 0; ; attempt++) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                c.download(id, dst, p);
                stats.add(Stats.DOWN, dst.length(), SystemClock.elapsedRealtime() - t0);
                break;
            } catch (Exception e) {
                if (!again(e, attempt)) throw e;
                Journal.add("download interrupted, trying again: " + e.getClass().getSimpleName());
                Thread.sleep(3000L << attempt);
            }
        }
        if (copy != null) try { copy.put(dst, copyName(id)); }
        catch (Exception e) { Journal.add("copy on the phone not saved: " + e.getClass().getSimpleName()); }
    }

    /** The phone copies go even if the cloud refuses some files: they were being deleted anyway. */
    @Override void trash(List<String> ids) throws Exception {
        try { c.trash(ids); }
        finally { dropCopies(ids); }
    }

    /** Removes the phone copies of these cloud files (deleted, or gone from the cloud). Best effort. */
    void dropCopies(List<String> ids) {
        if (copy == null || ids.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(copyName(id));
        try { copy.delete(names); } catch (Exception e) { Journal.add("copies on the phone not removed: " + e.getClass().getSimpleName()); }
    }
}
