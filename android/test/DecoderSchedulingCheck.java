package io.folddeck.spike;

/** Regression check: decoder output must drain before requesting more input. */
public class DecoderSchedulingCheck {
    public static void main(String[] args) {
        StringBuilder order = new StringBuilder();
        int idx = H264Stream.dequeueAfterDrain(
                () -> order.append("drain>"),
                () -> { order.append("input"); return 7; });
        if (idx != 7) throw new AssertionError("wrong input index");
        if (!"drain>input".contentEquals(order)) {
            throw new AssertionError("decoder order was " + order);
        }
        System.out.println("DecoderSchedulingCheck ok");
    }
}
