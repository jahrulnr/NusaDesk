package gh.nusashell.nusadesk.infrastructure.proot;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Owns path resolution and path-string validation for the packaged PRoot bridge.
 *
 * <p>Pure Java (no Android imports) so the command factory, spec builder, and
 * their tests can run on a plain JVM. The Android adapter
 * {@link ProotLauncher} feeds {@code Context}-derived paths into these helpers.
 * Validation rejects relative paths, {@code ..} traversal, and null bytes
 * without touching the filesystem, so it is deterministic and side-effect free.</p>
 */
public final class ProotPaths {
    /** Filename of the packaged PRoot PIE executable inside the APK lib area. */
    public static final String PROOT_BINARY_NAME = "libproot.so";

    /**
     * Filename of the packaged PRoot ELF loader inside the APK lib area.
     *
     * <p>PRoot needs a freestanding loader to start tracees. By default it
     * extracts an embedded copy into {@code PROOT_TMP_DIR} and re-executes it;
     * under {@code untrusted_app} on targetSdk 29+ that copy is labeled
     * {@code app_data_file} and its exec is denied by SELinux
     * ({@code execute_no_trans}). Packaging the loader as a native library puts
     * it in {@code apk_data_file}, which the app domain may execute; PRoot then
     * picks it up through the {@code PROOT_LOADER} environment variable.</p>
     */
    public static final String PROOT_LOADER_BINARY_NAME = "libproot-loader.so";

    /** Subdirectory layout under the app files dir, matching the runtime installer. */
    public static final String RUNTIME_ROOT = "linux-wrapper";
    public static final String RUNTIMES_DIR = "runtimes";
    public static final String ADDONS_DIR = "addons";
    public static final String ACTIVE_DIR = "active";

    private ProotPaths() {
    }

    /**
     * Resolve the curated active rootfs directory for an app id.
     *
     * <p>Layout: {@code <filesDir>/linux-wrapper/runtimes/<appId>/active}, matching
     * {@code AndroidRuntimeInstaller}. The app id is validated against the curated
     * catalog by the caller; this method only resolves the path.</p>
     */
    public static Path activeRootfsPath(Path filesDir, String appId) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir must not be null");
        }
        requireAbsolutePath(filesDir.toString(), "filesDir");
        String id = requireAppId(appId);
        return filesDir.resolve(RUNTIME_ROOT).resolve(RUNTIMES_DIR).resolve(id).resolve(ACTIVE_DIR);
    }

    /**
     * Resolve the activated add-on overlay directory for an add-on id.
     *
     * <p>Layout: {@code <filesDir>/linux-wrapper/addons/<addonId>/active}, matching
     * {@code AndroidGuestSshAddonInstaller}. Returns {@code null} when the id is
     * blank so detection can treat a missing profile as absent.</p>
     */
    public static Path activeAddonPath(Path filesDir, String addonId) {
        if (filesDir == null || addonId == null || addonId.trim().isEmpty()) {
            return null;
        }
        String id = requireAppId(addonId);
        return filesDir.resolve(RUNTIME_ROOT).resolve(ADDONS_DIR).resolve(id).resolve(ACTIVE_DIR);
    }

    /** Resolve the packaged PRoot binary inside the APK native-library directory. */
    public static Path prootBinaryPath(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) {
            throw new IllegalArgumentException("nativeLibraryDir must not be null or empty");
        }
        requireAbsolutePath(nativeLibraryDir, "nativeLibraryDir");
        return Paths.get(nativeLibraryDir).resolve(PROOT_BINARY_NAME);
    }

    /**
     * Resolve the packaged PRoot ELF loader inside the APK native-library
     * directory. Presence is optional: when absent, PRoot falls back to
     * extracting its embedded loader into {@code PROOT_TMP_DIR} (denied under
     * {@code untrusted_app} on targetSdk 29+, but still used on domains and
     * API levels where it is allowed).
     */
    public static Path prootLoaderPath(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) {
            throw new IllegalArgumentException("nativeLibraryDir must not be null or empty");
        }
        requireAbsolutePath(nativeLibraryDir, "nativeLibraryDir");
        return Paths.get(nativeLibraryDir).resolve(PROOT_LOADER_BINARY_NAME);
    }

    /**
     * Whether a host path is confined under one of the app-private root directories.
     *
     * <p>Used to reject arbitrary host bind mounts: only app-private host paths
     * (e.g. host-key storage) may be bound into the guest in addition to the fixed
     * system binds. The check is string-prefix based and uses normalized absolute
     * paths so a trailing-slash mismatch cannot bypass it.</p>
     */
    public static boolean isAppPrivatePath(String hostPath, List<String> appRoots) {
        String normalized = requireAbsolutePath(hostPath, "hostPath");
        if (appRoots == null || appRoots.isEmpty()) {
            return false;
        }
        String withSlash = ensureTrailingSlash(normalized);
        for (String root : appRoots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            String rootNormalized = ensureTrailingSlash(requireAbsolutePath(root, "appRoot"));
            if (withSlash.startsWith(rootNormalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate that a path string is absolute, non-empty, free of null bytes,
     * and contains no {@code ..} segments. Does not touch the filesystem.
     */
    public static String requireAbsolutePath(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or empty");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not contain null bytes");
        }
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException(field + " must be an absolute path: " + value);
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(field + " must not contain '..' segments: " + value);
            }
        }
        return value;
    }

    /** Validate a curated app id: non-blank, no slashes, no traversal. */
    public static String requireAppId(String appId) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        if (appId.indexOf('/') >= 0 || appId.indexOf('\\') >= 0
                || appId.indexOf('\0') >= 0 || "..".equals(appId) || ".".equals(appId)) {
            throw new IllegalArgumentException("appId must be a single path segment: " + appId);
        }
        return appId;
    }

    private static String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
