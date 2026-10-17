package io.folddeck.spike;

import android.content.SharedPreferences;

/**
 * Preference keys and their defaults, in one place.
 *
 * They were previously string literals spread across whichever file happened to
 * need them, which is how "host" ends up written by one screen and read as
 * "hostname" by another.
 */
final class Prefs {

    private Prefs() { }

    static final String NAME = "folddeck";

    /**
     * The last address that was actually dialled. Kept because {@link Endpoints}
     * migrates it into a home/away slot on first run, and because the deck still
     * wants to show what it is connected to.
     */
    static final String HOST = "host";
    static final String PORT = "port";

    /**
     * The laptop has two addresses, not one: a LAN address that is fast but
     * only exists at home, and a tailnet address that works anywhere. Both are
     * stored and the app measures which is live rather than asking.
     */
    static final String HOST_HOME = "host_home";
    static final String PORT_HOME = "port_home";
    static final String HOST_AWAY = "host_away";
    static final String PORT_AWAY = "port_away";
    /** Whatever was last typed into the address box, verbatim, to prefill it. */
    static final String LAST = "last";

    static final String AUTO_CONNECT = "auto_connect";
    static final String LAPTOP_LAYOUT = "laptop_layout";
    static final String SHOW_HUD = "show_hud";
    static final String HIDE_KEYBOARD = "hide_keyboard";
    static final String TRACKPAD_DECK = "trackpad_deck";
    static final String TRACKPAD_SCROLL = "trackpad_scroll";
    static final String TRACKPAD_LEFT_HANDED = "trackpad_left_handed";
    static final String NATURAL_SCROLL = "natural_scroll";
    static final String KEYBOARD_HAPTICS = "keyboard_haptics";
    static final String MOUSE_AUTO_CAPTURE = "mouse_auto_capture";
    static final String POINTER_MODE = "pointer_mode";
    static final String TRACKPAD_SENS = "trackpad_sens";
    static final String TRACKPOINT_SENS = "trackpoint_sens";
    static final String CONTROL_SCALE = "control_scale";
    static final String LAPTOP_AUDIO = "laptop_audio";
    static final String TRACKPAD_TAP = "trackpad_tap";

    /** What the deck shows below the desktop, per posture. */
    static final int LAYOUT_BOTH = 0, LAYOUT_KEYBOARD = 1, LAYOUT_TRACKPAD = 2,
            LAYOUT_MONITOR = 3;
    static final int POSTURE_COVER = 0, POSTURE_LANDSCAPE = 1, POSTURE_PORTRAIT = 2;
    static final String[] LAYOUT_KEYS = {"layout_cover", "layout_landscape", "layout_portrait"};
    /** Where the desktop sits in spare space: 0 start, 1 centre, 2 end. */
    static final String DESKTOP_ALIGN = "desktop_align";
    static final String SHOW_SHORTCUTS = "show_shortcuts";
    /** Shortcut bar right of the desktop instead of left, above instead of below. */
    static final String SHORTCUTS_FLIP = "shortcuts_flip";
    static final String TRACKPAD_SIZE = "trackpad_size";
    static final float TRACKPAD_SIZE_MIN = 0.7f, TRACKPAD_SIZE_MAX = 1.5f;
    static final String KEY_LABEL_SCALE = "key_label_scale";
    static final float KEY_LABEL_MIN = 0.75f, KEY_LABEL_MAX = 1.35f;
    static final String KEY_GAP_SCALE = "key_gap_scale";
    static final float KEY_GAP_MIN = 0.5f, KEY_GAP_MAX = 3f;
    static final String SHOW_TRACKPOINT = "show_trackpoint";
    static final String SHOW_FN_ROW = "show_fn_row";
    /** Which side of the keyboard the dedicated trackpad sits on, per orientation. */
    static final String TRACKPAD_LEFT_LANDSCAPE = "trackpad_left_landscape";
    static final String TRACKPAD_LEFT_PORTRAIT = "trackpad_left_portrait";
    /** Where the cover screen's monitor/keyboard button was dragged to, 0..1 of its travel. */
    static final String COVER_TOGGLE_X = "cover_toggle_x";
    static final String COVER_TOGGLE_Y = "cover_toggle_y";

    /** The laptop's Omarchy palette as last sent down the stream, "key=#rrggbb" lines. */
    static final String THEME = "theme";

    /** The deck's shortcut bar, "label\tchord" lines. See {@link Shortcuts}. */
    static final String SHORTCUTS = "shortcuts";

    /**
     * Pointer speed for a captured Bluetooth mouse, as a multiplier on the raw
     * delta. 1.0 tracks the video one-to-one: moving the mouse the width of the
     * desktop image moves the cursor the width of the desktop.
     *
     * This is the mouse once FoldDeck has it. On the app's own screens Android
     * owns the pointer and its speed, which no app can change -- that one lives
     * in Android Settings under Accessibility.
     */
    static final String MOUSE_SENS = "mouse_sens";
    static final float MOUSE_SENS_MIN = 0.25f, MOUSE_SENS_MAX = 3f;
    static final float TRACKPAD_SENS_MIN = 0.5f, TRACKPAD_SENS_MAX = 2f;
    static final float TRACKPOINT_SENS_MIN = 0.5f, TRACKPOINT_SENS_MAX = 3f;
    /** The control band's height, shown in Settings as "Keyboard height". */
    static final float CONTROL_SCALE_MIN = 0.7f, CONTROL_SCALE_MAX = 1.4f;

    /**
     * Defaults are deliberate:
     *
     * AUTO_CONNECT off — the connect button is the thing that makes the app
     * legible, and skipping straight past it is what made a wrong address so
     * hard to diagnose in the first place.
     *
     * LAPTOP_LAYOUT on — it is the whole point of the unfolded posture, and it
     * only ever applies there.
     *
     * SHOW_HUD off — the stats overlay is a development instrument. Home now
     * reports connection state in words, so the HUD is no longer load-bearing.
     */
    static boolean autoConnect(SharedPreferences p) {
        return p.getBoolean(AUTO_CONNECT, false);
    }

    static boolean laptopLayout(SharedPreferences p) {
        return p.getBoolean(LAPTOP_LAYOUT, true);
    }

    static boolean showHud(SharedPreferences p) {
        return p.getBoolean(SHOW_HUD, false);
    }

    /** On: a real keyboard plugged in or paired hides the on-screen one. The
     *  off switch is for a mouse that Android mistakes for a keyboard, which
     *  would otherwise take the only keyboard away. */
    static boolean hideKeyboard(SharedPreferences p) {
        return p.getBoolean(HIDE_KEYBOARD, true);
    }

    static float mouseSens(SharedPreferences p) {
        return p.getFloat(MOUSE_SENS, 1f);
    }

    static boolean trackpadDeck(SharedPreferences p) {
        return p.getBoolean(TRACKPAD_DECK, false);
    }

    static boolean trackpadScroll(SharedPreferences p) {
        return p.getBoolean(TRACKPAD_SCROLL, true);
    }

    static boolean trackpadLeftHanded(SharedPreferences p) {
        return p.getBoolean(TRACKPAD_LEFT_HANDED, false);
    }

    /** Defaults keep the original layout: right of the keyboard in landscape,
     *  left of the shortcut shelf in portrait. */
    static boolean trackpadLeft(SharedPreferences p, boolean landscape) {
        return landscape ? p.getBoolean(TRACKPAD_LEFT_LANDSCAPE, false)
                : p.getBoolean(TRACKPAD_LEFT_PORTRAIT, true);
    }

    /** On: while connected the laptop's audio plays here and its speakers go quiet. */
    static boolean laptopAudio(SharedPreferences p) {
        return p.getBoolean(LAPTOP_AUDIO, true);
    }

    /** Defaults keep the old behaviour: the landscape deck follows the former
     *  "Dedicated trackpad" switch, and portrait always had both. */
    static int layout(SharedPreferences p, int posture) {
        int fallback = posture == POSTURE_LANDSCAPE && !trackpadDeck(p)
                ? LAYOUT_KEYBOARD : LAYOUT_BOTH;
        int v = p.getInt(LAYOUT_KEYS[posture], fallback);
        return v >= LAYOUT_BOTH && v <= LAYOUT_MONITOR ? v : fallback;
    }

    static boolean layoutHasKeyboard(int layout) {
        return layout == LAYOUT_BOTH || layout == LAYOUT_KEYBOARD;
    }

    static boolean layoutHasTrackpad(int layout) {
        return layout == LAYOUT_BOTH || layout == LAYOUT_TRACKPAD;
    }

    static int desktopAlign(SharedPreferences p) {
        return Math.max(0, Math.min(2, p.getInt(DESKTOP_ALIGN, 1)));
    }

    static boolean showShortcuts(SharedPreferences p) {
        return p.getBoolean(SHOW_SHORTCUTS, true);
    }

    static boolean shortcutsFlip(SharedPreferences p) {
        return p.getBoolean(SHORTCUTS_FLIP, false);
    }

    static float trackpadSize(SharedPreferences p) {
        return clamp(p.getFloat(TRACKPAD_SIZE, 1f), TRACKPAD_SIZE_MIN, TRACKPAD_SIZE_MAX);
    }

    static float keyLabelScale(SharedPreferences p) {
        return clamp(p.getFloat(KEY_LABEL_SCALE, 1f), KEY_LABEL_MIN, KEY_LABEL_MAX);
    }

    static float keyGapScale(SharedPreferences p) {
        return clamp(p.getFloat(KEY_GAP_SCALE, 1f), KEY_GAP_MIN, KEY_GAP_MAX);
    }

    static boolean showTrackpoint(SharedPreferences p) {
        return p.getBoolean(SHOW_TRACKPOINT, true);
    }

    static boolean showFnRow(SharedPreferences p) {
        return p.getBoolean(SHOW_FN_ROW, true);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static boolean trackpadTap(SharedPreferences p) {
        return p.getBoolean(TRACKPAD_TAP, true);
    }

    static boolean naturalScroll(SharedPreferences p) {
        return p.getBoolean(NATURAL_SCROLL, true);
    }

    static boolean keyboardHaptics(SharedPreferences p) {
        return p.getBoolean(KEYBOARD_HAPTICS, true);
    }

    static boolean mouseAutoCapture(SharedPreferences p) {
        return p.getBoolean(MOUSE_AUTO_CAPTURE, true);
    }

    static int pointerMode(SharedPreferences p) {
        return clampPointerMode(p.getInt(POINTER_MODE, PointerPad.MODE_DIRECT));
    }

    static float trackpadSens(SharedPreferences p) {
        return clampTrackpadSens(p.getFloat(TRACKPAD_SENS, 1f));
    }

    static float trackpointSens(SharedPreferences p) {
        return clampTrackpointSens(p.getFloat(TRACKPOINT_SENS, 1f));
    }

    static float controlScale(SharedPreferences p) {
        return clampControlScale(p.getFloat(CONTROL_SCALE, 1f));
    }

    static float clampTrackpadSens(float value) {
        return Math.max(TRACKPAD_SENS_MIN, Math.min(TRACKPAD_SENS_MAX, value));
    }

    static float clampTrackpointSens(float value) {
        return Math.max(TRACKPOINT_SENS_MIN, Math.min(TRACKPOINT_SENS_MAX, value));
    }

    static float clampControlScale(float value) {
        return Math.max(CONTROL_SCALE_MIN, Math.min(CONTROL_SCALE_MAX, value));
    }

    static int clampPointerMode(int value) {
        return value == PointerPad.MODE_TRACKPAD ? value : PointerPad.MODE_DIRECT;
    }

    static void putBool(SharedPreferences p, String key, boolean value) {
        p.edit().putBoolean(key, value).apply();
    }
}
