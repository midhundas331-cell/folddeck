package io.folddeck.spike;

/**
 * The shared spring settles, overshoots only when asked to, and keeps its
 * speed when retargeted mid-flight.
 *
 *   J=$ANDROID_HOME/platforms/android-36/android.jar
 *   javac --release 17 -cp build/classes:$J -d build/test test/SpringCheck.java
 *   java -cp build/test:build/classes:$J io.folddeck.spike.SpringCheck
 */
public class SpringCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** Runs to rest at 60 Hz; returns {seconds, peak value}. */
    private static float[] settle(Spring s) {
        float peak = s.value, t = 0f;
        while (!s.step(1f / 60) && t < 5f) {
            t += 1f / 60;
            peak = Math.max(peak, s.value);
        }
        return new float[]{t, peak};
    }

    public static void main(String[] args) {
        Spring crit = new Spring(0.4f, 1f, 0.001f);
        crit.target = 1f;
        float[] c = settle(crit);
        check(crit.value == 1f && crit.velocity == 0f, "settles exactly on the target");
        check(c[0] < 1f, "critically damped settles inside a second: " + c[0]);
        check(c[1] <= 1.001f, "ratio 1 never overshoots: " + c[1]);

        Spring soft = new Spring(0.4f, 0.8f, 0.001f);
        soft.target = 1f;
        float[] b = settle(soft);
        check(b[1] > 1.005f && b[1] < 1.06f, "ratio 0.8 overshoots a little: " + b[1]);

        // Grabbed mid-flight: a new target keeps the current value and velocity.
        Spring s = new Spring(0.4f, 0.86f, 0.001f);
        s.target = 1f;
        for (int i = 0; i < 6; i++) s.step(1f / 60);
        float v = s.velocity, x = s.value;
        check(v > 0f, "moving toward the first target");
        s.target = 0f;
        check(s.velocity == v && s.value == x, "retarget keeps state");
        s.step(1f / 60);
        check(s.value > x, "momentum carries it on before it turns back");

        // A 1 s stall (backgrounded app) is one bounded step, not a fling.
        Spring stall = new Spring(0.15f, 0.86f, 0.001f);
        stall.target = 100f;
        stall.step(1f);
        check(Math.abs(stall.value) <= 100f * 1.2f, "a long frame stays bounded");

        // The fixed-duration curve matches: starts at 0, ends at 1, same overshoot.
        check(Spring.curve(0.4f, 1f, 0f) == 0f, "curve starts at rest");
        float end = Spring.seconds(0.4f, 0.86f);
        check(Math.abs(Spring.curve(0.4f, 0.86f, end) - 1f) < 0.003f, "curve is settled at seconds()");
        check(end > 0.2f && end < 1f, "UI springs finish well inside a second: " + end);
        float peak = 0f;
        for (float t = 0f; t < end; t += 0.005f) peak = Math.max(peak, Spring.curve(0.4f, 1f, t));
        check(peak <= 1.0001f, "critical curve never overshoots");
        System.out.println("SpringCheck ok");
    }
}
