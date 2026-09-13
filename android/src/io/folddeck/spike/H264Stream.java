package io.folddeck.spike;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-0 spike: read raw Annex-B H.264 off a TCP socket, feed MediaCodec,
 * render to a Surface as fast as the hardware allows.
 *
 * No crypto, no UDP, no reassembly, no adaptive bitrate. The only question this
 * is built to answer is whether the latency floor on this hardware makes the
 * whole idea viable.
 */
final class H264Stream implements Runnable {

    interface Stats {
        void update(long bytes, int framesDecoded, int framesDropped,
                    double decodeMsP50, double decodeMsP99, String state);
    }

    /** Reports the decoder's actual output size, for letterboxing and hit-testing. */
    interface SizeListener {
        void onVideoSize(int width, int height);
    }

    /**
     * Connection problems worth interrupting the user for.
     *
     * The retry loop below will happily dial a wrong address once a second
     * forever, which looks identical to a host that is merely down. These are
     * the two cases where retrying will never work on its own.
     */
    interface ProblemListener {
        /** Fired on every failure to open the socket, with the running count. */
        void onConnectFailing(int consecutiveFailures, String reason);

        /** The host answered with a certificate other than the pinned one. */
        void onPinMismatch(String reason);
    }

    /** The laptop's Omarchy palette, as "key=#rrggbb" lines. See stream.py theme_nal(). */
    interface ThemeListener {
        void onTheme(String palette);
    }

    private SizeListener sizeListener;
    private ProblemListener problemListener;
    private ThemeListener themeListener;

    void setThemeListener(ThemeListener l) {
        this.themeListener = l;
    }

    private static final byte[] THEME_UUID =
            "FOLDDECK-THEME-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The palette carried by a FoldDeck theme SEI, or null if this NAL is not one. */
    static String themeFrom(byte[] nal) {
        if (nal.length < 2 || (nal[0] & 0x1F) != 6 || nal[1] != 5) return null;
        int i = 2, size = 0;
        while (i < nal.length && (nal[i] & 0xFF) == 0xFF) { size += 255; i++; }
        if (i >= nal.length) return null;
        size += nal[i++] & 0xFF;
        if (size < THEME_UUID.length || i + size > nal.length) return null;
        for (int k = 0; k < THEME_UUID.length; k++) {
            if (nal[i + k] != THEME_UUID[k]) return null;
        }
        return new String(nal, i + THEME_UUID.length, size - THEME_UUID.length,
                java.nio.charset.StandardCharsets.US_ASCII);
    }

    void setSizeListener(SizeListener l) {
        this.sizeListener = l;
    }

    void setProblemListener(ProblemListener l) {
        this.problemListener = l;
    }

    /**
     * Turn a connect failure into something a person can act on.
     *
     * The raw text is a mouthful — "failed to connect to /192.168.1.20 (port
     * 5000) from /192.168.1.20 (port 41234) after 5000ms: isConnected failed:
     * ECONNREFUSED (Connection refused)" — and the errno buried in the middle is
     * the only part that says what to do differently. The original is kept in
     * brackets so nothing is lost.
     */
    static String describe(Throwable t) {
        String raw = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        String plain;
        if (raw.contains("ECONNREFUSED")) {
            plain = "That machine answered but nothing is listening on the port.";
        } else if (raw.contains("ETIMEDOUT")) {
            plain = "No answer. Check the address, and that both devices are on "
                    + "the same network.";
        } else if (raw.contains("EHOSTUNREACH") || raw.contains("ENETUNREACH")) {
            plain = "That address cannot be reached from here.";
        } else if (raw.contains("EACCES") || raw.contains("EPERM")) {
            plain = "Android blocked the connection.";
        } else if (raw.contains("UnknownHost")
                || t instanceof java.net.UnknownHostException) {
            plain = "That name does not resolve. An IP address always works.";
        } else {
            return raw;
        }
        return plain + "  (" + raw + ")";
    }

    private static final String TAG = "FoldDeck/Spike";

    private final String host;
    private final int port;
    private final Surface surface;
    private final Stats stats;
    private final android.content.SharedPreferences prefs;

    private volatile boolean running = true;
    private MediaCodec codec;

    /** Outbound key events. Bounded so a dead socket can't grow it without limit. */
    private final java.util.concurrent.BlockingQueue<byte[]> outbox =
            new java.util.concurrent.ArrayBlockingQueue<>(256);
    private volatile java.io.OutputStream out;

    // Stream accumulator. 1 MiB is comfortably more than one 1080p IDR.
    private byte[] buf = new byte[1 << 20];
    private int len = 0;

    // Current access unit under construction, as a list of complete NAL units
    // (each already prefixed with a 4-byte start code).
    private final List<byte[]> auNals = new ArrayList<>();
    private boolean auHasVcl = false;

    private byte[] sps, pps;

    private long totalBytes = 0;
    private int decoded = 0, dropped = 0;
    private final long[] decodeSamples = new long[256];
    private int decodeCount = 0;

    H264Stream(String host, int port, Surface surface, Stats stats,
               android.content.SharedPreferences prefs) {
        this.host = host;
        this.port = port;
        this.surface = surface;
        this.stats = stats;
        this.prefs = prefs;
    }

    void stop() {
        running = false;
    }

    /**
     * Queue a key event for the host. Safe to call from the UI thread.
     *
     * TCP is full-duplex, so this rides the same socket the video comes down.
     * Input goes on the reliable channel deliberately: a dropped KEY_UP leaves a
     * modifier stuck down on the laptop, which is far worse than a millisecond
     * of extra latency.
     */
    void sendKey(int code, boolean down) {
        // MSG_KEY, u16 code big-endian, u8 down
        offer(new byte[]{1, (byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF),
                (byte) (down ? 1 : 0)});
    }

    /** MSG_PTR_ABS: absolute position in permille of the host screen. */
    void sendPointer(int xPermille, int yPermille) {
        offer(new byte[]{2,
                (byte) ((xPermille >> 8) & 0xFF), (byte) (xPermille & 0xFF),
                (byte) ((yPermille >> 8) & 0xFF), (byte) (yPermille & 0xFF)});
    }

    /** MSG_BUTTON: 1 = left, 2 = right, 3 = middle. */
    void sendButton(int button, boolean down) {
        offer(new byte[]{3, (byte) button, (byte) (down ? 1 : 0)});
    }

    /** MSG_SCROLL: wheel notches, vertical and horizontal. */
    void sendScroll(int dv, int dh) {
        offer(new byte[]{4,
                (byte) ((dv >> 8) & 0xFF), (byte) (dv & 0xFF),
                (byte) ((dh >> 8) & 0xFF), (byte) (dh & 0xFF)});
    }

    private void offer(byte[] msg) {
        // Drop rather than block the UI thread if the socket has stalled. Losing
        // a motion sample is invisible; a frozen UI is not.
        outbox.offer(msg);
    }

    private void writerLoop() {
        while (running) {
            try {
                byte[] msg = outbox.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                java.io.OutputStream o = out;
                if (msg == null || o == null) continue;
                o.write(msg);
                // Flush per event. Batching would add a scheduling quantum of
                // latency to every keystroke, which you feel while typing.
                o.flush();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "key write failed", t);
            }
        }
    }

    @Override
    public void run() {
        // Video receive competes with the UI thread and the decoder's own
        // threads; without this it lands in the throttled background cgroup and
        // picks up tens of milliseconds of scheduling jitter.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        Thread writer = new Thread(this::writerLoop, "folddeck-keys");
        writer.setDaemon(true);
        writer.start();

        int consecutiveFailures = 0;

        while (running) {
            Socket socket = null;
            boolean opened = false;
            try {
                report("connecting to " + host + ":" + port);
                // TLS with certificate pinning; Nagle off, because it would batch
                // the tail of each frame into the next write and add a full frame
                // of latency for no bandwidth gain.
                socket = Secure.connect(prefs, host, port, 5000);
                opened = true;
                consecutiveFailures = 0;
                out = socket.getOutputStream();
                report("streaming");
                pump(socket.getInputStream());
            } catch (Secure.PinMismatchException t) {
                // Do not reconnect: a changed host certificate is exactly what an
                // interception attempt looks like. Stop and make the user decide.
                Log.e(TAG, "certificate pin mismatch", t);
                report("SECURITY: host identity changed - not connecting");
                if (problemListener != null) problemListener.onPinMismatch(t.getMessage());
                running = false;
            } catch (Throwable t) {
                Log.w(TAG, "stream error", t);
                report("error: " + t.getMessage());
                // Only a failure to *open* the socket says anything about the
                // address. A stream that ran and then dropped is the host
                // restarting or the phone changing network, which the retry loop
                // below handles on its own and should stay silent about.
                if (!opened) {
                    consecutiveFailures++;
                    if (problemListener != null) {
                        problemListener.onConnectFailing(consecutiveFailures, describe(t));
                    }
                }
            } finally {
                out = null;
                outbox.clear();
                closeQuietly(socket);
                releaseCodec();
                reset();
            }
            if (running) sleep(1000);   // retry loop, so the host can restart freely
        }
    }

    private void pump(InputStream in) throws Exception {
        byte[] chunk = new byte[65536];
        long lastReport = System.nanoTime();

        while (running) {
            int n = in.read(chunk);
            if (n < 0) throw new Exception("host closed the connection");
            totalBytes += n;
            append(chunk, n);
            drainNals();

            long now = System.nanoTime();
            if (now - lastReport > 250_000_000L) {
                publish("streaming");
                lastReport = now;
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Annex-B parsing
    // ------------------------------------------------------------------ //
    private void append(byte[] data, int n) {
        if (len + n > buf.length) {
            byte[] bigger = new byte[Math.max(buf.length * 2, len + n)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(data, 0, buf, len, n);
        len += n;
    }

    /** Index of the next 00 00 01 at or after {@code from}, or -1. */
    private int findStartCode(int from) {
        for (int i = Math.max(from, 0); i + 2 < len; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) return i;
        }
        return -1;
    }

    /**
     * Emit every complete NAL currently buffered.
     *
     * A NAL is only complete once the *next* start code has arrived, so the tail
     * of the buffer is always left behind for the next read.
     */
    private void drainNals() {
        int first = findStartCode(0);
        if (first < 0) return;

        int cursor = first;
        while (true) {
            int payloadStart = cursor + 3;
            int next = findStartCode(payloadStart);
            if (next < 0) break;

            // A 4-byte start code is 00 00 00 01; the extra zero belongs to the
            // start code, not to the NAL that precedes it.
            int payloadEnd = next;
            while (payloadEnd > payloadStart && buf[payloadEnd - 1] == 0) payloadEnd--;

            if (payloadEnd > payloadStart) {
                byte[] nal = new byte[payloadEnd - payloadStart];
                System.arraycopy(buf, payloadStart, nal, 0, nal.length);
                onNal(nal);
            }
            cursor = next;
        }

        // Compact: keep from the last (incomplete) start code onward.
        System.arraycopy(buf, cursor, buf, 0, len - cursor);
        len -= cursor;
    }

    private void onNal(byte[] nal) {
        int type = nal[0] & 0x1F;
        if (type == 6) {
            String palette = themeFrom(nal);
            if (palette != null) {
                if (themeListener != null) themeListener.onTheme(palette);
                return;   // ours, not the decoder's
            }
        }
        boolean isVcl = (type == 1 || type == 5);

        // first_mb_in_slice is ue(v); the value 0 encodes as a leading 1 bit.
        // A VCL NAL that starts a new picture while we already hold one means
        // the previous access unit is complete.
        boolean startsNewPicture = isVcl && nal.length > 1 && (nal[1] & 0x80) != 0;
        if (startsNewPicture && auHasVcl) {
            flushAccessUnit();
        }

        if (type == 7) sps = nal;
        if (type == 8) pps = nal;

        auNals.add(nal);
        auHasVcl |= isVcl;

        if (codec == null && sps != null && pps != null) {
            configure();
        }
    }

    private void flushAccessUnit() {
        if (codec == null || auNals.isEmpty()) {
            auNals.clear();
            auHasVcl = false;
            return;
        }

        int size = 0;
        for (byte[] nal : auNals) size += 4 + nal.length;
        byte[] au = new byte[size];
        int o = 0;
        boolean keyframe = false;
        for (byte[] nal : auNals) {
            au[o] = 0; au[o + 1] = 0; au[o + 2] = 0; au[o + 3] = 1;
            System.arraycopy(nal, 0, au, o + 4, nal.length);
            o += 4 + nal.length;
            if ((nal[0] & 0x1F) == 5) keyframe = true;
        }
        auNals.clear();
        auHasVcl = false;

        queue(au, keyframe);
    }

    // ------------------------------------------------------------------ //
    // MediaCodec
    // ------------------------------------------------------------------ //
    private void configure() {
        try {
            // Dimensions come from the SPS once the decoder parses it; the values
            // here only size the initial buffers.
            MediaFormat fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);

            ByteBuffer csd = ByteBuffer.allocate(8 + sps.length + pps.length);
            csd.put(new byte[]{0, 0, 0, 1}).put(sps);
            csd.put(new byte[]{0, 0, 0, 1}).put(pps);
            csd.flip();
            fmt.setByteBuffer("csd-0", csd);

            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);                        // realtime
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
            // KEY_LOW_LATENCY is honoured inconsistently; the Fold 7 is Snapdragon,
            // so the qti vendor key is the one that actually lands.
            fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(fmt, surface, null, 0);
            codec.start();
            Log.i(TAG, "decoder configured: " + codec.getName());
        } catch (Throwable t) {
            Log.e(TAG, "configure failed", t);
            releaseCodec();
        }
    }

    private void queue(byte[] au, boolean keyframe) {
        try {
            // Zero timeout: if no input buffer is free the decoder is behind, and
            // blocking here would build a latency debt that never drains. Drop
            // instead — a remote desktop wants the newest frame, not every frame.
            int idx = codec.dequeueInputBuffer(0);
            if (idx < 0) {
                dropped++;
                return;
            }
            ByteBuffer in = codec.getInputBuffer(idx);
            in.clear();
            in.put(au);
            // Carry a real clock in the PTS so the render callback can measure
            // how long the decoder actually took.
            long ptsUs = System.nanoTime() / 1000;
            codec.queueInputBuffer(idx, 0, au.length, ptsUs,
                    keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The authoritative stream size, from the decoder's own SPS
                    // parse. Beats hardcoding, which silently misaligns every
                    // touch if the host is run at a different resolution.
                    MediaFormat fmt = codec.getOutputFormat();
                    if (sizeListener != null) {
                        sizeListener.onVideoSize(fmt.getInteger(MediaFormat.KEY_WIDTH),
                                fmt.getInteger(MediaFormat.KEY_HEIGHT));
                    }
                    continue;
                }
                if (out < 0) break;
                recordDecodeTime(System.nanoTime() / 1000 - info.presentationTimeUs);
                // Render immediately, with no timestamp. Passing one asks
                // SurfaceFlinger to schedule the frame at a future vsync — right
                // for a media player, wrong for a remote desktop.
                codec.releaseOutputBuffer(out, true);
                decoded++;
            }
        } catch (Throwable t) {
            Log.e(TAG, "queue failed", t);
            releaseCodec();
        }
    }

    private void recordDecodeTime(long us) {
        decodeSamples[decodeCount % decodeSamples.length] = us;
        decodeCount++;
    }

    // ------------------------------------------------------------------ //
    private void publish(String state) {
        int n = Math.min(decodeCount, decodeSamples.length);
        long[] copy = new long[n];
        System.arraycopy(decodeSamples, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        double p50 = n > 0 ? copy[n / 2] / 1000.0 : 0;
        double p99 = n > 0 ? copy[(int) (n * 0.99)] / 1000.0 : 0;
        stats.update(totalBytes, decoded, dropped, p50, p99, state);
    }

    private void report(String state) {
        stats.update(totalBytes, decoded, dropped, 0, 0, state);
    }

    private void reset() {
        len = 0;
        auNals.clear();
        auHasVcl = false;
        sps = pps = null;
    }

    private void releaseCodec() {
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) { }
            try { codec.release(); } catch (Throwable ignored) { }
            codec = null;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
