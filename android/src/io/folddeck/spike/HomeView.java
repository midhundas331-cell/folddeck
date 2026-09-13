package io.folddeck.spike;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The screen the app lands on after unlocking: where the laptop is, whether it
 * is reachable, and one button that connects to it.
 *
 * It exists because the address used to be reachable only by long-pressing a
 * HUD that a single tap hid permanently — so a wrong address was a dead end you
 * could not steer out of without clearing app data. Everything the connection
 * needs is now on one screen, and Back always returns here.
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

    private final Listener listener;

    private final TextView stateText;
    private final TextView detailText;
    private final View stateDot;
    /** All assigned by buildAddressCard(), which is called from the constructor. */
    private TextView homeAddr, awayAddr, homeTag, awayTag;
    private View homeDot, awayDot;
    private View homeRow, awayRow;
    private TextView addressEmpty;
    private final TextView primary;
    private final TextView disconnect;

    private boolean streaming;

    HomeView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setBackgroundColor(Ui.BG);
        // Swallow touches: this sits over the live deck, and a tap falling
        // through would move the pointer on the laptop behind it.
        setClickable(true);

        LinearLayout col = Ui.readableColumn(c);
        col.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 28));

        col.addView(Ui.wordmark(c, 560));

        TextView tagline = Ui.text(c, "Your laptop, on the Fold", Ui.T_BODY, Ui.TEXT_DIM);
        tagline.setPadding(0, Ui.dp(c, 14), 0, 0);
        col.addView(tagline);

        // -------- status --------------------------------------------------- //
        LinearLayout status = new LinearLayout(c);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
        status.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));

        stateDot = new View(c);
        stateDot.setBackground(Ui.square(Ui.TEXT_FAINT));
        LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10));
        dotLp.rightMargin = Ui.dp(c, 12);
        status.addView(stateDot, dotLp);

        LinearLayout statusStack = new LinearLayout(c);
        statusStack.setOrientation(LinearLayout.VERTICAL);
        stateText = Ui.text(c, "Not connected", Ui.T_BODY, Ui.TEXT);
        detailText = Ui.text(c, "", Ui.T_LABEL, Ui.TEXT_DIM);
        detailText.setPadding(0, Ui.dp(c, 3), 0, 0);
        detailText.setVisibility(GONE);
        statusStack.addView(stateText);
        statusStack.addView(detailText);
        status.addView(statusStack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(status, Ui.fillW(c, 24));

        // -------- address -------------------------------------------------- //
        col.addView(Ui.sectionLabel(c, "Laptop"));
        col.addView(buildAddressCard(c), Ui.fillW(c, 0));

        // -------- actions -------------------------------------------------- //
        primary = Ui.primaryButton(c, "Connect", this::onPrimary);
        col.addView(primary, Ui.fillW(c, 24));

        disconnect = Ui.secondaryButton(c, "Disconnect", listener::onDisconnect);
        disconnect.setVisibility(GONE);
        col.addView(disconnect, Ui.fillW(c, 10));

        col.addView(buildLinkRow(c), Ui.fillW(c, 10));

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
    }

    /**
     * The address gets its own card rather than a settings row: it is the one
     * value that decides whether anything works at all.
     *
     * Both routes are shown at once, because "which network am I on" is the
     * question this screen exists to answer and hiding one of them behind a
     * tap is what made a wrong address hard to diagnose before.
     */
    private View buildAddressCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
        card.setBackground(Ui.pressable(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c)));
        card.setClickable(true);
        card.setOnClickListener(v -> listener.onEditAddress());

        addressEmpty = Ui.text(c, "Not set — tap to add", Ui.T_BODY, Ui.WARN);
        card.addView(addressEmpty);

        homeDot = new View(c);
        awayDot = new View(c);
        homeAddr = Ui.text(c, "", Ui.T_BODY, Ui.TEXT);
        awayAddr = Ui.text(c, "", Ui.T_BODY, Ui.TEXT);
        homeTag = Ui.text(c, "", Ui.T_MICRO, Ui.TEXT_FAINT);
        awayTag = Ui.text(c, "", Ui.T_MICRO, Ui.TEXT_FAINT);

        homeRow = routeRow(c, "Home Wi-Fi", homeDot, homeAddr, homeTag);
        awayRow = routeRow(c, "Tailnet", awayDot, awayAddr, awayTag);
        card.addView(homeRow, Ui.fillW(c, 0));
        card.addView(awayRow, Ui.fillW(c, 10));

        TextView change = Ui.text(c, "Change", Ui.T_LABEL, Ui.ACCENT);
        change.setPadding(0, Ui.dp(c, 12), 0, 0);
        card.addView(change);
        return card;
    }

    /** One route: a state dot, its name, the address, and a right-aligned tag. */
    private View routeRow(Context c, String name, View dot, TextView addr, TextView tag) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        dot.setBackground(Ui.square(Ui.TEXT_FAINT));
        LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8));
        dotLp.rightMargin = Ui.dp(c, 10);
        row.addView(dot, dotLp);

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        TextView label = Ui.text(c, name, Ui.T_LABEL, Ui.TEXT_DIM);
        stack.addView(label);
        stack.addView(addr);
        row.addView(stack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(tag);
        return row;
    }

    private View buildLinkRow(Context c) {
        LinearLayout links = new LinearLayout(c);
        links.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams left =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams right =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = Ui.dp(c, 10);

        links.addView(Ui.secondaryButton(c, "How to use", listener::onOpenGuide), left);
        links.addView(Ui.secondaryButton(c, "Settings", listener::onOpenSettings), right);
        return links;
    }

    private void onPrimary() {
        if (streaming) listener.onReturnToDesktop(); else listener.onConnect();
    }

    // ------------------------------------------------------------------ //
    void setEndpoints(Endpoints e) {
        boolean any = e != null && e.isConfigured();
        addressEmpty.setVisibility(any ? GONE : VISIBLE);
        int rows = any ? VISIBLE : GONE;
        homeRow.setVisibility(rows);
        awayRow.setVisibility(rows);
        if (!any) return;
        homeAddr.setText(e.homeHost == null ? "not set" : e.homeHost + ":" + e.homePort);
        awayAddr.setText(e.awayHost == null ? "not set" : e.awayHost + ":" + e.awayPort);
        homeAddr.setTextColor(e.homeHost == null ? Ui.TEXT_FAINT : Ui.TEXT);
        awayAddr.setTextColor(e.awayHost == null ? Ui.TEXT_FAINT : Ui.TEXT);
        setRoute(-1);
    }

    /**
     * Mark which route is carrying the connection.
     *
     * @param which {@link Endpoints#HOME}, {@link Endpoints#AWAY}, or -1 for none
     */
    void setRoute(int which) {
        homeDot.setBackground(Ui.square(which == Endpoints.HOME ? Ui.OK : Ui.TEXT_FAINT));
        awayDot.setBackground(Ui.square(which == Endpoints.AWAY ? Ui.OK : Ui.TEXT_FAINT));
        homeTag.setText(which == Endpoints.HOME ? "in use" : "");
        awayTag.setText(which == Endpoints.AWAY ? "in use" : "");
        homeTag.setTextColor(Ui.OK);
        awayTag.setTextColor(Ui.OK);
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
        String label;
        switch (phase) {
            case PHASE_STREAMING:  colour = Ui.OK;         label = "Connected";     break;
            case PHASE_CONNECTING: colour = Ui.ACCENT;     label = "Connecting…";   break;
            case PHASE_FINDING:    colour = Ui.ACCENT;     label = "Finding the laptop…"; break;
            case PHASE_ERROR:      colour = Ui.ERR;        label = "Cannot reach the laptop"; break;
            case PHASE_BLOCKED:    colour = Ui.ERR;        label = "Stopped for safety"; break;
            default:               colour = Ui.TEXT_FAINT; label = "Not connected"; break;
        }
        stateDot.setBackground(Ui.square(colour));
        stateText.setText(label);
        stateText.setTextColor(phase == PHASE_IDLE ? Ui.TEXT : colour);
        detailText.setText(detail == null ? "" : detail);
        detailText.setVisibility(detail == null || detail.isEmpty() ? GONE : VISIBLE);

        primary.setText(streaming ? "Return to desktop" : "Connect");
        disconnect.setVisibility(streaming ? VISIBLE : GONE);
    }
}
