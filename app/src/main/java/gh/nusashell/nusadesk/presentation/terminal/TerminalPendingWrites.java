package gh.nusashell.nusadesk.presentation.terminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded queue of pending terminal writes for one terminal bridge
 * (ADR-0054).
 *
 * <p>It exists because of one ordering gap: a host-owned tab session opens on
 * the service the moment the runtime is up, while the tab's packaged xterm
 * page still has to load, run its shim, and wire the {@code WebMessagePort}
 * back to the host. A freshly opened tab — in particular a command tab that
 * prints something immediately — can produce its first output in that window;
 * without this queue those bytes would be silently dropped by the bridge and
 * the tab would open already missing its first output.</p>
 *
 * <p>The queue is deliberately <em>one-shot</em>: {@code TerminalBridgeView}
 * fills it only until the page reports READY, then drains it in order and
 * never uses it again. It is not scrollback — ADR-0033 keeps terminal output
 * a live stream rather than retained state, so {@link #drain()} empties the
 * queue for good and {@link #clear()} (reset/release) simply discards it. The
 * {@link #MAX_BYTES} cap bounds the damage of a page that never readies: a
 * chunk that would exceed the cap is dropped while the queued chunks stay.</p>
 *
 * <p>Pure Java — no Android imports — so the ordering and the cap are asserted
 * in a plain JVM test. Not thread-safe: the owning bridge calls it on the UI
 * thread only.</p>
 */
public final class TerminalPendingWrites {

    /** Hard cap on queued write payload, in UTF-8 bytes. */
    public static final int MAX_BYTES = 64 * 1024;

    /**
     * One queued write: the decoded text plus which stream it arrived on, so a
     * flush can rebuild the exact bridge call ({@code writeStdout} or
     * {@code writeStderr}) it would have been.
     */
    public static final class Entry {
        private final String text;
        private final boolean stderr;

        private Entry(String text, boolean stderr) {
            this.text = text;
            this.stderr = stderr;
        }

        /** The write payload; never null. */
        public String getText() {
            return text;
        }

        /** True when the bytes came from the session's stderr stream. */
        public boolean isStderr() {
            return stderr;
        }
    }

    private final Deque<Entry> queue = new ArrayDeque<>();
    private int sizeBytes;

    /**
     * Queue one write. Returns {@code false} — dropping the new chunk — when
     * appending it would exceed {@link #MAX_BYTES}; the already queued writes
     * are kept untouched in that case.
     *
     * @param text   decoded terminal output; never null
     * @param stderr whether the chunk arrived on stderr
     */
    public boolean add(String text, boolean stderr) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        int bytes = utf8Length(text);
        if (sizeBytes + bytes > MAX_BYTES) {
            return false;
        }
        queue.addLast(new Entry(text, stderr));
        sizeBytes += bytes;
        return true;
    }

    /** Returns the queued writes in arrival order and empties the queue. */
    public List<Entry> drain() {
        List<Entry> out = new ArrayList<>(queue);
        clear();
        return out;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Total queued payload size in UTF-8 bytes. */
    public int sizeBytes() {
        return sizeBytes;
    }

    /** Discard every queued write. */
    public void clear() {
        queue.clear();
        sizeBytes = 0;
    }

    /** UTF-8 encoded length of a string, counted without allocating the bytes. */
    private static int utf8Length(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                len += 1;
            } else if (c < 0x800) {
                len += 2;
            } else if (Character.isHighSurrogate(c)) {
                len += 4;
                i++; // a surrogate pair encodes as one 4-byte sequence
            } else {
                len += 3;
            }
        }
        return len;
    }
}
