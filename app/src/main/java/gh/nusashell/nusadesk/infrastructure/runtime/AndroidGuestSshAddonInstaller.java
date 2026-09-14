package gh.nusashell.nusadesk.infrastructure.runtime;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import gh.nusashell.nusadesk.application.runtime.GuestSshAddonInstallUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationUseCase;
import gh.nusashell.nusadesk.domain.runtime.GuestSshPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.PayloadArtifact;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLauncher;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Installs the curated guest-SSH add-on profile as a private overlay directory
 * activated alongside — never inside — the already-active rootfs.
 *
 * <p>Pipeline (all under {@link PayloadIo#INSTALL_LOCK}, shared with the
 * rootfs installer):</p>
 * <ol>
 *   <li>Require the curated rootfs to be installed — the add-on is an overlay
 *       for it, not a runtime.</li>
 *   <li>Check guest ABI and storage headroom against the summed catalog
 *       sizes.</li>
 *   <li>Download each pinned {@code .deb} to a {@code .part} file, verify its
 *       cataloged SHA-256, then decode it into a versioned {@code .staging}
 *       directory through {@link GuestDebExtractor}: the guest's own verified
 *       {@code dpkg-deb --fsys-tarfile} emits the payload tar under PRoot, and
 *       {@link PayloadIo#extractTar} applies the same traversal, file-type,
 *       symlink, and size rules as the rootfs path.</li>
 *   <li>Validate the overlay: the declared entrypoint must exist and be an
 *       AArch64 ELF, and the rootfs tools the setup step needs
 *       ({@code perl}, {@code grep}, {@code chmod}) must be present.</li>
 *   <li>Write a provenance manifest into the overlay, then activate
 *       atomically (staging → active, active → previous), leaving the active
 *       rootfs entirely untouched.</li>
 * </ol>
 *
 * <p>No maintainer scripts run and no downloaded binary executes during
 * install. The single guest execution is {@code dpkg-deb} from the already
 * verified, already-activated rootfs used strictly as a decoder — the add-on
 * payload itself stays data until PRoot launches it at session start.</p>
 */
public final class AndroidGuestSshAddonInstaller implements GuestSshAddonInstallUseCase {
    private static final long EXTRA_STORAGE_BYTES = 16L * 1024L * 1024L;
    private static final String MANIFEST_NAME = "lw-addon-manifest";

    private final Context context;
    private final DebDecoder debDecoder;

    public AndroidGuestSshAddonInstaller(Context context) {
        this(context, null);
    }

    /** Test seam: inject a decoder so installs run without a real PRoot. */
    AndroidGuestSshAddonInstaller(Context context, DebDecoder debDecoder) {
        this.context = context.getApplicationContext();
        this.debDecoder = debDecoder;
    }

    /** Decodes one verified {@code .deb} into {@code staging}. */
    interface DebDecoder {
        void extract(Path deb, Path staging, long uncompressedCap)
                throws IOException, RuntimeInstallationException;
    }

    /** Host path of the activated overlay for an add-on id (null-safe). */
    public static Path activeOverlayDir(Path filesDir, String addonId) {
        return ProotPaths.activeAddonPath(filesDir, addonId);
    }

    /** Whether the overlay currently provides a usable daemon entrypoint. */
    public static boolean isInstalled(Path filesDir, GuestSshPayloadProfile profile) {
        Path active = activeOverlayDir(filesDir, profile.getAddonId());
        return active != null
                && Files.isRegularFile(active.resolve(profile.getEntrypoint()));
    }

    @Override
    public void install(
            GuestSshPayloadProfile profile,
            String runtimeAppId,
            RuntimeInstallationUseCase.ProgressListener listener)
            throws RuntimeInstallationException {
        synchronized (PayloadIo.INSTALL_LOCK) {
            Path filesDir = context.getFilesDir().toPath();
            Path rootfs = ProotPaths.activeRootfsPath(filesDir, runtimeAppId);
            Path root = filesDir.resolve(ProotPaths.RUNTIME_ROOT);
            Path downloads = root.resolve("downloads");
            Path versions = root.resolve(ProotPaths.ADDONS_DIR).resolve(profile.getAddonId());
            Path staging = versions.resolve("." + profile.getVersion() + ".staging");
            Path active = versions.resolve(ProotPaths.ACTIVE_DIR);
            Path previous = versions.resolve("previous");

            try {
                if (!Files.isDirectory(rootfs)) {
                    throw new RuntimeInstallationException(
                            "install the curated runtime before the guest SSH payload");
                }
                if (!Files.isRegularFile(rootfs.resolve(GuestDebExtractor.DPKG_DEB))) {
                    throw new RuntimeInstallationException(
                            "installed rootfs lacks the deb decoder: " + GuestDebExtractor.DPKG_DEB);
                }
                DebDecoder decoder = debDecoder != null
                        ? debDecoder
                        : new GuestDebExtractor(new ProotLauncher(context), runtimeAppId)::extract;
                checkGuestAbi(profile);
                checkStorage(profile);
                Files.createDirectories(downloads);
                Files.createDirectories(versions);
                PayloadIo.deleteRecursively(staging);
                Files.createDirectories(staging);

                int index = 0;
                int count = profile.getArtifacts().size();
                for (PayloadArtifact artifact : profile.getArtifacts()) {
                    index++;
                    Path partial = downloads.resolve(artifact.getArtifactId() + ".part");
                    Path deb = downloads.resolve(artifact.getArtifactId() + ".deb");
                    try {
                        PayloadIo.deleteRecursively(partial);
                        PayloadIo.deleteRecursively(deb);
                        String label = profile.getDisplayName() + " " + index + "/" + count;
                        publish(profile, RuntimeState.DOWNLOADING,
                                "Downloading " + label, listener);
                        PayloadIo.download(
                                artifact.getDownloadUrl(),
                                artifact.getCompressedBytes(),
                                partial,
                                percent -> publish(profile, RuntimeState.DOWNLOADING,
                                        "Downloading " + label + " · " + percent + "%",
                                        listener));
                        PayloadIo.moveAtomically(partial, deb);

                        publish(profile, RuntimeState.VERIFYING,
                                "Verifying " + label, listener);
                        PayloadIo.verifyDigest(deb, artifact.getSha256());

                        publish(profile, RuntimeState.EXTRACTING,
                                "Unpacking " + label, listener);
                        decoder.extract(
                                deb, staging, artifact.getUncompressedBytes());
                    } finally {
                        PayloadIo.deleteRecursively(partial);
                        PayloadIo.deleteRecursively(deb);
                    }
                }

                publish(profile, RuntimeState.VERIFYING,
                        "Validating the SSH payload", listener);
                validateOverlay(staging, rootfs, profile);
                writeManifest(staging, profile);
                activate(staging, active, previous);

                publish(profile, RuntimeState.READY,
                        profile.getDisplayName() + " " + profile.getVersion() + " installed",
                        listener);
            } catch (RuntimeInstallationException exception) {
                PayloadIo.deleteRecursively(staging);
                publishFailure(profile, exception.getMessage(), listener);
                throw exception;
            } catch (IOException | RuntimeException | LinkageError exception) {
                // LinkageError is included deliberately: a mis-packaged native
                // decoder (e.g. a missing zstd jniLib) must surface as an
                // honest FAILED state, not kill the app process mid-staging.
                PayloadIo.deleteRecursively(staging);
                String message = PayloadIo.normalizeFailureMessage(exception);
                RuntimeInstallationException wrapped =
                        new RuntimeInstallationException(message, exception);
                publishFailure(profile, message, listener);
                throw wrapped;
            }
        }
    }

    private void checkGuestAbi(GuestSshPayloadProfile profile)
            throws RuntimeInstallationException {
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            if (!"linux/arm64".equals(artifact.getGuestAbi())) {
                throw new RuntimeInstallationException(
                        "unsupported guest ABI: " + artifact.getGuestAbi());
            }
        }
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return;
            }
        }
        throw new RuntimeInstallationException(
                "this device does not expose the required arm64-v8a ABI");
    }

    private void checkStorage(GuestSshPayloadProfile profile)
            throws RuntimeInstallationException {
        StatFs statFs = new StatFs(context.getFilesDir().getPath());
        long required = profile.totalCompressedBytes()
                + (profile.totalUncompressedBytes() * 2L)
                + EXTRA_STORAGE_BYTES;
        if (statFs.getAvailableBytes() < required) {
            throw new RuntimeInstallationException(
                    "Insufficient storage. Free at least " + PayloadIo.formatMiB(required)
                            + " MiB and retry.");
        }
    }

    /**
     * Post-extract validation: the declared daemon entrypoint must be an
     * AArch64 ELF, and the rootfs must carry the tools the guest setup step
     * uses ({@code perl} for the shadow edit, {@code grep}/{@code chmod} for
     * account provisioning).
     */
    private void validateOverlay(Path staging, Path rootfs, GuestSshPayloadProfile profile)
            throws IOException, RuntimeInstallationException {
        Path entrypoint = staging.resolve(profile.getEntrypoint());
        if (!Files.isRegularFile(entrypoint)) {
            throw new RuntimeInstallationException(
                    "SSH payload is missing " + profile.getEntrypoint());
        }
        ElfAbi.requireAarch64(entrypoint, "guest SSH daemon");
        for (String tool : new String[] { "usr/bin/perl", "usr/bin/grep", "usr/bin/chmod" }) {
            if (!Files.isRegularFile(rootfs.resolve(tool))) {
                throw new RuntimeInstallationException(
                        "installed rootfs lacks the tool required by guest SSH setup: " + tool);
            }
        }
    }

    /**
     * Record provenance inside the overlay: which pinned artifacts produced
     * it, with their digests. Plain text, guest-visible, no secrets.
     */
    private void writeManifest(Path staging, GuestSshPayloadProfile profile)
            throws IOException {
        StringBuilder manifest = new StringBuilder();
        manifest.append("addon=").append(profile.getAddonId()).append('\n');
        manifest.append("version=").append(profile.getVersion()).append('\n');
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            manifest.append("artifact=").append(artifact.getArtifactId())
                    .append(' ').append(artifact.getPackageVersion())
                    .append(' ').append(artifact.getSha256()).append('\n');
        }
        Files.write(staging.resolve(MANIFEST_NAME),
                manifest.toString().getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW);
    }

    private void activate(Path staging, Path active, Path previous)
            throws IOException, RuntimeInstallationException {
        boolean hadActive = Files.exists(active, LinkOption.NOFOLLOW_LINKS);
        if (hadActive) {
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
                    "could not activate the verified SSH payload", exception);
        }
    }

    private void publish(
            GuestSshPayloadProfile profile,
            RuntimeState state,
            String detail,
            RuntimeInstallationUseCase.ProgressListener listener) {
        // Add-on snapshots are reported to the listener only, not persisted:
        // presence is derived from disk (the activated entrypoint), and the
        // runtime state store's READY check would misread an add-on key.
        listener.onSnapshot(new RuntimeSnapshot(
                profile.getAddonId(), state, detail, 0, System.currentTimeMillis()));
    }

    private void publishFailure(
            GuestSshPayloadProfile profile,
            String detail,
            RuntimeInstallationUseCase.ProgressListener listener) {
        publish(profile, RuntimeState.FAILED, detail, listener);
    }
}
