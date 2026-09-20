package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;

import java.nio.file.Path;

/**
 * The host paths a backup reads and a restore writes, derived from the app
 * files dir and the curated runtime app id.
 *
 * <p>Pure {@link java.nio.file.Path} so the engine's tests run on a plain JVM
 * against temporary directories. Staging and merge-aside slots live under
 * {@code linux-wrapper/backup/} so every atomic rename stays on the same
 * filesystem as the runtime slots they replace.</p>
 */
final class BackupLayout {

    private final Path filesDir;
    private final String runtimeAppId;

    BackupLayout(Path filesDir, String runtimeAppId) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir must not be null");
        }
        this.filesDir = filesDir;
        this.runtimeAppId = ProotPaths.requireAppId(runtimeAppId);
    }

    Path linuxWrapperDir() {
        return filesDir.resolve(ProotPaths.RUNTIME_ROOT);
    }

    Path runtimeDir() {
        return linuxWrapperDir().resolve(ProotPaths.RUNTIMES_DIR).resolve(runtimeAppId);
    }

    /** {@code linux-wrapper/runtimes/<appId>/active} — the live rootfs. */
    Path rootfsDir() {
        return ProotPaths.activeRootfsPath(filesDir, runtimeAppId);
    }

    Path previousRootfsDir() {
        return runtimeDir().resolve("previous");
    }

    Path addonsDir() {
        return linuxWrapperDir().resolve(ProotPaths.ADDONS_DIR);
    }

    Path activeAddonDir(String addonId) {
        return ProotPaths.activeAddonPath(filesDir, addonId);
    }

    Path previousAddonDir(String addonId) {
        return addonsDir().resolve(ProotPaths.requireAppId(addonId)).resolve("previous");
    }

    /** {@code linux-wrapper/state} — managed os-release, DNS, bridge state. */
    Path stateDir() {
        return linuxWrapperDir().resolve("state");
    }

    /** {@code linux-wrapper/backup} — restore scratch space. */
    Path workDir() {
        return linuxWrapperDir().resolve("backup");
    }

    Path stagingDir() {
        return workDir().resolve("staging");
    }

    Path asideDir() {
        return workDir().resolve("aside");
    }
}
