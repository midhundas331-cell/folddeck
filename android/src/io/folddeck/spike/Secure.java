package io.folddeck.spike;

import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS transport with certificate pinning, and this device's identity token.
 *
 * The host uses a self-signed certificate, so the usual CA chain means nothing.
 * Instead we pin: the first certificate we ever see for a given host:port is
 * remembered, and every later connection must present exactly that one. That is
 * trust-on-first-use -- the first connection is the vulnerable one, everything
 * after it is protected against interception.
 *
 * A mismatch is treated as fatal rather than as a prompt. If the host's identity
 * has genuinely changed the user can clear it deliberately; silently offering
 * "accept anyway" would throw away the entire guarantee at the exact moment it
 * matters.
 */
final class Secure {

    private static final String TAG = "FoldDeck/Secure";
    private static final String KEY_TOKEN = "client_token";
    private static final String PIN_PREFIX = "pin_";
    private static final int TOKEN_BYTES = 32;
    private static final byte HELLO_VERSION = 1;

    /** Thrown when the host presents a different certificate than the pinned one. */
    static final class PinMismatchException extends CertificateException {
        PinMismatchException(String msg) {
            super(msg);
        }
    }

    private Secure() { }

    // ------------------------------------------------------------------ //
    /** This device's stable identity, generated once. The host pins it in turn. */
    static byte[] clientToken(SharedPreferences prefs) {
        String stored = prefs.getString(KEY_TOKEN, null);
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP);
        }
        byte[] token = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(token);
        // commit(), not apply(): apply() returns before the write reaches disk,
        // so a process death between generating the token and flushing it leaves
        // the host paired to an identity this device can no longer prove. That
        // is unrecoverable without unpairing on the laptop, so pay the few
        // milliseconds and write it synchronously.
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(token, Base64.NO_WRAP)).commit();
        return token;
    }

    /** The opening message: version byte then the token. */
    static byte[] hello(SharedPreferences prefs) {
        byte[] token = clientToken(prefs);
        byte[] msg = new byte[1 + token.length];
        msg[0] = HELLO_VERSION;
        System.arraycopy(token, 0, msg, 1, token.length);
        return msg;
    }

    private static String pinKey(String host, int port) {
        return PIN_PREFIX + host + ":" + port;
    }

    static String pinnedFingerprint(SharedPreferences prefs, String host, int port) {
        return prefs.getString(pinKey(host, port), null);
    }

    /** Forget the pinned certificate, so the next connection re-pins. */
    static void clearPin(SharedPreferences prefs, String host, int port) {
        prefs.edit().remove(pinKey(host, port)).apply();
    }

    // ------------------------------------------------------------------ //
    /**
     * Connect over TLS, pinning the host certificate, and send the hello.
     *
     * @return a connected socket whose streams carry the same protocol as before
     */
    static SSLSocket connect(SharedPreferences prefs, String host, int port, int timeoutMs)
            throws Exception {
        TrustManager[] tms = {new PinningTrustManager(prefs, host, port)};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tms, new SecureRandom());
        SSLSocketFactory factory = ctx.getSocketFactory();

        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.setReceiveBufferSize(1 << 20);
        raw.connect(new InetSocketAddress(host, port), timeoutMs);

        SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true);
        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        socket.setUseClientMode(true);
        socket.startHandshake();   // pinning happens inside the trust manager

        socket.getOutputStream().write(hello(prefs));
        socket.getOutputStream().flush();
        Log.i(TAG, "TLS up: " + socket.getSession().getProtocol()
                + " " + socket.getSession().getCipherSuite());
        return socket;
    }

    // ------------------------------------------------------------------ //
    private static final class PinningTrustManager implements X509TrustManager {
        private final SharedPreferences prefs;
        private final String host;
        private final int port;

        PinningTrustManager(SharedPreferences prefs, String host, int port) {
            this.prefs = prefs;
            this.host = host;
            this.port = port;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("empty certificate chain");
            }
            String fingerprint = sha256(chain[0].getEncoded());
            String pinned = prefs.getString(pinKey(host, port), null);

            if (pinned == null) {
                // First contact: adopt this certificate as the host's identity.
                // commit() for the same reason as the token — a pin that is used
                // but never persisted silently re-pins on the next launch,
                // which quietly defeats the point of pinning.
                prefs.edit().putString(pinKey(host, port), fingerprint).commit();
                Log.i(TAG, "pinned " + host + ":" + port + " -> " + fingerprint);
                return;
            }
            if (!constantTimeEquals(pinned, fingerprint)) {
                throw new PinMismatchException(
                        "host certificate changed for " + host + ":" + port
                                + "\nexpected " + pinned + "\ngot      " + fingerprint);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // We never act as a TLS server.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static String sha256(byte[] data) throws CertificateException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new CertificateException("cannot hash certificate", e);
        }
    }

    /** Length-independent comparison, so a mismatch leaks no timing information. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(), y = b.getBytes();
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
