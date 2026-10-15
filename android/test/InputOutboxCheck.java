package io.folddeck.spike;

import java.util.Arrays;

/** JVM regression checks for the outbound input scheduler. */
public class InputOutboxCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static byte[] key(int code, boolean down) {
        return new byte[]{1, (byte) (code >> 8), (byte) code, (byte) (down ? 1 : 0)};
    }

    private static byte[] pointer(int x, int y) {
        return new byte[]{5, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y};
    }

    public static void main(String[] args) throws Exception {
        InputOutbox q = new InputOutbox();

        byte[] down = key(29, true);
        byte[] up = key(29, false);
        q.offer(down);
        for (int i = 0; i < 1000; i++) q.offer(pointer(i, 1000 - i));
        q.offer(up);

        check(Arrays.equals(q.take(1), down), "critical key-down stays first");
        check(Arrays.equals(q.take(1), pointer(999, 1)),
                "coalesced motion keeps its position before the following release");
        check(Arrays.equals(q.take(1), up), "critical key-up is never dropped");
        check(q.take(1) == null, "queue drains completely");

        for (int i = 0; i < InputOutbox.MAX_RELIABLE; i++) {
            check(q.offer(key(i & 255, true)), "critical capacity accepts bounded backlog");
        }
        check(!q.offer(key(1, false)), "overflow is reported instead of silently dropping a release");

        q.clear();
        check(q.take(1) == null, "clear removes every pending event");
        System.out.println("InputOutboxCheck ok");
    }
}
