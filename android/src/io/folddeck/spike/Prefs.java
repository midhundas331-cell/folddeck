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

    static void putBool(SharedPreferences p, String key, boolean value) {
        p.edit().putBoolean(key, value).apply();
    }
}
