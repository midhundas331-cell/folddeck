package io.folddeck.spike;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Home, built like `omarchy menu`: FOLDDECK in block letters over a square
 * card with a dim heading (the connection state) and a column of icon rows.
 * The main action sits in the selected row, where Omarchy's cursor starts.
 * Connection state always comes from the host.
 */
class HomeView extends FrameLayout {
    interface Listener {
        void onConnect();
        void onDisconnect();
        void onReturnToDesktop();
        void onEditAddress();
        void onOpenSettings();
    }

    static final int PHASE_IDLE = 0, PHASE_CONNECTING = 1, PHASE_STREAMING = 2,
            PHASE_ERROR = 3, PHASE_BLOCKED = 4, PHASE_FINDING = 5;

    // Nerd Font glyphs, all in assets/fonts/Icons.ttf.
    private static final String ICON_CONNECT = "󰌘", ICON_DISCONNECT = "󰌙",
            ICON_DESKTOP = "󰍹", ICON_ADD = "󰐕",
            ICON_LAPTOP = "󰌢", ICON_SETTINGS = "";

    private final Context c;
    private final TextView stateText, detailText, laptopAddr, routeTag;
    private final View stateDot;
    private final LinearLayout main, stop, laptopRow, column;
    private Endpoints endpoints;
    private boolean streaming, busy, configured;
    private int activeRoute = -1;

    HomeView(Context c, Listener listener) {
        super(c);
        this.c = c;
        setBackground(Ui.backdrop());
        setClickable(true);

        column = Ui.readableColumn(c, 480);
        column.setPadding(Ui.dp(c, 24), Ui.dp(c, 32), Ui.dp(c, 24), Ui.dp(c, 32));

        column.addView(Ui.blockWordmark(c, 480), Ui.fillW(c, 0));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.box(Ui.SURFACE, Ui.LINE, c));
        Ui.elevate(card, c, Ui.FLOAT_MID_DP);
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 14));

        // Omarchy's dim "Go…" line, here the connection state.
        LinearLayout heading = new LinearLayout(c);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(Ui.dp(c, 2), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10));
        stateText = Ui.text(c, "", 17f, Ui.TEXT_FAINT);
        stateText.setTypeface(Ui.MONO);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading.addView(stateText, new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f));
        stateDot = new View(c);
        heading.addView(stateDot, new LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)));
        card.addView(heading);

        // Only ever says something when there is something to act on.
        detailText = Ui.text(c, "", Ui.T_LABEL, Ui.TEXT_DIM);
        detailText.setTypeface(Ui.MONO);
        detailText.setTextIsSelectable(true);
        detailText.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), Ui.dp(c, 12));
        card.addView(detailText);

        main = row(ICON_CONNECT, "Connect", () -> {
            if (busy) return;
            if (streaming) listener.onReturnToDesktop(); else listener.onConnect();
        });
        // The cursor row: selected fill with Omarchy's thin accent bar on the left.
        LayerDrawable selected = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                Ui.square(Ui.SELECTED), Ui.square(Ui.ACCENT)});
        selected.setLayerWidth(1, Ui.dp(c, 4));
        selected.setLayerInset(1, 0, Ui.dp(c, 9), 0, Ui.dp(c, 9));
        selected.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        main.setBackground(Ui.pressable(selected));
        card.addView(main);
        stop = row(ICON_DISCONNECT, "Disconnect", listener::onDisconnect);
        card.addView(stop);

        card.addView(Ui.divider(c));
        laptopRow = row(ICON_LAPTOP, "Laptop", listener::onEditAddress);
        laptopAddr = Ui.text(c, "", Ui.T_MICRO, Ui.TEXT_DIM);
        laptopAddr.setTypeface(Ui.MONO);
        laptopAddr.setSingleLine(true);
        laptopAddr.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ((LinearLayout) laptopRow.getChildAt(1)).addView(laptopAddr);
        routeTag = Ui.text(c, "", Ui.T_MICRO, Ui.OK);
        routeTag.setTypeface(Ui.MONO_BOLD);
        routeTag.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 4), 0);
        laptopRow.addView(routeTag, 2);
        card.addView(laptopRow);
        card.addView(row(ICON_SETTINGS, "Settings", listener::onOpenSettings));
        column.addView(card, Ui.fillW(c, 28));

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout centre = new LinearLayout(c);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        centre.addView(column, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scroll.addView(centre);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setEndpoints(null);
        setState(PHASE_IDLE, null);
    }

    /** One Omarchy menu row: large icon, mono label, chevron. Child 1 is the label stack. */
    private LinearLayout row(String icon, String label, Runnable onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(c, 58));
        r.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        r.setBackground(Ui.pressable(Ui.square(Color.TRANSPARENT)));
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(label);
        r.setOnClickListener(v -> onClick.run());
        Motion.press(r);

        TextView glyph = Ui.text(c, icon, 30f, Ui.TEXT);
        glyph.setTypeface(Ui.ICONS);
        glyph.setTextColor(Ui.pressedAccent(Ui.TEXT));
        glyph.setDuplicateParentStateEnabled(true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(c, 40), LayoutParams.WRAP_CONTENT));

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(c, label, Ui.T_BODY, Ui.TEXT);
        name.setTypeface(Ui.MONO);
        name.setTextColor(Ui.pressedAccent(Ui.TEXT));
        name.setDuplicateParentStateEnabled(true);
        stack.addView(name);
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f);
        stackLp.leftMargin = Ui.dp(c, 10);
        r.addView(stack, stackLp);
        r.addView(Ui.chevron(c));
        return r;
    }

    private void label(LinearLayout row, String icon, String text) {
        ((TextView) row.getChildAt(0)).setText(icon);
        ((TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(0)).setText(text);
        row.setContentDescription(text);
    }

    void setEndpoints(Endpoints e) {
        endpoints = e;
        configured = e != null && e.isConfigured();
        laptopRow.setVisibility(configured ? VISIBLE : GONE);
        showAddress();
        showMain();
    }

    void enterPage() {
        Motion.stagger(column);
    }

    void setRoute(int which) {
        activeRoute = which;
        showAddress();
    }

    /** Both addresses at rest; while live, the one in use and which network it is. */
    private void showAddress() {
        if (!configured) return;
        boolean live = streaming && activeRoute >= 0;
        routeTag.setVisibility(live ? VISIBLE : GONE);
        if (live) {
            boolean home = activeRoute == Endpoints.HOME;
            routeTag.setText(home ? "wi-fi" : "tailscale");
            laptopAddr.setText(home
                    ? EndpointText.format(endpoints.homeHost, endpoints.homePort)
                    : EndpointText.format(endpoints.awayHost, endpoints.awayPort));
            return;
        }
        StringBuilder s = new StringBuilder();
        if (endpoints.homeHost != null) s.append(EndpointText.format(endpoints.homeHost, endpoints.homePort));
        if (endpoints.awayHost != null) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(EndpointText.format(endpoints.awayHost, endpoints.awayPort));
        }
        laptopAddr.setText(s);
    }

    private void showMain() {
        if (streaming) label(main, ICON_DESKTOP, "Open Desktop");
        else if (busy) label(main, ICON_CONNECT, "Connecting…");
        else if (configured) label(main, ICON_CONNECT, "Connect");
        else label(main, ICON_ADD, "Add Your Laptop");
        main.setAlpha(busy ? 0.6f : 1f);
        label(stop, ICON_DISCONNECT, busy ? "Cancel" : "Disconnect");
        stop.setVisibility(streaming || busy ? VISIBLE : GONE);
    }

    void setState(int phase, String detail) {
        streaming = phase == PHASE_STREAMING;
        busy = phase == PHASE_CONNECTING || phase == PHASE_FINDING;
        if (phase == PHASE_IDLE || phase == PHASE_ERROR || phase == PHASE_BLOCKED
                || phase == PHASE_FINDING) activeRoute = -1;
        int colour;
        String text;
        switch (phase) {
            case PHASE_STREAMING: colour = Ui.OK; text = "Connected"; break;
            case PHASE_CONNECTING: colour = Ui.ACCENT; text = "Connecting…"; break;
            case PHASE_FINDING: colour = Ui.ACCENT; text = "Finding your laptop…"; break;
            case PHASE_ERROR: colour = Ui.ERR; text = "Can't connect"; break;
            case PHASE_BLOCKED: colour = Ui.ERR; text = "Stopped for safety"; break;
            default: colour = Ui.TEXT_FAINT; text = "Ready…"; break;
        }
        stateDot.setBackground(Ui.square(colour));
        Motion.status(stateDot, phase);
        stateText.setText(text);
        stateText.setTextColor(phase == PHASE_ERROR || phase == PHASE_BLOCKED ? Ui.ERR : Ui.TEXT_FAINT);
        // Progress is the pulsing dot; words only for something to fix.
        boolean say = detail != null && !detail.isEmpty() && !busy && !streaming;
        detailText.setText(say ? detail : "");
        detailText.setVisibility(say ? VISIBLE : GONE);
        showMain();
        showAddress();
    }
}
