package gh.nusashell.nusadesk.domain.terminal;

/**
 * What one terminal tab's host-owned session runs (ADR-0054).
 *
 * <p>The kind decides the SSH channel the session opens on the fixed loopback
 * endpoint: a {@link #SHELL} tab is an interactive login shell, a
 * {@link #COMMAND} tab is an exec channel (with a PTY) running one
 * user-authored guest command. The kind is metadata only — the command itself
 * is validated by {@link TerminalCommand} and executed by the guest
 * {@code sshd}, never by the Android host.</p>
 */
public enum TerminalTabKind {
    /** Interactive shell tab: the initial session shell or one opened with New. */
    SHELL,
    /** Command tab opened from a launcher terminal-command app. */
    COMMAND
}
