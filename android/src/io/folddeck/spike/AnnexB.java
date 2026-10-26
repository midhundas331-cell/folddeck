package io.folddeck.spike;

/**
 * Splits an Annex-B byte stream into NAL units.
 *
 * A NAL only ends where the next start code begins, so on its own a splitter
 * must hold every NAL until more bytes arrive. The host ends each unit it sends
 * (a frame, the palette, a slice of audio) with an access unit delimiter, and
 * an AUD is a fixed two bytes: seeing one means everything before it is
 * complete, now. Before the host did this, each frame waited for the next one
 * and then one more -- two frames of latency.
 *
 * Pure Java so AnnexBCheck runs on a plain JVM.
 */
final class AnnexB {
    interface Sink {
        void nal(byte[] nal);

        /** An AUD arrived: the access unit in progress is complete. */
        void unitEnd();
    }

    // 1 MiB is comfortably more than one 1080p IDR; 8 MiB is a hard protocol
    // limit so a peer cannot grow the process without bound.
    static final int MAX_STREAM_BUFFER = 8 << 20;

    private final Sink sink;
    private byte[] buf = new byte[1 << 20];
    private int len;

    AnnexB(Sink sink) {
        this.sink = sink;
    }

    void feed(byte[] data, int n) {
        int needed = Math.addExact(len, n);
        if (needed > buf.length) {
            byte[] bigger = new byte[grownCapacity(buf.length, needed)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(data, 0, buf, len, n);
        len += n;
        drain();
    }

    void reset() {
        len = 0;
    }

    static int grownCapacity(int current, int needed) {
        if (needed < 0 || needed > MAX_STREAM_BUFFER) {
            throw new IllegalArgumentException("H.264 stream unit exceeds "
                    + MAX_STREAM_BUFFER + " bytes");
        }
        if (needed <= current) return current;
        long doubled = (long) current * 2L;
        return (int) Math.min(MAX_STREAM_BUFFER, Math.max(doubled, needed));
    }

    /** Index of the next 00 00 01 at or after {@code from}, or -1. */
    private int findStartCode(int from) {
        for (int i = Math.max(from, 0); i + 2 < len; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) return i;
        }
        return -1;
    }

    private void drain() {
        int cursor = findStartCode(0);
        if (cursor < 0) return;

        while (true) {
            int payloadStart = cursor + 3;
            if (payloadStart < len && (buf[payloadStart] & 0x1F) == 9) {
                if (payloadStart + 2 > len) break;   // the AUD's one payload byte
                sink.unitEnd();
                int next = findStartCode(payloadStart + 2);
                if (next < 0) {
                    cursor = payloadStart + 2;
                    break;
                }
                cursor = next;
                continue;
            }

            int next = findStartCode(payloadStart);
            if (next < 0) break;

            // A 4-byte start code is 00 00 00 01; the extra zero belongs to the
            // start code, not to the NAL that precedes it.
            int payloadEnd = next;
            while (payloadEnd > payloadStart && buf[payloadEnd - 1] == 0) payloadEnd--;
            if (payloadEnd > payloadStart) {
                byte[] nal = new byte[payloadEnd - payloadStart];
                System.arraycopy(buf, payloadStart, nal, 0, nal.length);
                sink.nal(nal);
            }
            cursor = next;
        }

        // Keep from the last incomplete NAL onward.
        System.arraycopy(buf, cursor, buf, 0, len - cursor);
        len -= cursor;
    }
}
