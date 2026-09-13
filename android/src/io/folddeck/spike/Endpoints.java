package io.folddeck.spike;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the laptop is, from here — which is two answers, not one.
 *
 * The same machine has a LAN address that is fast but only exists on the home
 * Wi-Fi, and a tailnet address that works from anywhere but routes through the
 * VPN. Making the user pick is making them answer a question the phone can
 * simply measure: {@link #choose} opens both at once and takes whichever
 * responds, preferring home because when it is reachable it is always the
 * better link.
 *
 * The probe is a bare TCP connect. It is not a handshake and proves nothing
 * about the host being healthy — only that something is listening — which is
 * exactly the question being asked here, and it answers in milliseconds
 * instead of the seconds a real connection attempt would take to time out.
 */
final class Endpoints {

    static final int HOME = 0, AWAY = 1;
    /** {@link #decide} outcomes that are not a route. */
    static final int NONE = -1, PENDING = -2;

    /**
     * Home is given the shorter deadline on purpose. Off the home network the
     * LAN address is usually unroutable and fails instantly, but on a hostile
     * Wi-Fi it can instead hang until the OS gives up — and every millisecond
     * of that is dead time before the tailnet route the user can actually use.
     */
    private static final int HOME_TIMEOUT_MS = 700;
    private static final int AWAY_TIMEOUT_MS = 2500;

    final String homeHost, awayHost;
    final int homePort, awayPort;

    Endpoints(String homeHost, int homePort, String awayHost, int awayPort) {
        this.homeHost = empty(homeHost) ? null : homeHost;
        this.homePort = homePort;
        this.awayHost = empty(awayHost) ? null : awayHost;
        this.awayPort = awayPort;
    }

    /** The outcome of {@link #choose}: which route won, and where it points. */
    static final class Route {
        final int which;
        final String host;
        final int port;

        Route(int which, String host, int port) {
            this.which = which;
            this.host = host;
            this.port = port;
        }

        String label() {
            return which == HOME ? "home Wi-Fi" : "tailnet";
        }

        String describe() {
            return host + ":" + port + " · " + label();
        }
    }

    interface Listener {
        void onChosen(Route route);

        /** Neither address answered. */
        void onNothingReachable();
    }

    boolean isConfigured() {
        return homeHost != null || awayHost != null;
    }

    boolean hasBoth() {
        return homeHost != null && awayHost != null;
    }

    /** The address to show when nothing has been probed yet. */
    String summary() {
        if (!isConfigured()) return null;
        if (!hasBoth()) return homeHost != null
                ? homeHost + ":" + homePort
                : awayHost + ":" + awayPort;
        return homeHost + ":" + homePort + "  ·  " + awayHost + ":" + awayPort;
    }

    /**
     * Probe both addresses in parallel and report the winner on the main thread.
     *
     * Only one address configured is the common case on first run, and it skips
     * straight to that one rather than paying a timeout to discover the other
     * is blank.
     */
    void choose(Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!isConfigured()) {
            main.post(listener::onNothingReachable);
            return;
        }
        if (!hasBoth()) {
            Route only = homeHost != null
                    ? new Route(HOME, homeHost, homePort)
                    : new Route(AWAY, awayHost, awayPort);
            main.post(() -> listener.onChosen(only));
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        // null = still probing. Deciding only from "both known, or home won"
        // keeps every arrival order fast; an earlier version could only resolve
        // a double failure via the backstop when away failed first.
        final Boolean[] homeOk = {null};
        final Boolean[] awayOk = {null};

        final Runnable settle = () -> {
            if (done.get()) return;
            int verdict = decide(homeOk[0], awayOk[0]);
            if (verdict == PENDING) return;
            if (done.getAndSet(true)) return;
            if (verdict == HOME)      listener.onChosen(new Route(HOME, homeHost, homePort));
            else if (verdict == AWAY) listener.onChosen(new Route(AWAY, awayHost, awayPort));
            else                      listener.onNothingReachable();
        };

        new Thread(() -> {
            boolean ok = reachable(homeHost, homePort, HOME_TIMEOUT_MS);
            main.post(() -> {
                homeOk[0] = ok;
                settle.run();
            });
        }, "folddeck-probe-home").start();

        new Thread(() -> {
            boolean ok = reachable(awayHost, awayPort, AWAY_TIMEOUT_MS);
            main.post(() -> {
                awayOk[0] = ok;
                settle.run();
            });
        }, "folddeck-probe-away").start();

        // Backstop: if a probe thread stalls past its own socket timeout, fail
        // rather than leaving the user on "Finding the laptop" forever.
        main.postDelayed(() -> {
            if (done.get()) return;
            // Away outlasting home is normal; take it if it has already answered.
            if (Boolean.TRUE.equals(awayOk[0])) {
                if (!done.getAndSet(true)) {
                    listener.onChosen(new Route(AWAY, awayHost, awayPort));
                }
            } else if (!done.getAndSet(true)) {
                listener.onNothingReachable();
            }
        }, AWAY_TIMEOUT_MS + 800);
    }

    /**
     * The whole routing rule, as a pure function so it can be tested without a
     * device: null means that probe has not reported yet.
     *
     * Home winning outright is decided the moment it answers — there is nothing
     * a later away result could add. Everything else waits for both, so a fast
     * failure on either side never commits to the wrong route.
     */
    static int decide(Boolean homeOk, Boolean awayOk) {
        if (Boolean.TRUE.equals(homeOk)) return HOME;
        if (homeOk == null || awayOk == null) return PENDING;
        return Boolean.TRUE.equals(awayOk) ? AWAY : NONE;
    }

    private static boolean reachable(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    // Persistence
    // ------------------------------------------------------------------ //
    static Endpoints load(SharedPreferences p) {
        migrate(p);
        return new Endpoints(
                p.getString(Prefs.HOST_HOME, null), p.getInt(Prefs.PORT_HOME, 5000),
                p.getString(Prefs.HOST_AWAY, null), p.getInt(Prefs.PORT_AWAY, 5000));
    }

    void save(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        if (homeHost != null) e.putString(Prefs.HOST_HOME, homeHost).putInt(Prefs.PORT_HOME, homePort);
        else e.remove(Prefs.HOST_HOME).remove(Prefs.PORT_HOME);
        if (awayHost != null) e.putString(Prefs.HOST_AWAY, awayHost).putInt(Prefs.PORT_AWAY, awayPort);
        else e.remove(Prefs.HOST_AWAY).remove(Prefs.PORT_AWAY);
        e.apply();
    }

    /**
     * Carry a single pre-split address into whichever slot it belongs in.
     *
     * A tailnet address is unmistakable — Tailscale hands out 100.64.0.0/10 —
     * so an existing install lands in the right slot without asking.
     */
    private static void migrate(SharedPreferences p) {
        if (p.contains(Prefs.HOST_HOME) || p.contains(Prefs.HOST_AWAY)) return;
        String old = p.getString(Prefs.HOST, null);
        if (old == null) return;
        int oldPort = p.getInt(Prefs.PORT, 5000);
        if (isTailnet(old)) {
            p.edit().putString(Prefs.HOST_AWAY, old).putInt(Prefs.PORT_AWAY, oldPort).apply();
        } else {
            p.edit().putString(Prefs.HOST_HOME, old).putInt(Prefs.PORT_HOME, oldPort).apply();
        }
    }

    /** True for the 100.64.0.0/10 CGNAT range Tailscale allocates from. */
    static boolean isTailnet(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
