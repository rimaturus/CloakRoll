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
import android.content.res.ColorStateList;
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
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.util.Size;
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
    static final String VERIFY = Store.VERIFY, BIO_ALIAS = "photovault_fingerprint", REDIRECT_SCHEME = "io.github.rimaturus.photovault";
    static final int REQ_PICK = 1, REQ_NOTIF = 2, RUN = 1, OK = 2, WARN = 3, BAD = 4;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final List<String> VAULT_SCREENS = Arrays.asList("gallery", "viewer", "settings", "about", "changepw");

    Store st;
    SharedPreferences prefs;
    Ui u;
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
    TextView titleView, subtitleView, loginStatus, loginHost, odStatus;
    ImageView loginHostIcon;
    View odFallback;
    LinearLayout banner;
    TextView bannerText, bannerAction;
    ImageView bannerIcon;
    ProgressBar bannerSpin;
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
    final Map<String, String> renames = new HashMap<>();  // folder renamed while open: old -> new name
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
        u = new Ui(this);
        if (!st.allowScreenshots) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // no screenshots, blank in "recent apps"
        CookieManager.getInstance(); // initialise on the UI thread before background use
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() { public void onBackInvoked() { back(); } });
        st.onChange = onChange; // also while stopped: an auto-lock must clear decrypted content from the screen
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || !"viewer".equals(screen)) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < u.dp(60) || Math.abs(dx) < 1.5f * Math.abs(dy) || Math.abs(vx) < u.dp(250)) return false;
                swipeTo(dx < 0 ? 1 : -1);
                return true;
            }
        });
        start();
        if (b == null) handleRedirect(getIntent());
    }

    void start() {
        if (!prefs.contains("verifier")) showWelcome();
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
        picking = false; // back from the photo picker or the sign-in browser: normal auto-lock delay again
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

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleRedirect(i);
    }

    @Override protected void onDestroy() {
        viewToken++; // a viewer download still running stops and deletes what it decrypted
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

    /** Reacts to shared state: lock, new items, background progress, expired sign-in. */
    void refresh() {
        if (st.key == null) { // locked: forget everything decrypted that the screens hold
            openFolder = "";
            selected.clear();
            scrollPos.clear();
            renames.clear();
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
            banner.setVisibility(st.status == null ? View.GONE : View.VISIBLE);
            if (st.status != null) {
                boolean failed = st.status.contains("failed") || st.status.contains("paused") || st.status.contains("Not added");
                bannerText.setText(st.status);
                bannerSpin.setVisibility(st.jobRunning ? View.VISIBLE : View.GONE);
                bannerIcon.setVisibility(st.jobRunning ? View.GONE : View.VISIBLE);
                bannerIcon.setImageDrawable(new Ui.Icon(failed ? Ui.ALERT : Ui.CHECK, failed ? u.warn : u.ok));
                bannerAction.setText(st.jobRunning ? "Stop" : "OK");
            }
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
            case "settings": showGallery(); break;
            case "about": case "changepw": showSettings(); break;
            case "log":
                if (!prefs.contains("verifier")) showSignIn();
                else if (st.key != null) showSettings();
                else start();
                break;
            case "login":
                if (web != null && web.canGoBack()) { web.goBack(); break; }
                // fall through
            case "odlogin": if (prefs.contains("verifier")) start(); else showChoose(); break;
            case "choose": showWelcome(); break;
            case "selftest": if (!testing) { if (st.key != null) showGallery(); else start(); } break;
            case "create": case "restore": showChoose(); break;
            default: finish();
        }
    }

    // ================================================================ screen frame and small helpers

    /**
     * Every screen: top bar + content, padded away from status bar, navigation bar, camera cutout and keyboard.
     * `left`: 0, Ui.BACK or Ui.CLOSE. `actions`: icon buttons on the right.
     */
    void setScreen(String name, String title, String subtitle, int left, View content, View... actions) {
        if (web != null) { // the sign-in screen makes a new one after this
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        viewToken++; // abandons a viewer download that is still running
        deletePlaying();
        screen = name;
        banner = null;
        boolean dark = "viewer".equals(name);
        int fg = dark ? Color.WHITE : u.text;
        LinearLayout root = u.vbox();
        root.setBackgroundColor(dark ? Color.BLACK : u.bg);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        LinearLayout bar = u.hbox();
        bar.setMinimumHeight(u.dp(60));
        bar.setPadding(left != 0 ? u.dp(4) : u.dp(20), u.dp(4), u.dp(4), u.dp(4));
        if (left != 0) bar.addView(u.iconButton(left, fg, left == Ui.CLOSE ? "Cancel" : "Back", new View.OnClickListener() { public void onClick(View v) { back(); } }));
        LinearLayout texts = u.vbox();
        texts.setPadding(left != 0 ? u.dp(6) : 0, 0, u.dp(8), 0);
        titleView = u.label(texts, title, 21, fg, true);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subtitleView = u.label(texts, subtitle, 13, dark ? 0xB3FFFFFF : u.muted, false);
        subtitleView.setSingleLine();
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        bar.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        for (View a : actions) bar.addView(a);
        root.addView(bar);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(u.night || dark ? 0 : light, light);
    }

    View menuButton() { return u.iconButton(Ui.MORE, u.text, "Menu", new View.OnClickListener() { public void onClick(View v) { menu(v); } }); }

    View logButton() { return u.iconButton(Ui.LIST, u.text, "Log", new View.OnClickListener() { public void onClick(View v) { showLog(); } }); }

    void menu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        Menu m = p.getMenu();
        if (openFolder.isEmpty()) m.add(0, 1, 0, "New folder");
        else { m.add(0, 2, 0, "Rename folder"); m.add(0, 3, 0, "Delete folder"); }
        m.add(0, 4, 0, "Sync from " + st.cloud().name());
        m.add(0, 5, 0, "Settings");
        if (!Config.DONATE_URL.isEmpty()) m.add(0, 6, 0, "Support PhotoVault");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() { public boolean onMenuItemClick(MenuItem i) {
            switch (i.getItemId()) {
                case 1: newFolder(); break;
                case 2: renameFolder(openFolder); break;
                case 3: deleteFolder(openFolder); break;
                case 4: startSync(); break;
                case 5: showSettings(); break;
                case 6: openUrl(Config.DONATE_URL); break;
            }
            return true;
        }});
        p.show();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void post(final Runnable r) { ui.post(new Runnable() { public void run() { if (!isDestroyed()) r.run(); } }); }

    static String date(long ms) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms)); }

    static String count(int n, String what) { return n + " " + what + (n == 1 ? "" : "s"); }

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No browser found for " + url); }
    }

    byte[] keyCopy() { return st.key == null ? null : st.key.clone(); }

    String cloudName() { return st.cloud().name(); }

    /** Where the vault is, in words. */
    String vaultPlace() {
        Cloud c = st.cloud();
        if (c instanceof OneDrive) return ((OneDrive) c).allFiles() ? "the folder PhotoVault in your OneDrive" : "the folder Apps/PhotoVault in your OneDrive";
        return "the folder PhotoVault in your Amazon Photos";
    }

    void askRelogin() {
        new AlertDialog.Builder(this).setTitle(cloudName() + " sign-in needed")
                .setMessage(cloudName() + " ended the session (this happens from time to time). Sign in again; your vault and password are not affected.")
                .setPositiveButton("Sign in", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { showSignIn(); } })
                .setNegativeButton("Later", null).show();
    }

    // ================================================================ setup: welcome, choice of storage

    void showWelcome() {
        LinearLayout l = u.page();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView hero = u.badge(Ui.LOCK, u.onAccent, u.accent, 88);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(u.dp(88), u.dp(88));
        hp.topMargin = u.dp(24);
        l.addView(hero, hp);
        u.title(l, "PhotoVault").setGravity(Gravity.CENTER);
        u.note(l, "Your photos and videos, encrypted before they leave this phone.").setGravity(Gravity.CENTER);
        LinearLayout k = u.card(l);
        u.feature(k, Ui.LOCK, "Encrypted on your phone", "Each photo or video is encrypted here with AES-256, with a key made from your password.");
        u.feature(k, Ui.NOISE, "The cloud sees only noise", "What gets uploaded is a PNG that looks like TV static. No faces, places, dates or file names: nothing to scan or to train AI on.");
        u.feature(k, Ui.SHIELD, "No ads, no tracking, no servers", "Free and open source. The app talks only to your cloud storage, only with encrypted files. Funded by voluntary donations.");
        u.feature(k, Ui.KEY, "Your password is the only key", "Nobody can reset it: not the cloud, not this app. If you lose it, the photos are gone. Keep a second backup of anything irreplaceable.");
        u.button(l, "Get started", Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { showChoose(); } });
        u.note(l, "Setup: choose where to store, sign in, create your password, self-test.").setGravity(Gravity.CENTER);
        setScreen("welcome", "", null, 0, u.scroll(l));
    }

    void showChoose() {
        LinearLayout l = u.page();
        u.steps(l, 1, 3);
        u.title(l, "Where should your vault live?");
        u.note(l, "Only encrypted PNGs are stored there. To move to another service later you would start a new vault.");
        option(l, "Amazon Photos", "Unlimited with Prime",
                "Full-resolution photos don't count against your storage with Prime. Unofficial: PhotoVault uses the same web interface as the "
                + "Amazon Photos website, so it can stop working if Amazon changes it (your files stay decryptable). Videos stored as photos go "
                + "against the spirit of Prime's terms, which include only 5 GB for video.", true, "amazon");
        option(l, "OneDrive", "Official Microsoft API",
                "Uses your OneDrive storage: 5 GB free, 1 TB with Microsoft 365. PhotoVault gets access only to its own folder, "
                + "Apps/PhotoVault, not to your other files." + (OneDrive.configured() ? ""
                : "\n\nNot available in this build: it needs a Microsoft app registration (see PUBLISHING.md)."), OneDrive.configured(), "onedrive");
        LinearLayout n = u.notice(l, u.field);
        u.heading(n, "Why not Google Photos?");
        u.note(n, "Google's rules don't allow it: apps may upload only real photos and videos, can't delete what they upload, "
                + "and downloads aren't bit-exact, which encrypted files need.");
        setScreen("choose", "Storage", "Step 1 of 3", Ui.BACK, u.scroll(l));
    }

    void option(LinearLayout parent, String name, String tag, String detail, boolean enabled, final String backend) {
        LinearLayout k = u.card(parent);
        LinearLayout head = u.hbox();
        head.addView(u.badge(Ui.CLOUD, u.accent, u.accentSoft, 44), new LinearLayout.LayoutParams(u.dp(44), u.dp(44)));
        LinearLayout t = u.vbox();
        t.setPadding(u.dp(14), 0, 0, 0);
        u.label(t, name, 18, u.text, true);
        u.label(t, tag, 13, u.accent, true);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        k.addView(head);
        u.note(k, detail).setPadding(0, u.dp(10), 0, 0);
        k.setAlpha(enabled ? 1f : 0.55f);
        if (!enabled) return;
        k.setBackground(u.ripple(Ui.round(u.surface, u.dp(20)), u.dp(20)));
        k.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            prefs.edit().putString("backend", backend).apply();
            showSignIn();
        }});
    }

    void showSignIn() { if (st.cloud() instanceof OneDrive) showOneDriveLogin(); else showLogin(); }

    interface Failed { void run(Exception e); }

    /**
     * Background: finds or makes the vault folder on the signed-in account, then: new vault, existing vault, or back
     * to the vault after a new sign-in. `from` is the screen that asked; nothing happens if it was left meanwhile.
     */
    void connect(final String from, final Failed failed) {
        connecting = true;
        io.execute(new Runnable() { public void run() {
            try {
                final Cloud c = st.cloud();
                final String folder = c.vaultFolder();
                final List<JSONObject> first = c.listFiles(folder, 1).files;
                Journal.add("connected: " + c.name() + " vault folder ready");
                post(new Runnable() { public void run() {
                    if (!from.equals(screen)) { connecting = false; return; }
                    String old = prefs.getString("folder", "");
                    if (!prefs.contains("verifier") || old.isEmpty() || old.equals(folder)) { connecting = false; useFolder(folder, first); return; }
                    // `connecting` stays true while the question is open: the sign-in page doesn't connect again meanwhile
                    new AlertDialog.Builder(MainActivity.this).setTitle("Another account?")
                            .setMessage("The PhotoVault folder of this " + c.name() + " account is not the one your vault is in: another account, "
                                    + "or the folder was deleted. Items of your vault won't open from here, and a Sync would take them off the list.")
                            .setPositiveButton("Sign out", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { connecting = false; signOut(); } })
                            .setNegativeButton("Use this account", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { connecting = false; useFolder(folder, first); } })
                            .setCancelable(false).show();
                }});
            } catch (final Exception e) {
                Journal.add("connect failed: " + e);
                post(new Runnable() { public void run() {
                    connecting = false;
                    if (from.equals(screen)) failed.run(e);
                }});
            }
        }});
    }

    void useFolder(String folder, List<JSONObject> first) {
        prefs.edit().putString("folder", folder).apply();
        if (prefs.contains("verifier")) { toast("Signed in to " + cloudName()); start(); }
        else if (first.isEmpty()) showCreatePassword();
        else showRestorePassword(first.get(0).optString("id"));
    }

    void signOut() {
        final Cloud c = st.cloud();
        c.signOut();
        toast("Signed out of " + c.name() + ". Your vault is untouched; sign in again to keep using it.");
        if (c instanceof Amazon) // cookies are removed in the background: open the sign-in page after that
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() { public void onReceiveValue(Boolean b) { if (!isDestroyed()) showSignIn(); } });
        else showSignIn();
    }

    // ================================================================ setup 1a: Amazon sign-in in a WebView

    void showLogin() {
        LinearLayout l = u.vbox();
        LinearLayout head = u.vbox();
        head.setPadding(u.dp(16), 0, u.dp(16), u.dp(8));
        if (!prefs.contains("verifier")) u.steps(head, 1, 3);
        u.note(head, "Sign in to Amazon as usual. PhotoVault doesn't read or store your Amazon password: it reuses the session this "
                + "page creates, like a browser tab. Links to other sites open in your normal browser.");
        LinearLayout hostRow = u.hbox();
        hostRow.setBackground(Ui.round(u.field, u.dp(12)));
        hostRow.setPadding(u.dp(10), u.dp(6), u.dp(12), u.dp(6));
        loginHostIcon = new ImageView(this);
        hostRow.addView(loginHostIcon, new LinearLayout.LayoutParams(u.dp(18), u.dp(18)));
        loginHost = u.label(hostRow, "", 13, u.muted, false);
        loginHost.setTypeface(Typeface.MONOSPACE);
        loginHost.setPadding(u.dp(8), 0, 0, 0);
        loginHost.setSingleLine();
        head.addView(hostRow, u.wide(u.dp(6)));
        loginStatus = u.label(head, "Waiting for sign-in...", 14, u.text, true);
        loginStatus.setPadding(u.dp(4), u.dp(8), 0, 0);
        l.addView(head);
        setScreen("login", "Sign in to Amazon", prefs.contains("verifier") ? "Refresh the session" : "Step 1 of 3", Ui.BACK, l, logButton());

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
                Uri x = r.getUrl();
                if (!r.isForMainFrame() || ("https".equals(x.getScheme()) && Amazon.isAmazonHost(x.getHost()))) return false;
                Journal.add("login page: blocked navigation to " + x.getScheme() + "://" + x.getHost());
                if (r.isForMainFrame() && ("https".equals(x.getScheme()) || "http".equals(x.getScheme()))) openUrl(x.toString());
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { showHost(url); }
            @Override public void onPageFinished(WebView v, String url) { showHost(url); checkLogin(); }
        });
        l.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        web.loadUrl(Amazon.WEB + "/photos");
        connecting = false;
        nextLoginTry = 0;
        ui.removeCallbacks(pollLogin);
        ui.postDelayed(pollLogin, 2000);
    }

    void showHost(String url) {
        if (loginHost == null || url == null || !"login".equals(screen)) return;
        Uri x = Uri.parse(url);
        boolean safe = "https".equals(x.getScheme()) && Amazon.isAmazonHost(x.getHost());
        loginHost.setText((safe ? "Amazon: " : "Not Amazon: ") + x.getScheme() + "://" + x.getHost());
        loginHost.setTextColor(safe ? u.ok : u.bad);
        loginHostIcon.setImageDrawable(new Ui.Icon(safe ? Ui.LOCK : Ui.ALERT, safe ? u.ok : u.bad));
    }

    final Runnable pollLogin = new Runnable() {
        public void run() { if ("login".equals(screen)) { checkLogin(); ui.postDelayed(this, 2000); } }
    };

    void checkLogin() {
        if (connecting || !"login".equals(screen) || SystemClock.elapsedRealtime() < nextLoginTry || !st.cloud().hasSession()) return;
        CookieManager.getInstance().flush();
        loginStatus.setText("Signed in. Checking access to Amazon Photos...");
        connect("login", new Failed() { public void run(Exception e) {
            nextLoginTry = SystemClock.elapsedRealtime() + 15_000;
            loginStatus.setText("Signed in, but Amazon Photos didn't accept the session yet (" + explain(e)
                    + "). Let the Photos page finish loading; I retry every 15 s. Log (top right) has the details.");
        }});
    }

    // ================================================================ setup 1b: OneDrive sign-in in the browser

    void showOneDriveLogin() {
        final boolean setup = !prefs.contains("verifier");
        LinearLayout l = u.page();
        if (setup) u.steps(l, 1, 3);
        u.title(l, "Sign in to OneDrive");
        LinearLayout k = u.card(l);
        u.feature(k, Ui.KEY, "Your password stays with Microsoft", "You sign in on Microsoft's own page, in your browser. PhotoVault gets "
                + (((OneDrive) st.cloud()).allFiles() ? "a token for your OneDrive and uses only the folder PhotoVault."
                   : "a token that opens only its own folder, Apps/PhotoVault, not your other files."));
        u.feature(k, Ui.LOCK, "The token is kept encrypted", "With a key in this phone's secure hardware. You can sign out any time in Settings.");
        u.button(l, "Sign in with Microsoft", Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) {
            openAuth(((OneDrive) st.cloud()).allFiles());
        }});
        odStatus = u.note(l, "");
        odStatus.setVisibility(View.GONE);
        LinearLayout fb = u.notice(l, u.warnSoft);
        u.heading(fb, "Microsoft refused the app folder");
        u.body(fb, "This happens with some new app registrations. You can give PhotoVault access to your OneDrive files instead: "
                + "the vault then goes in a normal folder called PhotoVault, and the app still touches only that folder.");
        u.button(fb, "Use full OneDrive access", Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { openAuth(true); } });
        fb.setVisibility(View.GONE);
        odFallback = fb;
        setScreen("odlogin", "OneDrive", setup ? "Step 1 of 3" : "Sign in again", Ui.BACK, u.scroll(l), logButton());
    }

    void odMessage(String s, int color) {
        if (odStatus == null || !"odlogin".equals(screen)) return;
        odStatus.setText(s);
        odStatus.setTextColor(color);
        odStatus.setVisibility(View.VISIBLE);
    }

    void openAuth(boolean allFiles) {
        try {
            String url = ((OneDrive) st.cloud()).authUrl(allFiles);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            picking = true; // longer auto-lock while the browser is in front
            odMessage("Waiting for the sign-in in your browser. When it's done, you come back here.", u.muted);
        } catch (Exception e) { toast("Could not open the browser: " + explain(e)); }
    }

    /** The way around Microsoft refusing app folders for some app registrations. Only offered during setup. */
    void offerAllFiles(OneDrive od) {
        if (!prefs.contains("verifier") && !od.allFiles() && odFallback != null && "odlogin".equals(screen)) odFallback.setVisibility(View.VISIBLE);
    }

    /** Microsoft sends the browser back to io.github.rimaturus.photovault://auth?code=...; the code is exchanged here. */
    void handleRedirect(Intent i) {
        final Uri x = i == null ? null : i.getData();
        if (x == null || !REDIRECT_SCHEME.equals(x.getScheme())) return;
        setIntent(new Intent()); // handled once
        String state = x.getQueryParameter("state");
        if (!"auth".equals(x.getHost()) || !(st.cloud() instanceof OneDrive) || state == null || !state.equals(prefs.getString("od_state", null)))
            return; // not the answer to a sign-in started here: ignored
        final OneDrive od = (OneDrive) st.cloud();
        final String error = x.getQueryParameter("error");
        if (!"odlogin".equals(screen)) showOneDriveLogin();
        if (connecting) return;
        connecting = true;
        odMessage("Finishing the sign-in...", u.muted);
        io.execute(new Runnable() { public void run() {
            try {
                od.redeem(x);
                post(new Runnable() { public void run() {
                    odMessage("Signed in. Opening your OneDrive folder...", u.muted);
                    connect("odlogin", new Failed() { public void run(Exception e) {
                        odMessage("Signed in, but OneDrive didn't open the vault folder: " + explain(e) + ". Log (top right) has the details.", u.bad);
                        int code = e instanceof Cloud.ApiError ? ((Cloud.ApiError) e).code : 0;
                        if (code == 400 || code == 403 || code == 404) offerAllFiles(od); // app folder refused for this app registration
                    }});
                }});
            } catch (final Exception e) {
                Journal.add("OneDrive sign-in failed: " + e);
                post(new Runnable() { public void run() {
                    connecting = false;
                    odMessage("Sign-in not completed: " + explain(e), u.bad);
                    if ("invalid_scope".equals(error) || "unauthorized_client".equals(error)) offerAllFiles(od);
                }});
            }
        }});
    }

    // ================================================================ setup 2: vault password

    void showCreatePassword() {
        LinearLayout l = u.page();
        u.steps(l, 2, 3);
        u.title(l, "Create your vault password");
        u.note(l, "This password encrypts everything. It never leaves this phone and nobody can reset or recover it. "
                + "A password manager is the best place to keep it.");
        LinearLayout k = u.card(l);
        final EditText p1 = u.password(k, "Vault password", null);
        u.strength(p1, k);
        final EditText p2 = u.password(k, "Repeat password", null);
        final CheckBox ok = u.check(k, "I saved this password somewhere safe. Without it my photos can never be recovered.");
        final TextView err = u.note(l, "");
        u.button(l, "Create vault", Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String a = p1.getText().toString(), b = p2.getText().toString();
            err.setTextColor(u.bad);
            if (a.length() < 10) { err.setText("Use at least 10 characters."); return; }
            if (!a.equals(b)) { err.setText("The two passwords don't match."); return; }
            if (!ok.isChecked()) { err.setText("Please confirm you saved the password."); return; }
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText("Deriving your key... (a few seconds on purpose: it makes password guessing slow)");
            io.execute(new Runnable() { public void run() {
                try {
                    final byte[] salt = Vault.random(16), k = Vault.deriveKey(a, salt);
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    st.writeIndex(k, new Store.Index());
                    Journal.add("new vault created");
                    post(new Runnable() { public void run() { st.key = k; st.items = new ArrayList<>(); st.folders = new ArrayList<>(); showSelfTest(); } });
                } catch (final Exception e) {
                    post(new Runnable() { public void run() { btn.setEnabled(true); err.setTextColor(u.bad); err.setText(explain(e)); } });
                }
            }});
        }});
        setScreen("create", "New vault", "Step 2 of 3", Ui.BACK, u.scroll(l), logButton());
    }

    void showRestorePassword(final String sampleId) {
        LinearLayout l = u.page();
        u.steps(l, 2, 3);
        u.title(l, "Unlock your existing vault");
        u.note(l, "This " + cloudName() + " account already has a PhotoVault folder with encrypted files. Enter the vault password you created it with.");
        final TextView[] go = new TextView[1];
        final EditText p = u.password(l, "Vault password", new Runnable() { public void run() { if (go[0].isEnabled()) go[0].performClick(); } });
        final TextView err = u.note(l, "");
        go[0] = u.button(l, "Unlock vault", Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText("Downloading one file and deriving the key...");
            io.execute(new Runnable() { public void run() {
                File f = new File(getCacheDir(), "sample.png");
                try {
                    Cloud c = st.cloud();
                    // the folders file always uses the newest key (also during a password change); then one photo
                    List<String> candidates = new ArrayList<>();
                    JSONObject newest = null;
                    for (JSONObject b : c.listFiles(st.indexFolder(), 1000).files)
                        if (newest == null || b.optString("createdDate").compareTo(newest.optString("createdDate")) > 0) newest = b;
                    if (newest != null) candidates.add(newest.getString("id"));
                    candidates.add(sampleId);
                    byte[] key = null, salt = null;
                    Throwable wrong = null;
                    for (String x : candidates) {
                        c.download(x, f, null);
                        byte[] s;
                        try (InputStream in = new FileInputStream(f)) { s = Vault.readSalt(in); }
                        byte[] kk = Vault.deriveKey(pw, s);
                        try (InputStream in = new FileInputStream(f)) { Vault.decryptPng(in, kk); key = kk; salt = s; break; }
                        catch (AEADBadTagException e) { wrong = e; Arrays.fill(kk, (byte) 0); }
                    }
                    if (key == null) throw wrong;
                    final byte[] k = key;
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    st.writeIndex(k, new Store.Index());
                    prefs.edit().putBoolean("restore_pending", true).putBoolean("folders_dirty", false).commit();
                    Journal.add("existing vault unlocked");
                    post(new Runnable() { public void run() { st.key = k; st.items = new ArrayList<>(); st.folders = new ArrayList<>(); restored = true; showSelfTest(); } });
                } catch (final Throwable e) {
                    post(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setTextColor(u.bad);
                        err.setText(e instanceof AEADBadTagException ? "Wrong password." : explain(e));
                    }});
                } finally { f.delete(); }
            }});
        }});
        setScreen("restore", "Existing vault", "Step 2 of 3", Ui.BACK, u.scroll(l), logButton());
    }

    // ================================================================ setup 3: self-test on the real account

    final class StepRow { ImageView icon; ProgressBar spin; TextView detail; }

    void showSelfTest() {
        final Cloud c = st.cloud();
        LinearLayout l = u.page();
        boolean setup = !prefs.contains("done_selftest");
        if (setup) u.steps(l, 3, 3);
        u.title(l, "Self-test");
        u.note(l, "Before you trust it with real photos, PhotoVault runs the whole chain once with a test image on your "
                + c.name() + " and removes it afterwards.");
        LinearLayout pics = u.hbox();
        ImageView mine = new ImageView(this), theirs = new ImageView(this);
        String[] caps = {"What you see", "What " + c.name() + " stores"};
        ImageView[] ivs = {mine, theirs};
        for (int i = 0; i < 2; i++) {
            LinearLayout k = u.vbox();
            k.setBackground(Ui.round(u.surface, u.dp(16)));
            k.setPadding(u.dp(8), u.dp(8), u.dp(8), u.dp(8));
            ivs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            ivs[i].setBackground(Ui.round(u.field, u.dp(10)));
            ivs[i].setClipToOutline(true);
            k.addView(ivs[i], new LinearLayout.LayoutParams(MATCH, u.dp(110)));
            u.label(k, caps[i], 13, u.muted, true).setPadding(u.dp(4), u.dp(6), 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i == 1) lp.leftMargin = u.dp(10);
            pics.addView(k, lp);
        }
        l.addView(pics, u.wide(u.dp(12)));
        String[] labels = {
                "Encrypt a test image on this phone",
                "Upload the encrypted PNG to " + c.name(),
                "Stored as a PNG image",
                "Download it back",
                "Every byte kept (SHA-256 identical)",
                "Decrypt: identical to the original",
                c instanceof Amazon ? "Photos don't use your storage quota" : "Storage space",
                "Remove the test file"};
        LinearLayout k = u.card(l);
        StepRow[] rows = new StepRow[labels.length];
        for (int i = 0; i < labels.length; i++) {
            StepRow r = rows[i] = new StepRow();
            LinearLayout row = u.hbox();
            row.setGravity(Gravity.TOP);
            row.setPadding(0, u.dp(7), 0, u.dp(7));
            FrameLayout mark = new FrameLayout(this);
            r.icon = new ImageView(this);
            r.icon.setImageDrawable(new Ui.Icon(Ui.CIRCLE, (u.muted & 0x00FFFFFF) | 0x66000000));
            mark.addView(r.icon, new FrameLayout.LayoutParams(MATCH, MATCH));
            r.spin = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            r.spin.setIndeterminateTintList(ColorStateList.valueOf(u.accent));
            r.spin.setVisibility(View.GONE);
            mark.addView(r.spin, new FrameLayout.LayoutParams(u.dp(20), u.dp(20), Gravity.CENTER));
            row.addView(mark, new LinearLayout.LayoutParams(u.dp(24), u.dp(24)));
            LinearLayout texts = u.vbox();
            texts.setPadding(u.dp(12), u.dp(1), 0, 0);
            u.label(texts, labels[i], 15, u.text, false);
            r.detail = u.label(texts, "", 13, u.muted, false);
            r.detail.setVisibility(View.GONE);
            row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
            k.addView(row);
        }
        LinearLayout actions = u.vbox();
        l.addView(actions);
        setScreen("selftest", "Self-test", setup ? "Step 3 of 3" : c.name(), 0, u.scroll(l));
        runSelfTest(c, rows, mine, theirs, actions);
    }

    /** Any thread. */
    void mark(final StepRow r, final int state, final String detail) {
        post(new Runnable() { public void run() {
            r.spin.setVisibility(state == RUN ? View.VISIBLE : View.GONE);
            r.icon.setVisibility(state == RUN ? View.GONE : View.VISIBLE);
            int color = state == OK ? u.ok : state == WARN ? u.warn : u.bad;
            r.icon.setImageDrawable(new Ui.Icon(state == OK ? Ui.CHECK : state == WARN ? Ui.ALERT : Ui.CLOSE, color));
            if (detail != null) {
                r.detail.setText(detail);
                r.detail.setTextColor(state == OK || state == RUN ? u.muted : color);
                r.detail.setVisibility(View.VISIBLE);
            }
        }});
    }

    /** Marks step `i` and starts the next one. */
    void passed(StepRow[] s, int i, int state, String detail) {
        mark(s[i], state, detail);
        if (i + 1 < s.length) mark(s[i + 1], RUN, null);
    }

    void runSelfTest(final Cloud c, final StepRow[] s, final ImageView mine, final ImageView theirs, final LinearLayout actions) {
        final byte[] k = keyCopy(), salt = st.salt();
        if (k == null) { showUnlock(); return; }
        testing = true;
        mark(s[0], RUN, null);
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
                passed(s, i++, OK, human(orig.length) + " image became a " + human(up.length()) + " PNG in " + (SystemClock.elapsedRealtime() - t) + " ms");

                t = SystemClock.elapsedRealtime();
                JSONObject node = c.upload(up, hex(Vault.random(8)) + ".png", st.folder(), null);
                nodeId = node.getString("id");
                passed(s, i++, OK, (SystemClock.elapsedRealtime() - t) + " ms");

                String type = node.optString("type");
                if (type.startsWith("image/")) passed(s, i++, OK, "type: " + type);
                else passed(s, i++, WARN, c.name() + " reports '" + type + "': check that it counts as a photo");

                t = SystemClock.elapsedRealtime();
                c.download(nodeId, down, null);
                passed(s, i++, OK, human(down.length()) + ", " + (SystemClock.elapsedRealtime() - t) + " ms");

                byte[] a = readFile(up), b = readFile(down);
                String h1 = Vault.sha256(a, 0, a.length), h2 = Vault.sha256(b, 0, b.length);
                if (!h1.equals(h2)) throw new IOException(c.name() + " returned a different file: it recompresses, so this can't work");
                passed(s, i++, OK, h1.substring(0, 16) + "...");

                Vault.Opened o;
                try (InputStream in = new FileInputStream(down)) { o = Vault.decryptPng(in, k); }
                if (!Vault.sha256(o.plain, o.dataOff, o.dataLen()).equals(Vault.sha256(orig, 0, orig.length))) throw new IOException("decrypted data differs");
                final Bitmap noise = BitmapFactory.decodeFile(down.getPath());
                post(new Runnable() { public void run() { theirs.setImageBitmap(noise); } });
                passed(s, i++, OK, "authenticated by AES-GCM, SHA-256 match");

                try {
                    String[] r = c.storage();
                    passed(s, i++, "ok".equals(r[0]) ? OK : WARN, r[1]);
                } catch (Exception e) { passed(s, i++, WARN, "could not read storage use: " + explain(e)); }

                c.trash(Collections.singletonList(nodeId));
                nodeId = null;
                passed(s, i++, OK, "moved to the " + c.trashName());
                prefs.edit().putBoolean("done_selftest", true).apply();
            } catch (final Throwable e) {
                ok = false;
                Journal.add("self-test failed: " + e);
                if (i < s.length) mark(s[i], BAD, explain(e));
                if (nodeId != null) try { c.trash(Collections.singletonList(nodeId)); } catch (Exception ignored) { }
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
                    LinearLayout n = u.notice(actions, u.okSoft);
                    u.label(n, "All checks passed. " + c.name() + " stores your encrypted files bit-for-bit, and only this app can open them.", 15, u.ok, true);
                    if (bioAvailable() && !bioEnabled())
                        u.button(actions, "Turn on fingerprint unlock (recommended)", Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
                    u.button(actions, "Open my vault", Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { openMyVault(); } });
                } else {
                    LinearLayout n = u.notice(actions, u.badSoft);
                    u.label(n, "The test stopped at the step marked with a cross. Nothing personal was uploaded.", 15, u.bad, true);
                    u.button(actions, "Retry", Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
                    u.button(actions, "Sign in to " + c.name() + " again", Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { showSignIn(); } });
                    u.button(actions, "Show the log (to report the problem)", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { showLog(); } });
                }
            }});
        }});
    }

    /** After setup: a vault restored on a new phone starts rebuilding its list from the cloud right away. */
    void openMyVault() {
        if (restored && st.items.isEmpty()) { restored = false; startSync(); }
        showGallery();
    }

    Bitmap testImage() {
        Bitmap b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 640, 400, 0xFF0B7A6C, 0xFF3DD9C1, Shader.TileMode.CLAMP));
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
        LinearLayout l = u.page();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(u.dp(88), u.dp(88));
        hp.topMargin = u.dp(40);
        l.addView(u.badge(Ui.LOCK, u.accent, u.accentSoft, 88), hp);
        u.title(l, "Vault locked").setGravity(Gravity.CENTER);
        u.note(l, "Photos are decrypted only on this phone, only after you unlock.").setGravity(Gravity.CENTER);
        final TextView[] go = new TextView[1];
        final EditText p = u.password(l, "Vault password", new Runnable() { public void run() { if (go[0].isEnabled()) go[0].performClick(); } });
        final TextView err = u.note(l, "");
        err.setGravity(Gravity.CENTER);
        go[0] = u.button(l, "Unlock", Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText("Deriving your key...");
            io.execute(new Runnable() { public void run() {
                byte[] k = null;
                try { k = Vault.deriveKey(pw, st.salt()); } catch (Exception ignored) { }
                openVault(k, new Runnable() { public void run() { btn.setEnabled(true); err.setTextColor(u.bad); err.setText("Wrong password."); } });
            }});
        }});
        if (bioEnabled()) u.button(l, "Use fingerprint", Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { bioUnlock(); } });
        if (st.status != null && st.jobRunning) u.note(l, "In the background: " + st.status).setGravity(Gravity.CENTER);
        setScreen("unlock", "", null, 0, u.scroll(l), logButton());
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
            catch (Exception e) { Journal.add("local list unreadable, use Sync: " + e); l = new Store.Index(); }
            final Store.Index ix = l;
            post(new Runnable() { public void run() {
                st.key = k;
                st.items = ix.items;
                st.folders = ix.folders;
                showGallery();
                if (st.reencrypting()) startJob(SyncService.REENCRYPT); // a password change still being applied
            }});
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
            bioPrompt("Turn on fingerprint unlock", c, new CipherDone() { public void run(Cipher c) throws Exception {
                if (st.key == null) return;
                byte[] ct = c.doFinal(st.key);
                prefs.edit().putString("bio_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                toast("Fingerprint unlock is on");
                if ("settings".equals(screen)) showSettings(); else openMyVault();
            }});
        } catch (Exception e) { toast("Could not turn on fingerprint unlock: " + explain(e)); }
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
            toast("Your fingerprints changed, so fingerprint unlock was reset. Use your password, then turn it on again in Settings.");
            showUnlock();
        } catch (Exception e) { toast("Fingerprint: " + explain(e)); }
    }

    // ================================================================ gallery: folders and items

    /** Top level: folder tiles, then items not in a folder. Inside a folder: its items. Main thread. */
    void rebuildCells() {
        shownItems = st.items;
        shownFolders = st.folders;
        if (!openFolder.isEmpty() && !st.folders.contains(openFolder)) {
            String n = renames.remove(openFolder);
            if (n != null && st.folders.contains(n)) { scrollPos.put(n, scrollPos.remove(openFolder)); openFolder = n; } else openFolder = "";
        }
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

    String gallerySubtitle() {
        if (!openFolder.isEmpty()) return count(cellItems().size(), "item") + " in this folder";
        return count(st.items.size(), "item") + " encrypted on " + cloudName() + (st.folders.isEmpty() ? "" : " • " + count(st.folders.size(), "folder"));
    }

    void showGallery() {
        if (st.key == null) { showUnlock(); return; }
        rebuildCells();
        final boolean selecting = !selected.isEmpty();
        FrameLayout frame = new FrameLayout(this);
        LinearLayout col = u.vbox();
        frame.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));

        // background job status: progress, result, Stop
        LinearLayout b = u.hbox();
        b.setBackground(Ui.round(u.surface, u.dp(16)));
        b.setPadding(u.dp(14), u.dp(6), u.dp(4), u.dp(6));
        FrameLayout mark = new FrameLayout(this);
        bannerSpin = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bannerSpin.setIndeterminateTintList(ColorStateList.valueOf(u.accent));
        mark.addView(bannerSpin, new FrameLayout.LayoutParams(u.dp(20), u.dp(20), Gravity.CENTER));
        bannerIcon = new ImageView(this);
        mark.addView(bannerIcon, new FrameLayout.LayoutParams(u.dp(20), u.dp(20), Gravity.CENTER));
        b.addView(mark, new LinearLayout.LayoutParams(u.dp(22), u.dp(22)));
        bannerText = u.label(null, "", 14, u.text, false);
        bannerText.setPadding(u.dp(12), u.dp(6), u.dp(8), u.dp(6));
        b.addView(bannerText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bannerAction = u.label(b, "", 14, u.accent, true);
        bannerAction.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        bannerAction.setBackground(u.ripple(null, u.dp(12)));
        bannerAction.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (st.jobRunning) new AlertDialog.Builder(MainActivity.this).setMessage("Stop the background job after the current file?")
                    .setPositiveButton("Stop", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { SyncService.stop(MainActivity.this); } })
                    .setNegativeButton("Continue", null).show();
            else { st.status = null; refresh(); }
        }});
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, WRAP);
        bp.setMargins(u.dp(12), 0, u.dp(12), u.dp(8));
        col.addView(b, bp);

        FrameLayout body = new FrameLayout(this);
        cellSize = (getResources().getDisplayMetrics().widthPixels - u.dp(16)) / 3;
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(u.dp(4));
        grid.setVerticalSpacing(u.dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(u.dp(4), 0, u.dp(4), u.dp(96)); // room for the Add button
        grid.setClipToPadding(false);
        grid.setDrawSelectorOnTop(true);
        grid.setSelector(u.ripple(null, u.dp(10)));
        adapter = new BaseAdapter() {
            public int getCount() { return cells.size(); }
            public Object getItem(int p) { return cells.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) { return cell(convert, cells.get(p)); }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) { tap(p); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) { longTap(p); return true; }
        });
        body.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
        View empty = emptyState();
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        grid.setEmptyView(empty);
        col.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        if (!selecting) { // Add: photos and videos, into the folder that is open
            LinearLayout fab = u.hbox();
            fab.setBackground(u.ripple(Ui.round(u.accent, u.dp(28)), u.dp(28)));
            fab.setElevation(u.dp(6));
            fab.setPadding(u.dp(18), u.dp(16), u.dp(24), u.dp(16));
            ImageView plus = new ImageView(this);
            plus.setImageDrawable(new Ui.Icon(Ui.PLUS, u.onAccent));
            fab.addView(plus, new LinearLayout.LayoutParams(u.dp(24), u.dp(24)));
            u.label(fab, "Add", 16, u.onAccent, true).setPadding(u.dp(10), 0, 0, 0);
            fab.setContentDescription("Add photos or videos");
            fab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(); } });
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
            fp.setMargins(0, 0, u.dp(20), u.dp(20));
            frame.addView(fab, fp);
        }

        if (selecting) setScreen("gallery", selected.size() + " selected", "Tap to select more", Ui.CLOSE, frame,
                u.iconButton(Ui.MOVE, u.text, "Move to folder", new View.OnClickListener() { public void onClick(View v) { moveSelected(); } }),
                u.iconButton(Ui.TRASH, u.text, "Delete", new View.OnClickListener() { public void onClick(View v) { confirmDeleteSelected(); } }));
        else setScreen("gallery", openFolder.isEmpty() ? "PhotoVault" : openFolder, gallerySubtitle(), openFolder.isEmpty() ? 0 : Ui.BACK, frame,
                u.iconButton(Ui.LOCK, u.text, "Lock now", new View.OnClickListener() { public void onClick(View v) { st.lock(); st.changed(); } }),
                menuButton());
        banner = b;
        restoreScroll();
        refresh();
    }

    /** Shown instead of the grid when there is nothing in it. */
    View emptyState() {
        LinearLayout l = u.vbox();
        l.setGravity(Gravity.CENTER);
        l.setPadding(u.dp(32), u.dp(24), u.dp(32), u.dp(96));
        boolean top = openFolder.isEmpty();
        l.addView(u.badge(top ? Ui.IMAGE : Ui.FOLDER, u.accent, u.accentSoft, 72), new LinearLayout.LayoutParams(u.dp(72), u.dp(72)));
        u.heading(l, top ? "Your vault is empty" : "This folder is empty").setPadding(0, u.dp(16), 0, u.dp(4));
        u.note(l, top ? "Photos and videos you add are encrypted on this phone, then uploaded to " + cloudName()
                        + " as noise. Uploads continue in the background."
                : "Add photos straight into it, or go back, long-press photos to select them and tap Move.").setGravity(Gravity.CENTER);
        u.button(l, top ? "Add photos or videos" : "Add to this folder", Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { pick(); } });
        if (top) u.button(l, "New phone? Restore from " + cloudName(), Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { startSync(); } });
        ScrollView s = u.scroll(l);
        return s;
    }

    View cell(View convert, Object o) {
        FrameLayout f = (FrameLayout) convert;
        if (f == null) {
            f = new FrameLayout(this);
            f.setLayoutParams(new AbsListView.LayoutParams(MATCH, cellSize));
            f.setBackground(Ui.round(u.field, u.dp(10)));
            f.setClipToOutline(true);
            ImageView iv = new ImageView(this);
            f.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
            TextView label = u.label(null, "", 14, Color.WHITE, true);
            label.setGravity(Gravity.BOTTOM);
            label.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(8));
            label.setMaxLines(3);
            label.setEllipsize(TextUtils.TruncateAt.END);
            f.addView(label, new FrameLayout.LayoutParams(MATCH, MATCH));
            ImageView mark = new ImageView(this);
            mark.setPadding(u.dp(4), u.dp(4), u.dp(4), u.dp(4));
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(u.dp(26), u.dp(26), Gravity.TOP | Gravity.END);
            mp.setMargins(0, u.dp(6), u.dp(6), 0);
            f.addView(mark, mp);
        }
        ImageView iv = (ImageView) f.getChildAt(0);
        TextView label = (TextView) f.getChildAt(1);
        ImageView mark = (ImageView) f.getChildAt(2);
        Store.Item it;
        if (o instanceof String) { // folder tile: cover, name and count on a dark gradient, folder mark
            String name = (String) o;
            Integer n = counts.get(name);
            SpannableString s = new SpannableString(name + "\n" + count(n == null ? 0 : n, "item"));
            s.setSpan(new RelativeSizeSpan(0.85f), name.length() + 1, s.length(), 0);
            label.setText(s);
            label.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0x22000000, 0xB0000000}));
            mark.setImageDrawable(new Ui.Icon(Ui.FOLDER, Color.WHITE));
            mark.setBackground(u.oval(0x66000000));
            mark.setVisibility(View.VISIBLE);
            it = covers.get(name);
            f.setForeground(null);
        } else {
            it = (Store.Item) o;
            label.setText(null);
            label.setBackground(null);
            boolean on = selected.contains(it.id);
            if (selected.isEmpty()) mark.setVisibility(View.GONE);
            else {
                mark.setVisibility(View.VISIBLE);
                mark.setImageDrawable(on ? new Ui.Icon(Ui.CHECK, u.onAccent) : null);
                if (on) mark.setBackground(u.oval(u.accent));
                else {
                    GradientDrawable ring = u.oval(0x33000000);
                    ring.setStroke(u.dp(2), Color.WHITE);
                    mark.setBackground(ring);
                }
            }
            if (on) {
                GradientDrawable g = Ui.round((u.accent & 0x00FFFFFF) | 0x40000000, u.dp(10));
                g.setStroke(u.dp(3), u.accent);
                f.setForeground(g);
            } else f.setForeground(null);
        }
        String id = it == null ? null : it.id;
        iv.setTag(id);
        if (id == null) { // empty folder
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(cellSize / 4, cellSize / 5, cellSize / 4, cellSize / 3);
            iv.setImageDrawable(new Ui.Icon(Ui.FOLDER, u.accent));
            return f;
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setPadding(0, 0, 0, 0);
        Bitmap bm = st.thumbs.get(id);
        iv.setImageBitmap(bm);
        if (bm == null) loadThumb(id, iv);
        return f;
    }

    void tap(int p) {
        Object o = cells.get(p);
        if (o instanceof String) {
            if (!selected.isEmpty()) { toast("Tap the folder icon at the top to move the selected items."); return; }
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
        box.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
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

    /** Changes the local list in the background, then saves the folders (encrypted) to the cloud. */
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
            if (old.equals(openFolder)) renames.put(old, name);
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
        final Set<String> ids = new HashSet<>();
        for (String id : selected) ids.add(st.currentId(id));
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
                .setMessage("The encrypted files go to your " + st.cloud().trashName() + " and disappear from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { deleteItems(ids); } })
                .setNegativeButton("Cancel", null).show();
    }

    /** Moves items to the cloud's trash, then removes them from the list and their local previews. */
    void deleteItems(List<String> requested) {
        final Map<String, List<String>> files = new LinkedHashMap<>(); // item id -> its files in the cloud (big files: parts)
        for (String r : requested) {
            String id = st.currentId(r); // replaced meanwhile by a password change
            List<String> nodes = Collections.singletonList(id);
            for (Store.Item it : st.items) if (it.id.equals(id)) nodes = new ArrayList<>(it.nodes());
            Collections.reverse(nodes = new ArrayList<>(nodes)); // part 0 last: a half-finished delete stays visible
            files.put(id, nodes);
        }
        final byte[] k = keyCopy();
        if (k == null) return;
        final Cloud cloud = st.cloud();
        viewIo.execute(new Runnable() { public void run() {
            final Set<String> gone = new HashSet<>();
            try {
                List<String> batch = new ArrayList<>(), waiting = new ArrayList<>();
                for (Map.Entry<String, List<String>> e : files.entrySet()) {
                    for (String n : e.getValue()) {
                        batch.add(n);
                        if (batch.size() == 50) { cloud.trashAll(batch); batch.clear(); gone.addAll(waiting); waiting.clear(); }
                    }
                    if (batch.isEmpty()) gone.add(e.getKey()); else waiting.add(e.getKey());
                }
                if (!batch.isEmpty()) cloud.trashAll(batch);
                gone.addAll(waiting);
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

    void startSync() { startJob(SyncService.SYNC); }

    void startJob(String action) {
        try { SyncService.start(this, action, null, ""); }
        catch (Exception e) { toast("Could not start the background job: " + explain(e)); }
    }

    // ================================================================ viewer

    /** Horizontal swipe in the viewer: next / previous item of the folder it was opened from. */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if ("viewer".equals(screen)) swipe.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    void swipeTo(int d) {
        int i = viewIndex + d;
        if (i < 0 || i >= viewList.size()) return;
        viewIndex = i;
        showViewer(viewList.get(i));
    }

    void showViewer(final Store.Item it) {
        LinearLayout l = u.vbox();
        final FrameLayout box = new FrameLayout(this);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        l.addView(box, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        final TextView info = u.label(l, "Preview. Downloading the encrypted original...", 13, 0xB3FFFFFF, false);
        info.setPadding(u.dp(20), u.dp(10), u.dp(20), u.dp(4));
        LinearLayout row = u.hbox();
        row.setPadding(u.dp(8), u.dp(4), u.dp(8), u.dp(8));
        final LinearLayout cloudView = u.action(row, Ui.EYE, "Cloud view", Color.WHITE, null);
        final LinearLayout save = u.action(row, Ui.SAVE, "Save to phone", Color.WHITE, null);
        u.action(row, Ui.TRASH, "Delete", Color.WHITE, new View.OnClickListener() { public void onClick(View v) { confirmDelete(it); } });
        cloudView.setEnabled(false);
        save.setEnabled(false);
        l.addView(row, u.wide(0));
        setScreen("viewer", it.name, (viewList.size() > 1 ? (viewIndex + 1) + " of " + viewList.size() + " • " : "")
                + date(it.taken) + " • " + human(it.size), Ui.BACK, l);
        lastViewedId = it.id;
        final int token = viewToken;

        // 1) instant: the small preview kept (encrypted) on the phone
        Bitmap pre = st.thumbs.get(it.id);
        if (pre != null) iv.setImageBitmap(pre);
        else loadThumb(it.id, iv);
        iv.setTag(it.id);

        // 2) full quality: download (or reuse the cached encrypted PNG), decrypt, swap in
        final byte[] k = keyCopy();
        final File png = st.blobFile(it.id); // for a big file: its part 0
        final Cloud cloud = st.cloud();
        viewIo.execute(new Runnable() { public void run() {
            File f = null;
            try {
                if (token != viewToken) return;
                String ext = it.name.contains(".") ? it.name.substring(it.name.lastIndexOf('.')) : it.video() ? ".mp4" : "";
                final Vault.Opened o;  // small file: decrypted in memory
                final File whole;      // big file, or video: decrypted file in app-private storage, deleted on close
                final String line;
                long t = SystemClock.elapsedRealtime();
                if (!it.parts.isEmpty()) {
                    f = new File(getCacheDir(), "play-" + it.id + ext);
                    st.assemble(it, k, f, new Cloud.Progress() {
                        public void on(final long d, final long total) {
                            if (token != viewToken) throw new CancellationException(); // viewer closed: stop downloading
                            post(new Runnable() { public void run() {
                                if (token == viewToken) info.setText("Preview. Downloading and decrypting part " + (d + 1) + " of " + total + "...");
                            }});
                        }
                    });
                    st.trimBlobCache();
                    o = null;
                    whole = f;
                    line = "Decrypted and verified: " + it.parts.size() + " parts, each checked by AES-GCM. "
                            + human(it.size) + " original, stored on " + cloud.name() + " as " + it.parts.size() + " PNGs of noise.";
                } else {
                    if (!png.exists()) {
                        cloud.download(it.id, png, new Cloud.Progress() {
                            public void on(final long d, final long total) {
                                if (token != viewToken) throw new CancellationException();
                                post(new Runnable() { public void run() {
                                    if (token == viewToken) info.setText("Preview. Downloading the encrypted original... " + (total > 0 ? 100 * d / total + "%" : human(d)));
                                }});
                            }
                        });
                        st.trimBlobCache();
                    } else png.setLastModified(System.currentTimeMillis());
                    if (token != viewToken) return;
                    post(new Runnable() { public void run() { if (token == viewToken) info.setText("Preview. Decrypting..."); } });
                    o = st.decrypt(png, k);
                    line = "Decrypted and verified in " + (SystemClock.elapsedRealtime() - t) + " ms (AES-GCM: not a single bit changed). "
                            + human(o.dataLen()) + " original, stored on " + cloud.name() + " as a " + human(png.length()) + " PNG of noise.";
                    if (it.video()) {
                        f = new File(getCacheDir(), "play-" + it.id + ext);
                        try (OutputStream out = new FileOutputStream(f)) { out.write(o.plain, o.dataOff, o.dataLen()); }
                    }
                    whole = f;
                }
                if (token != viewToken) { if (whole != null) whole.delete(); return; } // closed meanwhile
                if (it.video()) {
                    post(new Runnable() { public void run() {
                        if (token != viewToken) { whole.delete(); return; }
                        deletePlaying();
                        playing = whole;
                        VideoView vv = new VideoView(MainActivity.this);
                        MediaController mc = new MediaController(MainActivity.this);
                        mc.setAnchorView(vv);
                        vv.setMediaController(mc);
                        vv.setVideoPath(whole.getPath());
                        box.addView(vv, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
                        box.removeView(iv);
                        vv.start();
                        ready(it, o, whole, line, info, box, png, cloudView, save);
                    }});
                } else {
                    ImageDecoder.Source src = whole != null ? ImageDecoder.createSource(whole)
                            : ImageDecoder.createSource(ByteBuffer.wrap(o.plain, o.dataOff, o.dataLen()).slice());
                    final Bitmap bm = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                        public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo inf, ImageDecoder.Source s) {
                            Size z = inf.getSize();
                            d.setTargetSampleSize(Math.max(1, Math.max(z.getWidth(), z.getHeight()) / 4096 + 1));
                        }
                    });
                    if (token != viewToken) { if (whole != null) whole.delete(); return; }
                    post(new Runnable() { public void run() {
                        if (token != viewToken) { if (whole != null) whole.delete(); return; }
                        if (whole != null) { deletePlaying(); playing = whole; }
                        iv.setTag(null); // a late preview must not replace the full image
                        iv.setImageBitmap(bm);
                        ready(it, o, whole, line, info, box, png, cloudView, save);
                    }});
                }
            } catch (final Throwable e) {
                if (f != null) f.delete();
                if (e instanceof CancellationException) return;
                Journal.add("view failed: " + e);
                png.delete();
                post(new Runnable() { public void run() { if (token == viewToken) { info.setText("Error: " + explain(e)); info.setTextColor(0xFFFF9A93); } } });
                if (isAuth(e)) post(new Runnable() { public void run() { askRelogin(); } });
            } finally { Arrays.fill(k, (byte) 0); }
        }});
    }

    void ready(final Store.Item it, final Vault.Opened o, final File whole, String line, TextView info, final FrameLayout box,
               final File png, final LinearLayout cloudView, LinearLayout save) {
        info.setText(line);
        cloudView.setEnabled(png.exists());
        save.setEnabled(true);
        final TextView cloudLabel = (TextView) cloudView.getChildAt(1);
        cloudView.setOnClickListener(new View.OnClickListener() {
            ImageView noise;
            public void onClick(View v) {
                if (noise != null) { box.removeView(noise); noise = null; cloudLabel.setText("Cloud view"); return; }
                final ImageView n = noise = new ImageView(MainActivity.this);
                n.setBackgroundColor(Color.BLACK);
                n.setScaleType(ImageView.ScaleType.FIT_CENTER);
                box.addView(n, new FrameLayout.LayoutParams(MATCH, MATCH));
                cloudLabel.setText("My photo");
                viewIo.execute(new Runnable() { public void run() {
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inSampleSize = png.length() > (8 << 20) ? 4 : 1;
                    final Bitmap b = BitmapFactory.decodeFile(png.getPath(), op);
                    post(new Runnable() { public void run() { n.setImageBitmap(b); } });
                }});
            }
        });
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveToPhone(it, o, whole); } });
    }

    /** Writes a decrypted copy to the phone's gallery: from memory (small files) or from the decrypted file. */
    void saveToPhone(final Store.Item it, final Vault.Opened o, final File whole) {
        viewIo.execute(new Runnable() { public void run() {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (it.video() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/PhotoVault");
                v.put(MediaStore.MediaColumns.DATE_TAKEN, it.taken);
                Uri x = getContentResolver().insert(it.video() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (x == null) throw new IOException("gallery refused the file");
                try (OutputStream out = getContentResolver().openOutputStream(x)) {
                    if (whole != null) try (InputStream in = new FileInputStream(whole)) { Cloud.copy(in, out, -1, null); }
                    else out.write(o.plain, o.dataOff, o.dataLen());
                } catch (Exception e) { getContentResolver().delete(x, null, null); throw e; } // no empty file in the gallery
                post(new Runnable() { public void run() { toast("Saved (unencrypted) to " + (it.video() ? "Movies" : "Pictures") + "/PhotoVault"); } });
            } catch (final Exception e) { post(new Runnable() { public void run() { toast("Save failed: " + explain(e)); } }); }
        }});
    }

    void confirmDelete(final Store.Item it) {
        new AlertDialog.Builder(this).setTitle("Delete from the vault?")
                .setMessage("The encrypted file goes to your " + st.cloud().trashName() + " and disappears from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    deleteItems(Collections.singletonList(it.id));
                }})
                .setNegativeButton("Cancel", null).show();
    }

    void closeViewer() { viewToken++; deletePlaying(); }

    /** Decrypted videos exist on disk only while you watch them (app-private cache). */
    void deletePlaying() { if (playing != null) { playing.delete(); playing = null; } }

    // ================================================================ settings

    void showSettings() {
        LinearLayout l = u.page();
        String fp = "";
        try { fp = Vault.sha256(st.key, 0, st.key.length).substring(0, 16); } catch (Exception ignored) { }
        LinearLayout top = u.card(l);
        LinearLayout head = u.hbox();
        head.addView(u.badge(Ui.SHIELD, u.accent, u.accentSoft, 48), new LinearLayout.LayoutParams(u.dp(48), u.dp(48)));
        LinearLayout t = u.vbox();
        t.setPadding(u.dp(14), 0, 0, 0);
        u.label(t, count(st.items.size(), "item") + " on " + cloudName(), 18, u.text, true);
        u.label(t, "Encrypted with your key • fingerprint " + fp, 13, u.muted, false);
        u.label(t, "Locks itself 60 s after you leave the app", 13, u.muted, false);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(head);

        u.section(l, "SECURITY");
        LinearLayout sec = u.card(l);
        sec.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        if (st.reencrypting()) {
            int left = 0;
            String cur = prefs.getString("salt", "");
            for (Store.Item it : st.items) if (!cur.equals(it.salt)) left++;
            u.row(sec, Ui.KEY, "Password change in progress", count(left, "file") + " still use the old password. Tap to continue now.", null,
                    new View.OnClickListener() { public void onClick(View v) { startJob(SyncService.REENCRYPT); showGallery(); } });
        } else u.row(sec, Ui.KEY, "Change vault password", "Every file is encrypted again with the new key", null,
                new View.OnClickListener() { public void onClick(View v) { showChangePassword(); } });
        if (bioEnabled() || bioAvailable())
            u.row(sec, Ui.FINGER, "Fingerprint unlock", bioEnabled() ? "On" : "Off", u.toggle(bioEnabled()), new View.OnClickListener() { public void onClick(View v) {
                if (bioEnabled()) { disableBio(); showSettings(); } else enableBio();
            }});
        u.row(sec, Ui.EYE, "Allow screenshots", "Until the app is closed. Off: the app is also blank in recent apps", u.toggle(st.allowScreenshots),
                new View.OnClickListener() { public void onClick(View v) {
                    st.allowScreenshots = !st.allowScreenshots;
                    if (st.allowScreenshots) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    showSettings();
                }});
        u.row(sec, Ui.LOCK, "Lock now", null, null, new View.OnClickListener() { public void onClick(View v) { st.lock(); st.changed(); } });

        u.section(l, "STORAGE");
        LinearLayout sto = u.card(l);
        sto.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        u.row(sto, Ui.SYNC, "Sync from " + cloudName(), "Restores items and folders, drops items deleted there", null,
                new View.OnClickListener() { public void onClick(View v) { startSync(); showGallery(); } });
        u.row(sto, Ui.CHECK, "Run the self-test", "Checks the whole chain with a test image", null,
                new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
        u.row(sto, Ui.CLOUD, "Sign out of " + cloudName(), "Your vault and password are not affected", null, new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this).setTitle("Sign out of " + cloudName() + "?")
                    .setMessage("Your vault, password and files stay as they are. You sign in again to keep using the vault.")
                    .setPositiveButton("Sign out", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { signOut(); } })
                    .setNegativeButton("Cancel", null).show();
        }});

        u.section(l, "ABOUT");
        LinearLayout ab = u.card(l);
        ab.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        u.row(ab, Ui.SHIELD, "How your photos are protected", "Where your data is, cryptography, recovery", null,
                new View.OnClickListener() { public void onClick(View v) { showAbout(); } });
        u.row(ab, Ui.LIST, "Log", "Every request, never keys, passwords or file names", null,
                new View.OnClickListener() { public void onClick(View v) { showLog(); } });
        if (!Config.DONATE_URL.isEmpty())
            u.row(ab, Ui.HEART, "Support PhotoVault", "No ads, no tracking: funded by voluntary donations", null,
                    new View.OnClickListener() { public void onClick(View v) { openUrl(Config.DONATE_URL); } });
        if (!Config.SOURCE_URL.isEmpty())
            u.row(ab, Ui.CODE, "Source code", "Free and open source, GPL-3.0", null,
                    new View.OnClickListener() { public void onClick(View v) { openUrl(Config.SOURCE_URL); } });
        TextView foot = u.note(l, "PhotoVault " + version() + ". Not affiliated with Amazon or Microsoft; Amazon Photos and OneDrive "
                + "are trademarks of their owners.");
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(u.dp(16), u.dp(20), u.dp(16), 0);
        setScreen("settings", "Settings", null, Ui.BACK, u.scroll(l));
    }

    void showAbout() {
        LinearLayout l = u.page();
        Cloud c = st.cloud();
        about(l, "Our promise", "• No ads. No trackers, no analytics, no crash reporting.\n"
                + "• No servers and no accounts of ours: the app talks only to " + c.name() + ", only with encrypted files.\n"
                + "• Nothing is collected, so nothing can be sold or leaked by us.\n"
                + "• Free and open source (GPL-3.0): anyone can check what the app does.\n"
                + "• Funded only by voluntary donations. Donating unlocks nothing: every feature is free for everyone.");
        about(l, "Where your data is", "• " + c.name() + ": " + vaultPlace() + ", " + count(st.items.size(), "encrypted PNG")
                + " (big files are several). They also show up in " + c.name() + "'s own app as images of static: that's them, don't delete them there.\n"
                + "• Folders: their names and which item is in which are saved there as one more encrypted PNG (subfolder \"index\"), "
                + "so a new phone gets them back with Sync. " + c.name() + " can't read the folder names.\n"
                + "• This phone: the list of items and small previews, both encrypted with your vault key, in app-private storage, "
                + "excluded from backups and phone-to-phone transfers.\n"
                + (c instanceof OneDrive ? "• Sign-in: a Microsoft token, stored encrypted with a key in this phone's secure hardware.\n"
                   : "• Sign-in: the Amazon session cookies of the sign-in page, in app-private storage.\n")
                + "• Your key: only in memory while unlocked" + (bioEnabled()
                ? "; also stored wrapped by a fingerprint-protected key in the phone's secure hardware (Android Keystore)" : "")
                + ". The app locks itself 60 s after you leave it. A running background upload keeps its own copy of the key until it finishes.");
        about(l, "Cryptography", "• Key = PBKDF2-HMAC-SHA256(password, random 16-byte salt, 600 000 iterations).\n"
                + "• Each file: AES-256-GCM with a fresh random nonce. The GCM tag rejects any modified bit.\n"
                + "• Name, date and EXIF are inside the encrypted part.\n"
                + "• Only standard algorithms from Android's built-in crypto library, and no third-party libraries at all.\n"
                + "• Vault salt: " + prefs.getString("salt", ""));
        about(l, "Recovery without this app", "Download the PNGs of the vault folder from the " + c.name() + " website, then on a PC:\n"
                + "python photovault.py dec <folder> -o out\n(photovault.py is part of PhotoVault's open-source code on GitHub.) "
                + "If you lose the phone: install the app, sign in to the same account, enter the same password; the app restores your vault.");
        about(l, "Limits of this version", "• Files over 32 MB are stored as several encrypted parts; a big video is downloaded completely before it plays.\n"
                + "• Background jobs are limited by Android to 6 hours per day.\n"
                + (c instanceof Amazon ? "• Amazon has no public API: PhotoVault uses the same web interface as the Amazon Photos website. "
                   + "If Amazon changes it, uploads can stop until the app is updated; stored files stay decryptable, also with photovault.py."
                   : "• The vault counts against your OneDrive storage: each PNG is about as big as the original file."));
        setScreen("about", "How it's protected", null, Ui.BACK, u.scroll(l));
    }

    void about(LinearLayout l, String title, String text) {
        LinearLayout k = u.card(l);
        u.heading(k, title);
        u.body(k, text);
    }

    // ================================================================ change password

    static String duration(long s) {
        if (s < 90) return "about a minute";
        if (s < 90 * 60) return "about " + Math.round(s / 60.0) + " minutes";
        return "about " + Math.round(s / 3600.0) + " hours";
    }

    void showChangePassword() {
        LinearLayout l = u.page();
        Cloud cloud = st.cloud();
        u.title(l, "Change vault password");
        long bytes = 0;
        for (Store.Item it : st.items) bytes += it.size;
        int n = st.items.size();
        if (n == 0) u.note(l, "Your vault is empty, so the change is instant.");
        else {
            long secs = 2 * bytes * 8 / 50_000_000L + n * 3L / 2; // 50 Mbit/s both ways + about 1.5 s of requests per file
            LinearLayout w = u.notice(l, u.warnSoft);
            LinearLayout head = u.hbox();
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new Ui.Icon(Ui.ALERT, u.warn));
            head.addView(ic, new LinearLayout.LayoutParams(u.dp(22), u.dp(22)));
            u.label(head, "Before you start: what it costs", 16, u.text, true).setPadding(u.dp(10), 0, 0, 0);
            w.addView(head);
            u.body(w, "The key comes from the password, so every file has to be encrypted again with the new key:\n"
                    + "• All " + count(n, "item") + " (" + human(bytes) + ") are downloaded, re-encrypted on this phone and uploaded again: "
                    + human(bytes) + " of download and " + human(bytes) + " of upload. On Wi-Fi at 50 Mbit/s that is " + duration(secs)
                    + ". Use Wi-Fi and keep the phone charging.\n"
                    + "• It runs in the background with a notification. Android allows background transfers up to 6 hours a day, "
                    + "so a big vault may need more than one day: it resumes by itself every time you unlock PhotoVault.\n"
                    + "• Until it has finished, the files not re-encrypted yet still open with the OLD password. At the end the old "
                    + "password opens nothing in your vault, except copies someone may already have downloaded.\n"
                    + "• The old copies go to the " + cloud.trashName() + ". "
                    + (cloud instanceof Amazon ? "Photos don't use your storage; videos do, and may keep counting until Amazon empties the trash.\n"
                       : "They keep counting against your OneDrive storage until the recycle bin is emptied, so OneDrive needs about as much free space as the vault uses.\n")
                    + "• New uploads wait until the re-encryption has finished (or tap Stop in its notification).\n"
                    + "• Fingerprint unlock is switched off; you can switch it on again right after.\n"
                    + "• Don't set up PhotoVault on another phone until it has finished.");
        }
        LinearLayout k = u.card(l);
        final EditText cur = u.password(k, "Current password", null);
        final EditText p1 = u.password(k, "New password", null);
        u.strength(p1, k);
        final EditText p2 = u.password(k, "Repeat new password", null);
        final CheckBox ok = u.check(k, "I saved the new password somewhere safe. It cannot be recovered.");
        final TextView err = u.note(l, "");
        final TextView go = u.button(l, n == 0 ? "Change password" : "Change password and re-encrypt", Ui.PRIMARY, null);
        String blocked = prefs.getBoolean("restore_pending", false) ? "Finish restoring your vault first: Settings > Sync from " + cloud.name() + "."
                : st.busyJobs > 0 || st.jobRunning ? "Wait until the current upload or sync has finished." : null;
        if (blocked != null) { go.setEnabled(false); err.setTextColor(u.bad); err.setText(blocked); }
        go.setOnClickListener(new View.OnClickListener() { public void onClick(final View btn) {
            final String c = cur.getText().toString(), a = p1.getText().toString(), b = p2.getText().toString();
            err.setTextColor(u.bad);
            if (a.length() < 10) { err.setText("Use at least 10 characters for the new password."); return; }
            if (!a.equals(b)) { err.setText("The two new passwords don't match."); return; }
            if (a.equals(c)) { err.setText("The new password is the same as the current one."); return; }
            if (!ok.isChecked()) { err.setText("Please confirm you saved the new password."); return; }
            if (st.busyJobs > 0 || st.jobRunning) { err.setText("Wait until the current upload or sync has finished."); return; }
            final byte[] current = keyCopy();
            if (current == null) return;
            final boolean hadBio = bioEnabled();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText("Checking the current password and deriving the new key...");
            io.execute(new Runnable() { public void run() {
                byte[] old = null, k = null;
                try {
                    old = Vault.deriveKey(c, st.salt());
                    if (!Arrays.equals(old, current)) throw new AEADBadTagException("wrong current password");
                    final byte[] salt = Vault.random(16);
                    k = Vault.deriveKey(a, salt);
                    st.changePassword(old, k, salt, Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP));
                    st.backupFolders(k); // the folders file in the cloud is the first thing another phone opens: new key right away
                    Journal.add("password changed; re-encrypting the files in the cloud");
                    post(new Runnable() { public void run() { // Store has already switched the open vault to the new key
                        disableBio();
                        startJob(SyncService.REENCRYPT);
                        toast("Password changed." + (st.items.isEmpty() ? "" : " Re-encryption runs in the background."));
                        showSettings();
                        if (hadBio && bioAvailable()) enableBio();
                    }});
                } catch (final Throwable e) {
                    post(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setTextColor(u.bad);
                        err.setText(e instanceof AEADBadTagException ? "The current password is wrong." : explain(e));
                    }});
                } finally {
                    Arrays.fill(current, (byte) 0);
                    if (old != null) Arrays.fill(old, (byte) 0);
                    if (k != null) Arrays.fill(k, (byte) 0);
                }
            }});
        }});
        setScreen("changepw", "Password", null, Ui.BACK, u.scroll(l));
    }

    // ================================================================ log

    void showLog() {
        LinearLayout l = u.page();
        u.note(l, "Every request to the cloud is listed here (method, path, result). Cookies, tokens, passwords, keys and file names are never logged.");
        LinearLayout k = u.card(l);
        final TextView t = u.label(k, Journal.text(), 12, u.text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        u.button(l, "Copy log", Ui.TONAL, new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PhotoVault log", Journal.text()));
            toast("Log copied");
        }});
        setScreen("log", "Log", null, Ui.BACK, u.scroll(l));
    }
}
