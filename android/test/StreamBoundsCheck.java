package io.folddeck.spike;

/** JVM checks for bounded stream and access-unit growth. */
public class StreamBoundsCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void rejected(int current, int needed) {
        try {
            H264Stream.grownCapacity(current, needed);
            throw new AssertionError("accepted oversized stream buffer");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void rejectedAu(int current, int nalLength, int nalCount) {
        try {
            H264Stream.nextAccessUnitBytes(current, nalLength, nalCount);
            throw new AssertionError("accepted oversized access unit");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        check(H264Stream.grownCapacity(1024, 1024) == 1024, "no growth when capacity is enough");
        check(H264Stream.grownCapacity(1024, 1025) == 2048, "capacity doubles");
        check(H264Stream.grownCapacity(1024, 3000) == 3000, "large read grows directly");
        rejected(H264Stream.MAX_STREAM_BUFFER, H264Stream.MAX_STREAM_BUFFER + 1);
        rejected(1024, Integer.MAX_VALUE);

        check(H264Stream.nextAccessUnitBytes(0, 100, 0) == 104,
                "Annex-B start code counts toward access-unit memory");
        rejectedAu(H264Stream.MAX_ACCESS_UNIT - 2, 1, 0);
        rejectedAu(0, 1, H264Stream.MAX_ACCESS_UNIT_NALS);
        System.out.println("StreamBoundsCheck ok");
    }
}
