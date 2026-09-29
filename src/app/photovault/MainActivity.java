package app.photovault;

import static app.photovault.Store.hex;
import static app.photovault.Store.human;
import static app.photovault.Store.isAuth;
import static app.photovault.Store.readFile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
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
    static final int REQ_PICK = 1, REQ_NOTIF = 2, RUN = 1, OK = 2, WARN = 3, BAD = 4, INFO_TEXT = 0xB3FFFFFF, INFO_ERROR = 0xFFFF9A93;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final List<String> VAULT_SCREENS = Arrays.asList("gallery", "viewer", "settings", "about", "changepw");
    /** App languages ("" = the phone's). Also in res/xml/locales_config.xml. */
    static final String[] LANGS = {"", "en", "it", "es", "de", "fr", "pt"};

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
    boolean picking, connecting, testing, started;
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
        String again = st.reopen; // the screen was restarted by a language or theme change: show it again
        st.reopen = null;
        if (!prefs.contains("verifier")) showWelcome();
        else if (st.key == null) showUnlock();
        else if ("settings".equals(again)) showSettings();
        else if ("about".equals(again)) showAbout();
        else if ("changepw".equals(again)) showChangePassword();
        else if ("log".equals(again)) showLog();
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
        if (isChangingConfigurations() && st.reopen == null) st.reopen = screen;
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
                else titleView.setText(q(R.plurals.selected, selected.size()));
            }
            banner.setVisibility(st.status == null ? View.GONE : View.VISIBLE);
            if (st.status != null) {
                bannerText.setText(st.status);
                bannerSpin.setVisibility(st.jobRunning ? View.VISIBLE : View.GONE);
                bannerIcon.setVisibility(st.jobRunning ? View.GONE : View.VISIBLE);
                bannerIcon.setImageDrawable(new Ui.Icon(st.statusBad ? Ui.ALERT : Ui.CHECK, st.statusBad ? u.warn : u.ok));
                bannerAction.setText(st.jobRunning ? R.string.stop : R.string.ok);
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
        if (left != 0) bar.addView(u.iconButton(left, fg, s(left == Ui.CLOSE ? R.string.cancel : R.string.back), new View.OnClickListener() { public void onClick(View v) { back(); } }));
        LinearLayout texts = u.vbox();
        texts.setPadding(left != 0 ? u.dp(6) : 0, 0, u.dp(8), 0);
        titleView = u.label(texts, title, 21, fg, true);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subtitleView = u.label(texts, subtitle, 13, dark ? INFO_TEXT : u.muted, false);
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

    View menuButton() { return u.iconButton(Ui.MORE, u.text, s(R.string.menu), new View.OnClickListener() { public void onClick(View v) { menu(v); } }); }

    View logButton() { return u.iconButton(Ui.LIST, u.text, s(R.string.log), new View.OnClickListener() { public void onClick(View v) { showLog(); } }); }

    void menu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        Menu m = p.getMenu();
        if (openFolder.isEmpty()) m.add(0, 1, 0, R.string.menu_new_folder);
        else { m.add(0, 2, 0, R.string.menu_rename_folder); m.add(0, 3, 0, R.string.menu_delete_folder); }
        m.add(0, 4, 0, s(R.string.menu_sync, cloudName()));
        m.add(0, 5, 0, R.string.settings);
        if (!Config.DONATE_URL.isEmpty()) m.add(0, 6, 0, R.string.menu_support);
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

    /** A text of the app in the current language. */
    String s(int id, Object... args) { return args.length == 0 ? getString(id) : getString(id, args); }

    /** A text that depends on a number (1 item, 2 items); `count` is also the first placeholder. */
    String q(int id, int count, Object... more) {
        Object[] a = new Object[more.length + 1];
        a[0] = count;
        System.arraycopy(more, 0, a, 1, more.length);
        return getResources().getQuantityString(id, count, a);
    }

    String explain(Throwable e) { return st.explain(e); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void post(final Runnable r) { ui.post(new Runnable() { public void run() { if (!isDestroyed()) r.run(); } }); }

    static String date(long ms) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms)); }

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast(s(R.string.no_browser, url)); }
    }

    byte[] keyCopy() { return st.key == null ? null : st.key.clone(); }

    String cloudName() { return st.cloud().name(); }

    /** Where the vault is, in words. */
    String vaultPlace() {
        Cloud c = st.cloud();
        if (c instanceof OneDrive) return s(((OneDrive) c).allFiles() ? R.string.place_od_all : R.string.place_od_app);
        return s(R.string.place_amazon);
    }

    void askRelogin() {
        new AlertDialog.Builder(this).setTitle(s(R.string.relogin_title, cloudName()))
                .setMessage(s(R.string.relogin_msg, cloudName()))
                .setPositiveButton(R.string.sign_in, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { showSignIn(); } })
                .setNegativeButton(R.string.later, null).show();
    }

    // ---------------------------------------------------------------- language

    String currentLanguage() {
        LocaleList l = getSystemService(LocaleManager.class).getApplicationLocales();
        return l.isEmpty() ? "" : l.get(0).getLanguage();
    }

    /** "Italiano", "Deutsch"...: each language in its own words. */
    String languageName(String tag) {
        if (tag.isEmpty()) return s(R.string.lang_system);
        Locale l = Locale.forLanguageTag(tag);
        String n = l.getDisplayLanguage(l);
        return n.substring(0, 1).toUpperCase(l) + n.substring(1);
    }

    /** Android's per-app language (the same setting as in the phone's app settings). The screen restarts in the new language. */
    void chooseLanguage() {
        final String cur = currentLanguage();
        String[] names = new String[LANGS.length];
        int checked = 0;
        for (int i = 0; i < LANGS.length; i++) { names[i] = languageName(LANGS[i]); if (LANGS[i].equals(cur)) checked = i; }
        new AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    d.dismiss();
                    if (LANGS[w].equals(cur)) return;
                    st.reopen = screen;
                    getSystemService(LocaleManager.class).setApplicationLocales(LANGS[w].isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(LANGS[w]));
                }})
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ================================================================ setup: welcome, choice of storage

    void showWelcome() {
        LinearLayout l = u.page();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView hero = u.badge(Ui.LOCK, u.onAccent, u.accent, 88);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(u.dp(88), u.dp(88));
        hp.topMargin = u.dp(16);
        l.addView(hero, hp);
        u.title(l, "Cloakroll").setGravity(Gravity.CENTER);
        u.note(l, s(R.string.welcome_tagline)).setGravity(Gravity.CENTER);
        LinearLayout k = u.card(l);
        u.feature(k, Ui.LOCK, s(R.string.f_encrypt_t), s(R.string.f_encrypt_d));
        u.feature(k, Ui.NOISE, s(R.string.f_noise_t), s(R.string.f_noise_d));
        u.feature(k, Ui.SHIELD, s(R.string.f_free_t), s(R.string.f_free_d));
        u.feature(k, Ui.KEY, s(R.string.f_key_t), s(R.string.f_key_d));
        u.button(l, s(R.string.get_started), Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { showChoose(); } });
        u.note(l, s(R.string.welcome_steps)).setGravity(Gravity.CENTER);
        setScreen("welcome", "", null, 0, u.scroll(l),
                u.iconButton(Ui.GLOBE, u.text, s(R.string.language), new View.OnClickListener() { public void onClick(View v) { chooseLanguage(); } }));
    }

    void showChoose() {
        LinearLayout l = u.page();
        u.steps(l, 1, 3);
        u.title(l, s(R.string.choose_title));
        u.note(l, s(R.string.choose_note));
        option(l, "Amazon Photos", s(R.string.amazon_tag), s(R.string.amazon_detail), true, "amazon");
        option(l, "OneDrive", s(R.string.od_tag), s(R.string.od_detail) + (OneDrive.configured() ? "" : "\n\n" + s(R.string.od_unavailable)),
                OneDrive.configured(), "onedrive");
        LinearLayout n = u.notice(l, u.field);
        u.heading(n, s(R.string.google_t));
        u.note(n, s(R.string.google_d));
        setScreen("choose", s(R.string.storage_title), s(R.string.step_n, 1), Ui.BACK, u.scroll(l));
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
                    new AlertDialog.Builder(MainActivity.this).setTitle(R.string.other_account_t)
                            .setMessage(s(R.string.other_account_d, c.name()))
                            .setPositiveButton(R.string.sign_out, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { connecting = false; signOut(); } })
                            .setNegativeButton(R.string.use_this_account, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { connecting = false; useFolder(folder, first); } })
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
        if (prefs.contains("verifier")) { toast(s(R.string.signed_in_to, cloudName())); start(); }
        else if (first.isEmpty()) showCreatePassword();
        else showRestorePassword(first.get(0).optString("id"));
    }

    void signOut() {
        final Cloud c = st.cloud();
        c.signOut();
        toast(s(R.string.signed_out, c.name()));
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
        u.note(head, s(R.string.amazon_login_note));
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
        loginStatus = u.label(head, s(R.string.waiting_sign_in), 14, u.text, true);
        loginStatus.setPadding(u.dp(4), u.dp(8), 0, 0);
        l.addView(head);
        setScreen("login", s(R.string.amazon_login_title), prefs.contains("verifier") ? s(R.string.refresh_session) : s(R.string.step_n, 1),
                Ui.BACK, l, logButton());

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setUserAgentString(Amazon.UA); // desktop site: the one the API calls belong to
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
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
        loginHost.setText(s(safe ? R.string.host_amazon : R.string.host_not_amazon, x.getScheme() + "://" + x.getHost()));
        loginHost.setTextColor(safe ? u.ok : u.bad);
        loginHostIcon.setImageDrawable(new Ui.Icon(safe ? Ui.LOCK : Ui.ALERT, safe ? u.ok : u.bad));
    }

    final Runnable pollLogin = new Runnable() {
        public void run() { if ("login".equals(screen)) { checkLogin(); ui.postDelayed(this, 2000); } }
    };

    void checkLogin() {
        if (connecting || !"login".equals(screen) || SystemClock.elapsedRealtime() < nextLoginTry || !st.cloud().hasSession()) return;
        CookieManager.getInstance().flush();
        loginStatus.setText(R.string.amazon_checking);
        connect("login", new Failed() { public void run(Exception e) {
            nextLoginTry = SystemClock.elapsedRealtime() + 15_000;
            loginStatus.setText(s(R.string.amazon_not_yet, explain(e)));
        }});
    }

    // ================================================================ setup 1b: OneDrive sign-in in the browser

    void showOneDriveLogin() {
        final boolean setup = !prefs.contains("verifier");
        LinearLayout l = u.page();
        if (setup) u.steps(l, 1, 3);
        u.title(l, s(R.string.od_title));
        LinearLayout k = u.card(l);
        u.feature(k, Ui.KEY, s(R.string.od_pw_t), s(((OneDrive) st.cloud()).allFiles() ? R.string.od_pw_d_all : R.string.od_pw_d_app));
        u.feature(k, Ui.LOCK, s(R.string.od_token_t), s(R.string.od_token_d));
        u.button(l, s(R.string.od_sign_in), Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) {
            openAuth(((OneDrive) st.cloud()).allFiles());
        }});
        odStatus = u.note(l, "");
        odStatus.setVisibility(View.GONE);
        LinearLayout fb = u.notice(l, u.warnSoft);
        u.heading(fb, s(R.string.od_refused_t));
        u.body(fb, s(R.string.od_refused_d));
        u.button(fb, s(R.string.od_full), Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { openAuth(true); } });
        fb.setVisibility(View.GONE);
        odFallback = fb;
        setScreen("odlogin", "OneDrive", setup ? s(R.string.step_n, 1) : s(R.string.sign_in_again), Ui.BACK, u.scroll(l), logButton());
    }

    void odMessage(String text, int color) {
        if (odStatus == null || !"odlogin".equals(screen)) return;
        odStatus.setText(text);
        odStatus.setTextColor(color);
        odStatus.setVisibility(View.VISIBLE);
    }

    void openAuth(boolean allFiles) {
        try {
            String url = ((OneDrive) st.cloud()).authUrl(allFiles);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            picking = true; // longer auto-lock while the browser is in front
            odMessage(s(R.string.od_waiting), u.muted);
        } catch (Exception e) { toast(s(R.string.no_browser_open, explain(e))); }
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
        odMessage(s(R.string.od_finishing), u.muted);
        io.execute(new Runnable() { public void run() {
            try {
                od.redeem(x);
                post(new Runnable() { public void run() {
                    odMessage(s(R.string.od_opening), u.muted);
                    connect("odlogin", new Failed() { public void run(Exception e) {
                        odMessage(s(R.string.od_folder_failed, explain(e)), u.bad);
                        int code = e instanceof Cloud.ApiError ? ((Cloud.ApiError) e).code : 0;
                        if (code == 400 || code == 403 || code == 404) offerAllFiles(od); // app folder refused for this app registration
                    }});
                }});
            } catch (final Exception e) {
                Journal.add("OneDrive sign-in failed: " + e);
                post(new Runnable() { public void run() {
                    connecting = false;
                    odMessage(s(R.string.od_not_completed, explain(e)), u.bad);
                    if ("invalid_scope".equals(error) || "unauthorized_client".equals(error)) offerAllFiles(od);
                }});
            }
        }});
    }

    // ================================================================ setup 2: vault password

    void showCreatePassword() {
        LinearLayout l = u.page();
        u.steps(l, 2, 3);
        u.title(l, s(R.string.create_title));
        u.note(l, s(R.string.create_note));
        LinearLayout k = u.card(l);
        final EditText p1 = u.password(k, s(R.string.hint_password), null);
        u.strength(p1, k);
        final EditText p2 = u.password(k, s(R.string.hint_repeat), null);
        final CheckBox ok = u.check(k, s(R.string.create_confirm));
        final TextView err = u.note(l, "");
        u.button(l, s(R.string.create_btn), Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String a = p1.getText().toString(), b = p2.getText().toString();
            err.setTextColor(u.bad);
            if (a.length() < 10) { err.setText(R.string.err_short); return; }
            if (!a.equals(b)) { err.setText(R.string.err_mismatch); return; }
            if (!ok.isChecked()) { err.setText(R.string.err_confirm); return; }
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText(R.string.deriving_slow);
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
        setScreen("create", s(R.string.new_vault), s(R.string.step_n, 2), Ui.BACK, u.scroll(l), logButton());
    }

    void showRestorePassword(final String sampleId) {
        LinearLayout l = u.page();
        u.steps(l, 2, 3);
        u.title(l, s(R.string.restore_title));
        u.note(l, s(R.string.restore_note, cloudName()));
        final TextView[] go = new TextView[1];
        final EditText p = u.password(l, s(R.string.hint_password), new Runnable() { public void run() { if (go[0].isEnabled()) go[0].performClick(); } });
        final TextView err = u.note(l, "");
        go[0] = u.button(l, s(R.string.restore_btn), Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText(R.string.restore_working);
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
                        byte[] sl;
                        try (InputStream in = new FileInputStream(f)) { sl = Vault.readSalt(in); }
                        byte[] kk = Vault.deriveKey(pw, sl);
                        try (InputStream in = new FileInputStream(f)) { Vault.decryptPng(in, kk); key = kk; salt = sl; break; }
                        catch (AEADBadTagException e) { wrong = e; Arrays.fill(kk, (byte) 0); }
                    }
                    if (key == null) throw wrong;
                    final byte[] k = key;
                    prefs.edit().putString("salt", hex(salt))
                            .putString("verifier", Base64.encodeToString(Vault.seal(k, VERIFY.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
                    st.writeIndex(k, new Store.Index());
                    prefs.edit().putBoolean("restore_pending", true).putBoolean("folders_dirty", false).commit();
                    Journal.add("existing vault unlocked");
                    post(new Runnable() { public void run() { st.key = k; st.items = new ArrayList<>(); st.folders = new ArrayList<>(); showSelfTest(); } });
                } catch (final Throwable e) {
                    post(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setTextColor(u.bad);
                        err.setText(e instanceof AEADBadTagException ? s(R.string.wrong_password) : explain(e));
                    }});
                } finally { f.delete(); }
            }});
        }});
        setScreen("restore", s(R.string.existing_vault), s(R.string.step_n, 2), Ui.BACK, u.scroll(l), logButton());
    }

    // ================================================================ setup 3: self-test on the real account

    final class StepRow { ImageView icon; ProgressBar spin; TextView detail; }

    void showSelfTest() {
        final Cloud c = st.cloud();
        LinearLayout l = u.page();
        boolean setup = !prefs.contains("done_selftest");
        if (setup) u.steps(l, 3, 3);
        u.title(l, s(R.string.selftest_title));
        u.note(l, s(R.string.selftest_note, c.name()));
        LinearLayout pics = u.hbox();
        ImageView mine = new ImageView(this), theirs = new ImageView(this);
        String[] caps = {s(R.string.cap_mine), s(R.string.cap_theirs, c.name())};
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
                s(R.string.st1), s(R.string.st2, c.name()), s(R.string.st3), s(R.string.st4), s(R.string.st5), s(R.string.st6),
                s(c instanceof Amazon ? R.string.st7_amazon : R.string.st7_other), s(R.string.st8)};
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
        setScreen("selftest", s(R.string.selftest_title), setup ? s(R.string.step_n, 3) : c.name(), 0, u.scroll(l));
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
    void passed(StepRow[] rows, int i, int state, String detail) {
        mark(rows[i], state, detail);
        if (i + 1 < rows.length) mark(rows[i + 1], RUN, null);
    }

    void runSelfTest(final Cloud c, final StepRow[] rows, final ImageView mine, final ImageView theirs, final LinearLayout actions) {
        final byte[] k = keyCopy(), salt = st.salt();
        if (k == null) { showUnlock(); return; }
        testing = true;
        mark(rows[0], RUN, null);
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
                passed(rows, i++, OK, s(R.string.d_encrypted, human(orig.length), human(up.length()), SystemClock.elapsedRealtime() - t));

                t = SystemClock.elapsedRealtime();
                JSONObject node = c.upload(up, hex(Vault.random(8)) + ".png", st.folder(), null);
                nodeId = node.getString("id");
                passed(rows, i++, OK, s(R.string.d_ms, SystemClock.elapsedRealtime() - t));

                String type = node.optString("type");
                if (type.startsWith("image/")) passed(rows, i++, OK, s(R.string.d_type, type));
                else passed(rows, i++, WARN, s(R.string.d_type_warn, c.name(), type));

                t = SystemClock.elapsedRealtime();
                c.download(nodeId, down, null);
                passed(rows, i++, OK, s(R.string.d_download, human(down.length()), SystemClock.elapsedRealtime() - t));

                byte[] a = readFile(up), b = readFile(down);
                String h1 = Vault.sha256(a, 0, a.length), h2 = Vault.sha256(b, 0, b.length);
                if (!h1.equals(h2)) throw new IOException(s(R.string.d_differs, c.name()));
                passed(rows, i++, OK, h1.substring(0, 16) + "...");

                Vault.Opened o;
                try (InputStream in = new FileInputStream(down)) { o = Vault.decryptPng(in, k); }
                if (!Vault.sha256(o.plain, o.dataOff, o.dataLen()).equals(Vault.sha256(orig, 0, orig.length))) throw new IOException(s(R.string.d_decrypt_bad));
                final Bitmap noise = BitmapFactory.decodeFile(down.getPath());
                post(new Runnable() { public void run() { theirs.setImageBitmap(noise); } });
                passed(rows, i++, OK, s(R.string.d_decrypt_ok));

                try {
                    String[] r = c.storage();
                    passed(rows, i++, "ok".equals(r[0]) ? OK : WARN, r[1]);
                } catch (Exception e) { passed(rows, i++, WARN, s(R.string.d_storage_fail, explain(e))); }

                c.trash(Collections.singletonList(nodeId));
                nodeId = null;
                passed(rows, i++, OK, s(R.string.d_trash, c.trashName()));
                prefs.edit().putBoolean("done_selftest", true).apply();
            } catch (final Throwable e) {
                ok = false;
                Journal.add("self-test failed: " + e);
                if (i < rows.length) mark(rows[i], BAD, explain(e));
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
                    u.label(n, s(R.string.selftest_ok, c.name()), 15, u.ok, true);
                    if (bioAvailable() && !bioEnabled())
                        u.button(actions, s(R.string.bio_on_recommended), Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { enableBio(); } });
                    u.button(actions, s(R.string.open_vault), Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { openMyVault(); } });
                } else {
                    LinearLayout n = u.notice(actions, u.badSoft);
                    u.label(n, s(R.string.selftest_bad), 15, u.bad, true);
                    u.button(actions, s(R.string.retry), Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
                    u.button(actions, s(R.string.sign_in_to_again, c.name()), Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { showSignIn(); } });
                    u.button(actions, s(R.string.show_log_report), Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { showLog(); } });
                }
            }});
        }});
    }

    /** After setup: a vault restored on a new phone starts rebuilding its list from the cloud right away. */
    void openMyVault() {
        if (prefs.getBoolean("restore_pending", false) && st.items.isEmpty()) startSync();
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
        c.drawText("Cloakroll self-test", 40, 190, p);
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
        u.title(l, s(R.string.locked_title)).setGravity(Gravity.CENTER);
        u.note(l, s(R.string.locked_note)).setGravity(Gravity.CENTER);
        final TextView[] go = new TextView[1];
        final EditText p = u.password(l, s(R.string.hint_password), new Runnable() { public void run() { if (go[0].isEnabled()) go[0].performClick(); } });
        final TextView err = u.note(l, "");
        err.setGravity(Gravity.CENTER);
        go[0] = u.button(l, s(R.string.unlock), Ui.PRIMARY, new View.OnClickListener() { public void onClick(final View btn) {
            final String pw = p.getText().toString();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText(R.string.deriving);
            io.execute(new Runnable() { public void run() {
                byte[] k = null;
                try { k = Vault.deriveKey(pw, st.salt()); } catch (Exception ignored) { }
                openVault(k, new Runnable() { public void run() { btn.setEnabled(true); err.setTextColor(u.bad); err.setText(R.string.wrong_password); } });
            }});
        }});
        if (bioEnabled()) u.button(l, s(R.string.use_fingerprint), Ui.TONAL, new View.OnClickListener() { public void onClick(View v) { bioUnlock(); } });
        if (st.status != null && st.jobRunning) u.note(l, s(R.string.background_status, st.status)).setGravity(Gravity.CENTER);
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
                .setNegativeButton(s(R.string.cancel), getMainExecutor(), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { } })
                .build()
                .authenticate(new BiometricPrompt.CryptoObject(c), new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        if (isDestroyed()) return;
                        try { done.run(r.getCryptoObject().getCipher()); } catch (Exception e) { toast(s(R.string.fingerprint_err, explain(e))); }
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
            bioPrompt(s(R.string.bio_enable_title), c, new CipherDone() { public void run(Cipher c) throws Exception {
                if (st.key == null) return;
                byte[] ct = c.doFinal(st.key);
                prefs.edit().putString("bio_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                        .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                toast(s(R.string.bio_enabled));
                if ("settings".equals(screen)) showSettings(); else openMyVault();
            }});
        } catch (Exception e) { toast(s(R.string.bio_enable_failed, explain(e))); }
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
            bioPrompt(s(R.string.bio_unlock_title), c, new CipherDone() { public void run(Cipher c) throws Exception {
                final byte[] key = c.doFinal(Base64.decode(prefs.getString("bio_ct", ""), Base64.NO_WRAP));
                io.execute(new Runnable() { public void run() {
                    openVault(key, new Runnable() { public void run() { toast(s(R.string.bio_mismatch)); } });
                }});
            }});
        } catch (KeyPermanentlyInvalidatedException e) {
            disableBio();
            toast(s(R.string.bio_reset));
            showUnlock();
        } catch (Exception e) { toast(s(R.string.fingerprint_err, explain(e))); }
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
        if (!openFolder.isEmpty()) return q(R.plurals.items_in_folder, cellItems().size());
        return q(R.plurals.items_encrypted_on, st.items.size(), cloudName()) + (st.folders.isEmpty() ? "" : " • " + q(R.plurals.folders, st.folders.size()));
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
            if (st.jobRunning) new AlertDialog.Builder(MainActivity.this).setMessage(R.string.stop_job_q)
                    .setPositiveButton(R.string.stop, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { SyncService.stop(MainActivity.this); } })
                    .setNegativeButton(R.string.continue_, null).show();
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
            TextView add = u.label(fab, s(R.string.add), 16, u.onAccent, true);
            add.setPadding(u.dp(10), 0, 0, 0);
            add.setIncludeFontPadding(false);
            fab.setContentDescription(s(R.string.add_media));
            fab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(); } });
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
            fp.setMargins(0, 0, u.dp(20), u.dp(20));
            frame.addView(fab, fp);
        }

        if (selecting) setScreen("gallery", q(R.plurals.selected, selected.size()), s(R.string.tap_more), Ui.CLOSE, frame,
                u.iconButton(Ui.MOVE, u.text, s(R.string.move_to_folder), new View.OnClickListener() { public void onClick(View v) { moveSelected(); } }),
                u.iconButton(Ui.TRASH, u.text, s(R.string.delete), new View.OnClickListener() { public void onClick(View v) { confirmDeleteSelected(); } }));
        else setScreen("gallery", openFolder.isEmpty() ? "Cloakroll" : openFolder, gallerySubtitle(), openFolder.isEmpty() ? 0 : Ui.BACK, frame,
                u.iconButton(Ui.LOCK, u.text, s(R.string.lock_now), new View.OnClickListener() { public void onClick(View v) { st.lock(); st.changed(); } }),
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
        TextView t = u.heading(l, s(top ? R.string.empty_top_t : R.string.empty_folder_t));
        t.setPadding(0, u.dp(16), 0, u.dp(4));
        t.setGravity(Gravity.CENTER);
        u.note(l, top ? s(R.string.empty_top_d, cloudName()) : s(R.string.empty_folder_d)).setGravity(Gravity.CENTER);
        u.button(l, s(top ? R.string.add_media : R.string.add_here), Ui.PRIMARY, new View.OnClickListener() { public void onClick(View v) { pick(); } });
        if (top) u.button(l, s(R.string.restore_from, cloudName()), Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { startSync(); } });
        return u.scroll(l);
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
            String sub = q(R.plurals.items, n == null ? 0 : n);
            SpannableString sp = new SpannableString(name + "\n" + sub);
            sp.setSpan(new RelativeSizeSpan(0.85f), name.length() + 1, sp.length(), 0);
            label.setText(sp);
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
            if (!selected.isEmpty()) { toast(s(R.string.tap_folder_icon)); return; }
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
        titleView.setText(q(R.plurals.selected, selected.size()));
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
        e.setHint(R.string.folder_name);
        e.setText(current);
        e.setSelection(current.length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String n = e.getText().toString().trim().replaceAll("\\s+", " ");
                    if (n.isEmpty() || n.equals(current)) return;
                    if (n.length() > 60) { toast(s(R.string.max60)); return; }
                    if (mustBeNew && !n.equalsIgnoreCase(current))
                        for (String f : st.folders) if (f.equalsIgnoreCase(n)) { toast(s(R.string.folder_exists, f)); return; }
                    done.run(n);
                }})
                .setNegativeButton(R.string.cancel, null).show();
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
                post(new Runnable() { public void run() { toast(s(R.string.could_not_save, explain(x))); } });
            } finally { Arrays.fill(k, (byte) 0); }
        }});
    }

    void newFolder() {
        askFolderName(s(R.string.menu_new_folder), "", true, new NameDone() { public void run(final String name) {
            editFolders(new Store.Edit() { public void apply(Store.Index ix) { if (!ix.hasFolder(name)) ix.folders.add(name); } }, null);
        }});
    }

    void folderOptions(final String name) {
        new AlertDialog.Builder(this).setTitle(name)
                .setItems(new String[]{s(R.string.folder_opt_rename), s(R.string.folder_opt_delete)}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { if (w == 0) renameFolder(name); else deleteFolder(name); }
                }).show();
    }

    void renameFolder(final String old) {
        askFolderName(s(R.string.menu_rename_folder), old, true, new NameDone() { public void run(final String name) {
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
        new AlertDialog.Builder(this).setTitle(s(R.string.delete_folder_q, name))
                .setMessage(n == null ? s(R.string.folder_empty) : q(R.plurals.folder_kept, n))
                .setPositiveButton(R.string.menu_delete_folder, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    editFolders(new Store.Edit() { public void apply(Store.Index ix) {
                        ix.folders.remove(name);
                        for (Store.Item it : ix.items) if (it.folder.equals(name)) it.folder = "";
                    }}, null);
                }})
                .setNegativeButton(R.string.cancel, null).show();
    }

    void moveSelected() {
        final List<String> options = new ArrayList<>();
        options.add(s(R.string.main_view));
        options.addAll(st.folders);
        options.add(s(R.string.new_folder_dots));
        new AlertDialog.Builder(this).setTitle(q(R.plurals.move_n_to, selected.size()))
                .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    if (w == options.size() - 1)
                        askFolderName(s(R.string.menu_new_folder), "", false, new NameDone() { public void run(String name) { moveTo(name); } });
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
        }}, folder.isEmpty() ? q(R.plurals.moved_main, ids.size()) : q(R.plurals.moved_to, ids.size(), folder));
        saveScroll();
        selected.clear();
        showGallery();
    }

    void confirmDeleteSelected() {
        final List<String> ids = new ArrayList<>(selected);
        new AlertDialog.Builder(this).setTitle(q(R.plurals.delete_n_q, ids.size()))
                .setMessage(s(R.string.delete_files_msg, st.cloud().trashName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { deleteItems(ids); } })
                .setNegativeButton(R.string.cancel, null).show();
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
                post(new Runnable() { public void run() { toast(s(R.string.delete_failed, explain(e))); if (isAuth(e)) askRelogin(); } });
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
        if (st.key == null) { toast(s(R.string.locked_toast)); return; }
        List<Uri> uris = new ArrayList<>();
        ClipData c = data.getClipData();
        if (c != null) for (int i = 0; i < c.getItemCount(); i++) uris.add(c.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        try {
            SyncService.start(this, SyncService.UPLOAD, uris, openFolder);
            toast(openFolder.isEmpty() ? q(R.plurals.queued, uris.size()) : q(R.plurals.queued_for, uris.size(), openFolder));
        } catch (Exception e) { toast(s(R.string.upload_start_failed, explain(e))); }
    }

    void startSync() { startJob(SyncService.SYNC); }

    void startJob(String action) {
        try { SyncService.start(this, action, null, ""); }
        catch (Exception e) { toast(s(R.string.job_start_failed, explain(e))); }
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

    /** Opens with the small preview kept on the phone. The original is downloaded only when asked (Original, Save). */
    void showViewer(final Store.Item it) {
        LinearLayout l = u.vbox();
        FrameLayout box = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        l.addView(box, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        TextView info = u.label(l, "", 13, INFO_TEXT, false);
        info.setPadding(u.dp(20), u.dp(10), u.dp(20), u.dp(4));
        LinearLayout row = u.hbox();
        row.setPadding(u.dp(8), u.dp(4), u.dp(8), u.dp(8));
        LinearLayout original = u.action(row, Ui.IMAGE, s(R.string.v_original), Color.WHITE, null);
        LinearLayout cloudView = u.action(row, Ui.NOISE, s(R.string.v_cloud), Color.WHITE, null);
        LinearLayout save = u.action(row, Ui.SAVE, s(R.string.v_save), Color.WHITE, null);
        u.action(row, Ui.TRASH, s(R.string.delete), Color.WHITE, new View.OnClickListener() { public void onClick(View v) { confirmDelete(it); } });
        l.addView(row, u.wide(0));
        setScreen("viewer", it.name, (viewList.size() > 1 ? s(R.string.v_pos, viewIndex + 1, viewList.size()) + " • " : "")
                + date(it.taken) + " • " + human(it.size), Ui.BACK, l);
        lastViewedId = it.id;
        Bitmap pre = st.thumbs.get(it.id);
        if (pre != null) iv.setImageBitmap(pre);
        else loadThumb(it.id, iv);
        iv.setTag(it.id);
        new Viewer(it, box, iv, info, original, cloudView, save).start();
    }

    /** The item open in the viewer. Nothing goes over the network until asked: Original, Cloud view or Save to phone. */
    final class Viewer {
        final Store.Item it;
        final int token = viewToken; // made after setScreen: a newer screen makes this one stop
        final FrameLayout box;
        final ImageView iv;
        final TextView info;
        final LinearLayout original, cloudView, save;
        final File png;                // the encrypted PNG as stored (big file: its part 0), kept in the download cache
        final Cloud cloud = st.cloud();
        Vault.Opened o;                // small file, decrypted in memory
        File whole;                    // big file or video, decrypted in app-private storage, deleted on close
        boolean loading;
        Runnable after;                // asked for while the original was still loading (Save to phone)
        ImageView noise;               // cloud view shown on top
        CharSequence infoBefore;

        Viewer(Store.Item it, FrameLayout box, ImageView iv, TextView info, LinearLayout original, LinearLayout cloudView, LinearLayout save) {
            this.it = it; this.box = box; this.iv = iv; this.info = info; this.original = original; this.cloudView = cloudView; this.save = save;
            png = st.blobFile(it.id);
        }

        boolean open() { return token == viewToken; }

        boolean loaded() { return o != null || whole != null; }

        void start() {
            original.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { original(null); } });
            cloudView.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleCloudView(); } });
            save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                original(new Runnable() { public void run() { saveToPhone(it, o, whole); } });
            }});
            if (it.parts.isEmpty() && !it.video() && png.exists()) original(null); // already on the phone: no download needed
            else info.setText(s(it.video() ? R.string.v_preview_video : R.string.v_preview, human(it.size)));
        }

        /**
         * A line for the info text of `owner`: the cloud view it belongs to, or null for the photo. Shown if that view is
         * the one on screen; the photo's line is kept for when the cloud view is closed. Main thread.
         */
        void say(ImageView owner, String text, boolean error) {
            if (noise == owner) { info.setText(text); info.setTextColor(error ? INFO_ERROR : INFO_TEXT); }
            else if (owner == null) infoBefore = text;
        }

        /** Download progress; stops the download if the viewer was left. Any thread. */
        Cloud.Progress progress(final ImageView owner) {
            return new Cloud.Progress() { public void on(final long d, final long total) {
                if (!open()) throw new CancellationException();
                post(new Runnable() { public void run() {
                    if (open()) say(owner, s(R.string.v_downloading, total > 0 ? 100 * d / total + "%" : human(d)), false);
                }});
            }};
        }

        void failed(final Throwable e, final ImageView owner) {
            Journal.add("view failed: " + e);
            png.delete(); // a damaged download must not be reused
            post(new Runnable() { public void run() {
                if (!open()) return;
                if (owner == null) { loading = false; after = null; original.setEnabled(!loaded()); }
                say(owner, s(R.string.v_error, explain(e)), true);
                if (isAuth(e)) askRelogin();
            }});
        }

        /** Downloads (unless cached) and decrypts the original, shows it, then runs `then`. */
        void original(final Runnable then) {
            if (loaded()) { if (then != null) then.run(); return; }
            if (loading) { if (then != null) after = then; return; }
            final byte[] k = keyCopy();
            if (k == null) return;
            loading = true;
            original.setEnabled(false);
            final boolean play = then == null; // Save to phone doesn't start the video
            if (!png.exists() || !it.parts.isEmpty()) say(null, s(R.string.v_downloading, ""), false);
            viewIo.execute(new Runnable() { public void run() {
                File f = null;
                try {
                    if (!open()) return;
                    String ext = it.name.contains(".") ? it.name.substring(it.name.lastIndexOf('.')) : it.video() ? ".mp4" : "";
                    final Vault.Opened oo;
                    final File w;
                    final String line;
                    long t = SystemClock.elapsedRealtime();
                    if (!it.parts.isEmpty()) {
                        f = new File(getCacheDir(), "play-" + it.id + ext);
                        st.assemble(it, k, f, new Cloud.Progress() {
                            public void on(final long d, final long total) {
                                if (!open()) throw new CancellationException(); // viewer closed: stop downloading
                                post(new Runnable() { public void run() {
                                    if (open()) say(null, s(R.string.v_parts, (int) d + 1, (int) total), false);
                                }});
                            }
                        });
                        st.trimBlobCache();
                        oo = null;
                        w = f;
                        line = s(R.string.v_done_parts, it.parts.size(), human(it.size), cloud.name());
                    } else {
                        if (!png.exists()) {
                            cloud.download(it.id, png, progress(null));
                            st.trimBlobCache();
                        } else png.setLastModified(System.currentTimeMillis());
                        if (!open()) return;
                        post(new Runnable() { public void run() { if (open()) say(null, s(R.string.v_decrypting), false); } });
                        oo = st.decrypt(png, k);
                        line = s(R.string.v_done, SystemClock.elapsedRealtime() - t, human(oo.dataLen()), cloud.name(), human(png.length()));
                        if (it.video()) {
                            f = new File(getCacheDir(), "play-" + it.id + ext);
                            try (OutputStream out = new FileOutputStream(f)) { out.write(oo.plain, oo.dataOff, oo.dataLen()); }
                        }
                        w = f;
                    }
                    if (!open()) { if (w != null) w.delete(); return; } // closed meanwhile
                    if (it.video()) {
                        post(new Runnable() { public void run() {
                            if (!open()) { w.delete(); return; }
                            deletePlaying();
                            playing = w;
                            o = oo;
                            whole = w;
                            VideoView vv = new VideoView(MainActivity.this);
                            MediaController mc = new MediaController(MainActivity.this);
                            mc.setAnchorView(vv);
                            vv.setMediaController(mc);
                            vv.setVideoPath(w.getPath());
                            box.addView(vv, 0, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
                            box.removeView(iv);
                            if (play) vv.start();
                            ready(line, then);
                        }});
                    } else {
                        ImageDecoder.Source src = w != null ? ImageDecoder.createSource(w)
                                : ImageDecoder.createSource(ByteBuffer.wrap(oo.plain, oo.dataOff, oo.dataLen()).slice());
                        final Bitmap bm = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo inf, ImageDecoder.Source sr) {
                                Size z = inf.getSize();
                                d.setTargetSampleSize(Math.max(1, Math.max(z.getWidth(), z.getHeight()) / 4096 + 1));
                            }
                        });
                        if (!open()) { if (w != null) w.delete(); return; }
                        post(new Runnable() { public void run() {
                            if (!open()) { if (w != null) w.delete(); return; }
                            if (w != null) { deletePlaying(); playing = w; }
                            o = oo;
                            whole = w;
                            iv.setTag(null); // a late preview must not replace the full image
                            iv.setImageBitmap(bm);
                            ready(line, then);
                        }});
                    }
                } catch (final Throwable e) {
                    if (f != null) f.delete();
                    if (!(e instanceof CancellationException)) failed(e, null);
                } finally { Arrays.fill(k, (byte) 0); }
            }});
        }

        void ready(String line, Runnable then) {
            loading = false;
            say(null, line, false);
            Runnable r = then != null ? then : after;
            after = null;
            if (r != null) r.run();
        }

        /** The exact PNG the cloud stores, on top of the photo; tap again to go back. Downloads it if needed. */
        void toggleCloudView() {
            final TextView label = (TextView) cloudView.getChildAt(1);
            if (noise != null) {
                box.removeView(noise);
                noise = null;
                label.setText(R.string.v_cloud);
                info.setText(infoBefore);
                info.setTextColor(INFO_TEXT);
                return;
            }
            final ImageView n = noise = new ImageView(MainActivity.this);
            n.setBackgroundColor(Color.BLACK);
            n.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(n, new FrameLayout.LayoutParams(MATCH, MATCH));
            label.setText(R.string.v_my_photo);
            infoBefore = info.getText();
            say(n, png.exists() ? "" : s(R.string.v_downloading, ""), false);
            viewIo.execute(new Runnable() { public void run() {
                try {
                    if (!open()) return;
                    if (!png.exists()) {
                        cloud.download(it.id, png, progress(n));
                        st.trimBlobCache();
                    }
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inSampleSize = png.length() > (8 << 20) ? 4 : 1;
                    final Bitmap b = BitmapFactory.decodeFile(png.getPath(), op);
                    post(new Runnable() { public void run() {
                        if (!open() || noise != n) return;
                        n.setImageBitmap(b);
                        say(n, s(R.string.v_cloud_shown, cloud.name(), human(png.length())), false);
                    }});
                } catch (final Throwable e) {
                    if (!(e instanceof CancellationException)) failed(e, n);
                }
            }});
        }
    }

    /** Writes a decrypted copy to the phone's gallery: from memory (small files) or from the decrypted file. */
    void saveToPhone(final Store.Item it, final Vault.Opened o, final File whole) {
        viewIo.execute(new Runnable() { public void run() {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (it.video() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Cloakroll");
                v.put(MediaStore.MediaColumns.DATE_TAKEN, it.taken);
                Uri x = getContentResolver().insert(it.video() ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (x == null) throw new IOException("gallery refused the file");
                try (OutputStream out = getContentResolver().openOutputStream(x)) {
                    if (whole != null) try (InputStream in = new FileInputStream(whole)) { Cloud.copy(in, out, -1, null); }
                    else out.write(o.plain, o.dataOff, o.dataLen());
                } catch (Exception e) { getContentResolver().delete(x, null, null); throw e; } // no empty file in the gallery
                post(new Runnable() { public void run() { toast(s(R.string.saved, it.video() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)); } });
            } catch (final Exception e) { post(new Runnable() { public void run() { toast(s(R.string.save_failed, explain(e))); } }); }
        }});
    }

    void confirmDelete(final Store.Item it) {
        new AlertDialog.Builder(this).setTitle(R.string.delete_one_q)
                .setMessage(s(R.string.delete_one_msg, st.cloud().trashName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    deleteItems(Collections.singletonList(it.id));
                }})
                .setNegativeButton(R.string.cancel, null).show();
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
        u.label(t, q(R.plurals.s_items_on, st.items.size(), cloudName()), 18, u.text, true);
        u.label(t, s(R.string.s_fp, fp), 13, u.muted, false);
        u.label(t, s(R.string.s_autolock), 13, u.muted, false);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(head);

        u.section(l, s(R.string.sec_security).toUpperCase(getResources().getConfiguration().getLocales().get(0)));
        LinearLayout sec = u.card(l);
        sec.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        if (st.reencrypting()) {
            int left = 0;
            String cur = prefs.getString("salt", "");
            for (Store.Item it : st.items) if (!cur.equals(it.salt)) left++;
            u.row(sec, Ui.KEY, s(R.string.pw_in_progress_t), q(R.plurals.pw_in_progress_d, left), null,
                    new View.OnClickListener() { public void onClick(View v) { startJob(SyncService.REENCRYPT); showGallery(); } });
        } else u.row(sec, Ui.KEY, s(R.string.change_pw_t), s(R.string.change_pw_d), null,
                new View.OnClickListener() { public void onClick(View v) { showChangePassword(); } });
        if (bioEnabled() || bioAvailable())
            u.row(sec, Ui.FINGER, s(R.string.bio_t), s(bioEnabled() ? R.string.on : R.string.off), u.toggle(bioEnabled()), new View.OnClickListener() { public void onClick(View v) {
                if (bioEnabled()) { disableBio(); showSettings(); } else enableBio();
            }});
        u.row(sec, Ui.EYE, s(R.string.screenshots_t), s(R.string.screenshots_d), u.toggle(st.allowScreenshots),
                new View.OnClickListener() { public void onClick(View v) {
                    st.allowScreenshots = !st.allowScreenshots;
                    if (st.allowScreenshots) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    showSettings();
                }});
        u.row(sec, Ui.LOCK, s(R.string.lock_now), null, null, new View.OnClickListener() { public void onClick(View v) { st.lock(); st.changed(); } });

        u.section(l, s(R.string.storage_title).toUpperCase(getResources().getConfiguration().getLocales().get(0)));
        LinearLayout sto = u.card(l);
        sto.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        u.row(sto, Ui.SYNC, s(R.string.menu_sync, cloudName()), s(R.string.sync_d), null,
                new View.OnClickListener() { public void onClick(View v) { startSync(); showGallery(); } });
        u.row(sto, Ui.CHECK, s(R.string.selftest_t), s(R.string.selftest_d), null,
                new View.OnClickListener() { public void onClick(View v) { showSelfTest(); } });
        u.row(sto, Ui.CLOUD, s(R.string.signout_t, cloudName()), s(R.string.signout_d), null, new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this).setTitle(s(R.string.signout_q, cloudName()))
                    .setMessage(R.string.signout_msg)
                    .setPositiveButton(R.string.sign_out, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { signOut(); } })
                    .setNegativeButton(R.string.cancel, null).show();
        }});

        u.section(l, s(R.string.sec_about).toUpperCase(getResources().getConfiguration().getLocales().get(0)));
        LinearLayout ab = u.card(l);
        ab.setPadding(u.dp(6), u.dp(4), u.dp(6), u.dp(4));
        u.row(ab, Ui.GLOBE, s(R.string.language), languageName(currentLanguage()), null,
                new View.OnClickListener() { public void onClick(View v) { chooseLanguage(); } });
        u.row(ab, Ui.SHIELD, s(R.string.about_row_t), s(R.string.about_row_d), null,
                new View.OnClickListener() { public void onClick(View v) { showAbout(); } });
        u.row(ab, Ui.LIST, s(R.string.log), s(R.string.log_d), null,
                new View.OnClickListener() { public void onClick(View v) { showLog(); } });
        if (!Config.DONATE_URL.isEmpty())
            u.row(ab, Ui.HEART, s(R.string.menu_support), s(R.string.support_d), null,
                    new View.OnClickListener() { public void onClick(View v) { openUrl(Config.DONATE_URL); } });
        if (!Config.SOURCE_URL.isEmpty())
            u.row(ab, Ui.CODE, s(R.string.source_t), s(R.string.source_d), null,
                    new View.OnClickListener() { public void onClick(View v) { openUrl(Config.SOURCE_URL); } });
        TextView foot = u.note(l, s(R.string.footer, version()));
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(u.dp(16), u.dp(20), u.dp(16), 0);
        setScreen("settings", s(R.string.settings), null, Ui.BACK, u.scroll(l));
    }

    void showAbout() {
        LinearLayout l = u.page();
        Cloud c = st.cloud();
        about(l, s(R.string.promise_t), s(R.string.promise_d, c.name()));
        about(l, s(R.string.where_t), s(R.string.where_cloud, c.name(), vaultPlace(), q(R.plurals.pngs, st.items.size())) + "\n"
                + s(R.string.where_folders, c.name()) + "\n" + s(R.string.where_phone) + "\n"
                + s(c instanceof OneDrive ? R.string.where_signin_od : R.string.where_signin_amazon) + "\n"
                + s(bioEnabled() ? R.string.where_key_bio : R.string.where_key));
        about(l, s(R.string.crypto_t), s(R.string.crypto_d, prefs.getString("salt", "")));
        about(l, s(R.string.recovery_t), s(R.string.recovery_d, c.name()));
        about(l, s(R.string.limits_t), s(R.string.limits_d) + "\n" + s(c instanceof Amazon ? R.string.limits_amazon : R.string.limits_od));
        setScreen("about", s(R.string.about_title), null, Ui.BACK, u.scroll(l));
    }

    void about(LinearLayout l, String title, String text) {
        LinearLayout k = u.card(l);
        u.heading(k, title);
        u.body(k, text);
    }

    // ================================================================ change password

    String duration(long secs) {
        if (secs < 90) return s(R.string.dur_min);
        if (secs < 90 * 60) return s(R.string.dur_mins, (int) Math.round(secs / 60.0));
        return s(R.string.dur_hours, (int) Math.round(secs / 3600.0));
    }

    void showChangePassword() {
        LinearLayout l = u.page();
        Cloud cloud = st.cloud();
        u.title(l, s(R.string.change_pw_t));
        long bytes = 0;
        for (Store.Item it : st.items) bytes += it.size;
        int n = st.items.size();
        if (n == 0) u.note(l, s(R.string.changepw_empty));
        else {
            long secs = 2 * bytes * 8 / 50_000_000L + n * 3L / 2; // 50 Mbit/s both ways + about 1.5 s of requests per file
            LinearLayout w = u.notice(l, u.warnSoft);
            LinearLayout head = u.hbox();
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new Ui.Icon(Ui.ALERT, u.warn));
            head.addView(ic, new LinearLayout.LayoutParams(u.dp(22), u.dp(22)));
            u.label(head, s(R.string.cost_t), 16, u.text, true).setPadding(u.dp(10), 0, 0, 0);
            w.addView(head);
            u.body(w, s(R.string.cost_intro) + "\n" + q(R.plurals.cost_all, n, human(bytes), duration(secs)) + "\n"
                    + s(R.string.cost_rest, cloud.trashName(), s(cloud instanceof Amazon ? R.string.cost_trash_amazon : R.string.cost_trash_od)));
        }
        LinearLayout k = u.card(l);
        final EditText cur = u.password(k, s(R.string.hint_current), null);
        final EditText p1 = u.password(k, s(R.string.hint_new), null);
        u.strength(p1, k);
        final EditText p2 = u.password(k, s(R.string.hint_repeat_new), null);
        final CheckBox ok = u.check(k, s(R.string.confirm_new));
        final TextView err = u.note(l, "");
        final TextView go = u.button(l, s(n == 0 ? R.string.change_btn : R.string.change_btn_reenc), Ui.PRIMARY, null);
        String blocked = prefs.getBoolean("restore_pending", false) ? s(R.string.blocked_restore, cloud.name())
                : st.busyJobs > 0 || st.jobRunning ? s(R.string.blocked_busy) : null;
        if (blocked != null) { go.setEnabled(false); err.setTextColor(u.bad); err.setText(blocked); }
        go.setOnClickListener(new View.OnClickListener() { public void onClick(final View btn) {
            final String c = cur.getText().toString(), a = p1.getText().toString(), b = p2.getText().toString();
            err.setTextColor(u.bad);
            if (a.length() < 10) { err.setText(R.string.err_new_short); return; }
            if (!a.equals(b)) { err.setText(R.string.err_new_mismatch); return; }
            if (a.equals(c)) { err.setText(R.string.err_same); return; }
            if (!ok.isChecked()) { err.setText(R.string.err_confirm_new); return; }
            if (st.busyJobs > 0 || st.jobRunning) { err.setText(R.string.blocked_busy); return; }
            final byte[] current = keyCopy();
            if (current == null) return;
            final boolean hadBio = bioEnabled();
            btn.setEnabled(false);
            err.setTextColor(u.muted);
            err.setText(R.string.checking_pw);
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
                        toast(s(st.items.isEmpty() ? R.string.pw_changed : R.string.pw_changed_bg));
                        showSettings();
                        if (hadBio && bioAvailable()) enableBio();
                    }});
                } catch (final Throwable e) {
                    post(new Runnable() { public void run() {
                        btn.setEnabled(true);
                        err.setTextColor(u.bad);
                        err.setText(e instanceof AEADBadTagException ? s(R.string.wrong_current) : explain(e));
                    }});
                } finally {
                    Arrays.fill(current, (byte) 0);
                    if (old != null) Arrays.fill(old, (byte) 0);
                    if (k != null) Arrays.fill(k, (byte) 0);
                }
            }});
        }});
        setScreen("changepw", s(R.string.changepw_screen), null, Ui.BACK, u.scroll(l));
    }

    // ================================================================ log

    void showLog() {
        LinearLayout l = u.page();
        u.note(l, s(R.string.log_note));
        LinearLayout k = u.card(l);
        final TextView t = u.label(k, Journal.text(), 12, u.text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        u.button(l, s(R.string.copy_log), Ui.TONAL, new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Cloakroll log", Journal.text()));
            toast(s(R.string.log_copied));
        }});
        setScreen("log", s(R.string.log), null, Ui.BACK, u.scroll(l));
    }
}
