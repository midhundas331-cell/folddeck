package io.folddeck.spike;

/** JVM checks for strict endpoint parsing. */
public class EndpointTextCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void invalid(String text) {
        try {
            EndpointText.parse(text, 5000);
            throw new AssertionError("accepted invalid endpoint: " + text);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        check(EndpointText.parse("", 5000) == null, "blank means unused route");
        EndpointText.Value plain = EndpointText.parse("laptop.local", 5000);
        check(plain.host.equals("laptop.local") && plain.port == 5000, "default port");
        EndpointText.Value ipv4 = EndpointText.parse("192.168.1.20:5443", 5000);
        check(ipv4.host.equals("192.168.1.20") && ipv4.port == 5443, "IPv4 with port");
        EndpointText.Value ipv6 = EndpointText.parse("[fd7a:115c:a1e0::1]:5001", 5000);
        check(ipv6.host.equals("fd7a:115c:a1e0::1") && ipv6.port == 5001, "bracketed IPv6");
        check(EndpointText.format(ipv6.host, ipv6.port).equals("[fd7a:115c:a1e0::1]:5001"),
                "IPv6 formats back to an editable endpoint");
        check(EndpointText.format(ipv4.host, ipv4.port).equals("192.168.1.20:5443"),
                "IPv4 format stays plain");
        check(EndpointText.normalizeHost("[fd7a:115c:a1e0::1]").equals("fd7a:115c:a1e0::1"),
                "legacy bracketed IPv6 host is normalized on load");
        check(EndpointText.format(EndpointText.normalizeHost("[fd7a:115c:a1e0::1]"), 5000)
                        .equals("[fd7a:115c:a1e0::1]:5000"),
                "legacy IPv6 does not acquire double brackets");

        invalid(":5000");
        invalid("host:");
        invalid("host:nope");
        invalid("host:0");
        invalid("host:65536");
        invalid("fd7a:115c:a1e0::1:5000");

        System.out.println("EndpointTextCheck ok");
    }
}
