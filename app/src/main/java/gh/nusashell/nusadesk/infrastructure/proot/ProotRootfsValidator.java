package gh.nusashell.nusadesk.infrastructure.proot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates that a curated active rootfs directory is present and minimally
 * bootable as a PRoot guest.
 *
 * <p>Pure Java using {@link java.nio.file.Path} so it runs in plain JVM unit
 * tests against a temporary directory. It checks that the rootfs is a
 * directory and contains {@code etc/os-release} and {@code usr/bin/sh} as
 * regular files, matching the runtime installer's activation contract. It does
 * not parse {@code os-release} content or verify a digest; the digest is
 * verified once at install time by the runtime installer.</p>
 */
public final class ProotRootfsValidator {

    /** Required guest files for a minimal PRoot session. */
    public static final String OS_RELEASE = "etc/os-release";
    public static final String GUEST_SH = "usr/bin/sh";

    public ProotRootfsValidator() {
    }

    /**
     * Validate the active rootfs directory.
     *
     * @param rootfs       the active rootfs path (must be a directory)
     * @param expectedAppId the curated app id the caller resolved the path from;
     *                      validated for non-blankness here, and against the
     *                      curated catalog by the Android adapter
     * @throws ProotLaunchException if the rootfs is missing or incomplete
     */
    public void validate(Path rootfs, String expectedAppId) throws ProotLaunchException {
        if (rootfs == null) {
            throw new ProotLaunchException("rootfs path must not be null");
        }
        ProotPaths.requireAppId(expectedAppId);
        if (!Files.isDirectory(rootfs)) {
            throw new ProotLaunchException("active rootfs is not a directory: " + rootfs);
        }
        Path osRelease = rootfs.resolve(OS_RELEASE);
        if (!Files.isRegularFile(osRelease)) {
            throw new ProotLaunchException("active rootfs is missing " + OS_RELEASE);
        }
        Path sh = rootfs.resolve(GUEST_SH);
        if (!Files.isRegularFile(sh)) {
            throw new ProotLaunchException("active rootfs is missing " + GUEST_SH);
        }
    }
}
