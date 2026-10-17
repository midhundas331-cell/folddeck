package io.folddeck.spike;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings.
 *
 * Two of these rows are recovery paths that previously did not exist anywhere,
 * and their absence was the app's worst failure mode: a changed host
 * certificate stopped the client permanently with no way to re-pin, and the
 * only route out was clearing app data — which also regenerated the client
 * token, so the host then refused the device as unknown. Both ends of that trap
 * are openable from here now.
 */
class SettingsView extends Panel {

    interface Listener {
        void onEditAddress();
        void onMouseSensitivity(float sens);
        void onChangePin();
        void onForgetHostCertificate();
        void onControlsChanged();
        void onHudChanged();
        void onEditShortcuts();
    }

    private final TextView addressValue;

    SettingsView(Context c, SharedPreferences prefs, Lock lock,
                 Listener listener, Runnable onBack) {
        super(c, "Settings", onBack);

        LinearLayout body = content();

        // -------- connection ----------------------------------------------- //
        body.addView(Ui.sectionLabel(c, "Connection"));

        addressValue = Ui.text(c, "", Ui.T_LABEL, Ui.ACCENT);
        body.addView(Ui.row(c, "Laptop address",
                "Where the host is listening", addressValue, listener::onEditAddress),
                Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Connect after unlocking",
                "Skip this screen and go straight to the desktop",
                Ui.toggle(c, Prefs.autoConnect(prefs),
                        on -> Prefs.putBool(prefs, Prefs.AUTO_CONNECT, on)),
                null),
                Ui.fillW(c, 8));

        // -------- appearance ------------------------------------------------ //
        body.addView(Ui.sectionLabel(c, "Appearance"));

        body.addView(Ui.row(c, "Laptop layout when unfolded",
                "Hinge and bezels frame a full-width desktop above adaptive controls",
                Ui.toggle(c, Prefs.laptopLayout(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.LAPTOP_LAYOUT, on);
                    listener.onControlsChanged();
                }),
                null),
                Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Hide keyboard when one is connected",
                "A Bluetooth or USB keyboard types on the laptop, and the desktop "
                        + "and shortcuts take the space",
                Ui.toggle(c, Prefs.hideKeyboard(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.HIDE_KEYBOARD, on);
                    listener.onControlsChanged();
                }),
                null),
                Ui.fillW(c, 6));

        TextView scaleValue = Ui.text(c, percentLabel(Prefs.controlScale(prefs)),
                Ui.T_LABEL, Ui.ACCENT);
        LinearLayout scale = sliderRow(c, "Control size", scaleValue,
                Ui.slider(c, Prefs.controlScale(prefs), Prefs.CONTROL_SCALE_MIN,
                        Prefs.CONTROL_SCALE_MAX, value -> {
                            scaleValue.setText(percentLabel(value));
                            prefs.edit().putFloat(Prefs.CONTROL_SCALE, value).apply();
                            listener.onControlsChanged();
                        }));
        body.addView(Ui.row(c, "Control size",
                "Compact gives the desktop more room; large makes controls easier to hit",
                scale, null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Tactile key feedback",
                "A quiet vibration on keyboard and trackpad-button presses",
                Ui.toggle(c, Prefs.keyboardHaptics(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.KEYBOARD_HAPTICS, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Statistics overlay",
                "Bitrate, frame counts and decode latency over the video",
                Ui.toggle(c, Prefs.showHud(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.SHOW_HUD, on);
                    listener.onHudChanged();
                }),
                null),
                Ui.fillW(c, 8));

        body.addView(Ui.row(c, "Shortcuts",
                "The Omarchy shortcut bar beside the desktop: edit, add, delete",
                null, listener::onEditShortcuts),
                Ui.fillW(c, 8));

        // -------- touch and trackpad ---------------------------------------- //
        body.addView(Ui.sectionLabel(c, "Touch & trackpad"));

        body.addView(Ui.row(c, "Dedicated trackpad",
                "Resizes the keyboard and adds held left/right buttons",
                Ui.toggle(c, Prefs.trackpadDeck(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.TRACKPAD_DECK, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Scroll rail",
                "Theme-matched scrolling strip beside the dedicated pad",
                Ui.toggle(c, Prefs.trackpadScroll(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.TRACKPAD_SCROLL, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Natural scrolling",
                "Dragging down moves the page content down",
                Ui.toggle(c, Prefs.naturalScroll(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.NATURAL_SCROLL, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Left-handed buttons",
                "Swaps the dedicated trackpad's left and right zones",
                Ui.toggle(c, Prefs.trackpadLeftHanded(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.TRACKPAD_LEFT_HANDED, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Trackpad on the left in landscape",
                "Off puts it to the right of the keyboard",
                Ui.toggle(c, Prefs.trackpadLeft(prefs, true), on -> {
                    Prefs.putBool(prefs, Prefs.TRACKPAD_LEFT_LANDSCAPE, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Trackpad on the left in portrait",
                "Off puts it to the right of the shortcut shelf",
                Ui.toggle(c, Prefs.trackpadLeft(prefs, false), on -> {
                    Prefs.putBool(prefs, Prefs.TRACKPAD_LEFT_PORTRAIT, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Touchscreen trackpad mode",
                "Finger movement over the desktop is relative instead of direct",
                Ui.toggle(c, Prefs.pointerMode(prefs) == PointerPad.MODE_TRACKPAD, on -> {
                    prefs.edit().putInt(Prefs.POINTER_MODE,
                            on ? PointerPad.MODE_TRACKPAD : PointerPad.MODE_DIRECT).apply();
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        TextView padSpeedValue = Ui.text(c, speedLabel(Prefs.trackpadSens(prefs)),
                Ui.T_LABEL, Ui.ACCENT);
        LinearLayout padSpeed = sliderRow(c, "Trackpad speed", padSpeedValue,
                Ui.slider(c, Prefs.trackpadSens(prefs), Prefs.TRACKPAD_SENS_MIN,
                        Prefs.TRACKPAD_SENS_MAX, value -> {
                            padSpeedValue.setText(speedLabel(value));
                            prefs.edit().putFloat(Prefs.TRACKPAD_SENS, value).apply();
                            listener.onControlsChanged();
                        }));
        body.addView(Ui.row(c, "Trackpad speed",
                "Cursor travel from the dedicated pad", padSpeed, null), Ui.fillW(c, 6));

        TextView nubSpeedValue = Ui.text(c, speedLabel(Prefs.trackpointSens(prefs)),
                Ui.T_LABEL, Ui.ACCENT);
        LinearLayout nubSpeed = sliderRow(c, "TrackPoint speed", nubSpeedValue,
                Ui.slider(c, Prefs.trackpointSens(prefs), Prefs.TRACKPOINT_SENS_MIN,
                        Prefs.TRACKPOINT_SENS_MAX, value -> {
                            nubSpeedValue.setText(speedLabel(value));
                            prefs.edit().putFloat(Prefs.TRACKPOINT_SENS, value).apply();
                            listener.onControlsChanged();
                        }));
        body.addView(Ui.row(c, "TrackPoint speed",
                "Movement from the red keyboard nub", nubSpeed, null), Ui.fillW(c, 8));

        // -------- mouse ------------------------------------------------------ //
        body.addView(Ui.sectionLabel(c, "Mouse"));

        TextView speedValue = Ui.text(c, speedLabel(Prefs.mouseSens(prefs)),
                Ui.T_LABEL, Ui.ACCENT);
        LinearLayout speed = new LinearLayout(c);
        speed.setGravity(android.view.Gravity.CENTER_VERTICAL);
        speed.addView(speedValue);
        View bar = Ui.slider(c, Prefs.mouseSens(prefs),
                Prefs.MOUSE_SENS_MIN, Prefs.MOUSE_SENS_MAX, sens -> {
                    speedValue.setText(speedLabel(sens));
                    prefs.edit().putFloat(Prefs.MOUSE_SENS, sens).apply();
                    listener.onMouseSensitivity(sens);
                });
        bar.setContentDescription("Pointer speed");
        ((LinearLayout.LayoutParams) bar.getLayoutParams()).leftMargin = Ui.dp(c, 12);
        speed.addView(bar);

        body.addView(Ui.row(c, "Pointer speed",
                "How far the cursor travels for a given movement of the mouse. "
                        + "1.0x tracks the desktop image one-to-one.",
                speed, null),
                Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Capture Bluetooth mouse",
                "Send it to the laptop while the desktop is visible; turn off to keep Android's pointer",
                Ui.toggle(c, Prefs.mouseAutoCapture(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.MOUSE_AUTO_CAPTURE, on);
                    listener.onControlsChanged();
                }), null), Ui.fillW(c, 6));

        TextView mouseNote = Ui.text(c,
                "A Bluetooth mouse drives the laptop whenever the desktop is on "
                        + "screen. Press left and right together to hand it back to the "
                        + "phone, and again to take it back. On these settings screens "
                        + "the mouse is Android's, and so is its speed — that one is in "
                        + "Android Settings, under Accessibility.",
                Ui.T_LABEL, Ui.TEXT_FAINT);
        mouseNote.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), 0);
        body.addView(mouseNote);

        // -------- security --------------------------------------------------- //
        body.addView(Ui.sectionLabel(c, "Security"));

        body.addView(Ui.row(c, "Unlock with fingerprint",
                "PIN always works as well",
                Ui.toggle(c, lock.biometricEnabled(), lock::setBiometricEnabled),
                null),
                Ui.fillW(c, 6));

        body.addView(Ui.row(c, "Change PIN",
                "You will be asked to set a new four-digit PIN",
                null, listener::onChangePin),
                Ui.fillW(c, 8));

        body.addView(Ui.row(c, "Forget this laptop's certificate",
                "Only if the host was reinstalled. The next connection trusts "
                        + "whatever certificate answers, so do it on a network you trust.",
                null, listener::onForgetHostCertificate),
                Ui.fillW(c, 8));

        // -------- about ------------------------------------------------------ //
        body.addView(Ui.sectionLabel(c, "About"));
        TextView about = Ui.text(c,
                version(c) + "\n"
                        + "Traffic is TLS 1.3 with the host's certificate pinned on first "
                        + "connection. The PIN is an app lock, not disk encryption.",
                Ui.T_LABEL, Ui.TEXT_FAINT);
        about.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
        body.addView(about);
    }

    /**
     * The running build, read from the package rather than written here.
     *
     * The version was a literal in this file and 1 in the manifest, which is
     * two places to remember and therefore one place that goes stale. Asking
     * the PackageManager means what Settings shows is what is actually
     * installed, which is the only version worth reporting.
     */
    private static String version(Context c) {
        try {
            android.content.pm.PackageInfo p =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return "FoldDeck " + p.versionName + "  (build " + p.getLongVersionCode() + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "FoldDeck";   // cannot happen: it is our own package
        }
    }

    private static LinearLayout sliderRow(Context c, String label, TextView value, View slider) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(value);
        slider.setContentDescription(label);
        ((LinearLayout.LayoutParams) slider.getLayoutParams()).leftMargin = Ui.dp(c, 12);
        row.addView(slider);
        return row;
    }

    private static String percentLabel(float value) {
        return Math.round(value * 100f) + "%";
    }

    private static String speedLabel(float sens) {
        return String.format(java.util.Locale.US, "%.2fx", sens);
    }

    /** Called by the activity whenever the address changes underneath us. */
    void setAddress(String host, int port) {
        addressValue.setText(host == null ? "Not set" : EndpointText.format(host, port));
    }
}
