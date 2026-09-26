package app.photovault;

import android.app.*;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs uploads and "Sync from Amazon" in the background as a foreground service (type dataSync),
 * so they continue when you leave the app or the vault auto-locks. Jobs run one at a time.
 * Each job works with its own copy of the key, wiped when the job ends.
 * The notification never shows file names.
 */
public class SyncService extends Service {
    static final String UPLOAD = "upload", SYNC = "sync", REENCRYPT = "reencrypt", STOP = "stop", CHANNEL = "sync";
    static final int NOTE = 1, DONE_NOTE = 2;

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    Store st;
    NotificationManager nm;
    int pending, seq, lastStartId;
    volatile boolean destroyed;
    volatile int cancelUpTo;
    long lastNote;

    /** Notification and status texts are plain ASCII: some phone fonts show symbols like check marks as empty boxes. */
    static void start(Context c, String action, List<Uri> uris, String intoFolder) {
        Intent i = new Intent(c, SyncService.class).setAction(action).putExtra("folder", intoFolder);
        if (uris != null && !uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int n = 1; n < uris.size(); n++) clip.addItem(new ClipData.Item(uris.get(n)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        c.startForegroundService(i);
    }

    static void stop(Context c) { c.startService(new Intent(c, SyncService.class).setAction(STOP)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        st = Store.get(this);
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Uploads and sync", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        try {
            startForeground(NOTE, note("Preparing...", null, -1, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) { // e.g. a stale "Stop" tapped while the app is in the background
            Journal.add("background service not allowed now: " + e);
            if (pending == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        lastStartId = startId;
        String action = in == null ? "" : String.valueOf(in.getAction());
        if (STOP.equals(action)) {
            cancelUpTo = seq;
            if (pending > 0) show("Stopping after the current file...", -1);
        } else if (st.key != null && (UPLOAD.equals(action) || SYNC.equals(action) || REENCRYPT.equals(action))) {
            final byte[] k = st.key.clone(), salt = st.salt(); // taken together: they always belong to the same key
            final int id = ++seq;
            final List<Uri> uris = new ArrayList<>();
            ClipData c = in.getClipData();
            if (c != null) for (int n = 0; n < c.getItemCount(); n++) uris.add(c.getItemAt(n).getUri());
            final boolean upload = UPLOAD.equals(action), reencrypt = REENCRYPT.equals(action);
            final String into = in.getStringExtra("folder") == null ? "" : in.getStringExtra("folder");
            pending++;
            st.busyJobs = pending;
            worker.execute(new Runnable() { public void run() {
                try {
                    if (id <= cancelUpTo) return;
                    if (!st.keyIsCurrent(k)) { // the password was changed after this job was queued
                        done(upload ? "Not added: the vault password changed meanwhile. Add these items again." : "Stopped: the vault password changed.");
                        return;
                    }
                    if (upload) upload(k, salt, uris, into, id); else if (reencrypt) reencrypt(k, id); else sync(k, id);
                } catch (Throwable e) {
                    Journal.add("background job failed: " + e);
                } finally {
                    Arrays.fill(k, (byte) 0);
                    st.post(new Runnable() { public void run() { st.busyJobs = --pending; if (pending == 0) finish(); } });
                }
            }});
        }
        if (pending == 0) finish();
        return START_NOT_STICKY;
    }

    /** Main thread, when the queue is empty. A start request still on its way keeps the service alive. */
    void finish() {
        if (st.jobRunning) st.setStatus("Stopped", false); // jobs cancelled before they began
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(lastStartId);
    }

    /** Android 15+: data-sync services may run 6 h per day; stop cleanly when the system says so. */
    @Override public void onTimeout(int startId, int type) {
        cancelUpTo = seq;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        destroyed = true;
        worker.shutdown();
        nm.cancel(NOTE);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notifications

    Notification note(String text, String title, int pct, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, getPackageManager().getLaunchIntentForPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title == null ? "PhotoVault" : title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (ongoing) {
            b.setProgress(100, Math.max(0, pct), pct < 0);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SyncService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        } else b.setAutoCancel(true);
        return b.build();
    }

    /** Progress in the notification (throttled) and in the gallery. */
    void show(String text, int pct) {
        st.setStatus(text, true);
        if (destroyed) return; // stopped by the system (6 h limit): no orphan notification
        long now = SystemClock.elapsedRealtime();
        if (now - lastNote < 700 && pct > 0 && pct < 100) return;
        lastNote = now;
        nm.notify(NOTE, note(text, "PhotoVault is working", pct, true));
    }

    void done(String text) {
        st.setStatus(text, false);
        nm.notify(DONE_NOTE, note(text, "PhotoVault", 100, false));
    }

    // ---------------------------------------------------------------- jobs

    void upload(byte[] k, byte[] salt, List<Uri> uris, String into, int id) throws Exception {
        String folder = st.folder();
        int ok = 0, failed = 0;
        for (int n = 0; n < uris.size(); n++) {
            if (id <= cancelUpTo) break;
            Uri u = uris.get(n);
            final String pre = "Adding " + (n + 1) + " of " + uris.size();
            File png = new File(getCacheDir(), "upload-" + id + ".png");
            try {
                String name = "item", mime = getContentResolver().getType(u);
                long size = -1, taken = System.currentTimeMillis();
                try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0 && !c.isNull(i)) name = c.getString(i);
                        i = c.getColumnIndex(OpenableColumns.SIZE);
                        if (i >= 0 && !c.isNull(i)) size = c.getLong(i);
                        i = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN);
                        if (i >= 0 && !c.isNull(i) && c.getLong(i) > 0) taken = c.getLong(i);
                    }
                }
                if (mime == null) mime = "application/octet-stream";
                if (size > Store.MAX_FILE) throw new IOException("larger than " + Store.human(Store.MAX_FILE) + " (not supported yet)");
                show(pre + ": encrypting", -1);
                String meta = new JSONObject().put("name", name).put("taken", taken).put("mime", mime).toString();
                byte[] plain;
                int off;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    byte[] all = null;
                    if (size <= 0) {
                        all = Amazon.readAll(in);
                        if (all.length > Store.MAX_FILE) throw new IOException("larger than " + Store.human(Store.MAX_FILE));
                        size = all.length;
                    }
                    plain = Vault.plainBuffer(meta, (int) size);
                    off = plain.length - (int) size;
                    if (all != null) System.arraycopy(all, 0, plain, off, all.length);
                    else for (int p = off, r; p < plain.length; p += r)
                        if ((r = in.read(plain, p, plain.length - p)) < 0) throw new EOFException("file shorter than expected");
                }
                byte[] thumb = Store.makeThumb(plain, off, plain.length - off, mime.startsWith("video/"));
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(png), 1 << 16)) { Vault.encryptToPng(k, salt, plain, o); }
                plain = null;
                final long total = png.length();
                JSONObject node = Amazon.upload(png, Store.hex(Vault.random(8)) + ".png", folder, new Amazon.Progress() {
                    public void on(long d, long t) { int pct = (int) (100 * d / Math.max(1, total)); show(pre + ": uploading " + pct + "%", pct); }
                });
                Store.Item it = new Store.Item();
                it.id = node.getString("id"); it.name = name; it.mime = mime; it.taken = taken; it.size = size; it.folder = into;
                it.salt = Store.hex(salt);
                if (thumb != null) Store.writeFile(st.thumbFile(it.id), Vault.seal(k, thumb));
                st.add(k, it, false);
                ok++;
            } catch (Throwable e) {
                failed++;
                Journal.add("upload " + (n + 1) + "/" + uris.size() + " failed: " + e);
                if (Store.isAuth(e)) { st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } }); break; }
            } finally { png.delete(); }
        }
        int skipped = uris.size() - ok - failed;
        if (ok > 0 && !into.isEmpty()) st.backupFolders(k);
        done("Done: " + ok + " added" + (into.isEmpty() ? "" : " to a folder") + (failed > 0 ? ", " + failed + " failed (see Log)" : "")
                + (skipped > 0 ? ", " + skipped + " not started" : ""));
    }

    /** Rebuilds the local list from the PhotoVault folder on Amazon (new phone, reinstall, items deleted on the website). */
    static final class SyncResult {
        final Set<String> unopenable = new HashSet<>(); // files neither the current nor the previous key opens
        int retry;                                        // temporary failures: another Sync may restore them
        boolean ok;                                       // ran to the end
    }

    SyncResult sync(final byte[] k, int id) throws Exception {
        SyncResult res = new SyncResult();
        int restored = 0, skipped = 0, retry = 0, removed = 0;
        try {
            show("Sync: listing your vault on Amazon...", -1);
            Amazon.Listing listing = Amazon.listFiles(st.folder(), 1_000_000);
            List<JSONObject> nodes = listing.files;
            final Set<String> remote = new HashSet<>(), local = new HashSet<>();
            for (JSONObject n : nodes) remote.add(n.getString("id"));
            Store.Index before = st.readIndex(k);
            for (Store.Item it : before.items) local.add(it.id);
            final Set<String> skip = new HashSet<>(local);
            skip.addAll(before.retire); // old copies replaced during a password change: to be trashed, never restored
            if (listing.complete) { // drop items deleted on the Amazon website (only if Amazon listed everything)
                final List<String> gone = new ArrayList<>();
                for (String x : local) if (!remote.contains(x)) gone.add(x);
                removed = gone.size();
                if (removed > 0) st.edit(k, new Store.Edit() { public void apply(Store.Index ix) {
                    Iterator<Store.Item> i = ix.items.iterator();
                    while (i.hasNext()) if (gone.contains(i.next().id)) i.remove();
                }});
            }
            // folders saved on Amazon: fill in items that aren't in a folder here (new phone, reinstall).
            // Skipped if the phone has newer folder changes that aren't on Amazon yet, unless a restore is under way.
            final boolean restoring = st.prefs.getBoolean("restore_pending", false);
            JSONObject fb = null;
            if (restoring || !st.foldersDirty())
                try { fb = st.readFoldersBackup(k); }
                catch (Exception e) { if (Store.isAuth(e)) throw e; Journal.add("sync: folders backup not readable: " + e); }
            final JSONObject map = fb == null || fb.optJSONObject("map") == null ? new JSONObject() : fb.getJSONObject("map");
            final JSONArray remoteFolders = fb == null || fb.optJSONArray("folders") == null ? new JSONArray() : fb.getJSONArray("folders");
            if (fb != null) st.edit(k, new Store.Edit() { public void apply(Store.Index ix) {
                if (!restoring && st.foldersDirty()) return; // changed on the phone while the backup was downloading
                for (int i = 0; i < remoteFolders.length(); i++) {
                    String f = remoteFolders.optString(i);
                    if (!f.isEmpty() && !ix.hasFolder(f)) ix.folders.add(f);
                }
                for (Store.Item it : ix.items) if (it.folder.isEmpty()) it.folder = map.optString(it.id, "");
            }});
            int n = 0;
            for (JSONObject node : nodes) {
                n++;
                if (id <= cancelUpTo) break;
                String nid = node.getString("id");
                if (skip.contains(nid)) continue;
                show("Sync: restoring " + n + " of " + nodes.size(), 100 * n / nodes.size());
                File f = new File(getCacheDir(), "sync.png");
                try {
                    try { Amazon.download(nid, st.owner(), f, null); }
                    catch (IOException e) {
                        if (Store.isAuth(e)) throw e;
                        Journal.add("sync: download failed, retried on the next Sync: " + e);
                        retry++;
                        continue;
                    }
                    Vault.Opened o;
                    try { o = st.decrypt(f, k); }
                    catch (Exception e) {
                        String s = "";
                        try (InputStream in = new FileInputStream(f)) { s = Store.hex(Vault.readSalt(in)); } catch (Exception ignored) { }
                        if (s.equals(Store.hex(st.salt())) || s.equals(st.prefs.getString("old_salt", "-"))) {
                            Journal.add("sync: a file of this vault didn't open (damaged download?), retried on the next Sync: " + e);
                            retry++; // made with one of our keys: never treated as "not ours"
                            continue;
                        }
                        Journal.add("sync: skipped a file this vault's key can't open: " + e); // another key, or not PhotoVault's
                        res.unopenable.add(nid);
                        skipped++;
                        continue;
                    }
                    JSONObject m = new JSONObject(o.meta);
                    Store.Item it = new Store.Item();
                    it.id = nid; it.name = m.optString("name", "item"); it.mime = m.optString("mime", "");
                    it.taken = m.optLong("taken", System.currentTimeMillis()); it.size = o.dataLen(); it.folder = map.optString(nid, "");
                    it.salt = Store.hex(o.salt);
                    byte[] thumb = Store.makeThumb(o.plain, o.dataOff, o.dataLen(), it.video());
                    if (thumb != null) Store.writeFile(st.thumbFile(nid), Vault.seal(k, thumb));
                    st.add(k, it, true);
                    restored++;
                } catch (Throwable e) {
                    if (Store.isAuth(e)) throw e;
                    Journal.add("sync: could not restore a file, retried on the next Sync: " + e);
                    retry++;
                } finally { f.delete(); }
            }
            // restore finished: everything that can be restored is here (files other keys made never will be)
            if (restoring && retry == 0 && id > cancelUpTo) {
                st.prefs.edit().putBoolean("restore_pending", false).commit();
                if (st.foldersDirty()) st.backupFolders(k);
            } else if (!restoring && st.foldersDirty()) st.backupFolders(k); // retry an upload that failed
            done("Sync done: " + restored + " restored, " + removed + " removed"
                    + (skipped > 0 ? ", " + skipped + " skipped (not openable with this vault's key, see Log)" : "")
                    + (retry > 0 ? ", " + retry + " to retry: run Sync again" : ""));
            res.retry = retry;
            res.ok = id > cancelUpTo;
        } catch (Throwable e) {
            Journal.add("sync failed: " + e);
            done("Sync failed: " + Store.explain(e));
            if (Store.isAuth(e)) st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } });
        }
        return res;
    }

    /**
     * After a password change: every file still made with the previous key is downloaded, re-encrypted with the
     * current key, uploaded again, and the old copy moved to the Amazon trash. Resumes where it stopped. The previous
     * key is deleted only when Amazon's complete listing shows no file that still needs it.
     */
    void reencrypt(final byte[] k, int id) throws Exception {
        if (!st.reencrypting()) return;
        final byte[] salt = st.salt();
        final String cur = Store.hex(salt);
        int ok = 0, failed = 0;
        try {
            st.applyPendingPreviews(k);
            // 1. replacements uploaded but never recorded (app killed in between): named "r" + old id + "-" + random
            Store.Index ix = st.readIndex(k);
            Set<String> oldIds = new HashSet<>();
            for (Store.Item it : ix.items) if (!cur.equals(it.salt)) oldIds.add(it.id);
            final List<String> orphans = new ArrayList<>();
            for (JSONObject n : Amazon.listFiles(st.folder(), 1_000_000).files) {
                String name = n.optString("name");
                int dash = name.lastIndexOf('-'); // ids can contain '-' too; the random suffix comes last
                if (name.startsWith("r") && dash > 1 && oldIds.contains(name.substring(1, dash))) orphans.add(n.getString("id"));
            }
            if (!orphans.isEmpty()) { // recorded first, so Sync never restores them as duplicates
                st.edit(k, new Store.Edit() { public void apply(Store.Index x) { x.retire.addAll(orphans); } });
                retire(k, orphans);
            }
            // 2. old copies already replaced
            if (!ix.retire.isEmpty()) retire(k, new ArrayList<>(ix.retire));
            // 3. the list must know every file first (new phone, website uploads...), so none is forgotten
            SyncResult sr = sync(k, id);
            if (!sr.ok) throw new IOException("the vault could not be listed completely");
            // 4. re-encrypt
            ix = st.readIndex(k);
            List<Store.Item> todo = new ArrayList<>();
            for (Store.Item it : ix.items) if (!cur.equals(it.salt)) todo.add(it);
            for (int n = 0; n < todo.size(); n++) {
                if (id <= cancelUpTo) break;
                final Store.Item it = todo.get(n);
                show("Password change: re-encrypting " + (n + 1) + " of " + todo.size() + ". New uploads wait until it's done.", 100 * n / todo.size());
                File cached = st.blobFile(it.id), down = new File(getCacheDir(), "reenc-down.png"), up = new File(getCacheDir(), "reenc-up.png");
                try {
                    File src = cached.exists() ? cached : down;
                    if (src == down) Amazon.download(it.id, st.owner(), down, null);
                    Vault.Opened o = st.decrypt(src, k);
                    if (Arrays.equals(o.salt, salt)) { // already made with the current key: just note it
                        st.edit(k, new Store.Edit() { public void apply(Store.Index x) {
                            for (Store.Item i : x.items) if (i.id.equals(it.id)) i.salt = cur;
                        }});
                        ok++;
                        continue;
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(up), 1 << 16)) { Vault.encryptToPng(k, salt, o.plain, out); }
                    final String newId = Amazon.upload(up, "r" + it.id + "-" + Store.hex(Vault.random(4)) + ".png", st.folder(), null).getString("id");
                    final boolean[] found = {false};
                    st.edit(k, new Store.Edit() { public void apply(Store.Index x) {
                        for (Store.Item i : x.items) if (i.id.equals(it.id)) { i.id = newId; i.salt = cur; found[0] = true; }
                        x.retire.add(found[0] ? it.id : newId); // item deleted meanwhile: the new copy goes too
                    }});
                    if (found[0]) { st.renamed.put(it.id, newId); st.thumbFile(it.id).renameTo(st.thumbFile(newId)); }
                    cached.delete();
                    retire(k, Collections.singletonList(found[0] ? it.id : newId));
                    ok++;
                    if (ok % 25 == 0) st.backupFolders(k); // the folders file refers to item ids, which change
                } catch (Throwable e) {
                    if (Store.isAuth(e)) throw e;
                    Journal.add("re-encryption of one file failed, retried later: " + e);
                    cached.delete(); // a damaged cached copy must not be reused
                    failed++;
                } finally { down.delete(); up.delete(); }
            }
            st.backupFolders(k);
            // 5. finished only if Amazon lists every file and none needs the old key any more
            Amazon.Listing all = Amazon.listFiles(st.folder(), 1_000_000);
            Store.Index after = st.readIndex(k);
            Set<String> fine = new HashSet<>(after.retire);
            fine.addAll(sr.unopenable); // opened by neither key: they don't depend on the old one
            for (Store.Item i : after.items) if (cur.equals(i.salt)) fine.add(i.id);
            int unknown = 0;
            for (JSONObject n : all.files) if (!fine.contains(n.getString("id"))) unknown++;
            int left = st.toReencrypt(after);
            if (all.complete && unknown == 0 && left == 0 && after.retire.isEmpty()) {
                st.finishPasswordChange();
                Journal.add("password change complete: every file uses the new key");
                done("Password change complete: every file in your vault now uses the new password.");
            } else if (!all.complete) {
                done("Password change: " + ok + " files re-encrypted. Amazon doesn't list vaults this big completely, "
                        + "so the old key is kept to make sure no file becomes unreadable.");
            } else done("Password change paused: " + Math.max(left, unknown) + " files left" + (failed > 0 ? " (" + failed + " failed, see Log)" : "")
                    + ". It continues the next time you unlock PhotoVault.");
        } catch (Throwable e) {
            Journal.add("re-encryption stopped: " + e);
            done("Password change paused: " + Store.explain(e) + ". It continues the next time you unlock PhotoVault.");
            if (Store.isAuth(e)) st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } });
        }
    }

    /** Moves replaced files to the Amazon trash and forgets them. Kept for another try if Amazon refuses. */
    void retire(byte[] k, List<String> ids) throws Exception {
        final List<String> done = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
            try { Amazon.trash(batch); done.addAll(batch); }
            catch (Amazon.ApiError e) {
                if (e.isAuth()) throw e;
                for (String one : batch) // one at a time: only "not found" counts as already gone
                    try { Amazon.trash(Collections.singletonList(one)); done.add(one); }
                    catch (Amazon.ApiError x) {
                        if (x.isAuth()) throw x;
                        if (x.code == 404) done.add(one);
                        else Journal.add("old copy not trashed yet, retried later: " + x.getMessage());
                    }
            }
        }
        if (!done.isEmpty()) st.edit(k, new Store.Edit() { public void apply(Store.Index x) { x.retire.removeAll(done); } });
    }
}
