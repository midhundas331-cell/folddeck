package io.folddeck.spike;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A frame is complete at its AUD, not at the next frame. Run:
 *   javac --release 17 -cp build/classes:$J -d build/test test/AnnexBCheck.java
 *   java -cp build/test:build/classes:$J io.folddeck.spike.AnnexBCheck
 */
public class AnnexBCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static final byte[] AUD = {0, 0, 0, 1, 9, (byte) 0xF0};

    private static byte[] nal(int... b) {
        byte[] out = new byte[b.length + 4];
        out[3] = 1;
        for (int i = 0; i < b.length; i++) out[4 + i] = (byte) b[i];
        return out;
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(nal(0x67, 1, 2));          // SPS
        s.write(nal(0x68, 3));             // PPS
        s.write(nal(0x65, 0x88, 0, 0, 0)); // IDR + trailing_zero_8bits, stripped
        s.write(AUD);
        int firstUnitEnd = s.size();
        s.write(nal(0x41, 0x9A, 7));       // P
        s.write(AUD);
        byte[] stream = s.toByteArray();

        for (int chunk : new int[]{1, 2, 3, 7, 4096}) {
            List<String> events = new ArrayList<>();
            AnnexB split = new AnnexB(new AnnexB.Sink() {
                @Override public void nal(byte[] n) { events.add("nal" + (n[0] & 0x1F) + ":" + n.length); }
                @Override public void unitEnd() { events.add("end"); }
            });
            for (int i = 0; i < stream.length; i += chunk) {
                int n = Math.min(chunk, stream.length - i);
                byte[] part = new byte[n];
                System.arraycopy(stream, i, part, 0, n);
                split.feed(part, n);
                if (i + n == firstUnitEnd || (i < firstUnitEnd && i + n > firstUnitEnd)) {
                    check(events.contains("end"),
                            "IDR unit must finish at its AUD without waiting (chunk " + chunk + ")");
                }
            }
            String got = String.join(" ", events);
            check(got.equals("nal7:3 nal8:2 nal5:2 end nal1:3 end"),
                    "chunk " + chunk + " gave " + got);
        }
        System.out.println("AnnexBCheck ok");
    }
}
