package io.folddeck.spike;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The app: a lock, a home screen, and the deck the desktop streams onto.
 *
 *   adb shell am start -n io.folddeck.spike/.MainActivity \
 *       --es host 127.0.0.1 --ei port 5000
 *
 * Screens are sibling views in one FrameLayout rather than separate Activities,
 * because the deck must not be torn down and rebuilt every time you glance at
 * Settings — the SurfaceView would be destroyed with it, dropping the socket and
 * making the laptop stop capturing.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    /** 58/42 split for the cover screen: the keyboard needs a usable key pitch,
     *  and a 16:9 desktop still letterboxes into the remainder with thin bars.
     *  Unfolded, {@link Chassis} overrides this with an even split — see
     *  {@link #applyPosture()}. */
    private static final float VIDEO_WEIGHT = 58f, KEYBOARD_WEIGHT = 42f;

    /** The inner display's smallest side, in dp. The Fold's cover screen is
     *  around 410dp and the inner display well over 800dp, so the standard
     *  tablet breakpoint separates them with room to spare — and it needs no
     *  androidx.window, which would drag Gradle into a two-second build. */
    private static final int INNER_DISPLAY_SW_DP = 600;

    /** After this many failures in a row, stop retrying and go back to Home.
     *  A wrong address otherwise retries once a second forever, which looks
     *  exactly like a laptop that happens to be asleep. */
    private static final int FAILURES_BEFORE_GIVING_UP = 5;

    private static final int SCREEN_HOME = 0, SCREEN_DECK = 1, SCREEN_SETTINGS = 2,
            SCREEN_GUIDE = 3, SCREEN_ADDRESS = 4, SCREEN_SHORTCUTS = 5;

    private FrameLayout root;
    private LinearLayout deck;
    private Chassis.Lid lid;
    private Chassis.Base base;
    private Chassis.Hinge hinge;

    private SurfaceView surfaceView;
    private TextView hud;
    private KeyboardView keyboard;
    private PointerPad pointerPad;
    private FrameLayout videoPane;

    private HomeView home;
    private SettingsView settingsView;
    private GuideView guideView;
    private AddressView addressView;
    private ShortcutsView shortcutsView;
    private ShortcutBar shortcutBar;
    /** Where the shortcut editor returns to: the deck if it was opened from the bar. */
    private int shortcutsReturnTo = SCREEN_HOME;

    private H264Stream stream;
    private Lock lock;
    private LockOverlay lockOverlay;

    private boolean locked = true;
    /** True once the user has chorded left+right to hand the mouse back to the
     *  phone. Sticky, because the chord is a toggle: re-grabbing the moment the
     *  desktop is on screen again would make it impossible to ever keep. */
    private boolean mouseHandedBack = false;
    /** True from pressing Connect until Disconnect: survives locking, so
     *  unlocking puts you back on the desktop you were using. */
    private boolean wantStream = false;
    private int screen = SCREEN_HOME;
    private int lastPhase = -1;
    /** Which route is in use, kept so a rebuilt Home can show it again. */
    private int route = -1;
    /** Bumped on every start and stop, so reports from a replaced stream are
     *  recognised as stale and dropped. UI thread only. */
    private int streamGeneration = 0;

    /** Real stream dimensions, replaced by the decoder's reported format. */
    private final int[] streamSize = {1920, 1080};

    private String host = null;
    private int port = 5000;
    /** Both known addresses for the laptop; which one is live is measured. */
    private Endpoints endpoints;

    private SurfaceHolder holder;
    private SharedPreferences prefs;

    private long lastBytes = 0;
    private long lastNanos = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(getApplicationContext());

        // A crash from the previous run is worth more than getting straight back
        // to the stream: without adb on this phone there is no other way to see
        // what actually failed.
        String previousCrash = Crash.pending(this);
        if (previousCrash != null) {
            showCrashReport(previousCrash);
            return;
        }

        try {
            build();
        } catch (Throwable t) {
            // Show the failure rather than dying into the system's "this app has
            // a bug" dialog, which tells nobody anything.
            Crash.record(getApplicationContext(), Thread.currentThread().getName(), t);
            showCrashReport(Crash.pending(this));
        }
    }

    private void showCrashReport(String trace) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 32), Ui.dp(this, 20), Ui.dp(this, 20));

        TextView heading = Ui.text(this, "FoldDeck hit an error last run",
                Ui.T_TITLE, Ui.ERR);
        heading.setTypeface(Ui.FONT_BOLD);

        TextView body = Ui.text(this, trace == null ? "(no details captured)" : trace,
                Ui.T_MICRO, Ui.TEXT_DIM);
        body.setTextIsSelectable(true);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);

        box.addView(heading);
        box.addView(Ui.primaryButton(this, "Dismiss and continue", () -> {
            Crash.clear(this);
            recreate();
        }), Ui.fillW(this, 16));
        box.addView(scroll, Ui.fillW(this, 16));
        setContentView(box);
    }

    // ------------------------------------------------------------------ //
    private void build() {
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        // Before any view exists: every screen reads the palette and font as it
        // builds. The laptop's last theme, so the app opens in it offline too.
        Ui.loadFonts(this);
        Ui.apply(prefs.getString(Prefs.THEME, Ui.DEFAULT_PALETTE));

        // Three ways to get an address, in order of precedence:
        //   1. intent extras   -- the adb path, scriptable
        //   2. saved prefs     -- whatever was set last
        //   3. the address screen, reachable from Home and from Settings
        endpoints = Endpoints.load(prefs);
        if (getIntent().hasExtra("host")) host = getIntent().getStringExtra("host");
        if (getIntent().hasExtra("port")) port = getIntent().getIntExtra("port", 5000);
        if (host == null) {
            host = prefs.getString(Prefs.HOST, null);
            port = prefs.getInt(Prefs.PORT, 5000);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Ask the panel to skip Samsung's optional colour/motion post-processing.
        // A real latency win on these displays, and exactly what the flag is for.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setPreferMinimalPostProcessing(true);
        }

        root = framed();
        root.setBackgroundColor(Ui.BG);
        root.addView(buildDeck(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        home = newHome();
        root.addView(home, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // The lock sits above everything and is opaque, so nothing of the desktop
        // shows behind it.
        lock = new Lock(this);
        lockOverlay = new LockOverlay(this, lock, this::onUnlocked);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyPosture();
        showScreen(SCREEN_HOME);
        registerBackHandler();
        hideSystemBars();
    }

    /**
     * The whole app wears the focused-window border Hyprland draws on the laptop:
     * the accent, at Omarchy's border_size of 2. Drawn over the children and
     * padded in by the same amount, so nothing — not the edge keys, not the
     * video — is covered, and the lid and base stay symmetric about the crease.
     */
    private FrameLayout framed() {
        final int width = Ui.dp(this, 2);
        FrameLayout f = new FrameLayout(this) {
            final android.graphics.Paint paint = new android.graphics.Paint();

            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                super.dispatchDraw(canvas);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(width);
                paint.setColor(Ui.ACCENT);
                canvas.drawRect(width / 2f, width / 2f,
                        getWidth() - width / 2f, getHeight() - width / 2f, paint);
            }
        };
        f.setPadding(width, width, width, width);
        return f;
    }

    private HomeView newHome() {
        HomeView h = new HomeView(this, new HomeView.Listener() {
            @Override public void onConnect()          { connect(); }
            @Override public void onDisconnect()       { stopStreaming(); idle(); }
            @Override public void onReturnToDesktop()  { showScreen(SCREEN_DECK); }
            @Override public void onEditAddress()      { showAddressScreen(); }
            @Override public void onOpenGuide()        { showGuide(); }
            @Override public void onOpenSettings()     { showSettings(); }
        });
        h.setEndpoints(endpoints);
        return h;
    }

    /**
     * Repaint the app in the laptop's Omarchy theme, live, without dropping the
     * stream.
     *
     * The deck draws its colours from {@link Ui} on every frame, so it only needs
     * an invalidate. The screens bake colours into drawables as they are built,
     * so they are rebuilt in place, each at its old z-position — recreate() would
     * be shorter, but it destroys the Surface, which drops the socket and re-locks.
     */
    private void applyTheme(String palette) {
        if (palette.equals(prefs.getString(Prefs.THEME, null))) return;
        prefs.edit().putString(Prefs.THEME, palette).apply();
        Ui.apply(palette);

        root.setBackgroundColor(Ui.BG);
        deck.setBackgroundColor(Ui.BG);
        hud.setTextColor(Ui.OK);
        for (View v : new View[]{root, lid, base, hinge, keyboard, pointerPad}) v.invalidate();
        shortcutBar.rebuild();

        home = (HomeView) replace(home, newHome());
        home.setRoute(route);
        lastPhase = -1;               // the next stats tick restates connection state

        LockOverlay oldLock = lockOverlay;
        lockOverlay = new LockOverlay(this, lock, this::onUnlocked);
        lockOverlay.setVisibility(oldLock.getVisibility());
        replace(oldLock, lockOverlay);

        // Built lazily, so dropping them is enough — unless one is on screen.
        if (settingsView != null) root.removeView(settingsView);
        if (guideView != null) root.removeView(guideView);
        settingsView = null;
        guideView = null;
        if (screen == SCREEN_SETTINGS) showSettings();
        else if (screen == SCREEN_GUIDE) showGuide();
        else if (screen == SCREEN_ADDRESS) showAddressScreen();
        else if (screen == SCREEN_SHORTCUTS) showShortcuts(shortcutsReturnTo);
        else showScreen(screen);
    }

    /** Put {@code fresh} where {@code old} was in the root stack. */
    private View replace(View old, View fresh) {
        int at = root.indexOfChild(old);
        root.removeView(old);
        root.addView(fresh, at, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return fresh;
    }

    /** The laptop: lid, hinge, base. Built once and never torn down. */
    private View buildDeck() {
        // SurfaceView, not TextureView: it goes straight to SurfaceFlinger and can
        // take a hardware overlay. TextureView routes frames through the app's GL
        // context, costing an extra composite pass (~8ms) we can't afford.
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);

        hud = new TextView(this);
        hud.setTextColor(Ui.OK);
        hud.setBackgroundColor(0x99000000);
        hud.setTypeface(Ui.FONT);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hud.setPadding(16, 16, 16, 16);
        // Not clickable: it used to hide on tap, which also removed the only way
        // to reach the address prompt. Visibility is a setting now, and Home is
        // where connection state is reported.
        hud.setVisibility(Prefs.showHud(prefs) ? View.VISIBLE : View.GONE);

        pointerPad = new PointerPad(this);
        pointerPad.setListener(new PointerPad.Listener() {
            @Override public void onMove(int x, int y) {
                if (stream != null) stream.sendPointer(x, y);
            }
            @Override public void onButton(int button, boolean down) {
                if (stream != null) stream.sendButton(button, down);
            }
            @Override public void onScroll(int dv, int dh) {
                if (stream != null) stream.sendScroll(dv, dh);
            }
        });
        // Captured pointer events go to the focused view, so the pad has to be
        // able to hold focus for a Bluetooth mouse to reach it at all.
        pointerPad.setFocusable(true);
        pointerPad.setFocusableInTouchMode(true);
        pointerPad.setMouseSensitivity(Prefs.mouseSens(prefs));
        pointerPad.setHandBackListener(() -> {
            mouseHandedBack = true;
            updatePointerCapture();
        });

        // Video pane: the surface, the pointer overlay, then the HUD on top.
        videoPane = new FrameLayout(this);
        videoPane.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        videoPane.addView(pointerPad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        shortcutBar = new ShortcutBar(this, new ShortcutBar.Listener() {
            @Override public void onShortcut(Shortcuts.Item item) { sendChord(item); }
            @Override public void onEditShortcuts() { showShortcuts(screen); }
            @Override public void onReordered(java.util.List<Shortcuts.Item> items) {
                Shortcuts.save(prefs, items);
            }
            @Override public boolean onToggleTouchMode() {
                return pointerPad.toggleMode() == PointerPad.MODE_TRACKPAD;
            }
        });
        shortcutBar.setItems(Shortcuts.load(prefs));
        videoPane.addView(shortcutBar, new FrameLayout.LayoutParams(0, 0));
        videoPane.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        // Letterbox the surface to the stream's aspect ratio, and tell the
        // pointer overlay where the image actually landed.
        //
        // Without this the SurfaceView stretches the buffer to the pane, which
        // both distorts the desktop and makes every touch land off-target,
        // increasingly so towards the edges.
        //
        // The shortcut deck takes the space the stream leaves. Upright, the pane is
        // taller than 16:9: the desktop sits flush at the top and the deck fills
        // the band beneath it (capped, so the cover screen's huge band does not
        // become one enormous button row). In landscape it is wider: the desktop
        // goes flush right and the deck takes the whole left side.
        videoPane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            int paneW = r - l, paneH = b - t;
            if (paneW <= 0 || paneH <= 0) return;
            float aspect = (float) streamSize[0] / streamSize[1];
            // A side band too thin to hold even one column of buttons is not worth
            // turning the deck sideways for.
            boolean column = paneW - (int) (paneH * aspect) >= Ui.dp(this, ShortcutBar.COLUMN_MIN_DP);
            int x, y, vw, vh;
            if (column) {
                // Full height, flush right, always: no bars above or below the
                // desktop. The deck takes whatever width is left and, when that is
                // narrow (laptop layout off), shrinks to one column of small labels.
                // The image gives up a 2dp line where a border must show: top and
                // bottom in the laptop posture, where the screen well draws its accent
                // border just outside the image (so it sits level with the deck's);
                // bottom only without the hinge, for the separator line.
                int line = Chassis.Lid.separator(this);
                boolean chassis = lid.getPaddingTop() > 0;
                vh = paneH - (chassis ? 2 : 1) * line;
                vw = (int) (vh * aspect);
                x = paneW - vw;
                y = chassis ? line : 0;
                place(shortcutBar, 0, 0, x, paneH);
                shortcutBar.setCompact(x < Ui.dp(this, ShortcutBar.COMPACT_BELOW_DP));
            } else {
                int band = paneH - (int) (paneW / aspect);
                band = Math.max(Ui.dp(this, ShortcutBar.ROW_MIN_DP),
                        Math.min(band, Ui.dp(this, ShortcutBar.ROW_MAX_DP)));
                vh = Math.min(paneH - band, (int) (paneW / aspect));
                vw = (int) (vh * aspect);
                // Any height the cap leaves over is split around the pair.
                int top = (paneH - vh - band) / 2;
                x = (paneW - vw) / 2;
                y = top;
                place(shortcutBar, 0, top + vh, paneW, band);
                shortcutBar.setCompact(false);
            }
            place(surfaceView, x, y, vw, vh);
            shortcutBar.setVertical(column);
            // A deck side lying on the app frame stays open; see ShortcutBar.edges.
            boolean flushLeft = lid.getPaddingLeft() == 0, flushTop = lid.getPaddingTop() == 0;
            shortcutBar.setEdges(!flushLeft, !(column && flushTop), !(!column && lid.getPaddingRight() == 0), true);
            pointerPad.setVideoBounds(x, y, x + vw, y + vh);
            // videoPane sits inside the lid's content box, so its origin in lid
            // coordinates is the lid's top-left padding.
            lid.setScreen(lid.getPaddingLeft() + x, lid.getPaddingTop() + y,
                    lid.getPaddingLeft() + x + vw, lid.getPaddingTop() + y + vh);
        });

        keyboard = new KeyboardView(this);
        keyboard.setKeyListener((code, down) -> {
            if (stream != null) stream.sendKey(code, down);
        });
        // The TrackPoint drives the same virtual cursor the pointer pad uses, so
        // the nub and the pad never disagree about where the pointer is.
        keyboard.setTrackPointListener((dx, dy) -> {
            if (pointerPad != null) pointerPad.moveRelative(dx, dy);
        });

        lid = new Chassis.Lid(this);
        lid.addView(videoPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        base = new Chassis.Base(this);
        base.addView(keyboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hinge = new Chassis.Hinge(this);

        deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);
        deck.setBackgroundColor(Ui.BG);
        deck.addView(lid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, VIDEO_WEIGHT));
        deck.addView(hinge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, Chassis.HINGE_DP)));
        deck.addView(base, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, KEYBOARD_WEIGHT));
        return deck;
    }

    /** Pin a child of a FrameLayout to an exact box; a no-op when already there. */
    private static void place(View child, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
        if (lp.width == w && lp.height == h && lp.leftMargin == x && lp.topMargin == y) return;
        lp.width = w;
        lp.height = h;
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.gravity = Gravity.TOP | Gravity.START;
        child.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ //
    // Posture
    // ------------------------------------------------------------------ //
    /**
     * True only on the inner display, held in landscape, with the setting on.
     *
     * That is the posture where the crease runs horizontally across the middle
     * of the screen, which is the only reason any of the chassis exists. On the
     * cover screen there is no crease to align to and no room to spend on
     * bezels, so nothing here applies.
     */
    private boolean laptopPosture() {
        if (!Prefs.laptopLayout(prefs)) return false;
        Configuration cfg = getResources().getConfiguration();
        return cfg.smallestScreenWidthDp >= INNER_DISPLAY_SW_DP
                && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Put the seam where the crease is.
     *
     * Equal weights, with the hinge between them, is what makes the boundary land
     * on the fold line: the hinge is centred, so the lid and the base get exactly
     * half of the remaining height each. The old 58/42 split put the seam about a
     * centimetre above the crease, which is why the keyboard looked short.
     */
    private void applyPosture() {
        boolean laptop = laptopPosture();

        lid.setChassis(laptop);
        base.setChassis(laptop);
        // GONE, not INVISIBLE: an invisible hinge would still eat its height and
        // push the seam off the crease in the very mode it is meant to serve.
        hinge.setVisibility(laptop ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams lidLp = (LinearLayout.LayoutParams) lid.getLayoutParams();
        LinearLayout.LayoutParams baseLp = (LinearLayout.LayoutParams) base.getLayoutParams();
        lidLp.weight = laptop ? 1f : VIDEO_WEIGHT;
        baseLp.weight = laptop ? 1f : KEYBOARD_WEIGHT;
        lid.setLayoutParams(lidLp);
        base.setLayoutParams(baseLp);
    }

    /**
     * Folding and unfolding does not recreate the Activity — the manifest lists
     * screenSize, screenLayout and orientation in configChanges to keep the
     * Surface and the socket alive across the change. That means this callback
     * is the only notification the layout gets that the posture moved.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (deck != null) applyPosture();
        hideSystemBars();
    }

    /**
     * setSystemUiVisibility() is deprecated and a no-op for targetSdk 35+, which
     * would leave the status and nav bars drawn over the video. WindowInsetsController
     * is the only thing that actually hides them on Android 15+.
     */
    private void hideSystemBars() {
        // No setDecorFitsSystemWindows(false) needed: targetSdk 35+ is edge-to-edge
        // by default, which is why that call is itself deprecated now.
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ------------------------------------------------------------------ //
    // Mouse capture
    //
    // A Bluetooth mouse paired to the phone should drive the laptop, not the
    // phone -- so FoldDeck takes the mouse whenever the desktop is what you are
    // looking at, and gives it back everywhere else. Pointer capture hides the
    // phone's own cursor, which is why it must be released before any screen
    // with something to tap on it.
    // ------------------------------------------------------------------ //
    private void updatePointerCapture() {
        if (pointerPad == null) return;
        boolean want = screen == SCREEN_DECK && !locked && !mouseHandedBack && hasWindowFocus();
        if (want == pointerPad.hasPointerCapture()) return;
        if (want) {
            pointerPad.requestFocus();
            pointerPad.requestPointerCapture();
        } else {
            pointerPad.releasePointerCapture();
        }
    }

    /**
     * Capture can only be requested while the window has focus, so this is the
     * hook that actually grabs the mouse on the way back from the lock screen,
     * a notification shade or another app.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        updatePointerCapture();
    }

    // Both dispatch paths, because an uncaptured mouse reports its buttons on
    // whichever of the two the device happens to use: button state rides
    // ACTION_DOWN on the touch path and ACTION_BUTTON_PRESS on the generic one.
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return retakeMouse(ev) || super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return retakeMouse(ev) || super.dispatchTouchEvent(ev);
    }

    /**
     * Left and right together takes the mouse back, mirroring the chord that
     * gave it up.
     *
     * Consuming the event matters: without it the same press that re-captures
     * also lands as a click on whatever is under the phone's cursor.
     */
    private boolean retakeMouse(MotionEvent ev) {
        if (!mouseHandedBack || locked || screen != SCREEN_DECK) return false;
        int both = MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY;
        if ((ev.getButtonState() & both) != both) return false;
        mouseHandedBack = false;
        updatePointerCapture();
        return true;
    }

    // ------------------------------------------------------------------ //
    // Screens
    // ------------------------------------------------------------------ //
    private void showScreen(int which) {
        screen = which;
        home.setVisibility(which == SCREEN_HOME ? View.VISIBLE : View.GONE);
        if (settingsView != null) {
            settingsView.setVisibility(which == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);
        }
        if (guideView != null) {
            guideView.setVisibility(which == SCREEN_GUIDE ? View.VISIBLE : View.GONE);
        }
        if (addressView != null) {
            addressView.setVisibility(which == SCREEN_ADDRESS ? View.VISIBLE : View.GONE);
        }
        if (shortcutsView != null) {
            shortcutsView.setVisibility(which == SCREEN_SHORTCUTS ? View.VISIBLE : View.GONE);
        }
        hideSystemBars();
        updatePointerCapture();
    }

    private void showSettings() {
        if (settingsView == null) {
            settingsView = new SettingsView(this, prefs, lock, new SettingsView.Listener() {
                @Override public void onEditAddress()   { showAddressScreen(); }
                @Override public void onMouseSensitivity(float sens) {
                    if (pointerPad != null) pointerPad.setMouseSensitivity(sens);
                }
                @Override public void onChangePin()     { changePin(); }
                @Override public void onForgetHostCertificate() { forgetHostCertificate(); }
                @Override public void onLaptopLayoutChanged()   { applyPosture(); }
                @Override public void onEditShortcuts() { showShortcuts(SCREEN_SETTINGS); }
                @Override public void onHudChanged() {
                    hud.setVisibility(Prefs.showHud(prefs) ? View.VISIBLE : View.GONE);
                }
            }, () -> showScreen(SCREEN_HOME));
            // Added below the lock overlay so locking still covers it.
            root.addView(settingsView, root.indexOfChild(lockOverlay),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        settingsView.setAddress(host, port);
        showScreen(SCREEN_SETTINGS);
    }

    private void showGuide() {
        if (guideView == null) {
            guideView = new GuideView(this, () -> showScreen(SCREEN_HOME));
            root.addView(guideView, root.indexOfChild(lockOverlay),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        showScreen(SCREEN_GUIDE);
    }

    /**
     * Rebuilt every time rather than reused: it captures the current address in
     * its field at construction, and a stale field showing the previous address
     * is exactly the kind of thing that gets saved by accident.
     */
    private void showAddressScreen() {
        if (addressView != null) root.removeView(addressView);
        addressView = new AddressView(this, endpoints,
                saved -> {
                    endpoints = saved;
                    endpoints.save(prefs);
                    home.setEndpoints(endpoints);
                    // A new address invalidates whatever the last one failed with.
                    idle();
                    showScreen(SCREEN_HOME);
                },
                () -> showScreen(SCREEN_HOME));
        root.addView(addressView, root.indexOfChild(lockOverlay),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        showScreen(SCREEN_ADDRESS);
    }

    /** Rebuilt on every open, like the address editor, so it never shows a stale list. */
    private void showShortcuts(int returnTo) {
        shortcutsReturnTo = returnTo;
        if (shortcutsView != null) root.removeView(shortcutsView);
        shortcutsView = new ShortcutsView(this, Shortcuts.load(prefs),
                items -> {
                    Shortcuts.save(prefs, items);
                    shortcutBar.setItems(items);
                    showScreen(shortcutsReturnTo);
                },
                () -> showScreen(shortcutsReturnTo));
        root.addView(shortcutsView, root.indexOfChild(lockOverlay),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        showScreen(SCREEN_SHORTCUTS);
    }

    /**
     * Send a chord the way fingers play it: every key down in order, then up in
     * reverse, so modifiers wrap the key they modify.
     */
    private void sendChord(Shortcuts.Item item) {
        int[] codes = Shortcuts.parse(item.chord);
        if (stream == null || codes == null) return;
        for (int code : codes) stream.sendKey(code, true);
        for (int i = codes.length - 1; i >= 0; i--) stream.sendKey(codes[i], false);
    }

    /** Back always leads home, and from home it leaves. */
    private void handleBack() {
        // From the lock screen and from Home, Back leaves. Swallowing it on the
        // lock screen would make the app feel stuck, and it protects nothing:
        // the lock exists to stop the stream, and leaving stops it too.
        if (locked || screen == SCREEN_HOME) {
            finish();
            return;
        }
        // The shortcut editor opened from the bar should hand the desktop back.
        showScreen(screen == SCREEN_SHORTCUTS ? shortcutsReturnTo : SCREEN_HOME);
    }

    /**
     * Back has to be registered two different ways.
     *
     * Predictive back is on by default for apps targeting SDK 35+, and when it is
     * on the platform stops calling {@link #onBackPressed()} entirely — so
     * relying on the override alone would leave Back going straight out of the
     * app from the deck, which is precisely the escape route this release is
     * adding. The dispatcher is the live path on this phone; the override is
     * what runs at API 31 and 32.
     */
    private void registerBackHandler() {
        if (android.os.Build.VERSION.SDK_INT >= 33) registerPredictiveBack();
    }

    /** Kept separate so API 32 and below never verify a class they do not have. */
    @android.annotation.TargetApi(33)
    private void registerPredictiveBack() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBack);
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ------------------------------------------------------------------ //
    // Connection
    // ------------------------------------------------------------------ //
    /**
     * Pick a route, then dial it.
     *
     * The address the phone should use depends on which network it is standing
     * on, so it is measured at connect time rather than remembered. An address
     * supplied by intent extras skips the probe: that path is scripted and its
     * whole point is to go exactly where it was told.
     */
    private void connect() {
        if (!endpoints.isConfigured() && host == null) {
            showAddressScreen();
            return;
        }
        wantStream = true;
        showScreen(SCREEN_DECK);

        if (!endpoints.isConfigured()) {           // intent-supplied address only
            home.setState(HomeView.PHASE_CONNECTING, host + ":" + port);
            startStreamIfReady();
            return;
        }

        final int generation = ++streamGeneration;
        home.setState(HomeView.PHASE_FINDING, null);
        endpoints.choose(new Endpoints.Listener() {
            @Override public void onChosen(Endpoints.Route route) {
                if (generation != streamGeneration || !wantStream) return;
                host = route.host;
                port = route.port;
                prefs.edit().putString(Prefs.HOST, host).putInt(Prefs.PORT, port).apply();
                MainActivity.this.route = route.which;
                home.setRoute(route.which);
                home.setState(HomeView.PHASE_CONNECTING, route.describe());
                if (settingsView != null) settingsView.setAddress(host, port);
                startStreamIfReady();
            }

            @Override public void onNothingReachable() {
                if (generation != streamGeneration || !wantStream) return;
                wantStream = false;
                route = -1;
                home.setRoute(-1);
                home.setState(HomeView.PHASE_ERROR, reachabilityHint());
                showScreen(SCREEN_HOME);
            }
        });
    }

    /**
     * Why nothing answered, phrased as the next thing to check rather than as
     * an error code — the two causes are almost always a sleeping laptop or a
     * Tailscale toggle that is off.
     */
    private String reachabilityHint() {
        if (endpoints.hasBoth()) {
            return "Neither address answered. Is the host running on the laptop, "
                    + "and is Tailscale switched on here?";
        }
        return endpoints.homeHost != null
                ? "No answer on the home address. Add a tailnet address to reach "
                        + "the laptop when you are out."
                : "No answer on the tailnet. Is Tailscale switched on, and is the "
                        + "host running on the laptop?";
    }

    private void startStreamIfReady() {
        if (!wantStream || locked || holder == null || host == null) return;
        dropStream();
        lastBytes = 0;
        lastNanos = System.nanoTime();
        lastPhase = -1;

        // A stopped stream's thread can still be inside its catch block and will
        // deliver one last report after this one has started. Without the
        // generation check that late failure tears down the connection the user
        // just made and bounces them back to Home.
        final int generation = ++streamGeneration;

        stream = new H264Stream(host, port, holder.getSurface(),
                (bytes, decoded, dropped, p50, p99, state) ->
                        onStats(generation, bytes, decoded, dropped, p50, p99, state),
                prefs);
        stream.setSizeListener((w, h) -> runOnUiThread(() -> {
            if (generation != streamGeneration) return;
            if (w <= 0 || h <= 0 || (streamSize[0] == w && streamSize[1] == h)) return;
            streamSize[0] = w;
            streamSize[1] = h;
            videoPane.requestLayout();   // re-letterbox to the real aspect
        }));
        stream.setThemeListener(palette -> runOnUiThread(() -> {
            if (generation == streamGeneration) applyTheme(palette);
        }));
        stream.setProblemListener(new H264Stream.ProblemListener() {
            @Override
            public void onConnectFailing(int failures, String reason) {
                runOnUiThread(() -> {
                    if (generation != streamGeneration) return;
                    if (failures >= FAILURES_BEFORE_GIVING_UP) {
                        stopStreaming();
                        home.setState(HomeView.PHASE_ERROR, reason);
                        showScreen(SCREEN_HOME);
                    } else {
                        home.setState(HomeView.PHASE_ERROR, reason);
                    }
                });
            }

            @Override
            public void onPinMismatch(String reason) {
                runOnUiThread(() -> {
                    if (generation != streamGeneration) return;
                    stopStreaming();
                    home.setState(HomeView.PHASE_BLOCKED,
                            "The laptop answered with a different certificate than "
                                    + "the one trusted here. If you reinstalled the host, "
                                    + "clear it in Settings.");
                    showScreen(SCREEN_HOME);
                });
            }
        });
        new Thread(stream, "folddeck-stream").start();
    }

    /** Stop streaming and stay stopped until Connect is pressed again. */
    private void stopStreaming() {
        wantStream = false;
        dropStream();
    }

    /**
     * Drop the socket without changing intent, so locking the phone pauses the
     * desktop and unlocking resumes it.
     */
    private void dropStream() {
        // Clear latched modifiers and any held mouse button before dropping the
        // socket, so on-screen state matches the host (which also releases
        // everything on disconnect).
        if (keyboard != null) keyboard.releaseAll();
        if (pointerPad != null) pointerPad.releaseAll();
        if (stream != null) {
            stream.stop();
            stream = null;
            streamGeneration++;   // silence anything still in flight from it
        }
    }

    private void idle() {
        lastPhase = HomeView.PHASE_IDLE;
        home.setState(HomeView.PHASE_IDLE, null);
    }

    private void changePin() {
        lock.reset();
        showScreen(SCREEN_HOME);
        relock();       // the lock screen sees no PIN configured and runs setup
    }

    private void forgetHostCertificate() {
        if (host != null) Secure.clearPin(prefs, host, port);
        stopStreaming();
        idle();
        home.setState(HomeView.PHASE_IDLE,
                "Certificate cleared. The next connection will trust whatever "
                        + "answers, so make it one you expect.");
        showScreen(SCREEN_HOME);
    }

    // ------------------------------------------------------------------ //
    // Surface + lock lifecycle
    // ------------------------------------------------------------------ //
    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        startStreamIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        dropStream();
    }

    private void onUnlocked() {
        locked = false;
        lockOverlay.setVisibility(View.GONE);
        hideSystemBars();          // the biometric dialog brings the bars back

        if (wantStream) {
            showScreen(SCREEN_DECK);
            startStreamIfReady();
        } else if (Prefs.autoConnect(prefs) && (endpoints.isConfigured() || host != null)) {
            connect();
        } else {
            showScreen(SCREEN_HOME);
        }
    }

    /**
     * Re-lock and drop the connection.
     *
     * Stopping the stream matters beyond the phone: the host only captures while
     * a client is connected, so locking the phone also stops the laptop
     * capturing its own screen.
     */
    private void relock() {
        if (locked || lockOverlay == null) return;
        locked = true;
        dropStream();
        lockOverlay.setVisibility(View.VISIBLE);
        lockOverlay.onShown();
        updatePointerCapture();
    }

    // ------------------------------------------------------------------ //
    private void onStats(int generation, long bytes, int decoded, int dropped,
                         double p50, double p99, String state) {
        long now = System.nanoTime();
        double seconds = (now - lastNanos) / 1e9;
        double mbps = seconds > 0.05 ? (bytes - lastBytes) * 8 / seconds / 1e6 : 0;
        if (seconds > 0.05) {
            lastBytes = bytes;
            lastNanos = now;
        }

        final String text = String.format(Locale.US,
                "%s  %s:%d%n" +
                        "net    %6.2f Mbps   %6.1f MB%n" +
                        "frames %6d decoded  %4d dropped%n" +
                        "decode p50 %5.2f ms   p99 %5.2f ms",
                state, host, port, mbps, bytes / 1e6, decoded, dropped, p50, p99);

        final int phase = phaseOf(state);
        final String detail = phase == HomeView.PHASE_STREAMING
                ? String.format(Locale.US, "%s:%d · %.1f Mbps", host, port, mbps)
                : null;

        runOnUiThread(() -> {
            if (generation != streamGeneration) return;
            hud.setText(text);
            // Home is behind the deck while streaming; repainting it four times a
            // second would be wasted work, so only phase changes get through.
            // The error text itself arrives via the problem listener.
            if (phase != lastPhase && phase != HomeView.PHASE_ERROR) {
                lastPhase = phase;
                home.setState(phase, detail);
            }
        });
    }

    /**
     * The stream reports its state as the string the HUD prints. Mapping it back
     * here keeps H264Stream unaware of the UI, at the cost of this switch.
     */
    private static int phaseOf(String state) {
        if (state.startsWith("streaming")) return HomeView.PHASE_STREAMING;
        if (state.startsWith("connecting")) return HomeView.PHASE_CONNECTING;
        if (state.startsWith("SECURITY")) return HomeView.PHASE_BLOCKED;
        if (state.startsWith("error")) return HomeView.PHASE_ERROR;
        return HomeView.PHASE_IDLE;
    }

    // Every lifecycle callback below can fire while the crash report is on
    // screen, where none of the UI exists. Guard rather than assume.
    @Override
    protected void onStart() {
        super.onStart();
        if (lockOverlay != null && locked) {
            lockOverlay.setVisibility(View.VISIBLE);
            // post() so the window is actually attached before the biometric
            // prompt goes up; onStart is too early for a system dialog.
            lockOverlay.post(lockOverlay::onShown);
        }
    }

    /**
     * Re-lock whenever the app leaves the foreground, so leaving it sitting in
     * Recents is not the same as leaving it unlocked.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (lockOverlay != null) lockOverlay.cancelBiometric();
        relock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (stream != null) stream.stop();
    }
}
