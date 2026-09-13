package io.folddeck.spike;

import android.content.Context;
import android.content.SharedPreferences;
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
        void onChangePin();
        void onForgetHostCertificate();
        void onLaptopLayoutChanged();
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
                Ui.fillW(c, 0));

        body.addView(Ui.row(c, "Connect after unlocking",
                "Skip this screen and go straight to the desktop",
                Ui.toggle(c, Prefs.autoConnect(prefs),
                        on -> Prefs.putBool(prefs, Prefs.AUTO_CONNECT, on)),
                null),
                Ui.fillW(c, 8));

        // -------- appearance ------------------------------------------------ //
        body.addView(Ui.sectionLabel(c, "Appearance"));

        body.addView(Ui.row(c, "Laptop layout when unfolded",
                "Hinge and bezels, with the screen and keyboard split evenly on the crease",
                Ui.toggle(c, Prefs.laptopLayout(prefs), on -> {
                    Prefs.putBool(prefs, Prefs.LAPTOP_LAYOUT, on);
                    listener.onLaptopLayoutChanged();
                }),
                null),
                Ui.fillW(c, 0));

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

        // -------- security --------------------------------------------------- //
        body.addView(Ui.sectionLabel(c, "Security"));

        body.addView(Ui.row(c, "Unlock with fingerprint",
                "PIN always works as well",
                Ui.toggle(c, lock.biometricEnabled(), lock::setBiometricEnabled),
                null),
                Ui.fillW(c, 0));

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
                "FoldDeck spike 0.1\n"
                        + "Traffic is TLS 1.3 with the host's certificate pinned on first "
                        + "connection. The PIN is an app lock, not disk encryption.",
                Ui.T_LABEL, Ui.TEXT_FAINT);
        about.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
        body.addView(about);
    }

    /** Called by the activity whenever the address changes underneath us. */
    void setAddress(String host, int port) {
        addressValue.setText(host == null ? "Not set" : host + ":" + port);
    }
}
