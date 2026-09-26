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
import org.json.JSONObject;

/**
 * Runs uploads and "Sync from Amazon" in the background as a foreground service (type dataSync),
 * so they continue when you leave the app or the vault auto-locks. Jobs run one at a time.
 * Each job works with its own copy of the key, wiped when the job ends.
 * The notification never shows file names.
 */
public class SyncService extends Service {
    static final String UPLOAD = "upload", SYNC = "sync", STOP = "stop", CHANNEL = "sync";
    static final int NOTE = 1, DONE_NOTE = 2;

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    Store st;
    NotificationManager nm;
    int pending, seq, lastStartId;
    volatile boolean destroyed;
    volatile int cancelUpTo;
    long lastNote;

    static void start(Context c, String action, List<Uri> uris) {
        Intent i = new Intent(c, SyncService.class).setAction(action);
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
            startForeground(NOTE, note("Preparing…", null, -1, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) { // e.g. a stale "Stop" tapped while the app is in the background
            Journal.add("background service not allowed now: " + e);
            if (pending == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        lastStartId = startId;
        String action = in == null ? "" : String.valueOf(in.getAction());
        if (STOP.equals(action)) {
            cancelUpTo = seq;
            if (pending > 0) show("Stopping after the current file…", -1);
        } else if (st.key != null && (UPLOAD.equals(action) || SYNC.equals(action))) {
            final byte[] k = st.key.clone();
            final int id = ++seq;
            final List<Uri> uris = new ArrayList<>();
            ClipData c = in.getClipData();
            if (c != null) for (int n = 0; n < c.getItemCount(); n++) uris.add(c.getItemAt(n).getUri());
            final boolean upload = UPLOAD.equals(action);
            pending++;
            worker.execute(new Runnable() { public void run() {
                try {
                    if (id <= cancelUpTo) return;
                    if (upload) upload(k, uris, id); else sync(k, id);
                } catch (Throwable e) {
                    Journal.add("background job failed: " + e);
                } finally {
                    Arrays.fill(k, (byte) 0);
                    st.post(new Runnable() { public void run() { if (--pending == 0) finish(); } });
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

    void upload(byte[] k, List<Uri> uris, int id) throws Exception {
        byte[] salt = st.salt();
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
                show(pre + " · encrypting", -1);
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
                    public void on(long d, long t) { int pct = (int) (100 * d / Math.max(1, total)); show(pre + " · uploading " + pct + "%", pct); }
                });
                Store.Item it = new Store.Item();
                it.id = node.getString("id"); it.name = name; it.mime = mime; it.taken = taken; it.size = size;
                if (thumb != null) Store.writeFile(st.thumbFile(it.id), Vault.seal(k, thumb));
                st.add(k, it);
                ok++;
            } catch (Throwable e) {
                failed++;
                Journal.add("upload " + (n + 1) + "/" + uris.size() + " failed: " + e);
                if (Store.isAuth(e)) { st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } }); break; }
            } finally { png.delete(); }
        }
        int skipped = uris.size() - ok - failed;
        done("✓ " + ok + " added" + (failed > 0 ? " · " + failed + " failed (see Log)" : "") + (skipped > 0 ? " · " + skipped + " not started" : ""));
    }

    /** Rebuilds the local list from the PhotoVault folder on Amazon (new phone, reinstall, items deleted on the website). */
    void sync(final byte[] k, int id) throws Exception {
        int restored = 0, skipped = 0, removed = 0;
        try {
            show("Sync · listing your vault on Amazon…", -1);
            Amazon.Listing listing = Amazon.listFiles(st.folder(), 1_000_000);
            List<JSONObject> nodes = listing.files;
            final Set<String> remote = new HashSet<>(), local = new HashSet<>();
            for (JSONObject n : nodes) remote.add(n.getString("id"));
            for (Store.Item it : st.readIndex(k)) local.add(it.id);
            if (listing.complete) { // drop items deleted on the Amazon website (only if Amazon listed everything)
                final List<String> gone = new ArrayList<>();
                for (String x : local) if (!remote.contains(x)) gone.add(x);
                removed = gone.size();
                if (removed > 0) st.edit(k, new Store.Edit() { public void apply(List<Store.Item> l) {
                    Iterator<Store.Item> i = l.iterator();
                    while (i.hasNext()) if (gone.contains(i.next().id)) i.remove();
                }});
            }
            int n = 0;
            for (JSONObject node : nodes) {
                n++;
                if (id <= cancelUpTo) break;
                String nid = node.getString("id");
                if (local.contains(nid)) continue;
                show("Sync · restoring " + n + " of " + nodes.size(), 100 * n / nodes.size());
                File f = new File(getCacheDir(), "sync.png");
                try {
                    Amazon.download(nid, st.owner(), f, null);
                    Vault.Opened o;
                    try (InputStream in = new FileInputStream(f)) { o = Vault.decryptPng(in, k); }
                    JSONObject m = new JSONObject(o.meta);
                    Store.Item it = new Store.Item();
                    it.id = nid; it.name = m.optString("name", "item"); it.mime = m.optString("mime", "");
                    it.taken = m.optLong("taken", System.currentTimeMillis()); it.size = o.dataLen();
                    byte[] thumb = Store.makeThumb(o.plain, o.dataOff, o.dataLen(), it.video());
                    if (thumb != null) Store.writeFile(st.thumbFile(nid), Vault.seal(k, thumb));
                    st.add(k, it);
                    restored++;
                } catch (Throwable e) {
                    if (Store.isAuth(e)) throw e;
                    Journal.add("sync: skipped a file: " + e);
                    skipped++;
                } finally { f.delete(); }
            }
            done("✓ Sync done · " + restored + " restored · " + removed + " removed"
                    + (skipped > 0 ? " · " + skipped + " skipped (not openable with this vault's key, see Log)" : ""));
        } catch (Throwable e) {
            Journal.add("sync failed: " + e);
            done("✗ Sync failed: " + Store.explain(e));
            if (Store.isAuth(e)) st.post(new Runnable() { public void run() { st.needLogin = true; st.changed(); } });
        }
    }
}
