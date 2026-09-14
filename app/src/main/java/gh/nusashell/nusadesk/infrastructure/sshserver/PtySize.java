package gh.nusashell.nusadesk.infrastructure.sshserver;

import java.util.Objects;

/**
 * Immutable PTY dimensions for a guest shell.
 *
 * <p>Pure value object: no Android, no I/O. Validated at construction so an
 * absurd PTY size never reaches the guest. Defaults match the common
 * terminal size used across the host shell contracts.</p>
 */
public final class PtySize {

    /** Common default column count. */
    public static final int DEFAULT_COLS = 80;
    /** Common default row count. */
    public static final int DEFAULT_ROWS = 24;

    private final int cols;
    private final int rows;

    public PtySize(int cols, int rows) {
        if (cols < 1 || cols > 1024) {
            throw new IllegalArgumentException("cols must be between 1 and 1024");
        }
        if (rows < 1 || rows > 1024) {
            throw new IllegalArgumentException("rows must be between 1 and 1024");
        }
        this.cols = cols;
        this.rows = rows;
    }

    /** @return the default {@code 80x24} PTY size. */
    public static PtySize defaults() {
        return new PtySize(DEFAULT_COLS, DEFAULT_ROWS);
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PtySize)) {
            return false;
        }
        PtySize that = (PtySize) o;
        return cols == that.cols && rows == that.rows;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cols, rows);
    }

    @Override
    public String toString() {
        return cols + "x" + rows;
    }
}
