package io.folddeck.spike;

/** Permission/foreground config plus a revision-bound, expiring consumer lease. */
final class CaptureGate {
    static final long LEASE_MS = 3000;
    int revision, allowed, ready;
    private int demand;
    private long expires;

    void configure(int mask) {
        ++revision;
        allowed = mask & 7;
        ready = demand = 0;
        expires = 0;
    }

    void ready(int rev, int mask) {
        if (rev == revision) ready = mask & allowed & 7;
    }

    void demand(int rev, int mask, long received) {
        if (rev != revision) return;
        demand = mask & allowed & 6;
        expires = received + LEASE_MS;
    }

    int active(long now) { return now < expires ? ready & demand & allowed : 0; }
}
