package io.folddeck.spike;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the laptop is — both places it can be.
 *
 * A screen rather than an AlertDialog because the app theme is a legacy one, so
 * a system dialog arrives styled like Android 4 and undoes the rest of the work.
 *
 * Two fields, not one. The laptop has a LAN address that only exists at home
 * and a tailnet address that works anywhere, and which of them is live is a
 * fact about where the phone is standing, not a setting. Both are saved once
 * and {@link Endpoints} measures the rest.
 *
 * The live warning is the important part. The one failure this app has actually
 * suffered was the *phone's own* address being entered here: the phone then
 * dialled itself, nothing ever reached the laptop, and the only symptom was a
 * connect error naming an address that looked perfectly plausible. Every local
 * address is enumerated on the way in, so that mistake is now caught as it is
 * typed instead of an hour later.
 */
class AddressView extends Panel {

    interface Listener {
        void onSaved(Endpoints endpoints);
    }

    private final List<String> ownAddresses;
    private final Field homeField;
    private final Field awayField;

    AddressView(Context c, Endpoints current, Listener listener, Runnable onBack) {
        super(c, "Laptop address", onBack);
        LinearLayout body = content();
        ownAddresses = localAddresses();

        body.addView(Ui.body(c, "The machine running the FoldDeck host, as host:port. "
                + "Fill in both and the app connects over whichever one is live — "
                + "home Wi-Fi when you are on it, the tailnet when you are not."),
                Ui.fillW(c, 12));

        homeField = new Field(c, "Home Wi-Fi", "Fast. Only works on your own network.",
                "192.168.1.10:5000",
                current.homeHost == null ? "" : current.homeHost + ":" + current.homePort);
        body.addView(homeField.view, Ui.fillW(c, 18));

        awayField = new Field(c, "Tailnet", "Works anywhere, with Tailscale switched on.",
                "100.100.10.20:5000",
                current.awayHost == null ? "" : current.awayHost + ":" + current.awayPort);
        body.addView(awayField.view, Ui.fillW(c, 14));

        body.addView(Ui.primaryButton(c, "Save", () -> {
            HostPort home = homeField.parse();
            HostPort away = awayField.parse();
            if (home == null && away == null) return;   // nothing to save
            listener.onSaved(new Endpoints(
                    home == null ? null : home.host, home == null ? 5000 : home.port,
                    away == null ? null : away.host, away == null ? 5000 : away.port));
        }), Ui.fillW(c, 22));

        // -------- this phone's own addresses -------------------------------- //
        if (!ownAddresses.isEmpty()) {
            body.addView(Ui.sectionLabel(c, "Not the laptop"));
            StringBuilder sb = new StringBuilder();
            sb.append("This phone is ");
            for (int i = 0; i < ownAddresses.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ownAddresses.get(i));
            }
            sb.append(". Neither address above may be one of these.");
            TextView own = Ui.text(c, sb.toString(), Ui.T_LABEL, Ui.TEXT_DIM);
            own.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
            own.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));
            body.addView(own, Ui.fillW(c, 0));
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * One labelled address box with its own inline warning.
     *
     * Both boxes need identical parsing and identical self-address checking, and
     * the surest way to make them behave the same is for there to be one of them.
     */
    private final class Field {
        final LinearLayout view;
        private final EditText input;
        private final TextView warning;

        Field(Context c, String label, String subtitle, String hint, String initial) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 16));
            view.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));

            TextView title = Ui.text(c, label, Ui.T_BODY, Ui.TEXT);
            title.setTypeface(Ui.FONT_BOLD);
            view.addView(title);

            TextView sub = Ui.text(c, subtitle, Ui.T_LABEL, Ui.TEXT_DIM);
            sub.setPadding(0, Ui.dp(c, 2), 0, 0);
            view.addView(sub);

            input = Ui.input(c, initial, hint, InputType.TYPE_TEXT_VARIATION_URI);
            input.setSelectAllOnFocus(true);
            view.addView(input, Ui.fillW(c, 12));

            warning = Ui.text(c, "", Ui.T_LABEL, Ui.WARN);
            warning.setPadding(Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2), 0);
            warning.setVisibility(GONE);
            view.addView(warning, Ui.fillW(c, 0));

            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void afterTextChanged(Editable e) { check(e.toString()); }
            });
            check(initial);
        }

        /** null when the box is empty — an unused slot is allowed. */
        HostPort parse() {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return null;
            int colon = text.lastIndexOf(':');
            String h = (colon > 0 ? text.substring(0, colon) : text).trim();
            int p = 5000;
            if (colon > 0) {
                try {
                    p = Integer.parseInt(text.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Keep 5000. A typo in the port is better than refusing to
                    // save the host, which is the part that is hard to retype.
                }
            }
            return h.isEmpty() ? null : new HostPort(h, p);
        }

        private void check(String text) {
            int colon = text.lastIndexOf(':');
            String h = (colon > 0 ? text.substring(0, colon) : text).trim();
            boolean self = ownAddresses.contains(h)
                    || h.equals("localhost") || h.startsWith("127.");
            if (self && !h.isEmpty()) {
                // 127.0.0.1 is legitimate with `adb reverse`, so this is a warning
                // and never a block — it just has to be impossible to miss.
                warning.setText(h.startsWith("127.") || h.equals("localhost")
                        ? "That is this phone itself. Only correct if you are using "
                                + "adb reverse tcp:5000 tcp:5000 over USB."
                        : "That is this phone's own address. The connection will "
                                + "never leave the device. Use the laptop's address.");
                warning.setVisibility(VISIBLE);
            } else {
                warning.setVisibility(GONE);
            }
        }
    }

    /** Every IPv4 address this device currently holds, tailnet included. */
    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    // IPv4 only: the address box is typed by hand, and nobody
                    // hand-types an IPv6 address to reach their own laptop.
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            // Enumeration needs no permission, but a vendor ROM refusing it must
            // not cost you the ability to type an address.
            android.util.Log.w("FoldDeck/Address", "cannot list local addresses", t);
        }
        return out;
    }
}
