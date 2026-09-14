package gh.nusashell.nusadesk.infrastructure.network;

import gh.nusashell.nusadesk.domain.network.ResolvConf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a validated guest {@code /etc/resolv.conf} atomically into an
 * app-private path so the PRoot bridge can bind it read-only-by-location into
 * the guest.
 *
 * <p>Android-free (pure {@code java.nio.file}): the path is supplied by the
 * Android adapter, so this writer is unit-testable with a temp directory. It
 * never mutates the active rootfs — it writes a single host file that PRoot
 * binds over the guest {@code /etc/resolv.conf}, so a failed or partial write
 * never corrupts a working rootfs resolver.</p>
 *
 * <p>Write contract:</p>
 * <ul>
 *   <li>The content comes from {@link ResolvConf#of(List)}: only validated
 *       literal {@code nameserver <ip>} lines, bounded count and size.</li>
 *   <li>The total file size is capped at {@link #MAX_FILE_BYTES}; an over-long
 *       result (which the validator should already prevent) is refused.</li>
 *   <li>The write is atomic: a temp file in the same directory is written, then
 *       moved over the target with {@code ATOMIC_MOVE} when supported and a
 *       {@code REPLACE_EXISTING} move otherwise. A failed write deletes the
 *       temp file and never touches the last known-good resolver.</li>
 *   <li>Returns {@code null} when there is no valid resolver, so the caller
 *       binds nothing and the guest keeps its own resolver (graceful).</li>
 * </ul>
 */
public final class GuestResolvConfWriter {

    /** Hard cap on the written file size. 3 lines of ~50 bytes is far below this. */
    public static final int MAX_FILE_BYTES = 512;

    private static final String TMP_PREFIX = "resolv-";
    private static final String TMP_SUFFIX = ".tmp";

    private GuestResolvConfWriter() {
    }

    /**
     * Write the validated resolv.conf to {@code targetFile} atomically.
     *
     * @param targetFile  the app-private host path to write (its parent is created)
     * @param nameservers raw candidate nameserver literals from the active network
     * @return {@code targetFile} when a valid resolver was written, or
     *         {@code null} when no valid nameserver exists (caller binds nothing)
     * @throws IOException when the file system write fails
     */
    public static Path write(Path targetFile, List<String> nameservers) throws IOException {
        ResolvConf conf = ResolvConf.of(nameservers);
        if (conf == null) {
            return null;
        }
        byte[] bytes = conf.toText().getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("resolv.conf exceeds " + MAX_FILE_BYTES
                    + " bytes after validation (" + bytes.length + ")");
        }
        Path parent = targetFile.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("resolv.conf target has no parent directory: " + targetFile);
        }
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, TMP_PREFIX, TMP_SUFFIX);
        try {
            Files.write(tmp, bytes);
            moveAtomically(tmp, targetFile);
            return targetFile;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best-effort cleanup; the original failure is the real error.
            }
            throw e;
        }
    }

    /**
     * Move {@code source} to {@code target}, preferring an atomic move and
     * falling back to a non-atomic replace when the filesystem does not support
     * atomic moves (some Android app-private filesystems). The fallback still
     * replaces the target, but is not atomic; this is acceptable because the
     * content is bounded and the writer is the only producer.
     */
    private static void moveAtomically(Path source, Path target) throws IOException {
        Set<StandardCopyOption> attempted = new HashSet<>();
        attempted.add(StandardCopyOption.REPLACE_EXISTING);
        attempted.add(StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(source, target, attempted.toArray(new StandardCopyOption[0]));
        } catch (IOException atomicFailed) {
            try {
                Files.move(source, target,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailed) {
                IOException combined = new IOException(
                        "could not move resolv.conf to " + target, fallbackFailed);
                combined.addSuppressed(atomicFailed);
                throw combined;
            }
        }
    }

    /** Empty list constant for callers that have no candidates. */
    public static final List<String> NO_CANDIDATES = Collections.emptyList();
}
