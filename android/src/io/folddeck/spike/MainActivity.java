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
    private final Predictor predictor = new Predictor();

    private void showPrediction() {
        if (keyboard != null) keyboard.setPrediction(predictor.weights(), predictor.suggestions());
    }
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
    private int resizePosture = -1, resizePreset = -1;
    private float monitorFraction = -1f, padScale = 1f, barScale = 1f;
    private boolean resizeFramePending, resizeSnapped;
    private boolean barBesideMonitor, barAroundPad, splitShown, sideBySideShown;
    private float padHeight = -1f, wheelWidth = 28f, buttonHeight = 40f;
    private float padHeightLimit = 500f;
    private boolean deckFlip, padLeft, barFlip, padTop, splitOverrideActive, splitOverride;
    /** Portrait: the bar has left the keyboard's shelf to sit beside the desktop; and whether it could dock. */
    private boolean barBeside, barCanDock;
    /** The trackpad has left the base for a band under the desktop: asked for, possible, and in effect. */
    private boolean padLid, padCanLift, padInLid;
    /** The studio drag that last docked or undocked the bar. */
    private int barDockGesture = -1;
    private WorkspaceGeometry.ResizeState resizeStartState;
    /** Deck height a divider drag started with, px. */
    private float resizeStartAvailable;
    /** The bar's size at scale 1 in the desktop pane (before limits), px. */
    private float barUnitPx;
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
        Prefs.migrateSizes(prefs);
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
     * the accent, two hairlines wide, as Hyprland's border_size 2 against Omarchy's 1px. Drawn over the children and
     * padded in by the same amount, so nothing — not the edge keys, not the
     * video — is covered, and the lid and base stay symmetric about the crease.
     */
    private FrameLayout framed() {
        final int width = 2 * Ui.hair(this);
        FrameLayout f = new FrameLayout(this) {
            final android.graphics.Paint paint = new android.graphics.Paint();

            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                super.dispatchDraw(canvas);
                if (theatre || screen != SCREEN_DECK || locked) return;
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(width);
                // Solid accent, as Hyprland's col.active_border: FoldDeck is the
                // focused window, so it wears the focused border.
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
            b.setBackground(Ui.pressable(Ui.box(Ui.SURFACE_HI, Ui.ACCENT, this)));
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
                if (down) {   // a click can put the cursor anywhere
                    predictor.lost();
                    showPrediction();
                }
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
            // A trackpad lifted out of the base takes a band along the bottom; the
            // desktop and the bar share what is above it.
            int padBand = lidPadBand(paneH);
            if (padBand > 0) paneH = Math.max(1, paneH - padBand - Ui.dp(this, 8));
            barAroundPad = fitRaisedPadVideo(paneW, paneH);
            if (barAroundPad) return;
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
            // Dragged out of the keyboard's shelf (portrait), the bar is always a
            // column beside the desktop, however little room the desktop leaves.
            boolean beside = barBeside && barCanDock;
            boolean column = beside
                    || paneW - (int) (paneH * aspect) >= Ui.dp(this, ShortcutBar.COLUMN_MIN_DP);
            barBesideMonitor = column;
            int x, y, vw, vh;
            boolean grid = false;
            boolean flip = barFlip;
            if (column) {
                // Full height, flush right, always: no bars above or below the
                // desktop. The deck takes whatever width is left and, when that is
                // narrow (laptop layout off), shrinks to one column of small labels.
                // The image gives up a hairline where a border must show: top and
                // bottom in the laptop posture, where the screen well draws its accent
                // border just outside the image (so it sits level with the deck's);
                // bottom only without the hinge, for the separator line.
                int line = Chassis.Lid.separator(this);
                boolean chassis = lid.getPaddingTop() > 0;
                vh = paneH - (chassis ? 2 : controlsShown ? 1 : 0) * line;
                int naturalWidth = paneW - (int) (vh * aspect);
                barUnitPx = Math.max(naturalWidth, Ui.dp(this, ShortcutBar.COLUMN_MIN_DP));
                int deckW = Math.round(Motion.clamp(barUnitPx * barScale,
                        Ui.dp(this, ShortcutBar.COLUMN_MIN_DP), paneW * 0.6f));
                vw = Math.min(paneW - deckW, (int) (vh * aspect));
                vh = Math.round(vw / aspect);
                // Monitor and deck each hug their outer edge, so resizing the
                // deck moves only the edge it shares with the desktop.
                // (Beside: the desktop centres in what the bar leaves, so a
                // clamped bar never opens a hole on one side only.)
                int free = paneW - deckW - vw;
                x = flip ? (beside ? free / 2 : 0) : deckW + (beside ? free / 2 : free);
                y = (paneH - vh) / 2;
                place(shortcutBar, flip ? paneW - deckW : 0, 0, deckW, paneH);
                shortcutBar.setCompact(deckW < Ui.dp(this, ShortcutBar.COMPACT_BELOW_DP));
            } else {
                int band = paneH - (int) (paneW / aspect);
                // With the keyboard gone the band is no longer capped: the deck
                // takes all of it, as a grid of rows once it is taller than one.
                band = Math.max(Ui.dp(this, ShortcutBar.ROW_MIN_DP), controlsShown
                        ? Math.min(band, Ui.dp(this, ShortcutBar.ROW_MAX_DP)) : band);
                barUnitPx = band;
                band = Math.round(Motion.clamp(band * barScale,
                        Math.min(paneH / 2f, Ui.dp(this, 56)), Math.max(0, paneH - Ui.dp(this, 48))));
                grid = band > Ui.dp(this, ShortcutBar.ROW_MAX_DP);
                vh = Math.min(paneH - band, (int) (paneW / aspect));
                vw = (int) (vh * aspect);
                // The deck sits on the pane's edge with the desktop against it;
                // any height left over goes beyond the desktop. Resizing the deck
                // then moves only the edge it shares with the desktop.
                x = coverInputMode ? paneW - vw : (paneW - vw) * Prefs.desktopAlign(prefs) / 2;
                int pairTop = flip ? 0 : paneH - vh - band;
                y = flip ? band : pairTop;
                place(shortcutBar, 0, flip ? 0 : y + vh, paneW, band);
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
            if (predictor.key(code, down)) showPrediction();
        });
        // A suggestion types the rest of its word and a space, as keystrokes.
        keyboard.setSuggestionListener(word -> {
            for (char ch : predictor.rest(word).toCharArray()) {
                int code = ch == ' ' ? Ev.SPACE : Predictor.code(ch);
                if (code < 0) continue;
                if (stream != null) {
                    stream.sendKey(code, true);
                    stream.sendKey(code, false);
                }
                predictor.key(code, true);
            }
            showPrediction();
        });
        keyboard.setSmart(Prefs.smartKeys(prefs), Prefs.wordSuggestions(prefs));
        new Thread(() -> {
            // Optional assets (android/fetch_words.py); without them the keyboard just stays plain.
            try (java.io.BufferedReader words = new java.io.BufferedReader(
                         new java.io.InputStreamReader(getAssets().open("words.txt")));
                 java.io.BufferedReader next = new java.io.BufferedReader(
                         new java.io.InputStreamReader(getAssets().open("next.txt")))) {
                predictor.load(words, next);
                runOnUiThread(this::showPrediction);
            } catch (java.io.IOException e) {
                android.util.Log.i("FoldDeck", "no word list; smart typing off");
            }
        }, "folddeck-words").start();
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

    /** Height of the trackpad's band at the bottom of the monitor pane; 0 when it lives in the base. */
    private int lidPadBand(int paneH) {
        if (!padInLid || paneH <= 0) return 0;
        float want = padHeight > 0 ? Ui.dp(this, padHeight)
                : Motion.clamp(paneH * 0.3f, Ui.dp(this, 110), Ui.dp(this, 180));
        return Math.round(Motion.clamp(want, Math.min(Ui.dp(this, 100), paneH * 0.6f), paneH * 0.6f));
    }

    private boolean padHorizontal() {
        return !padInLid && inputDeck.trackpadHorizontal();
    }

    private void layoutFloatingTrackpad() {
        if (padInLid) {
            boolean up = videoPane.isShown() && videoPane.getHeight() > 0;
            trackpadDeck.setVisibility(up ? View.VISIBLE : View.GONE);
            if (!up) return;
            android.graphics.Rect pane = new android.graphics.Rect(0, 0, videoPane.getWidth(), videoPane.getHeight());
            deckStage.offsetDescendantRectToMyCoords(videoPane, pane);
            int band = lidPadBand(pane.height()), top = pane.bottom - band;
            trackpadDeck.setInset(Ui.dp(this, 6));
            place(trackpadDeck, pane.left, top, pane.width(), band);
            trackpadDeck.measure(View.MeasureSpec.makeMeasureSpec(pane.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(band, View.MeasureSpec.EXACTLY));
            trackpadDeck.layout(pane.left, top, pane.right, top + band);
            trackpadDeck.setAlpha(1f);
            trackpadDeck.setTranslationY(0f);
            return;
        }
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
        b.setBackground(Ui.pressable(Ui.box(Ui.SURFACE_HI, Ui.ACCENT, this)));
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
            keyboard.setSmart(Prefs.smartKeys(prefs), Prefs.wordSuggestions(prefs));
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
        int currentPreset = Prefs.layout(prefs, currentPosture);
        if (resizePosture != currentPosture || resizePreset != currentPreset) {
            if (resizeOverlay != null) finishResize(false);
            resizePosture = currentPosture;
            resizePreset = currentPreset;
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
        padCanLift = keyboardShown && trackpadShown && !coverDisplay;
        padInLid = padLid && padCanLift;
        // Up beside the desktop the pad is no longer the base's to lay out.
        if (padInLid) trackpadShown = false;
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
                    monitorFraction, keyboardShown,
                    solved.portraitConsole && Prefs.showShortcuts(prefs) && !barBeside) * density);
        }
        boolean showShortcuts = Prefs.showShortcuts(prefs);
        barCanDock = solved.portraitConsole && controlsShown && showShortcuts;
        boolean dockShortcuts = barCanDock && !barBeside;
        if (padInLid && monitorFraction < 0f) {
            // The pad's band is extra: grow the lid by it (down to the keys'
            // minimum) so the desktop keeps the size it had without the pad.
            int most = Math.round(DeckLayout.resizedLid(availablePx / density, 1f,
                    keyboardShown, dockShortcuts) * density);
            lidPx = Math.max(lidPx, Math.min(most, lidPx + lidPadBand(lidPx) + Ui.dp(this, 8)));
        }
        raisedPad = trackpadShown && keyboardShown && (solved.sideBySide || split)
                && showShortcuts && !dockShortcuts && !theatre;
        splitShown = split;
        sideBySideShown = solved.sideBySide && !split;
        // Pad height is the pad's own column beside the keys (or raised over the
        // monitor). Alone it fills the base, and in a split board it is the
        // channel between the halves: either way the keys set its height.
        boolean ownHeight = trackpadShown && keyboardShown && padHeight > 0 && (raisedPad || !split);
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
        if (padInLid) padHeightLimit = Math.max(100f, lidPx * 0.6f / density);
        if (ownHeight && !raisedPad) {
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
        if (dockShortcuts) shortcutBar.setCompact(false);   // the column's small labels stay beside the monitor
        // Docked without the chassis, the deck runs flush to the app frame at the
        // sides and sits right under the lid's separator: drawing those edges too
        // would double the line. See ShortcutBar.edges.
        boolean flush = dockShortcuts && !laptop;
        shortcutBar.setEdges(!flush, !(flush && !deckFlip && controlsShown), !flush, true);
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
            inputDeck.setPadHeight(ownHeight && !raisedPad ? padHeight : -1f);
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
        // Move base and hinge around the lid; never detach the lid. It holds the
        // SurfaceView, and detaching that destroys the video surface: the stream
        // pauses and waits for a new keyframe, a black monitor for ~250 ms.
        deck.removeView(base);
        deck.removeView(hinge);
        if (deckFlip) {
            deck.addView(base, 0);
            deck.addView(hinge, 1);
        } else {
            deck.addView(hinge);
            deck.addView(base);
        }
    }

    private void loadResize() {
        int p = resizePosture >= 0 ? resizePosture : posture();
        int l = resizePreset >= 0 ? resizePreset : Prefs.layout(prefs, p);
        monitorFraction = Prefs.resize(prefs, Prefs.RESIZE_MONITOR, p, l, -1f);
        padScale = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_PAD, p, l,
                Prefs.trackpadSize(prefs)), 0.7f, 1.5f);
        barScale = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_BAR, p, l, 1f),
                Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
        padHeight = Prefs.resize(prefs, Prefs.RESIZE_HEIGHT, p, l, -1f);
        wheelWidth = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_WHEEL, p, l, 28f), 18f, 64f);
        buttonHeight = Motion.clamp(Prefs.resize(prefs, Prefs.RESIZE_BUTTONS, p, l, 40f), 28f, 80f);
        deckFlip = Prefs.resizeFlag(prefs, Prefs.RESIZE_DECK_FLIP, p, false);
        padTop = Prefs.resizeFlag(prefs, Prefs.RESIZE_PAD_TOP, p, false);
        // Portrait keeps its own side: choosing one there must not move the bar
        // in landscape or on the cover, which share the Settings switch.
        barFlip = p == Prefs.POSTURE_PORTRAIT
                ? Prefs.resizeFlag(prefs, Prefs.RESIZE_BAR_FLIP, p, Prefs.shortcutsFlip(prefs))
                : Prefs.shortcutsFlip(prefs);
        barBeside = prefs.getBoolean(Prefs.sizeKey(Prefs.RESIZE_BAR_BESIDE, p, l), false);
        padLid = prefs.getBoolean(Prefs.sizeKey(Prefs.RESIZE_PAD_LID, p, l), false);
        boolean landscape = p != Prefs.POSTURE_PORTRAIT;
        padLeft = Prefs.trackpadLeft(prefs, landscape);
        splitOverrideActive = false;
    }

    /** Where {@code v} is laid out in the deck stage (ignoring any slide in flight); null if hidden. */
    private InputDeckLayout.Box laidOut(View v) {
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int[] stage = new int[2], at = new int[2];
        deckStage.getLocationInWindow(stage);
        v.getLocationInWindow(at);
        return new InputDeckLayout.Box(Math.round(at[0] - stage[0] - v.getTranslationX()),
                Math.round(at[1] - stage[1] - v.getTranslationY()), v.getWidth(), v.getHeight());
    }

    /**
     * The edges that can really move in the layout on screen, each with the one
     * value it changes: the monitor/controls divider, the bar's edge against
     * the desktop (or the keys, docked), the trackpad's edge against its
     * neighbour, and the trackpad's free edge where it has a height of its own.
     */
    private WorkspaceGeometry.Edge[] resizeEdges() {
        java.util.ArrayList<WorkspaceGeometry.Edge> out = new java.util.ArrayList<>();
        float nudge = Ui.dp(this, Chassis.HINGE_DP) / 2f;
        InputDeckLayout.Box lidBox = laidOut(lid), baseBox = laidOut(base), monitor = laidOut(surfaceView),
                bar = laidOut(shortcutBar), keys = laidOut(keyboard), pad = laidOut(trackpadDeck);
        boolean docked = shortcutBar.getParent() == inputDeck;
        if (controlsShown && lidBox != null && baseBox != null) {
            out.add(WorkspaceGeometry.between(ResizeOverlay.MONITOR, lidBox, baseBox, nudge));
        }
        if (bar != null) {
            InputDeckLayout.Box against = docked ? keys : barAroundPad ? null : monitor;
            if (against != null) out.add(WorkspaceGeometry.between(ResizeOverlay.BAR, bar, against, nudge));
        }
        if (pad != null && padInLid) {
            out.add(WorkspaceGeometry.side(ResizeOverlay.HEIGHT, pad, WorkspaceGeometry.TOP, 1f));
        } else if (pad != null && keys != null && keyboardShown) {
            if (splitShown) {
                // The channel widens on both sides, each by its half's share; the
                // value scales the channel (keys width x 0.19), not the pad view.
                float channel = InputDeckLayout.SPLIT_CHANNEL * keys.w * padScale;
                for (int side : new int[]{WorkspaceGeometry.LEFT, WorkspaceGeometry.RIGHT}) {
                    WorkspaceGeometry.Edge e = WorkspaceGeometry.side(ResizeOverlay.TRACKPAD, pad, side,
                            1f / Math.max(0.1f, keyboard.splitShare(side == WorkspaceGeometry.RIGHT)));
                    out.add(new WorkspaceGeometry.Edge(e.target, e.side, e.pos, e.from, e.to, channel, e.gain, 0f));
                }
            } else {
                out.add(WorkspaceGeometry.between(ResizeOverlay.TRACKPAD, pad,
                        docked && bar != null ? bar : keys, nudge));
            }
            // A height of its own: raised over the monitor, or its own column beside
            // the keys, anchored by the deck order. Docked, only when the pad grows
            // up from the keys (its other arrangements move the keys instead).
            boolean free = raisedPad || sideBySideShown && !docked
                    || docked && !splitShown && !deckFlip && !padTop;
            if (free) {
                out.add(WorkspaceGeometry.side(ResizeOverlay.HEIGHT, pad,
                        deckFlip && !docked ? WorkspaceGeometry.BOTTOM : WorkspaceGeometry.TOP, 1f));
            }
        }
        return out.toArray(new WorkspaceGeometry.Edge[0]);
    }

    private float resizeValue(int target) {
        if (target == ResizeOverlay.HEIGHT || target == ResizeOverlay.TRACKPAD && !padHorizontal()) return Motion.clamp(((padHeight < 0
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
            Motion.stop(v);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    /**
     * FLIP on springs: each pane starts where it was on screen (mid-flight
     * included, keeping its speed) and glides to its new place. Position only:
     * scaling a pane would distort the keys and the desktop inside it.
     */
    private void animateSwapTransition(int[][] beforeBounds) {
        View[] panes = {surfaceView, keyboard, trackpadDeck, shortcutBar};
        int[] loc = new int[2];
        for (int i = 0; i < panes.length; i++) {
            View v = panes[i];
            int[] prev = beforeBounds[i];
            if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0 || prev[2] <= 0 || prev[3] <= 0) {
                continue;
            }
            v.getLocationInWindow(loc);
            float dx = prev[0] + prev[2] * 0.5f - (loc[0] - v.getTranslationX() + v.getWidth() * 0.5f);
            float dy = prev[1] + prev[3] * 0.5f - (loc[1] - v.getTranslationY() + v.getHeight() * 0.5f);
            if (Math.abs(dx) < 1f && Math.abs(dy) < 1f && !Motion.spring(v, View.TRANSLATION_X, Motion.SNAPPY).running()) {
                continue;
            }
            v.setTranslationX(dx);
            v.setTranslationY(dy);
            Motion.to(v, View.TRANSLATION_X, 0f, Motion.SNAPPY);
            Motion.to(v, View.TRANSLATION_Y, 0f, Motion.SNAPPY);
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
                resizeStartAvailable = available;
                resizeSnapped = false;
            }
            @Override public WorkspaceGeometry.Edge[] edges() { return resizeEdges(); }
            @Override public void resizeEdge(WorkspaceGeometry.Edge edge, float travelPx) {
                if (resizeStartState == null) beginResize();
                WorkspaceGeometry.ResizeState start = resizeStartState;
                float density = getResources().getDisplayMetrics().density;
                // The pane grows by exactly what the finger travelled. Every size
                // is proportional to its value until a limit, so the value scales
                // with the pane; past a limit the layout stops and the overlay
                // stretches instead.
                float size = Math.max(1f, edge.size), grown = Math.max(1f, edge.size + edge.growth(travelPx));
                float mon = start.monitorFraction, pad = start.padScale, bar = start.barScale,
                        height = start.padHeight;
                if (edge.target == ResizeOverlay.MONITOR) {
                    float deckDp = resizeStartAvailable / density;
                    mon = DeckLayout.resizedLid(deckDp, grown / Math.max(1f, resizeStartAvailable),
                            keyboardShown, shortcutBar.getParent() == inputDeck) / Math.max(1f, deckDp);
                } else if (edge.target == ResizeOverlay.TRACKPAD && start.padHeight > 0
                        && !padHorizontal()) {
                    // A stacked pad with a height of its own: the divider sets that
                    // height (its scale would only resize the keys behind it).
                    height = Motion.clamp(grown / density, 100f, Math.max(100f, padHeightLimit));
                } else if (edge.target == ResizeOverlay.TRACKPAD) {
                    pad = Motion.clamp(start.padScale * grown / size,
                            Prefs.TRACKPAD_SIZE_MIN, Prefs.TRACKPAD_SIZE_MAX);
                } else if (edge.target == ResizeOverlay.BAR) {
                    // From the bar as it looks, over its unlimited size: a bar held
                    // at its minimum grows the moment the finger pulls.
                    float unit = shortcutBar.getParent() == videoPane && barUnitPx > 0f
                            ? barUnitPx : size / Math.max(0.01f, start.barScale);
                    bar = Motion.clamp(grown / unit, Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
                } else if (edge.target == ResizeOverlay.HEIGHT) {
                    height = Motion.clamp(grown / density, 100f, Math.max(100f, padHeightLimit));
                }
                WorkspaceGeometry.ResizeState raw = new WorkspaceGeometry.ResizeState(
                        mon, pad, bar, height, start.wheelWidth, start.buttonHeight);
                WorkspaceGeometry.ResizeState next = WorkspaceGeometry.snap(start, raw,
                        Prefs.trackpadSize(prefs));
                // A light tick as a size clicks onto its magnet, once per catch.
                boolean snapped = !WorkspaceGeometry.same(raw, next);
                if (snapped && !resizeSnapped && resizeOverlay != null) {
                    resizeOverlay.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
                resizeSnapped = snapped;
                // Only the value this edge owns: the rest keep what they were,
                // defaults included.
                if (edge.target == ResizeOverlay.MONITOR) monitorFraction = next.monitorFraction;
                if (edge.target == ResizeOverlay.TRACKPAD) {
                    padScale = next.padScale;
                    padHeight = next.padHeight;
                }
                if (edge.target == ResizeOverlay.BAR) barScale = next.barScale;
                if (edge.target == ResizeOverlay.HEIGHT) padHeight = next.padHeight;
                if (!resizeFramePending) {
                    resizeFramePending = true;
                    deck.postOnAnimation(resizeFrame);
                }
            }
            private int[][] paneBounds() {
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
                return before;
            }
            /** Lay out the new arrangement and slide the panes there from {@code before}. */
            private void rearrange(int[][] before) {
                applyPosture(false);
                // After the new layout, before it is drawn: no frame shows panes
                // jumped to their new places.
                android.view.ViewTreeObserver tree = deckStage.getViewTreeObserver();
                tree.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        deckStage.getViewTreeObserver().removeOnPreDrawListener(this);
                        animateSwapTransition(before);
                        if (resizeOverlay != null) resizeOverlay.refresh();
                        return true;
                    }
                });
            }
            @Override public boolean barSide(float x, InputDeckLayout.Box monitor) {
                if (!barCanDock || !barBeside || monitor == null) return false;
                boolean right = x > monitor.x + monitor.w * 0.5f;
                if (right == barFlip) return false;
                int[][] before = paneBounds();
                barFlip = right;
                rearrange(before);
                return true;
            }
            @Override public boolean swap(int from, int to, float dropX, InputDeckLayout.Box toBox) {
                int[][] before = paneBounds();
                boolean curSplit = (splitOverrideActive ? splitOverride
                        : Prefs.layoutSplit(Prefs.layout(prefs, posture())))
                        && keyboardShown && trackpadDeck.isShown() && !padInLid;
                boolean dockShortcuts = shortcutBar.getParent() == inputDeck;
                boolean sideBySide = padHorizontal() && !dockShortcuts && !curSplit;
                WorkspaceGeometry.Arrangement cur = new WorkspaceGeometry.Arrangement(
                        deckFlip, padLeft, barFlip, padTop, curSplit);
                WorkspaceGeometry.Arrangement next = WorkspaceGeometry.swap(
                        cur, from, to, sideBySide, dockShortcuts, dropX, toBox);
                int barMove = WorkspaceGeometry.barMove(from, to, dockShortcuts, barCanDock);
                int padMove = WorkspaceGeometry.padMove(from, to, padInLid, padCanLift);
                if (padMove == WorkspaceGeometry.PAD_BLOCK) return false;
                if (padMove != WorkspaceGeometry.PAD_STAY) {
                    padLid = padMove == WorkspaceGeometry.PAD_LIFT;
                    padHeight = -1f;   // a band's height up there, the pad's own down here
                    barDockGesture = resizeOverlay.gesture();
                    next = cur;
                } else if (barMove != WorkspaceGeometry.BAR_STAY) {
                    // The bar lands on the half of the monitor it was dropped on.
                    boolean right = toBox != null && dropX > toBox.x + toBox.w * 0.5f;
                    boolean beside = barMove != WorkspaceGeometry.BAR_REDOCK;
                    boolean side = beside ? right : barFlip;
                    if (beside == barBeside && side == barFlip) return false;
                    if (beside != barBeside) {
                        // The bar's size and the pad's height mean different things in
                        // the shelf and beside the desktop: start each from its default.
                        barScale = 1f;
                        padHeight = -1f;
                        barDockGesture = resizeOverlay.gesture();
                    }
                    barBeside = beside;
                    next = new WorkspaceGeometry.Arrangement(deckFlip, padLeft, side, padTop, curSplit);
                } else if (barDockGesture == resizeOverlay.gesture()) {
                    // One drag that docked or undocked the bar does only that: the
                    // shelf appearing under the finger must not trigger more swaps.
                    return false;
                } else
                if (next.deckFlip == deckFlip && next.padLeft == padLeft && next.barFlip == barFlip
                        && next.padTop == padTop && next.split == curSplit) return false;
                deckFlip = next.deckFlip;
                padLeft = next.padLeft;
                barFlip = next.barFlip;
                padTop = next.padTop;
                if (curSplit && !next.split) {
                    splitOverrideActive = true;
                    splitOverride = false;
                }
                rearrange(before);
                return true;
            }
            @Override public void change(int target, float fraction) {
                if (target == ResizeOverlay.KEYBOARD && padHeight < 0 && trackpadDeck.isShown() && !padInLid) {
                    padHeight = trackpadDeck.getHeight() / getResources().getDisplayMetrics().density;
                }
                if (target == ResizeOverlay.HEIGHT || target == ResizeOverlay.TRACKPAD && !padHorizontal())
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
                        && padHorizontal();
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
                barBeside = false;
                padLid = false;
                padLeft = posture() == Prefs.POSTURE_PORTRAIT;
                splitOverrideActive = false;
                applyPosture(false);
                resizeOverlay.post(resizeOverlay::refresh);
            }
            @Override public void finish(boolean save) { finishResize(save); }
        });
        deckStage.addView(resizeOverlay, new FrameLayout.LayoutParams(-1, -1));
        Ui.elevate(resizeOverlay, this, 16);
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
            // Swapping out of Split turns the board into Both: the sizes go with it.
            int preset = splitOverrideActive && !splitOverride ? Prefs.LAYOUT_BOTH : resizePreset;
            int p = resizePosture;
            SharedPreferences.Editor ed = prefs.edit()
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_MONITOR, p, preset), monitorFraction)
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_PAD, p, preset), padScale)
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_BAR, p, preset), barScale)
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_HEIGHT, p, preset), padHeight)
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_WHEEL, p, preset), wheelWidth)
                    .putFloat(Prefs.sizeKey(Prefs.RESIZE_BUTTONS, p, preset), buttonHeight)
                    .putBoolean(Prefs.RESIZE_DECK_FLIP + resizePosture, deckFlip)
                    .putBoolean(Prefs.RESIZE_PAD_LEFT + resizePosture, padLeft)
                    .putBoolean(Prefs.RESIZE_BAR_FLIP + resizePosture, barFlip)
                    .putBoolean(Prefs.sizeKey(Prefs.RESIZE_BAR_BESIDE, p, preset), barBeside)
                    .putBoolean(Prefs.sizeKey(Prefs.RESIZE_PAD_LID, p, preset), padLid)
                    .putBoolean(Prefs.RESIZE_PAD_TOP + resizePosture, padTop)
                    .putBoolean(Prefs.SHORTCUTS_FLIP, p == Prefs.POSTURE_PORTRAIT
                            ? Prefs.shortcutsFlip(prefs) : barFlip)
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
        resize.setDuration(Motion.millis(Motion.NAV));
        resize.setInterpolator(Motion.curve(Motion.NAV));
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
                    .setDuration(Motion.millis(Motion.SNAPPY))
                    .setInterpolator(Motion.curve(Motion.SNAPPY)).start();
        } else {
            base.animate().alpha(0f).translationY(Ui.dp(this, 44)).scaleX(0.985f).scaleY(0.985f)
                    .setDuration(Motion.millis(Motion.FADE))
                    .setInterpolator(Motion.curve(Motion.FADE)).start();
        }
        resize.start();
    }

    private void releaseDeckInput() {
        if (keyboard != null) keyboard.releaseAll();
        if (trackpadDeck != null) trackpadDeck.cancelInput();
        predictor.reset();
        showPrediction();
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
        move.setDuration(Motion.millis(Motion.NAV));
        move.setInterpolator(Motion.curve(Motion.NAV));
        android.transition.TransitionManager.beginDelayedTransition(root, move);
        int frame = on ? 0 : 2 * Ui.hair(this);
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
        Motion.to(theatreToggle, View.ALPHA, theatre ? 0.25f : 1f, Motion.FADE);
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
        Motion.to(coverToggle, View.ROTATION, expanded ? -4f : 0f, Motion.RELEASE);
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
                && outgoing.getVisibility() == View.VISIBLE && root.getWidth() > 0;
        for (View v : new View[]{home, settingsView, addressView, shortcutsView}) {
            // A repeat call for the same screen leaves a screen still leaving alone.
            if (v == null || v == target || animate && v == outgoing
                    || previous == which && Motion.moving(v)) continue;
            settleScreen(v);
            v.setVisibility(View.GONE);
        }
        target.setVisibility(View.VISIBLE);
        if (animate) {
            navigate(target, outgoing);
        } else if (previous != which) {
            settleScreen(target);
            if (!locked) {
                if (target instanceof HomeView) ((HomeView) target).enterPage();
                else if (target instanceof Panel) ((Panel) target).enterPage(
                        which == SCREEN_SETTINGS && previous == SCREEN_SHORTCUTS);
            }
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
        settleScreen(v);
    }

    private static void settleScreen(View v) {
        if (v == null) return;
        v.animate().cancel();
        Motion.stop(v);
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        Motion.Dim.PROPERTY.set(v, 0f);
    }

    /**
     * Screens move like iOS navigation. Going deeper, the new screen slides in
     * over the old, which drifts back a third and dims; going back reverses it.
     * Home over the desktop zooms instead, like leaving and entering an app.
     * The deck itself never moves. Whatever is on top stays opaque all the way,
     * so the live desktop shows only when it is where you are going or leaving.
     * Every part is a spring: tap Back mid-slide and it turns around smoothly.
     */
    private void navigate(View target, View outgoing) {
        boolean push = root.indexOfChild(target) > root.indexOfChild(outgoing);
        View top = push ? target : outgoing, under = push ? outgoing : target;
        if (top == home && under == deckStage) {
            float far = 1.06f;
            // Origin-aware: Home zooms away from (and back onto) its Connect row.
            View from = home.mainRow();
            if (from.isLaidOut() && home.getWidth() > 0) {
                int[] a = new int[2], b = new int[2];
                home.getLocationInWindow(a);
                from.getLocationInWindow(b);
                home.setPivotX(b[0] - a[0] + from.getWidth() / 2f);
                home.setPivotY(b[1] - a[1] + from.getHeight() / 2f);
            }
            if (push && !Motion.spring(home, View.ALPHA, Motion.FADE).running()) {
                home.setAlpha(0f);
                home.setScaleX(far);
                home.setScaleY(far);
            }
            float scale = push ? 1f : far;
            Motion.to(home, View.SCALE_X, scale, Motion.NAV);
            Motion.to(home, View.SCALE_Y, scale, Motion.NAV);
            Motion.to(home, View.ALPHA, push ? 1f : 0f, Motion.FADE, push ? null : () -> conceal(home));
            return;
        }
        float width = root.getWidth(), drift = -width * 0.3f;
        boolean moving = Motion.spring(top, View.TRANSLATION_X, Motion.NAV).running();
        if (push && !moving) top.setTranslationX(width);
        top.setAlpha(1f);
        Motion.to(top, View.TRANSLATION_X, push ? 0f : width, Motion.NAV,
                () -> conceal(push ? under : top));
        if (under == deckStage) return;
        if (!push && !Motion.spring(under, View.TRANSLATION_X, Motion.NAV).running()) {
            under.setTranslationX(drift);
            Motion.Dim.PROPERTY.set(under, 0.3f);
        }
        under.setAlpha(1f);
        Motion.to(under, View.TRANSLATION_X, push ? drift : 0f, Motion.NAV);
        Motion.to(under, Motion.Dim.PROPERTY, push ? 0.3f : 0f, Motion.NAV);
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

    /**
     * The biometric prompt takes window focus without telling us: neither losing it
     * nor getting it back reaches onWindowFocusChanged, and unlock runs while the
     * prompt still holds it. So clipboard sync would stay off after a fingerprint
     * unlock. Re-check until the window really has focus (100 ms steps, 2 s cap).
     */
    private void focusSharingAfterUnlock(int tries) {
        if (locked || sharing == null) return;
        if (hasWindowFocus()) sharing.focus(true);
        else if (tries > 0) getWindow().getDecorView().postDelayed(() -> focusSharingAfterUnlock(tries - 1), 100);
    }

    private void onUnlocked() {
        locked = false;
        focusSharingAfterUnlock(20);
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

        // Hidden HUD (the default): skip formatting it four times a second.
        final String text = hud.getVisibility() != View.VISIBLE ? null : String.format(Locale.US,
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
            if (text != null) hud.setText(text);
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
                    // Wi-Fi just took over: look for home now, not on the next
                    // 15 s tick (which skipped while mobile data was active).
                    if (!mobile) runOnUiThread(() -> {
                        if (root == null || route != Endpoints.AWAY) return;
                        root.removeCallbacks(homeCheck);
                        homeCheck.run();
                    });
                }
            };
}
