package app.photovault;

import android.app.*;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the jobs (Jobs: uploads, Sync, re-encryption, previews) in the background as a foreground service (type
 * dataSync), so they continue when you leave the app or the vault auto-locks. Jobs run one at a time.
 * Each job works with its own copy of the key, wiped when the job ends. The notification never shows file names.
 */
public class SyncService extends Service {
    static final String UPLOAD = "upload", SYNC = "sync", REENCRYPT = "reencrypt", PREVIEWS = "previews", STOP = "stop", CHANNEL = "sync";
    static final int NOTE = 1, DONE_NOTE = 2;

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    Store st;
    NotificationManager nm;
    int pending, seq, lastStartId;
    volatile boolean destroyed;
    long lastNote;
    Jobs jobs;

    /** Notification and status texts are words only (no symbols like check marks, which some phone fonts show as boxes). */
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
        jobs = new Jobs(this, st, new Jobs.Host() {
            public void show(String text, int pct) { SyncService.this.show(text, pct); }
            public void done(String text, boolean bad) { SyncService.this.done(text, bad); }
        }, "s-");
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        try {
            startForeground(NOTE, note(getString(R.string.preparing), null, -1, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) { // e.g. a stale "Stop" tapped while the app is in the background
            Journal.add("background service not allowed now: " + e);
            if (pending == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        lastStartId = startId;
        String action = in == null ? "" : String.valueOf(in.getAction());
        if (STOP.equals(action)) {
            jobs.cancelUpTo = seq;
            if (pending > 0) show(getString(R.string.stopping), -1);
        } else if (st.key != null && (UPLOAD.equals(action) || SYNC.equals(action) || REENCRYPT.equals(action) || PREVIEWS.equals(action))) {
            final byte[] k = st.key.clone(), salt = st.salt(); // taken together: they always belong to the same key
            final int id = ++seq;
            final List<Uri> uris = new ArrayList<>();
            ClipData c = in.getClipData();
            if (c != null) for (int n = 0; n < c.getItemCount(); n++) uris.add(c.getItemAt(n).getUri());
            final boolean upload = UPLOAD.equals(action), reencrypt = REENCRYPT.equals(action), previews = PREVIEWS.equals(action);
            final String into = in.getStringExtra("folder") == null ? "" : in.getStringExtra("folder");
            pending++;
            st.busyJobs = pending;
            worker.execute(new Runnable() { public void run() {
                try {
                    if (id <= jobs.cancelUpTo) return;
                    if (!st.keyIsCurrent(k)) { // the password was changed after this job was queued
                        done(getString(upload ? R.string.not_added_pw : R.string.stopped_pw), true);
                        return;
                    }
                    if (upload) jobs.upload(k, salt, uris, into, id); else if (reencrypt) jobs.reencrypt(k, id);
                    else if (previews) jobs.previews(k, id); else jobs.sync(k, id);
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
        if (st.jobRunning) st.setStatus(getString(R.string.stopped), false, true); // jobs cancelled before they began
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(lastStartId);
    }

    /** Android 15+: data-sync services may run 6 h per day; stop cleanly when the system says so. */
    @Override public void onTimeout(int startId, int type) {
        jobs.cancelUpTo = seq;
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
                .setContentTitle(title == null ? "Cloakroll" : title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (ongoing) {
            b.setProgress(100, Math.max(0, pct), pct < 0);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SyncService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        } else b.setAutoCancel(true);
        return b.build();
    }

    /** Progress in the notification (throttled) and in the gallery. */
    void show(String text, int pct) {
        st.setStatus(text, true, false);
        if (destroyed) return; // stopped by the system (6 h limit): no orphan notification
        long now = SystemClock.elapsedRealtime();
        if (now - lastNote < 700 && pct > 0 && pct < 100) return;
        lastNote = now;
        nm.notify(NOTE, note(text, getString(R.string.n_working), pct, true));
    }

    void done(String text, boolean bad) {
        st.setStatus(text, false, bad);
        nm.notify(DONE_NOTE, note(text, "Cloakroll", 100, false));
    }
}
