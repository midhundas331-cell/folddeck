package io.folddeck.spike;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How to use it, on the same Omarchy menu card as Settings.
 *
 * Written for someone who has just installed this and is looking at a black
 * screen: what to run on the laptop, what to enter on the phone, and the
 * gestures, none of which a keyboard and a video pane make discoverable.
 */
class GuideView extends Panel {

    private final Context c;

    GuideView(Context c, Runnable onBack) {
        super(c, "How to Use", onBack);
        this.c = c;
        LinearLayout card = content();
        Ui.menuCard(card);
        card.addView(Ui.menuHeading(c, "How to Use…"));

        section(card, "Get Connected", null,
                step("1", "Start FoldDeck on Your Laptop",
                        "It starts when you log in. To check it's running, enter "
                                + "systemctl --user status folddeck in a terminal."),
                step("2", "Find Your Laptop's Address",
                        "Enter ip -4 addr in a terminal for your home Wi-Fi address, "
                                + "or tailscale ip -4 for one that works anywhere."),
                step("3", "Add It to FoldDeck",
                        "On the home screen, tap Add Your Laptop and enter the "
                                + "address, like 192.168.1.10:5000. You only do this once."),
                step("4", "Connect",
                        "Your phone and laptop remember each other after the first "
                                + "connection. Do it on a network you trust, like your "
                                + "home Wi-Fi."));

        section(card, "Touch & Trackpad", null,
                tip("Tap", "Click."),
                tip("Touch and Hold", "Right-click."),
                tip("Double-Tap and Drag", "Drag items."),
                tip("Swipe with Two Fingers", "Scroll in any direction."),
                tip("Trackpad Button", "The first shortcut button switches between "
                        + "tapping exactly where you want to click and moving the cursor "
                        + "like a trackpad."),
                tip("Pointing Stick", "Push the red dot between G, H and B to move "
                        + "the cursor without leaving the keys."));

        section(card, "Mouse", null,
                tip("Bluetooth Mouse", "Pair a mouse with your phone and it controls "
                        + "your laptop. Change its speed in Settings › Mouse."),
                tip("Press Both Buttons", "Switches the mouse between your laptop and "
                        + "your phone."));

        section(card, "Keyboard", null,
                tip("Shift, Ctrl and Alt", "Tap once for the next key, twice to lock "
                        + "it, and once more to release."),
                tip("Fn", "Turns the top row into media keys, like volume and brightness."),
                tip("Touch and Hold a Key", "Repeats it."),
                tip("Shortcuts", "Tap one to run it. Touch and hold the shortcuts, or "
                        + "tap +, to change them."));

        section(card, "Unfolded", null,
                tip("Laptop Frame", "Open your phone and turn it sideways. The fold "
                        + "becomes the hinge, with the screen above and the keyboard "
                        + "below. Turn it off in Settings › Display."),
                tip("Watch Full Screen", "Tap ▣ to show only the laptop screen, for "
                        + "videos. Tap it again to bring the controls back."),
                tip("Lock", "Locking FoldDeck disconnects from your laptop and stops "
                        + "sharing its screen."));

        section(card, "If It Won't Connect", null,
                tip("Check the Address", "Make sure it's your laptop's address, not "
                        + "your phone's."),
                tip("Check the Network", "Your phone and laptop need to be on the same "
                        + "Wi-Fi. Use your Tailscale address to connect from anywhere."),
                tip("\"Stopped for Safety\"", "Your laptop's identity changed. If you "
                        + "reinstalled FoldDeck on it, go to Settings › Lock & Security "
                        + "› Reset Laptop Trust."));

        // The card opens straight onto its first section.
        ((LinearLayout) card.getChildAt(1)).removeViewAt(0);
    }

    private void section(LinearLayout card, String header, String note, View... rows) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(Ui.divider(c));
        wrap.addView(Ui.menuSection(c, header));
        for (View row : rows) wrap.addView(row);
        if (note != null) wrap.addView(Ui.menuNote(c, note));
        card.addView(wrap);
    }

    /** A numbered step: the number where a menu row has its icon. */
    private View step(String number, String title, String detail) {
        LinearLayout row = tip(title, detail);
        TextView n = Ui.text(c, number, 22f, Ui.ACCENT);
        n.setTypeface(Ui.MONO_BOLD);
        n.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        row.addView(n, 0, new LinearLayout.LayoutParams(Ui.dp(c, 44),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).leftMargin = Ui.dp(c, 8);
        row.setGravity(android.view.Gravity.TOP);
        return row;
    }

    /** A name and what it does. */
    private LinearLayout tip(String title, String detail) {
        LinearLayout row = Ui.row(c, title, detail, null, null);
        TextView sub = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        sub.setTextColor(Ui.TEXT_DIM);
        sub.setPadding(0, Ui.dp(c, 4), 0, 0);
        return row;
    }
}
