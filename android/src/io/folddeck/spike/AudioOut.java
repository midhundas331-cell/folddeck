package io.folddeck.spike;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Plays the laptop's audio: raw 48 kHz s16le stereo carried in SEI NALs.
 * See host/audio.py.
 *
 * Writes never block, because they happen on the video receive thread.
 *
 * Latency is the depth of the track's queue, so the depth is steered rather
 * than left to drift: a stall that drains it is padded back up to TARGET with
 * silence (a cushion, so the next jitter spike does not crackle), and a queue
 * that has crept past HIGH -- clock drift, a burst after a stall -- drops the
 * incoming slice. Before, it only dropped once the 180 ms buffer was full.
 */
final class AudioOut {
    static final byte[] UUID =
            "FOLDDECK-AUDIO-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int RATE = 48000, FRAME = 4;
    static final int TARGET_MS = 40, HIGH_MS = 100;

    private AudioTrack track;
    private long written;   // frames handed to the track

    /** The PCM in a FoldDeck audio SEI, unescaped, or null if this NAL is not one. */
    static byte[] pcmFrom(byte[] nal) {
        if (nal.length < 2 || (nal[0] & 0x1F) != 6) return null;
        // The whole SEI body is emulation-escaped; the UUID has no zeros, so the
        // header can be read raw before unescaping.
        int i = 2;
        if (nal[1] != 5) return null;
        while (i < nal.length && (nal[i] & 0xFF) == 0xFF) i++;
        i++;
        if (i + UUID.length >= nal.length) return null;
        for (int k = 0; k < UUID.length; k++) {
            if (nal[i + k] != UUID[k]) return null;
        }
        return unescape(nal, i + UUID.length, nal.length - 1);   // drop the 0x80 stop bit
    }

    /** Remove H.264 emulation-prevention bytes: 00 00 03 -> 00 00. */
    static byte[] unescape(byte[] in, int from, int to) {
        byte[] out = new byte[Math.max(0, to - from)];
        int n = 0, zeros = 0;
        for (int i = from; i < to; i++) {
            byte b = in[i];
            if (zeros >= 2 && b == 3) { zeros = 0; continue; }
            zeros = b == 0 ? zeros + 1 : 0;
            out[n++] = b;
        }
        return java.util.Arrays.copyOf(out, n - n % FRAME);
    }

    /** Frames queued in the track and not yet played. */
    private long queued() {
        return written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
    }

    /** What to do with an incoming slice: frames of silence to add first, or -1 to drop it. */
    static long steer(long queuedFrames, int sliceFrames) {
        long target = RATE * TARGET_MS / 1000, high = RATE * HIGH_MS / 1000;
        if (queuedFrames + sliceFrames > high) return -1;
        if (queuedFrames <= RATE * 5 / 1000) return Math.max(0, target - sliceFrames - Math.max(0, queuedFrames));
        return 0;
    }

    void play(byte[] pcm) {
        if (pcm.length == 0) return;
        if (track == null) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(min, RATE * FRAME * HIGH_MS * 2 / 1000))
                    .build();
            written = 0;
            track.play();
        }
        long pad = steer(queued(), pcm.length / FRAME);
        if (pad < 0) return;
        if (pad > 0) write(new byte[(int) pad * FRAME]);
        write(pcm);
    }

    private void write(byte[] data) {
        int wrote = track.write(data, 0, data.length, AudioTrack.WRITE_NON_BLOCKING);
        if (wrote > 0) written += wrote / FRAME;
    }

    void release() {
        if (track == null) return;
        try { track.stop(); } catch (Throwable ignored) { }
        track.release();
        track = null;
    }
}
