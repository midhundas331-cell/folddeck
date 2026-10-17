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
    static final float CONTROL_SCALE_MIN = 0.85f, CONTROL_SCALE_MAX = 1.15f;

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
