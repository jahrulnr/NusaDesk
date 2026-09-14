package gh.nusashell.nusadesk.presentation.terminal;

/**
 * Immutable, Android-free representation of one terminal bridge message exchanged
 * over a {@code WebMessagePort} channel between the native host and the trusted
 * packaged xterm page.
 *
 * <p>This is a tiny, fixed protocol — not a generic RPC. The native side encodes
 * {@link Type#WRITE}, {@link Type#WRITE_STDERR}, {@link Type#RESIZE},
 * {@link Type#FIT}, {@link Type#FOCUS}, and {@link Type#SCROLL} commands to the page; the page encodes
 * {@link Type#READY}, {@link Type#INPUT} and {@link Type#RESIZE} events back. Only
 * the fields each type uses are meaningful; the others are zero/null.</p>
 *
 * <p>Carrying no secrets and doing no I/O, this object is safe to unit-test without
 * Android. See {@link TerminalMessageCodec} for the on-wire JSON form and the
 * size/type validation enforced at the boundary.</p>
 */
public final class TerminalMessage {
    /** Kind of terminal bridge message. */
    public enum Type {
        /** Page -> host: the injected bridge shim wired the port and is ready. */
        READY,
        /** Page -> host: user stdin data (UTF-8 string in {@link #data}). */
        INPUT,
        /** Page -> host: terminal resized by user/layout ({@link #cols}/{@link #rows}). */
        RESIZE,
        /** Host -> page: write SSH stdout to the screen ({@link #data}). */
        WRITE,
        /** Host -> page: write SSH stderr to the screen ({@link #data}). */
        WRITE_STDERR,
        /** Host -> page: resize the PTY and refit ({@link #cols}/{@link #rows}). */
        SET_SIZE,
        /** Host -> page: refit the terminal to its container. */
        FIT,
        /** Host -> page: scroll the xterm viewport by a bounded pixel delta. */
        SCROLL,
        /** Host -> page: focus the terminal for input. */
        FOCUS
    }

    private final Type type;
    private final String data;
    private final int cols;
    private final int rows;
    private final int scrollDelta;

    private TerminalMessage(Type type, String data, int cols, int rows, int scrollDelta) {
        this.type = type;
        this.data = data;
        this.cols = cols;
        this.rows = rows;
        this.scrollDelta = scrollDelta;
    }

    /** A text-carrying message (READY/INPUT/WRITE/WRITE_STDERR). {@code data} must be non-null. */
    public static TerminalMessage text(Type type, String data) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return new TerminalMessage(type, data, 0, 0, 0);
    }

    /** A size-carrying message (RESIZE/SET_SIZE). */
    public static TerminalMessage size(Type type, int cols, int rows) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return new TerminalMessage(type, null, cols, rows, 0);
    }

    /** A parameterless message (FIT/FOCUS). */
    public static TerminalMessage signal(Type type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return new TerminalMessage(type, null, 0, 0, 0);
    }

    /** A native touch-scroll command, bounded to a safe pixel delta. */
    public static TerminalMessage scroll(int deltaPixels) {
        if (deltaPixels < -2000 || deltaPixels > 2000 || deltaPixels == 0) {
            throw new IllegalArgumentException("scroll delta must be between -2000 and 2000");
        }
        return new TerminalMessage(Type.SCROLL, null, 0, 0, deltaPixels);
    }

    public Type getType() {
        return type;
    }

    /** Non-null for READY/INPUT/WRITE/WRITE_STDERR; null for size/signal messages. */
    public String getData() {
        return data;
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    /** Pixel delta for {@link Type#SCROLL}; zero for every other message. */
    public int getScrollDelta() {
        return scrollDelta;
    }
}
