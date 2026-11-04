package io.folddeck.spike;

/**
 * A damped spring described the way Apple describes them: {@code response} is
 * the seconds one undamped swing takes, {@code ratio} the damping ratio
 * (1 = settles with no overshoot, below 1 = a little bounce). Mass is 1, so
 * stiffness = (2π/response)² and damping = 4π·ratio/response.
 *
 * Plain Java on purpose: the physics is checked on the JVM (SpringCheck).
 */
final class Spring {
    float value, velocity, target;
    private final float precision;
    private float stiffness, damping;

    /** {@code precision}: how close to the target, in the value's own units, counts as there. */
    Spring(float response, float ratio, float precision) {
        this.precision = precision;
        tune(response, ratio);
    }

    /** Changes the feel without losing where the spring is or how fast it moves. */
    Spring tune(float response, float ratio) {
        float w = (float) (2 * Math.PI / response);
        stiffness = w * w;
        damping = 2f * ratio * w;
        return this;
    }

    /** Advances {@code dt} seconds. True once at rest, with the value snapped to the target. */
    boolean step(float dt) {
        dt = Math.max(0f, Math.min(dt, 1f / 30));   // a dropped frame must not fling it
        int n = Math.max(1, (int) Math.ceil(dt * 240f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {               // semi-implicit Euler: stable at 240 Hz
            velocity += (-stiffness * (value - target) - damping * velocity) * h;
            value += velocity * h;
        }
        if (Math.abs(value - target) < precision && Math.abs(velocity) < precision * 10f) {
            value = target;
            velocity = 0f;
            return true;
        }
        return false;
    }

    /**
     * The same spring as a 0→1 curve from rest at {@code t} seconds, for
     * framework animators that want a fixed duration (see {@link #seconds}).
     */
    static float curve(float response, float ratio, float t) {
        if (t <= 0f) return 0f;
        double w = 2 * Math.PI / response, z = Math.min(ratio, 1f);
        if (z >= 0.999) return (float) (1 - Math.exp(-w * t) * (1 + w * t));
        double wd = w * Math.sqrt(1 - z * z), decay = Math.exp(-z * w * t);
        return (float) (1 - decay * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t)));
    }

    /** How long {@link #curve} takes to come within 0.2% of 1 and stay there. */
    static float seconds(float response, float ratio) {
        double w = 2 * Math.PI / response, z = Math.min(ratio, 1f);
        if (z >= 0.999) return (float) (8.4 / w);   // (1+x)e^-x = 0.002 at x ≈ 8.4
        return (float) (Math.log(500 / Math.sqrt(1 - z * z)) / (z * w));
    }
}
