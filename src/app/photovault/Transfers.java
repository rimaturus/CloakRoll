package app.photovault;

import android.os.SystemClock;
import java.io.File;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;

/**
 * The storage as the app uses it: the chosen cloud (or phone folder), with every upload and download timed for the
 * statistics and, if switched on, a copy of each encrypted PNG kept in a folder on the phone. Downloads come from
 * that copy when it's there. Copies are best effort: a failed copy never fails the upload.
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

    @Override String name() { return c.name(); }
    @Override String trashName() { return c.trashName(); }
    @Override boolean hasSession() { return c.hasSession(); }
    @Override String vaultFolder() throws Exception { return c.vaultFolder(); }
    @Override String folder(String parentId, String name) throws Exception { return c.folder(parentId, name); }
    @Override Listing listFiles(String folderId, int max) throws Exception { return c.listFiles(folderId, max); }
    @Override String[] storage() throws Exception { return c.storage(); }
    @Override void signOut() { c.signOut(); }

    @Override JSONObject upload(File png, String name, String parentId, Progress p) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        JSONObject r = c.upload(png, name, parentId, p);
        stats.add(Stats.UP, png.length(), SystemClock.elapsedRealtime() - t0);
        if (copy != null) try { copy.put(png, copyName(r.getString("id"))); }
        catch (Exception e) { Journal.add("copy on the phone not saved: " + e.getClass().getSimpleName()); }
        return r;
    }

    @Override void download(String id, File dst, Progress p) throws Exception {
        if (copy != null) try { if (copy.get(copyName(id), dst, p)) return; }
        catch (CancellationException e) { throw e; } // the viewer was closed: no download either
        catch (Exception e) { Journal.add("copy on the phone not readable, downloading: " + e.getClass().getSimpleName()); }
        long t0 = SystemClock.elapsedRealtime();
        c.download(id, dst, p);
        stats.add(Stats.DOWN, dst.length(), SystemClock.elapsedRealtime() - t0);
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
