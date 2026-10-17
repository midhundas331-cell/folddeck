package io.folddeck.spike;

/** The phone decodes exactly the PCM that host/audio.py audio_nal() framed. */
public class AudioCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // audio_nal(b"\0" * 8 + b"\1\2\3\4\0\0\0\3"), start code removed.
        byte[] nal = {6, 5, 32, 70, 79, 76, 68, 68, 69, 67, 75, 45, 65, 85, 68, 73, 79, 45,
                49, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, 0, 3, 1, 2, 3, 4, 0, 0, 3, 0, 3, -128};
        byte[] pcm = AudioOut.pcmFrom(nal);
        check(java.util.Arrays.equals(pcm, new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                1, 2, 3, 4, 0, 0, 0, 3}), "emulation bytes removed, PCM intact");

        byte[] theme = {6, 5, 20, 70, 79, 76, 68, 68, 69, 67, 75, 45, 84, 72, 69, 77, 69,
                45, 49, 97, 61, 98, 10, -128};
        check(AudioOut.pcmFrom(theme) == null, "theme SEI is not audio");
        check(AudioOut.pcmFrom(new byte[]{5, 1, 2}) == null, "slices are not audio");
        System.out.println("AudioCheck ok");
    }
}
