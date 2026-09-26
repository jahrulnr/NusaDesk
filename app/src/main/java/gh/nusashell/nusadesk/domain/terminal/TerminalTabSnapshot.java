package gh.nusashell.nusadesk.domain.terminal;

import java.util.Objects;

/**
 * Immutable snapshot of one terminal tab (ADR-0054).
 *
 * <p>A tab couples tab metadata (id, kind, the command-app fields) with the
 * live {@link TerminalSessionStatus} of its host-owned SSH session, so views
 * render one row per tab from a single object. {@code commandAppId} and
 * {@code command} are set only for {@link TerminalTabKind#COMMAND} tabs —
 * {@code command} is the already-validated {@link TerminalCommand} value the
 * tab's exec channel runs on the guest.</p>
 *
 * <p>{@code displayOrdinal} is a <em>display</em> ordinal for every tab kind,
 * used by presentation to label the tab "Terminal 1", "Terminal 2", and so
 * on. It is not a session identity: it is monotonic within the tab set and
 * never reused while that set lives, but resets when the runtime session
 * replaces every tab.</p>
 */
public final class TerminalTabSnapshot {

    private final String id;
    private final TerminalTabKind kind;
    private final String commandAppId;
    private final String command;
    private final int displayOrdinal;
    private final boolean closable;
    private final TerminalSessionStatus status;

    public TerminalTabSnapshot(
            String id,
            TerminalTabKind kind,
            String commandAppId,
            String command,
            int displayOrdinal,
            boolean closable,
            TerminalSessionStatus status) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.id = id;
        this.kind = kind;
        this.commandAppId = commandAppId;
        this.command = command;
        this.displayOrdinal = displayOrdinal;
        this.closable = closable;
        this.status = status;
    }

    /** Stable identity of the tab within the host service's lifetime. */
    public String getId() {
        return id;
    }

    public TerminalTabKind getKind() {
        return kind;
    }

    /** Owning terminal-command app id; {@code null} for shell tabs. */
    public String getCommandAppId() {
        return commandAppId;
    }

    /** Validated command the exec channel runs; {@code null} for shell tabs. */
    public String getCommand() {
        return command;
    }

    /**
     * Display ordinal within the tab's own kind (shell tabs number 1, 2, …
     * on the sequence the terminal's menu shows), not a session identity.
     */
    public int getDisplayOrdinal() {
        return displayOrdinal;
    }

    /** Whether the user may close this tab; the initial shell is closable too. */
    public boolean isClosable() {
        return closable;
    }

    /** Live status of the tab's host-owned SSH session. */
    public TerminalSessionStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TerminalTabSnapshot)) {
            return false;
        }
        TerminalTabSnapshot that = (TerminalTabSnapshot) o;
        return closable == that.closable
                && displayOrdinal == that.displayOrdinal
                && Objects.equals(id, that.id)
                && kind == that.kind
                && Objects.equals(commandAppId, that.commandAppId)
                && Objects.equals(command, that.command)
                && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, commandAppId, command, displayOrdinal, closable, status);
    }
}
