package gh.nusashell.nusadesk.application.terminal;

/**
 * Expected failure at the terminal-tabs boundary (ADR-0054).
 *
 * <p>The reason is machine-readable so the caller can react without parsing
 * English text — for example {@link Reason#TAB_LIMIT} maps to the "all tabs
 * used" toast while {@link Reason#NOT_RUNNING} just re-renders the honest
 * "runtime not running" state.</p>
 */
public final class TerminalTabException extends Exception {
    /** What the caller has to correct or accept. */
    public enum Reason {
        /** The command text failed {@code TerminalCommand} validation. */
        INVALID_COMMAND,
        /** The tab cap ({@code MAX_TABS}) is already reached. */
        TAB_LIMIT,
        /** No runtime session is running, so no tab can attach. */
        NOT_RUNNING,
        /** The named tab id does not exist. */
        UNKNOWN_TAB
    }

    private final Reason reason;

    public TerminalTabException(Reason reason, String message) {
        super(message);
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
