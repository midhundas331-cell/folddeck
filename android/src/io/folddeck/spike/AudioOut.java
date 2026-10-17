package io.folddeck.spike;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Plays the laptop's audio: raw 48 kHz s16le stereo carried in SEI NALs.
 * See host/audio.py.
 *
 * Writes never block, because they happen on the video receive thread. When the
 * track's buffer is full the excess is dropped, which bounds latency at the
 * buffer size even if the two sound clocks drift apart.
 */
final class AudioOut {
    static final byte[] UUID =
            "FOLDDECK-AUDIO-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int RATE = 48000, FRAME = 4;
    /** Start only once this much is queued, so network jitter does not crackle. */
    private static final int PREBUFFER = RATE * FRAME * 60 / 1000;

    private AudioTrack track;
    private int queued;

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
                    .setBufferSizeInBytes(Math.max(min, PREBUFFER * 3))
                    .build();
            queued = 0;
        }
        int wrote = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
        if (wrote > 0 && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            queued += wrote;
            if (queued >= PREBUFFER) track.play();
        }
    }

    void release() {
        if (track == null) return;
        try { track.stop(); } catch (Throwable ignored) { }
        track.release();
        track = null;
    }
}
