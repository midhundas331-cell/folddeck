package io.folddeck.spike;

import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

/** JVM regression for bounded TLS handshakes and failed-connect socket cleanup. */
public class HandshakeTimeoutCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        // Exclude one-time provider/class initialization from the network timeout
        // assertion; cold JVM startup is not peer-controlled handshake time.
        SSLContext.getInstance("TLS").init(null, null, null);
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, argv) -> {
                    if (method.getName().equals("getAll")) return Collections.emptyMap();
                    throw new AssertionError("unexpected preference call: " + method.getName());
                });

        AtomicBoolean peerClosed = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(700);
                    byte[] scratch = new byte[4096];
                    check(socket.getInputStream().read(scratch) > 0, "client sent no TLS hello");
                    try {
                        while (socket.getInputStream().read(scratch) >= 0) {
                            // Drain fragmented ClientHello bytes until the client closes.
                        }
                        peerClosed.set(true);
                    } catch (SocketTimeoutException ignored) {
                        peerClosed.set(false);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "stalling-tls-peer");
            peer.start();

            long start = System.nanoTime();
            try {
                Secure.connect(prefs, "127.0.0.1", server.getLocalPort(), 150);
                throw new AssertionError("stalled TLS peer unexpectedly connected");
            } catch (Exception expected) {
                // A bounded handshake must fail.
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            peer.join(1500);

            check(elapsedMs < 500, "TLS handshake ignored timeout: " + elapsedMs + "ms");
            check(peerClosed.get(), "failed TLS connection did not close its socket");
        }
        System.out.println("HandshakeTimeoutCheck ok");
    }
}
