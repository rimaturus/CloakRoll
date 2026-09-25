package app.photovault;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.*;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static byte[] key;                       // vault key: only in RAM, only while unlocked
    static long backgroundSince;
    static final long MAX_FILE = 100L << 20; // v1 limit per item (whole file is held in RAM)
    static final String VERIFY = "PhotoVault password check";
    static final String BIO_ALIAS = "photovault_fingerprint";
    static final int REQ_PICK = 1, OK = 0xFF2E7D32, BAD = 0xFFC62828, WARN = 0xFFE65100;

    SharedPreferences prefs;
    final ExecutorService io = Executors.newSingleThreadExecutor();     // uploads, sync, setup
    final ExecutorService viewIo = Executors.newSingleThreadExecutor(); // viewer (not stuck behind uploads)
    final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    final Handler ui = new Handler(Looper.getMainLooper());
    final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(48 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    final ArrayList<Item> items = new ArrayList<>();
    String screen = "";
    boolean busy, picking, connecting;
    long nextLoginTry;
    WebView web;
    TextView status, loginStatus;
    BaseAdapter adapter;
    Vault.Opened shown;
    File playing;

    static final class Item {
        String id, name, mime;
        long taken, size;
        boolean video() { return mime != null && mime.startsWith("video/"); }
    }

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // no screenshots, blank in "recent apps"
        prefs = getSharedPreferences("vault", MODE_PRIVATE);
        CookieManager.getInstance(); // initialise on the UI thread before background use
        loadIndex();
        start();
    }

    void start() {
        if (!prefs.contains("verifier")) showIntro();
        else if (key == null) showUnlock();
        else showGallery();
    }

    @Override protected void onStop() { super.onStop(); backgroundSince = SystemClock.elapsedRealtime(); }

    @Override protected void onStart() {
        super.onStart();
        // auto-lock after 60 s in background (not while picking photos or while an upload runs)
        if (key != null && backgroundSince > 0 && !picking && !busy && SystemClock.elapsedRealtime() - backgroundSince > 60_000) lock();
        backgroundSince = 0;
    }

    void lock() {
        key = null;
        shown = null;
        thumbs.evictAll();
        deletePlaying();
        showUnlock();
    }

    @Override public void onBackPressed() {
        switch (screen) {
            case "viewer": closeViewer(); showGallery(); break;
            case "info": showGallery(); break;
            case "log": if (key != null && prefs.contains("verifier")) showGallery(); else start(); break;
            case "login":
                if (web != null && web.canGoBack()) web.goBack();
                else if (prefs.contains("verifier")) start(); else showIntro();
                break;
            case "selftest": if (!busy) { if (key != null) showGallery(); else start(); } break;
            case "create": case "restore": showIntro(); break;
            default: super.onBackPressed();
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        if ("gallery".equals(screen)) {
            m.add(0, 1, 0, "＋ Add").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            m.add(0, 2, 0, "Sync from Amazon");
            m.add(0, 3, 0, "Info & security");
            m.add(0, 5, 0, "Lock now");
        }
        m.add(0, 4, 0, "Log");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case 1: pick(); return true;
            case 2: sync(); return true;
            case 3: showInfo(); return true;
            case 4: showLog(); return true;
            case 5: lock(); return true;
        }
        return super.onOptionsItemSelected(i);
    }

    // ================================================================ UI helpers

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(12), dp(20), dp(24));
        return l;
    }

    TextView text(LinearLayout p, String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(6), 0, dp(6));
        t.setLineSpacing(0, 1.15f);
        if (sp >= 17) t.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(t);
        return t;
    }

    Button button(LinearLayout p, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        p.addView(b, lp);
        return b;
    }

    EditText password(LinearLayout p, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSingleLine();
        p.addView(e);
        return e;
    }

    ScrollView scroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    void setScreen(String name, String title, String subtitle, View content) {
        if (web != null && !"login".equals(name)) {
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        if (!"gallery".equals(name)) status = null;
        screen = name;
        ActionBar ab = getActionBar();
        if (ab != null) { ab.setTitle(title); ab.setSubtitle(subtitle); }
        setContentView(content);
        invalidateOptionsMenu();
    }

    void onUi(Runnable r) { ui.post(r); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void setBusy(final boolean b) {
        busy = b;
        onUi(new Runnable() { public void run() {
            if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }});
    }

    void setStatus(final String s) {
        onUi(new Runnable() { public void run() {
            if (status == null) return;
            status.setText(s);
            status.setVisibility(s == null ? View.GONE : View.VISIBLE);
        }});
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    static String date(long ms) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms)); }

    static String explain(Throwable e) {
        if (e instanceof Amazon.ApiError && ((Amazon.ApiError) e).isAuth()) return "Amazon session expired or not accepted: sign in again";
        if (e instanceof AEADBadTagException) return "decryption check failed: wrong key, or the file was altered";
        if (e instanceof java.net.UnknownHostException) return "no internet connection";
        if (e instanceof OutOfMemoryError) return "file too big for this phone's app memory";
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static boolean isAuth(Throwable e) { return e instanceof Amazon.ApiError && ((Amazon.ApiError) e).isAuth(); }

    void askRelogin() {
        onUi(new Runnable() { public void run() {
            new AlertDialog.Builder(MainActivity.this).setTitle("Amazon sign-in needed")
                    .setMessage("Amazon ended the session (this happens every few weeks). Sign in again; your vault and password are not affected.")
                    .setPositiveButton("Sign in", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { showLogin(); } })
                    .setNegativeButton("Later", null).show();
        }});
    }

    byte[] salt() { return hex(prefs.getString("salt", "")); }
    String folder() { return prefs.getString("folder", ""); }
    String owner() { return prefs.getString("owner", ""); }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] readFile(File f) throws IOException { try (InputStream in = new FileInputStream(f)) { return Amazon.readAll(in); } }

    static void writeFile(File f, byte[] b) throws IOException { try (OutputStream o = new FileOutputStream(f)) { o.write(b); } }

    File thumbFile(String id) { File d = new File(getFilesDir(), "thumbs"); d.mkdirs(); return new File(d, id); }

    File blobFile(String id) { File d = new File(getCacheDir(), "blobs"); d.mkdirs(); return new File(d, id + ".png"); }

    /** Keeps at most ~500 MB of downloaded encrypted PNGs as a speed-up cache. */
    void trimBlobCache() {
        File[] fs = new File(getCacheDir(), "blobs").listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
        long sum = 0;
        for (File f : fs) { sum += f.length(); if (sum > 500L << 20) f.delete(); }
    }

    // ================================================================ local index (file list, private app storage)

    void loadIndex() {
        items.clear();
        try {
            File f = new File(getFilesDir(), "index.json");
            if (!f.exists()) return;
            JSONArray a = new JSONArray(new String(readFile(f), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id"); it.name = o.optString("name"); it.mime = o.optString("mime");
                it.taken = o.optLong("taken"); it.size = o.optLong("size");
                items.add(it);
            }
        } catch (Exception e) { Journal.add("index load failed: " + e); }
    }

    /** Call on UI thread. */
    void saveIndex() {
        Collections.sort(items, new Comparator<Item>() { public int compare(Item a, Item b) { return Long.compare(b.taken, a.taken); } });
        try {
            JSONArray a = new JSONArray();
            for (Item it : items)
                a.put(new JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("taken", it.taken).put("size", it.size));
            File tmp = new File(getFilesDir(), "index.json.tmp");
            writeFile(tmp, a.toString().getBytes("UTF-8"));
            tmp.renameTo(new File(getFilesDir(), "index.json"));
        } catch (Exception e) { Journal.add("index save failed: " + e); }
        if (adapter != null) adapter.notifyDataSetChanged();
        if ("gallery".equals(screen) && getActionBar() != null) getActionBar().setSubtitle(subtitle());
    }

    void addItem(final Item it) {
        onUi(new Runnable() { public void run() {
            for (Item x : items) if (x.id.equals(it.id)) return;
            items.add(it);
            saveIndex();
        }});
    }

    String subtitle() { return items.size() + (items.size() == 1 ? " item" : " items") + " · encrypted on Amazon"; }

    // ================================================================ setup: intro

    void showIntro() {
        LinearLayout l = vbox();
        text(l, "Your photos, encrypted before they leave this phone", 22);
        text(l, "How it works", 17);
        text(l, "1. PhotoVault encrypts each photo or video here, on your phone (AES-256).\n"
                + "2. The encrypted bytes are packed into a PNG image that looks like TV static.\n"
                + "3. Only that PNG is uploaded to your Amazon Photos, where Prime gives unlimited photo storage.\n"
                + "4. To view, the app downloads the PNG and decrypts it on the phone.", 15);
        text(l, "What Amazon can see", 17);
        text(l, "• That you store PNG files of random noise: how many, their size and when they were uploaded.\n"
                + "• Nothing of the content: no faces, places, dates, EXIF or file names. There is nothing to scan or to train AI on.", 15);
        text(l, "What you need to know", 17);
        text(l, "• Your vault password is the only key. Nobody can reset it, not Amazon and not this app. If you lose it, the photos are gone.\n"
                + "• The app uses the same private web interface as the amazon.it website (Amazon has no public API). If Amazon changes it, "
                + "uploads can stop working until the app is updated. Files already stored stay decryptable, also on a PC with photovault.py.\n"
                + "• Videos stored as photo files go against the spirit of Amazon's terms (Prime includes only 5 GB for video). "
                + "Amazon could restrict the account. Keep a second backup of anything irreplaceable.", 15);
        text(l, "Setup takes 3 steps: sign in to Amazon, create your vault password, and a self-test on your account.", 15);
        button(l, "Continue: sign in to Amazon Photos", new View.OnClickListener() { public void onClick(View v) { showLogin(); } });
        setScreen("intro", "PhotoVault", "Setup", scroll(l));
    }

    // ================================================================ setup 1: Amazon login in a WebView

    void showLogin() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setPadding(dp(16), dp(8), dp(16), dp(4));
        head.setText("Sign in to amazon.it as usual. PhotoVault doesn't read or store your Amazon password: "
                + "it reuses the login session this page creates, like a browser tab.");
        l.addView(head);
        loginStatus = new TextView(this);
        loginStatus.setPadding(dp(16), 0, dp(16), dp(8));
        loginStatus.setTypeface(Typeface.DEFAULT_BOLD);
        loginStatus.setText("Waiting for sign-in…");
        l.addView(loginStatus);
        setScreen("login", "Sign in to Amazon", prefs.contains("verifier") ? "Refresh session" : "Step 1 of 3", l);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Amazon.UA); // desktop site: the one the API calls belong to
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { checkLogin(); }
        });
        l.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.loadUrl(Amazon.WEB + "/photos");
        connecting = false;
        nextLoginTry = 0;
        ui.postDelayed(pollLogin, 2000);
    }

    final Runnable pollLogin = new Runnable() {
        public void run() { if ("login".equals(screen)) { checkLogin(); ui.postDelayed(this, 2000); } }
    };

    void checkLogin() {
        if (connecting || SystemClock.elapsedRealtime() < nextLoginTry || !Amazon.hasSession()) return;
        connecting = true;
        CookieManager.getInstance().flush();
        loginStatus.setText("Signed in. Checking access to Amazon Photos…");
        io.execute(new Runnable() { public void run() {
            try {
                JSONObject root = Amazon.root();
                String folder = Amazon.folder(root.getString("id"), "PhotoVault");
                final List<JSONObject> first = Amazon.listFiles(folder, 1);
                prefs.edit().putString("owner", root.optString("ownerId")).putString("folder", folder).apply();
                Journal.add("connected: Amazon Photos folder PhotoVault ready");
                onUi(new Runnable() { public void run() {
                    connecting = false;
                    if (!"login".equals(screen)) return;
                    if (prefs.contains("verifier")) { toast("Amazon session refreshed"); if (key == null) showUnlock(); else showGallery(); }
                    else if (first.isEmpty()) showCreatePassword();
                    else showRestorePassword(first.get(0).optString("id"));
                }});
            } catch (final Exception e) {
                Journal.add("connect failed: " + e);
                onUi(new Runnable() { public void run() {
                    connecting = false;
                    nextLoginTry = SystemClock.elapsedRealtime() + 15_000;
                    if (loginStatus != null) loginStatus.setText("Signed in, but Amazon Photos didn't accept the session yet ("
                            + explain(e) + "). Let the Photos page finish loading; I retry every 15 s. Menu → Log for details.");
                }});
            }
        }});
    }

    // ================================================================ setup 2: vault password

    void showCreatePassword() {
        LinearLayout l = vbox();
        text(l, "Create your vault password", 20);
        text(l, "This password encrypts everything. It never leaves this phone and it cannot be reset or recovered.\n"
                + "Use at least 10 characters. A few random words is ideal; a password manager is the best place to keep it.", 15);
        final EditText p1 = password(l, "Vault password"), p2 = password(l, "Repeat password");
        final CheckBox ok = new CheckBox(this);
        ok.setText("I saved this password somewhere safe. I understand that without it my photos can never be recovered.");
        l.addView(ok);
        final TextView err = text(l, "", 15);
        err.setTextColor(BAD);
        button(l, "Create vault", new View.OnClickListener() { public void onClick(final View btn) {
            final String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < 10) { err.setText("Use at least 10 characters."); return; }
            if (!a.equals(b)) { err.setText("The two passwords don't match."); return; }
            if (!ok.isChecked()) { err.setText("Please confirm you saved the password."); return; }
            btn.setEnabled(false);
            err.setTextColor(p1.getCurrentTextColor());
            err.setText("Deriving your key… (takes a few seconds on purpose: it makes password guessing slow)");
            io.execute(new Runnable() { public void run() {
                try {
                    byte[] salt = Vault.random(16), k = Vault.deriveKey(a, salt);
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    key = k;
                    Journal.add("new vault created");
                    onUi(new Runnable() { public void run() { showSelfTest(); } });
                } catch (final Exception e) {
                    onUi(new Runnable() { public void run() { btn.setEnabled(true); err.setTextColor(BAD); err.setText(explain(e)); } });
                }
            }});
        }});
        setScreen("create", "New vault", "Step 2 of 3", scroll(l));
    }

    void showRestorePassword(final String sampleId) {
        LinearLayout l = vbox();
        text(l, "Unlock your existing vault", 20);
        text(l, "This Amazon account already has a PhotoVault folder with encrypted files. Enter the vault password you created it with.", 15);
        final EditText p = password(l, "Vault password");
        final TextView err = text(l, "", 15);
        button(l, "Unlock vault", new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setText("Downloading one file and deriving the key…");
            io.execute(new Runnable() { public void run() {
                File f = new File(getCacheDir(), "sample.png");
                try {
                    Amazon.download(sampleId, owner(), f, null);
                    byte[] salt;
                    try (InputStream in = new FileInputStream(f)) { salt = Vault.readSalt(in); }
                    byte[] k = Vault.deriveKey(pw, salt);
                    try (InputStream in = new FileInputStream(f)) { Vault.decryptPng(in, k); }
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    key = k;
                    Journal.add("existing vault unlocked");
                    onUi(new Runnable() { public void run() { showSelfTest(); } });
                } catch (final Throwable e) {
                    onUi(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setText(e instanceof AEADBadTagException ? "Wrong password." : explain(e));
                    }});
                } finally { f.delete(); }
            }});
        }});
        setScreen("restore", "Existing vault", "Step 2 of 3", scroll(l));
    }

    // ================================================================ setup 3: self-test on the real account

    void showSelfTest() {
        LinearLayout l = vbox();
        text(l, "Self-test on your Amazon account", 20);
        text(l, "Before you trust it with real photos, PhotoVault runs the whole chain once with a test image and removes it afterwards.", 15);
        LinearLayout pics = new LinearLayout(this);
        ImageView mine = new ImageView(this), theirs = new ImageView(this);
        String[] caps = {"What you see", "What Amazon stores"};
        ImageView[] ivs = {mine, theirs};
        for (int i = 0; i < 2; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(caps[i]);
            t.setGravity(Gravity.CENTER);
            c.addView(t);
            ivs[i].setScaleType(ImageView.ScaleType.FIT_CENTER);
            ivs[i].setBackgroundColor(0x22888888);
            c.addView(ivs[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(6), dp(8), i == 0 ? dp(6) : 0, dp(8));
            pics.addView(c, lp);
        }
        l.addView(pics);
        String[] labels = {
                "Encrypt a test image on this phone",
                "Upload the encrypted PNG to Amazon Photos",
                "Amazon files it as a photo",
                "Download it back",
                "Amazon kept every byte (SHA-256 identical)",
                "Decrypt: identical to the original",
                "Photos don't use your storage quota",
                "Remove the test file from Amazon"};
        TextView[] steps = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) steps[i] = text(l, "○  " + labels[i], 15);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        l.addView(actions);
        setScreen("selftest", "Self-test", prefs.contains("done_selftest") ? null : "Step 3 of 3", scroll(l));
        runSelfTest(labels, steps, mine, theirs, actions);
    }

    void step(final TextView t, final String label, final int state, final String detail) {
        onUi(new Runnable() { public void run() {
            t.setText((state == OK ? "✓  " : state == BAD ? "✗  " : "⚠  ") + label + (detail == null ? "" : "\n     " + detail));
            t.setTextColor(state);
        }});
    }

    void runSelfTest(final String[] labels, final TextView[] s, final ImageView mine, final ImageView theirs, final LinearLayout actions) {
        final byte[] k = key, salt = salt();
        setBusy(true);
        io.execute(new Runnable() { public void run() {
            int i = 0;
            String nodeId = null;
            boolean ok = true;
            File up = new File(getCacheDir(), "selftest-up.png"), down = new File(getCacheDir(), "selftest-down.png");
            try {
                long t = SystemClock.elapsedRealtime();
                final Bitmap img = testImage();
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                img.compress(Bitmap.CompressFormat.JPEG, 90, jpg);
                byte[] orig = jpg.toByteArray();
                String meta = new JSONObject().put("name", "selftest.jpg").put("taken", System.currentTimeMillis()).put("mime", "image/jpeg").toString();
                byte[] plain = Vault.plainBuffer(meta, orig.length);
                System.arraycopy(orig, 0, plain, plain.length - orig.length, orig.length);
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(up))) { Vault.encryptToPng(k, salt, plain, o); }
                onUi(new Runnable() { public void run() { mine.setImageBitmap(img); } });
                step(s[i], labels[i++], OK, human(orig.length) + " image → " + human(up.length()) + " PNG, " + (SystemClock.elapsedRealtime() - t) + " ms");

                t = SystemClock.elapsedRealtime();
                JSONObject node = Amazon.upload(up, hex(Vault.random(8)) + ".png", folder(), null);
                nodeId = node.getString("id");
                step(s[i], labels[i++], OK, (SystemClock.elapsedRealtime() - t) + " ms");

                JSONObject cp = node.optJSONObject("contentProperties");
                String type = cp == null ? "" : cp.optString("contentType");
                if (type.startsWith("image/")) step(s[i], labels[i++], OK, "type: " + type);
                else step(s[i], labels[i++], WARN, "Amazon reports '" + type + "': check under Storage that it counts as a photo");

                t = SystemClock.elapsedRealtime();
                Amazon.download(nodeId, owner(), down, null);
                step(s[i], labels[i++], OK, human(down.length()) + ", " + (SystemClock.elapsedRealtime() - t) + " ms");

                byte[] a = readFile(up), b = readFile(down);
                String h1 = Vault.sha256(a, 0, a.length), h2 = Vault.sha256(b, 0, b.length);
                if (!h1.equals(h2)) { step(s[i], labels[i], BAD, "Amazon returned a different file: it recompresses, so this can't work"); throw new IOException("file altered by Amazon"); }
                step(s[i], labels[i++], OK, h1.substring(0, 16) + "…");

                Vault.Opened o;
                try (InputStream in = new FileInputStream(down)) { o = Vault.decryptPng(in, k); }
                boolean same = Vault.sha256(o.plain, o.dataOff, o.dataLen()).equals(Vault.sha256(orig, 0, orig.length));
                if (!same) { step(s[i], labels[i], BAD, "mismatch"); throw new IOException("decrypted data differs"); }
                final Bitmap noise = BitmapFactory.decodeFile(down.getPath());
                onUi(new Runnable() { public void run() { theirs.setImageBitmap(noise); } });
                step(s[i], labels[i++], OK, "authenticated by AES-GCM, SHA-256 match");

                try {
                    JSONObject u = Amazon.usage(), ph = u.optJSONObject("photo");
                    if (ph == null) step(s[i], labels[i++], WARN, "not reported (" + u.names() + ")");
                    else {
                        long bill = ph.optJSONObject("billable") == null ? -1 : ph.getJSONObject("billable").optLong("bytes", -1);
                        long total = ph.optJSONObject("total") == null ? -1 : ph.getJSONObject("total").optLong("bytes", -1);
                        if (bill == 0) step(s[i], labels[i++], OK, "photos stored: " + human(total) + ", billed: 0 B (Prime unlimited active)");
                        else step(s[i], labels[i++], WARN, "photos billed: " + human(bill) + " of " + human(total) + ". Prime unlimited photos may not be active on this account");
                    }
                } catch (Exception e) { step(s[i], labels[i++], WARN, "could not read usage: " + explain(e)); }

                Amazon.trash(Collections.singletonList(nodeId));
                nodeId = null;
                step(s[i], labels[i++], OK, "moved to the Amazon Photos trash");
                prefs.edit().putBoolean("done_selftest", true).apply();
            } catch (final Throwable e) {
                ok = false;
                Journal.add("self-test failed: " + e);
                if (i < s.length) step(s[i], labels[i], BAD, explain(e));
                if (nodeId != null) try { Amazon.trash(Collections.singletonList(nodeId)); } catch (Exception ignored) { }
            } finally {
                up.delete();
                down.delete();
                setBusy(false);
            }
            final boolean fok = ok;
            onUi(new Runnable() { public void run() {
                if (fok) {
                    text(actions, "All checks passed. Amazon stores your encrypted files bit-for-bit and only this app can open them.", 15).setTextColor(OK);
                    if (bioAvailable() && !bioEnabled())
                        button(actions, "Enable fingerprint unlock (recommended)", new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
                    button(actions, "Open my vault", new View.OnClickListener() { public void onClick(View v) { showGallery(); } });
                } else {
                    text(actions, "The test stopped at the step marked ✗. Nothing personal was uploaded.", 15).setTextColor(BAD);
                    button(actions, "Retry", new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
                    button(actions, "Sign in to Amazon again", new View.OnClickListener() { public void onClick(View v) { showLogin(); } });
                    button(actions, "Show log (to report the problem)", new View.OnClickListener() { public void onClick(View v) { showLog(); } });
                }
            }});
        }});
    }

    Bitmap testImage() {
        Bitmap b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 640, 400, 0xFF1565C0, 0xFF00ACC1, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 640, 400, p);
        p.setShader(null);
        p.setColor(0x55FFFFFF);
        c.drawCircle(520, 90, 60, p);
        c.drawCircle(560, 330, 110, p);
        p.setColor(Color.WHITE);
        p.setTextSize(44);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("PhotoVault self-test", 40, 190, p);
        p.setTextSize(28);
        p.setTypeface(Typeface.DEFAULT);
        c.drawText(date(System.currentTimeMillis()), 40, 240, p);
        return b;
    }

    // ================================================================ unlock (password or fingerprint)

    void showUnlock() {
        LinearLayout l = vbox();
        text(l, "Vault locked", 22);
        text(l, "Photos are decrypted only on this phone, only after you unlock.", 15);
        if (bioEnabled()) button(l, "Unlock with fingerprint", new View.OnClickListener() { public void onClick(View v) { bioUnlock(); } });
        final EditText p = password(l, "Vault password");
        final TextView err = text(l, "", 15);
        button(l, "Unlock with password", new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setText("Deriving your key…");
            io.execute(new Runnable() { public void run() {
                boolean good;
                byte[] k = null;
                try {
                    k = Vault.deriveKey(pw, salt());
                    good = Arrays.equals(Vault.open(k, Base64.decode(prefs.getString("verifier", ""), Base64.NO_WRAP)), VERIFY.getBytes("UTF-8"));
                } catch (Exception e) { good = false; }
                final boolean g = good;
                final byte[] fk = k;
                onUi(new Runnable() { public void run() {
                    if (g) { key = fk; showGallery(); }
                    else { btn.setEnabled(true); err.setText("Wrong password."); }
                }});
            }});
        }});
        setScreen("unlock", "PhotoVault", "Locked", scroll(l));
        if (bioEnabled()) ui.postDelayed(new Runnable() { public void run() { if ("unlock".equals(screen)) bioUnlock(); } }, 400);
    }

    boolean bioAvailable() {
        BiometricManager m = getSystemService(BiometricManager.class);
        return m != null && m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    boolean bioEnabled() { return prefs.contains("bio_ct"); }

    interface CipherDone { void run(Cipher c) throws Exception; }

    void bioPrompt(String title, Cipher c, final CipherDone done) {
        new BiometricPrompt.Builder(this).setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", getMainExecutor(), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { } })
                .build()
                .authenticate(new BiometricPrompt.CryptoObject(c), new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        try { done.run(r.getCryptoObject().getCipher()); } catch (Exception e) { toast("Fingerprint: " + explain(e)); }
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                && code != BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON) toast(String.valueOf(msg));
                    }
                });
    }

    /** Wraps the vault key with a hardware-backed Keystore key that only a fingerprint can unlock. */
    void enableBio() {
        try {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true).build());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, g.generateKey());
            bioPrompt("Enable fingerprint unlock", c, new CipherDone() { public void run(Cipher c) throws Exception {
                byte[] ct = c.doFinal(key);
                prefs.edit().putString("bio_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                toast("Fingerprint unlock enabled");
                if ("info".equals(screen)) showInfo(); else showGallery();
            }});
        } catch (Exception e) { toast("Could not enable fingerprint: " + explain(e)); }
    }

    void disableBio() {
        prefs.edit().remove("bio_iv").remove("bio_ct").apply();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(BIO_ALIAS); } catch (Exception ignored) { }
    }

    void bioUnlock() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey k = (SecretKey) ks.getKey(BIO_ALIAS, null);
            if (k == null) throw new KeyPermanentlyInvalidatedException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(prefs.getString("bio_iv", ""), Base64.NO_WRAP)));
            bioPrompt("Unlock PhotoVault", c, new CipherDone() { public void run(Cipher c) throws Exception {
                key = c.doFinal(Base64.decode(prefs.getString("bio_ct", ""), Base64.NO_WRAP));
                showGallery();
            }});
        } catch (KeyPermanentlyInvalidatedException e) {
            disableBio();
            toast("Your fingerprints changed, so fingerprint unlock was reset. Use your password, then enable it again in Info.");
            showUnlock();
        } catch (Exception e) { toast("Fingerprint: " + explain(e)); }
    }

    // ================================================================ gallery

    void showGallery() {
        if (key == null) { showUnlock(); return; }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setVisibility(View.GONE);
        l.addView(status);
        TextView empty = new TextView(this);
        empty.setPadding(dp(24), dp(32), dp(24), dp(24));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setGravity(Gravity.CENTER);
        empty.setText("Your vault is empty.\n\nTap ＋ Add to encrypt and upload photos or videos.\n\n"
                + "Already have a vault on Amazon (new phone, reinstall)? Menu → Sync from Amazon.");
        l.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final int cols = 3, cell = getResources().getDisplayMetrics().widthPixels / cols;
        GridView g = new GridView(this);
        g.setNumColumns(cols);
        g.setHorizontalSpacing(dp(2));
        g.setVerticalSpacing(dp(2));
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int p) { return items.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) {
                ImageView iv = (ImageView) convert;
                if (iv == null) {
                    iv = new ImageView(MainActivity.this);
                    iv.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundColor(0x22888888);
                }
                Item it = items.get(p);
                iv.setTag(it.id);
                Bitmap b = thumbs.get(it.id);
                iv.setImageBitmap(b);
                if (b == null) loadThumb(it.id, iv);
                return iv;
            }
        };
        g.setAdapter(adapter);
        g.setEmptyView(empty);
        g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { showViewer(items.get(p)); }
        });
        l.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setScreen("gallery", "PhotoVault", subtitle(), l);
        if (busy) setStatus("Working… (keep the app open)");
    }

    void loadThumb(final String id, final ImageView iv) {
        final byte[] k = key;
        if (k == null) return;
        thumbIo.execute(new Runnable() { public void run() {
            try {
                File f = thumbFile(id);
                if (!f.exists()) return;
                byte[] j = Vault.open(k, readFile(f));
                final Bitmap b = BitmapFactory.decodeByteArray(j, 0, j.length);
                if (b == null) return;
                thumbs.put(id, b);
                onUi(new Runnable() { public void run() { if (id.equals(iv.getTag())) iv.setImageBitmap(b); } });
            } catch (Exception e) { Journal.add("thumbnail unreadable: " + e); }
        }});
    }

    void pick() {
        if (busy) { toast("Wait for the current operation to finish."); return; }
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES); // Android photo picker: no storage permission needed
        i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        picking = true;
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        picking = false;
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        if (key == null) { toast("Vault is locked."); return; }
        List<Uri> uris = new ArrayList<>();
        ClipData c = data.getClipData();
        if (c != null) for (int i = 0; i < c.getItemCount(); i++) uris.add(c.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (!uris.isEmpty()) upload(uris);
    }

    void upload(final List<Uri> uris) {
        final byte[] k = key, salt = salt();
        final String folder = folder();
        setBusy(true);
        io.execute(new Runnable() { public void run() {
            int ok = 0;
            final List<String> failed = new ArrayList<>();
            for (int n = 0; n < uris.size(); n++) {
                Uri u = uris.get(n);
                final String pre = (n + 1) + "/" + uris.size() + " · ";
                String name = "item";
                File png = new File(getCacheDir(), "upload.png");
                try {
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
                    String mime = getContentResolver().getType(u);
                    if (mime == null) mime = "application/octet-stream";
                    if (size > MAX_FILE) throw new IOException("larger than " + human(MAX_FILE) + " (not supported in this version)");
                    setStatus(pre + "Encrypting " + name + "…");
                    String meta = new JSONObject().put("name", name).put("taken", taken).put("mime", mime).toString();
                    byte[] plain;
                    int off;
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (size > 0) {
                            plain = Vault.plainBuffer(meta, (int) size);
                            off = plain.length - (int) size;
                            for (int p = off, r; p < plain.length; p += r)
                                if ((r = in.read(plain, p, plain.length - p)) < 0) throw new EOFException("file shorter than expected");
                        } else {
                            byte[] all = Amazon.readAll(in);
                            if (all.length > MAX_FILE) throw new IOException("larger than " + human(MAX_FILE));
                            plain = Vault.plainBuffer(meta, all.length);
                            off = plain.length - all.length;
                            System.arraycopy(all, 0, plain, off, all.length);
                            size = all.length;
                        }
                    }
                    byte[] thumb = makeThumb(plain, off, plain.length - off, mime.startsWith("video/"));
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(png), 1 << 16)) { Vault.encryptToPng(k, salt, plain, o); }
                    plain = null;
                    final String fname = name;
                    final long total = png.length();
                    setStatus(pre + "Uploading " + name + " (" + human(total) + ")…");
                    JSONObject node = Amazon.upload(png, hex(Vault.random(8)) + ".png", folder, new Amazon.Progress() {
                        public void on(long done, long t) { setStatus(pre + "Uploading " + fname + " " + (100 * done / Math.max(1, total)) + "%"); }
                    });
                    Item it = new Item();
                    it.id = node.getString("id"); it.name = name; it.mime = mime; it.taken = taken; it.size = size;
                    if (thumb != null) writeFile(thumbFile(it.id), Vault.seal(k, thumb));
                    addItem(it);
                    ok++;
                } catch (Throwable e) {
                    Journal.add("upload failed: " + e);
                    failed.add(name + ": " + explain(e));
                    if (isAuth(e)) { askRelogin(); break; }
                } finally { png.delete(); }
            }
            setBusy(false);
            setStatus("✓ " + ok + " added" + (failed.isEmpty() ? "" : " · " + failed.size() + " failed"));
            if (!failed.isEmpty()) onUi(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Some items were not added")
                        .setMessage(android.text.TextUtils.join("\n\n", failed)).setPositiveButton("OK", null).show();
            }});
        }});
    }

    /** Small JPEG preview (kept only on the phone, encrypted). */
    byte[] makeThumb(final byte[] b, final int off, final int len, boolean video) {
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
                Bitmap f = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 400);
                r.release();
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
                        d.setTargetSampleSize(Math.max(1, Math.min(s.getWidth(), s.getHeight()) / 400));
                        d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 80, o);
            return o.toByteArray();
        } catch (Throwable e) { Journal.add("preview not created: " + e); return null; }
    }

    /** Rebuilds the local list from the PhotoVault folder on Amazon (new phone, reinstall, or items deleted on the website). */
    void sync() {
        if (busy) { toast("Wait for the current operation to finish."); return; }
        final byte[] k = key;
        final Set<String> local = new HashSet<>();
        for (Item it : items) local.add(it.id);
        setBusy(true);
        setStatus("Listing your vault on Amazon…");
        io.execute(new Runnable() { public void run() {
            int restored = 0, skipped = 0, removed = 0;
            try {
                List<JSONObject> nodes = Amazon.listFiles(folder(), 1_000_000);
                final Set<String> remote = new HashSet<>();
                for (JSONObject n : nodes) remote.add(n.getString("id"));
                if (nodes.size() < 9000) { // listing is complete: drop items deleted on the Amazon website
                    final List<String> gone = new ArrayList<>();
                    for (String id : local) if (!remote.contains(id)) gone.add(id);
                    removed = gone.size();
                    if (!gone.isEmpty()) onUi(new Runnable() { public void run() {
                        Iterator<Item> it = items.iterator();
                        while (it.hasNext()) if (gone.contains(it.next().id)) it.remove();
                        saveIndex();
                    }});
                }
                int n = 0;
                for (JSONObject node : nodes) {
                    n++;
                    String id = node.getString("id");
                    if (local.contains(id)) continue;
                    setStatus("Restoring " + n + "/" + nodes.size() + "…");
                    File f = new File(getCacheDir(), "sync.png");
                    try {
                        Amazon.download(id, owner(), f, null);
                        Vault.Opened o;
                        try (InputStream in = new FileInputStream(f)) { o = Vault.decryptPng(in, k); }
                        JSONObject m = new JSONObject(o.meta);
                        Item it = new Item();
                        it.id = id; it.name = m.optString("name", "item"); it.mime = m.optString("mime", "");
                        it.taken = m.optLong("taken", System.currentTimeMillis()); it.size = o.dataLen();
                        byte[] thumb = makeThumb(o.plain, o.dataOff, o.dataLen(), it.video());
                        if (thumb != null) writeFile(thumbFile(id), Vault.seal(k, thumb));
                        addItem(it);
                        restored++;
                    } catch (Throwable e) {
                        if (isAuth(e)) throw e;
                        Journal.add("sync: skipped a file: " + e);
                        skipped++;
                    } finally { f.delete(); }
                }
                setStatus("✓ Sync done · " + restored + " restored · " + removed + " removed"
                        + (skipped > 0 ? " · " + skipped + " skipped (not openable with this vault's key, see Log)" : ""));
            } catch (Throwable e) {
                Journal.add("sync failed: " + e);
                setStatus("✗ Sync failed: " + explain(e));
                if (isAuth(e)) askRelogin();
            } finally { setBusy(false); }
        }});
    }

    // ================================================================ viewer

    void showViewer(final Item it) {
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        l.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final TextView info = new TextView(this);
        info.setPadding(dp(16), dp(8), dp(16), dp(4));
        info.setText("Downloading encrypted file…");
        l.addView(info);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), 0, dp(8), dp(8));
        final Button amazon = new Button(this), save = new Button(this), del = new Button(this);
        amazon.setText("Amazon's view");
        save.setText("Save to phone");
        del.setText("Delete");
        for (Button b : new Button[]{amazon, save, del}) {
            b.setAllCaps(false);
            b.setEnabled(false);
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        l.addView(row);
        setScreen("viewer", it.name, date(it.taken), l);
        final byte[] k = key;
        final File png = blobFile(it.id);
        del.setEnabled(true);
        del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete(it); } });

        viewIo.execute(new Runnable() { public void run() {
            try {
                if (!png.exists()) {
                    Amazon.download(it.id, owner(), png, new Amazon.Progress() {
                        public void on(final long d, final long t) {
                            onUi(new Runnable() { public void run() { info.setText("Downloading encrypted file… " + (t > 0 ? 100 * d / t + "%" : human(d))); } });
                        }
                    });
                    trimBlobCache();
                } else png.setLastModified(System.currentTimeMillis());
                onUi(new Runnable() { public void run() { info.setText("Decrypting…"); } });
                long t = SystemClock.elapsedRealtime();
                final Vault.Opened o;
                try (InputStream in = new FileInputStream(png)) { o = Vault.decryptPng(in, k); }
                final long ms = SystemClock.elapsedRealtime() - t;
                final String line = "✓ Decrypted and verified in " + ms + " ms (AES-GCM: not a single bit changed)\n"
                        + human(o.dataLen()) + " original · stored on Amazon as a " + human(png.length()) + " PNG of noise";
                if (it.video()) {
                    String ext = it.name.contains(".") ? it.name.substring(it.name.lastIndexOf('.')) : ".mp4";
                    deletePlaying();
                    playing = new File(getCacheDir(), "play" + ext);
                    try (OutputStream out = new FileOutputStream(playing)) { out.write(o.plain, o.dataOff, o.dataLen()); }
                    onUi(new Runnable() { public void run() {
                        if (!"viewer".equals(screen)) return;
                        shown = o;
                        VideoView vv = new VideoView(MainActivity.this);
                        MediaController mc = new MediaController(MainActivity.this);
                        mc.setAnchorView(vv);
                        vv.setMediaController(mc);
                        vv.setVideoPath(playing.getPath());
                        box.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                        vv.start();
                        ready(it, o, line, info, box, png, amazon, save);
                    }});
                } else {
                    final Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(o.plain, o.dataOff, o.dataLen()).slice()),
                            new ImageDecoder.OnHeaderDecodedListener() {
                                public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                                    Size z = info.getSize();
                                    d.setTargetSampleSize(Math.max(1, Math.max(z.getWidth(), z.getHeight()) / 4096 + 1));
                                }
                            });
                    onUi(new Runnable() { public void run() {
                        if (!"viewer".equals(screen)) return;
                        shown = o;
                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageBitmap(bm);
                        box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        ready(it, o, line, info, box, png, amazon, save);
                    }});
                }
            } catch (final Throwable e) {
                Journal.add("view failed: " + e);
                png.delete();
                onUi(new Runnable() { public void run() { info.setText("✗ " + explain(e)); } });
                if (isAuth(e)) askRelogin();
            }
        }});
    }

    void ready(final Item it, final Vault.Opened o, String line, TextView info, final FrameLayout box, final File png, final Button amazon, Button save) {
        info.setText(line);
        amazon.setEnabled(true);
        save.setEnabled(true);
        amazon.setOnClickListener(new View.OnClickListener() {
            ImageView noise;
            public void onClick(View v) {
                if (noise != null) { box.removeView(noise); noise = null; amazon.setText("Amazon's view"); return; }
                BitmapFactory.Options op = new BitmapFactory.Options();
                op.inSampleSize = png.length() > (8 << 20) ? 4 : 1;
                noise = new ImageView(MainActivity.this);
                noise.setBackgroundColor(Color.BLACK);
                noise.setScaleType(ImageView.ScaleType.FIT_CENTER);
                noise.setImageBitmap(BitmapFactory.decodeFile(png.getPath(), op));
                box.addView(noise, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                amazon.setText("My photo");
            }
        });
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveToPhone(it, o); } });
    }

    void saveToPhone(final Item it, final Vault.Opened o) {
        viewIo.execute(new Runnable() { public void run() {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (it.video() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/PhotoVault");
                v.put(MediaStore.MediaColumns.DATE_TAKEN, it.taken);
                Uri u = getContentResolver().insert(it.video() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IOException("gallery refused the file");
                try (OutputStream out = getContentResolver().openOutputStream(u)) { out.write(o.plain, o.dataOff, o.dataLen()); }
                onUi(new Runnable() { public void run() { toast("Saved (unencrypted) to " + (it.video() ? "Movies" : "Pictures") + "/PhotoVault"); } });
            } catch (final Exception e) { onUi(new Runnable() { public void run() { toast("Save failed: " + explain(e)); } }); }
        }});
    }

    void confirmDelete(final Item it) {
        new AlertDialog.Builder(this).setTitle("Delete from vault?")
                .setMessage("The encrypted file goes to your Amazon Photos trash (Amazon empties it after a while) and disappears from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    viewIo.execute(new Runnable() { public void run() {
                        try {
                            Amazon.trash(Collections.singletonList(it.id));
                            thumbFile(it.id).delete();
                            blobFile(it.id).delete();
                            onUi(new Runnable() { public void run() {
                                items.remove(it);
                                thumbs.remove(it.id);
                                saveIndex();
                                closeViewer();
                                showGallery();
                            }});
                        } catch (final Exception e) {
                            onUi(new Runnable() { public void run() { toast("Delete failed: " + explain(e)); } });
                            if (isAuth(e)) askRelogin();
                        }
                    }});
                }})
                .setNegativeButton("Cancel", null).show();
    }

    void closeViewer() { shown = null; deletePlaying(); }

    /** Decrypted videos exist on disk only while you watch them (app-private cache). */
    void deletePlaying() { if (playing != null) { playing.delete(); playing = null; } }

    // ================================================================ info, log

    void showInfo() {
        LinearLayout l = vbox();
        String fp = "";
        try { fp = Vault.sha256(key, 0, key.length).substring(0, 16); } catch (Exception ignored) { }
        text(l, "Where your data is", 17);
        text(l, "• Amazon: folder “PhotoVault” in your Amazon Photos (amazon.it): " + items.size() + " encrypted PNGs. "
                + "They also show up in Amazon's own app as images of static: that's them.\n"
                + "• This phone: the list of items and small encrypted previews, in app-private storage, excluded from backups.\n"
                + "• Your key: only in memory while unlocked" + (bioEnabled()
                ? "; also stored wrapped by a fingerprint-protected key in the phone's secure hardware (Android Keystore)." : ".")
                + " The app locks itself after 60 s in the background.", 15);
        text(l, "Cryptography", 17);
        text(l, "• Key = PBKDF2-HMAC-SHA256(password, random 16-byte salt, 600 000 iterations).\n"
                + "• Each file: AES-256-GCM with a fresh random nonce. The GCM tag rejects any modified bit.\n"
                + "• Name, date and EXIF are inside the encrypted part.\n"
                + "• Only standard algorithms from Android's built-in crypto library. No home-made cipher.\n"
                + "• Vault salt: " + prefs.getString("salt", "") + "\n"
                + "• Key fingerprint: " + fp + " (same password + salt ⇒ same fingerprint on any device; it reveals nothing about the key)", 15);
        text(l, "Recovery without this app", 17);
        text(l, "Download any PNG from the PhotoVault folder on the Amazon Photos website, then on a PC:\n"
                + "py photovault.py dec file.png -o out\n(the script ships with the app's source). If you lose the phone: install the app, "
                + "sign in, enter the same password, then Menu → Sync from Amazon.", 15);
        text(l, "Limits of this version", 17);
        text(l, "• Max 100 MB per item. • The password can't be changed (it would need re-encrypting everything). "
                + "• Keep the app open during big uploads.", 15);
        button(l, "Run the self-test again", new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
        if (bioEnabled()) button(l, "Turn off fingerprint unlock", new View.OnClickListener() { public void onClick(View v) { disableBio(); showInfo(); } });
        else if (bioAvailable()) button(l, "Turn on fingerprint unlock", new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
        button(l, "Show log", new View.OnClickListener() { public void onClick(View v) { showLog(); } });
        button(l, "Lock now", new View.OnClickListener() { public void onClick(View v) { lock(); } });
        button(l, "Sign out of Amazon", new View.OnClickListener() { public void onClick(View v) {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() { public void onReceiveValue(Boolean b) {
                toast("Signed out of Amazon. Your vault is untouched; sign in again to keep using it.");
                showLogin();
            }});
        }});
        setScreen("info", "Info & security", null, scroll(l));
    }

    void showLog() {
        LinearLayout l = vbox();
        text(l, "Every request to Amazon is listed here (method, path, result). Cookies, passwords, keys and file names are never logged.", 14);
        final TextView t = text(l, Journal.text(), 12);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        button(l, "Copy log", new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PhotoVault log", Journal.text()));
            toast("Log copied");
        }});
        setScreen("log", "Log", null, scroll(l));
    }
}
