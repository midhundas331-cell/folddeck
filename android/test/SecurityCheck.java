package io.folddeck.spike;

import javax.net.ssl.SSLHandshakeException;

/** JVM checks for security-sensitive exception and credential scoping helpers. */
public class SecurityCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        String fp = "ab".repeat(32);
        check(Secure.tokenKey(fp).equals("client_token_" + fp), "token is scoped to host certificate");
        check(!Secure.tokenKey(fp).equals(Secure.tokenKey("cd".repeat(32))),
                "different hosts cannot receive the same preference slot");
        check(Secure.pinKey("[fd7a:115c:a1e0::1]", 5000)
                        .equals("pin_fd7a:115c:a1e0::1:5000"),
                "current IPv6 pin key uses normalized host");
        check(Secure.legacyPinKey("fd7a:115c:a1e0::1", 5000)
                        .equals("pin_[fd7a:115c:a1e0::1]:5000"),
                "legacy bracketed IPv6 pin remains discoverable during upgrade");

        Secure.PinMismatchException mismatch = new Secure.PinMismatchException("changed");
        SSLHandshakeException wrapped = new SSLHandshakeException("handshake failed");
        wrapped.initCause(new RuntimeException(mismatch));
        check(Secure.isPinMismatch(mismatch), "direct mismatch found");
        check(Secure.isPinMismatch(wrapped), "wrapped mismatch found through cause chain");
        check(!Secure.isPinMismatch(new RuntimeException("network")), "ordinary failure is not mismatch");
        check(Secure.requiredProtocols().length == 1
                        && "TLSv1.3".equals(Secure.requiredProtocols()[0]),
                "client must refuse TLS downgrade");

        System.out.println("SecurityCheck ok");
    }
}
