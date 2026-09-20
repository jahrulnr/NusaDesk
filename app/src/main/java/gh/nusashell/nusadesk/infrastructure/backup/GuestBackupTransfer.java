package gh.nusashell.nusadesk.infrastructure.backup;

import android.content.Context;
import android.os.StatFs;

import gh.nusashell.nusadesk.application.backup.BackupFailure;
import gh.nusashell.nusadesk.application.backup.BackupFailureException;
import gh.nusashell.nusadesk.application.backup.BackupProgress;
import gh.nusashell.nusadesk.application.backup.BackupProgressListener;
import gh.nusashell.nusadesk.application.backup.BackupResult;
import gh.nusashell.nusadesk.application.backup.GuestBackupUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.domain.backup.BackupManifest;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupScopePolicy;
import gh.nusashell.nusadesk.domain.backup.BackupSelection;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.runtime.RuntimePayloadSupport;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The guest backup/restore engine. Streams archives to/from plain streams via
 * {@link BackupArchiveWriter}/{@link BackupArchiveReader} and applies them to
 * the runtime layout through {@link RuntimePayloadSupport}, so the same
 * extraction safety and atomic activation rules the installers enforce apply
 * verbatim here.
 *
 * <p>Every operation runs under {@code PayloadIo.INSTALL_LOCK} through the
 * facade, serialized with installs. An import refuses to start while a guest
 * session is live (its processes would keep writing into trees being
 * replaced); an export may run under a live session but can then capture a
 * mid-write file — the help text says so.</p>
 *
 * <p>FULL restore swaps the staged rootfs into {@code active} (the previous
 * tree parks at {@code previous} and rolls back on failure), then activates
 * each archived add-on and replaces {@code linux-wrapper/state} wholesale.
 * HOME/CUSTOM replace each declared top-level subtree wholesale: the existing
 * subtree moves aside, the staged one moves in, and the aside copy is deleted
 * only after success or moved back on failure.</p>
 */
public final class GuestBackupTransfer implements GuestBackupUseCase {

    /** Free-space headroom demanded on top of the manifest's declared bytes. */
    private static final long STORAGE_MARGIN_BYTES = 32L * 1024L * 1024L;
    /** Slack above the declared payload bytes the extractor is allowed to write. */
    private static final long EXTRACT_SLACK_BYTES = 64L * 1024L * 1024L;

    private final BackupLayout layout;
    private final RuntimeCatalogEntry catalogEntry;
    private final Set<String> curatedAddonIds;
    private final RuntimeStateStore stateStore;
    private final String appVersion;
    private final BooleanSupplier sessionActiveProbe;
    private final LongSupplier availableBytesProbe;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BackupArchiveWriter writer = new BackupArchiveWriter();
    private final BackupArchiveReader reader = new BackupArchiveReader();

    /**
     * @param filesDir           the app files dir
     * @param stateStore         runtime state store stamped READY after a FULL restore
     * @param appVersion         the app's own versionName, recorded in the manifest
     * @param sessionActiveProbe true while a guest session may touch the rootfs
     * @param availableBytesProbe free bytes of the files partition, or -1 when unknown
     */
    public GuestBackupTransfer(Path filesDir, RuntimeStateStore stateStore, String appVersion,
            BooleanSupplier sessionActiveProbe, LongSupplier availableBytesProbe) {
        if (filesDir == null || stateStore == null) {
            throw new IllegalArgumentException("filesDir and stateStore must not be null");
        }
        this.catalogEntry = CuratedRuntimeCatalog.ubuntuBaseArm64();
        this.layout = new BackupLayout(filesDir, catalogEntry.getAppId());
        this.stateStore = stateStore;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.sessionActiveProbe = sessionActiveProbe;
        this.availableBytesProbe = availableBytesProbe;
        this.curatedAddonIds = curatedAddonIds();
    }

    /** Production wiring: files dir, status bus, and storage probe bound to Android. */
    public static GuestBackupTransfer inAppStorage(Context context,
            RuntimeStateStore stateStore, String appVersion) {
        File filesDir = context.getApplicationContext().getFilesDir();
        return new GuestBackupTransfer(filesDir.toPath(), stateStore, appVersion,
                () -> {
                    HostRuntimeStatus status = RuntimeStatusBus.getInstance().current();
                    return status != null && isSessionLive(status.getState());
                },
                () -> new StatFs(filesDir.getPath()).getAvailableBytes());
    }

    @Override
    public BackupResult exportBackup(BackupSelection selection, OutputStream destination,
            BackupProgressListener listener) {
        if (selection == null || destination == null) {
            throw new IllegalArgumentException("selection and destination must not be null");
        }
        if (!running.compareAndSet(false, true)) {
            return BackupResult.failed(BackupFailure.BUSY,
                    "another backup or install is already running");
        }
        try {
            return RuntimePayloadSupport.underInstallLock(
                    () -> doExport(selection, destination, listener));
        } catch (RuntimeInstallationException exception) {
            return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE, exception.getMessage());
        } catch (IOException exception) {
            return BackupResult.failed(ioFailure(exception), message(exception));
        } catch (RuntimeException exception) {
            return BackupResult.failed(BackupFailure.IO_FAILURE,
                    "backup failed: " + exception.getMessage());
        } finally {
            running.set(false);
        }
    }

    @Override
    public BackupResult importBackup(InputStream source, BackupProgressListener listener) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (!running.compareAndSet(false, true)) {
            return BackupResult.failed(BackupFailure.BUSY,
                    "another backup or install is already running");
        }
        try {
            if (sessionActiveProbe != null && sessionActiveProbe.getAsBoolean()) {
                return BackupResult.failed(BackupFailure.BUSY,
                        "Stop the Linux session before restoring.");
            }
            try (BackupArchiveReader.Opened opened = reader.open(source)) {
                BackupManifest manifest = opened.getManifest();
                if (!catalogEntry.getAppId().equals(manifest.getRuntimeAppId())) {
                    return BackupResult.failed(BackupFailure.RUNTIME_MISMATCH,
                            "this backup is for a different runtime ("
                                    + manifest.getRuntimeAppId() + ")");
                }
                long available = availableBytesProbe == null
                        ? -1L : availableBytesProbe.getAsLong();
                if (available >= 0
                        && available < manifest.getTotalBytes() + STORAGE_MARGIN_BYTES) {
                    return BackupResult.failed(BackupFailure.STORAGE_FULL,
                            "Free at least " + RuntimePayloadSupport.formatMiB(
                                    manifest.getTotalBytes() + STORAGE_MARGIN_BYTES)
                                    + " MiB and retry.");
                }
                return RuntimePayloadSupport.underInstallLock(
                        () -> restoreUnderLock(opened, manifest, listener));
            }
        } catch (BackupFailureException exception) {
            return BackupResult.failed(exception.getFailure(), exception.getMessage());
        } catch (RuntimeInstallationException exception) {
            return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE, exception.getMessage());
        } catch (IOException exception) {
            return BackupResult.failed(ioFailure(exception), message(exception));
        } catch (RuntimeException exception) {
            return BackupResult.failed(BackupFailure.IO_FAILURE,
                    "restore failed: " + exception.getMessage());
        } finally {
            running.set(false);
        }
    }

    private BackupResult doExport(BackupSelection selection, OutputStream destination,
            BackupProgressListener listener)
            throws IOException, RuntimeInstallationException {
        Path rootfs = layout.rootfsDir();
        if (!Files.isDirectory(rootfs)) {
            return BackupResult.failed(BackupFailure.RUNTIME_REQUIRED,
                    "Install Linux before making a backup.");
        }
        List<BackupArchiveWriter.Tree> trees = new ArrayList<>();
        List<String> archivedRoots = new ArrayList<>();
        switch (selection.getMode()) {
            case FULL:
                trees.add(new BackupArchiveWriter.Tree("rootfs", rootfs, ""));
                for (GuestAddonPayloadProfile addon : CuratedRuntimeCatalog.guestAddons()) {
                    Path activeAddon = layout.activeAddonDir(addon.getAddonId());
                    if (activeAddon != null && Files.isDirectory(activeAddon)) {
                        trees.add(new BackupArchiveWriter.Tree(
                                "addons/" + addon.getAddonId(), activeAddon, null));
                    }
                }
                if (Files.isDirectory(layout.stateDir())) {
                    trees.add(new BackupArchiveWriter.Tree("state", layout.stateDir(), null));
                }
                break;
            case HOME:
            case CUSTOM:
                for (String root : selection.getRoots()) {
                    String rel = BackupScopePolicy.relativeOf(root);
                    Path subtree = rootfs.resolve(rel);
                    if (Files.isDirectory(subtree)) {
                        trees.add(new BackupArchiveWriter.Tree(
                                "rootfs/" + rel, subtree, rel));
                        archivedRoots.add(root);
                    }
                }
                if (trees.isEmpty()) {
                    return BackupResult.failed(BackupFailure.IO_FAILURE,
                            "none of the selected folders exist in the guest");
                }
                break;
            default:
                return BackupResult.failed(BackupFailure.UNSUPPORTED_FORMAT,
                        "unknown backup mode");
        }
        BackupManifest manifest = writer.write(trees, selection.getMode(), archivedRoots,
                catalogEntry.getAppId(), catalogEntry.getVersion(), appVersion,
                destination, listener);
        return BackupResult.ready("Backup written",
                manifest.getEntries(), manifest.getTotalBytes());
    }

    private BackupResult restoreUnderLock(BackupArchiveReader.Opened opened,
            BackupManifest manifest, BackupProgressListener listener)
            throws IOException, RuntimeInstallationException {
        Path staging = layout.stagingDir();
        RuntimePayloadSupport.deleteRecursively(staging);
        try {
            RuntimePayloadSupport.extractTar(opened.getTar(), staging,
                    manifest.getTotalBytes() + EXTRACT_SLACK_BYTES);
            emit(listener, manifest.getEntries(), manifest.getTotalBytes(), "Restoring");
            BackupResult failure = manifest.getMode() == BackupMode.FULL
                    ? restoreFull(staging)
                    : restoreMerge(staging, manifest);
            if (failure != null) {
                return failure;
            }
            return BackupResult.ready("Restored the backup",
                    manifest.getEntries(), manifest.getTotalBytes());
        } finally {
            RuntimePayloadSupport.deleteRecursively(staging);
            RuntimePayloadSupport.deleteRecursively(layout.asideDir());
        }
    }

    /**
     * FULL restore: validate the staged rootfs and every archived add-on id
     * before touching the live slots, then swap rootfs, add-ons, and session
     * state in that order and stamp the runtime READY.
     *
     * @return a FAILED result, or {@code null} on success.
     */
    private BackupResult restoreFull(Path staging)
            throws IOException, RuntimeInstallationException {
        Path stagedRootfs = staging.resolve("rootfs");
        if (!Files.isDirectory(stagedRootfs)) {
            return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE,
                    "the archive has no system files");
        }
        RuntimePayloadSupport.validateRootfs(stagedRootfs);

        Path stagedAddons = staging.resolve("addons");
        List<Path> addonTrees = new ArrayList<>();
        if (Files.isDirectory(stagedAddons)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(stagedAddons)) {
                for (Path child : children) {
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(child)) {
                        return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE,
                                "the archive carries an invalid add-on entry");
                    }
                    if (!curatedAddonIds.contains(child.getFileName().toString())) {
                        return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE,
                                "the archive carries an unknown add-on: "
                                        + child.getFileName());
                    }
                    addonTrees.add(child);
                }
            }
        }

        try {
            Files.createDirectories(layout.runtimeDir());
            RuntimePayloadSupport.activatePayload(stagedRootfs,
                    layout.rootfsDir(), layout.previousRootfsDir(), "runtime");
            for (Path addonTree : addonTrees) {
                String addonId = addonTree.getFileName().toString();
                Files.createDirectories(layout.addonsDir().resolve(addonId));
                RuntimePayloadSupport.activatePayload(addonTree,
                        layout.activeAddonDir(addonId),
                        layout.previousAddonDir(addonId), "add-on payload");
            }
            Path stagedState = staging.resolve("state");
            if (Files.exists(stagedState, LinkOption.NOFOLLOW_LINKS)) {
                replaceSubtree(stagedState, layout.stateDir());
            }
        } catch (RuntimeInstallationException exception) {
            return BackupResult.failed(BackupFailure.IO_FAILURE, exception.getMessage());
        }
        stateStore.save(new RuntimeSnapshot(
                catalogEntry.getAppId(), RuntimeState.READY,
                "Restored from a full backup", 0, System.currentTimeMillis()));
        return null;
    }

    /**
     * HOME/CUSTOM restore: requires a usable active runtime, then replaces
     * each declared top-level subtree wholesale — the existing subtree parks
     * under the aside slot, the staged one moves in, and the parked copy is
     * deleted only after success or moved back on failure.
     *
     * @return a FAILED result, or {@code null} on success.
     */
    private BackupResult restoreMerge(Path staging, BackupManifest manifest) {
        Path active = layout.rootfsDir();
        try {
            RuntimePayloadSupport.validateRootfs(active);
        } catch (RuntimeInstallationException exception) {
            return BackupResult.failed(BackupFailure.RUNTIME_REQUIRED,
                    "This backup needs Linux installed first — it only carries "
                            + "the selected folders.");
        }
        Path stagedRootfs = staging.resolve("rootfs");
        for (String root : manifest.getRoots()) {
            String rel = BackupScopePolicy.relativeOf(root);
            Path staged = stagedRootfs.resolve(rel);
            if (!Files.isDirectory(staged)) {
                return BackupResult.failed(BackupFailure.UNSAFE_ARCHIVE,
                        "the archive is missing the declared folder " + root);
            }
            try {
                replaceSubtree(staged, active.resolve(rel));
            } catch (IOException exception) {
                return BackupResult.failed(ioFailure(exception), exception.getMessage());
            }
        }
        return null;
    }

    /**
     * Wholesale replacement of {@code target} by {@code staged}: move the
     * existing subtree into a fresh aside slot, move the staged tree in, and
     * restore the parked tree if the move-in fails. The aside slot is deleted
     * only after the staged tree is in place.
     */
    private void replaceSubtree(Path staged, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.createDirectories(layout.asideDir());
        Path aside = Files.createTempDirectory(layout.asideDir(), "merge");
        Path parked = aside.resolve(target.getFileName().toString());
        boolean hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (hadTarget) {
            RuntimePayloadSupport.moveAtomically(target, parked);
        }
        try {
            RuntimePayloadSupport.moveAtomically(staged, target);
        } catch (IOException exception) {
            if (hadTarget && Files.exists(parked, LinkOption.NOFOLLOW_LINKS)) {
                RuntimePayloadSupport.moveAtomically(parked, target);
            }
            throw exception;
        }
        RuntimePayloadSupport.deleteRecursively(aside);
    }

    private void emit(BackupProgressListener listener, long entries, long bytes,
            String detail) {
        if (listener != null) {
            listener.onProgress(new BackupProgress(entries, bytes, detail));
        }
    }

    private static BackupFailure ioFailure(IOException exception) {
        return RuntimePayloadSupport.isStorageFullFailure(exception)
                ? BackupFailure.STORAGE_FULL
                : BackupFailure.IO_FAILURE;
    }

    private static String message(IOException exception) {
        String detail = exception.getMessage();
        return detail == null ? "the storage read or write failed" : detail;
    }

    /** Session states during which guest processes may still touch the rootfs. */
    private static boolean isSessionLive(SessionState state) {
        switch (state) {
            case STARTING:
            case RUNNING:
            case RECONNECTING:
            case STOPPING:
            case RECOVERING:
                return true;
            default:
                return false;
        }
    }

    private static Set<String> curatedAddonIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (GuestAddonPayloadProfile addon : CuratedRuntimeCatalog.guestAddons()) {
            ids.add(addon.getAddonId());
        }
        return ids;
    }
}
