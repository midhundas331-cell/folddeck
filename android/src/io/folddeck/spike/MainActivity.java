package io.folddeck.spike;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The app: a lock, a home screen, and the deck the desktop streams onto.
 * Screens are sibling views in one FrameLayout rather than separate Activities,
 * because the deck must not be torn down and rebuilt every time you glance at
 * Settings — the SurfaceView would be destroyed with it, dropping the socket and
 * making the laptop stop capturing.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    /** Initial weights used only before the first measured responsive layout. */
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
            SCREEN_ADDRESS = 4, SCREEN_SHORTCUTS = 5;

    static boolean preserveDraftDuringTheme(int targetScreen) {
        return targetScreen == SCREEN_ADDRESS || targetScreen == SCREEN_SHORTCUTS;
    }

    private FrameLayout root;
    private LinearLayout deck;
    private FrameLayout deckStage;
    private View trackpadSlot;
    private boolean raisedPad;
    private Runnable fitVideo;
    private Chassis.Lid lid;
    private Chassis.Base base;
    private Chassis.Hinge hinge;

    private SurfaceView surfaceView;
    private TextView hud;
    private KeyboardView keyboard;
    private InputDeckLayout inputDeck;
    private TrackpadDeck trackpadDeck;
    private PointerPad pointerPad;
    private FrameLayout videoPane;

    private HomeView home;
    private SettingsView settingsView;
    private AddressView addressView;
    private ShortcutsView shortcutsView;
    private ShortcutBar shortcutBar;
    private TextView coverToggle;
    /** Unfolded only: the desktop alone, centred, no frame -- for watching video. */
    private TextView theatreToggle;
    private boolean theatre;
    /** Folded, screen-only: a slim wheel in the band beside the desktop. */
    private TrackpadDeck coverWheel;
    private final CoverConsole coverConsole = new CoverConsole();
    private boolean coverDisplay;
    private boolean coverInputMode;
    private Animator coverAnimator;
    /** Where the shortcut editor returns to: the deck if it was opened from the bar. */
    private int shortcutsReturnTo = SCREEN_HOME;
    private int settingsReturnTo = SCREEN_HOME;
    private ResizeOverlay resizeOverlay;
    private int resizePosture = -1;
    private float monitorFraction = -1f, padScale = 1f, barScale = 1f;
    private boolean resizeFramePending;
    private boolean barBesideMonitor;
    private float padHeight = -1f, wheelWidth = 28f, buttonHeight = 40f;
    private float padHeightLimit = 500f;
    private boolean deckFlip, padLeft, barFlip, padTop, splitOverrideActive, splitOverride;
    private WorkspaceGeometry.ResizeState resizeStartState;
    private PhoneSharing sharing;
    private final Runnable resizeFrame = () -> {
        resizeFramePending = false;
        applyPosture(false);
        if (resizeOverlay != null) resizeOverlay.invalidate();
    };

    private H264Stream stream;
    private Lock lock;
    private LockOverlay lockOverlay;

    private boolean locked = true;
    /** False while a real keyboard stands in for the on-screen one. */
    private boolean keyboardShown = true;
    /** Keyboard or dedicated trackpad occupies the lower deck. */
    private boolean controlsShown = true;
    /** Physical keys down on the laptop, so each gets its release even if the
     *  screen changed while it was held. */
    private final java.util.Set<Integer> heldKeys = new java.util.HashSet<>();
    /** True once the user has chorded left+right to hand the mouse back to the
     *  phone. Sticky, because the chord is a toggle: re-grabbing the moment the
     *  desktop is on screen again would make it impossible to ever keep. */
    private boolean mouseHandedBack = false;
    /** True from pressing Connect until Disconnect: survives locking, so
     *  unlocking puts you back on the desktop you were using. */
    private boolean wantStream = false;
    /** Leave Home for the desktop once the stream has drawn its first frame. */
    private boolean revealOnFrame = false;
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
        lastMobile = onMobileData();
        try {
            getSystemService(android.net.ConnectivityManager.class)
                    .registerDefaultNetworkCallback(networkWatch);
        } catch (RuntimeException e) {
            android.util.Log.w("FoldDeck", "no network callback", e);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

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
        sharing = new PhoneSharing(this, prefs);
        sharing.changed = this::updateSharing;
        // Before any view exists: every screen reads the palette and font as it
        // builds. The laptop's last theme, so the app opens in it offline too.
        Ui.loadFonts(this);
        Ui.apply(prefs.getString(Prefs.THEME, Ui.DEFAULT_PALETTE));

        // Endpoints only come from this app's private preferences. Accepting host
        // extras on the exported launcher Activity lets another app steer the
        // TLS client (and its pairing credential) toward an attacker.
        endpoints = Endpoints.load(prefs);
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

        updateSharing();

        // The lock sits above everything and is opaque, so nothing of the desktop
        // shows behind it.
        lock = new Lock(this);
        lockOverlay = new LockOverlay(this, lock, this::onUnlocked);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyPosture(false);
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
                if (theatre || screen != SCREEN_DECK || locked) return;
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(width);
                paint.setColor(Ui.mix(Ui.BG, Ui.ACCENT, 0.35f));
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
        for (View v : new View[]{root, lid, base, hinge, keyboard, inputDeck,
                trackpadDeck, pointerPad, coverWheel}) v.invalidate();
        for (TextView b : new TextView[]{coverToggle, theatreToggle}) {
            b.setTextColor(Ui.TEXT);
            b.setBackground(Ui.pressable(Ui.floatingPanel(Ui.SURFACE_HI, Ui.ACCENT, 2, this)));
        }
        if (resizeOverlay != null) resizeOverlay.refresh();
        configureTrackpad();
        shortcutBar.rebuild();

        home = (HomeView) replace(home, newHome());
        home.setRoute(route);
        lastPhase = -1;               // the next stats tick restates connection state

        LockOverlay oldLock = lockOverlay;
        lockOverlay = new LockOverlay(this, lock, this::onUnlocked);
        // From the flag, not the old view: that may still be fading out after an unlock.
        lockOverlay.setVisibility(locked ? View.VISIBLE : View.GONE);
        replace(oldLock, lockOverlay);

        // Built lazily, so dropping them is enough — unless one is on screen.
        if (settingsView != null) root.removeView(settingsView);
        settingsView = null;
        if (screen == SCREEN_SETTINGS) showSettings();
        // Address and shortcut editors may contain text/reorder drafts that are
        // not persisted yet. Keep the live view until the user saves or leaves;
        // rebuilding it for palette colours would silently discard their work.
        else if (preserveDraftDuringTheme(screen)) showScreen(screen);
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
        hud.setTypeface(Ui.MONO);
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
        pointerPad.setMode(Prefs.pointerMode(prefs));
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
            @Override public void onResize() { showResize(); }
            @Override public void onAppSettings() { showSettings(); }
            @Override public void onShortcut(Shortcuts.Item item) { sendChord(item); }
            @Override public void onEditShortcuts() { showShortcuts(screen); }
            @Override public void onReordered(java.util.List<Shortcuts.Item> items) {
                Shortcuts.save(prefs, items);
            }
            @Override public boolean onToggleTouchMode() {
                int mode = pointerPad.toggleMode();
                prefs.edit().putInt(Prefs.POINTER_MODE, mode).apply();
                return mode == PointerPad.MODE_TRACKPAD;
            }
        });
        Ui.elevate(shortcutBar, this, Ui.FLOAT_MID_DP);
        shortcutBar.setItems(Shortcuts.load(prefs));
        shortcutBar.setTrackpadMode(pointerPad.getMode() == PointerPad.MODE_TRACKPAD);
        videoPane.addView(shortcutBar, new FrameLayout.LayoutParams(0, 0));
        videoPane.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        coverWheel = new TrackpadDeck(this);
        coverWheel.setWheelOnly();
        coverWheel.setInset(Ui.dp(this, 2));
        coverWheel.setListener(new TrackpadDeck.Listener() {
            // Only the wheel is drawn; a stray second finger moves and clicks nothing.
            @Override public void onMove(float dx, float dy) { }
            @Override public void onButton(int button, boolean down) { }
            @Override public void onScroll(int vertical) {
                if (stream != null) stream.sendScroll(vertical, 0);
            }
        });
        coverWheel.setVisibility(View.GONE);
        videoPane.addView(coverWheel, new FrameLayout.LayoutParams(0, 0));

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
        fitVideo = () -> {
            int paneW = videoPane.getWidth(), paneH = videoPane.getHeight();
            if (paneW <= 0 || paneH <= 0) return;
            if (fitRaisedPadVideo(paneW, paneH)) return;
            float aspect = (float) streamSize[0] / streamSize[1];
            boolean shortcutInVideo = shortcutBar.getParent() == videoPane
                    && shortcutBar.getVisibility() == View.VISIBLE;
            if (!shortcutInVideo) {
                // Full-monitor cover mode and the open-portrait console both keep
                // controls outside the lid. Fit the complete desktop at maximum
                // size; never crop it merely to fill the phone aspect.
                int vw = paneW, vh = Math.round(vw / aspect);
                if (vh > paneH) {
                    vh = paneH;
                    vw = Math.round(vh * aspect);
                }
                int align = theatre ? 1 : Prefs.desktopAlign(prefs);
                int x = (paneW - vw) * align / 2, y = (paneH - vh) * align / 2;
                place(surfaceView, x, y, vw, vh);
                if (coverWheel.getVisibility() == View.VISIBLE) {
                    // In the empty band beside the desktop, on the side it leaves
                    // widest; over the image's edge if there is no band.
                    int ww = Ui.dp(this, COVER_WHEEL_DP), wh = paneH;
                    place(coverWheel, align == 2 ? 0 : paneW - ww, (paneH - wh) / 2, ww, wh);
                }
                pointerPad.setVideoBounds(x, y, x + vw, y + vh);
                lid.setScreen(lid.getPaddingLeft() + x, lid.getPaddingTop() + y,
                        lid.getPaddingLeft() + x + vw, lid.getPaddingTop() + y + vh);
                return;
            }
            // A side band too thin to hold even one column of buttons is not worth
            // turning the deck sideways for.
            boolean column = paneW - (int) (paneH * aspect) >= Ui.dp(this, ShortcutBar.COLUMN_MIN_DP);
            barBesideMonitor = column;
            int x, y, vw, vh;
            boolean grid = false;
            boolean flip = barFlip;
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
                vh = paneH - (chassis ? 2 : controlsShown ? 1 : 0) * line;
                int naturalWidth = paneW - (int) (vh * aspect);
                int deckW = Math.round(Motion.clamp(naturalWidth * barScale,
                        Ui.dp(this, ShortcutBar.COLUMN_MIN_DP), paneW * 0.6f));
                vw = Math.min(paneW - deckW, (int) (vh * aspect));
                vh = Math.round(vw / aspect);
                x = flip ? 0 : paneW - vw;
                y = (paneH - vh) / 2;
                place(shortcutBar, flip ? vw : 0, 0, deckW, paneH);
                shortcutBar.setCompact(deckW < Ui.dp(this, ShortcutBar.COMPACT_BELOW_DP));
            } else {
                int band = paneH - (int) (paneW / aspect);
                // With the keyboard gone the band is no longer capped: the deck
                // takes all of it, as a grid of rows once it is taller than one.
                band = Math.max(Ui.dp(this, ShortcutBar.ROW_MIN_DP), controlsShown
                        ? Math.min(band, Ui.dp(this, ShortcutBar.ROW_MAX_DP)) : band);
                band = Math.round(Motion.clamp(band * barScale,
                        Math.min(paneH / 2f, Ui.dp(this, 56)), Math.max(0, paneH - Ui.dp(this, 48))));
                grid = band > Ui.dp(this, ShortcutBar.ROW_MAX_DP);
                vh = Math.min(paneH - band, (int) (paneW / aspect));
                vw = (int) (vh * aspect);
                // Any height the cap leaves over is split around the pair.
                int top = (paneH - vh - band) / 2;
                x = coverInputMode ? paneW - vw : (paneW - vw) * Prefs.desktopAlign(prefs) / 2;
                int pairTop = coverInputMode ? 0 : top;
                y = flip ? pairTop + band : pairTop;
                place(shortcutBar, 0, flip ? pairTop : y + vh, paneW, band);
                shortcutBar.setCompact(false);
            }
            place(surfaceView, x, y, vw, vh);
            shortcutBar.setVertical(column || grid && !controlsShown);
            // A deck side lying on the app frame stays open; see ShortcutBar.edges.
            boolean flushLeft = lid.getPaddingLeft() == 0, flushTop = lid.getPaddingTop() == 0;
            boolean flushRight = lid.getPaddingRight() == 0;
            if (!flip) {
                shortcutBar.setEdges(!flushLeft, !(column && flushTop),
                        !(!column && flushRight), controlsShown);
            } else if (column) {
                shortcutBar.setEdges(true, !flushTop, !flushRight, controlsShown);
            } else {
                shortcutBar.setEdges(!flushLeft, !flushTop, !flushRight, true);
            }
            pointerPad.setVideoBounds(x, y, x + vw, y + vh);
            // videoPane sits inside the lid's content box, so its origin in lid
            // coordinates is the lid's top-left padding.
            lid.setScreen(lid.getPaddingLeft() + x, lid.getPaddingTop() + y,
                    lid.getPaddingLeft() + x + vw, lid.getPaddingTop() + y + vh);
        };
        videoPane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> fitVideo.run());

        keyboard = new KeyboardView(this);
        // No elevation: caps paint their own depth, and a raised keyboard would
        // draw over (and take touches from) the split trackpad laid in its middle.
        keyboard.setKeyListener((code, down) -> {
            if (stream != null) stream.sendKey(code, down);
        });
        // The TrackPoint drives the same virtual cursor the pointer pad uses, so
        // the nub and the pad never disagree about where the pointer is.
        keyboard.setTrackPointListener((dx, dy) -> {
            if (pointerPad != null) {
                float speed = Prefs.trackpointSens(prefs);
                pointerPad.moveRelative(dx * speed, dy * speed);
            }
        });
        keyboard.setHaptics(Prefs.keyboardHaptics(prefs));
        keyboard.setLook(Prefs.keyLabelScale(prefs), Prefs.keyGapScale(prefs),
                Prefs.showTrackpoint(prefs), Prefs.showFnRow(prefs));

        trackpadDeck = new TrackpadDeck(this);
        trackpadDeck.setListener(new TrackpadDeck.Listener() {
            @Override public void onMove(float dx, float dy) {
                pointerPad.moveRelative(dx, dy);
            }
            @Override public void onButton(int button, boolean down) {
                pointerPad.buttonFrom(PointerButtons.SOURCE_TRACKPAD, button, down);
            }
            @Override public void onScroll(int vertical) {
                if (stream != null) stream.sendScroll(vertical, 0);
            }
        });
        configureTrackpad();

        inputDeck = new InputDeckLayout(this);
        inputDeck.setKeyboardView(keyboard);
        // Reserve keyboard space while the real pad can cross workspace bands.
        trackpadSlot = new View(this);
        inputDeck.setTrackpadView(trackpadSlot);

        lid = new Chassis.Lid(this);
        lid.addView(videoPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        base = new Chassis.Base(this);
        base.addView(inputDeck, new FrameLayout.LayoutParams(
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
        deck.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (r - l != or_ - ol || b - t != ob - ot) applyPosture(false);
        });

        deckStage = new FrameLayout(this) {
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                layoutFloatingTrackpad();
                fitVideo.run();
            }
        };
        deckStage.addView(deck, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        deckStage.addView(trackpadDeck, new FrameLayout.LayoutParams(0, 0));
        coverToggle = floatingButton("⌨", "Show keyboard and trackpad",
                Prefs.COVER_TOGGLE_X, Prefs.COVER_TOGGLE_Y, v -> toggleCoverConsole());
        theatreToggle = floatingButton(ICON_FULLSCREEN, "Full screen",
                Prefs.THEATRE_TOGGLE_X, Prefs.THEATRE_TOGGLE_Y, v -> setTheatre(!theatre));
        theatreToggle.setTypeface(Ui.ICONS);
        deckStage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            placeFloating(coverToggle, Prefs.COVER_TOGGLE_X, Prefs.COVER_TOGGLE_Y);
            placeFloating(theatreToggle, Prefs.THEATRE_TOGGLE_X, Prefs.THEATRE_TOGGLE_Y);
        });
        return deckStage;
    }

    private void layoutFloatingTrackpad() {
        boolean shown = trackpadSlot.isShown() && base.getVisibility() == View.VISIBLE;
        trackpadDeck.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) return;
        android.graphics.Rect slot = new android.graphics.Rect(0, 0, trackpadSlot.getWidth(), trackpadSlot.getHeight());
        deckStage.offsetDescendantRectToMyCoords(trackpadSlot, slot);
        int height = raisedPad && padHeight > 0
                ? (deckFlip ? Math.min(Math.max(0, deckStage.getHeight() - slot.top), Ui.dp(this, padHeight))
                            : Math.min(slot.bottom, Ui.dp(this, padHeight)))
                : slot.height();
        int top = deckFlip ? slot.top : slot.bottom - height;
        boolean split = (splitOverrideActive ? splitOverride : Prefs.layoutSplit(Prefs.layout(prefs, posture()))) && keyboardShown;
        trackpadDeck.setInset(split ? keyboard.keyGap(keyboard.getWidth()) : Ui.dp(this, 6));
        place(trackpadDeck, slot.left, top, slot.width(), height);
        trackpadDeck.measure(View.MeasureSpec.makeMeasureSpec(slot.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        trackpadDeck.layout(slot.left, top, slot.right, top + height);
        trackpadDeck.setAlpha(base.getAlpha());
        trackpadDeck.setTranslationY(0f);
    }

    /** Fit video/shortcuts around a raised pad instead of painting underneath it. */
    private boolean fitRaisedPadVideo(int paneW, int paneH) {
        if (!raisedPad || padHeight <= 0 || !trackpadDeck.isShown()
                || shortcutBar.getParent() != videoPane || shortcutBar.getVisibility() != View.VISIBLE) return false;
        android.graphics.Rect pane = new android.graphics.Rect(0, 0, paneW, paneH);
        deckStage.offsetDescendantRectToMyCoords(videoPane, pane);
        if (trackpadDeck.getTop() >= pane.bottom || trackpadDeck.getBottom() <= pane.top) return false;
        int gap = Ui.dp(this, 8);
        int left = Math.max(0, trackpadDeck.getLeft() - pane.left - gap);
        int right = Math.min(paneW, trackpadDeck.getRight() - pane.left + gap);
        WorkspaceGeometry.Top top = WorkspaceGeometry.top(paneW, paneH, left, right, gap,
                Ui.dp(this, 64), (float) streamSize[0] / Math.max(1, streamSize[1]), barFlip);
        place(shortcutBar, top.bar.x, top.bar.y, top.bar.w, top.bar.h);
        place(surfaceView, top.monitor.x, top.monitor.y, top.monitor.w, top.monitor.h);
        shortcutBar.setCompact(top.bar.w < Ui.dp(this, ShortcutBar.COMPACT_BELOW_DP));
        shortcutBar.setVertical(true);
        shortcutBar.setEdges(true, true, true, true);
        barBesideMonitor = true;
        pointerPad.setVideoBounds(top.monitor.x, top.monitor.y, top.monitor.right(), top.monitor.bottom());
        lid.setScreen(lid.getPaddingLeft() + top.monitor.x, lid.getPaddingTop() + top.monitor.y,
                lid.getPaddingLeft() + top.monitor.right(), lid.getPaddingTop() + top.monitor.bottom());
        return true;
    }

    /**
     * A floating square button over the deck. Tap acts; drag moves it out of the
     * way, remembered across launches. Laid out at the top-left corner and
     * positioned by translation, so a drag and a stored position are the same thing.
     */
    private TextView floatingButton(String glyph, String description, String keyX, String keyY,
                                    View.OnClickListener onTap) {
        TextView b = Ui.text(this, glyph, Ui.T_TITLE, Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setBackground(Ui.pressable(Ui.floatingPanel(Ui.SURFACE_HI, Ui.ACCENT, 2, this)));
        Ui.elevate(b, this, Ui.FLOAT_ELEVATION_DP);
        Motion.draggable(b, Ui.dp(this, COVER_TOGGLE_MARGIN_DP), (tx, ty) -> {
            float[] travel = coverToggleTravel();
            int m = Ui.dp(this, COVER_TOGGLE_MARGIN_DP);
            prefs.edit()
                    .putFloat(keyX, travel[0] > 0 ? (tx - m) / travel[0] : 1f)
                    .putFloat(keyY, travel[1] > 0 ? (ty - m) / travel[1] : 0f)
                    .apply();
        });
        b.setOnClickListener(onTap);
        b.setVisibility(View.GONE);
        deckStage.addView(b, new FrameLayout.LayoutParams(
                Ui.dp(this, COVER_TOGGLE_DP), Ui.dp(this, COVER_TOGGLE_DP),
                Gravity.TOP | Gravity.START));
        return b;
    }

    private static final int COVER_TOGGLE_DP = 54, COVER_TOGGLE_MARGIN_DP = 12;
    /** Nerd Font fullscreen / fullscreen-exit, in assets/fonts/Icons.ttf. */
    private static final String ICON_FULLSCREEN = "\udb80\ude93", ICON_EXIT_FULLSCREEN = "\udb80\ude94";
    private static final int COVER_WHEEL_DP = 40;

    /** How far the cover button can travel inside its margins, x and y, in px. */
    private float[] coverToggleTravel() {
        int size = Ui.dp(this, COVER_TOGGLE_DP), m = Ui.dp(this, COVER_TOGGLE_MARGIN_DP);
        return new float[]{Math.max(0, deckStage.getWidth() - size - 2 * m),
                Math.max(0, deckStage.getHeight() - size - 2 * m)};
    }

    /** Put a floating button where it was last dropped; top-right by default. */
    private void placeFloating(View b, String keyX, String keyY) {
        float[] travel = coverToggleTravel();
        int m = Ui.dp(this, COVER_TOGGLE_MARGIN_DP);
        b.setTranslationX(m + travel[0] * prefs.getFloat(keyX, 1f));
        b.setTranslationY(m + travel[1] * prefs.getFloat(keyY, 0f));
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

    private int posture() {
        Configuration cfg = getResources().getConfiguration();
        if (DeckLayout.isCover(cfg.smallestScreenWidthDp)) return Prefs.POSTURE_COVER;
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Prefs.POSTURE_LANDSCAPE : Prefs.POSTURE_PORTRAIT;
    }

    private void configureTrackpad() {
        if (coverWheel != null) {
            coverWheel.configure(true, false, Prefs.naturalScroll(prefs), 1f,
                    Prefs.keyboardHaptics(prefs), false);
        }
        if (trackpadDeck == null) return;
        trackpadDeck.configure(Prefs.trackpadScroll(prefs),
                Prefs.trackpadLeftHanded(prefs), Prefs.naturalScroll(prefs),
                Prefs.trackpadSens(prefs), Prefs.keyboardHaptics(prefs),
                Prefs.trackpadTap(prefs));
    }

    private void applyCustomization() {
        if (resizeOverlay == null && resizePosture >= 0) loadResize();
        if (pointerPad != null) {
            pointerPad.setMouseSensitivity(Prefs.mouseSens(prefs));
            pointerPad.setMode(Prefs.pointerMode(prefs));
        }
        if (shortcutBar != null && pointerPad != null) {
            shortcutBar.setTrackpadMode(pointerPad.getMode() == PointerPad.MODE_TRACKPAD);
        }
        if (keyboard != null) {
            keyboard.setHaptics(Prefs.keyboardHaptics(prefs));
            keyboard.setLook(Prefs.keyLabelScale(prefs), Prefs.keyGapScale(prefs),
                    Prefs.showTrackpoint(prefs), Prefs.showFnRow(prefs));
        }
        configureTrackpad();
        applyPosture(false);
        updatePointerCapture();
    }

    /**
     * True while a physical keyboard is attached. The Fold has none of its own,
     * so any QWERTY Android reports is one paired over Bluetooth or plugged in.
     */
    private boolean hardKeyboard() {
        Configuration cfg = getResources().getConfiguration();
        return cfg.keyboard == Configuration.KEYBOARD_QWERTY
                && cfg.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /** Allocate the exact measured bands for video, shortcuts, keyboard and pad. */
    private void applyPosture(boolean animateCover) {
        int currentPosture = posture();
        if (resizePosture != currentPosture) {
            if (resizeOverlay != null) finishResize(false);
            resizePosture = currentPosture;
            loadResize();
        }
        if (!animateCover && coverAnimator != null) {
            coverAnimator.cancel();
            coverAnimator = null;
            base.animate().cancel();
            releaseDeckInput();
            base.setAlpha(1f);
            base.setTranslationY(0f);
            base.setScaleX(1f);
            base.setScaleY(1f);
        }
        Configuration cfg = getResources().getConfiguration();
        coverDisplay = DeckLayout.isCover(cfg.smallestScreenWidthDp);
        // The cover screen is landscape only; the inner display follows the user.
        int orientation = coverDisplay ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
        coverConsole.updatePosture(coverDisplay);
        coverInputMode = coverConsole.controlsExpanded();
        if (coverDisplay && theatre) setTheatre(false);   // theatre is an unfolded thing
        coverWheel.setVisibility(coverDisplay && !coverInputMode ? View.VISIBLE : View.GONE);

        boolean preferredKeyboard = !(Prefs.hideKeyboard(prefs) && hardKeyboard());
        int preset = Prefs.layout(prefs, posture());
        // On the cover the preset is what the corner button reveals.
        boolean revealed = !theatre && (!coverDisplay || coverInputMode);
        keyboardShown = revealed && Prefs.layoutHasKeyboard(preset)
                && (coverDisplay || preferredKeyboard);
        boolean trackpadShown = revealed && Prefs.layoutHasTrackpad(preset);
        controlsShown = keyboardShown || trackpadShown;

        if (!keyboardShown) keyboard.releaseAll();
        if (!trackpadShown && trackpadDeck != null) trackpadDeck.cancelInput();

        lid.setSeparated(controlsShown);
        lid.setFlipped(deckFlip);
        syncDeckOrder();
        boolean laptop = controlsShown && laptopPosture();
        lid.setChassis(laptop);
        base.setChassis(laptop);
        hinge.setVisibility(laptop ? View.VISIBLE : View.GONE);

        int totalW = deck.getWidth(), totalH = deck.getHeight();
        if (totalW <= 0 || totalH <= 0) {
            updateCoverToggle();
            return;
        }
        int hingePx = laptop ? Ui.dp(this, Chassis.HINGE_DP) : 0;
        int availablePx = Math.max(0, totalH - hingePx);
        float density = getResources().getDisplayMetrics().density;
        // A split board is a keyboard-sized band; the pad lives inside it.
        boolean split = (splitOverrideActive ? splitOverride : Prefs.layoutSplit(preset))
                && keyboardShown && trackpadShown;
        DeckLayout.Result solved = DeckLayout.solve(totalW / density, availablePx / density,
                cfg.smallestScreenWidthDp, totalW >= totalH, keyboardShown, trackpadShown && !split,
                (float) streamSize[0] / Math.max(1, streamSize[1]), Prefs.controlScale(prefs));
        int lidPx = controlsShown
                ? Math.max(0, Math.min(availablePx, Math.round(solved.lidDp * density)))
                : availablePx;
        if (controlsShown && monitorFraction >= 0f) {
            lidPx = Math.round(DeckLayout.resizedLid(availablePx / density,
                    monitorFraction, keyboardShown, solved.portraitConsole && Prefs.showShortcuts(prefs)) * density);
        }
        boolean showShortcuts = Prefs.showShortcuts(prefs);
        boolean dockShortcuts = solved.portraitConsole && controlsShown && showShortcuts;
        raisedPad = trackpadShown && keyboardShown && (solved.sideBySide || split)
                && showShortcuts && !dockShortcuts && !theatre;
        // Freeze the keyboard's allocation before reserving independent pad height.
        int padding = base.getPaddingTop() + base.getPaddingBottom();
        int referenceHeight = Math.max(0, availablePx - lidPx - padding);
        int gap = Ui.dp(this, 8);
        InputDeckLayout.Boxes reference = InputDeckLayout.computeBoxes(
                Math.max(0, totalW - base.getPaddingLeft() - base.getPaddingRight()), referenceHeight,
                gap, keyboardShown, trackpadShown, solved.sideBySide, dockShortcuts,
                padLeft, padScale, density, split, barScale, padTop);
        int maxBase = Math.max(0, availablePx - Math.round(Math.min(96f * density, availablePx * 0.25f)) - padding);
        padHeightLimit = Math.max(100f, InputDeckLayout.maxPadHeight(reference, maxBase, gap,
                keyboardShown, solved.sideBySide || split) / density);
        if (raisedPad) padHeightLimit = Math.max(100f, (totalH - base.getPaddingBottom()) / density);
        if (trackpadShown && padHeight > 0 && !raisedPad) {
            int required = InputDeckLayout.requiredHeight(reference, gap, Math.round(padHeight * density),
                    keyboardShown, solved.sideBySide || split, dockShortcuts && !split);
            lidPx = Math.min(lidPx, availablePx - Math.min(maxBase, required) - padding);
        }
        int basePx = Math.max(0, availablePx - lidPx);
        // Full screen leaves a docked deck inside the keyboard's base, so it fades
        // out with it. Moving it to the desktop pane mid-transition drew it
        // across the top of the screen for a frame.
        if (theatre) dockShortcuts = shortcutBar.getParent() == inputDeck;
        setShortcutDocked(dockShortcuts);
        shortcutBar.setTwoRows(dockShortcuts);
        shortcutBar.setEdges(true, true, true, true);
        shortcutBar.setVisibility(!showShortcuts || theatre && !dockShortcuts
                || coverDisplay && !coverInputMode
                ? View.GONE : View.VISIBLE);

        // Keep the old controls painted during the collapse while their base moves
        // away. Their input state was released above, and they become GONE at end.
        boolean visualControls = controlsShown || (animateCover && base.getVisibility() == View.VISIBLE);
        if (inputDeck != null) {
            inputDeck.setInputEnabled(!animateCover && controlsShown && resizeOverlay == null);
            trackpadDeck.setInputEnabled(!animateCover && controlsShown && resizeOverlay == null);
            inputDeck.setArrangement(padTop, deckFlip);
            inputDeck.setBarScale(barScale);
            inputDeck.setPadHeight(raisedPad ? -1f : padHeight);
            inputDeck.setReferenceHeight(referenceHeight);
            trackpadDeck.setDimensions(wheelWidth, buttonHeight);
            inputDeck.configure(visualControls && (keyboardShown || !controlsShown),
                    visualControls && (trackpadShown || !controlsShown),
                    solved.sideBySide, dockShortcuts,
                    padLeft, padScale,
                    split);
        }

        if (animateCover) {
            animateDeckBands(lidPx, basePx, hingePx, controlsShown);
        } else {
            setDeckBands(lidPx, basePx, hingePx);
            base.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        }
        videoPane.requestLayout();
        updateCoverToggle();
    }

    private void syncDeckOrder() {
        if (deck == null || lid == null || base == null || hinge == null) return;
        boolean currentlyFlipped = deck.indexOfChild(base) < deck.indexOfChild(lid);
        if (currentlyFlipped == deckFlip) return;
        deck.removeAllViews();
        if (deckFlip) {
            deck.addView(base);
            deck.addView(hinge);
            deck.addView(lid);
        } else {
            deck.addView(lid);
            deck.addView(hinge);
            deck.addView(base);
        }
    }

    private void loadResize() {
        int p = resizePosture >= 0 ? resizePosture : posture();
        monitorFraction = Prefs.resize(prefs, Prefs.RESIZE_MONITOR, p, -1f);
        padScale = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_PAD, p,
                Prefs.trackpadSize(prefs)), 0.7f, 1.5f);
        barScale = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_BAR, p, 1f),
                Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
        padHeight = Prefs.resize(prefs, Prefs.RESIZE_HEIGHT, p, -1f);
        wheelWidth = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_WHEEL, p, 28f), 18f, 64f);
        buttonHeight = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_BUTTONS, p, 40f), 28f, 80f);
        deckFlip = Prefs.resizeFlag(prefs, Prefs.RESIZE_DECK_FLIP, p, false);
        padTop = Prefs.resizeFlag(prefs, Prefs.RESIZE_PAD_TOP, p, false);
        barFlip = Prefs.shortcutsFlip(prefs);
        boolean landscape = p != Prefs.POSTURE_PORTRAIT;
        padLeft = Prefs.trackpadLeft(prefs, landscape);
        splitOverrideActive = false;
    }

    private float resizeValue(int target) {
        if (target == ResizeOverlay.HEIGHT || target == ResizeOverlay.TRACKPAD && !inputDeck.trackpadHorizontal()) return Motion.clamp(((padHeight < 0
                ? trackpadDeck.getHeight() / getResources().getDisplayMetrics().density : padHeight) - 100f) / Math.max(1f, padHeightLimit - 100f), 0, 1);
        if (target == ResizeOverlay.WHEEL) return (wheelWidth - 18f) / 46f;
        if (target == ResizeOverlay.BUTTONS) return (buttonHeight - 28f) / 52f;
        if (target == ResizeOverlay.TRACKPAD && keyboardShown) return (padScale - 0.7f) / 0.8f;
        if (target == ResizeOverlay.BAR) return (barScale - Prefs.BAR_SIZE_MIN) / (Prefs.BAR_SIZE_MAX - Prefs.BAR_SIZE_MIN);
        if (target == ResizeOverlay.MONITOR && !controlsShown) return 1f - (barScale - Prefs.BAR_SIZE_MIN)
                / (Prefs.BAR_SIZE_MAX - Prefs.BAR_SIZE_MIN);
        float available = Math.max(1, lid.getHeight() + base.getHeight());
        float fraction = monitorFraction >= 0f ? monitorFraction : lid.getHeight() / available;
        return Motion.clamp(target == ResizeOverlay.MONITOR ? fraction : 1f - fraction, 0f, 1f);
    }

    private void clearSwapTransforms() {
        for (View v : new View[]{surfaceView, keyboard, trackpadDeck, shortcutBar}) {
            if (v == null) continue;
            v.animate().cancel();
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    private void animateSwapTransition(int[][] beforeBounds) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        View[] panes = {surfaceView, keyboard, trackpadDeck, shortcutBar};
        PathInterpolator spring = new PathInterpolator(0.16f, 1f, 0.30f, 1f);
        int[] loc = new int[2];
        for (int i = 0; i < panes.length; i++) {
            View v = panes[i];
            int[] prev = beforeBounds[i];
            if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0 || prev[2] <= 0 || prev[3] <= 0) {
                continue;
            }
            v.getLocationInWindow(loc);
            float prevCx = prev[0] + prev[2] * 0.5f;
            float prevCy = prev[1] + prev[3] * 0.5f;
            float curCx = loc[0] - v.getTranslationX() + v.getWidth() * 0.5f;
            float curCy = loc[1] - v.getTranslationY() + v.getHeight() * 0.5f;
            float dx = prevCx - curCx;
            float dy = prevCy - curCy;
            float sx = Motion.clamp((float) prev[2] / v.getWidth(), 0.65f, 1.45f);
            float sy = Motion.clamp((float) prev[3] / v.getHeight(), 0.65f, 1.45f);
            if (Math.abs(dx) < 2f && Math.abs(dy) < 2f && Math.abs(sx - 1f) < 0.03f && Math.abs(sy - 1f) < 0.03f) {
                continue;
            }
            v.animate().cancel();
            v.setTranslationX(dx);
            v.setTranslationY(dy);
            v.setScaleX(sx);
            v.setScaleY(sy);
            v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(280).setInterpolator(spring).start();
        }
    }

    private void showResize() {
        if (locked || resizeOverlay != null) return;
        if (theatre) setTheatre(false);
        showScreen(SCREEN_DECK);
        if (coverDisplay && !coverInputMode) {
            coverConsole.toggle();
            applyPosture(false);
        }
        releaseDeckInput();
        pointerPad.releasePointerCapture();
        for (int code : heldKeys) if (stream != null) stream.sendKey(code, false);
        heldKeys.clear();
        resizeOverlay = new ResizeOverlay(this,
                new View[]{surfaceView, keyboard, trackpadDeck, shortcutBar,
                        trackpadDeck, trackpadDeck, trackpadDeck}, new ResizeOverlay.Listener() {
            @Override public float value(int target) { return resizeValue(target); }
            @Override public void beginResize() {
                float density = getResources().getDisplayMetrics().density;
                float available = Math.max(1f, lid.getHeight() + base.getHeight());
                float startMon = monitorFraction >= 0f ? monitorFraction : lid.getHeight() / available;
                float startPadH = padHeight >= 0f ? padHeight
                        : (trackpadDeck.isShown() ? trackpadDeck.getHeight() / density : -1f);
                resizeStartState = new WorkspaceGeometry.ResizeState(
                        startMon, padScale, barScale, startPadH, wheelWidth, buttonHeight);
            }
            @Override public void resizeCorner(int target, int corner, float dxPx, float dyPx,
                                               float overlayW, float overlayH) {
                if (resizeStartState == null) beginResize();
                float density = getResources().getDisplayMetrics().density;
                float deckHeightDp = (lid.getHeight() + base.getHeight()) / density;
                boolean shortcutDocked = shortcutBar.getParent() == inputDeck;
                if (target == ResizeOverlay.KEYBOARD && padHeight < 0 && trackpadDeck.isShown()) {
                    padHeight = resizeStartState.padHeight;
                }
                WorkspaceGeometry.ResizeState next = WorkspaceGeometry.resizeCorner(
                        resizeStartState, target, corner, dxPx, dyPx, overlayW, overlayH,
                        deckHeightDp, density, padHeightLimit, controlsShown, keyboardShown,
                        inputDeck.trackpadHorizontal(), barBesideMonitor, shortcutDocked);
                monitorFraction = next.monitorFraction;
                padScale = next.padScale;
                barScale = next.barScale;
                padHeight = next.padHeight;
                wheelWidth = next.wheelWidth;
                buttonHeight = next.buttonHeight;
                if (!resizeFramePending) {
                    resizeFramePending = true;
                    deck.postOnAnimation(resizeFrame);
                }
            }
            @Override public void swap(int from, int to, float dropX, InputDeckLayout.Box toBox) {
                clearSwapTransforms();
                View[] panes = {surfaceView, keyboard, trackpadDeck, shortcutBar};
                int[][] before = new int[panes.length][4];
                int[] loc = new int[2];
                for (int i = 0; i < panes.length; i++) {
                    View v = panes[i];
                    if (v != null && v.isShown()) {
                        v.getLocationInWindow(loc);
                        before[i][0] = loc[0];
                        before[i][1] = loc[1];
                        before[i][2] = v.getWidth();
                        before[i][3] = v.getHeight();
                    }
                }
                boolean curSplit = (splitOverrideActive ? splitOverride
                        : Prefs.layoutSplit(Prefs.layout(prefs, posture())))
                        && keyboardShown && trackpadDeck.isShown();
                boolean dockShortcuts = shortcutBar.getParent() == inputDeck;
                boolean sideBySide = inputDeck.trackpadHorizontal() && !dockShortcuts && !curSplit;
                WorkspaceGeometry.Arrangement cur = new WorkspaceGeometry.Arrangement(
                        deckFlip, padLeft, barFlip, padTop, curSplit);
                WorkspaceGeometry.Arrangement next = WorkspaceGeometry.swap(
                        cur, from, to, sideBySide, dockShortcuts, dropX, toBox);
                deckFlip = next.deckFlip;
                padLeft = next.padLeft;
                barFlip = next.barFlip;
                padTop = next.padTop;
                if (curSplit && !next.split) {
                    splitOverrideActive = true;
                    splitOverride = false;
                }
                applyPosture(false);
                deckStage.post(() -> {
                    animateSwapTransition(before);
                    if (resizeOverlay != null) resizeOverlay.refresh();
                });
            }
            @Override public void change(int target, float fraction) {
                if (target == ResizeOverlay.KEYBOARD && padHeight < 0 && trackpadDeck.isShown()) {
                    padHeight = trackpadDeck.getHeight() / getResources().getDisplayMetrics().density;
                }
                if (target == ResizeOverlay.HEIGHT || target == ResizeOverlay.TRACKPAD && !inputDeck.trackpadHorizontal())
                    padHeight = 100f + fraction * Math.max(0f, padHeightLimit - 100f);
                else if (target == ResizeOverlay.WHEEL) wheelWidth = 18f + fraction * 46f;
                else if (target == ResizeOverlay.BUTTONS) buttonHeight = 28f + fraction * 52f;
                else if (target == ResizeOverlay.TRACKPAD && keyboardShown) padScale = 0.7f + fraction * 0.8f;
                else if (target == ResizeOverlay.BAR) barScale = Prefs.BAR_SIZE_MIN
                        + fraction * (Prefs.BAR_SIZE_MAX - Prefs.BAR_SIZE_MIN);
                else if (target == ResizeOverlay.MONITOR && !controlsShown) barScale = Prefs.BAR_SIZE_MIN
                        + (1f - fraction) * (Prefs.BAR_SIZE_MAX - Prefs.BAR_SIZE_MIN);
                else {
                    float wanted = target == ResizeOverlay.MONITOR ? fraction : 1f - fraction;
                    float height = (lid.getHeight() + base.getHeight())
                            / getResources().getDisplayMetrics().density;
                    monitorFraction = DeckLayout.resizedLid(height, wanted, keyboardShown,
                            shortcutBar.getParent() == inputDeck) / Math.max(1, height);
                }
                // One layout per display frame; no disk writes or hierarchy rebuilds during a drag.
                if (!resizeFramePending) {
                    resizeFramePending = true;
                    deck.postOnAnimation(resizeFrame);
                }
            }
            @Override public boolean horizontal(int target) {
                if (target == ResizeOverlay.WHEEL) return true;
                if (target == ResizeOverlay.BAR) return shortcutBar.getParent() == videoPane
                        && barBesideMonitor;
                return target == ResizeOverlay.TRACKPAD && keyboardShown
                        && inputDeck.trackpadHorizontal();
            }
            @Override public void reset() {
                clearSwapTransforms();
                monitorFraction = -1f;
                padScale = Prefs.trackpadSize(prefs);
                barScale = 1f;
                padHeight = -1f;
                wheelWidth = 28f;
                buttonHeight = 40f;
                deckFlip = false;
                padTop = false;
                barFlip = false;
                padLeft = posture() == Prefs.POSTURE_PORTRAIT;
                splitOverrideActive = false;
                applyPosture(false);
                resizeOverlay.post(resizeOverlay::refresh);
            }
            @Override public void finish(boolean save) { finishResize(save); }
        });
        deckStage.addView(resizeOverlay, new FrameLayout.LayoutParams(-1, -1));
        resizeOverlay.setElevation(Ui.dp(this, 16));
        inputDeck.setInputEnabled(false);
        trackpadDeck.setInputEnabled(false);
        deck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        updateCoverToggle();
        resizeOverlay.enter();
    }

    private void finishResize(boolean save) {
        if (resizeOverlay == null) return;
        deck.removeCallbacks(resizeFrame);
        resizeFramePending = false;
        clearSwapTransforms();
        if (save) {
            boolean landscape = resizePosture != Prefs.POSTURE_PORTRAIT;
            SharedPreferences.Editor ed = prefs.edit()
                    .putFloat(Prefs.RESIZE_MONITOR + resizePosture, monitorFraction)
                    .putFloat(Prefs.RESIZE_PAD + resizePosture, padScale)
                    .putFloat(Prefs.RESIZE_BAR + resizePosture, barScale)
                    .putFloat(Prefs.RESIZE_HEIGHT + resizePosture, padHeight)
                    .putFloat(Prefs.RESIZE_WHEEL + resizePosture, wheelWidth)
                    .putFloat(Prefs.RESIZE_BUTTONS + resizePosture, buttonHeight)
                    .putBoolean(Prefs.RESIZE_DECK_FLIP + resizePosture, deckFlip)
                    .putBoolean(Prefs.RESIZE_PAD_LEFT + resizePosture, padLeft)
                    .putBoolean(Prefs.RESIZE_BAR_FLIP + resizePosture, barFlip)
                    .putBoolean(Prefs.RESIZE_PAD_TOP + resizePosture, padTop)
                    .putBoolean(Prefs.SHORTCUTS_FLIP, barFlip)
                    .putBoolean(landscape ? Prefs.TRACKPAD_LEFT_LANDSCAPE : Prefs.TRACKPAD_LEFT_PORTRAIT, padLeft);
            if (splitOverrideActive && !splitOverride && resizePosture >= 0 && resizePosture < Prefs.LAYOUT_KEYS.length) {
                ed.putInt(Prefs.LAYOUT_KEYS[resizePosture], Prefs.LAYOUT_BOTH);
            }
            ed.apply();
            splitOverrideActive = false;
        } else loadResize();
        deckStage.removeView(resizeOverlay);
        resizeOverlay = null;
        deck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        inputDeck.setInputEnabled(controlsShown);
        trackpadDeck.setInputEnabled(controlsShown);
        // Invalidate Settings' captured values when returning from the studio.
        if (settingsView != null) root.removeView(settingsView);
        settingsView = null;
        if (resizePosture == posture()) applyPosture(false);
        updatePointerCapture();
    }

    private void setShortcutDocked(boolean dock) {
        boolean already = dock ? shortcutBar.getParent() == inputDeck
                : shortcutBar.getParent() == videoPane;
        if (already) return;
        if (shortcutBar.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) shortcutBar.getParent()).removeView(shortcutBar);
        }
        if (dock) {
            inputDeck.setShortcutView(shortcutBar);
        } else {
            inputDeck.clearShortcutView();
            videoPane.addView(shortcutBar, new FrameLayout.LayoutParams(0, 0));
        }
    }

    private void setDeckBands(int lidPx, int basePx, int hingePx) {
        LinearLayout.LayoutParams lidLp = (LinearLayout.LayoutParams) lid.getLayoutParams();
        LinearLayout.LayoutParams baseLp = (LinearLayout.LayoutParams) base.getLayoutParams();
        LinearLayout.LayoutParams hingeLp = (LinearLayout.LayoutParams) hinge.getLayoutParams();
        lidLp.height = lidPx;
        lidLp.weight = 0f;
        baseLp.height = basePx;
        baseLp.weight = 0f;
        hingeLp.height = hingePx;
        lid.setLayoutParams(lidLp);
        base.setLayoutParams(baseLp);
        hinge.setLayoutParams(hingeLp);
    }

    private void animateDeckBands(int targetLid, int targetBase, int targetHinge,
                                  boolean expanding) {
        if (coverAnimator != null) {
            coverAnimator.cancel();
            releaseDeckInput();
        }
        inputDeck.setInputEnabled(false);
        trackpadDeck.setInputEnabled(false);
        int startLid = lid.getHeight() > 0 ? lid.getHeight() : targetLid;
        int startBase = base.getHeight();
        base.setVisibility(View.VISIBLE);

        ValueAnimator resize = ValueAnimator.ofFloat(0f, 1f);
        resize.setDuration(Motion.COVER_SWITCH_MS);
        resize.setInterpolator(new PathInterpolator(0.18f, 0.88f, 0.22f, 1f));
        resize.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            int lh = Math.round(startLid + (targetLid - startLid) * p);
            int bh = Math.round(startBase + (targetBase - startBase) * p);
            setDeckBands(lh, bh, targetHinge);
        });
        resize.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                setDeckBands(targetLid, targetBase, targetHinge);
                if (!expanding) {
                    releaseDeckInput();
                    base.setVisibility(View.GONE);
                    inputDeck.configure(false, false, false, false, false, 1f, false);
                } else {
                    inputDeck.setInputEnabled(true);
                    trackpadDeck.setInputEnabled(true);
                }
                base.setAlpha(1f);
                base.setTranslationY(0f);
                base.setScaleX(1f);
                base.setScaleY(1f);
                coverAnimator = null;
            }
        });
        coverAnimator = resize;

        if (expanding) {
            base.setAlpha(0f);
            base.setTranslationY(Ui.dp(this, 54));
            base.setScaleX(0.965f);
            base.setScaleY(0.965f);
            base.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(Motion.COVER_SWITCH_MS)
                    .setInterpolator(new OvershootInterpolator(Motion.COVER_OVERSHOOT)).start();
        } else {
            base.animate().alpha(0f).translationY(Ui.dp(this, 44)).scaleX(0.985f).scaleY(0.985f)
                    .setDuration(Motion.COVER_SWITCH_MS - 30)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f)).start();
        }
        resize.start();
    }

    private void releaseDeckInput() {
        if (keyboard != null) keyboard.releaseAll();
        if (trackpadDeck != null) trackpadDeck.cancelInput();
    }

    /**
     * The desktop alone: no keyboard, deck, chassis or accent borders, centred on
     * black. The floating button and Back both leave it.
     */
    private void setTheatre(boolean on) {
        if (theatre == on) return;
        theatre = on;
        // Everything moves to its new place together: the desktop grows into
        // the room the keyboard and shortcuts leave, and they fade on the way.
        android.transition.TransitionSet move = new android.transition.TransitionSet()
                .addTransition(new android.transition.Fade(android.transition.Fade.OUT))
                .addTransition(new android.transition.ChangeBounds())
                .addTransition(new android.transition.Fade(android.transition.Fade.IN))
                .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        move.setDuration(Motion.THEATRE_MS);
        move.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        android.transition.TransitionManager.beginDelayedTransition(root, move);
        int frame = on ? 0 : Ui.dp(this, 2);
        root.setPadding(frame, frame, frame, frame);
        root.invalidate();
        videoPane.setBackgroundColor(on ? android.graphics.Color.BLACK : android.graphics.Color.TRANSPARENT);
        hud.setVisibility(!on && Prefs.showHud(prefs) ? View.VISIBLE : View.GONE);
        applyPosture(false);
    }

    private void toggleCoverConsole() {
        if (!coverDisplay) return;
        coverConsole.toggle();
        coverInputMode = coverConsole.controlsExpanded();
        applyPosture(true);
    }

    private void updateCoverToggle() {
        if (coverToggle == null) return;
        theatreToggle.setVisibility(resizeOverlay == null && !coverDisplay && screen == SCREEN_DECK
                ? View.VISIBLE : View.GONE);
        // Over a film it stays findable but out of the way.
        theatreToggle.animate().alpha(theatre ? 0.25f : 1f).setDuration(Motion.THEATRE_MS);
        theatreToggle.setText(theatre ? ICON_EXIT_FULLSCREEN : ICON_FULLSCREEN);
        theatreToggle.setContentDescription(theatre ? "Exit full screen" : "Full screen");
        // A monitor-only cover preset has nothing to reveal.
        coverToggle.setVisibility(resizeOverlay == null && coverDisplay && screen == SCREEN_DECK
                && Prefs.layout(prefs, Prefs.POSTURE_COVER) != Prefs.LAYOUT_MONITOR
                ? View.VISIBLE : View.GONE);
        boolean expanded = coverConsole.controlsExpanded();
        coverToggle.setText(expanded ? "□" : "⌨");
        coverToggle.setContentDescription(expanded
                ? "Show full monitor" : "Show keyboard and trackpad");
        coverToggle.animate().rotation(expanded ? -4f : 0f)
                .setDuration(Motion.RELEASE_MS)
                .setInterpolator(new OvershootInterpolator(Motion.COVER_OVERSHOOT)).start();
    }

    /**
     * Folding and unfolding does not recreate the Activity — the manifest lists
     * screenSize, screenLayout and orientation in configChanges to keep the
     * Surface and the socket alive across the change. keyboard is there for the
     * same reason: pairing a keyboard is a configuration change too. That means this callback
     * is the only notification the layout gets that the posture moved.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        pinRefreshRate();   // folding swaps displays, and each has its own modes
        if (deck != null) applyPosture(false);
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
        boolean want = screen == SCREEN_DECK && !locked && !mouseHandedBack && resizeOverlay == null
                && Prefs.mouseAutoCapture(prefs) && hasWindowFocus();
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
        if (sharing != null) sharing.focus(hasFocus && !locked);
        if (!hasFocus) releaseDeckInput();
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
        if (!mouseHandedBack || locked || screen != SCREEN_DECK || resizeOverlay != null) return false;
        int both = MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY;
        if ((ev.getButtonState() & both) != both) return false;
        mouseHandedBack = false;
        updatePointerCapture();
        return true;
    }

    // ------------------------------------------------------------------ //
    // Physical keyboard
    //
    // A key from a Bluetooth or USB keyboard carries the kernel's own evdev
    // scancode, which is exactly what the host injects -- so it goes across
    // untranslated, and every layout, modifier and Fn key behaves as the
    // keyboard itself says. Only on the deck: everywhere else (the PIN, the
    // address editor) the keyboard types into the phone.
    // ------------------------------------------------------------------ //
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        return forwardKey(ev) || super.dispatchKeyEvent(ev);
    }

    /**
     * One UI takes the Meta (Super) key for Home before any app sees it, which
     * makes every Hyprland binding unreachable. Samsung's DeX API for remote
     * desktop apps hands it to this activity instead, while it is in front.
     * Reflection, because it is Samsung-only: elsewhere this does nothing.
     */
    private void requestMetaKey(boolean on) {
        try {
            Class<?> wm = Class.forName("com.samsung.android.view.SemWindowManager");
            Object instance = wm.getMethod("getInstance").invoke(null);
            wm.getMethod("requestMetaKeyEvent", android.content.ComponentName.class, boolean.class)
                    .invoke(instance, getComponentName(), on);
        } catch (Throwable t) {
            android.util.Log.w("FoldDeck", "no Samsung meta-key API: " + t);
        }
    }

    private boolean forwardKey(KeyEvent ev) {
        int code = ev.getScanCode();
        // External only: the phone's own volume rocker has scancodes too. Codes
        // above 255 are mouse buttons, which the pointer path already carries.
        if (stream == null || code <= 0 || code > 255
                || ev.getDevice() == null || !ev.getDevice().isExternal()) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (locked || screen != SCREEN_DECK || resizeOverlay != null) return false;
            // Android's own repeats are dropped: the laptop repeats a held key.
            if (heldKeys.add(code)) stream.sendKey(code, true);
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP && heldKeys.remove(code)) {
            stream.sendKey(code, false);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    // Screens
    // ------------------------------------------------------------------ //
    private void showScreen(int which) {
        if (which != SCREEN_DECK && resizeOverlay != null) finishResize(false);
        if (which != SCREEN_DECK) releaseDeckInput();
        int previous = screen;
        screen = which;
        View target = screenView(which), outgoing = screenView(previous);
        boolean animate = previous != which && !locked && outgoing != null
                && outgoing.getVisibility() == View.VISIBLE;
        for (View v : new View[]{home, settingsView, addressView, shortcutsView}) {
            if (v == null || v == target || animate && v == outgoing) continue;
            v.animate().cancel();
            v.setAlpha(1f);
            v.setVisibility(View.GONE);
        }
        target.animate().cancel();
        target.setVisibility(View.VISIBLE);
        // Crossfade by fading whichever screen is on top, so the one underneath
        // is opaque all the way through. The deck is the bottom of the stack:
        // the live desktop shows only when it is where you are going or leaving.
        if (!animate) {
            target.setAlpha(1f);
        } else if (root.indexOfChild(target) > root.indexOfChild(outgoing)) {
            if (target.getAlpha() == 1f) target.setAlpha(0f);
            Motion.fade(target, true, () -> conceal(outgoing));
        } else {
            target.setAlpha(1f);
            Motion.fade(outgoing, false, () -> conceal(outgoing));
        }
        if (previous != which && !locked) {
            if (target instanceof HomeView) ((HomeView) target).enterPage();
            else if (target instanceof Panel) ((Panel) target).enterPage(
                    which == SCREEN_SETTINGS && previous == SCREEN_SHORTCUTS);
        }
        root.invalidate();
        updateCoverToggle();
        hideSystemBars();
        updatePointerCapture();
    }

    private View screenView(int which) {
        return which == SCREEN_HOME ? home
                : which == SCREEN_SETTINGS ? settingsView
                : which == SCREEN_ADDRESS ? addressView
                : which == SCREEN_SHORTCUTS ? shortcutsView : deckStage;
    }

    /** Hide a screen that has finished leaving. The deck is never hidden. */
    private void conceal(View v) {
        if (v == deckStage || v == screenView(screen)) return;
        v.setVisibility(View.GONE);
        v.setAlpha(1f);
    }

    private void showSettings() {
        if (screen != SCREEN_SETTINGS && screen != SCREEN_SHORTCUTS) settingsReturnTo = screen;
        if (settingsView == null) {
            settingsView = new SettingsView(this, prefs, lock, sharing, new SettingsView.Listener() {
                @Override public void onEditAddress()   { showAddressScreen(); }
                @Override public void onMouseSensitivity(float sens) {
                    if (pointerPad != null) pointerPad.setMouseSensitivity(sens);
                }
                @Override public void onChangePin()     { changePin(); }
                @Override public void onForgetHostCertificate() { forgetHostCertificate(); }
                @Override public void onControlsChanged()       { applyCustomization(); }
                @Override public void onAudioChanged(boolean on) {
                    if (stream != null) stream.sendAudio(on);
                }
                @Override public void onEditShortcuts() { showShortcuts(SCREEN_SETTINGS); }
                @Override public void onResize() { showResize(); }
                @Override public void onHudChanged() {
                    hud.setVisibility(!theatre && Prefs.showHud(prefs) ? View.VISIBLE : View.GONE);
                }
            }, () -> showScreen(settingsReturnTo));
            // Added below the lock overlay so locking still covers it.
            root.addView(settingsView, root.indexOfChild(lockOverlay),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        settingsView.setAddress(host, port);
        showScreen(SCREEN_SETTINGS);
    }

    private void updateSharing() {
        if (sharing == null) return;
        if (settingsView != null) settingsView.updateSharing();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PhoneSharing.PERMISSIONS && sharing != null) sharing.permissionResult();
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
        if (resizeOverlay != null) { finishResize(false); return; }
        // From the lock screen and from Home, Back leaves. Swallowing it on the
        // lock screen would make the app feel stuck, and it protects nothing:
        // the lock exists to stop the stream, and leaving stops it too.
        if (locked || screen == SCREEN_HOME) {
            finish();
            return;
        }
        if (theatre) {
            setTheatre(false);
            return;
        }
        if (screen == SCREEN_SETTINGS && settingsView.up()) return;
        // The shortcut editor opened from the bar should hand the desktop back.
        showScreen(screen == SCREEN_SHORTCUTS ? shortcutsReturnTo
                : screen == SCREEN_SETTINGS ? settingsReturnTo : SCREEN_HOME);
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
    @SuppressWarnings("deprecation")
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
     * configured by the user is probed at connection time.
     */
    private void connect() {
        if (!endpoints.isConfigured() && host == null) {
            showAddressScreen();
            return;
        }
        wantStream = true;
        // Home stays up, its dot pulsing, until a frame is on the surface; then
        // it fades off the live desktop. No black screen while the route is found.
        revealOnFrame = true;

        final int generation = ++streamGeneration;
        home.setState(HomeView.PHASE_FINDING, null);
        endpoints.choose(new Endpoints.Listener() {
            @Override public void onChosen(Endpoints.Route route) {
                if (generation != streamGeneration || !wantStream) return;
                home.setState(HomeView.PHASE_CONNECTING, route.describe());
                useRoute(route);
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

    /**
     * Re-measure both routes under a running retry loop. Switch only if the
     * other address answers; otherwise (host restarting, no signal) leave the
     * loop redialling as before.
     */
    private void reroute(int generation) {
        endpoints.choose(new Endpoints.Listener() {
            @Override public void onChosen(Endpoints.Route r) {
                if (generation != streamGeneration || !wantStream || r.host.equals(host)) return;
                useRoute(r);
            }

            @Override public void onNothingReachable() { }
        });
    }

    private void useRoute(Endpoints.Route r) {
        host = r.host;
        port = r.port;
        prefs.edit().putString(Prefs.HOST, host).putInt(Prefs.PORT, port).apply();
        route = r.which;
        home.setRoute(r.which);
        if (settingsView != null) settingsView.setAddress(host, port);
        startStreamIfReady();
        root.removeCallbacks(homeCheck);
        if (route == Endpoints.AWAY && endpoints.hasBoth()) {
            root.postDelayed(homeCheck, HOME_CHECK_MS);
        }
    }

    private static final int HOME_CHECK_MS = 15_000;

    /**
     * On the tailnet but not on mobile data: is home Wi-Fi back? A live tailnet
     * stream never drops on its own when it is, so without this it stays on the
     * slower route until the next reconnect. Switches only when the home address
     * presents this laptop's certificate, so someone else's 192.168.x device on
     * a foreign Wi-Fi is never mistaken for it.
     */
    private final Runnable homeCheck = new Runnable() {
        @Override public void run() {
            if (!wantStream || route != Endpoints.AWAY || !endpoints.hasBoth()) return;
            root.postDelayed(this, HOME_CHECK_MS);
            if (locked || onMobileData()) return;
            final int generation = streamGeneration;
            final String awayHost = host;
            final int awayPort = port;
            new Thread(() -> {
                String laptop = Secure.pinnedFingerprint(prefs, awayHost, awayPort);
                if (!Secure.presents(endpoints.homeHost, endpoints.homePort, laptop, 1500)) return;
                runOnUiThread(() -> {
                    if (generation != streamGeneration || !wantStream
                            || route != Endpoints.AWAY) return;
                    useRoute(new Endpoints.Route(Endpoints.HOME,
                            endpoints.homeHost, endpoints.homePort));
                });
            }, "folddeck-home-check").start();
        }
    };

    private void startStreamIfReady() {
        if (!wantStream || locked || holder == null || host == null) return;
        if (stream != null && stream.isPaused() && stream.sameRoute(host, port)) {
            stream.resume(holder.getSurface());   // same connection; sharing never dropped
            return;
        }
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
        sharing.attach(stream);
        stream.setSizeListener((w, h) -> runOnUiThread(() -> {
            if (generation != streamGeneration) return;
            if (w <= 0 || h <= 0 || (streamSize[0] == w && streamSize[1] == h)) return;
            streamSize[0] = w;
            streamSize[1] = h;
            applyPosture(false);
            videoPane.requestLayout();   // re-letterbox to the real aspect
        }));
        stream.setMobileDataCheck(this::onMobileData);
        stream.setThemeListener(palette -> runOnUiThread(() -> {
            if (generation == streamGeneration) applyTheme(palette);
        }));
        stream.setProblemListener(new H264Stream.ProblemListener() {
            @Override
            public void onConnectFailing(int failures, String reason) {
                runOnUiThread(() -> {
                    if (generation != streamGeneration) return;
                    // A route that worked and now will not reopen is the phone
                    // changing network (Wi-Fi -> mobile data): the LAN address is
                    // gone for good, so measure both again instead of redialling it.
                    // Only after this stream once opened, so a wrong address on a
                    // fresh connect still gives up below.
                    if (failures < FAILURES_BEFORE_GIVING_UP && stream != null && stream.everOpened
                            && endpoints.hasBoth()) {
                        reroute(generation);
                    }
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
                            "Your laptop's identity changed. If you reinstalled FoldDeck "
                                    + "on it, go to Settings › Lock & Security › Reset Laptop Trust.");
                    showScreen(SCREEN_HOME);
                });
            }
        });
        new Thread(stream, "folddeck-stream").start();
    }

    /** Stop streaming and stay stopped until Connect is pressed again. */
    private void stopStreaming() {
        wantStream = false;
        revealOnFrame = false;
        dropStream();
    }

    /**
     * Drop the socket without changing intent, so locking the phone pauses the
     * desktop and unlocking resumes it.
     */
    private void dropStream() {
        if (sharing != null) sharing.disconnect();
        releaseHeldInput();
        if (stream != null) {
            stream.stop();
            stream = null;
            streamGeneration++;   // silence anything still in flight from it
        }
    }

    /**
     * Clear latched modifiers and any held mouse button, so on-screen state matches
     * the host (which also releases everything on disconnect).
     */
    private void releaseHeldInput() {
        if (keyboard != null) keyboard.releaseAll();
        if (trackpadDeck != null) trackpadDeck.cancelInput();
        if (pointerPad != null) pointerPad.releaseAll();
        heldKeys.clear();         // the host releases them as the socket goes
    }

    /**
     * Surface gone or phone locked. With the phone mic/camera shared, keep the
     * connection without video, so laptop apps keep using them in the background;
     * otherwise drop it as before.
     */
    private void pauseStream() {
        if (stream == null || sharing == null || !sharing.keepsConnection()) {
            dropStream();
            return;
        }
        releaseHeldInput();
        stream.pause();
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
        if (endpoints.homeHost != null) Secure.clearPin(prefs, endpoints.homeHost, endpoints.homePort);
        if (endpoints.awayHost != null) Secure.clearPin(prefs, endpoints.awayHost, endpoints.awayPort);
        Secure.clearPins(prefs);
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
        pauseStream();
    }

    private void onUnlocked() {
        locked = false;
        if (sharing != null) sharing.focus(hasWindowFocus());
        LockOverlay leaving = lockOverlay;
        Motion.fade(leaving, false, () -> {
            leaving.setVisibility(View.GONE);
            leaving.setAlpha(1f);
        });
        hideSystemBars();          // the biometric dialog brings the bars back

        if (wantStream) {
            // Back to the desktop by the same road as Connect: Home until a frame.
            showScreen(SCREEN_HOME);
            revealOnFrame = true;
            startStreamIfReady();
        } else if (Prefs.autoConnect(prefs) && (endpoints.isConfigured() || host != null)) {
            connect();
        } else {
            showScreen(SCREEN_HOME);
            home.enterPage();
        }
    }

    /**
     * Re-lock and stop the desktop stream.
     *
     * Stopping it matters beyond the phone: the host only captures while a client
     * wants video, so locking the phone also stops the laptop capturing its own
     * screen. With phone mic/camera sharing on, the connection itself stays
     * (paused, no video) so laptop apps keep the phone inputs.
     */
    private void relock() {
        if (locked || lockOverlay == null) return;
        locked = true;
        pauseStream();
        lockOverlay.animate().cancel();
        lockOverlay.setAlpha(1f);
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
            if (phase == HomeView.PHASE_ERROR || phase == HomeView.PHASE_CONNECTING) releaseDeckInput();
            // Home is behind the deck while streaming; repainting it four times a
            // second would be wasted work, so only phase changes get through.
            // The error text itself arrives via the problem listener.
            if (phase != lastPhase && phase != HomeView.PHASE_ERROR) {
                lastPhase = phase;
                home.setState(phase, detail);
            }
            if (revealOnFrame && decoded > 0 && phase == HomeView.PHASE_STREAMING) {
                revealOnFrame = false;
                // Not if they wandered into Settings meanwhile: Home says Open Desktop.
                if (screen == SCREEN_HOME && !locked) showScreen(SCREEN_DECK);
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
    protected void onResume() {
        super.onResume();
        requestMetaKey(true);
        pinRefreshRate();
        holdLowLatencyWifi(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseDeckInput();
        if (resizeOverlay != null) finishResize(false);
        requestMetaKey(false);
        holdLowLatencyWifi(false);
    }

    /**
     * Keep the panel at its fastest mode while this window is up.
     *
     * Samsung's adaptive refresh watches how often the content changes; a
     * desktop that redraws ~20 times a second dropped the panel to 20-24 Hz
     * (measured 2026-10-26), so a new frame then waited up to ~42 ms for a
     * refresh, and motion waited further for the panel to ramp back up. At
     * 120 Hz -- twice the laptop's 60 -- every laptop frame lands on the next
     * 8 ms refresh, evenly paced.
     */
    private void pinRefreshRate() {
        android.view.Display d = getDisplay();
        if (d == null) return;
        android.view.Display.Mode cur = d.getMode(), best = cur;
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                    && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.preferredDisplayModeId == best.getModeId()) return;
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
    }

    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /**
     * Wi-Fi power save batches incoming packets between beacons; a ping to the
     * phone showed 7 ms typical with 30-50 ms spikes. The low-latency lock turns
     * power save off while this app is in the foreground with the screen on
     * (the system enforces both), which is exactly when it streams.
     */
    private void holdLowLatencyWifi(boolean on) {
        if (wifiLock == null) {
            android.net.wifi.WifiManager wm = getSystemService(android.net.wifi.WifiManager.class);
            if (wm == null) return;
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "folddeck:stream");
            wifiLock.setReferenceCounted(false);
        }
        if (on) wifiLock.acquire(); else wifiLock.release();
    }

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
        if (sharing != null) sharing.close();
        try {
            getSystemService(android.net.ConnectivityManager.class)
                    .unregisterNetworkCallback(networkWatch);
        } catch (RuntimeException ignored) { }   // never registered
        if (stream != null) stream.stop();
    }

    /**
     * Mobile data, as the system routes traffic now. Behind Tailscale the active
     * network is the VPN, which carries its underlying network's transports; if
     * it does not, any Wi-Fi or Ethernet being up means that is what is in use.
     */
    private boolean onMobileData() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return false;
        android.net.NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
        int cell = android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                wifi = android.net.NetworkCapabilities.TRANSPORT_WIFI,
                eth = android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
        if (c != null && (c.hasTransport(cell) || c.hasTransport(wifi) || c.hasTransport(eth))) {
            return c.hasTransport(cell) && !c.hasTransport(wifi) && !c.hasTransport(eth);
        }
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && (nc.hasTransport(wifi) || nc.hasTransport(eth))) return false;
        }
        return true;
    }

    private volatile boolean lastMobile;

    /**
     * A tailnet stream survives the phone moving from someone's Wi-Fi to mobile
     * data (same tailnet IP, no reconnect), so the quality has to follow the
     * network mid-stream, not only at connect.
     */
    private final android.net.ConnectivityManager.NetworkCallback networkWatch =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(android.net.Network n,
                        android.net.NetworkCapabilities caps) {
                    boolean mobile = onMobileData();
                    if (mobile == lastMobile) return;   // signal-strength churn
                    lastMobile = mobile;
                    H264Stream s = stream;
                    if (s != null) s.sendNetwork(mobile);
                }
            };
}
