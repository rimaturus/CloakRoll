package app.photovault;

import static app.photovault.Store.explain;
import static app.photovault.Store.hex;
import static app.photovault.Store.human;
import static app.photovault.Store.isAuth;
import static app.photovault.Store.readFile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final String VERIFY = "PhotoVault password check", BIO_ALIAS = "photovault_fingerprint";
    static final int REQ_PICK = 1, REQ_NOTIF = 2, OK = 0xFF2E7D32, BAD = 0xFFC62828, WARN = 0xFFE65100;
    static final List<String> VAULT_SCREENS = Arrays.asList("gallery", "viewer", "info");

    Store st;
    SharedPreferences prefs;
    final ExecutorService io = Executors.newSingleThreadExecutor();     // setup, unlock, self-test
    final ExecutorService viewIo = Executors.newSingleThreadExecutor(); // viewer
    final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable onChange = new Runnable() { public void run() { refresh(); } };
    final Runnable autoBio = new Runnable() { public void run() { if (started && "unlock".equals(screen) && bioEnabled()) bioUnlock(); } };
    String screen = "";
    boolean picking, connecting, testing, restored, started;
    long nextLoginTry;
    volatile int viewToken;
    WebView web;
    TextView titleView, subtitleView, statusLine, loginStatus, loginHost;
    BaseAdapter adapter;
    GridView grid;
    int cellSize;
    String openFolder = "";                               // "" = top level
    List<Object> cells = new ArrayList<>();               // what the grid shows: folder names (String), then items
    List<Store.Item> shownItems;                          // the lists `cells` was built from
    List<String> shownFolders;
    final Map<String, Integer> counts = new HashMap<>();  // folder -> number of items
    final Map<String, Store.Item> covers = new HashMap<>(); // folder -> newest item
    final Set<String> selected = new LinkedHashSet<>();   // ids selected with a long press
    final Map<String, int[]> scrollPos = new HashMap<>(); // folder -> grid position, restored when coming back
    List<Store.Item> viewList = new ArrayList<>();        // what the viewer swipes through
    int viewIndex;
    String lastViewedId;
    GestureDetector swipe;
    File playing;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // own top bar: predictable under Android 15+ edge-to-edge
        getWindow().setDecorFitsSystemWindows(false);   // same edge-to-edge layout on Android 13/14; insets handled in setScreen
        st = Store.get(this);
        prefs = st.prefs;
        if (!st.allowScreenshots) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // no screenshots, blank in "recent apps"
        CookieManager.getInstance(); // initialise on the UI thread before background use
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() { public void onBackInvoked() { back(); } });
        st.onChange = onChange; // also while stopped: an auto-lock must clear decrypted content from the screen
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || !"viewer".equals(screen)) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < dp(60) || Math.abs(dx) < 1.5f * Math.abs(dy) || Math.abs(vx) < dp(250)) return false;
                step(dx < 0 ? 1 : -1);
                return true;
            }
        });
        start();
    }

    void start() {
        if (!prefs.contains("verifier")) showIntro();
        else if (st.key == null) showUnlock();
        else showGallery();
    }

    long lockAfter() { return picking ? Store.PICKER_LOCK_MS : Store.AUTO_LOCK_MS; }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        st.ui.removeCallbacks(st.autoLock);
        // the timer below doesn't run while the phone sleeps: this check is what really enforces the lock
        if (st.key != null && st.backgroundSince > 0 && SystemClock.elapsedRealtime() - st.backgroundSince > lockAfter()) st.lock();
        st.backgroundSince = 0;
        st.onChange = onChange;
        refresh();
        if ("unlock".equals(screen) && bioEnabled()) { ui.removeCallbacks(autoBio); ui.postDelayed(autoBio, 400); }
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        // armed even if still locked: an unlock that completes in the background is covered too
        st.backgroundSince = SystemClock.elapsedRealtime();
        st.ui.postDelayed(st.autoLock, lockAfter());
    }

    @Override protected void onDestroy() {
        if (st.onChange == onChange) st.onChange = null;
        ui.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        screen = "";
        deletePlaying();
        io.shutdown();
        viewIo.shutdown();
        thumbIo.shutdown();
        super.onDestroy();
    }

    /** Reacts to shared state: lock, new items, background progress, expired Amazon session. */
    void refresh() {
        if (st.key == null) { // locked: forget everything decrypted that the screens hold
            openFolder = "";
            selected.clear();
            scrollPos.clear();
            counts.clear();
            covers.clear();
            cells = new ArrayList<>();
            viewList = new ArrayList<>();
            lastViewedId = null;
            shownItems = null;
            shownFolders = null;
        }
        if (st.key == null && VAULT_SCREENS.contains(screen)) { closeViewer(); showUnlock(); return; }
        if (st.needLogin && started) { st.needLogin = false; askRelogin(); }
        if ("gallery".equals(screen)) {
            if (shownItems != st.items || shownFolders != st.folders) {
                String before = openFolder;
                boolean wasSelecting = !selected.isEmpty();
                rebuildCells();
                if (!before.equals(openFolder)) { showGallery(); return; } // the open folder was renamed or deleted
                if (wasSelecting && selected.isEmpty()) { saveScroll(); showGallery(); return; } // selected items are gone
                adapter.notifyDataSetChanged();
                if (selected.isEmpty()) subtitleView.setText(gallerySubtitle());
                else titleView.setText(selected.size() + " selected");
            }
            statusLine.setText(st.status == null ? "" : st.status + (st.jobRunning ? "  (tap to stop)" : ""));
            statusLine.setVisibility(st.status == null ? View.GONE : View.VISIBLE);
        }
    }

    void back() {
        switch (screen) {
            case "viewer": closeViewer(); showGallery(); break;
            case "gallery":
                saveScroll();
                if (!selected.isEmpty()) { selected.clear(); showGallery(); }
                else if (!openFolder.isEmpty()) { openFolder = ""; showGallery(); }
                else finish();
                break;
            case "info": showGallery(); break;
            case "log": if (st.key != null && prefs.contains("verifier")) showGallery(); else start(); break;
            case "login":
                if (web != null && web.canGoBack()) web.goBack();
                else if (prefs.contains("verifier")) start(); else showIntro();
                break;
            case "selftest": if (!testing) { if (st.key != null) showGallery(); else start(); } break;
            case "create": case "restore": showIntro(); break;
            default: finish();
        }
    }

    void menu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        Menu m = p.getMenu();
        if ("gallery".equals(screen)) {
            if (openFolder.isEmpty()) m.add(0, 7, 0, "New folder");
            m.add(0, 2, 0, "Sync from Amazon");
            m.add(0, 3, 0, "Info & security");
            if (!Config.DONATE_URL.isEmpty()) m.add(0, 6, 0, "Support PhotoVault");
            m.add(0, 5, 0, "Lock now");
        }
        m.add(0, 4, 0, "Log");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { public boolean onMenuItemClick(MenuItem i) {
            switch (i.getItemId()) {
                case 2: startSync(); break;
                case 3: showInfo(); break;
                case 4: showLog(); break;
                case 5: st.lock(); st.changed(); break;
                case 6: openUrl(Config.DONATE_URL); break;
                case 7: newFolder(); break;
            }
            return true;
        }});
        p.show();
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

    TextView barButton(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        t.setBackground(a.getDrawable(0));
        a.recycle();
        t.setOnClickListener(l);
        return t;
    }

    /** Menu (three dots) or back (arrow) button, drawn rather than typed: no font can miss it. */
    View icon(final boolean dots, View.OnClickListener l) {
        final int color = new TextView(this).getCurrentTextColor();
        View v = new View(this) {
            @Override protected void onDraw(Canvas c) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(color);
                float cx = getWidth() / 2f, cy = getHeight() / 2f, u = dp(1);
                if (dots) for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * 6 * u, 2.2f * u, p);
                else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * u);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    Path a = new Path();
                    a.moveTo(cx + 7 * u, cy);
                    a.lineTo(cx - 7 * u, cy);
                    a.moveTo(cx - 1 * u, cy - 6 * u);
                    a.lineTo(cx - 7 * u, cy);
                    a.lineTo(cx - 1 * u, cy + 6 * u);
                    c.drawPath(a, p);
                }
            }
        };
        v.setContentDescription(dots ? "Menu" : "Back");
        TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        v.setBackground(ta.getDrawable(0));
        ta.recycle();
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return v;
    }

    /** Every screen: top bar + content, padded away from status bar, navigation bar, camera cutout and keyboard. */
    void setScreen(String name, String title, String subtitle, boolean backArrow, View content) {
        if (web != null && !"login".equals(name)) {
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        viewToken++; // abandons a viewer download that is still running
        deletePlaying();
        screen = name;
        statusLine = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(backArrow ? dp(2) : dp(16), dp(4), dp(2), dp(4));
        if (backArrow) bar.addView(icon(false, new View.OnClickListener() { public void onClick(View v) { back(); } }));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(t);
        titleView = t;
        subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setAlpha(0.7f);
        subtitleView.setSingleLine();
        subtitleView.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        texts.addView(subtitleView);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if ("gallery".equals(name) && !selected.isEmpty()) {
            bar.addView(barButton("Move", new View.OnClickListener() { public void onClick(View v) { moveSelected(); } }));
            bar.addView(barButton("Delete", new View.OnClickListener() { public void onClick(View v) { confirmDeleteSelected(); } }));
        } else {
            if ("gallery".equals(name)) {
                TextView add = barButton("+ Add", new View.OnClickListener() { public void onClick(View v) { pick(); } });
                add.setTypeface(Typeface.DEFAULT_BOLD);
                bar.addView(add);
            }
            bar.addView(icon(true, new View.OnClickListener() { public void onClick(View v) { menu(v); } }));
        }
        root.addView(bar);
        View line = new View(this);
        line.setBackgroundColor(0x33888888);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(night ? 0 : light, light);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void post(final Runnable r) { ui.post(new Runnable() { public void run() { if (!isDestroyed()) r.run(); } }); }

    static String date(long ms) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms)); }

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No browser found for " + url); }
    }

    void askRelogin() {
        new AlertDialog.Builder(this).setTitle("Amazon sign-in needed")
                .setMessage("Amazon ended the session (this happens every few weeks). Sign in again; your vault and password are not affected.")
                .setPositiveButton("Sign in", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { showLogin(); } })
                .setNegativeButton("Later", null).show();
    }

    byte[] keyCopy() { return st.key == null ? null : st.key.clone(); }


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
        text(l, "Free, open source, no ads, no tracking", 17);
        text(l, "PhotoVault has no servers, no accounts, no analytics and no ads. It talks only to Amazon, and only with your encrypted files. "
                + "It collects nothing, so there is nothing to sell. Development is funded only by voluntary donations.", 15);
        text(l, "What you need to know", 17);
        text(l, "• Your vault password is the only key. Nobody can reset it, not Amazon and not this app. If you lose it, the photos are gone.\n"
                + "• The app uses the same private web interface as the Amazon Photos website (Amazon has no public API). If Amazon changes it, "
                + "uploads can stop working until the app is updated. Files already stored stay decryptable, also on a PC with photovault.py.\n"
                + "• Videos stored as photo files go against the spirit of Amazon's terms (Prime includes only 5 GB for video). "
                + "Amazon could restrict the account. Keep a second backup of anything irreplaceable.\n"
                + "• PhotoVault is not made by or affiliated with Amazon.", 15);
        text(l, "Setup takes 3 steps: sign in to Amazon, create your vault password, and a self-test on your account.", 15);
        button(l, "Continue: sign in to Amazon Photos", new View.OnClickListener() { public void onClick(View v) { showLogin(); } });
        setScreen("intro", "PhotoVault", "Setup", false, scroll(l));
    }

    // ================================================================ setup 1: Amazon login in a WebView

    void showLogin() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setPadding(dp(16), dp(8), dp(16), dp(2));
        head.setText("Sign in to Amazon as usual. PhotoVault doesn't read or store your Amazon password: "
                + "it reuses the login session this page creates, like a browser tab. Links to non-Amazon sites open in your normal browser instead.");
        l.addView(head);
        loginHost = new TextView(this);
        loginHost.setPadding(dp(16), dp(2), dp(16), dp(2));
        loginHost.setTypeface(Typeface.MONOSPACE);
        l.addView(loginHost);
        loginStatus = new TextView(this);
        loginStatus.setPadding(dp(16), 0, dp(16), dp(8));
        loginStatus.setTypeface(Typeface.DEFAULT_BOLD);
        loginStatus.setText("Waiting for sign-in...");
        l.addView(loginStatus);
        setScreen("login", "Sign in to Amazon", prefs.contains("verifier") ? "Refresh session" : "Step 1 of 3", true, l);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(Amazon.UA); // desktop site: the one the API calls belong to
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!r.isForMainFrame() || ("https".equals(u.getScheme()) && Amazon.isAmazonHost(u.getHost()))) return false;
                Journal.add("login page: blocked navigation to " + u.getScheme() + "://" + u.getHost());
                if (r.isForMainFrame() && ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))) openUrl(u.toString());
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { showHost(url); }
            @Override public void onPageFinished(WebView v, String url) { showHost(url); checkLogin(); }
        });
        l.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.loadUrl(Amazon.WEB + "/photos");
        connecting = false;
        nextLoginTry = 0;
        ui.removeCallbacks(pollLogin);
        ui.postDelayed(pollLogin, 2000);
    }

    void showHost(String url) {
        if (loginHost == null || url == null) return;
        Uri u = Uri.parse(url);
        boolean safe = "https".equals(u.getScheme()) && Amazon.isAmazonHost(u.getHost());
        loginHost.setText((safe ? "Amazon site: " : "Not an Amazon site: ") + u.getScheme() + "://" + u.getHost());
        loginHost.setTextColor(safe ? OK : BAD);
    }

    final Runnable pollLogin = new Runnable() {
        public void run() { if ("login".equals(screen)) { checkLogin(); ui.postDelayed(this, 2000); } }
    };

    void checkLogin() {
        if (connecting || SystemClock.elapsedRealtime() < nextLoginTry || !Amazon.hasSession()) return;
        connecting = true;
        CookieManager.getInstance().flush();
        loginStatus.setText("Signed in. Checking access to Amazon Photos...");
        io.execute(new Runnable() { public void run() {
            try {
                JSONObject root = Amazon.root();
                String folder = Amazon.folder(root.getString("id"), "PhotoVault");
                final List<JSONObject> first = Amazon.listFiles(folder, 1).files;
                prefs.edit().putString("owner", root.optString("ownerId")).putString("folder", folder).apply();
                Journal.add("connected: Amazon Photos folder PhotoVault ready");
                post(new Runnable() { public void run() {
                    connecting = false;
                    if (!"login".equals(screen)) return;
                    if (prefs.contains("verifier")) { toast("Amazon session refreshed"); start(); }
                    else if (first.isEmpty()) showCreatePassword();
                    else showRestorePassword(first.get(0).optString("id"));
                }});
            } catch (final Exception e) {
                Journal.add("connect failed: " + e);
                post(new Runnable() { public void run() {
                    connecting = false;
                    nextLoginTry = SystemClock.elapsedRealtime() + 15_000;
                    if (loginStatus != null) loginStatus.setText("Signed in, but Amazon Photos didn't accept the session yet ("
                            + explain(e) + "). Let the Photos page finish loading; I retry every 15 s. Menu > Log for details.");
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
        button(l, "Create vault", new View.OnClickListener() { public void onClick(final View btn) {
            final String a = p1.getText().toString(), b = p2.getText().toString();
            err.setTextColor(BAD);
            if (a.length() < 10) { err.setText("Use at least 10 characters."); return; }
            if (!a.equals(b)) { err.setText("The two passwords don't match."); return; }
            if (!ok.isChecked()) { err.setText("Please confirm you saved the password."); return; }
            btn.setEnabled(false);
            err.setTextColor(p1.getCurrentTextColor());
            err.setText("Deriving your key... (takes a few seconds on purpose: it makes password guessing slow)");
            io.execute(new Runnable() { public void run() {
                try {
                    final byte[] salt = Vault.random(16), k = Vault.deriveKey(a, salt);
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    st.writeIndex(k, new Store.Index());
                    Journal.add("new vault created");
                    post(new Runnable() { public void run() { st.key = k; st.items = new ArrayList<>(); st.folders = new ArrayList<>(); showSelfTest(); } });
                } catch (final Exception e) {
                    post(new Runnable() { public void run() { btn.setEnabled(true); err.setTextColor(BAD); err.setText(explain(e)); } });
                }
            }});
        }});
        setScreen("create", "New vault", "Step 2 of 3", true, scroll(l));
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
            err.setText("Downloading one file and deriving the key...");
            io.execute(new Runnable() { public void run() {
                File f = new File(getCacheDir(), "sample.png");
                try {
                    Amazon.download(sampleId, st.owner(), f, null);
                    byte[] salt;
                    try (InputStream in = new FileInputStream(f)) { salt = Vault.readSalt(in); }
                    final byte[] k = Vault.deriveKey(pw, salt);
                    try (InputStream in = new FileInputStream(f)) { Vault.decryptPng(in, k); }
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    st.writeIndex(k, new Store.Index());
                    prefs.edit().putBoolean("restore_pending", true).putBoolean("folders_dirty", false).commit();
                    Journal.add("existing vault unlocked");
                    post(new Runnable() { public void run() { st.key = k; st.items = new ArrayList<>(); st.folders = new ArrayList<>(); restored = true; showSelfTest(); } });
                } catch (final Throwable e) {
                    post(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setText(e instanceof AEADBadTagException ? "Wrong password." : explain(e));
                    }});
                } finally { f.delete(); }
            }});
        }});
        setScreen("restore", "Existing vault", "Step 2 of 3", true, scroll(l));
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
        for (int i = 0; i < labels.length; i++) steps[i] = text(l, "...  " + labels[i], 15);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        l.addView(actions);
        setScreen("selftest", "Self-test", prefs.contains("done_selftest") ? null : "Step 3 of 3", false, scroll(l));
        runSelfTest(labels, steps, mine, theirs, actions);
    }

    void step(final TextView t, final String label, final int state, final String detail) {
        post(new Runnable() { public void run() {
            t.setText((state == OK ? "OK  " : state == BAD ? "FAILED  " : "CHECK  ") + label + (detail == null ? "" : "\n     " + detail));
            t.setTextColor(state);
        }});
    }

    void runSelfTest(final String[] labels, final TextView[] s, final ImageView mine, final ImageView theirs, final LinearLayout actions) {
        final byte[] k = keyCopy(), salt = st.salt();
        if (k == null) { showUnlock(); return; }
        testing = true;
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
                post(new Runnable() { public void run() { mine.setImageBitmap(img); } });
                step(s[i], labels[i++], OK, human(orig.length) + " image became a " + human(up.length()) + " PNG in " + (SystemClock.elapsedRealtime() - t) + " ms");

                t = SystemClock.elapsedRealtime();
                JSONObject node = Amazon.upload(up, hex(Vault.random(8)) + ".png", st.folder(), null);
                nodeId = node.getString("id");
                step(s[i], labels[i++], OK, (SystemClock.elapsedRealtime() - t) + " ms");

                JSONObject cp = node.optJSONObject("contentProperties");
                String type = cp == null ? "" : cp.optString("contentType");
                if (type.startsWith("image/")) step(s[i], labels[i++], OK, "type: " + type);
                else step(s[i], labels[i++], WARN, "Amazon reports '" + type + "': check under Storage that it counts as a photo");

                t = SystemClock.elapsedRealtime();
                Amazon.download(nodeId, st.owner(), down, null);
                step(s[i], labels[i++], OK, human(down.length()) + ", " + (SystemClock.elapsedRealtime() - t) + " ms");

                byte[] a = readFile(up), b = readFile(down);
                String h1 = Vault.sha256(a, 0, a.length), h2 = Vault.sha256(b, 0, b.length);
                if (!h1.equals(h2)) { step(s[i], labels[i], BAD, "Amazon returned a different file: it recompresses, so this can't work"); throw new IOException("file altered by Amazon"); }
                step(s[i], labels[i++], OK, h1.substring(0, 16) + "...");

                Vault.Opened o;
                try (InputStream in = new FileInputStream(down)) { o = Vault.decryptPng(in, k); }
                boolean same = Vault.sha256(o.plain, o.dataOff, o.dataLen()).equals(Vault.sha256(orig, 0, orig.length));
                if (!same) { step(s[i], labels[i], BAD, "mismatch"); throw new IOException("decrypted data differs"); }
                final Bitmap noise = BitmapFactory.decodeFile(down.getPath());
                post(new Runnable() { public void run() { theirs.setImageBitmap(noise); } });
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
                Arrays.fill(k, (byte) 0);
            }
            final boolean fok = ok;
            post(new Runnable() { public void run() {
                testing = false;
                if (!"selftest".equals(screen)) return;
                if (fok) {
                    text(actions, "All checks passed. Amazon stores your encrypted files bit-for-bit and only this app can open them.", 15).setTextColor(OK);
                    if (bioAvailable() && !bioEnabled())
                        button(actions, "Enable fingerprint unlock (recommended)", new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
                    button(actions, "Open my vault", new View.OnClickListener() { public void onClick(View v) { openMyVault(); } });
                } else {
                    text(actions, "The test stopped at the step marked FAILED. Nothing personal was uploaded.", 15).setTextColor(BAD);
                    button(actions, "Retry", new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
                    button(actions, "Sign in to Amazon again", new View.OnClickListener() { public void onClick(View v) { showLogin(); } });
                    button(actions, "Show log (to report the problem)", new View.OnClickListener() { public void onClick(View v) { showLog(); } });
                }
            }});
        }});
    }

    /** After setup: a vault restored on a new phone starts rebuilding its list from Amazon right away. */
    void openMyVault() {
        if (restored && st.items.isEmpty()) { restored = false; startSync(); }
        showGallery();
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
        final Button go = button(l, "Unlock with password", null);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setText("Deriving your key...");
            io.execute(new Runnable() { public void run() {
                byte[] k = null;
                try { k = Vault.deriveKey(pw, st.salt()); } catch (Exception ignored) { }
                openVault(k, new Runnable() { public void run() { btn.setEnabled(true); err.setText("Wrong password."); } });
            }});
        }});
        if (st.status != null && st.jobRunning) text(l, "Background: " + st.status, 13).setAlpha(0.7f);
        setScreen("unlock", "PhotoVault", "Locked", false, scroll(l));
        if (bioEnabled() && started) { ui.removeCallbacks(autoBio); ui.postDelayed(autoBio, 400); }
    }

    /** Background thread: checks the key against the stored verifier, loads the encrypted index, opens the gallery. */
    void openVault(final byte[] k, final Runnable wrong) {
        boolean good;
        try { good = k != null && Arrays.equals(Vault.open(k, Base64.decode(prefs.getString("verifier", ""), Base64.NO_WRAP)), VERIFY.getBytes("UTF-8")); }
        catch (Exception e) { good = false; }
        if (!good) { post(wrong); return; }
        synchronized (st) { // a background job can't change the index between this read and the list shown
            Store.Index l;
            try { l = st.readIndex(k); st.writeIndex(k, l); } // also upgrades an older index to the current, encrypted format
            catch (Exception e) { Journal.add("local list unreadable, use Sync from Amazon: " + e); l = new Store.Index(); }
            final Store.Index ix = l;
            post(new Runnable() { public void run() { st.key = k; st.items = ix.items; st.folders = ix.folders; showGallery(); } });
        }
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
                        if (isDestroyed()) return;
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
                if (st.key == null) return;
                byte[] ct = c.doFinal(st.key);
                prefs.edit().putString("bio_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                toast("Fingerprint unlock enabled");
                if ("info".equals(screen)) showInfo(); else openMyVault();
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
                final byte[] key = c.doFinal(Base64.decode(prefs.getString("bio_ct", ""), Base64.NO_WRAP));
                io.execute(new Runnable() { public void run() {
                    openVault(key, new Runnable() { public void run() { toast("Fingerprint key doesn't match this vault: use your password"); } });
                }});
            }});
        } catch (KeyPermanentlyInvalidatedException e) {
            disableBio();
            toast("Your fingerprints changed, so fingerprint unlock was reset. Use your password, then enable it again in Info.");
            showUnlock();
        } catch (Exception e) { toast("Fingerprint: " + explain(e)); }
    }

    // ================================================================ gallery: folders and items

    /** Top level: folder tiles, then items not in a folder. Inside a folder: its items. Main thread. */
    void rebuildCells() {
        shownItems = st.items;
        shownFolders = st.folders;
        if (!openFolder.isEmpty() && !st.folders.contains(openFolder)) openFolder = "";
        Set<String> known = new HashSet<>(st.folders), ids = new HashSet<>();
        counts.clear();
        covers.clear();
        List<Object> c = new ArrayList<>();
        if (openFolder.isEmpty()) c.addAll(st.folders);
        for (Store.Item it : st.items) {
            String f = known.contains(it.folder) ? it.folder : ""; // unknown folder name: show it at the top level
            if (!f.isEmpty()) {
                Integer n = counts.get(f);
                counts.put(f, n == null ? 1 : n + 1);
                if (!covers.containsKey(f)) covers.put(f, it);
            }
            if (f.equals(openFolder)) { c.add(it); ids.add(it.id); }
        }
        selected.retainAll(ids);
        cells = c;
    }

    List<Store.Item> cellItems() {
        List<Store.Item> l = new ArrayList<>();
        for (Object o : cells) if (o instanceof Store.Item) l.add((Store.Item) o);
        return l;
    }

    static String count(int n, String what) { return n + " " + what + (n == 1 ? "" : "s"); }

    String gallerySubtitle() {
        if (!openFolder.isEmpty()) return count(cellItems().size(), "item") + " in this folder";
        return count(st.items.size(), "item") + " encrypted on Amazon" + (st.folders.isEmpty() ? "" : ", " + count(st.folders.size(), "folder"));
    }

    void showGallery() {
        if (st.key == null) { showUnlock(); return; }
        rebuildCells();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(this);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setBackgroundColor(0x14888888);
        status.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (st.jobRunning) new AlertDialog.Builder(MainActivity.this).setMessage("Stop the background job after the current file?")
                    .setPositiveButton("Stop", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { SyncService.stop(MainActivity.this); } })
                    .setNegativeButton("Continue", null).show();
            else { st.status = null; refresh(); }
        }});
        l.addView(status);
        TextView empty = new TextView(this);
        empty.setPadding(dp(24), dp(32), dp(24), dp(24));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setGravity(Gravity.CENTER);
        empty.setText(openFolder.isEmpty()
                ? "Your vault is empty.\n\nTap + Add to encrypt and upload photos or videos. Uploads continue in the background.\n\n"
                  + "Already have a vault on Amazon (new phone, reinstall)? Menu > Sync from Amazon."
                : "This folder is empty.\n\nTap + Add to upload photos straight into it, or go back, long-press photos to select them and tap Move.");
        l.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cellSize = getResources().getDisplayMetrics().widthPixels / 3;
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new BaseAdapter() {
            public int getCount() { return cells.size(); }
            public Object getItem(int p) { return cells.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) { return cell(convert, cells.get(p)); }
        };
        grid.setAdapter(adapter);
        grid.setEmptyView(empty);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { tap(p); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) { longTap(p); return true; }
        });
        l.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        boolean selecting = !selected.isEmpty();
        setScreen("gallery", selecting ? selected.size() + " selected" : openFolder.isEmpty() ? "PhotoVault" : openFolder,
                selecting ? "Tap to select more, back to cancel" : gallerySubtitle(), selecting || !openFolder.isEmpty(), l);
        statusLine = status;
        restoreScroll();
        refresh();
    }

    View cell(View convert, Object o) {
        FrameLayout f = (FrameLayout) convert;
        if (f == null) {
            f = new FrameLayout(this);
            f.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x22888888);
            f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView label = new TextView(this);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.BOTTOM);
            label.setPadding(dp(10), dp(8), dp(10), dp(10));
            f.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ImageView iv = (ImageView) f.getChildAt(0);
        TextView label = (TextView) f.getChildAt(1);
        Store.Item it;
        if (o instanceof String) { // folder tile: darkened cover, name and count
            String name = (String) o;
            Integer n = counts.get(name);
            label.setText(name + "\n" + count(n == null ? 0 : n, "item"));
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setBackgroundColor(0x8C000000);
            it = covers.get(name);
            f.setForeground(null);
        } else {
            it = (Store.Item) o;
            label.setText(null);
            label.setBackground(null);
            if (selected.contains(it.id)) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x552DD4BF);
                g.setStroke(dp(4), 0xFF2DD4BF);
                f.setForeground(g);
            } else f.setForeground(null);
        }
        String id = it == null ? null : it.id;
        iv.setTag(id);
        Bitmap b = id == null ? null : st.thumbs.get(id);
        iv.setImageBitmap(b);
        if (b == null && id != null) loadThumb(id, iv);
        return f;
    }

    void tap(int p) {
        Object o = cells.get(p);
        if (o instanceof String) {
            if (!selected.isEmpty()) { toast("Tap Move to put the selected items into a folder."); return; }
            saveScroll();
            openFolder = (String) o;
            showGallery();
            return;
        }
        Store.Item it = (Store.Item) o;
        if (!selected.isEmpty()) { toggle(it); return; }
        saveScroll();
        viewList = cellItems();
        viewIndex = viewList.indexOf(it);
        showViewer(it);
    }

    void longTap(int p) {
        Object o = cells.get(p);
        if (o instanceof String) { if (selected.isEmpty()) folderOptions((String) o); return; }
        Store.Item it = (Store.Item) o;
        if (!selected.isEmpty()) { toggle(it); return; }
        saveScroll();
        selected.add(it.id);
        showGallery();
    }

    void toggle(Store.Item it) {
        if (!selected.remove(it.id)) selected.add(it.id);
        if (selected.isEmpty()) { saveScroll(); showGallery(); return; }
        titleView.setText(selected.size() + " selected");
        adapter.notifyDataSetChanged();
    }

    /** Remembers where the grid was, per folder, so coming back doesn't jump to the top. */
    void saveScroll() {
        if (grid == null || !"gallery".equals(screen)) return;
        View first = grid.getChildAt(0);
        scrollPos.put(openFolder, new int[]{grid.getFirstVisiblePosition(), first == null ? 0 : first.getTop(), grid.getChildCount()});
    }

    /** Back where the grid was; if the viewer was swiped to an item that was off screen, that item is shown instead. */
    void restoreScroll() {
        int[] p = scrollPos.get(openFolder);
        int target = -1;
        if (lastViewedId != null)
            for (int i = 0; i < cells.size(); i++)
                if (cells.get(i) instanceof Store.Item && ((Store.Item) cells.get(i)).id.equals(lastViewedId)) target = i;
        lastViewedId = null;
        if (target >= 0 && (p == null || target < p[0] || target >= p[0] + p[2])) grid.setSelection(target);
        else if (p != null) grid.setSelectionFromTop(p[0], p[1]);
    }

    // ---------------------------------------------------------------- folder actions

    interface NameDone { void run(String name); }

    void askFolderName(String title, final String current, final boolean mustBeNew, final NameDone done) {
        final EditText e = new EditText(this);
        e.setSingleLine();
        e.setHint("Folder name");
        e.setText(current);
        e.setSelection(current.length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String n = e.getText().toString().trim().replaceAll("\\s+", " ");
                    if (n.isEmpty() || n.equals(current)) return;
                    if (n.length() > 60) { toast("Use at most 60 characters."); return; }
                    if (mustBeNew && !n.equalsIgnoreCase(current))
                        for (String f : st.folders) if (f.equalsIgnoreCase(n)) { toast("A folder called " + f + " already exists."); return; }
                    done.run(n);
                }})
                .setNegativeButton("Cancel", null).show();
    }

    /** Changes the local list in the background, then saves the folders (encrypted) to Amazon. */
    void editFolders(final Store.Edit e, final String doneMessage) {
        final byte[] k = keyCopy();
        if (k == null) return;
        io.execute(new Runnable() { public void run() {
            try {
                st.markFoldersDirty();
                st.edit(k, e);
                st.backupFolders(k);
                if (doneMessage != null) post(new Runnable() { public void run() { toast(doneMessage); } });
            } catch (final Exception x) {
                post(new Runnable() { public void run() { toast("Could not save: " + explain(x)); } });
            } finally { Arrays.fill(k, (byte) 0); }
        }});
    }

    void newFolder() {
        askFolderName("New folder", "", true, new NameDone() { public void run(final String name) {
            editFolders(new Store.Edit() { public void apply(Store.Index ix) { if (!ix.hasFolder(name)) ix.folders.add(name); } }, null);
        }});
    }

    void folderOptions(final String name) {
        new AlertDialog.Builder(this).setTitle(name)
                .setItems(new String[]{"Rename", "Delete folder (keeps its photos)"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { if (w == 0) renameFolder(name); else deleteFolder(name); }
                }).show();
    }

    void renameFolder(final String old) {
        askFolderName("Rename folder", old, true, new NameDone() { public void run(final String name) {
            editFolders(new Store.Edit() { public void apply(Store.Index ix) {
                int i = ix.folders.indexOf(old);
                if (i >= 0) ix.folders.set(i, name);
                for (Store.Item it : ix.items) if (it.folder.equals(old)) it.folder = name;
            }}, null);
        }});
    }

    void deleteFolder(final String name) {
        Integer n = counts.get(name);
        new AlertDialog.Builder(this).setTitle("Delete folder " + name + "?")
                .setMessage(n == null ? "The folder is empty." : "Its " + count(n, "item") + " are kept: they move back to the main view.")
                .setPositiveButton("Delete folder", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    editFolders(new Store.Edit() { public void apply(Store.Index ix) {
                        ix.folders.remove(name);
                        for (Store.Item it : ix.items) if (it.folder.equals(name)) it.folder = "";
                    }}, null);
                }})
                .setNegativeButton("Cancel", null).show();
    }

    void moveSelected() {
        final List<String> options = new ArrayList<>();
        options.add("Main view (no folder)");
        options.addAll(st.folders);
        options.add("New folder...");
        new AlertDialog.Builder(this).setTitle("Move " + count(selected.size(), "item") + " to")
                .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    if (w == options.size() - 1)
                        askFolderName("New folder", "", false, new NameDone() { public void run(String name) { moveTo(name); } });
                    else moveTo(w == 0 ? "" : options.get(w));
                }}).show();
    }

    void moveTo(final String folder) {
        final Set<String> ids = new HashSet<>(selected);
        editFolders(new Store.Edit() { public void apply(Store.Index ix) {
            String f = folder;
            for (String x : ix.folders) if (x.equalsIgnoreCase(folder)) f = x; // same name, different case: use the existing one
            if (!f.isEmpty() && !ix.folders.contains(f)) ix.folders.add(f);
            for (Store.Item it : ix.items) if (ids.contains(it.id)) it.folder = f;
        }}, "Moved " + count(ids.size(), "item") + (folder.isEmpty() ? " to the main view" : " to " + folder));
        saveScroll();
        selected.clear();
        showGallery();
    }

    void confirmDeleteSelected() {
        final List<String> ids = new ArrayList<>(selected);
        new AlertDialog.Builder(this).setTitle("Delete " + count(ids.size(), "item") + "?")
                .setMessage("The encrypted files go to your Amazon Photos trash (Amazon empties it after a while) and disappear from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { deleteItems(ids); } })
                .setNegativeButton("Cancel", null).show();
    }

    /** Moves items to the Amazon Photos trash, then removes them from the list and their local previews. */
    void deleteItems(final List<String> ids) {
        final byte[] k = keyCopy();
        if (k == null) return;
        viewIo.execute(new Runnable() { public void run() {
            final Set<String> gone = new HashSet<>();
            try {
                for (int i = 0; i < ids.size(); i += 50) {
                    List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
                    Amazon.trash(batch);
                    gone.addAll(batch);
                }
            } catch (final Exception e) {
                post(new Runnable() { public void run() { toast("Delete failed: " + explain(e)); if (isAuth(e)) askRelogin(); } });
            }
            try {
                if (!gone.isEmpty()) st.edit(k, new Store.Edit() { public void apply(Store.Index ix) {
                    Iterator<Store.Item> i = ix.items.iterator();
                    while (i.hasNext()) if (gone.contains(i.next().id)) i.remove();
                }});
                for (String id : gone) { st.thumbFile(id).delete(); st.blobFile(id).delete(); }
            } catch (final Exception e) {
                Journal.add("delete: list not updated, Sync fixes it: " + e);
            } finally { Arrays.fill(k, (byte) 0); }
            post(new Runnable() { public void run() {
                for (String id : gone) st.thumbs.remove(id);
                Iterator<Store.Item> i = viewList.iterator();
                while (i.hasNext()) if (gone.contains(i.next().id)) i.remove();
                if ("viewer".equals(screen) && gone.contains(lastViewedId)) { closeViewer(); showGallery(); }
                else if ("viewer".equals(screen)) {
                    for (int n = 0; n < viewList.size(); n++) if (viewList.get(n).id.equals(lastViewedId)) viewIndex = n;
                }
            }});
        }});
    }

    // ---------------------------------------------------------------- previews, adding, sync

    void loadThumb(final String id, final ImageView iv) {
        final byte[] k = keyCopy();
        if (k == null) return;
        thumbIo.execute(new Runnable() { public void run() {
            final Bitmap b = st.thumb(id, k);
            Arrays.fill(k, (byte) 0);
            if (b != null) post(new Runnable() { public void run() {
                if (st.key == null) return;
                st.thumbs.put(id, b);
                if (id.equals(iv.getTag())) iv.setImageBitmap(b);
            }});
        }});
    }

    void pick() {
        picking = true;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply(); // asked once: needed only to show upload progress
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        launchPicker();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTIF) launchPicker();
    }

    void launchPicker() {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES); // Android photo picker: no storage permission needed
        i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        picking = true;
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        picking = false;
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        if (st.key == null) { toast("Vault is locked."); return; }
        List<Uri> uris = new ArrayList<>();
        ClipData c = data.getClipData();
        if (c != null) for (int i = 0; i < c.getItemCount(); i++) uris.add(c.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        try {
            SyncService.start(this, SyncService.UPLOAD, uris, openFolder);
            toast(count(uris.size(), "item") + " queued" + (openFolder.isEmpty() ? "" : " for " + openFolder)
                    + ". Encryption and upload run in the background: you can leave the app.");
        } catch (Exception e) { toast("Could not start the upload: " + explain(e)); }
    }

    void startSync() {
        try { SyncService.start(this, SyncService.SYNC, null, ""); }
        catch (Exception e) { toast("Could not start the sync: " + explain(e)); }
    }

    // ================================================================ viewer

    /** Horizontal swipe in the viewer: next / previous item of the folder it was opened from. */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if ("viewer".equals(screen)) swipe.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    void step(int d) {
        int i = viewIndex + d;
        if (i < 0 || i >= viewList.size()) return;
        viewIndex = i;
        showViewer(viewList.get(i));
    }


    void showViewer(final Store.Item it) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        l.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final TextView info = new TextView(this);
        info.setPadding(dp(16), dp(8), dp(16), dp(4));
        info.setText("Low-resolution preview, downloading the encrypted original...");
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
        setScreen("viewer", it.name, (viewList.size() > 1 ? (viewIndex + 1) + " of " + viewList.size() + "  |  " : "")
                + date(it.taken) + ", " + human(it.size), true, l);
        lastViewedId = it.id;
        final int token = viewToken;
        del.setEnabled(true);
        del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete(it); } });

        // 1) instant: the small preview kept (encrypted) on the phone
        Bitmap pre = st.thumbs.get(it.id);
        if (pre != null) iv.setImageBitmap(pre);
        else loadThumb(it.id, iv);
        iv.setTag(it.id);

        // 2) full quality: download (or reuse the cached encrypted PNG), decrypt, swap in
        final byte[] k = keyCopy();
        final File png = st.blobFile(it.id);
        viewIo.execute(new Runnable() { public void run() {
            try {
                if (token != viewToken) return;
                if (!png.exists()) {
                    Amazon.download(it.id, st.owner(), png, new Amazon.Progress() {
                        public void on(final long d, final long t) {
                            if (token != viewToken) throw new CancellationException(); // viewer closed: stop downloading
                            post(new Runnable() { public void run() {
                                if (token == viewToken) info.setText("Low-resolution preview, downloading the encrypted original... " + (t > 0 ? 100 * d / t + "%" : human(d)));
                            }});
                        }
                    });
                    st.trimBlobCache();
                } else png.setLastModified(System.currentTimeMillis());
                if (token != viewToken) return;
                post(new Runnable() { public void run() { if (token == viewToken) info.setText("Low-resolution preview, decrypting..."); } });
                long t = SystemClock.elapsedRealtime();
                final Vault.Opened o;
                try (InputStream in = new FileInputStream(png)) { o = Vault.decryptPng(in, k); }
                final long ms = SystemClock.elapsedRealtime() - t;
                final String line = "Decrypted and verified in " + ms + " ms (AES-GCM: not a single bit changed)\n"
                        + human(o.dataLen()) + " original, stored on Amazon as a " + human(png.length()) + " PNG of noise";
                if (it.video()) {
                    String ext = it.name.contains(".") ? it.name.substring(it.name.lastIndexOf('.')) : ".mp4";
                    final File f = new File(getCacheDir(), "play" + ext);
                    try (OutputStream out = new FileOutputStream(f)) { out.write(o.plain, o.dataOff, o.dataLen()); }
                    post(new Runnable() { public void run() {
                        if (token != viewToken) { f.delete(); return; }
                        deletePlaying();
                        playing = f;
                        VideoView vv = new VideoView(MainActivity.this);
                        MediaController mc = new MediaController(MainActivity.this);
                        mc.setAnchorView(vv);
                        vv.setMediaController(mc);
                        vv.setVideoPath(f.getPath());
                        box.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                        box.removeView(iv);
                        vv.start();
                        ready(it, o, line, info, box, png, amazon, save);
                    }});
                } else {
                    final Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(o.plain, o.dataOff, o.dataLen()).slice()),
                            new ImageDecoder.OnHeaderDecodedListener() {
                                public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo inf, ImageDecoder.Source s) {
                                    Size z = inf.getSize();
                                    d.setTargetSampleSize(Math.max(1, Math.max(z.getWidth(), z.getHeight()) / 4096 + 1));
                                }
                            });
                    post(new Runnable() { public void run() {
                        if (token != viewToken) return;
                        iv.setTag(null); // a late preview must not replace the full image
                        iv.setImageBitmap(bm);
                        ready(it, o, line, info, box, png, amazon, save);
                    }});
                }
            } catch (final Throwable e) {
                if (e instanceof CancellationException) return;
                Journal.add("view failed: " + e);
                png.delete();
                post(new Runnable() { public void run() { if (token == viewToken) info.setText("Error: " + explain(e)); } });
                if (isAuth(e)) post(new Runnable() { public void run() { askRelogin(); } });
            } finally { Arrays.fill(k, (byte) 0); }
        }});
    }

    void ready(final Store.Item it, final Vault.Opened o, String line, TextView info, final FrameLayout box, final File png, final Button amazon, Button save) {
        info.setText(line);
        amazon.setEnabled(true);
        save.setEnabled(true);
        amazon.setOnClickListener(new View.OnClickListener() {
            ImageView noise;
            public void onClick(View v) {
                if (noise != null) { box.removeView(noise); noise = null; amazon.setText("Amazon's view"); return; }
                final ImageView n = noise = new ImageView(MainActivity.this);
                n.setBackgroundColor(Color.BLACK);
                n.setScaleType(ImageView.ScaleType.FIT_CENTER);
                box.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                amazon.setText("My photo");
                viewIo.execute(new Runnable() { public void run() {
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inSampleSize = png.length() > (8 << 20) ? 4 : 1;
                    final Bitmap b = BitmapFactory.decodeFile(png.getPath(), op);
                    post(new Runnable() { public void run() { n.setImageBitmap(b); } });
                }});
            }
        });
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveToPhone(it, o); } });
    }

    void saveToPhone(final Store.Item it, final Vault.Opened o) {
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
                post(new Runnable() { public void run() { toast("Saved (unencrypted) to " + (it.video() ? "Movies" : "Pictures") + "/PhotoVault"); } });
            } catch (final Exception e) { post(new Runnable() { public void run() { toast("Save failed: " + explain(e)); } }); }
        }});
    }

    void confirmDelete(final Store.Item it) {
        new AlertDialog.Builder(this).setTitle("Delete from vault?")
                .setMessage("The encrypted file goes to your Amazon Photos trash (Amazon empties it after a while) and disappears from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    deleteItems(Collections.singletonList(it.id));
                }})
                .setNegativeButton("Cancel", null).show();
    }

    void closeViewer() { viewToken++; deletePlaying(); }

    /** Decrypted videos exist on disk only while you watch them (app-private cache). */
    void deletePlaying() { if (playing != null) { playing.delete(); playing = null; } }

    // ================================================================ info, log

    void showInfo() {
        LinearLayout l = vbox();
        String fp = "";
        try { fp = Vault.sha256(st.key, 0, st.key.length).substring(0, 16); } catch (Exception ignored) { }
        text(l, "Our promise", 17);
        text(l, "• No ads. No trackers, no analytics, no crash reporting.\n"
                + "• No servers and no accounts of ours: the app talks only to Amazon, only with encrypted files.\n"
                + "• Nothing is collected, so nothing can be sold or leaked by us.\n"
                + "• Free and open source (GPL-3.0): anyone can check what the app does.\n"
                + "• Funded only by voluntary donations. Donating unlocks nothing: every feature is free for everyone.", 15);
        if (!Config.DONATE_URL.isEmpty())
            button(l, "Support PhotoVault with a donation", new View.OnClickListener() { public void onClick(View v) { openUrl(Config.DONATE_URL); } });
        if (!Config.SOURCE_URL.isEmpty())
            button(l, "Source code", new View.OnClickListener() { public void onClick(View v) { openUrl(Config.SOURCE_URL); } });
        text(l, "Where your data is", 17);
        text(l, "• Amazon: folder \"PhotoVault\" in your Amazon Photos: " + st.items.size() + " encrypted PNGs. "
                + "They also show up in Amazon's own app as images of static: that's them, don't delete them there.\n"
                + "• Folders: their names and which item is in which are saved on Amazon as one more encrypted PNG (subfolder \"index\"), "
                + "so a new phone gets them back with Sync. Amazon can't read the folder names.\n"
                + "• This phone: the list of items and small previews, both encrypted with your vault key, in app-private storage, excluded from backups and phone-to-phone transfers.\n"
                + "• Your key: only in memory while unlocked" + (bioEnabled()
                ? "; also stored wrapped by a fingerprint-protected key in the phone's secure hardware (Android Keystore)" : "")
                + ". The app locks itself 60 s after you leave it. A running background upload keeps its own copy of the key until it finishes.", 15);
        text(l, "Cryptography", 17);
        text(l, "• Key = PBKDF2-HMAC-SHA256(password, random 16-byte salt, 600 000 iterations).\n"
                + "• Each file: AES-256-GCM with a fresh random nonce. The GCM tag rejects any modified bit.\n"
                + "• Name, date and EXIF are inside the encrypted part.\n"
                + "• Only standard algorithms from Android's built-in crypto library, and no third-party libraries at all.\n"
                + "• Vault salt: " + prefs.getString("salt", "") + "\n"
                + "• Key fingerprint: " + fp + " (same password and salt give the same fingerprint on any device; it reveals nothing about the key)", 15);
        text(l, "Recovery without this app", 17);
        text(l, "Download any PNG from the PhotoVault folder on the Amazon Photos website, then on a PC:\n"
                + "python photovault.py dec file.png -o out\n(photovault.py is part of PhotoVault's open-source code on GitHub). If you lose the phone: install the app, "
                + "sign in, enter the same password; the app restores your vault from Amazon.", 15);
        text(l, "Limits of this version", 17);
        text(l, "• Max 100 MB per item. • The password can't be changed (it would need re-encrypting everything). "
                + "• Background jobs are limited by Android to 6 hours per day.", 15);
        button(l, "Run the self-test again", new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
        if (bioEnabled()) button(l, "Turn off fingerprint unlock", new View.OnClickListener() { public void onClick(View v) { disableBio(); showInfo(); } });
        else if (bioAvailable()) button(l, "Turn on fingerprint unlock", new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
        button(l, st.allowScreenshots ? "Block screenshots again" : "Allow screenshots until the app is closed", new View.OnClickListener() { public void onClick(View v) {
            st.allowScreenshots = !st.allowScreenshots;
            if (st.allowScreenshots) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            showInfo();
        }});
        button(l, "Show log", new View.OnClickListener() { public void onClick(View v) { showLog(); } });
        button(l, "Lock now", new View.OnClickListener() { public void onClick(View v) { st.lock(); st.changed(); } });
        button(l, "Sign out of Amazon", new View.OnClickListener() { public void onClick(View v) {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() { public void onReceiveValue(Boolean b) {
                toast("Signed out of Amazon. Your vault is untouched; sign in again to keep using it.");
                showLogin();
            }});
        }});
        text(l, "PhotoVault " + version() + ", not affiliated with Amazon. Amazon Photos is a trademark of Amazon.", 12).setAlpha(0.6f);
        setScreen("info", "Info & security", null, true, scroll(l));
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
        setScreen("log", "Log", null, true, scroll(l));
    }
}
