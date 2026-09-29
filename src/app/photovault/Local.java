package app.photovault;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * A folder on the phone that the user picked (Android's folder picker; the app gets access to that folder only). The
 * encrypted PNGs go in its subfolder "Cloakroll". Two uses: the vault itself ("Only on this phone"), or a copy of every
 * encrypted file next to a cloud (Transfers). Files are identified by their names, which are random; a name is
 * looked up in the folder once and remembered.
 */
final class Local extends Cloud {
    static final String VAULT = "Cloakroll", TRASH = "Trash";
    private static final String[] COLS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED};

    private final Store st;
    private final ContentResolver cr;
    private final Map<String, String[]> byName = new ConcurrentHashMap<>(); // file name -> {document id, folder's document id}
    private volatile boolean scanned; // byName holds every file of the vault folder (and its subfolders)
    private volatile String home;     // document id of the folder "Cloakroll"

    Local(Store st) { this.st = st; cr = st.app.getContentResolver(); }

    @Override String name() { return st.app.getString(R.string.local_name); }

    @Override String trashName() { return st.app.getString(R.string.trash_local); }

    Uri tree() { String s = st.prefs.getString("local_tree", ""); return s.isEmpty() ? null : Uri.parse(s); }

    /** Keeps the folder the user picked, with access that lasts across restarts. Main thread. */
    void setTree(Uri u) {
        cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Uri old = tree();
        if (old != null && !old.equals(u)) release(old);
        st.prefs.edit().putString("local_tree", u.toString()).commit();
        forget();
    }

    private void release(Uri u) {
        try { cr.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (Exception ignored) { } // already gone
    }

    private synchronized void forget() { byName.clear(); scanned = false; home = null; }

    /** The picked folder is still there and the app may still write to it. */
    @Override boolean hasSession() {
        Uri t = tree();
        if (t == null) return false;
        for (UriPermission p : cr.getPersistedUriPermissions()) if (p.getUri().equals(t) && p.isWritePermission()) return true;
        return false;
    }

    /** Forgets the folder (its files stay where they are). */
    @Override void signOut() {
        Uri t = tree();
        if (t != null) release(t);
        st.prefs.edit().remove("local_tree").commit();
        forget();
    }

    /** Access to the folder was withdrawn (or it was deleted): handled like an expired sign-in. */
    private static ApiError lost() { return new ApiError(401, "no access to the folder on this phone"); }

    private Uri doc(String id) throws ApiError {
        if (!hasSession()) throw lost();
        return DocumentsContract.buildDocumentUriUsingTree(tree(), id);
    }

    /** {document id, name, mime type, last modified} of everything in a folder. */
    private List<String[]> children(String dir) throws Exception {
        if (!hasSession()) throw lost();
        List<String[]> out = new ArrayList<>();
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree(), dir), COLS, null, null, null)) {
            if (c == null) throw new IOException("the folder on this phone can't be read");
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), String.valueOf(c.isNull(3) ? 0 : c.getLong(3))});
        } catch (SecurityException e) { throw lost(); }
        return out;
    }

    private static boolean isDir(String[] ch) { return Document.MIME_TYPE_DIR.equals(ch[2]); }

    /** Only the vault's own files count: finished PNGs (not ".part" files being written, not other files). */
    private static boolean isVaultFile(String[] ch) { return !isDir(ch) && ch[1] != null && ch[1].endsWith(".png"); }

    /**
     * The folder "Cloakroll" inside the picked folder, created if missing. If the picked folder is itself a vault folder
     * (named Cloakroll, or holding the "index" subfolder, e.g. copied from a PC), that one. It gets a ".nomedia" file
     * so gallery apps don't show (or back up) the noise images.
     */
    synchronized String home() throws Exception {
        if (home != null) return home;
        if (!hasSession()) throw lost();
        String root = DocumentsContract.getTreeDocumentId(tree()), h = null, rootName = null;
        try (Cursor c = cr.query(doc(root), new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) rootName = c.getString(0);
        } catch (SecurityException e) { throw lost(); }
        List<String[]> top = children(root);
        if (VAULT.equalsIgnoreCase(rootName)) h = root;
        else for (String[] ch : top) if (isDir(ch) && "index".equals(ch[1])) h = root;
        if (h == null) h = folder(root, VAULT);
        boolean media = true;
        for (String[] ch : h.equals(root) ? top : children(h)) if (".nomedia".equals(ch[1])) media = false;
        if (media) try { DocumentsContract.createDocument(cr, doc(h), "application/octet-stream", ".nomedia"); }
        catch (Exception e) { Journal.add("phone folder: .nomedia not created (" + e.getClass().getSimpleName() + ")"); }
        return home = h;
    }

    @Override String vaultFolder() throws Exception { return home(); }

    @Override String folder(String parentId, String name) throws Exception {
        for (String[] ch : children(parentId)) if (isDir(ch) && name.equals(ch[1])) return ch[0];
        try {
            Uri u = DocumentsContract.createDocument(cr, doc(parentId), Document.MIME_TYPE_DIR, name);
            if (u == null) throw new IOException("can't create a folder on this phone");
            return DocumentsContract.getDocumentId(u);
        } catch (SecurityException e) { throw lost(); }
    }

    @Override Listing listFiles(String folderId, int max) throws Exception {
        Listing res = new Listing();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        res.complete = true;
        for (String[] ch : children(folderId)) {
            if (!isVaultFile(ch)) continue;
            byName.put(ch[1], new String[]{ch[0], folderId});
            if (res.files.size() < max) res.files.add(node(ch[1], ch[1], iso.format(new Date(Long.parseLong(ch[3])))));
            else res.complete = false;
        }
        return res;
    }

    /** A file by name: known from a listing or a write, else found with one look through the vault folder and its subfolders. */
    private String[] find(String name) throws Exception {
        String[] d = byName.get(name);
        if (d != null || scanned) return d;
        synchronized (this) {
            if (!scanned) {
                String h = home();
                for (String[] ch : children(h)) {
                    if (isVaultFile(ch)) byName.put(ch[1], new String[]{ch[0], h});
                    else if (isDir(ch) && !TRASH.equals(ch[1])) for (String[] f : children(ch[0])) if (isVaultFile(f)) byName.put(f[1], new String[]{f[0], ch[0]});
                }
                scanned = true;
            }
        }
        return byName.get(name);
    }

    /**
     * Writes `src` as file `name` in folder `dir`: first as "name.part", renamed when complete, so a copy cut short
     * (app killed, phone off) is never taken for a real file. Returns the name the phone gave it (normally the same).
     */
    private String write(File src, String name, String dir, Progress p) throws Exception {
        Uri u = create(dir, name + ".part", src, p);
        try {
            Uri r = DocumentsContract.renameDocument(cr, u, name);
            if (r == null) throw new IOException("rename failed");
            u = r;
        } catch (Exception e) { // some storage can't rename: written again under the final name
            try { DocumentsContract.deleteDocument(cr, u); } catch (Exception ignored) { }
            Journal.add("phone folder: rename not possible (" + e.getClass().getSimpleName() + "), writing directly");
            u = create(dir, name, src, p);
        }
        String got = name;
        try (Cursor c = cr.query(u, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) got = c.getString(0);
        }
        byName.put(got, new String[]{DocumentsContract.getDocumentId(u), dir});
        return got;
    }

    /** A new file with these bytes; nothing is left behind if writing fails. */
    private Uri create(String dir, String name, File src, Progress p) throws Exception {
        Uri u;
        try { u = DocumentsContract.createDocument(cr, doc(dir), name.endsWith(".png") ? "image/png" : "application/octet-stream", name); }
        catch (SecurityException e) { throw lost(); }
        if (u == null) throw new IOException("can't write to the folder on this phone");
        boolean ok = false;
        try {
            try (InputStream in = new FileInputStream(src); OutputStream o = cr.openOutputStream(u, "w")) {
                if (o == null) throw new IOException("can't write to the folder on this phone");
                copy(in, o, src.length(), p);
            }
            ok = true;
        } finally {
            if (!ok) try { DocumentsContract.deleteDocument(cr, u); } catch (Exception ignored) { }
        }
        return u;
    }

    @Override JSONObject upload(File png, String name, String parentId, Progress p) throws Exception {
        return new JSONObject().put("id", write(png, name, parentId, p)).put("type", "image/png");
    }

    @Override void download(String id, File dst, Progress p) throws Exception {
        if (!get(id, dst, p)) throw new ApiError(404, "file not found in the folder on this phone");
    }

    /** Copies file `name` to `dst`. False if there is no such file. */
    boolean get(String name, File dst, Progress p) throws Exception {
        String[] d = find(name);
        if (d == null) return false;
        InputStream in;
        try { in = cr.openInputStream(doc(d[0])); }
        catch (FileNotFoundException e) { byName.remove(name); return false; } // deleted outside the app
        catch (SecurityException e) { throw lost(); }
        if (in == null) return false;
        save(in, -1, dst, p);
        return true;
    }

    /** Vault on the phone: into its folder "Trash" (deleted instead if this storage can't move files). */
    @Override void trash(List<String> ids) throws Exception {
        String bin = null;
        for (String id : ids) {
            String[] d = find(id);
            if (d == null) throw new ApiError(404, "file not found in the folder on this phone");
            if (bin == null) bin = folder(home(), TRASH);
            Uri moved = null;
            try { moved = DocumentsContract.moveDocument(cr, doc(d[0]), doc(d[1]), doc(bin)); }
            catch (Exception e) { Journal.add("phone folder: move to Trash not possible, deleting instead: " + e.getClass().getSimpleName()); }
            if (moved == null) {
                try { DocumentsContract.deleteDocument(cr, doc(d[0])); }
                catch (FileNotFoundException e) { byName.remove(id); throw new ApiError(404, "file not found in the folder on this phone"); }
            }
            byName.remove(id);
        }
    }

    /** Copy next to a cloud: saved under `name` unless it is already there. */
    void put(File src, String name) throws Exception { if (find(name) == null) write(src, name, home(), null); }

    /** Copies next to a cloud: removed (the cloud keeps its own trash). Missing ones are fine. */
    void delete(List<String> names) throws Exception {
        for (String n : names) {
            String[] d = find(n);
            if (d == null) continue;
            try { DocumentsContract.deleteDocument(cr, doc(d[0])); } catch (FileNotFoundException gone) { }
            byName.remove(n);
        }
    }

    @Override String[] storage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long free = fs.getAvailableBytes();
        return new String[]{free < (1L << 30) ? "warn" : "ok", st.app.getString(R.string.stor_phone, Store.human(free), Store.human(fs.getTotalBytes()))};
    }
}
