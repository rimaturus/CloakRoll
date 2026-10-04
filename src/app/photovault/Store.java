package app.photovault;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import android.util.Size;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.AEADBadTagException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * State shared by the screens and the background service (one per app process).
 * Fields are read and written on the main thread; the index file is guarded by `synchronized`.
 */
final class Store {
    /**
     * Files bigger than this are stored as several PNGs ("parts") of at most this size, each encrypted and
     * authenticated on its own, so there is no size limit and memory use stays small.
     */
    static final int CHUNK = 32 << 20;
    static final long AUTO_LOCK_MS = 60_000, PICKER_LOCK_MS = 10 * 60_000;

    private static Store instance;

    static Store get(Context c) { // main thread
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    static final class Item {
        String id, name, mime, folder = ""; // folder "" = not in a folder
        String salt = "";                   // hex salt of the key the file in the cloud was made with ("" = before v1.3)
        List<String> parts = new ArrayList<>(); // big files: cloud ids of all parts, [0] == id. Empty: one PNG
        long taken, size;
        private long when;                  // sort key, worked out once
        /** When it was taken: the date in its name if there is one (`taken` is often just when it was added), else `taken`. */
        long when() { if (when == 0) { when = Names.date(name); if (when == 0) when = taken; } return when; }
        boolean video() { return mime != null && mime.startsWith("video/"); }
        /** Every cloud file this item is made of. */
        List<String> nodes() { return parts.isEmpty() ? Collections.singletonList(id) : parts; }
    }

    /** The local list: items and folder names. A folder is a label on items (one level, like albums). */
    static final class Index {
        final List<Item> items = new ArrayList<>();
        final List<String> folders = new ArrayList<>();
        final List<String> retire = new ArrayList<>(); // old copies replaced during a password change, still to trash

        boolean hasFolder(String name) {
            for (String f : folders) if (f.equalsIgnoreCase(name)) return true;
            return false;
        }

        /** The existing folder with this name ignoring case, or the name itself. */
        String canonical(String name) {
            for (String f : folders) if (f.equalsIgnoreCase(name)) return f;
            return name;
        }
    }

    final Context app;
    final SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(48 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static final String VERIFY = "PhotoVault password check";

    byte[] key;                           // vault key: in memory only while unlocked
    int busyJobs;                         // background jobs queued or running (main thread)
    final Map<String, String> renamed = new java.util.concurrent.ConcurrentHashMap<>(); // old -> new item id (password change)
    List<Item> items = new ArrayList<>(); // decrypted index: in memory only while unlocked
    List<String> folders = new ArrayList<>();
    String status;                        // last background-job line shown in the gallery
    boolean jobRunning, statusBad, needLogin, allowScreenshots;
    volatile boolean backupRunning;       // the scheduled automatic backup (Backup) is uploading
    String reopen;                        // screen to show again after the language changed (the activity restarts)
    long backgroundSince;
    Runnable onChange;                    // set by the visible screen

    /** Posted 60 s after the app goes to the background. */
    final Runnable autoLock = new Runnable() { public void run() { lock(); changed(); } };

    private final Amazon amazon;
    private final OneDrive oneDrive;
    private final GoogleDrive google;
    final Local local;  // the phone folder: the vault itself ("local"), or where the copies go next to a cloud
    final Stats stats;
    private volatile Transfers transfers;

    private Store(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("vault", Context.MODE_PRIVATE);
        Amazon.tld = prefs.getString("amazon_site", "it");
        amazon = new Amazon(this);
        oneDrive = new OneDrive(this);
        google = new GoogleDrive(this);
        local = new Local(this);
        stats = new Stats(this);
        deletePlayback(); // leftovers if the app was killed while a video was open
    }

    /** Where this vault's files are, chosen at setup: "amazon" (also before v1.5), "onedrive", "google" or "local". */
    String backend() {
        String b = prefs.getString("backend", "");
        return "onedrive".equals(b) || "google".equals(b) || "local".equals(b) ? b : "amazon";
    }

    /** The storage itself, without timing or phone copy: for sign-in, the self-test and texts. Any thread. */
    Cloud base() {
        switch (backend()) {
            case "onedrive": return oneDrive;
            case "google": return google;
            case "local": return local;
            default: return amazon;
        }
    }

    /** A copy of every encrypted file is kept in a folder on the phone too (next to a cloud). */
    boolean keepsCopy() { return !"local".equals(backend()) && prefs.getBoolean("local_copy", false) && local.tree() != null; }

    /** The storage for reading and writing vault files: timed, and with the phone copy if it's on. Any thread. */
    Cloud cloud() {
        Cloud b = base();
        Local copy = keepsCopy() ? local : null;
        Transfers t = transfers;
        if (t == null || t.c != b || t.copy != copy) transfers = t = new Transfers(b, copy, stats);
        return t;
    }

    /** Decrypted videos are written to disk only for playback; removed on lock, on close and at start. */
    void deletePlayback() {
        File[] fs = app.getCacheDir().listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("play")) f.delete();
    }

    void changed() { if (onChange != null) onChange.run(); }

    void post(Runnable r) { ui.post(r); }

    /** Any thread. `bad`: the job failed or stopped early (shown with a warning sign). */
    void setStatus(final String s, final boolean running, final boolean bad) {
        post(new Runnable() { public void run() { status = s; jobRunning = running; statusBad = bad; changed(); } });
    }

    /** Main thread. Background jobs keep their own copy of the key until they finish. */
    void lock() {
        if (key != null) Arrays.fill(key, (byte) 0);
        key = null;
        items = new ArrayList<>();
        folders = new ArrayList<>();
        thumbs.evictAll();
        deletePlayback();
    }

    byte[] salt() { return hex(prefs.getString("salt", "")); }
    String folder() { return prefs.getString("folder", ""); }
    String owner() { return prefs.getString("owner", ""); }

    File thumbFile(String id) { File d = new File(app.getFilesDir(), "thumbs"); d.mkdirs(); return new File(d, id); }
    File blobFile(String id) { File d = new File(app.getCacheDir(), "blobs"); d.mkdirs(); return new File(d, id + ".png"); }

    /** Keeps at most ~500 MB of downloaded (still encrypted) PNGs as a speed-up cache. */
    void trimBlobCache() {
        File[] fs = new File(app.getCacheDir(), "blobs").listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
        long sum = 0;
        for (File f : fs) { sum += f.length(); if (sum > 500L << 20) f.delete(); }
    }

    // ---------------------------------------------------------------- index: encrypted list of items

    private File indexFile() { return new File(app.getFilesDir(), "index.bin"); }

    /** Reads the encrypted index (or the plaintext list of v1.0, which the next write replaces). */
    synchronized Index readIndex(byte[] k) throws Exception {
        File f = indexFile(), old = new File(app.getFilesDir(), "index.json");
        String json = f.exists() ? new String(openSealed(k, readFile(f)), "UTF-8") : old.exists() ? new String(readFile(old), "UTF-8") : "[]";
        Index ix = new Index();
        JSONArray a, fs = null, rs = null;
        if (json.trim().startsWith("[")) a = new JSONArray(json); // v1.0 / v1.1: just the items
        else { JSONObject o = new JSONObject(json); a = o.getJSONArray("items"); fs = o.optJSONArray("folders"); rs = o.optJSONArray("retire"); }
        for (int i = 0; i < a.length(); i++) ix.items.add(item(a.getJSONObject(i)));
        if (fs != null) for (int i = 0; i < fs.length(); i++) ix.folders.add(fs.getString(i));
        if (rs != null) for (int i = 0; i < rs.length(); i++) ix.retire.add(rs.getString(i));
        return ix;
    }

    /** An item as it is written in the list, on the phone and in the copy of the list in the cloud. */
    static JSONObject json(Item it) throws org.json.JSONException {
        JSONObject o = new JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("taken", it.taken)
                .put("size", it.size).put("folder", it.folder).put("salt", it.salt);
        if (!it.parts.isEmpty()) o.put("parts", new JSONArray(it.parts));
        return o;
    }

    static Item item(JSONObject o) throws org.json.JSONException {
        Item it = new Item();
        it.id = o.getString("id"); it.name = o.optString("name"); it.mime = o.optString("mime");
        it.taken = o.optLong("taken"); it.size = o.optLong("size"); it.folder = o.optString("folder", "");
        it.salt = o.optString("salt", "");
        JSONArray ps = o.optJSONArray("parts");
        if (ps != null) for (int j = 0; j < ps.length(); j++) it.parts.add(ps.getString(j));
        return it;
    }

    synchronized void writeIndex(byte[] k, Index ix) throws Exception {
        File tmp = new File(app.getFilesDir(), "index.bin.tmp");
        writeSynced(tmp, Vault.seal(k, indexJson(ix)));
        if (!tmp.renameTo(indexFile())) throw new IOException("cannot save the index");
        new File(app.getFilesDir(), "index.json").delete(); // v1.0 plaintext index
    }

    private static byte[] indexJson(Index ix) throws Exception {
        Collections.sort(ix.items, new Comparator<Item>() { public int compare(Item a, Item b) { return Long.compare(b.when(), a.when()); } });
        Collections.sort(ix.folders, String.CASE_INSENSITIVE_ORDER);
        for (Item it : ix.items) if (!it.folder.isEmpty()) it.folder = ix.canonical(it.folder); // one spelling per folder
        JSONArray a = new JSONArray();
        for (Item it : ix.items) a.put(json(it));
        return new JSONObject().put("items", a).put("folders", new JSONArray(ix.folders)).put("retire", new JSONArray(ix.retire))
                .toString().getBytes("UTF-8");
    }

    static void writeSynced(File f, byte[] b) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); o.getFD().sync(); }
    }

    interface Edit { void apply(Index ix); }

    /** Any thread: changes the index on disk and, if the vault is still open with this key, the lists on screen. */
    void edit(byte[] jobKey, Edit e) throws Exception {
        final byte[] k = jobKey.clone(); // the caller may wipe its key before the UI update below runs
        synchronized (this) {
            final Index ix;
            try { ix = readIndex(k); e.apply(ix); writeIndex(k, ix); }
            catch (Exception x) { Arrays.fill(k, (byte) 0); throw x; }
            post(new Runnable() { public void run() { // posted in write order
                if (Store.this.key != null && Arrays.equals(Store.this.key, k)) { items = ix.items; folders = ix.folders; changed(); }
                Arrays.fill(k, (byte) 0);
            }});
        }
    }

    /**
     * Adds an item. If its folder no longer exists (renamed or deleted during an upload), `createFolder` decides:
     * create it again (restore from the cloud) or put the item in the main view (upload).
     */
    void add(byte[] k, final Item it, final boolean createFolder) throws Exception {
        edit(k, new Edit() { public void apply(Index ix) {
            for (Item x : ix.items) if (x.id.equals(it.id)) return;
            if (!it.folder.isEmpty()) {
                it.folder = ix.canonical(it.folder);
                if (!ix.folders.contains(it.folder)) { if (createFolder) ix.folders.add(it.folder); else it.folder = ""; }
            }
            ix.items.add(it);
        }});
    }

    // ---------------------------------------------------------------- folders, backed up to the cloud (encrypted)

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean backupQueued; // guarded by this
    private byte[] backupKey;     // guarded by this

    /** The folders on the phone are newer than the copy in the cloud: Sync must not apply that copy. Call before the change. */
    synchronized void markFoldersDirty() { prefs.edit().putBoolean("folders_dirty", true).commit(); }

    synchronized boolean foldersDirty() { return prefs.getBoolean("folders_dirty", false); }

    /** Subfolder "index" of the vault folder: holds one encrypted PNG with the folder organisation. */
    String indexFolder() throws Exception {
        synchronized (indexLock) { // backup, Sync and restore may ask at the same time: one lookup, one folder
            String pref = "index_folder_" + folder(), id = prefs.getString(pref, "");
            if (id.isEmpty()) { id = cloud().folder(folder(), "index"); prefs.edit().putString(pref, id).apply(); }
            return id;
        }
    }

    private final Object indexLock = new Object();

    /**
     * Saves the folders (names and which item is in which) and the list of items (name, date, size, parts: what the
     * phone's own list holds) to the cloud as one more encrypted PNG, so a new phone gets them back with Sync
     * without downloading every file. Any thread; runs in the background; calls made while one is waiting merge into it.
     */
    void backupFolders(byte[] jobKey) {
        synchronized (this) {
            markFoldersDirty();
            if (prefs.getBoolean("restore_pending", false)) { // the list may still be partial: upload after the restore
                Journal.add("folders will be saved to the cloud when the restore has finished");
                return;
            }
            if (backupKey != null) Arrays.fill(backupKey, (byte) 0);
            backupKey = jobKey.clone(); // a waiting backup always uses the newest key
            if (backupQueued) return;
            backupQueued = true;
        }
        bg.execute(new Runnable() { public void run() {
            final byte[] k;
            synchronized (Store.this) { backupQueued = false; k = backupKey; backupKey = null; }
            File png = new File(app.getCacheDir(), "folders-up.png");
            try {
                Index ix = readIndex(k);
                JSONObject map = new JSONObject();
                JSONArray list = new JSONArray();
                String cur = prefs.getString("salt", "");
                for (Item it : ix.items) {
                    if (!it.folder.isEmpty()) map.put(it.id, it.folder);
                    JSONObject o = json(it);
                    o.remove("folder"); // in the map
                    if (cur.equals(it.salt)) o.remove("salt"); // said once for all
                    list.put(o);
                }
                byte[] data = new JSONObject().put("folders", new JSONArray(ix.folders)).put("map", map).put("salt", cur).put("items", list)
                        .toString().getBytes("UTF-8");
                String meta = new JSONObject().put("name", "folders.json").put("taken", System.currentTimeMillis()).put("mime", "application/json").toString();
                byte[] plain = Vault.plainBuffer(meta, data.length);
                System.arraycopy(data, 0, plain, plain.length - data.length, data.length);
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(png))) { Vault.encryptToPng(k, salt(), plain, o); }
                String dir = indexFolder();
                Cloud c = cloud();
                String id = c.upload(png, hex(Vault.random(8)) + ".png", dir, null).getString("id");
                List<String> old = new ArrayList<>();
                for (JSONObject n : c.listFiles(dir, 1000).files) if (!n.getString("id").equals(id)) old.add(n.getString("id"));
                if (!old.isEmpty()) c.trashAll(old);
                synchronized (Store.this) { if (!backupQueued) prefs.edit().putBoolean("folders_dirty", false).commit(); }
                Journal.add("folders saved to the cloud (encrypted)");
            } catch (Exception e) {
                Journal.add("folders not saved to the cloud yet (retried on the next change): " + e);
            } finally { png.delete(); Arrays.fill(k, (byte) 0); }
        }});
    }

    /** The folder organisation saved in the cloud: {"folders":[...], "map":{itemId: folder}}, or null. Background thread. */
    JSONObject readFoldersBackup(byte[] k) throws Exception {
        List<JSONObject> l = cloud().listFiles(indexFolder(), 1000).files;
        if (l.isEmpty()) return null;
        JSONObject newest = l.get(0);
        for (JSONObject n : l) if (n.optString("createdDate").compareTo(newest.optString("createdDate")) > 0) newest = n;
        File f = new File(app.getCacheDir(), "folders-down.png");
        try {
            cloud().download(newest.getString("id"), f, null);
            Vault.Opened o = decrypt(f, k);
            return new JSONObject(new String(o.plain, o.dataOff, o.dataLen(), "UTF-8"));
        } finally { f.delete(); }
    }

    // ---------------------------------------------------------------- password change
    //
    // The key comes from the password, so a new password means a new key, and every file in the cloud must be
    // re-encrypted with it (SyncService.reencrypt). Until that's done the previous key is kept, sealed with
    // the new one ("old_key"), so both kinds of file keep opening. It is deleted when the last file is done.

    /** True while files made with the previous password still exist. */
    boolean reencrypting() { return prefs.contains("old_key"); }

    /** The previous key, or null. The caller wipes it. */
    byte[] oldKey(byte[] k) {
        String s = prefs.getString("old_key", "");
        if (s.isEmpty()) return null;
        try { return Vault.open(k, Base64.decode(s, Base64.NO_WRAP)); } catch (Exception e) { return null; }
    }

    /** Local secret (index, preview) sealed with the current key, or with the previous one if a change was interrupted. */
    byte[] openSealed(byte[] k, byte[] sealed) throws Exception {
        try { return Vault.open(k, sealed); }
        catch (AEADBadTagException e) {
            byte[] old = oldKey(k);
            if (old == null) throw e;
            try { return Vault.open(old, sealed); } finally { Arrays.fill(old, (byte) 0); }
        }
    }

    /** Decrypts a vault PNG with the key it was made with: the current one, or the previous one during a change. */
    Vault.Opened decrypt(File png, byte[] k) throws Exception {
        byte[] s;
        try (InputStream in = new FileInputStream(png)) { s = Vault.readSalt(in); }
        byte[] old = Arrays.equals(s, salt()) ? null : oldKey(k);
        long t0 = android.os.SystemClock.elapsedRealtime();
        try (InputStream in = new FileInputStream(png)) {
            Vault.Opened o = Vault.decryptPng(in, old != null && hex(s).equals(prefs.getString("old_salt", "")) ? old : k);
            stats.add(Stats.DEC, png.length(), android.os.SystemClock.elapsedRealtime() - t0);
            return o;
        } finally { if (old != null) Arrays.fill(old, (byte) 0); }
    }

    /** Items whose file in the cloud still uses an older key. */
    int toReencrypt(Index ix) {
        String cur = prefs.getString("salt", "");
        int n = 0;
        for (Item it : ix.items) if (!cur.equals(it.salt)) n++;
        return n;
    }

    /**
     * Switches this phone to the new key: previews and list re-sealed, new salt and password check saved, previous key
     * kept (sealed with the new one) until SyncService has re-encrypted every file. Background thread.
     */
    synchronized void changePassword(byte[] oldK, byte[] newK, byte[] newSalt, String verifier) throws Exception {
        if (reencrypting()) throw new IOException("a password change is still being applied");
        Index ix = readIndex(oldK);
        File[] thumbs = new File(app.getFilesDir(), "thumbs").listFiles();
        List<File> ready = new ArrayList<>();
        if (thumbs != null) for (File f : thumbs) if (f.getName().endsWith(".new")) f.delete(); // leftovers of a crash
        thumbs = new File(app.getFilesDir(), "thumbs").listFiles();
        if (thumbs != null) for (File f : thumbs) {
            try {
                File n = new File(f.getPath() + ".new");
                writeSynced(n, Vault.seal(newK, Vault.open(oldK, readFile(f))));
                ready.add(f);
            } catch (Exception e) { f.delete(); } // unreadable preview: the item just shows without one
        }
        String oldSalt = prefs.getString("salt", "");
        for (Item it : ix.items) if (it.salt.isEmpty()) it.salt = oldSalt; // files from before v1.3 use the only key there was
        File tmp = new File(app.getFilesDir(), "index.bin.new");
        writeSynced(tmp, Vault.seal(newK, indexJson(ix)));
        // the switch: one atomic commit; if the app dies right after it, openSealed() still reads the old files
        boolean saved = prefs.edit().putString("salt", hex(newSalt)).putString("verifier", verifier)
                .putString("old_salt", oldSalt).putString("old_key", Base64.encodeToString(Vault.seal(newK, oldK), Base64.NO_WRAP))
                .remove("bio_iv").remove("bio_ct").remove("unopenable").remove("suspect").commit();
        final byte[] k = newK.clone();
        try {
            if (!saved) throw new IOException("settings not saved");
            if (!tmp.renameTo(indexFile())) throw new IOException("cannot save the index");
            for (File f : ready) new File(f.getPath() + ".new").renameTo(f);
        } catch (IOException e) { // half switched: lock, so the next unlock (new password) starts from a consistent state
            Arrays.fill(k, (byte) 0);
            post(new Runnable() { public void run() { lock(); changed(); } });
            throw e;
        }
        post(new Runnable() { public void run() { // the vault stays open with the new key (unless it locked meanwhile)
            if (key != null) { Arrays.fill(key, (byte) 0); key = k; } else Arrays.fill(k, (byte) 0);
        }});
    }

    /** The item id now, if a password change replaced the file. */
    String currentId(String id) { String n = renamed.get(id); return n == null ? id : n; }

    /** True if `k` is the vault's key (checked against the stored password check). */
    boolean keyIsCurrent(byte[] k) {
        try { return Arrays.equals(Vault.open(k, Base64.decode(prefs.getString("verifier", ""), Base64.NO_WRAP)), VERIFY.getBytes("UTF-8")); }
        catch (Exception e) { return false; }
    }

    /** Previews re-sealed by an interrupted password change (".new" files) are put in place. Background thread. */
    synchronized void applyPendingPreviews(byte[] k) {
        File[] fs = new File(app.getFilesDir(), "thumbs").listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".new")) {
            try { Vault.open(k, readFile(f)); f.renameTo(new File(f.getPath().substring(0, f.getPath().length() - 4))); }
            catch (Exception e) { f.delete(); }
        }
    }

    /** Called when no file with the previous key is left: the old password becomes useless. */
    synchronized void finishPasswordChange() {
        prefs.edit().remove("old_key").remove("old_salt").commit();
    }

    // ---------------------------------------------------------------- previews

    /** Small JPEG preview (kept only on the phone, encrypted) of a file held in memory. */
    static byte[] makeThumb(final byte[] b, final int off, final int len, boolean video) {
        try {
            if (video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(new MediaDataSource() {
                    public int readAt(long pos, byte[] buf, int o, int size) {
                        if (pos >= len) return -1;
                        int n = (int) Math.min(size, len - pos);
                        System.arraycopy(b, off + (int) pos, buf, o, n);
                        return n;
                    }
                    public long getSize() { return len; }
                    public void close() { }
                });
                return videoThumb(r);
            }
            return jpeg(ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b, off, len).slice()), SAMPLE), 480, 82);
        } catch (Throwable e) { Journal.add("preview not created: " + e); return null; }
    }

    /** Same, for a big file read straight from the phone's gallery (upload) or from an assembled file (restore). */
    static byte[] makeThumb(Context c, Uri u, File f, boolean video) {
        try {
            if (video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                if (f != null) r.setDataSource(f.getPath()); else r.setDataSource(c, u);
                return videoThumb(r);
            }
            ImageDecoder.Source s = f != null ? ImageDecoder.createSource(f) : ImageDecoder.createSource(c.getContentResolver(), u);
            return jpeg(ImageDecoder.decodeBitmap(s, SAMPLE), 480, 82);
        } catch (Throwable e) { Journal.add("preview not created: " + e); return null; }
    }

    private static final ImageDecoder.OnHeaderDecodedListener SAMPLE = new ImageDecoder.OnHeaderDecodedListener() {
        public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
            Size s = info.getSize();
            d.setTargetSampleSize(Math.max(1, Math.min(s.getWidth(), s.getHeight()) / 480));
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        }
    };

    private static byte[] videoThumb(MediaMetadataRetriever r) {
        Bitmap f;
        try { f = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480); }
        finally { try { r.release(); } catch (Exception ignored) { } }
        if (f == null) return null;
        Bitmap bm = f.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = bm.getWidth() / 2f, cy = bm.getHeight() / 2f, rad = Math.min(cx, cy) / 3f;
        p.setColor(0x88000000);
        c.drawCircle(cx, cy, rad, p);
        p.setColor(Color.WHITE);
        Path tri = new Path();
        tri.moveTo(cx - rad / 3, cy - rad / 2);
        tri.lineTo(cx + rad / 2, cy);
        tri.lineTo(cx - rad / 3, cy + rad / 2);
        tri.close();
        c.drawPath(tri, p);
        return jpeg(bm, 480, 82);
    }

    static byte[] jpeg(Bitmap bm, int maxSide, int quality) {
        int w = bm.getWidth(), h = bm.getHeight(), m = Math.max(w, h);
        if (m > maxSide) bm = Bitmap.createScaledBitmap(bm, Math.max(1, w * maxSide / m), Math.max(1, h * maxSide / m), true);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, quality, o);
        return o.toByteArray();
    }

    /** Saves an item's preview, encrypted. `jpeg` null: none can be made of this file; an empty one says so, and nobody tries again. */
    void saveThumb(String id, byte[] k, byte[] jpeg) throws Exception {
        writeFile(thumbFile(id), Vault.seal(k, jpeg == null ? new byte[0] : jpeg));
    }

    /** Items without a preview on this phone (restored from the list in the cloud, not opened yet). */
    List<Item> withoutPreview(List<Item> all) {
        String[] have = new File(app.getFilesDir(), "thumbs").list();
        Set<String> names = new HashSet<>(Arrays.asList(have == null ? new String[0] : have));
        List<Item> l = new ArrayList<>();
        for (Item it : all) if (!names.contains(it.id)) l.add(it);
        return l;
    }

    /** A smaller copy of a preview, stored inside part 0 of a big file so a new phone gets it without the whole file. */
    static String embeddedThumb(byte[] thumb) {
        if (thumb == null) return null;
        Bitmap b = BitmapFactory.decodeByteArray(thumb, 0, thumb.length);
        return b == null ? null : Base64.encodeToString(jpeg(b, 256, 70), Base64.NO_WRAP);
    }

    // ---------------------------------------------------------------- big files made of parts

    /**
     * Downloads and decrypts every part of a big item into `out`, checking that each part belongs to this item
     * and sits at its place (the checks are inside the authenticated, encrypted part). Keeps part 0 in the
     * download cache for the "cloud view". Background thread.
     */
    void assemble(final Item it, byte[] k, File out, final Cloud.Progress progress) throws Exception {
        File tmp = new File(app.getCacheDir(), "part-" + it.id + ".png");
        String group = null;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            for (int i = 0; i < it.parts.size(); i++) {
                if (progress != null) progress.on(i, it.parts.size());
                File src = i == 0 ? blobFile(it.id) : tmp;
                final int at = i;
                final Cloud.Progress p2 = progress == null ? null : new Cloud.Progress() { // lets the viewer stop mid-part
                    public void on(long d, long t) { progress.on(at, it.parts.size()); }
                };
                if (i > 0 || !src.exists()) cloud().download(it.parts.get(i), src, p2);
                Vault.Opened p = decrypt(src, k);
                JSONObject m = new JSONObject(p.meta);
                if (i == 0) {
                    group = m.optString("group");
                    if (m.optInt("parts") != it.parts.size()) throw new IOException("big file: wrong number of parts");
                }
                if (m.optInt("part", -1) != i || group == null || group.isEmpty() || !group.equals(m.optString("group")))
                    throw new IOException("part " + (i + 1) + " doesn't belong here: the file in the cloud was changed");
                o.write(p.plain, p.dataOff, p.dataLen());
            }
        } catch (Exception e) { out.delete(); throw e; }
        finally { tmp.delete(); }
        if (out.length() != it.size) { out.delete(); throw new IOException("big file incomplete"); }
    }

    /** Decrypts a stored preview, or null. Any thread; the caller caches it on the main thread while unlocked. */
    Bitmap thumb(String id, byte[] k) {
        try {
            File f = thumbFile(id);
            if (!f.exists()) return null;
            byte[] j = openSealed(k, readFile(f));
            return BitmapFactory.decodeByteArray(j, 0, j.length);
        } catch (Exception e) { Journal.add("preview unreadable: " + e); return null; }
    }

    // ---------------------------------------------------------------- small helpers

    /** "about 40 s", "about 3 min", "about 2 h 10 min"; "" if unknown (negative). */
    String eta(long secs) {
        if (secs < 0) return "";
        if (secs < 60) return app.getString(R.string.eta_s, Math.max(1, secs));
        if (secs < 3600) return app.getString(R.string.eta_min, (secs + 59) / 60);
        return app.getString(R.string.eta_h, secs / 3600, secs % 3600 / 60);
    }

    /** " · about 3 min left" to put after a progress text; "" if unknown (negative). */
    String left(long secs) {
        String t = eta(secs);
        return t.isEmpty() ? "" : " · " + app.getString(R.string.eta_left, t);
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /** An error in words, in the app's language. Our own IOExceptions carry their message as it is. */
    String explain(Throwable e) {
        if (isAuth(e)) return app.getString(R.string.e_auth);
        if (e instanceof AEADBadTagException) return app.getString(R.string.e_tamper);
        if (e instanceof java.net.UnknownHostException) return app.getString(R.string.e_offline);
        if (e instanceof OutOfMemoryError) return app.getString(R.string.e_memory);
        if (e.getClass() == IOException.class && e.getMessage() != null) return e.getMessage();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static boolean isAuth(Throwable e) { return e instanceof Cloud.ApiError && ((Cloud.ApiError) e).isAuth(); }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] readFile(File f) throws IOException { try (InputStream in = new FileInputStream(f)) { return Cloud.readAll(in); } }

    static void writeFile(File f, byte[] b) throws IOException { try (OutputStream o = new FileOutputStream(f)) { o.write(b); } }
}
