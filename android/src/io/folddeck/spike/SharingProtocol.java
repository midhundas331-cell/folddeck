package io.folddeck.spike;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Negotiated v2 sharing: revision-bound input availability and demand leases. */
final class SharingProtocol {
    static final int MAX_PACKET = 256 * 1024, MAX_TEXT = 64 * 1024;
    static final int HELLO = 0, STATUS = 1, CONFIG = 2, CLIPBOARD = 3, MIC = 4, CAMERA = 5, READY = 6, DEMAND = 7;
    static final byte[] UUID = "FOLDDECK-SHARE-2".getBytes(StandardCharsets.US_ASCII);

    static byte[] tagged(int revision, byte[] data) {
        byte[] out = new byte[data.length + 4];
        for (int i = 0; i < 4; i++) out[i] = (byte) (revision >>> (24 - i * 8));
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    static int revision(byte[] payload) {
        return java.nio.ByteBuffer.wrap(payload, 1, 4).getInt();
    }

    static byte[] packet(int kind, byte[] data) {
        if (data.length + 1 > MAX_PACKET) throw new IllegalArgumentException("sharing packet too large");
        int n = data.length + 1;
        byte[] out = new byte[n + 5];
        out[0] = 8;
        for (int i = 0; i < 4; i++) out[i + 1] = (byte) (n >>> (24 - i * 8));
        out[5] = (byte) kind;
        System.arraycopy(data, 0, out, 6, data.length);
        return out;
    }

    static byte[] fromNal(byte[] nal) {
        if (nal.length < 20 || (nal[0] & 31) != 6) return null;
        byte[] body = new byte[nal.length - 1];
        int n = 0, zeros = 0;
        for (int i = 1; i < nal.length; i++) {
            int b = nal[i] & 255;
            if (zeros >= 2 && b == 3) { zeros = 0; continue; }
            body[n++] = nal[i];
            zeros = b == 0 ? zeros + 1 : 0;
        }
        if (body[0] != 5) return null;
        int at = 1, size = 0;
        while (at < n && (body[at] & 255) == 255) { size += 255; at++; }
        if (at >= n) return null;
        size += body[at++] & 255;
        if (size < UUID.length + 1 || size > MAX_TEXT + UUID.length + 1 || at + size >= n) return null;
        for (int i = 0; i < UUID.length; i++) if (body[at + i] != UUID[i]) return null;
        return Arrays.copyOfRange(body, at + UUID.length, at + size);
    }
}
