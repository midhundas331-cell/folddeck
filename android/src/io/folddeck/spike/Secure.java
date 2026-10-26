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
    /** Legacy pre-0.4 token, migrated only to a certificate trusted before this connection. */
    private static final String KEY_TOKEN = "client_token";
    private static final String TOKEN_PREFIX = "client_token_";
    private static final String PIN_PREFIX = "pin_";
    private static final int TOKEN_BYTES = 32;
    private static final byte HELLO_VERSION = 1;

    /** Thrown when the host presents a different certificate than the pinned one. */
    static final class PinMismatchException extends CertificateException {
        private static final long serialVersionUID = 1L;
        PinMismatchException(String msg) {
            super(msg);
        }
    }

    private Secure() { }

    // ------------------------------------------------------------------ //
    /** One stable identity per host certificate, so an unrelated host cannot steal it. */
    static byte[] clientToken(SharedPreferences prefs, String fingerprint,
                              boolean mayMigrateLegacy) {
        String key = tokenKey(fingerprint);
        String stored = prefs.getString(key, null);
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP);
        }
        if (mayMigrateLegacy) {
            String legacy = prefs.getString(KEY_TOKEN, null);
            if (legacy != null) {
                prefs.edit().putString(key, legacy).commit();
                return Base64.decode(legacy, Base64.NO_WRAP);
            }
        }
        byte[] token = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(token);
        // commit(), not apply(): apply() returns before the write reaches disk,
        // so a process death between generating the token and flushing it leaves
        // the host paired to an identity this device can no longer prove. That
        // is unrecoverable without unpairing on the laptop, so pay the few
        // milliseconds and write it synchronously.
        prefs.edit().putString(key, Base64.encodeToString(token, Base64.NO_WRAP)).commit();
        return token;
    }

    static String tokenKey(String fingerprint) {
        return TOKEN_PREFIX + fingerprint;
    }

    /** The opening message: version byte then the token. */
    static byte[] hello(SharedPreferences prefs, String fingerprint, boolean mayMigrateLegacy) {
        byte[] token = clientToken(prefs, fingerprint, mayMigrateLegacy);
        byte[] msg = new byte[1 + token.length];
        msg[0] = HELLO_VERSION;
        System.arraycopy(token, 0, msg, 1, token.length);
        return msg;
    }

    static String pinKey(String host, int port) {
        return PIN_PREFIX + EndpointText.normalizeHost(host) + ":" + port;
    }

    static String legacyPinKey(String host, int port) {
        String normalized = EndpointText.normalizeHost(host);
        return normalized != null && normalized.indexOf(':') >= 0
                ? PIN_PREFIX + "[" + normalized + "]:" + port
                : pinKey(normalized, port);
    }

    static String pinnedFingerprint(SharedPreferences prefs, String host, int port) {
        String currentKey = pinKey(host, port);
        String pinned = prefs.getString(currentKey, null);
        if (pinned != null) return pinned;

        String legacyKey = legacyPinKey(host, port);
        if (legacyKey.equals(currentKey)) return null;
        pinned = prefs.getString(legacyKey, null);
        if (pinned != null) {
            // Preserve identity across the 0.4 IPv6 normalization migration.
            // Even if the synchronous rewrite fails, use the legacy pin for this
            // connection rather than silently treating the host as first contact.
            prefs.edit().putString(currentKey, pinned).remove(legacyKey).commit();
        }
        return pinned;
    }

    /** Forget the pinned certificate, so the next connection re-pins. */
    static void clearPin(SharedPreferences prefs, String host, int port) {
        prefs.edit().remove(pinKey(host, port)).remove(legacyPinKey(host, port)).apply();
    }

    // ------------------------------------------------------------------ //
    /**
     * Connect over TLS, pinning the host certificate, and send the hello.
     *
     * @return a connected socket whose streams carry the same protocol as before
     */
    static SSLSocket connect(SharedPreferences prefs, String host, int port, int timeoutMs)
            throws Exception {
        java.util.Set<String> trustedBefore = pinnedFingerprints(prefs);
        TrustManager[] tms = {new PinningTrustManager(prefs, host, port)};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tms, new SecureRandom());
        SSLSocketFactory factory = ctx.getSocketFactory();

        Socket raw = new Socket();
        SSLSocket socket = null;
        boolean connected = false;
        try {
            raw.setTcpNoDelay(true);
            // DSCP CS6: Wi-Fi WMM voice category, the shortest airtime wait.
            // Upstream here is only input, so it costs nobody bandwidth.
            raw.setTrafficClass(0xC0);
            raw.setReceiveBufferSize(1 << 20);
            raw.connect(new InetSocketAddress(host, port), timeoutMs);

            socket = (SSLSocket) factory.createSocket(raw, host, port, true);
            socket.setEnabledProtocols(requiredProtocols());
            socket.setUseClientMode(true);
            // Socket.connect() only bounds the TCP phase. A peer can accept TCP
            // and then never complete TLS, so keep a read deadline until both the
            // handshake and FoldDeck hello are finished.
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();   // pinning happens inside the trust manager

            X509Certificate peer = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            String fingerprint = sha256(peer.getEncoded());
            socket.getOutputStream().write(hello(prefs, fingerprint,
                    trustedBefore.contains(fingerprint)));
            socket.getOutputStream().flush();
            socket.setSoTimeout(0);    // video streaming is intentionally long-lived
            Log.i(TAG, "TLS up: " + socket.getSession().getProtocol()
                    + " " + socket.getSession().getCipherSuite());
            connected = true;
            return socket;
        } finally {
            if (!connected) {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) { }
                }
                try { raw.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Whether host:port presents the certificate with this fingerprint. Only a
     * TLS handshake: it pins nothing and sends no hello, so the host neither
     * pairs nor hands it the stream. For checking that the home address is the
     * same laptop before moving a live stream onto it.
     */
    static boolean presents(String host, int port, String fingerprint, int timeoutMs) {
        if (fingerprint == null) return false;
        final String[] seen = {null};
        TrustManager[] capture = {new X509TrustManager() {
            @Override public void checkServerTrusted(X509Certificate[] chain, String type)
                    throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no cert");
                seen[0] = sha256(chain[0].getEncoded());
                // Refuse anything else, so the handshake never completes to a stranger.
                if (!constantTimeEquals(fingerprint, seen[0])) {
                    throw new CertificateException("not this host");
                }
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String type)
                    throws CertificateException {
                throw new CertificateException("client mode only");
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, capture, new SecureRandom());
            try (SSLSocket tls = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(raw, host, port, false)) {
                tls.setEnabledProtocols(requiredProtocols());
                tls.setUseClientMode(true);
                tls.startHandshake();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static String[] requiredProtocols() {
        return new String[]{"TLSv1.3"};
    }

    private static java.util.Set<String> pinnedFingerprints(SharedPreferences prefs) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(PIN_PREFIX) && e.getValue() instanceof String) {
                out.add((String) e.getValue());
            }
        }
        return out;
    }

    static boolean isPinMismatch(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PinMismatchException) return true;
            if (t.getCause() == t) break;
        }
        return false;
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
            String pinned = pinnedFingerprint(prefs, host, port);

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
