package gh.nusashell.nusadesk.domain.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A validated, bounded guest {@code /etc/resolv.conf} built from Android's
 * active-network DNS servers.
 *
 * <p>Pure Java, no Android, no filesystem, no network: this is the deterministic
 * policy that validates nameserver IP literals, deduplicates them, bounds the
 * count, and formats the {@code nameserver <ip>} lines. The Android adapter
 * ({@code AndroidActiveNetworkDns}) supplies the candidate addresses; the
 * writer ({@code GuestResolvConfWriter}) performs the atomic file write and
 * PRoot bind. This class only decides what is safe to write.</p>
 *
 * <p>Validation rules (enforced here so they are testable without a device):</p>
 * <ul>
 *   <li>Each nameserver must be a literal IPv4 or IPv6 address. Hostnames,
 *       ports, brackets, whitespace, zone/scope IDs ({@code %}), and null
 *       bytes are rejected — a resolver file must contain only literal
 *       nameserver lines so it can never carry an injected directive.</li>
 *   <li>At most {@link #MAX_NAMESERVERS} (3) lines are emitted, matching
 *       glibc's {@code MAXNS}. Extra candidates are dropped.</li>
 *   <li>Duplicates are collapsed so a flapping Android DNS list cannot grow
 *       the file.</li>
 *   <li>If no candidate is a valid literal, {@link #of(List)} returns
 *       {@code null}: the caller must <em>not</em> bind a resolver in that
 *       case, so the guest keeps whatever its rootfs ships (graceful, never
 *       a broken empty resolver that overrides a working one).</li>
 * </ul>
 */
public final class ResolvConf {

    /** Maximum nameserver lines emitted (glibc {@code MAXNS}). */
    public static final int MAX_NAMESERVERS = 3;

    /** Hard cap on a single {@code nameserver <ip>\n} line length. */
    public static final int MAX_LINE_LENGTH = 64;

    private final List<String> nameservers;

    private ResolvConf(List<String> nameservers) {
        this.nameservers = Collections.unmodifiableList(new ArrayList<>(nameservers));
    }

    /**
     * Build a validated resolv.conf from candidate nameserver strings.
     *
     * @param candidates raw IP-literal candidates (may be null, may contain
     *                   invalid/hostnames/zone-scoped entries; they are dropped)
     * @return a non-empty validated resolv.conf, or {@code null} when no
     *         candidate is a usable literal (caller must not bind a resolver)
     */
    public static ResolvConf of(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<String> valid = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String ip = candidate.trim();
            if (ip.isEmpty() || ip.indexOf('\0') >= 0) {
                continue;
            }
            if (!isValidIpLiteral(ip)) {
                continue;
            }
            if (!valid.contains(ip)) {
                valid.add(ip);
            }
            if (valid.size() >= MAX_NAMESERVERS) {
                break;
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        return new ResolvConf(valid);
    }

    /** The validated, deduplicated, bounded nameserver literals. */
    public List<String> nameservers() {
        return nameservers;
    }

    /**
     * Format as resolv.conf text: one {@code nameserver <ip>\n} line per
     * server, US-ASCII. The output is bounded by {@link #MAX_NAMESERVERS} and
     * {@link #MAX_LINE_LENGTH}.
     */
    public String toText() {
        StringBuilder sb = new StringBuilder(nameservers.size() * MAX_LINE_LENGTH);
        for (String ip : nameservers) {
            String line = "nameserver " + ip + "\n";
            if (line.length() > MAX_LINE_LENGTH) {
                // Defensive: an over-long literal should not have passed
                // validation, but never emit a line that could overflow a
                // fixed-size reader.
                continue;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** True when the value is a literal IPv4 or IPv6 address (no hostnames, scopes, or brackets). */
    public static boolean isValidIpLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return false;
        }
        if (value.indexOf('\0') >= 0 || value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
            return false;
        }
        // IPv6 contains ':' and only hex digits + ':'. IPv4 contains only
        // digits and '.'. Anything else (brackets, hostnames, ports) is rejected.
        if (value.indexOf(':') >= 0) {
            return isIpv6(value);
        }
        return isIpv4(value);
    }

    private static boolean isIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int first = -1;
        int second = -1;
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int j = 0; j < octet.length(); j++) {
                char c = octet.charAt(j);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            int v = Integer.parseInt(octet);
            if (v < 0 || v > 255) {
                return false;
            }
            if (i == 0) {
                first = v;
            } else if (i == 1) {
                second = v;
            }
        }
        // Reject IPv4 link-local (169.254.0.0/16): a resolver file cannot
        // carry a link-local address, and Android may report the router's
        // link-local IPv4 as a DNS candidate.
        if (first == 169 && second == 254) {
            return false;
        }
        return true;
    }

    private static boolean isIpv6(String value) {
        if (value.indexOf('%') >= 0) {
            return false; // no zone/scope id in a resolver file
        }
        if (value.charAt(0) == '[' || value.charAt(value.length() - 1) == ']') {
            return false; // no surrounding brackets
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isHex(c) && c != ':') {
                return false;
            }
        }
        if (value.indexOf(":::") >= 0) {
            return false;
        }
        int doubleColon = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            if (value.charAt(i) == ':' && value.charAt(i + 1) == ':') {
                doubleColon++;
                i++;
            }
        }
        if (doubleColon > 1) {
            return false;
        }
        String[] parts = value.split(":", -1);
        int groupCount = 0;
        int firstGroup = -1;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() > 4) {
                return false;
            }
            int parsed = Integer.parseInt(part, 16);
            if (groupCount == 0) {
                firstGroup = parsed;
            }
            groupCount++;
        }
        if (doubleColon == 1) {
            if (groupCount > 7) {
                return false;
            }
        } else if (groupCount != 8) {
            return false;
        }
        // Reject IPv6 link-local (fe80::/10): a resolver file cannot carry a
        // scoped link-local address, and Android reports the router's
        // link-local IPv6 (e.g. fe80::1%wlan0) with the scope stripped, which
        // is unroutable without an interface.
        if (firstGroup >= 0 && (firstGroup & 0xFFC0) == 0xFE80) {
            return false;
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
