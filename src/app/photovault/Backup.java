package app.photovault;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.*;

/**
 * Automatic backup: new photos and videos on the phone go to the vault by themselves, on Wi-Fi, without opening the
 * app. Two ways in: this scheduled job (about hourly, also while the vault is locked) and, when the app is open and
 * unlocked, the normal upload service (MainActivity.autoBackup). To encrypt while the vault is locked, a copy of the
 * key is kept on the phone, sealed by its secure hardware ("auto_key"); the vault itself still opens with the password.
 * What is new: everything the phone's media store lists from "auto_since" on that the vault doesn't hold yet
 * (same name and size), except files that failed today.
 */
public final class Backup extends JobService {
    static final String ALIAS = "photovault_backup_key";
    static final int JOB = 7;
    static final String[] PERMISSIONS = {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
    private static final Uri[] MEDIA = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
    private static final String[] COLS = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};

    private static volatile Backup running;
    private Jobs jobs;

    static boolean on(Store st) { return st.prefs.contains("auto_key"); }

    /** Also on mobile data, not only Wi-Fi (the user's choice when switching it on). */
    static boolean anyNetwork(Store st) { return st.prefs.getBoolean("auto_any_net", false); }

    /** The connection in use is one the user allows for the backup: Wi-Fi, or anything if mobile data is allowed too. */
    static boolean networkOk(Context c, Store st) {
        if (anyNetwork(st)) return true;
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
    }

    /** The app may read the phone's photos (all of them, or the ones the user picked on Android 14+). */
    static boolean allowed(Context c) {
        for (String p : PERMISSIONS) if (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        return c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
    }

    /** Switches it on with the open vault's key: files added to the phone from `since` (seconds) are backed up. Main thread. */
    static void turnOn(Context c, Store st, long since, boolean anyNetwork) throws Exception {
        st.prefs.edit().putString("auto_key", Hw.seal(ALIAS, st.key)).putLong("auto_since", since).putBoolean("auto_any_net", anyNetwork)
                .remove("auto_failed").apply();
        schedule(c, st);
    }

    static void turnOff(Context c, Store st) {
        st.prefs.edit().remove("auto_key").remove("auto_since").remove("auto_any_net").remove("auto_failed").apply();
        Hw.delete(ALIAS);
        c.getSystemService(JobScheduler.class).cancel(JOB);
        Journal.add("automatic backup switched off");
    }

    /** After a password change: the kept copy follows the key. */
    static void rekey(Store st, byte[] k) {
        if (!on(st)) return;
        try { st.prefs.edit().putString("auto_key", Hw.seal(ALIAS, k)).apply(); }
        catch (Exception e) { Journal.add("automatic backup: key copy not updated, switched off: " + e); st.prefs.edit().remove("auto_key").apply(); }
    }

    static void schedule(Context c, Store st) {
        JobInfo j = new JobInfo.Builder(JOB, new ComponentName(c, Backup.class))
                .setRequiredNetworkType(anyNetwork(st) ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .setPeriodic(60 * 60_000L, 20 * 60_000L)
                .build();
        c.getSystemService(JobScheduler.class).schedule(j);
    }

    /** Stops a run under way (the Stop button in the gallery). */
    static void stop() {
        Backup b = running;
        if (b != null && b.jobs != null) b.jobs.cancelUpTo = Integer.MAX_VALUE;
    }

    /** {id, name, size} of the photos and videos on the phone added from `since` (seconds; 0: all). Background thread. */
    private static List<Object[]> media(Context c, long since) {
        List<Object[]> out = new ArrayList<>();
        for (Uri base : MEDIA) {
            try (Cursor q = c.getContentResolver().query(base, COLS, MediaStore.MediaColumns.DATE_ADDED + " >= ?",
                    new String[]{String.valueOf(since)}, MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
                while (q != null && q.moveToNext())
                    out.add(new Object[]{ContentUris.withAppendedId(base, q.getLong(0)), q.isNull(1) ? "" : q.getString(1), q.getLong(2)});
            } catch (Exception e) { Journal.add("automatic backup: the phone's photos can't be listed: " + e.getClass().getSimpleName()); }
        }
        return out;
    }

    private static Set<String> names(List<Store.Item> items) {
        Set<String> s = new HashSet<>();
        for (Store.Item it : items) s.add(it.name + "|" + it.size);
        return s;
    }

    /** Files on the phone the vault doesn't hold yet, oldest first. `bytes[0]` gets their total size. Background thread. */
    static List<Uri> pending(Context c, Store st, List<Store.Item> items, long[] bytes) {
        Set<String> have = names(items), failed = failedToday(st);
        List<Uri> out = new ArrayList<>();
        for (Object[] m : media(c, st.prefs.getLong("auto_since", 0))) {
            if (have.contains(m[1] + "|" + m[2]) || failed.contains(m[0].toString())) continue;
            out.add((Uri) m[0]);
            bytes[0] += (Long) m[2];
        }
        return out;
    }

    /** Files on the phone the vault already holds (Free up space). Background thread. */
    static List<Uri> backedUp(Context c, List<Store.Item> items, long[] bytes) {
        Set<String> have = names(items);
        List<Uri> out = new ArrayList<>();
        for (Object[] m : media(c, 0)) if (have.contains(m[1] + "|" + m[2])) { out.add((Uri) m[0]); bytes[0] += (Long) m[2]; }
        return out;
    }

    /** Files that failed today are left for tomorrow, so one bad file doesn't cost a retry on every run. */
    private static Set<String> failedToday(Store st) {
        long day = System.currentTimeMillis() / 86_400_000L;
        if (st.prefs.getLong("auto_failed_day", -1) != day) return new HashSet<>();
        return new HashSet<>(st.prefs.getStringSet("auto_failed", Collections.<String>emptySet()));
    }

    /** Files that were pending before a run and still are after it. Background thread. */
    static void noteFailed(Store st, List<Uri> before, List<Uri> after) {
        Set<String> failed = failedToday(st);
        Set<String> still = new HashSet<>();
        for (Uri u : after) still.add(u.toString());
        for (Uri u : before) if (still.contains(u.toString())) failed.add(u.toString());
        st.prefs.edit().putLong("auto_failed_day", System.currentTimeMillis() / 86_400_000L).putStringSet("auto_failed", failed).apply();
    }

    // ---------------------------------------------------------------- the scheduled job

    @Override public boolean onStartJob(final JobParameters params) {
        final Store st = Store.get(this);
        if (!on(st) || !allowed(this)) { getSystemService(JobScheduler.class).cancel(JOB); return false; }
        if (st.busyJobs > 0 || st.backupRunning) return false; // the app is busy with it already: next time
        if (st.base() instanceof Amazon) android.webkit.CookieManager.getInstance(); // made on the main thread before background use
        final byte[] k;
        try { k = Hw.open(ALIAS, st.prefs.getString("auto_key", "")); }
        catch (Exception e) { Journal.add("automatic backup: key copy unreadable, switched off: " + e); turnOff(this, st); return false; }
        if (k == null || !st.keyIsCurrent(k)) { Journal.add("automatic backup: key copy out of date, switched off"); turnOff(this, st); return false; }
        st.backupRunning = true;
        running = this;
        jobs = new Jobs(this, st, new Jobs.Host() {
            public void show(String text, int pct) { st.setStatus(text, true, false, pct); }
            public void done(String text, boolean bad) { st.setStatus(text, false, bad); }
        }, "b-");
        new Thread(new Runnable() { public void run() {
            try {
                List<Uri> todo = pending(Backup.this, st, st.readIndex(k).items, new long[1]);
                if (!todo.isEmpty()) {
                    Journal.add("automatic backup: " + todo.size() + " new files");
                    jobs.upload(k, st.salt(), todo, "", 1);
                    noteFailed(st, todo, pending(Backup.this, st, st.readIndex(k).items, new long[1]));
                }
            } catch (Throwable e) {
                Journal.add("automatic backup failed: " + e);
            } finally {
                Arrays.fill(k, (byte) 0);
                st.backupRunning = false;
                running = null;
                jobFinished(params, false);
            }
        }}).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (jobs != null) jobs.cancelUpTo = Integer.MAX_VALUE;
        return true; // the rest on the next run
    }
}
