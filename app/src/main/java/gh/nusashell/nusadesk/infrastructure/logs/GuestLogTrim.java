package gh.nusashell.nusadesk.infrastructure.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bounds a growing log file <em>in place</em>.
 *
 * <p>The writers of these logs — the vendored {@code systemctl3} manager inside
 * the guest, or {@link SessionLogWriter} on the host — hold an append-mode file
 * descriptor open for the whole session. Rotation by rename would orphan that
 * descriptor: the writer would keep appending to the unlinked inode, the new
 * file would stay empty, and disk usage would keep growing invisibly. Keeping
 * the same inode and rewriting the tail in place is the only trim an open
 * {@code O_APPEND} writer survives cleanly: after the shrink its next write
 * lands at the new, smaller end of file.</p>
 *
 * <p>A small race is inherent: a guest line written between the tail read and
 * the truncate can be lost. That is acceptable for logs and far cheaper than
 * coordinating with a writer inside the guest.</p>
 */
public final class GuestLogTrim {

    private GuestLogTrim() {
    }

    /**
     * Shrinks {@code file} to at most {@code keepBytes} by keeping its newest
     * bytes, aligned forward to the next line boundary so the result never
     * starts mid-line.
     *
     * @param file      regular file to bound; missing or non-regular files are
     *                  ignored (a rotated-away file simply needs no trim)
     * @param keepBytes tail bytes to retain; must be positive
     * @return {@code true} when the file was actually shrunk
     */
    public static boolean shrinkToTail(Path file, long keepBytes) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (keepBytes <= 0 || keepBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("keepBytes out of range: " + keepBytes);
        }
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            long size = raf.length();
            if (size <= keepBytes) {
                return false;
            }
            long tailStart = size - keepBytes;
            byte[] tail = new byte[(int) keepBytes];
            raf.seek(tailStart);
            raf.readFully(tail);
            // Drop the partial first line so the kept tail starts on a boundary.
            int contentStart = 0;
            while (contentStart < tail.length && tail[contentStart] != (byte) '\n') {
                contentStart++;
            }
            if (contentStart < tail.length) {
                contentStart++; // keep from just after the newline
            }
            int contentLength = tail.length - contentStart;
            raf.seek(0);
            if (contentLength > 0) {
                raf.write(tail, contentStart, contentLength);
            }
            raf.setLength(contentLength);
            return true;
        }
    }
}
