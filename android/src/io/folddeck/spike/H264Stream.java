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
    private final MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();

    /** Reliable key/button transitions plus a coalesced latest pointer sample. */
    private final InputOutbox outbox = new InputOutbox();
    private volatile OutputTarget outputTarget;
    private volatile Socket activeSocket;
    private volatile Thread writerThread;

    // Stream accumulator. 1 MiB is comfortably more than one 1080p IDR; 8 MiB
    // is a hard protocol limit so a peer cannot grow the process without bound.
    static final int MAX_STREAM_BUFFER = 8 << 20;
    static final int MAX_ACCESS_UNIT = 8 << 20;
    static final int MAX_ACCESS_UNIT_NALS = 4096;
    private byte[] buf = new byte[1 << 20];
    private int len = 0;

    // Current access unit under construction, as a list of complete NAL units
    // (each already prefixed with a 4-byte start code).
    private final List<byte[]> auNals = new ArrayList<>();
    private int auBytes = 0;
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
        closeQuietly(activeSocket);
        Thread writer = writerThread;
        if (writer != null) writer.interrupt();
        outbox.clear();
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

    /**
     * MSG_PTR_ABS16: absolute position on the host screen, each axis 0..65535.
     *
     * Not the older permille MSG_PTR_ABS (type 2). A thousand steps is 1.9px per
     * step across a 1920px desktop -- invisible under a finger, visibly steppy
     * under a mouse, and at a low pointer speed a small movement rounds to no
     * movement at all. The host still parses type 2, so an older APK keeps
     * working; this one needs a host new enough to know type 5.
     */
    void sendPointer(int x, int y) {
        offer(new byte[]{5,
                (byte) ((x >> 8) & 0xFF), (byte) (x & 0xFF),
                (byte) ((y >> 8) & 0xFF), (byte) (y & 0xFF)});
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
        // Pointer samples coalesce, while key/button transitions remain ordered.
        // If the defensive reliable-event ceiling is ever reached, close the
        // connection: the host releases every held input on disconnect, which is
        // safer than silently losing a KEY_UP while pretending to stay connected.
        if (!outbox.offer(msg)) {
            Log.e(TAG, "input backlog overflow; resetting connection");
            closeQuietly(activeSocket);
        }
    }

    private void writerLoop() {
        while (running) {
            OutputTarget target = null;
            try {
                byte[] msg = outbox.take(200);
                target = outputTarget;
                if (msg == null || target == null) continue;
                target.output.write(msg);
                // Flush per event. Batching would add a scheduling quantum of
                // latency to every keystroke, which you feel while typing.
                target.output.flush();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "key write failed", t);
                closeFailedTarget(target);
            }
        }
    }

    static final class OutputTarget {
        final Socket socket;
        final java.io.OutputStream output;

        OutputTarget(Socket socket, java.io.OutputStream output) {
            this.socket = socket;
            this.output = output;
        }
    }

    static void closeFailedTarget(OutputTarget target) {
        if (target != null) closeQuietly(target.socket);
    }

    @Override
    public void run() {
        // Video receive competes with the UI thread and the decoder's own
        // threads; without this it lands in the throttled background cgroup and
        // picks up tens of milliseconds of scheduling jitter.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        Thread writer = new Thread(this::writerLoop, "folddeck-keys");
        writer.setDaemon(true);
        writerThread = writer;
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
                activeSocket = socket;
                opened = true;
                consecutiveFailures = 0;
                outputTarget = new OutputTarget(socket, socket.getOutputStream());
                report("streaming");
                pump(socket.getInputStream());
            } catch (Throwable t) {
                // TrustManager failures are commonly wrapped by SSLHandshakeException;
                // inspect the full cause chain rather than silently retrying forever.
                if (Secure.isPinMismatch(t)) {
                    Log.e(TAG, "certificate pin mismatch", t);
                    report("SECURITY: host identity changed - not connecting");
                    if (problemListener != null) problemListener.onPinMismatch(t.getMessage());
                    running = false;
                    continue;
                }
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
                OutputTarget target = outputTarget;
                if (target != null && target.socket == socket) outputTarget = null;
                outbox.clear();
                closeQuietly(socket);
                if (activeSocket == socket) activeSocket = null;
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
        int needed = Math.addExact(len, n);
        if (needed > buf.length) {
            byte[] bigger = new byte[grownCapacity(buf.length, needed)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(data, 0, buf, len, n);
        len += n;
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

    static int nextAccessUnitBytes(int current, int nalLength, int nalCount) {
        if (current < 0 || nalLength < 1 || nalCount < 0
                || nalCount >= MAX_ACCESS_UNIT_NALS) {
            throw new IllegalArgumentException("invalid H.264 access unit");
        }
        long next = (long) current + 4L + nalLength;
        if (next > MAX_ACCESS_UNIT) {
            throw new IllegalArgumentException("H.264 access unit exceeds "
                    + MAX_ACCESS_UNIT + " bytes");
        }
        return (int) next;
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

        auBytes = nextAccessUnitBytes(auBytes, nal.length, auNals.size());
        auNals.add(nal);
        auHasVcl |= isVcl;

        if (codec == null && sps != null && pps != null) {
            configure();
        }
    }

    private void flushAccessUnit() {
        if (codec == null || auNals.isEmpty()) {
            auNals.clear();
            auBytes = 0;
            auHasVcl = false;
            return;
        }

        byte[] au = new byte[auBytes];
        int o = 0;
        boolean keyframe = false;
        for (byte[] nal : auNals) {
            au[o] = 0; au[o + 1] = 0; au[o + 2] = 0; au[o + 3] = 1;
            System.arraycopy(nal, 0, au, o + 4, nal.length);
            o += 4 + nal.length;
            if ((nal[0] & 0x1F) == 5) keyframe = true;
        }
        auNals.clear();
        auBytes = 0;
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
            // Release completed output before asking for more input. Otherwise a
            // full output side can make dequeueInputBuffer() fail, and returning
            // there would prevent the output side from ever being drained.
            int idx = dequeueAfterDrain(this::drainOutputs,
                    () -> codec.dequeueInputBuffer(0));
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
            drainOutputs();
        } catch (Throwable t) {
            Log.e(TAG, "queue failed", t);
            releaseCodec();
        }
    }

    static int dequeueAfterDrain(Runnable drain, java.util.function.IntSupplier dequeue) {
        drain.run();
        return dequeue.getAsInt();
    }

    private void drainOutputs() {
        while (true) {
            int out = codec.dequeueOutputBuffer(outputInfo, 0);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat fmt = codec.getOutputFormat();
                if (sizeListener != null) {
                    sizeListener.onVideoSize(fmt.getInteger(MediaFormat.KEY_WIDTH),
                            fmt.getInteger(MediaFormat.KEY_HEIGHT));
                }
                continue;
            }
            if (out < 0) return;
            recordDecodeTime(System.nanoTime() / 1000 - outputInfo.presentationTimeUs);
            // Render immediately. Timestamped release is right for playback but
            // adds avoidable scheduling latency to an interactive desktop.
            codec.releaseOutputBuffer(out, true);
            decoded++;
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
        auBytes = 0;
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
