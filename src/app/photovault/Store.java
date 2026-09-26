package app.photovault;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
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
    static final long MAX_FILE = 100L << 20; // per item: the whole file is held in memory while encrypting
    static final long AUTO_LOCK_MS = 60_000, PICKER_LOCK_MS = 10 * 60_000;

    private static Store instance;

    static Store get(Context c) { // main thread
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    static final class Item {
        String id, name, mime, folder = ""; // folder "" = not in a folder
        long taken, size;
        boolean video() { return mime != null && mime.startsWith("video/"); }
    }

    /** The local list: items and folder names. A folder is a label on items (one level, like albums). */
    static final class Index {
        final List<Item> items = new ArrayList<>();
        final List<String> folders = new ArrayList<>();

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

    byte[] key;                           // vault key: in memory only while unlocked
    List<Item> items = new ArrayList<>(); // decrypted index: in memory only while unlocked
    List<String> folders = new ArrayList<>();
    String status;                        // last background-job line shown in the gallery
    boolean jobRunning, needLogin, allowScreenshots;
    long backgroundSince;
    Runnable onChange;                    // set by the visible screen

    /** Posted 60 s after the app goes to the background. */
    final Runnable autoLock = new Runnable() { public void run() { lock(); changed(); } };

    private Store(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("vault", Context.MODE_PRIVATE);
        deletePlayback(); // leftovers if the app was killed while a video was open
    }

    /** Decrypted videos are written to disk only for playback; removed on lock, on close and at start. */
    void deletePlayback() {
        File[] fs = app.getCacheDir().listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("play")) f.delete();
    }

    void changed() { if (onChange != null) onChange.run(); }

    void post(Runnable r) { ui.post(r); }

    /** Any thread. */
    void setStatus(final String s, final boolean running) {
        post(new Runnable() { public void run() { status = s; jobRunning = running; changed(); } });
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
        String json = f.exists() ? new String(Vault.open(k, readFile(f)), "UTF-8") : old.exists() ? new String(readFile(old), "UTF-8") : "[]";
        Index ix = new Index();
        JSONArray a, fs = null;
        if (json.trim().startsWith("[")) a = new JSONArray(json); // v1.0 / v1.1: just the items
        else { JSONObject o = new JSONObject(json); a = o.getJSONArray("items"); fs = o.optJSONArray("folders"); }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Item it = new Item();
            it.id = o.getString("id"); it.name = o.optString("name"); it.mime = o.optString("mime");
            it.taken = o.optLong("taken"); it.size = o.optLong("size"); it.folder = o.optString("folder", "");
            ix.items.add(it);
        }
        if (fs != null) for (int i = 0; i < fs.length(); i++) ix.folders.add(fs.getString(i));
        return ix;
    }

    synchronized void writeIndex(byte[] k, Index ix) throws Exception {
        Collections.sort(ix.items, new Comparator<Item>() { public int compare(Item a, Item b) { return Long.compare(b.taken, a.taken); } });
        Collections.sort(ix.folders, String.CASE_INSENSITIVE_ORDER);
        for (Item it : ix.items) if (!it.folder.isEmpty()) it.folder = ix.canonical(it.folder); // one spelling per folder
        JSONArray a = new JSONArray();
        for (Item it : ix.items)
            a.put(new JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("taken", it.taken)
                    .put("size", it.size).put("folder", it.folder));
        String json = new JSONObject().put("items", a).put("folders", new JSONArray(ix.folders)).toString();
        File tmp = new File(app.getFilesDir(), "index.bin.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(Vault.seal(k, json.getBytes("UTF-8"))); o.getFD().sync(); }
        if (!tmp.renameTo(indexFile())) throw new IOException("cannot save the index");
        new File(app.getFilesDir(), "index.json").delete(); // v1.0 plaintext index
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
     * create it again (restore from Amazon) or put the item in the main view (upload).
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

    // ---------------------------------------------------------------- folders, backed up to Amazon (encrypted)

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean backupQueued; // guarded by this

    /** The folders on the phone are newer than the copy on Amazon: Sync must not apply that copy. Call before the change. */
    synchronized void markFoldersDirty() { prefs.edit().putBoolean("folders_dirty", true).commit(); }

    synchronized boolean foldersDirty() { return prefs.getBoolean("folders_dirty", false); }

    /** Amazon subfolder PhotoVault/index: holds one encrypted PNG with the folder organisation. */
    String indexFolder() throws Exception {
        String pref = "index_folder_" + folder(), id = prefs.getString(pref, "");
        if (id.isEmpty()) { id = Amazon.folder(folder(), "index"); prefs.edit().putString(pref, id).apply(); }
        return id;
    }

    /**
     * Saves the folders (names and which item is in which) to Amazon as one more encrypted PNG, so a new phone
     * gets them back with Sync. Any thread; runs in the background; calls made while one is waiting merge into it.
     */
    void backupFolders(byte[] jobKey) {
        final byte[] k = jobKey.clone();
        synchronized (this) {
            markFoldersDirty();
            if (prefs.getBoolean("restore_pending", false)) { // the list may still be partial: upload after the restore
                Journal.add("folders will be saved to Amazon when the restore from Amazon has finished");
                Arrays.fill(k, (byte) 0);
                return;
            }
            if (backupQueued) { Arrays.fill(k, (byte) 0); return; }
            backupQueued = true;
        }
        bg.execute(new Runnable() { public void run() {
            synchronized (Store.this) { backupQueued = false; }
            File png = new File(app.getCacheDir(), "folders-up.png");
            try {
                Index ix = readIndex(k);
                JSONObject map = new JSONObject();
                for (Item it : ix.items) if (!it.folder.isEmpty()) map.put(it.id, it.folder);
                byte[] data = new JSONObject().put("folders", new JSONArray(ix.folders)).put("map", map).toString().getBytes("UTF-8");
                String meta = new JSONObject().put("name", "folders.json").put("taken", System.currentTimeMillis()).put("mime", "application/json").toString();
                byte[] plain = Vault.plainBuffer(meta, data.length);
                System.arraycopy(data, 0, plain, plain.length - data.length, data.length);
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(png))) { Vault.encryptToPng(k, salt(), plain, o); }
                String dir = indexFolder();
                String id = Amazon.upload(png, hex(Vault.random(8)) + ".png", dir, null).getString("id");
                List<String> old = new ArrayList<>();
                for (JSONObject n : Amazon.listFiles(dir, 1000).files) if (!n.getString("id").equals(id)) old.add(n.getString("id"));
                if (!old.isEmpty()) Amazon.trash(old);
                synchronized (Store.this) { if (!backupQueued) prefs.edit().putBoolean("folders_dirty", false).commit(); }
                Journal.add("folders saved to Amazon (encrypted)");
            } catch (Exception e) {
                Journal.add("folders not saved to Amazon yet (retried on the next change): " + e);
            } finally { png.delete(); Arrays.fill(k, (byte) 0); }
        }});
    }

    /** The folder organisation saved on Amazon: {"folders":[...], "map":{itemId: folder}}, or null. Background thread. */
    JSONObject readFoldersBackup(byte[] k) throws Exception {
        List<JSONObject> l = Amazon.listFiles(indexFolder(), 1000).files;
        if (l.isEmpty()) return null;
        JSONObject newest = l.get(0);
        for (JSONObject n : l) if (n.optString("createdDate").compareTo(newest.optString("createdDate")) > 0) newest = n;
        File f = new File(app.getCacheDir(), "folders-down.png");
        try {
            Amazon.download(newest.getString("id"), owner(), f, null);
            Vault.Opened o;
            try (InputStream in = new FileInputStream(f)) { o = Vault.decryptPng(in, k); }
            return new JSONObject(new String(o.plain, o.dataOff, o.dataLen(), "UTF-8"));
        } finally { f.delete(); }
    }

    // ---------------------------------------------------------------- previews

    /** Small JPEG preview, stored only on the phone and encrypted with the vault key. */
    static byte[] makeThumb(final byte[] b, final int off, final int len, boolean video) {
        try {
            Bitmap bm;
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
                Bitmap f;
                try { f = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480); }
                finally { r.release(); }
                if (f == null) return null;
                bm = f.copy(Bitmap.Config.ARGB_8888, true);
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
            } else {
                bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b, off, len).slice()), new ImageDecoder.OnHeaderDecodedListener() {
                    public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                        Size s = info.getSize();
                        d.setTargetSampleSize(Math.max(1, Math.min(s.getWidth(), s.getHeight()) / 480));
                        d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 82, o);
            return o.toByteArray();
        } catch (Throwable e) { Journal.add("preview not created: " + e); return null; }
    }

    /** Decrypts a stored preview, or null. Any thread; the caller caches it on the main thread while unlocked. */
    Bitmap thumb(String id, byte[] k) {
        try {
            File f = thumbFile(id);
            if (!f.exists()) return null;
            byte[] j = Vault.open(k, readFile(f));
            return BitmapFactory.decodeByteArray(j, 0, j.length);
        } catch (Exception e) { Journal.add("preview unreadable: " + e); return null; }
    }

    // ---------------------------------------------------------------- small helpers

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    static String explain(Throwable e) {
        if (isAuth(e)) return "Amazon session expired or not accepted: sign in again";
        if (e instanceof AEADBadTagException) return "decryption check failed: wrong key, or the file was altered";
        if (e instanceof java.net.UnknownHostException) return "no internet connection";
        if (e instanceof OutOfMemoryError) return "file too big for this phone's app memory";
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static boolean isAuth(Throwable e) { return e instanceof Amazon.ApiError && ((Amazon.ApiError) e).isAuth(); }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] readFile(File f) throws IOException { try (InputStream in = new FileInputStream(f)) { return Amazon.readAll(in); } }

    static void writeFile(File f, byte[] b) throws IOException { try (OutputStream o = new FileOutputStream(f)) { o.write(b); } }
}
