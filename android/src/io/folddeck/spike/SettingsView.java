package io.folddeck.spike;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings, laid out the way iOS Settings is: a card for the laptop, then a
 * grouped list of categories with coloured icon tiles, each opening a page of
 * rounded groups. Rows carry short names only; what a setting does is said
 * once, in the small note under its group.
 *
 * Two rows are recovery paths that once did not exist: a reinstalled host
 * changed its certificate and the client stopped for good, with clearing app
 * data (and so losing the pairing) the only way out. Both live under
 * Lock & Security now.
 */
class SettingsView extends Panel {

    interface Listener {
        void onEditAddress();
        void onMouseSensitivity(float sens);
        void onChangePin();
        void onForgetHostCertificate();
        void onControlsChanged();
        void onAudioChanged(boolean on);
        void onHudChanged();
        void onEditShortcuts();
    }

    private final Context c;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final LinearLayout menu;
    private final TextView addressValue, cardAddress;
    private LinearLayout openPage;

    SettingsView(Context c, SharedPreferences prefs, Lock lock,
                 Listener listener, Runnable onBack) {
        super(c, "Settings", onBack);
        this.c = c;
        this.prefs = prefs;
        this.listener = listener;

        // Omarchy's menu card: a square frame with a 2px border round the list.
        LinearLayout frame = content();
        Ui.menuCard(frame);

        menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        frame.addView(menu);
        menu.addView(Ui.menuHeading(c, "Settings…"));

        // -------- the laptop -------------------------------------------------- //
        cardAddress = Ui.text(c, "", Ui.T_LABEL, Ui.TEXT_FAINT);
        LinearLayout laptop = Ui.menuItem(c, "\udb80\udf22", "FoldDeck", listener::onEditAddress);
        ((LinearLayout) laptop.getChildAt(1)).addView(cardAddress);
        TextView build = Ui.text(c, version(c), Ui.T_LABEL, Ui.TEXT_FAINT);
        ((LinearLayout) laptop.getChildAt(1)).addView(build);
        cardAddress.setTypeface(Ui.MONO);
        build.setTypeface(Ui.MONO);
        menu.addView(laptop);
        menu.addView(Ui.divider(c));

        // -------- categories ------------------------------------------------- //
        LinearLayout connection = page("Connection");
        LinearLayout screen = page("Display");
        LinearLayout keyboard = page("Keyboard");
        LinearLayout trackpad = page("Trackpad");
        LinearLayout shortcuts = page("Shortcuts");
        LinearLayout mouse = page("Mouse");
        LinearLayout security = page("Lock & Security");
        menu.addView(category("\udb81\udef3", "Connection", connection));
        menu.addView(category("\udb80\udf79", "Display", screen));
        menu.addView(category("\uf11c", "Keyboard", keyboard));
        menu.addView(category("\udb81\udf41", "Trackpad", trackpad));
        menu.addView(category("\udb80\udf7d", "Mouse", mouse));
        menu.addView(category("\udb80\udc3b", "Shortcuts", shortcuts));
        menu.addView(category("\ueb11", "Lock & Security", security));

        // -------- Connection ------------------------------------------------- //
        addressValue = value("");
        connection.addView(group(null,
                "When Connect Automatically is on, unlocking the app takes you "
                        + "straight to your laptop.",
                Ui.row(c, "Laptop", null, withChevron(addressValue), listener::onEditAddress),
                toggle("Connect Automatically", Prefs.autoConnect(prefs),
                        on -> Prefs.putBool(prefs, Prefs.AUTO_CONNECT, on))));
        connection.addView(group(null,
                "Plays your laptop's sound on this phone. The laptop's speakers "
                        + "stay quiet while you're connected.",
                toggle("Play Sound on Phone", Prefs.laptopAudio(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.LAPTOP_AUDIO, on);
                    listener.onAudioChanged(on);
                })));

        // -------- Display ---------------------------------------------------- //
        String[] presets = {"Both", "Keys", "Pad", "None", "Split"};
        String[] postures = {"Folded", "Open, Sideways", "Open, Upright"};
        View[] postureRows = new View[postures.length];
        for (int i = 0; i < postures.length; i++) {
            final int posture = i;
            postureRows[i] = choice(postures[i], presets, Prefs.layout(prefs, posture),
                    v -> prefs.edit().putInt(Prefs.LAYOUT_KEYS[posture], v).apply());
        }
        screen.addView(group("Controls Below the Screen",
                "Choose the keyboard, the trackpad, both, or none. Split puts the "
                        + "trackpad between two halves of the keyboard. When folded, "
                        + "None hides the corner button.",
                postureRows));
        screen.addView(group("Laptop Screen",
                "Laptop Frame draws the screen full width across the top, like a "
                        + "laptop lid.",
                choice("Position", new String[]{"Start", "Middle", "End"},
                        Prefs.desktopAlign(prefs),
                        v -> prefs.edit().putInt(Prefs.DESKTOP_ALIGN, v).apply()),
                control("Laptop Frame", Prefs.laptopLayout(prefs), Prefs.LAPTOP_LAYOUT)));
        screen.addView(group(null,
                "Shows connection speed and delay over the screen.",
                toggle("Connection Info", Prefs.showHud(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.SHOW_HUD, on);
                    listener.onHudChanged();
                })));

        // -------- Keyboard --------------------------------------------------- //
        keyboard.addView(group("Size",
                "A larger keyboard leaves less room for the laptop screen.",
                slider("Keyboard Size", Prefs.controlScale(prefs),
                        Prefs.CONTROL_SCALE_MIN, Prefs.CONTROL_SCALE_MAX, false,
                        v -> prefs.edit().putFloat(Prefs.CONTROL_SCALE, v).apply()),
                slider("Text Size", Prefs.keyLabelScale(prefs),
                        Prefs.KEY_LABEL_MIN, Prefs.KEY_LABEL_MAX, false,
                        v -> prefs.edit().putFloat(Prefs.KEY_LABEL_SCALE, v).apply()),
                slider("Key Spacing", Prefs.keyGapScale(prefs),
                        Prefs.KEY_GAP_MIN, Prefs.KEY_GAP_MAX, false,
                        v -> prefs.edit().putFloat(Prefs.KEY_GAP_SCALE, v).apply())));
        keyboard.addView(group("Keys",
                "The pointing stick is the small red dot between G, H and B. "
                        + "Push it to move the cursor.",
                control("Function Keys", Prefs.showFnRow(prefs), Prefs.SHOW_FN_ROW),
                control("Pointing Stick", Prefs.showTrackpoint(prefs), Prefs.SHOW_TRACKPOINT),
                slider("Pointing Stick Speed", Prefs.trackpointSens(prefs),
                        Prefs.TRACKPOINT_SENS_MIN, Prefs.TRACKPOINT_SENS_MAX, true,
                        v -> prefs.edit().putFloat(Prefs.TRACKPOINT_SENS, v).apply())));
        keyboard.addView(group(null,
                "Hides this keyboard while a Bluetooth or USB keyboard is connected.",
                control("Haptic Feedback", Prefs.keyboardHaptics(prefs), Prefs.KEYBOARD_HAPTICS),
                control("Hide with Hardware Keyboard", Prefs.hideKeyboard(prefs),
                        Prefs.HIDE_KEYBOARD)));

        // -------- Trackpad --------------------------------------------------- //
        trackpad.addView(group(null, null,
                slider("Tracking Speed", Prefs.trackpadSens(prefs),
                        Prefs.TRACKPAD_SENS_MIN, Prefs.TRACKPAD_SENS_MAX, true,
                        v -> prefs.edit().putFloat(Prefs.TRACKPAD_SENS, v).apply()),
                slider("Size", Prefs.trackpadSize(prefs),
                        Prefs.TRACKPAD_SIZE_MIN, Prefs.TRACKPAD_SIZE_MAX, false,
                        v -> prefs.edit().putFloat(Prefs.TRACKPAD_SIZE, v).apply())));
        trackpad.addView(group("Point & Click",
                "Tap with two fingers to right-click.",
                control("Tap to Click", Prefs.trackpadTap(prefs), Prefs.TRACKPAD_TAP),
                control("Left-Handed", Prefs.trackpadLeftHanded(prefs),
                        Prefs.TRACKPAD_LEFT_HANDED)));
        trackpad.addView(group("Scroll",
                "Natural scrolling moves content the same way as your finger.",
                control("Natural Scrolling", Prefs.naturalScroll(prefs), Prefs.NATURAL_SCROLL),
                control("Scroll Strip", Prefs.trackpadScroll(prefs), Prefs.TRACKPAD_SCROLL)));
        trackpad.addView(group("Side",
                null,
                control("Left Side When Sideways", Prefs.trackpadLeft(prefs, true),
                        Prefs.TRACKPAD_LEFT_LANDSCAPE),
                control("Left Side When Upright", Prefs.trackpadLeft(prefs, false),
                        Prefs.TRACKPAD_LEFT_PORTRAIT)));
        trackpad.addView(group(null,
                "Drag anywhere on the laptop screen to move the cursor, instead "
                        + "of tapping exactly where you want to click.",
                toggle("Screen Works as Trackpad",
                        Prefs.pointerMode(prefs) == PointerPad.MODE_TRACKPAD, on -> {
                            prefs.edit().putInt(Prefs.POINTER_MODE,
                                    on ? PointerPad.MODE_TRACKPAD : PointerPad.MODE_DIRECT).apply();
                            listener.onControlsChanged();
                        })));

        // -------- Mouse ------------------------------------------------------ //
        mouse.addView(group(null,
                "Press both mouse buttons together to switch the mouse between "
                        + "your laptop and this phone.",
                slider("Tracking Speed", Prefs.mouseSens(prefs),
                        Prefs.MOUSE_SENS_MIN, Prefs.MOUSE_SENS_MAX, true, v -> {
                            prefs.edit().putFloat(Prefs.MOUSE_SENS, v).apply();
                            listener.onMouseSensitivity(v);
                        }),
                control("Use with Laptop", Prefs.mouseAutoCapture(prefs),
                        Prefs.MOUSE_AUTO_CAPTURE)));

        // -------- Shortcuts -------------------------------------------------- //
        shortcuts.addView(group(null,
                "Buttons beside the laptop screen that open apps and run "
                        + "Omarchy actions with one tap.",
                Ui.row(c, "Edit Shortcuts", null, chevron(), listener::onEditShortcuts),
                control("Show Shortcuts", Prefs.showShortcuts(prefs), Prefs.SHOW_SHORTCUTS),
                control("Swap Side", Prefs.shortcutsFlip(prefs), Prefs.SHORTCUTS_FLIP)));

        // -------- Lock & Security -------------------------------------------- //
        security.addView(group(null,
                "Your PIN always works too.",
                toggle("Unlock with Fingerprint", lock.biometricEnabled(),
                        lock::setBiometricEnabled),
                Ui.row(c, "Change PIN", null, chevron(), listener::onChangePin)));
        LinearLayout reset = Ui.row(c, "Reset Laptop Trust", null, null,
                listener::onForgetHostCertificate);
        ((TextView) ((LinearLayout) reset.getChildAt(0)).getChildAt(0)).setTextColor(Ui.ERR);
        security.addView(group(null,
                "Only needed after reinstalling FoldDeck on your laptop. Do it on "
                        + "your home Wi-Fi. Everything between your phone and laptop "
                        + "is encrypted.",
                reset));

        // A page opens straight onto its first section, with no divider above it.
        for (int i = 1; i < frame.getChildCount(); i++) {
            ((LinearLayout) ((LinearLayout) frame.getChildAt(i)).getChildAt(1)).removeViewAt(0);
        }
    }

    // ------------------------------------------------------------------ //
    // Paging
    // ------------------------------------------------------------------ //
    private LinearLayout page(String title) {
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(GONE);
        page.setTag(title);
        page.addView(Ui.menuHeading(c, title + "…"));
        content().addView(page);
        return page;
    }

    private View category(String icon, String title, LinearLayout page) {
        return Ui.menuItem(c, icon, title, () -> open(page));
    }

    private void open(LinearLayout page) {
        menu.setVisibility(GONE);
        page.setVisibility(VISIBLE);
        openPage = page;
        showPage((String) page.getTag());
        Motion.enter(page);
    }

    @Override
    boolean up() {
        if (openPage == null) return false;
        openPage.setVisibility(GONE);
        openPage = null;
        menu.setVisibility(VISIBLE);
        showPage("Settings");
        return true;
    }

    // ------------------------------------------------------------------ //
    // Building blocks
    // ------------------------------------------------------------------ //

    /**
     * A section of rows: a bold dim header, the rows flat on the card, and a
     * note under them. Sections after the first open with Omarchy's divider.
     */
    private LinearLayout group(String header, String footer, View... rows) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(Ui.divider(c));
        if (header != null) wrap.addView(Ui.menuSection(c, header));
        for (View row : rows) {
            wrap.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (footer != null) wrap.addView(Ui.menuNote(c, footer));
        return wrap;
    }

    /** A switch whose whole row is the touch target. */
    private View toggle(String title, boolean on, java.util.function.Consumer<Boolean> onChange) {
        View t = Ui.toggle(c, on, onChange);
        return Ui.row(c, title, null, t, t::performClick);
    }

    /** The common case: a boolean pref that reshapes the deck. */
    private View control(String title, boolean on, String key) {
        return toggle(title, on, v -> {
            Prefs.putBool(prefs, key, v);
            listener.onControlsChanged();
        });
    }

    private View choice(String title, String[] labels, int selected,
                        java.util.function.IntConsumer onChange) {
        View picker = Ui.choice(c, labels, selected, v -> {
            onChange.accept(v);
            listener.onControlsChanged();
        });
        picker.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout segments = (LinearLayout) picker;
        for (int i = 0; i < segments.getChildCount(); i++) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) segments.getChildAt(i).getLayoutParams();
            lp.width = 0;
            lp.weight = 1f;
            segments.getChildAt(i).setPadding(0, 0, 0, 0);
        }
        return stacked(Ui.row(c, title, null, null, null), picker);
    }

    /** Name and value on one line, a full-width slider under them. */
    private View slider(String title, float value, float min, float max,
                        boolean speed, java.util.function.Consumer<Float> onChange) {
        TextView label = value(format(value, speed));
        View bar = Ui.slider(c, value, min, max, v -> {
            label.setText(format(v, speed));
            onChange.accept(v);
            listener.onControlsChanged();
        });
        bar.setContentDescription(title);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 28)));
        return stacked(Ui.row(c, title, null, label, null), bar);
    }

    /** A row with its control on a line of its own beneath. */
    private View stacked(LinearLayout row, View below) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(row.getPaddingLeft(), row.getPaddingTop(),
                row.getPaddingRight(), Ui.dp(c, 14));
        row.setPadding(0, 0, 0, 0);
        row.setMinimumHeight(Ui.dp(c, 32));
        box.addView(row);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) below.getLayoutParams();
        lp.topMargin = Ui.dp(c, 8);
        box.addView(below, lp);
        return box;
    }

    private TextView value(String text) {
        TextView t = Ui.text(c, text, Ui.T_BODY, Ui.TEXT_FAINT);
        t.setTypeface(Ui.MONO);
        return t;
    }

    private View withChevron(TextView value) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(value);
        l.addView(chevron());
        return l;
    }

    private TextView chevron() {
        return Ui.chevron(c);
    }

    private static String format(float v, boolean speed) {
        return speed ? String.format(java.util.Locale.US, "%.1f×", v) : Math.round(v * 100f) + "%";
    }

    /** The running build, read from the package so it is what is installed. */
    private static String version(Context c) {
        try {
            android.content.pm.PackageInfo p =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return "Version " + p.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";   // cannot happen: it is our own package
        }
    }

    /** Called by the activity whenever the address changes underneath us. */
    void setAddress(String host, int port) {
        addressValue.setText(host == null ? "Not Set" : host);
        cardAddress.setText(host == null ? "No laptop set up"
                : "Laptop at " + EndpointText.format(host, port));
    }
}
