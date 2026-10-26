package io.folddeck.spike;

import java.util.ArrayDeque;

/**
 * Outbound input scheduler.
 *
 * Every transition remains in wire order. Consecutive pointer samples coalesce
 * to the newest position, but a key/button/scroll event seals the pointer sample
 * before it so a drag cannot become button-down, button-up, then movement. If the
 * reliable backlog reaches its defensive ceiling, the caller tears down the
 * socket; host disconnect cleanup is safer than losing a release transition.
 */
final class InputOutbox {
    static final int MAX_RELIABLE = 4096;
    private static final int MSG_PTR_ABS16 = 5;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int reliableCount;

    synchronized boolean offer(byte[] message) {
        if (message == null || message.length == 0) return true;
        if (isPointer(message)) {
            byte[] tail = queue.peekLast();
            if (isPointer(tail)) queue.removeLast();
            queue.addLast(message);
            notifyAll();
            return true;
        }
        if (reliableCount >= MAX_RELIABLE) return false;
        queue.addLast(message);
        reliableCount++;
        notifyAll();
        return true;
    }

    synchronized byte[] take(long timeoutMs) throws InterruptedException {
        if (queue.isEmpty() && timeoutMs > 0) wait(timeoutMs);
        byte[] next = queue.pollFirst();
        if (next != null && !isPointer(next)) reliableCount--;
        return next;
    }

    /** Every queued message, in order, as one buffer; null on timeout. */
    synchronized byte[] takeAll(long timeoutMs) throws InterruptedException {
        if (queue.isEmpty() && timeoutMs > 0) wait(timeoutMs);
        if (queue.isEmpty()) return null;
        int size = 0;
        for (byte[] m : queue) size += m.length;
        byte[] out = new byte[size];
        int o = 0;
        for (byte[] m : queue) {
            System.arraycopy(m, 0, out, o, m.length);
            o += m.length;
        }
        queue.clear();
        reliableCount = 0;
        return out;
    }

    synchronized void clear() {
        queue.clear();
        reliableCount = 0;
        notifyAll();
    }

    private static boolean isPointer(byte[] message) {
        return message != null && message.length > 0
                && (message[0] & 0xff) == MSG_PTR_ABS16;
    }
}
