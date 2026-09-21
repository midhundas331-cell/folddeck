package io.folddeck.spike;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How to use it.
 *
 * Written for the person who has just installed this and is looking at a black
 * screen: what to run on the laptop, what to type on the phone, and what the
 * gestures are — none of which is discoverable from a keyboard and a video pane.
 * The troubleshooting section names the one failure that has actually happened.
 */
class GuideView extends Panel {

    GuideView(Context c, Runnable onBack) {
        super(c, "How to use", onBack);
        LinearLayout body = content();

        body.addView(Ui.sectionLabel(c, "Getting connected"));
        body.addView(step(c, 1, "Start the host on the laptop",
                "It runs at login already. To check it:\n"
                        + "systemctl --user status folddeck"));
        body.addView(step(c, 2, "Find the laptop's address",
                "ip -4 addr  gives the address on the local network.\n"
                        + "tailscale ip -4  gives one that also works away from home."));
        body.addView(step(c, 3, "Enter it here and connect",
                "Type it as host:port, for example 192.168.1.10:5000. "
                        + "It is remembered, so this is a one-time step."));
        body.addView(step(c, 4, "Approve the pairing",
                "The first connection is trusted automatically and the laptop "
                        + "remembers this phone. Do that first one on a network you "
                        + "trust — every connection after it is pinned to that laptop."));

        body.addView(Ui.sectionLabel(c, "Pointer"));
        body.addView(tip(c, "Tap", "Left click"));
        body.addView(tip(c, "Press and hold", "Right click"));
        body.addView(tip(c, "Double tap, then drag", "Click and drag"));
        body.addView(tip(c, "Two fingers", "Scroll, vertically or sideways"));
        body.addView(tip(c, "Red square", "The TrackPoint, between G, H and B. Push it "
                + "to move the pointer without lifting your hands off the keys."));
        body.addView(tip(c, "Bluetooth mouse", "Pair one with the phone and it drives "
                + "the laptop on its own, with the cursor locked inside the desktop "
                + "image. Pointer speed is in Settings."));
        body.addView(tip(c, "Left + right together", "Hands the mouse back to the "
                + "phone, so you can use the app with it. The same chord takes it "
                + "back to the laptop."));
        body.addView(tip(c, "DIRECT / TRACKPAD", "The first chip on the shortcut "
                + "bar. DIRECT puts the pointer where you touch; TRACKPAD moves it "
                + "relative to where it already is, like a laptop trackpad."));

        body.addView(Ui.sectionLabel(c, "Keyboard"));
        body.addView(tip(c, "Shift, Ctrl, Alt", "Tap once to arm for the next key. "
                + "Tap again to lock it down. A third tap releases it."));
        body.addView(tip(c, "Fn", "Lights the accent legends on the top row and sends "
                + "the media keys instead of F1 to F12."));
        body.addView(tip(c, "Hold a key", "Repeats, at the same rate the laptop does."));
        body.addView(tip(c, "Shortcut bar", "The Omarchy shortcuts beside the desktop. Tap "
                + "one to send it; long-press the bar or tap + to edit, add or delete them."));

        body.addView(Ui.sectionLabel(c, "Unfolded"));
        body.addView(tip(c, "Laptop layout", "Open the phone and turn it sideways: the "
                + "screen and keyboard split evenly along the crease, and the crease "
                + "becomes the hinge. Turn it off in Settings."));
        body.addView(tip(c, "Locking", "Locking the app drops the connection, which "
                + "also stops the laptop capturing its own screen."));

        body.addView(Ui.sectionLabel(c, "If it will not connect"));
        body.addView(tip(c, "Check whose address it is",
                "The commonest mistake is entering the phone's own address instead of "
                        + "the laptop's. The phone then tries to connect to itself and "
                        + "nothing ever reaches the laptop."));
        body.addView(tip(c, "Check both are on the same network",
                "Or use the tailnet address, which works from anywhere."));
        body.addView(tip(c, "\"Stopped for safety\"",
                "The laptop answered with a different certificate than the one pinned "
                        + "here. If you reinstalled the host, clear it under "
                        + "Settings › Forget this laptop's certificate."));
    }

    /** A numbered step: the digit in a filled square, then title and detail. */
    private View step(Context c, int number, String titleText, String detail) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
        row.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));

        TextView badge = Ui.text(c, String.valueOf(number), Ui.T_LABEL, Ui.BG);
        badge.setTypeface(Ui.FONT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.square(Ui.ACCENT));
        LinearLayout.LayoutParams badgeLp =
                new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        badgeLp.rightMargin = Ui.dp(c, 12);
        badgeLp.topMargin = Ui.dp(c, 2);
        row.addView(badge, badgeLp);

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(Ui.text(c, titleText, Ui.T_BODY, Ui.TEXT));
        TextView d = Ui.text(c, detail, Ui.T_LABEL, Ui.TEXT_DIM);
        d.setPadding(0, Ui.dp(c, 4), 0, 0);
        stack.addView(d);
        row.addView(stack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = Ui.fillW(c, 8);
        row.setLayoutParams(lp);
        return row;
    }

    /** A term and what it does — the gesture reference. */
    private View tip(Context c, String term, String meaning) {
        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        stack.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));

        TextView t = Ui.text(c, term, Ui.T_BODY, Ui.TEXT);
        t.setTypeface(Ui.FONT_BOLD);
        TextView m = Ui.text(c, meaning, Ui.T_LABEL, Ui.TEXT_DIM);
        m.setPadding(0, Ui.dp(c, 3), 0, 0);
        stack.addView(t);
        stack.addView(m);
        stack.setLayoutParams(Ui.fillW(c, 8));
        return stack;
    }
}
