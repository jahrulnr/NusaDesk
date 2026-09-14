package gh.nusashell.nusadesk.infrastructure.runtime;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Downloads and stages the first curated Ubuntu base profile.
 *
 * <p>This adapter installs a verified rootfs as data. It deliberately does not
 * execute anything; execution through a future Android bridge is a separate
 * research boundary. The download/verify/extract/activate pipeline itself is
 * shared with the curated add-on installers through {@link PayloadIo}.</p>
 */
public final class AndroidRuntimeInstaller implements RuntimeInstallationUseCase {
    private static final long EXTRA_STORAGE_BYTES = 32L * 1024L * 1024L;
    private static final String ROOT_DIRECTORY = "linux-wrapper";

    private final Context context;
    private final RuntimeStateStore stateStore;

    public AndroidRuntimeInstaller(Context context, RuntimeStateStore stateStore) {
        this.context = context.getApplicationContext();
        this.stateStore = stateStore;
    }

    @Override
    public void install(RuntimeCatalogEntry entry, ProgressListener listener)
            throws RuntimeInstallationException {
        synchronized (PayloadIo.INSTALL_LOCK) {
            Path root = context.getFilesDir().toPath().resolve(ROOT_DIRECTORY);
        Path downloads = root.resolve("downloads");
        Path versions = root.resolve("runtimes").resolve(entry.getAppId());
        Path staging = versions.resolve("." + entry.getVersion() + ".staging");
        Path active = versions.resolve("active");
        Path previous = versions.resolve("previous");
        Path archive = downloads.resolve(entry.getAppId() + "-" + entry.getVersion() + ".tar.gz");
        Path partial = downloads.resolve(entry.getAppId() + "-" + entry.getVersion() + ".part");

        try {
            checkGuestAbi(entry);
            checkStorage(entry);
            Files.createDirectories(downloads);
            Files.createDirectories(versions);
            PayloadIo.deleteRecursively(staging);
            PayloadIo.deleteRecursively(partial);
            PayloadIo.deleteRecursively(archive);

            publish(entry, RuntimeState.DOWNLOADING, "Downloading " + entry.getDisplayName(), listener);
            download(entry, partial, listener);
            PayloadIo.moveAtomically(partial, archive);

            publish(entry, RuntimeState.VERIFYING, "Verifying the curated payload", listener);
            verifyDigest(archive, entry.getSha256());

            publish(entry, RuntimeState.EXTRACTING, "Preparing the private runtime files", listener);
            extractSafely(archive, staging, entry);
            validateRootfs(staging);
            activate(staging, active, previous);

            publish(entry, RuntimeState.READY,
                    entry.getDisplayName() + " " + entry.getVersion() + " installed",
                    listener);
        } catch (RuntimeInstallationException exception) {
            PayloadIo.deleteRecursively(staging);
            publishFailure(entry, exception.getMessage(), listener);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            PayloadIo.deleteRecursively(staging);
            String message = PayloadIo.normalizeFailureMessage(exception);
            RuntimeInstallationException wrapped = new RuntimeInstallationException(message, exception);
            publishFailure(entry, message, listener);
            throw wrapped;
        } finally {
            PayloadIo.deleteRecursively(partial);
            PayloadIo.deleteRecursively(archive);
        }
        }
    }

    private void checkGuestAbi(RuntimeCatalogEntry entry)
            throws RuntimeInstallationException {
        if (!"linux/arm64".equals(entry.getGuestAbi())) {
            throw new RuntimeInstallationException(
                    "unsupported guest ABI: " + entry.getGuestAbi());
        }
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return;
            }
        }
        throw new RuntimeInstallationException(
                "this device does not expose the required arm64-v8a ABI");
    }

    private void checkStorage(RuntimeCatalogEntry entry)
            throws RuntimeInstallationException {
        StatFs statFs = new StatFs(context.getFilesDir().getPath());
        long required = (entry.getCompressedBytes() * 1L)
                + (entry.getUncompressedBytes() * 2L)
                + EXTRA_STORAGE_BYTES;
        if (statFs.getAvailableBytes() < required) {
            throw new RuntimeInstallationException(
                    "Insufficient storage. Free at least " + PayloadIo.formatMiB(required)
                            + " MiB and retry.");
        }
    }

    private void download(
            RuntimeCatalogEntry entry,
            Path partial,
            ProgressListener listener)
            throws IOException, RuntimeInstallationException {
        PayloadIo.download(
                entry.getDownloadUrl(),
                entry.getCompressedBytes(),
                partial,
                percent -> publish(entry, RuntimeState.DOWNLOADING,
                        "Downloading " + entry.getDisplayName() + " · " + percent + "%",
                        listener));
    }

    private void verifyDigest(Path archive, String expected)
            throws IOException, RuntimeInstallationException {
        PayloadIo.verifyDigest(archive, expected);
    }

    private void extractSafely(Path archive, Path staging, RuntimeCatalogEntry entry)
            throws IOException, RuntimeInstallationException {
        try (InputStream fileInput = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(fileInput);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            PayloadIo.extractTar(tar, staging, entry.getUncompressedBytes());
        }
    }

    private void validateRootfs(Path staging) throws RuntimeInstallationException {
        if (!Files.isRegularFile(staging.resolve("etc/os-release"))) {
            throw new RuntimeInstallationException("payload is missing etc/os-release");
        }
        if (!Files.isRegularFile(staging.resolve("usr/bin/sh"))) {
            throw new RuntimeInstallationException("payload is missing usr/bin/sh");
        }
    }

    private void activate(Path staging, Path active, Path previous)
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
            throw new RuntimeInstallationException("could not activate the verified runtime", exception);
        }
    }

    private void publish(
            RuntimeCatalogEntry entry,
            RuntimeState state,
            String detail,
            ProgressListener listener) {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(
                entry.getAppId(), state, detail, 0, System.currentTimeMillis());
        stateStore.save(snapshot);
        listener.onSnapshot(snapshot);
    }

    private void publishFailure(
            RuntimeCatalogEntry entry,
            String detail,
            ProgressListener listener) {
        publish(entry, RuntimeState.FAILED, detail, listener);
    }
}
