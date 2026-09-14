package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Reads process stdout line-by-line with explicit, bounded limits so a
 * misbehaving or verbose guest cannot exhaust host memory or hang the
 * readiness handshake.
 *
 * <p>The reader enforces:</p>
 * <ul>
 *   <li>a per-line byte cap ({@link Limits#getMaxLineBytes()}), so a runaway
 *       line without a newline is rejected as {@link StopReason#LINE_TOO_LONG}
 *       instead of growing unbounded;</li>
 *   <li>a maximum number of lines ({@link Limits#getMaxLines()});</li>
 *   <li>a maximum total byte budget ({@link Limits#getMaxTotalBytes()}, {@code 0}
 *       means unlimited);</li>
 *   <li>a deadline expressed in epoch milliseconds supplied by the injected
 *       {@link LongSupplier} clock, so the handshake fails fast when the guest
 *       never emits readiness ({@code 0} means no deadline).</li>
 * </ul>
 *
 * <p>Each complete line is delivered to {@link LineHandler#onLine(String)}; the
 * handler returns {@code false} to stop reading (for example when a valid
 * readiness frame has been found). The reader is pure Java with no Android
 * dependency, so it is unit-testable with a {@link java.io.ByteArrayInputStream}.</p>
 */
public final class BoundedOutputReader {

    /** Immutable bounds for a single read. */
    public static final class Limits {
        private final long deadlineMillis;
        private final int maxLines;
        private final int maxLineBytes;
        private final int maxTotalBytes;

        /**
         * @param deadlineMillis  epoch-millis budget; {@code 0} disables the deadline
         * @param maxLines        maximum number of lines to deliver; must be positive
         * @param maxLineBytes    maximum bytes per line before {@link StopReason#LINE_TOO_LONG}; must be positive
         * @param maxTotalBytes   overall byte budget; {@code 0} means unlimited
         */
        public Limits(long deadlineMillis, int maxLines, int maxLineBytes, int maxTotalBytes) {
            if (deadlineMillis < 0) {
                throw new IllegalArgumentException("deadlineMillis must not be negative");
            }
            if (maxLines <= 0) {
                throw new IllegalArgumentException("maxLines must be positive");
            }
            if (maxLineBytes <= 0) {
                throw new IllegalArgumentException("maxLineBytes must be positive");
            }
            if (maxTotalBytes < 0) {
                throw new IllegalArgumentException("maxTotalBytes must not be negative");
            }
            this.deadlineMillis = deadlineMillis;
            this.maxLines = maxLines;
            this.maxLineBytes = maxLineBytes;
            this.maxTotalBytes = maxTotalBytes;
        }

        public long getDeadlineMillis() {
            return deadlineMillis;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public int getMaxLineBytes() {
            return maxLineBytes;
        }

        public int getMaxTotalBytes() {
            return maxTotalBytes;
        }
    }

    /** Why a read stopped. */
    public enum StopReason {
        /** The handler returned {@code false}; reading is complete. */
        COMPLETED,
        /** End of stream reached before the handler stopped. */
        EOF,
        /** A single line exceeded the per-line byte cap. */
        LINE_TOO_LONG,
        /** The maximum number of lines was delivered. */
        MAX_LINES_EXCEEDED,
        /** The deadline elapsed. */
        DEADLINE_EXCEEDED,
        /** The total byte budget was consumed. */
        MAX_BYTES_EXCEEDED
    }

    /** Receives each complete line; return {@code false} to stop reading. */
    public interface LineHandler {
        boolean onLine(String line);
    }

    /**
     * Read lines from {@code input} until the handler stops, EOF, or a bound is hit.
     *
     * @param input   the raw stdout stream
     * @param limits   bounds for this read
     * @param handler  receives each line
     * @param clock    epoch-millis source for the deadline check
     * @return the reason reading stopped
     * @throws IOException if reading from {@code input} fails
     */
    public StopReason read(InputStream input, Limits limits, LineHandler handler, LongSupplier clock)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }

        BufferedInputStream in = new BufferedInputStream(input);
        long deadline = limits.getDeadlineMillis() > 0 ? clock.getAsLong() + limits.getDeadlineMillis() : 0L;
        int lines = 0;
        int totalBytes = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();

        while (true) {
            if (deadline > 0 && clock.getAsLong() >= deadline) {
                return StopReason.DEADLINE_EXCEEDED;
            }
            int b = in.read();
            if (b == -1) {
                if (line.size() > 0) {
                    String text = decode(line);
                    if (!handler.onLine(text)) {
                        return StopReason.COMPLETED;
                    }
                }
                return StopReason.EOF;
            }
            totalBytes++;
            if (b == '\n') {
                String text = decode(line);
                line.reset();
                lines++;
                if (!handler.onLine(text)) {
                    return StopReason.COMPLETED;
                }
                if (lines >= limits.getMaxLines()) {
                    return StopReason.MAX_LINES_EXCEEDED;
                }
                if (limits.getMaxTotalBytes() > 0 && totalBytes >= limits.getMaxTotalBytes()) {
                    return StopReason.MAX_BYTES_EXCEEDED;
                }
            } else {
                line.write(b);
                if (line.size() > limits.getMaxLineBytes()) {
                    return StopReason.LINE_TOO_LONG;
                }
                if (limits.getMaxTotalBytes() > 0 && totalBytes >= limits.getMaxTotalBytes()) {
                    return StopReason.MAX_BYTES_EXCEEDED;
                }
            }
        }
    }

    private static String decode(ByteArrayOutputStream line) {
        String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
