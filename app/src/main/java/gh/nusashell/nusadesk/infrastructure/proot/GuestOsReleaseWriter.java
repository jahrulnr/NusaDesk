package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds the product-owned {@code /etc/os-release} overlay.
 *
 * <p>The active Ubuntu rootfs remains the source of truth for all original
 * distribution fields. This writer copies that content into persistent
 * app-private state and appends one namespaced NusaDesk field. PRoot strictly
 * binds the generated file at the literal guest path, so package updates may
 * change {@code /usr/lib/os-release} without permanently removing the product
 * field from new sessions.</p>
 */
public final class GuestOsReleaseWriter {

    /** App-private state path, relative to filesDir. */
    public static final String STATE_RELATIVE_PATH = "linux-wrapper/state/os-release";
    /** Guest path overridden by the generated source. */
    public static final String GUEST_PATH = "/etc/os-release";
    /** Namespaced custom keys; keep Ubuntu's ID fields unchanged. */
    public static final String CONTRIBUTOR_KEY = "NUSADESK_CONTRIBUTOR";
    public static final String CONTRIBUTOR_VALUE = "NusaDesk";
    public static final String SOURCE_KEY = "NUSADESK_SOURCE";
    public static final String SOURCE_VALUE = "https://github.com/jahrulnr/NusaDesk";
    private static final int MAX_BASE_BYTES = 64 * 1024;
    private static final String TEMP_PREFIX = ".os-release-";
    private static final String TEMP_SUFFIX = ".tmp";

    /** Idempotent write result. */
    public enum Result {
        UNCHANGED,
        UPDATED
    }

    private GuestOsReleaseWriter() {
    }

    /**
     * Generate/update the app-owned source from the active rootfs base file.
     *
     * @param filesDir persistent app-private files directory
     * @param activeRootfs active curated rootfs
     * @return whether the source changed
     * @throws IOException when the base path is unsafe/unreadable or the source
     *                     cannot be atomically written
     */
    public static Result ensure(Path filesDir, Path activeRootfs) throws IOException {
        if (filesDir == null || activeRootfs == null) {
            throw new IllegalArgumentException("filesDir and activeRootfs are required");
        }
        Path base = safeBaseOsRelease(activeRootfs);
        if (Files.size(base) > MAX_BASE_BYTES) {
            throw new IOException("guest os-release exceeds " + MAX_BASE_BYTES + " bytes");
        }
        String baseText = new String(Files.readAllBytes(base), StandardCharsets.UTF_8);
        if (baseText.indexOf('\0') >= 0) {
            throw new IOException("guest os-release contains a null byte");
        }
        byte[] expected = appendContributor(baseText).getBytes(StandardCharsets.UTF_8);

        Path target = filesDir.resolve(STATE_RELATIVE_PATH);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && sameBytes(target, expected)) {
            ensureWritable(target);
            return Result.UNCHANGED;
        }
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            Files.write(temporary, expected, StandardOpenOption.TRUNCATE_EXISTING);
            ensureWritable(temporary);
            moveAtomically(temporary, target);
            ensureWritable(target);
            return Result.UPDATED;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw failure;
        }
    }

    /** Format the base content with one canonical contributor assignment. */
    public static String appendContributor(String baseText) {
        if (baseText == null) {
            throw new IllegalArgumentException("baseText must not be null");
        }
        String[] lines = baseText.split("\\n", -1);
        StringBuilder result = new StringBuilder(baseText.length() + 48);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) {
            last--;
        }
        for (int i = 0; i < last; i++) {
            String line = lines[i].endsWith("\r")
                    ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.startsWith(CONTRIBUTOR_KEY + "=")
                    || line.startsWith(SOURCE_KEY + "=")) {
                continue;
            }
            result.append(line).append('\n');
        }
        result.append(CONTRIBUTOR_KEY).append("=\"")
                .append(CONTRIBUTOR_VALUE).append("\"\n");
        result.append(SOURCE_KEY).append("=\"")
                .append(SOURCE_VALUE).append("\"\n");
        return result.toString();
    }

    private static Path safeBaseOsRelease(Path activeRootfs) throws IOException {
        Path root = activeRootfs.toRealPath();
        Path candidate = activeRootfs.resolve("etc/os-release");
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(candidate)) {
            throw new IOException("guest os-release is missing");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("guest os-release escapes the active rootfs");
        }
        return real;
    }

    private static boolean sameBytes(Path file, byte[] expected) throws IOException {
        if (Files.size(file) != expected.length) {
            return false;
        }
        byte[] actual = Files.readAllBytes(file);
        if (actual.length != expected.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < expected.length; i++) {
            difference |= actual[i] ^ expected[i];
        }
        return difference == 0;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Set<StandardCopyOption> options = new HashSet<>();
        options.add(StandardCopyOption.ATOMIC_MOVE);
        options.add(StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(source, target, options.toArray(new StandardCopyOption[0]));
        } catch (IOException atomicFailed) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailed) {
                fallbackFailed.addSuppressed(atomicFailed);
                throw fallbackFailed;
            }
        }
    }

    private static void ensureWritable(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
            // App-private providers without POSIX mode support remain owner-writable.
        }
    }
}
