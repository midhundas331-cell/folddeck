package io.folddeck.spike;

/**
 * JVM check for the mouse cursor maths and the hand-back chord. After ./build.sh:
 *
 *   J=~/Android/Sdk/platforms/android-36/android.jar
 *   javac --release 17 -cp build/classes:$J -d build/test test/MouseCheck.java
 *   java -cp build/test:build/classes:$J io.folddeck.spike.MouseCheck
 *
 * Both halves are the pieces that fail quietly. A broken clamp lets the cursor
 * off the desktop image with nothing on screen to show where it went; a broken
 * chord either swallows every right click or leaves a mouse button held down on
 * the laptop, which looks like a broken laptop rather than a broken phone.
 */
public class MouseCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void near(float got, float want, String what) {
        check(Math.abs(got - want) < 1e-4f, what + ": got " + got + " want " + want);
    }

    public static void main(String[] args) {
        // -------- travel -------------------------------------------------- //
        near(PointerPad.step(0.5f, 100f, 1000), 0.6f, "a tenth of the way across");
        near(PointerPad.step(0.5f, -100f, 1000), 0.4f, "and back");
        near(PointerPad.step(0.5f, 0f, 1000), 0.5f, "no delta, no move");

        // The clamp is what keeps the cursor inside the desktop image.
        near(PointerPad.step(0.9f, 5000f, 1000), 1f, "clamped at the right edge");
        near(PointerPad.step(0.1f, -5000f, 1000), 0f, "clamped at the left edge");
        near(PointerPad.step(0f, -1f, 1000), 0f, "cannot go further left than 0");

        // Sub-pixel accumulation: the whole point of holding the cursor as a
        // fraction. Truncating each step to permille would lose all of this,
        // and a low pointer speed would move the cursor not at all.
        float pos = 0.5f;
        for (int i = 0; i < 40; i++) pos = PointerPad.step(pos, 0.25f, 1000);
        near(pos, 0.51f, "forty quarter-pixel steps add up");

        // Sensitivity is applied by the caller, so it is just a scaled delta.
        near(PointerPad.step(0.5f, 100f * 0.25f, 1000), 0.525f, "quarter speed");
        near(PointerPad.step(0.5f, 100f * 3f, 1000), 0.8f, "triple speed");

        // -------- buttons and the hand-back chord -------------------------- //
        final int LEFT = 1, RIGHT = 2, MIDDLE = 3;
        final int NONE = 0, LEFT_HELD = 1 << LEFT, RIGHT_HELD = 1 << RIGHT,
                MIDDLE_HELD = 1 << MIDDLE;

        check(PointerPad.buttonAction(NONE, LEFT, true) == PointerPad.ACT_PRESS,
                "left press");
        check(PointerPad.buttonAction(LEFT_HELD, LEFT, false) == PointerPad.ACT_RELEASE,
                "left release");
        check(PointerPad.buttonAction(NONE, RIGHT, true) == PointerPad.ACT_PRESS,
                "right press on its own is a right click, not a chord");
        check(PointerPad.buttonAction(NONE, MIDDLE, true) == PointerPad.ACT_PRESS,
                "middle press");

        // The chord, from either order.
        check(PointerPad.buttonAction(LEFT_HELD, RIGHT, true) == PointerPad.ACT_CHORD,
                "left held, right pressed");
        check(PointerPad.buttonAction(RIGHT_HELD, LEFT, true) == PointerPad.ACT_CHORD,
                "right held, left pressed");
        check(PointerPad.buttonAction(LEFT_HELD | MIDDLE_HELD, RIGHT, true)
                        == PointerPad.ACT_CHORD,
                "a held middle button does not cancel the chord");
        check(PointerPad.buttonAction(MIDDLE_HELD, LEFT, true) == PointerPad.ACT_PRESS,
                "middle plus left is not the chord");

        // A release we never saw pressed. This is not hypothetical: the chord
        // that re-takes the mouse has both buttons down at the moment capture
        // is granted, so both releases arrive here with nothing held.
        check(PointerPad.buttonAction(NONE, LEFT, false) == PointerPad.ACT_NONE,
                "release of an unheld left is ignored");
        check(PointerPad.buttonAction(LEFT_HELD, RIGHT, false) == PointerPad.ACT_NONE,
                "release of an unheld right is ignored");
        check(PointerPad.buttonAction(LEFT_HELD, LEFT, false) == PointerPad.ACT_RELEASE,
                "release of a held left still goes through");

        System.out.println("MouseCheck ok");
    }
}
