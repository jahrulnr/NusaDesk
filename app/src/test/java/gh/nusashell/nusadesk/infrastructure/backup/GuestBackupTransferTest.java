package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.application.backup.BackupFailure;
import gh.nusashell.nusadesk.application.backup.BackupResult;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupSelection;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Writer/reader round-trips of the guest backup engine over temporary
 * directories: a fake app files dir carries the runtime layout
 * ({@code linux-wrapper/runtimes/ubuntu-base-arm64/active}, the add-on slots,
 * and {@code linux-wrapper/state}), so the FULL export/import path exercises
 * the real exclusions, the manifest-first format, the atomic swap, and the
 * HOME/CUSTOM wholesale merge without a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GuestBackupTransferTest {

    private static final String APP_ID = "ubuntu-base-arm64";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** In-memory runtime state store: FULL restore stamps READY into it. */
    private static final class MemoryStateStore implements RuntimeStateStore {
        private final Map<String, RuntimeSnapshot> snapshots = new HashMap<>();

        @Override
        public RuntimeSnapshot load(String appId) {
            return snapshots.get(appId);
        }

        @Override
        public void save(RuntimeSnapshot snapshot) {
            snapshots.put(snapshot.getAppId(), snapshot);
        }

        @Override
        public void clear(String appId) {
            snapshots.remove(appId);
        }
    }

    private Path filesDir;
    private MemoryStateStore stateStore;
    private GuestBackupTransfer transfer;

    private void setUpTransfer() throws IOException {
        filesDir = temp.newFolder().toPath();
        stateStore = new MemoryStateStore();
        transfer = new GuestBackupTransfer(filesDir, stateStore, "0.4.0-test",
                () -> false, () -> -1L);
    }

    private Path rootfs() {
        return filesDir.resolve("linux-wrapper/runtimes/" + APP_ID + "/active");
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private void makeUsableRootfs() throws IOException {
        writeFile(rootfs().resolve("etc/os-release"), "NAME=Ubuntu\n");
        writeFile(rootfs().resolve("usr/bin/sh"), "sh");
    }

    private void makeFullGuest() throws IOException {
        makeUsableRootfs();
        writeFile(rootfs().resolve("root/.bashrc"), "export PS1=x\n");
        writeFile(rootfs().resolve("home/user/notes.txt"), "hello\n");
        writeFile(rootfs().resolve("var/lib/dpkg/status"), "installed\n");
        writeFile(rootfs().resolve("etc/hostname"), "nusadesk\n");
        // Exclusions: virtual dirs, and the workspace bind target's contents.
        writeFile(rootfs().resolve("proc/cpuinfo"), "cpu\n");
        writeFile(rootfs().resolve("tmp/scratch.bin"), "tmp\n");
        writeFile(rootfs().resolve("dev/null"), "dev\n");
        writeFile(rootfs().resolve("root/nusadesk/userfile.txt"), "workspace\n");
        // An activated add-on carries the guest ssh host key under etc/.
        writeFile(filesDir.resolve(
                "linux-wrapper/addons/guest-ssh-openssh/active/etc/ssh_host_ed25519_key"),
                "key-material\n");
        writeFile(filesDir.resolve("linux-wrapper/state/os-release"),
                "PRETTY_NAME=managed\n");
        // A previous runtime slot must never be archived.
        writeFile(filesDir.resolve(
                "linux-wrapper/runtimes/" + APP_ID + "/previous/marker.txt"), "old\n");
    }

    private static List<String> archiveEntryNames(byte[] archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static byte[] archiveFrom(Path filesDir, BackupSelection selection)
            throws IOException {
        GuestBackupTransfer exporter = new GuestBackupTransfer(
                filesDir, new MemoryStateStore(), "0.4.0-test", () -> false, () -> -1L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupResult result = exporter.exportBackup(selection, out, null);
        assertTrue("export failed: " + result.getDetail(), result.isReady());
        return out.toByteArray();
    }

    // ---- Export ----

    /**
     * The add-on overlay mount points are mode {@code 000} in a real guest, so
     * the walk must emit their entries and skip the subtree instead of failing
     * with an {@code io-failure} (their payloads travel as {@code addons/<id>}
     * trees). Regression for the export that died on {@code opt/lw-ssh}.
     */
    @Test
    public void exportSkipsAnUnreadableOverlayMountPointInsteadOfFailing() throws Exception {
        setUpTransfer();
        makeUsableRootfs();
        Path overlay = rootfs().resolve("opt/lw-ssh");
        writeFile(overlay.resolve("usr/bin/sshd"), "sshd\n");
        Files.setPosixFilePermissions(overlay,
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        // The device tool trees are mount points too: entries stay, the
        // device's own files never travel inside a guest backup.
        writeFile(rootfs().resolve("system/lib64/libc.so"), "device-libc\n");
        writeFile(rootfs().resolve("apex/com.android.runtime/bin/linker64"), "device-linker\n");
        try {
            List<String> names = archiveEntryNames(archiveFrom(filesDir, BackupSelection.full()));

            assertTrue("the mount point keeps its entry", names.contains("rootfs/opt/lw-ssh/"));
            assertFalse("its contents are never archived",
                    names.contains("rootfs/opt/lw-ssh/usr/bin/sshd"));
            assertTrue(names.contains("rootfs/system/"));
            assertTrue(names.contains("rootfs/apex/"));
            assertFalse("device files stay on the device",
                    names.contains("rootfs/system/lib64/libc.so"));
            assertFalse("device files stay on the device",
                    names.contains("rootfs/apex/com.android.runtime/bin/linker64"));
        } finally {
            Files.setPosixFilePermissions(overlay,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    public void fullExportArchivesRuntimeAddonsAndStateWithExclusions()
            throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.full());
        List<String> names = archiveEntryNames(archive);

        assertEquals("manifest.json", names.get(0));
        for (String expected : Arrays.asList(
                "rootfs/etc/os-release", "rootfs/usr/bin/sh",
                "rootfs/root/.bashrc", "rootfs/home/user/notes.txt",
                "rootfs/var/lib/dpkg/status",
                "addons/guest-ssh-openssh/etc/ssh_host_ed25519_key",
                "state/os-release")) {
            assertTrue("missing payload entry " + expected, names.contains(expected));
        }
        for (String excluded : Arrays.asList(
                "rootfs/proc/cpuinfo", "rootfs/tmp/scratch.bin", "rootfs/dev/null",
                "rootfs/root/nusadesk/userfile.txt")) {
            assertFalse(excluded + " must never be archived", names.contains(excluded));
        }
        // The mount point itself stays so a restore keeps the bind target.
        assertTrue(names.contains("rootfs/root/nusadesk/"));
        assertFalse("the previous runtime slot is never archived",
                names.contains("rootfs/previous/marker.txt"));
    }

    @Test
    public void homeExportContainsOnlyTheHomeRoots() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.home());
        List<String> names = archiveEntryNames(archive);
        for (String name : names) {
            if (name.equals("manifest.json")) {
                continue;
            }
            assertTrue("unexpected payload entry " + name,
                    name.startsWith("rootfs/root") || name.startsWith("rootfs/home"));
        }
        assertTrue(names.contains("rootfs/root/.bashrc"));
        assertTrue(names.contains("rootfs/home/user/notes.txt"));
    }

    @Test
    public void exportWithoutRuntimeFailsRuntimeRequired() throws Exception {
        setUpTransfer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupResult result = transfer.exportBackup(BackupSelection.full(), out, null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.RUNTIME_REQUIRED, result.getFailure());
    }

    // ---- FULL restore ----

    @Test
    public void fullRestoreBootstrapsAFreshInstall() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.full());

        // Restore onto a fresh files dir — the reinstall/new-phone case.
        Path freshDir = temp.newFolder().toPath();
        MemoryStateStore freshStore = new MemoryStateStore();
        GuestBackupTransfer restorer = new GuestBackupTransfer(
                freshDir, freshStore, "0.4.0-test", () -> false, () -> -1L);
        BackupResult result = restorer.importBackup(
                new ByteArrayInputStream(archive), null);

        assertTrue("restore failed: " + result.getDetail(), result.isReady());
        Path restored = freshDir.resolve("linux-wrapper/runtimes/" + APP_ID + "/active");
        assertTrue(Files.isRegularFile(restored.resolve("etc/os-release")));
        assertTrue(Files.isRegularFile(restored.resolve("usr/bin/sh")));
        assertTrue(Files.isRegularFile(restored.resolve("root/.bashrc")));
        assertTrue(Files.isRegularFile(restored.resolve("home/user/notes.txt")));
        assertFalse(Files.exists(restored.resolve("proc/cpuinfo")));
        assertFalse(Files.exists(restored.resolve("root/nusadesk/userfile.txt")));
        assertTrue(Files.isDirectory(restored.resolve("root/nusadesk")));
        assertTrue(Files.isRegularFile(freshDir.resolve(
                "linux-wrapper/addons/guest-ssh-openssh/active/etc/ssh_host_ed25519_key")));
        assertTrue(Files.isRegularFile(freshDir.resolve("linux-wrapper/state/os-release")));
        assertEquals(RuntimeState.READY, freshStore.load(APP_ID).getState());
    }

    @Test
    public void fullRestoreReplacesTheActiveRuntimeAtomically() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.full());

        // Replace a different installed runtime: the old tree must be gone
        // from active and parked at previous.
        writeFile(rootfs().resolve("etc/marker-old"), "old\n");
        writeFile(rootfs().resolve("root/oldfile"), "old\n");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);

        assertTrue("restore failed: " + result.getDetail(), result.isReady());
        assertTrue(Files.isRegularFile(rootfs().resolve("root/.bashrc")));
        assertFalse(Files.exists(rootfs().resolve("etc/marker-old")));
        Path previous = filesDir.resolve(
                "linux-wrapper/runtimes/" + APP_ID + "/previous");
        assertTrue(Files.isRegularFile(previous.resolve("etc/marker-old")));
    }

    // ---- HOME/CUSTOM merge ----

    @Test
    public void homeRestoreReplacesSelectedSubtreesWholesale() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.home());

        // An existing runtime whose /root and /etc differ from the backup.
        Path freshDir = temp.newFolder().toPath();
        GuestBackupTransfer restorer = new GuestBackupTransfer(
                freshDir, new MemoryStateStore(), "0.4.0-test", () -> false, () -> -1L);
        Path active = freshDir.resolve("linux-wrapper/runtimes/" + APP_ID + "/active");
        writeFile(active.resolve("etc/os-release"), "NAME=Ubuntu\n");
        writeFile(active.resolve("usr/bin/sh"), "sh");
        writeFile(active.resolve("etc/custom.conf"), "kept\n");
        writeFile(active.resolve("root/oldfile"), "must be replaced\n");
        writeFile(active.resolve("home/user/stale.txt"), "stale\n");

        BackupResult result = restorer.importBackup(
                new ByteArrayInputStream(archive), null);
        assertTrue("merge failed: " + result.getDetail(), result.isReady());

        // /root and /home are replaced wholesale; /etc outside the roots stays.
        assertTrue(Files.isRegularFile(active.resolve("root/.bashrc")));
        assertFalse("wholesale replace must drop the old /root file",
                Files.exists(active.resolve("root/oldfile")));
        assertTrue(Files.isRegularFile(active.resolve("home/user/notes.txt")));
        assertFalse(Files.exists(active.resolve("home/user/stale.txt")));
        assertTrue(Files.isRegularFile(active.resolve("etc/custom.conf")));
    }

    @Test
    public void customRestoreMergesOnlyTheSelectedRoots() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir,
                BackupSelection.custom(Arrays.asList("/etc", "/var/lib")));

        Path freshDir = temp.newFolder().toPath();
        GuestBackupTransfer restorer = new GuestBackupTransfer(
                freshDir, new MemoryStateStore(), "0.4.0-test", () -> false, () -> -1L);
        Path active = freshDir.resolve("linux-wrapper/runtimes/" + APP_ID + "/active");
        writeFile(active.resolve("etc/os-release"), "NAME=Ubuntu\n");
        writeFile(active.resolve("usr/bin/sh"), "sh");
        writeFile(active.resolve("etc/stale.conf"), "stale\n");
        writeFile(active.resolve("root/kept.txt"), "kept\n");

        BackupResult result = restorer.importBackup(
                new ByteArrayInputStream(archive), null);
        assertTrue("merge failed: " + result.getDetail(), result.isReady());
        assertTrue(Files.isRegularFile(active.resolve("etc/hostname")));
        assertFalse("stale /etc content must be replaced",
                Files.exists(active.resolve("etc/stale.conf")));
        assertTrue(Files.isRegularFile(active.resolve("var/lib/dpkg/status")));
        assertTrue("/root was not selected and must be untouched",
                Files.isRegularFile(active.resolve("root/kept.txt")));
    }

    @Test
    public void mergeRestoreWithoutRuntimeFailsRuntimeRequired() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.home());

        Path freshDir = temp.newFolder().toPath();
        GuestBackupTransfer restorer = new GuestBackupTransfer(
                freshDir, new MemoryStateStore(), "0.4.0-test", () -> false, () -> -1L);
        BackupResult result = restorer.importBackup(
                new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.RUNTIME_REQUIRED, result.getFailure());
    }

    // ---- Manifest and archive validation ----

    private static byte[] rawArchive(byte[] manifestJson, String... payloadEntries)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            if (manifestJson != null) {
                TarArchiveEntry manifest = new TarArchiveEntry(
                        "manifest.json", TarConstants.LF_NORMAL);
                manifest.setSize(manifestJson.length);
                tar.putArchiveEntry(manifest);
                tar.write(manifestJson);
                tar.closeArchiveEntry();
            }
            for (String name : payloadEntries) {
                TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_NORMAL);
                byte[] data = "x".getBytes(StandardCharsets.UTF_8);
                entry.setSize(data.length);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return out.toByteArray();
    }

    private static String manifestJson(String mode, String runtimeAppId,
            String rootsJson) {
        return "{\"formatVersion\":1,\"mode\":\"" + mode + "\","
                + "\"runtimeAppId\":\"" + runtimeAppId + "\","
                + "\"runtimeVersion\":\"24.04.5\",\"appVersion\":\"test\","
                + "\"createdAtEpochMs\":0,\"roots\":" + rootsJson + ","
                + "\"entries\":1,\"totalBytes\":1}";
    }

    @Test
    public void manifestMissingWhenFirstEntryIsNotTheManifest() throws Exception {
        setUpTransfer();
        byte[] archive = rawArchive(null, "rootfs/etc/os-release");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.MANIFEST_MISSING, result.getFailure());
    }

    @Test
    public void unsupportedFormatIsRejected() throws Exception {
        setUpTransfer();
        String badVersion = manifestJson("full", APP_ID, "[]")
                .replace("\"formatVersion\":1", "\"formatVersion\":2");
        byte[] archive = rawArchive(badVersion.getBytes(StandardCharsets.UTF_8),
                "rootfs/etc/os-release");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.UNSUPPORTED_FORMAT, result.getFailure());
    }

    @Test
    public void unknownRuntimeIdFailsRuntimeMismatch() throws Exception {
        setUpTransfer();
        byte[] archive = rawArchive(
                manifestJson("full", "someone-elses-os", "[]")
                        .getBytes(StandardCharsets.UTF_8),
                "rootfs/etc/os-release");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.RUNTIME_MISMATCH, result.getFailure());
    }

    @Test
    public void traversalEntriesFailUnsafeArchive() throws Exception {
        setUpTransfer();
        makeUsableRootfs();
        byte[] archive = rawArchive(
                manifestJson("home", APP_ID, "[\"/root\"]")
                        .getBytes(StandardCharsets.UTF_8),
                "rootfs/root/../../escape.txt");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.UNSAFE_ARCHIVE, result.getFailure());
        assertFalse(Files.exists(filesDir.resolve("escape.txt")));
    }

    @Test
    public void escapingSymlinkFailsUnsafeArchive() throws Exception {
        setUpTransfer();
        makeUsableRootfs();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(out))) {
            byte[] manifestJson = manifestJson("home", APP_ID, "[\"/root\"]")
                    .getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry manifest = new TarArchiveEntry(
                    "manifest.json", TarConstants.LF_NORMAL);
            manifest.setSize(manifestJson.length);
            tar.putArchiveEntry(manifest);
            tar.write(manifestJson);
            tar.closeArchiveEntry();
            TarArchiveEntry link = new TarArchiveEntry(
                    "rootfs/root/evil", TarConstants.LF_SYMLINK);
            link.setLinkName("../../../etc/passwd");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            tar.finish();
        }
        BackupResult result = transfer.importBackup(
                new ByteArrayInputStream(out.toByteArray()), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.UNSAFE_ARCHIVE, result.getFailure());
    }

    @Test
    public void fullArchiveWithoutRootfsFailsUnsafeArchive() throws Exception {
        setUpTransfer();
        byte[] archive = rawArchive(
                manifestJson("full", APP_ID, "[]").getBytes(StandardCharsets.UTF_8),
                "state/os-release");
        BackupResult result = transfer.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.UNSAFE_ARCHIVE, result.getFailure());
    }

    // ---- Typed engine guards ----

    @Test
    public void importWhileSessionLiveFailsBusy() throws Exception {
        setUpTransfer();
        GuestBackupTransfer guarded = new GuestBackupTransfer(
                filesDir, stateStore, "0.4.0-test", () -> true, () -> -1L);
        BackupResult result = guarded.importBackup(
                new ByteArrayInputStream(new byte[0]), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.BUSY, result.getFailure());
    }

    @Test
    public void importWithoutFreeSpaceFailsStorageFull() throws Exception {
        setUpTransfer();
        makeFullGuest();
        byte[] archive = archiveFrom(filesDir, BackupSelection.full());
        GuestBackupTransfer cramped = new GuestBackupTransfer(
                filesDir, stateStore, "0.4.0-test", () -> false, () -> 1L);
        BackupResult result = cramped.importBackup(new ByteArrayInputStream(archive), null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.STORAGE_FULL, result.getFailure());
    }

    @Test
    public void unreadableStreamFailsIoFailure() throws Exception {
        setUpTransfer();
        BackupResult result = transfer.importBackup(
                new ByteArrayInputStream("this is not a backup".getBytes(StandardCharsets.UTF_8)),
                null);
        assertFalse(result.isReady());
        assertEquals(BackupFailure.IO_FAILURE, result.getFailure());
    }

    @Test
    public void concurrentImportWhileExportingFailsBusy() throws Exception {
        setUpTransfer();
        makeFullGuest();
        // A payload large enough to flush the buffered stream, so the export
        // thread provably sits inside the engine when the import is tried.
        byte[] bulk = new byte[64 * 1024];
        Arrays.fill(bulk, (byte) 'a');
        Files.createDirectories(rootfs().resolve("root"));
        Files.write(rootfs().resolve("root/big.bin"), bulk);

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream blocking = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writeStarted.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AtomicReference<BackupResult> exportResult = new AtomicReference<>();
        Thread exporter = new Thread(() -> exportResult.set(
                transfer.exportBackup(BackupSelection.full(), blocking, null)));
        exporter.start();
        assertTrue("the export never reached the stream",
                writeStarted.await(15, TimeUnit.SECONDS));
        try {
            BackupResult result = transfer.importBackup(
                    new ByteArrayInputStream(new byte[0]), null);
            assertFalse(result.isReady());
            assertEquals(BackupFailure.BUSY, result.getFailure());
        } finally {
            release.countDown();
        }
        exporter.join(15_000);
        assertTrue(exportResult.get().isReady());
    }
}
