package gh.nusashell.nusadesk.domain.compose;

import java.util.Locale;

/**
 * The restart policy of one Compose service, restricted to the subset the
 * udocker adapter can implement honestly.
 *
 * <p>udocker has no daemon and no built-in restart handling, so a policy here
 * is a contract the adapter must enforce through its own supervision
 * (systemctl3 unit + persisted manual-stop state, per ADR-0024 and the parent
 * plan's restart-semantics findings). Unsupported Compose values such as
 * {@code on-failure} are rejected rather than approximated: an accepted spec
 * must never promise behaviour the adapter cannot deliver. In particular,
 * {@link #UNLESS_STOPPED} differs from {@link #ALWAYS} only through a
 * persisted "user stopped this" flag — without that flag the two would be the
 * same lie Docker users already know.</p>
 */
public enum ComposeRestartPolicy {
    /** Never restart: process exit is final (Compose {@code no} or omitted). */
    NO("no"),
    /** Restart on every exit while the session runs (Compose {@code always}). */
    ALWAYS("always"),
    /** Restart on exit unless the user explicitly stopped the service. */
    UNLESS_STOPPED("unless-stopped");

    private final String composeValue;

    ComposeRestartPolicy(String composeValue) {
        this.composeValue = composeValue;
    }

    /** The canonical Compose spelling of this policy. */
    public String toComposeValue() {
        return composeValue;
    }

    /**
     * Parses a Compose {@code restart} value.
     *
     * <p>A missing ({@code null}) policy maps to {@link #NO}; the text is
     * trimmed and matched case-insensitively. Blank text and any value outside
     * the supported subset — including {@code on-failure} — are rejected.</p>
     *
     * <p>Note for the YAML parser: under YAML 1.1 an unquoted {@code no} is the
     * boolean {@code false}. The parser must map that scalar back to
     * {@code "no"} (i.e. {@link #NO}) rather than passing {@code "false"}.</p>
     *
     * @throws IllegalArgumentException on a blank or unsupported value
     */
    public static ComposeRestartPolicy fromComposeValue(String value) {
        if (value == null) {
            return NO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("restart policy must not be blank");
        }
        switch (normalized) {
            case "no":
                return NO;
            case "always":
                return ALWAYS;
            case "unless-stopped":
                return UNLESS_STOPPED;
            default:
                throw new IllegalArgumentException("unsupported restart policy: " + value);
        }
    }
}
