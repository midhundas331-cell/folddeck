package io.folddeck.spike;

import java.io.ByteArrayOutputStream;
import java.net.Socket;

/** A failed old writer must never tear down a replacement connection. */
public class WriterTargetCheck {
    private static final class TrackingSocket extends Socket {
        boolean closed;
        @Override public synchronized void close() {
            closed = true;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        TrackingSocket oldSocket = new TrackingSocket();
        TrackingSocket replacement = new TrackingSocket();
        H264Stream.OutputTarget old = new H264Stream.OutputTarget(
                oldSocket, new ByteArrayOutputStream());
        H264Stream.OutputTarget current = new H264Stream.OutputTarget(
                replacement, new ByteArrayOutputStream());

        H264Stream.closeFailedTarget(old);
        check(oldSocket.closed, "failed writer did not close its owning socket");
        check(!replacement.closed, "stale writer closed replacement socket");
        check(current.socket == replacement, "replacement target changed");
        System.out.println("WriterTargetCheck ok");
    }
}
