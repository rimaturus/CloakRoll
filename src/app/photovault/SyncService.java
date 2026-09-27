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
import android.util.Base64;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs uploads and Sync in the background as a foreground service (type dataSync),
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
        int ok = 0, failed = 0;
        for (int n = 0; n < uris.size(); n++) {
            if (id <= cancelUpTo) break;
            Uri u = uris.get(n);
            String pre = "Adding " + (n + 1) + " of " + uris.size();
            try {
                String name = "item", mime = getContentResolver().getType(u);
                long taken = System.currentTimeMillis();
                try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0 && !c.isNull(i)) name = c.getString(i);
                        i = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN);
                        if (i >= 0 && !c.isNull(i) && c.getLong(i) > 0) taken = c.getLong(i);
                    }
                }
                if (mime == null) mime = "application/octet-stream";
                Store.Item it = new Store.Item();
                it.name = name; it.mime = mime; it.taken = taken; it.folder = into; it.salt = Store.hex(salt);
                byte[] thumb;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    byte[] first = new byte[Store.CHUNK];
                    int len = readUpTo(in, first);
                    byte[] second = len < Store.CHUNK ? null : new byte[Store.CHUNK];
                    int len2 = second == null ? 0 : readUpTo(in, second);
                    if (len2 == 0) { // fits in one PNG
                        String meta = new JSONObject().put("name", name).put("taken", taken).put("mime", mime).toString();
                        byte[] plain = Vault.plainBuffer(meta, len);
                        System.arraycopy(first, 0, plain, plain.length - len, len);
                        first = null;
                        thumb = Store.makeThumb(plain, plain.length - len, len, it.video());
                        it.id = put(k, salt, plain, Store.hex(Vault.random(8)) + ".png", pre + ": uploading");
                        it.size = len;
                    } else {
                        thumb = Store.makeThumb(this, u, null, it.video());
                        uploadParts(k, salt, it, first, second, len2, in, thumb, pre, id);
                    }
                }
                if (thumb != null) Store.writeFile(st.thumbFile(it.id), Vault.seal(k, thumb));
                st.add(k, it, false);
                ok++;
            } catch (Throwable e) {
                failed++;
                Journal.add("upload " + (n + 1) + "/" + uris.size() + " failed: " + e);
                if (Store.isAuth(e)) { st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } }); break; }
            }
        }
        int skipped = uris.size() - ok - failed;
        if (ok > 0 && !into.isEmpty()) st.backupFolders(k);
        done("Done: " + ok + " added" + (into.isEmpty() ? "" : " to a folder") + (failed > 0 ? ", " + failed + " failed (see Log)" : "")
                + (skipped > 0 ? ", " + skipped + " not started" : ""));
    }

    /** Reads until `buf` is full or the stream ends. */
    static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
        return n;
    }

    /** Encrypts one plaintext (meta + data) as a noise PNG, uploads it, returns the cloud's id for it. */
    String put(byte[] k, byte[] salt, byte[] plain, String name, final String label) throws Exception {
        File png = new File(getCacheDir(), "upload.png");
        try {
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(png), 1 << 16)) { Vault.encryptToPng(k, salt, plain, o); }
            final long total = png.length();
            return st.cloud().upload(png, name, st.folder(), new Cloud.Progress() {
                public void on(long d, long t) { int pct = (int) (100 * d / Math.max(1, total)); show(label + " " + pct + "%", pct); }
            }).getString("id");
        } finally { png.delete(); }
    }

    /**
     * A big file as several PNGs: parts 1..n-1 first, part 0 last, because part 0 carries the list of the others
     * (and name, date, size, a small preview). Every part holds a random group id and its index, inside the
     * encryption, so parts can't be swapped or mixed up unnoticed. If anything fails, the parts already
     * uploaded are trashed.
     */
    void uploadParts(byte[] k, byte[] salt, Store.Item it, byte[] first, byte[] next, int nextLen, InputStream in,
                     byte[] thumb, String pre, int job) throws Exception {
        String group = Store.hex(Vault.random(8));
        List<String> ids = new ArrayList<>();
        long total = first.length;
        try {
            for (int part = 1; nextLen > 0; part++) {
                if (job <= cancelUpTo) throw new IOException("stopped");
                String meta = new JSONObject().put("group", group).put("part", part).toString();
                byte[] plain = Vault.plainBuffer(meta, nextLen);
                System.arraycopy(next, 0, plain, plain.length - nextLen, nextLen);
                ids.add(put(k, salt, plain, Store.hex(Vault.random(8)) + ".png", pre + ": " + Store.human(total) + " sent, uploading"));
                total += nextLen;
                nextLen = readUpTo(in, next);
            }
            JSONObject m = new JSONObject().put("name", it.name).put("taken", it.taken).put("mime", it.mime)
                    .put("group", group).put("part", 0).put("parts", ids.size() + 1).put("size", total).put("ids", new JSONArray(ids));
            String small = Store.embeddedThumb(thumb);
            if (small != null && m.toString().length() + small.length() < 60_000) m.put("thumb", small);
            if (m.toString().getBytes("UTF-8").length > 65_000) throw new IOException("file too big (over about 80 GB)");
            byte[] plain = Vault.plainBuffer(m.toString(), first.length);
            System.arraycopy(first, 0, plain, plain.length - first.length, first.length);
            it.id = put(k, salt, plain, Store.hex(Vault.random(8)) + ".png", pre + ": " + Store.human(total) + " sent, finishing");
            it.parts.add(it.id);
            it.parts.addAll(ids);
            it.size = total;
        } catch (Throwable e) {
            if (!ids.isEmpty()) try { retireNow(ids); } catch (Exception x) { Journal.add("parts left in the cloud, Sync removes them: " + x); }
            throw e;
        }
    }

    /** Trashes cloud files in batches (no index involved). */
    void retireNow(List<String> ids) throws Exception { st.cloud().trashAll(ids); }

    static final class SyncResult {
        final Set<String> unopenable = new HashSet<>(); // files neither the current nor the previous key opens
        int retry;                                        // temporary failures: another Sync may restore them
        boolean ok;                                       // ran to the end
    }

    SyncResult sync(final byte[] k, int id) throws Exception {
        SyncResult res = new SyncResult();
        int restored = 0, skipped = 0, retry = 0, removed = 0;
        try {
            final Cloud cloud = st.cloud();
            show("Sync: listing your vault on " + cloud.name() + "...", -1);
            Cloud.Listing listing = cloud.listFiles(st.folder(), 1_000_000);
            List<JSONObject> nodes = listing.files;
            final Set<String> remote = new HashSet<>(), local = new HashSet<>();
            for (JSONObject n : nodes) remote.add(n.getString("id"));
            Store.Index before = st.readIndex(k);
            for (Store.Item it : before.items) local.add(it.id);
            final Set<String> skip = new HashSet<>();
            for (Store.Item it : before.items) skip.addAll(it.nodes());
            skip.addAll(before.retire); // old copies replaced during a password change: to be trashed, never restored
            if (listing.complete) { // drop items deleted on the cloud's website or app (only if it listed everything)
                final List<String> gone = new ArrayList<>();
                for (String x : local) if (!remote.contains(x)) gone.add(x);
                removed = gone.size();
                if (removed > 0) st.edit(k, new Store.Edit() { public void apply(Store.Index ix) {
                    Iterator<Store.Item> i = ix.items.iterator();
                    while (i.hasNext()) if (gone.contains(i.next().id)) i.remove();
                }});
            }
            // folders saved in the cloud: fill in items that aren't in a folder here (new phone, reinstall).
            // Skipped if the phone has newer folder changes that aren't in the cloud yet, unless a restore is under way.
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
            // newest first: part 0 of a big file is uploaded last, and it lists the other parts, which are then skipped
            List<JSONObject> ordered = new ArrayList<>(nodes);
            Collections.sort(ordered, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) {
                return b.optString("createdDate").compareTo(a.optString("createdDate"));
            }});
            List<String> strayParts = new ArrayList<>();
            Map<String, String> strayGroup = new HashMap<>();
            Set<String> heads = new HashSet<>(); // groups that have a part 0 in the cloud
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String twoDaysAgo = iso.format(new Date(System.currentTimeMillis() - 48L * 3600_000));
            int n = 0;
            for (JSONObject node : ordered) {
                n++;
                if (id <= cancelUpTo) break;
                String nid = node.getString("id");
                if (skip.contains(nid)) continue;
                show("Sync: restoring " + n + " of " + nodes.size(), 100 * n / nodes.size());
                File f = new File(getCacheDir(), "sync.png");
                try {
                    try { cloud.download(nid, f, null); }
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
                    byte[] thumb;
                    if (m.has("group")) { // part of a big file
                        if (m.optInt("part", -1) != 0) { // listed by its part 0 (seen later), or left over by an interrupted upload
                            if (node.optString("createdDate").compareTo(twoDaysAgo) < 0) { strayParts.add(nid); strayGroup.put(nid, m.optString("group")); }
                            continue;
                        }
                        heads.add(m.optString("group"));
                        JSONArray ids = m.optJSONArray("ids");
                        List<String> missing = new ArrayList<>();
                        it.parts.add(nid);
                        for (int i = 0; ids != null && i < ids.length(); i++) {
                            it.parts.add(ids.getString(i));
                            if (!remote.contains(ids.getString(i))) missing.add(ids.getString(i));
                        }
                        skip.addAll(it.parts);
                        if (ids == null || it.parts.size() != m.optInt("parts") || !missing.isEmpty()) {
                            Journal.add("sync: a big file has " + missing.size() + " missing parts in the cloud: not restored");
                            if (Arrays.equals(o.salt, st.salt())) res.unopenable.addAll(it.parts); // with the old key: keep it
                            skipped++;
                            continue;
                        }
                        it.size = m.optLong("size");
                        String small = m.optString("thumb", "");
                        thumb = small.isEmpty() ? null : Base64.decode(small, Base64.NO_WRAP);
                    } else thumb = Store.makeThumb(o.plain, o.dataOff, o.dataLen(), it.video());
                    if (thumb != null) Store.writeFile(st.thumbFile(nid), Vault.seal(k, thumb));
                    st.add(k, it, true);
                    restored++;
                } catch (Throwable e) {
                    if (Store.isAuth(e)) throw e;
                    Journal.add("sync: could not restore a file, retried on the next Sync: " + e);
                    retry++;
                } finally { f.delete(); }
            }
            // parts no part 0 refers to: an upload that was interrupted. Removed once the cloud has listed everything
            strayParts.removeAll(skip);
            for (Iterator<String> i = strayParts.iterator(); i.hasNext(); ) if (heads.contains(strayGroup.get(i.next()))) i.remove();
            if (!strayParts.isEmpty() && listing.complete && retry == 0 && id > cancelUpTo) {
                try {
                    cloud.trashAll(strayParts);
                    Journal.add("sync: removed " + strayParts.size() + " parts of an interrupted upload");
                } catch (Exception e) { if (Store.isAuth(e)) throw e; Journal.add("sync: leftover parts not removed yet: " + e); }
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
     * current key, uploaded again, and the old copy moved to the cloud's trash. Resumes where it stopped. The previous
     * key is deleted only when the cloud's complete listing shows no file that still needs it.
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
            for (Store.Item it : ix.items) if (!cur.equals(it.salt)) oldIds.addAll(it.nodes());
            final List<String> orphans = new ArrayList<>();
            for (JSONObject n : st.cloud().listFiles(st.folder(), 1_000_000).files) {
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
                File cached = st.blobFile(it.id);
                try {
                    final List<String> fresh = reencryptItem(k, salt, it, id);
                    if (fresh == null) { // already made with the current key: just note it
                        st.edit(k, new Store.Edit() { public void apply(Store.Index x) {
                            for (Store.Item i : x.items) if (i.id.equals(it.id)) i.salt = cur;
                        }});
                        ok++;
                        continue;
                    }
                    final boolean[] found = {false};
                    st.edit(k, new Store.Edit() { public void apply(Store.Index x) {
                        for (Store.Item i : x.items) if (i.id.equals(it.id)) {
                            i.id = fresh.get(0);
                            i.parts = fresh.size() > 1 ? new ArrayList<>(fresh) : new ArrayList<String>();
                            i.salt = cur;
                            found[0] = true;
                        }
                        x.retire.addAll(found[0] ? it.nodes() : fresh); // item deleted meanwhile: the new copy goes too
                    }});
                    if (found[0]) { st.renamed.put(it.id, fresh.get(0)); st.thumbFile(it.id).renameTo(st.thumbFile(fresh.get(0))); }
                    cached.delete();
                    retire(k, found[0] ? new ArrayList<>(it.nodes()) : fresh);
                    ok++;
                    if (ok % 25 == 0) st.backupFolders(k); // the folders file refers to item ids, which change
                } catch (Throwable e) {
                    if (Store.isAuth(e)) throw e;
                    Journal.add("re-encryption of one file failed, retried later: " + e);
                    cached.delete(); // a damaged cached copy must not be reused
                    failed++;
                }
            }
            st.backupFolders(k);
            // 5. finished only if the cloud lists every file and none needs the old key any more
            Cloud.Listing all = st.cloud().listFiles(st.folder(), 1_000_000);
            Store.Index after = st.readIndex(k);
            Set<String> fine = new HashSet<>(after.retire);
            fine.addAll(sr.unopenable); // opened by neither key: they don't depend on the old one
            for (Store.Item i : after.items) if (cur.equals(i.salt)) fine.addAll(i.nodes());
            int unknown = 0;
            for (JSONObject n : all.files) if (!fine.contains(n.getString("id"))) unknown++;
            int left = st.toReencrypt(after);
            if (all.complete && unknown == 0 && left == 0 && after.retire.isEmpty()) {
                st.finishPasswordChange();
                Journal.add("password change complete: every file uses the new key");
                done("Password change complete: every file in your vault now uses the new password.");
            } else if (!all.complete) {
                done("Password change: " + ok + " files re-encrypted. " + st.cloud().name() + " doesn't list vaults this big completely, "
                        + "so the old key is kept to make sure no file becomes unreadable.");
            } else done("Password change paused: " + Math.max(left, unknown) + " files left" + (failed > 0 ? " (" + failed + " failed, see Log)" : "")
                    + ". It continues the next time you unlock PhotoVault.");
        } catch (Throwable e) {
            Journal.add("re-encryption stopped: " + e);
            done("Password change paused: " + Store.explain(e) + ". It continues the next time you unlock PhotoVault.");
            if (Store.isAuth(e)) st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } });
        }
    }

    /**
     * Re-encrypts one item with the current key and uploads the new copy (named "r" + old id, so an interrupted run
     * can find it). Returns the new cloud ids (part 0 first), or null if the file already uses the current key.
     * The old copy is left alone: the caller records the swap, then trashes it.
     */
    List<String> reencryptItem(byte[] k, byte[] salt, Store.Item it, int job) throws Exception {
        File cached = st.blobFile(it.id), down = new File(getCacheDir(), "reenc-down.png");
        List<String> fresh = new ArrayList<>();
        try {
            List<String> olds = it.nodes();
            String group = null;
            for (int p = 1; p < olds.size(); p++) { // parts 1..n-1 of a big file: same content, new key
                if (job <= cancelUpTo) throw new IOException("stopped");
                st.cloud().download(olds.get(p), down, null);
                Vault.Opened o = st.decrypt(down, k);
                JSONObject m = new JSONObject(o.meta);
                if (group == null) group = m.optString("group");
                if (m.optInt("part", -1) != p || !group.equals(m.optString("group"))) throw new IOException("part " + (p + 1) + " doesn't belong to this file");
                fresh.add(put(k, salt, o.plain, "r" + olds.get(p) + "-" + Store.hex(Vault.random(4)) + ".png", "Password change: uploading"));
            }
            File src = cached.exists() ? cached : down;
            if (src == down) st.cloud().download(it.id, down, null);
            Vault.Opened o = st.decrypt(src, k);
            if (olds.size() == 1 && Arrays.equals(o.salt, salt)) return null;
            if (olds.size() > 1 && (new JSONObject(o.meta).optInt("part", -1) != 0 || !new JSONObject(o.meta).optString("group").equals(group)))
                throw new IOException("part 1 doesn't belong to this file");
            byte[] plain = o.plain;
            if (olds.size() > 1) { // part 0 lists the other parts: point it to the new ones
                String meta = new JSONObject(o.meta).put("ids", new JSONArray(fresh)).toString();
                plain = Vault.plainBuffer(meta, o.dataLen());
                System.arraycopy(o.plain, o.dataOff, plain, plain.length - o.dataLen(), o.dataLen());
            }
            fresh.add(0, put(k, salt, plain, "r" + it.id + "-" + Store.hex(Vault.random(4)) + ".png", "Password change: uploading"));
            return fresh;
        } catch (Throwable e) {
            cached.delete(); // a damaged cached copy must not be reused
            if (!fresh.isEmpty()) try { retireNow(fresh); } catch (Exception x) { Journal.add("new copies left, removed on the next run: " + x); }
            throw e;
        } finally { down.delete(); }
    }

    /** Moves replaced files to the cloud's trash and forgets them. Kept for another try if the cloud refuses. */
    void retire(byte[] k, List<String> ids) throws Exception {
        final List<String> done = new ArrayList<>();
        Cloud cloud = st.cloud();
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
            try { cloud.trash(batch); done.addAll(batch); }
            catch (Cloud.ApiError e) {
                if (e.isAuth()) throw e;
                for (String one : batch) // one at a time: only "not found" counts as already gone
                    try { cloud.trash(Collections.singletonList(one)); done.add(one); }
                    catch (Cloud.ApiError x) {
                        if (x.isAuth()) throw x;
                        if (x.code == 404) done.add(one);
                        else Journal.add("old copy not trashed yet, retried later: " + x.getMessage());
                    }
            }
        }
        if (!done.isEmpty()) st.edit(k, new Store.Edit() { public void apply(Store.Index x) { x.retire.removeAll(done); } });
    }
}
