package gh.nusashell.nusadesk.infrastructure.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streams one release-asset APK into the app cache and hashes it in the same
 * pass (ADR-0038, assisted install flow).
 *
 * <p>The SHA-256 is computed <em>while</em> the bytes are written, not by
 * re-reading the file afterwards: a second pass would open a window where the
 * digest could describe different bytes than the ones persisted, and it would
 * double the I/O of what is already the slowest step of the update flow. The
 * returned digest attests exactly the bytes the file now holds; the caller
 * compares it against the digest recorded with the release and must treat a
 * mismatch as a failed download.</p>
 *
 * <p>The payload is written to {@code <destination>.tmp} in the same
 * directory and promoted with an atomic {@code Files.move}. A cache hit on a
 * later attempt therefore only ever observes a complete file — a partial or
 * aborted download can never occupy the destination path. This is the same
 * atomic-activation discipline ADR-0002 applies to runtime payloads.</p>
 *
 * <p>The class is deliberately pure Java: the coordinator supplies the
 * Android-facing {@link UpdateSource} (HTTP response body) and the cache
 * location, and runs the download on its own executor.</p>
 */
public final class ApkDownloader {

    /**
     * Byte source for one download attempt. Implemented by the coordinator's
     * HTTP adapter; tests use an in-memory fake.
     */
    public interface UpdateSource {
        /**
         * The expected payload length in bytes (e.g. the release asset's
         * declared size), or {@code -1} when unknown. Used for the early
         * size-cap rejection and as the progress total only — the returned
         * digest remains the authoritative integrity check.
         */
        long expectedLength() throws IOException;

        /** Opens the payload stream. The downloader closes it. */
        InputStream open() throws IOException;
    }

    /** Per-chunk progress callback for the download popup. */
    public interface ProgressListener {
        /**
         * Called once per written chunk.
         *
         * @param bytesRead total bytes written so far
         * @param total     {@link UpdateSource#expectedLength()}, or {@code -1}
         *                  when the source does not know the length
         */
        void onProgress(long bytesRead, long total);
    }

    private static final int BUFFER_BYTES = 16 * 1024;

    private ApkDownloader() {
    }

    /**
     * Downloads {@code source} into {@code destination}, enforcing
     * {@code maxBytes} as a hard cap.
     *
     * <p>A source that declares an {@code expectedLength} above the cap is
     * rejected before any byte is read. A source that streams past the cap
     * anyway fails with {@link IOException} partway through. On every failure
     * — I/O error, cap breach, listener or source {@link RuntimeException} —
     * the temp file is deleted and {@code destination} keeps whatever it held
     * before (nothing, or the previous complete download).</p>
     *
     * @param source      the release asset byte source
     * @param destination target file inside the app-private cache dir; its
     *                    parent must exist
     * @param maxBytes    hard size cap in bytes
     * @param listener    optional per-chunk progress sink; may be {@code null}
     * @return lowercase hex SHA-256 of the bytes written to
     *         {@code destination}
     * @throws IOException on any I/O failure or when the cap is exceeded
     */
    public static String download(UpdateSource source, File destination, long maxBytes,
            ProgressListener listener) throws IOException {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("source and destination must not be null");
        }
        long expected = source.expectedLength();
        if (expected > maxBytes) {
            throw new IOException("declared length " + expected
                    + " exceeds the " + maxBytes + " byte cap");
        }
        File temp = new File(destination.getPath() + ".tmp");
        MessageDigest digest = newSha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = source.open();
                OutputStream out = new FileOutputStream(temp)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
                if (total > maxBytes) {
                    throw new IOException(
                            "download exceeds the " + maxBytes + " byte cap");
                }
                if (listener != null) {
                    listener.onProgress(total, expected >= 0 ? expected : -1);
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        try {
            Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // Best-effort cleanup; the original failure is the one that propagates.
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * The lowercase hex SHA-256 of an existing file, read in one streaming
     * pass. Used to re-validate a cached-but-cancelled download before the
     * install session is staged again (the cache probe).
     */
    public static String sha256Hex(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }
}
