package io.folddeck.spike;

/** Strict parser for a manually entered host or host:port endpoint. */
final class EndpointText {
    static final class Value {
        final String host;
        final int port;

        Value(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private EndpointText() { }

    static String normalizeHost(String host) {
        if (host == null) return null;
        String normalized = host.trim();
        if (normalized.length() >= 2 && normalized.charAt(0) == '['
                && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    static String format(String host, int port) {
        if (host == null || host.isEmpty()) return "";
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    static Value parse(String source, int defaultPort) {
        String text = source == null ? "" : source.trim();
        if (text.isEmpty()) return null;

        String host;
        int port = defaultPort;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close <= 1) throw new IllegalArgumentException("IPv6 address needs closing ]");
            host = text.substring(1, close).trim();
            String rest = text.substring(close + 1).trim();
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) throw new IllegalArgumentException("Expected :port after ]");
                port = parsePort(rest.substring(1));
            }
        } else {
            int first = text.indexOf(':');
            int last = text.lastIndexOf(':');
            if (first != last) {
                throw new IllegalArgumentException("Wrap IPv6 addresses in [brackets]");
            }
            if (last >= 0) {
                host = text.substring(0, last).trim();
                port = parsePort(text.substring(last + 1));
            } else {
                host = text;
            }
        }

        if (host.isEmpty()) throw new IllegalArgumentException("Host is empty");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1–65535");
        return new Value(host, port);
    }

    private static int parsePort(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Port is empty");
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number");
        }
    }
}
