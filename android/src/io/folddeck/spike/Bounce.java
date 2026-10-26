package io.folddeck.spike;

import java.util.HashMap;
import java.util.Map;

/**
 * Press-and-bounce for controls painted on a Canvas (keys, trackpad buttons),
 * keyed by an int. Down is a quick ease into {@link #DOWN}; release springs
 * back past 1 and settles, the same curve {@link Motion#press} uses for Views.
 *
 * Pure Java so it can be checked on a JVM. The view asks {@link #animating}
 * after drawing and keeps invalidating while it is true.
 */
final class Bounce {
    static final float DOWN = 0.93f;
    static final long DOWN_MS = 70, UP_MS = 320;

    private final Map<Integer, long[]> live = new HashMap<>();   // {pressedAt, releasedAt}

    void press(int id, long now) {
        live.put(id, new long[]{now, 0});
    }

    void release(int id, long now) {
        long[] t = live.get(id);
        if (t != null && t[1] == 0) t[1] = Math.max(now, t[0] + 1);
    }

    void clear() {
        live.clear();
    }

    float scale(int id, long now) {
        long[] t = live.get(id);
        if (t == null) return 1f;
        if (t[1] == 0) return downScale(now - t[0]);
        float from = downScale(t[1] - t[0]);
        float u = (now - t[1]) / (float) UP_MS;
        if (u >= 1f) {
            live.remove(id);
            return 1f;
        }
        return from + (1f - from) * backOut(u);
    }

    boolean animating(long now) {
        for (long[] t : live.values()) {
            if (t[1] != 0 || now - t[0] < DOWN_MS) return true;
        }
        return false;
    }

    private static float downScale(long ms) {
        float u = Math.min(1f, ms / (float) DOWN_MS);
        float eased = 1f - (1f - u) * (1f - u);
        return 1f - (1f - DOWN) * eased;
    }

    /** Ease-out with a small overshoot past 1: the bounce. */
    static float backOut(float u) {
        float c1 = 1.9f, c3 = c1 + 1f, x = u - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }
}
