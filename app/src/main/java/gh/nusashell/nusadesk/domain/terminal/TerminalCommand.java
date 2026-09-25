package gh.nusashell.nusadesk.domain.terminal;

import java.util.Objects;

/**
 * One user-authored command carried by a launcher terminal-command app.
 *
 * <p>This is the security boundary of the feature: the command is data, and the
 * Android host <b>never executes it</b>. When the app's tile is opened, the
 * already-pinned loopback SSH session to the app's own guest Linux runs it
 * there inside a guest PTY; quoting, expansion, and exit behavior are entirely
 * the guest shell's semantics. Validation here therefore does not try to make
 * the command "safe" — it only guarantees the stored record stays one single
 * printable line so it can never smuggle a second command or a NUL byte past
 * the storage layer. Quotes, pipes, {@code $}, and unicode are deliberately
 * allowed.</p>
 *
 * <p>Immutable value object.</p>
 */
public final class TerminalCommand {
    /** Maximum accepted command length after trimming. */
    public static final int MAX_LENGTH = 512;

    private final String value;

    private TerminalCommand(String value) {
        this.value = value;
    }

    /**
     * Validates and normalizes a raw command string.
     *
     * @param raw candidate command; surrounding whitespace is ignored
     * @return the validated command
     * @throws IllegalArgumentException when the command is missing, longer than
     *                                  {@link #MAX_LENGTH} after trimming, or
     *                                  contains a control character (below
     *                                  U+0020, or U+007F)
     */
    public static TerminalCommand of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "command must be at most " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(
                        "command must be a single line without control characters");
            }
        }
        return new TerminalCommand(trimmed);
    }

    /** The validated command, trimmed. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TerminalCommand)) {
            return false;
        }
        return value.equals(((TerminalCommand) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
