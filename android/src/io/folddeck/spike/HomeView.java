package io.folddeck.spike;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The screen the app lands on after unlocking, drawn as an Omarchy menu card:
 * the connection (state, both routes and which one is in use) as one section,
 * then the actions.
 *
 * It exists because the address used to be reachable only by long-pressing a
 * HUD that a single tap hid permanently — so a wrong address was a dead end.
 * Everything the connection needs is on this one card, and Back returns here.
 */
class HomeView extends FrameLayout {

    interface Listener {
        void onConnect();
        void onDisconnect();
        void onReturnToDesktop();
        void onEditAddress();
        void onOpenGuide();
        void onOpenSettings();
    }

    static final int PHASE_IDLE = 0, PHASE_CONNECTING = 1, PHASE_STREAMING = 2,
            PHASE_ERROR = 3, PHASE_BLOCKED = 4, PHASE_FINDING = 5;

    private static final String ICON_LAPTOP = "󰌢", ICON_WIFI = "󰖩",
            ICON_TAILNET = "󰛳", ICON_CONNECT = "󰌘",
            ICON_DISCONNECT = "󰌙", ICON_DESKTOP = "󰍹",
            ICON_HELP = "󰘥", ICON_SETTINGS = "";

    private final Context c;
    private final Listener listener;
    private final TextView stateText, detailText;
    private final View stateDot;
    private final LinearLayout homeRow, awayRow, addRow, primary, disconnect;
    private final TextView homeAddr, awayAddr, homeTag, awayTag;
    private boolean streaming;

    HomeView(Context c, Listener listener) {
        super(c);
        this.c = c;
        this.listener = listener;
        setBackgroundColor(Ui.BG);
        // Swallow touches: this sits over the live deck, and a tap falling
        // through would move the pointer on the laptop behind it.
        setClickable(true);

        LinearLayout col = Ui.readableColumn(c);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 28), Ui.dp(c, 20), Ui.dp(c, 28));
        col.addView(Ui.wordmark(c, 560));
        TextView tagline = Ui.text(c, "Your laptop, on the Fold", Ui.T_BODY, Ui.TEXT_DIM);
        tagline.setTypeface(Ui.MONO);
        tagline.setPadding(0, Ui.dp(c, 14), 0, 0);
        col.addView(tagline);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        col.addView(card, Ui.fillW(c, 0));
        Ui.menuCard(card);
        ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(0, Ui.dp(c, 24), 0, 0);

        // -------- connection ------------------------------------------------ //
        card.addView(Ui.menuHeading(c, "Connection…"));

        LinearLayout status = Ui.row(c, "Not Connected", "", null, null);
        stateText = label(status);
        detailText = (TextView) ((LinearLayout) status.getChildAt(0)).getChildAt(1);
        detailText.setTypeface(Ui.MONO);
        stateDot = new View(c);
        FrameLayout dotBox = new FrameLayout(c);
        dotBox.addView(stateDot, new FrameLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12),
                Gravity.CENTER));
        status.addView(dotBox, 0, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 30)));
        ((LinearLayout.LayoutParams) status.getChildAt(1).getLayoutParams()).leftMargin =
                Ui.dp(c, 8);
        card.addView(status);

        addRow = Ui.menuItem(c, ICON_LAPTOP, "Add Your Laptop", listener::onEditAddress);
        addSubtitle(addRow, "Tap to enter its address");
        card.addView(addRow);

        homeAddr = Ui.text(c, "", Ui.T_LABEL, Ui.TEXT_FAINT);
        awayAddr = Ui.text(c, "", Ui.T_LABEL, Ui.TEXT_FAINT);
        homeTag = tag();
        awayTag = tag();
        homeRow = route(ICON_WIFI, "Home Wi-Fi", homeAddr, homeTag);
        awayRow = route(ICON_TAILNET, "Tailscale", awayAddr, awayTag);
        card.addView(homeRow);
        card.addView(awayRow);
        card.addView(Ui.menuNote(c, "Uses your home Wi-Fi when it can, and "
                + "Tailscale everywhere else. Tap one to change it."));

        // -------- actions --------------------------------------------------- //
        card.addView(Ui.divider(c));
        primary = Ui.menuItem(c, ICON_CONNECT, "Connect", this::onPrimary);
        label(primary).setTextColor(Ui.pressedAccent(Ui.ACCENT));
        card.addView(primary);
        disconnect = Ui.menuItem(c, ICON_DISCONNECT, "Disconnect", listener::onDisconnect);
        card.addView(disconnect);

        card.addView(Ui.divider(c));
        card.addView(Ui.menuItem(c, ICON_HELP, "How to Use", listener::onOpenGuide));
        card.addView(Ui.menuItem(c, ICON_SETTINGS, "Settings", listener::onOpenSettings));

        // ScrollView because the cover screen in landscape is barely 1080px tall
        // and this column does not fit; without it the buttons are unreachable.
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        LinearLayout centre = new LinearLayout(c);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        centre.addView(col, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(centre);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setEndpoints(null);
        setState(PHASE_IDLE, null);
    }

    /** One route: its icon and name, the address under it, "In Use" on the right. */
    private LinearLayout route(String icon, String name, TextView addr, TextView tag) {
        LinearLayout row = Ui.menuItem(c, icon, name, listener::onEditAddress);
        addr.setTypeface(Ui.MONO);
        ((LinearLayout) row.getChildAt(1)).addView(addr);
        row.addView(tag, 2);
        return row;
    }

    private TextView tag() {
        TextView t = Ui.text(c, "", Ui.T_LABEL, Ui.OK);
        t.setTypeface(Ui.MONO_BOLD);
        return t;
    }

    private void addSubtitle(LinearLayout row, String text) {
        TextView t = Ui.text(c, text, Ui.T_LABEL, Ui.TEXT_FAINT);
        t.setTypeface(Ui.MONO);
        ((LinearLayout) row.getChildAt(1)).addView(t);
    }

    /** The title of a row from {@link Ui#row} or {@link Ui#menuItem}. */
    private static TextView label(LinearLayout row) {
        View stack = row.getChildAt(row.getChildAt(0) instanceof LinearLayout ? 0 : 1);
        return (TextView) ((LinearLayout) stack).getChildAt(0);
    }

    private void onPrimary() {
        if (streaming) listener.onReturnToDesktop(); else listener.onConnect();
    }

    // ------------------------------------------------------------------ //
    void setEndpoints(Endpoints e) {
        boolean any = e != null && e.isConfigured();
        addRow.setVisibility(any ? GONE : VISIBLE);
        homeRow.setVisibility(any ? VISIBLE : GONE);
        awayRow.setVisibility(any ? VISIBLE : GONE);
        if (!any) return;
        homeAddr.setText(e.homeHost == null ? "Not Set" : EndpointText.format(e.homeHost, e.homePort));
        awayAddr.setText(e.awayHost == null ? "Not Set" : EndpointText.format(e.awayHost, e.awayPort));
        setRoute(-1);
    }

    /**
     * Mark which route is carrying the connection.
     *
     * @param which {@link Endpoints#HOME}, {@link Endpoints#AWAY}, or -1 for none
     */
    void setRoute(int which) {
        homeTag.setText(which == Endpoints.HOME ? "In Use" : "");
        awayTag.setText(which == Endpoints.AWAY ? "In Use" : "");
    }

    /**
     * @param phase  one of the {@code PHASE_*} constants
     * @param detail the underlying message, shown verbatim — a connection error
     *               is only actionable if you can read the actual reason.
     */
    void setState(int phase, String detail) {
        streaming = phase == PHASE_STREAMING;
        if (phase == PHASE_IDLE || phase == PHASE_ERROR) setRoute(-1);
        int colour;
        String text;
        switch (phase) {
            case PHASE_STREAMING:  colour = Ui.OK;         text = "Connected";     break;
            case PHASE_CONNECTING: colour = Ui.ACCENT;     text = "Connecting…";   break;
            case PHASE_FINDING:    colour = Ui.ACCENT;     text = "Finding Your Laptop…"; break;
            case PHASE_ERROR:      colour = Ui.ERR;        text = "Can't Reach Your Laptop"; break;
            case PHASE_BLOCKED:    colour = Ui.ERR;        text = "Stopped for Safety"; break;
            default:               colour = Ui.TEXT_FAINT; text = "Not Connected"; break;
        }
        stateDot.setBackground(Ui.square(colour));
        Motion.status(stateDot, phase);
        stateText.setText(text);
        stateText.setTextColor(phase == PHASE_IDLE ? Ui.TEXT : colour);
        detailText.setText(detail == null ? "" : detail);
        detailText.setVisibility(detail == null || detail.isEmpty() ? GONE : VISIBLE);

        label(primary).setText(streaming ? "Return to Desktop" : "Connect");
        ((TextView) primary.getChildAt(0)).setText(streaming ? ICON_DESKTOP : ICON_CONNECT);
        disconnect.setVisibility(streaming ? VISIBLE : GONE);
    }
}
