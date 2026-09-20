package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Public facade over the package-private {@link PayloadIo} primitives.
 *
 * <p>{@code PayloadIo} deliberately stays package-private so its safety
 * invariants are enforced, not duplicated: traversal-safe extraction, confined
 * symlinks and hard links, atomic renames, and the shared
 * {@link PayloadIo#INSTALL_LOCK} that serializes runtime installs. This facade
 * exposes exactly the operations the guest backup/restore engine needs and
 * nothing more. The installers and the restorer share
 * {@link #validateRootfs} and {@link #activatePayload} so an activated runtime
 * means the same thing on both paths.</p>
 */
public final class RuntimePayloadSupport {

    /** One filesystem operation that runs under the shared install lock. */
    public interface IoOperation<T> {
        T run() throws IOException, RuntimeInstallationException;
    }

    private RuntimePayloadSupport() {
    }

    /**
     * Runs {@code operation} while holding {@link PayloadIo#INSTALL_LOCK}, the
     * same monitor the installers synchronize on, so a restore can never race
     * an install or another restore's staging trees.
     */
    public static <T> T underInstallLock(IoOperation<T> operation)
            throws IOException, RuntimeInstallationException {
        synchronized (PayloadIo.INSTALL_LOCK) {
            return operation.run();
        }
    }

    /**
     * Streams the remaining entries of {@code tar} into {@code staging} with
     * the shared payload rules: bounded entry count and extracted size, no
     * absolute/escaping paths, confined symlinks and hard links, and
     * directories/regular files only.
     */
    public static void extractTar(TarArchiveInputStream tar, Path staging,
            long uncompressedCap) throws IOException, RuntimeInstallationException {
        PayloadIo.extractTar(tar, staging, uncompressedCap);
    }

    /** Atomic rename where supported, plain rename otherwise. */
    public static void moveAtomically(Path source, Path destination) throws IOException {
        PayloadIo.moveAtomically(source, destination);
    }

    /** Best-effort recursive delete used for staging/aside cleanup. */
    public static void deleteRecursively(Path path) {
        PayloadIo.deleteRecursively(path);
    }

    /**
     * The activation contract every runtime payload must satisfy before it may
     * become active: {@code etc/os-release} and {@code usr/bin/sh} as regular
     * files. Shared verbatim by the installer and the FULL restorer.
     */
    public static void validateRootfs(Path rootfs) throws RuntimeInstallationException {
        if (!Files.isRegularFile(rootfs.resolve("etc/os-release"))) {
            throw new RuntimeInstallationException("payload is missing etc/os-release");
        }
        if (!Files.isRegularFile(rootfs.resolve("usr/bin/sh"))) {
            throw new RuntimeInstallationException("payload is missing usr/bin/sh");
        }
    }

    /**
     * Atomic activation shared by the installers and the restorer: move
     * {@code active} aside into {@code previous} only after the old backup
     * slot is cleared, then move {@code staging} in. If the swap fails the
     * previous tree is moved back so a failed activation never leaves a false
     * active payload.
     *
     * @param what payload kind used in the failure message ("runtime",
     *             "add-on payload") so callers keep their own wording.
     */
    public static void activatePayload(Path staging, Path active, Path previous, String what)
            throws IOException, RuntimeInstallationException {
        boolean hadActive = Files.exists(active, LinkOption.NOFOLLOW_LINKS);
        if (hadActive) {
            // Keep active untouched until the old backup slot is clear and the
            // rename succeeds. A failed cleanup therefore cannot erase active.
            PayloadIo.deleteRecursively(previous);
            PayloadIo.moveAtomically(active, previous);
        }
        try {
            PayloadIo.moveAtomically(staging, active);
        } catch (IOException exception) {
            if (hadActive && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                PayloadIo.moveAtomically(previous, active);
            }
            throw new RuntimeInstallationException(
                    "could not activate the verified " + what, exception);
        }
    }

    /**
     * True when {@code exception} or any of its causes reports the filesystem
     * being out of space (ENOSPC). Mirrors the storage check inside
     * {@code PayloadIo.normalizeFailureMessage} so backup failures classify
     * the same condition the install path already recognizes.
     */
    public static boolean isStorageFullFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String detail = current.getMessage();
            if (detail != null) {
                String lower = detail.toLowerCase(Locale.ROOT);
                if (lower.contains("no space") || lower.contains("enospc")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** Whole-MiB formatter shared with the installer's storage messages. */
    public static String formatMiB(long bytes) {
        return PayloadIo.formatMiB(bytes);
    }
}
