package gh.nusashell.nusadesk.domain.compose;

import java.util.Locale;
import java.util.Objects;

/**
 * One published port of a Compose service: a container port reachable on a
 * host-side port, optionally restricted to a numeric host address.
 *
 * <p>{@code protocol} is stored lower-case ({@code tcp} or {@code udp}); a null
 * protocol means the Compose default {@code tcp}. {@code hostAddress} is a
 * numeric IPv4 or IPv6 literal only — never a hostname, because validating a
 * name would require DNS and a name's meaning can change after validation. The
 * address is stored verbatim (no canonicalisation), so {@code ::1} and
 * {@code 0:0:0:0:0:0:0:1} compare as different spellings.</p>
 */
public final class ComposePortMapping {
    /** The only accepted protocols, in stored (lower-case) form. */
    public static final String PROTOCOL_TCP = "tcp";
    public static final String PROTOCOL_UDP = "udp";

    private final int hostPort;
    private final int containerPort;
    private final String protocol;
    private final String hostAddress;

    /**
     * @param hostPort      published port on the host side, 1..65535
     * @param containerPort port inside the container, 1..65535
     * @param protocol      {@code tcp} or {@code udp} (any case), or null for
     *                      the Compose default {@code tcp}
     * @param hostAddress   numeric IPv4 or IPv6 literal to bind on, or
     *                      null/blank for all local addresses
     * @throws IllegalArgumentException when a port is out of range, the
     *                                  protocol is unsupported, or the host
     *                                  address is not a numeric literal
     */
    public ComposePortMapping(
            int hostPort, int containerPort, String protocol, String hostAddress) {
        if (hostPort < 1 || hostPort > 65535) {
            throw new IllegalArgumentException(
                    "hostPort must be between 1 and 65535: " + hostPort);
        }
        if (containerPort < 1 || containerPort > 65535) {
            throw new IllegalArgumentException(
                    "containerPort must be between 1 and 65535: " + containerPort);
        }
        this.hostPort = hostPort;
        this.containerPort = containerPort;
        this.protocol = normalizeProtocol(protocol);
        this.hostAddress = normalizeHostAddress(hostAddress);
    }

    /** Published port on the host side. */
    public int getHostPort() {
        return hostPort;
    }

    /** Port inside the container. */
    public int getContainerPort() {
        return containerPort;
    }

    /** Lower-case {@code tcp} or {@code udp}; never null. */
    public String getProtocol() {
        return protocol;
    }

    /** The numeric bind address, or null for all local addresses. */
    public String getHostAddress() {
        return hostAddress;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComposePortMapping)) {
            return false;
        }
        ComposePortMapping that = (ComposePortMapping) other;
        return hostPort == that.hostPort
                && containerPort == that.containerPort
                && protocol.equals(that.protocol)
                && Objects.equals(hostAddress, that.hostAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostPort, containerPort, protocol, hostAddress);
    }

    /**
     * Renders the Compose short form: {@code [hostAddress:]hostPort:
     * containerPort/protocol}, with an IPv6 address in brackets.
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder();
        if (hostAddress != null) {
            if (hostAddress.indexOf(':') >= 0) {
                rendered.append('[').append(hostAddress).append(']');
            } else {
                rendered.append(hostAddress);
            }
            rendered.append(':');
        }
        return rendered.append(hostPort).append(':').append(containerPort)
                .append('/').append(protocol).toString();
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null) {
            return PROTOCOL_TCP;
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT);
        if (!PROTOCOL_TCP.equals(normalized) && !PROTOCOL_UDP.equals(normalized)) {
            throw new IllegalArgumentException(
                    "protocol must be tcp or udp: " + protocol);
        }
        return normalized;
    }

    private static String normalizeHostAddress(String hostAddress) {
        if (hostAddress == null || hostAddress.trim().isEmpty()) {
            return null;
        }
        String text = hostAddress.trim();
        if (isIpv4Literal(text) || isIpv6Literal(text)) {
            return text;
        }
        throw new IllegalArgumentException(
                "hostAddress must be a numeric IPv4 or IPv6 literal, not a name: " + text);
    }

    /** Dotted-quad IPv4 literal; leading zeros are rejected as ambiguous. */
    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !isAsciiDigits(part)) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 literal: 1-4 hex-digit groups separated by {@code :}, at most one
     * {@code ::} compression that must stand for at least one group, and an
     * optional embedded IPv4 tail that counts as two groups. Zone identifiers
     * ({@code %eth0}) are not accepted.
     */
    private static boolean isIpv6Literal(String value) {
        if (value.indexOf(':') < 0) {
            return false;
        }
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 2) >= 0) {
            return false;
        }
        String head = doubleColon >= 0 ? value.substring(0, doubleColon) : value;
        String tail = doubleColon >= 0 ? value.substring(doubleColon + 2) : null;
        int groups = ipv6GroupCount(head, tail == null);
        if (groups < 0) {
            return false;
        }
        if (tail != null) {
            int tailGroups = ipv6GroupCount(tail, true);
            if (tailGroups < 0) {
                return false;
            }
            groups += tailGroups;
            return groups < 8;
        }
        return groups == 8;
    }

    /**
     * @return the width of {@code part} in 16-bit groups, or -1 when invalid.
     *         An embedded IPv4 tail counts as two groups and is allowed only
     *         as the last group when {@code allowIpv4Tail} is set.
     */
    private static int ipv6GroupCount(String part, boolean allowIpv4Tail) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] groups = part.split(":", -1);
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) {
                return -1;
            }
            if (i == groups.length - 1 && group.indexOf('.') >= 0) {
                if (!allowIpv4Tail || !isIpv4Literal(group)) {
                    return -1;
                }
                count += 2;
            } else {
                if (group.length() > 4 || !isHexDigits(group)) {
                    return -1;
                }
                count += 1;
            }
        }
        return count;
    }

    private static boolean isAsciiDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
