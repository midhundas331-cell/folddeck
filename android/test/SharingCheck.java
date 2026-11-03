package io.folddeck.spike;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class SharingCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        byte[] text = "clipboard 🌍\u0000\u0000\u0001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wire = SharingProtocol.packet(3, text);
        check(wire[0] == 8 && wire[4] == text.length + 1 && wire[5] == 3, "bounded envelope framing");
        ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
        rbsp.write(5); rbsp.write(SharingProtocol.UUID.length + text.length + 1);
        rbsp.write(SharingProtocol.UUID); rbsp.write(3); rbsp.write(text); rbsp.write(128);
        ByteArrayOutputStream nal = new ByteArrayOutputStream(); nal.write(6);
        int zeros = 0;
        for (byte b : rbsp.toByteArray()) {
            if (zeros >= 2 && (b & 255) <= 3) { nal.write(3); zeros = 0; }
            nal.write(b); zeros = b == 0 ? zeros + 1 : 0;
        }
        byte[] received = SharingProtocol.fromNal(nal.toByteArray());
        check(received[0] == 3 && Arrays.equals(text, Arrays.copyOfRange(received, 1, received.length)), "UTF-8 and binary SEI round trip");
        check(SharingProtocol.fromNal(Arrays.copyOf(nal.toByteArray(), 12)) == null, "truncated SEI ignored");
        InputOutbox queue = new InputOutbox();
        for (int i = 0; i < 100; i++) queue.offer(SharingProtocol.packet(5, new byte[10000]));
        queue.offer(new byte[]{3, 1, 0});
        check(queue.takeAll(0).length == 10009, "camera backlog stays at one frame plus release");
        queue.offer(SharingProtocol.packet(4, new byte[1920]));
        queue.offer(SharingProtocol.packet(5, new byte[10000]));
        queue.offer(SharingProtocol.packet(2, new byte[]{0}));
        check(queue.takeAll(0).length == 7, "stop drops pending capture data");
        byte[] samples = new byte[1920]; // 20 ms, 48 kHz mono s16le
        for (int i = 1; i <= 5; i++) {
            Arrays.fill(samples, (byte) i);
            queue.offer(SharingProtocol.packet(4, SharingProtocol.tagged(12, samples)));
        }
        byte[] burst = queue.takeAll(0);
        check(burst.length == 9610, "a 100 ms writer stall preserves all microphone PCM");
        for (int i = 0; i < 9600; i++) check(burst[10 + i] == i / 1920 + 1, "PCM remains contiguous");
        for (int i = 1; i <= 6; i++) {
            Arrays.fill(samples, (byte) i);
            queue.offer(SharingProtocol.packet(4, SharingProtocol.tagged(12, samples)));
        }
        burst = queue.takeAll(0);
        check(burst.length == 9610, "microphone latency is bounded at 100 ms");
        for (int i = 0; i < 9600; i++) check(burst[10 + i] == i / 1920 + 2, "overflow retains newest contiguous PCM");
        queue.offer(SharingProtocol.packet(4, SharingProtocol.tagged(12, samples)));
        byte[] fresh = SharingProtocol.packet(4, SharingProtocol.tagged(13, samples));
        queue.offer(fresh);
        check(Arrays.equals(queue.takeAll(0), fresh), "microphone revisions never mix");
        CaptureGate gate = new CaptureGate();
        gate.configure(7);
        int rev = gate.revision;
        gate.ready(rev, 7);
        check(gate.active(100) == 0, "permission and availability do not start sensors");
        gate.demand(rev, 6, 100);
        check(gate.active(101) == 6, "real consumer enables capture");
        check(gate.active(3100) == 0, "lost heartbeat expires capture");
        gate.configure(1);
        gate.ready(rev, 7); gate.demand(rev, 6, 4000);
        check(gate.ready == 0 && gate.active(4001) == 0, "stale ready and demand cannot undo disable");
        gate.configure(3);
        gate.ready(gate.revision, 7); gate.demand(gate.revision, 6, 5000);
        check(gate.active(5001) == 2, "host cannot activate a disabled camera");
        gate.demand(gate.revision, 0, 5100);
        check(gate.active(5101) == 0, "consumer stop releases sensors immediately");
        gate.configure(0);
        gate.ready(gate.revision, 7); gate.demand(gate.revision, 6, 6000);
        check(gate.active(6001) == 0, "disabled inputs cannot capture");
        // Availability is switch + permission only: focus and foreground no longer zero it.
        check(PhoneSharing.allowedMask(7, true, true) == 7, "all enabled and granted");
        check(PhoneSharing.allowedMask(7, false, true) == 5, "no mic permission, no mic");
        check(PhoneSharing.allowedMask(3, true, true) == 3, "camera switch off");
        check(PhoneSharing.serviceTypes(1) == 0, "clipboard alone needs no foreground service");
        check(PhoneSharing.serviceTypes(3) == android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "mic type");
        check(PhoneSharing.serviceTypes(7) == (android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA), "mic + camera types");
        // No in-app live badge, and no single tap that switches the inputs off.
        String activity = java.nio.file.Files.readString(java.nio.file.Path.of("android/src/io/folddeck/spike/MainActivity.java"));
        String sharing = java.nio.file.Files.readString(java.nio.file.Path.of("android/src/io/folddeck/spike/PhoneSharing.java"));
        check(!activity.contains("sharingBadge") && !activity.contains(" live\""), "live badge removed");
        check(!sharing.contains("stopInputs") && !activity.contains("stopInputs"), "no one-tap disable");
        check(!sharing.contains("foreground && focused"), "capture not gated on focus");
        for (int h : new int[]{240, 480, 900}) {
            InputDeckLayout.Boxes base = InputDeckLayout.computeBoxes(1000, h, 8, true, true, true, false, false, 1, 1);
            for (int requested : new int[]{100, 200, 400, 500}) {
                InputDeckLayout.Boxes sized = InputDeckLayout.resizeHeight(base, h, 8, requested, true, true, true, false);
                check(sized.trackpad.h == Math.min(requested, h) && sized.trackpad.bottom() <= h, "independent height stays bounded");
                check(sized.keyboard.w == base.keyboard.w && sized.keyboard.h == h, "pad height does not shrink adjacent keyboard");
            }
        }
        System.out.println("SharingCheck ok");
    }
}
