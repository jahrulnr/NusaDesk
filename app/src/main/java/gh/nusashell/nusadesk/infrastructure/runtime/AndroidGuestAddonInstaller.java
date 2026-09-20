package gh.nusashell.nusadesk.infrastructure.runtime;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.StatFs;

import gh.nusashell.nusadesk.application.runtime.GuestAddonInstallUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationUseCase;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.PayloadArtifact;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLauncher;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Installs a curated guest add-on profile as a private overlay directory
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
 *   <li>Copy each pinned {@link VendoredFile} from the packaged assets into
 *       the staging tree, verifying its SHA-256 — a packaged file is verified
 *       exactly like a downloaded one, so a tampered APK asset fails
 *       closed.</li>
 *   <li>Validate the overlay: the declared entrypoint must exist and be an
 *       AArch64 ELF, every declared {@code requiredFiles} member must resolve,
 *       and the {@code requiredRootfsTools} the profile's guest setup needs
 *       must be present in the active rootfs.</li>
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
public final class AndroidGuestAddonInstaller implements GuestAddonInstallUseCase {
    private static final long EXTRA_STORAGE_BYTES = 16L * 1024L * 1024L;
    private static final String MANIFEST_NAME = "lw-addon-manifest";

    private final Context context;
    private final DebDecoder debDecoder;

    public AndroidGuestAddonInstaller(Context context) {
        this(context, null);
    }

    /** Test seam: inject a decoder so installs run without a real PRoot. */
    AndroidGuestAddonInstaller(Context context, DebDecoder debDecoder) {
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

    /**
     * Whether the overlay currently provides a usable payload: the entrypoint
     * plus every vendored file, so an app upgrade that adds vendored members
     * re-installs instead of running a stale partial overlay.
     */
    public static boolean isInstalled(Path filesDir, GuestAddonPayloadProfile profile) {
        Path active = activeOverlayDir(filesDir, profile.getAddonId());
        if (active == null
                || !Files.isRegularFile(active.resolve(profile.getEntrypoint()))) {
            return false;
        }
        for (VendoredFile vendored : profile.getVendoredFiles()) {
            if (!Files.isRegularFile(active.resolve(vendored.getOverlayPath()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void install(
            GuestAddonPayloadProfile profile,
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
                            "install the curated runtime before the " + profile.getDisplayName()
                                    + " add-on");
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
                        "Installing " + profile.getDisplayName() + " payload files", listener);
                installVendoredFiles(staging, profile);

                publish(profile, RuntimeState.VERIFYING,
                        "Validating " + profile.getDisplayName(), listener);
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

    private void checkGuestAbi(GuestAddonPayloadProfile profile)
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

    private void checkStorage(GuestAddonPayloadProfile profile)
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
     * Copies every packaged vendored file into the staging overlay at its
     * declared guest-relative path, then verifies the written bytes against
     * the pinned digest. A packaged asset is trusted only after the same
     * digest check a downloaded artifact gets; mode 0755 is applied to
     * executables so guest {@code execve} resolves them under PRoot.
     */
    private void installVendoredFiles(Path staging, GuestAddonPayloadProfile profile)
            throws IOException, RuntimeInstallationException {
        AssetManager assets = context.getAssets();
        for (VendoredFile file : profile.getVendoredFiles()) {
            Path target = staging.resolve(file.getOverlayPath());
            if (!target.normalize().startsWith(staging)) {
                throw new RuntimeInstallationException(
                        "vendored overlay path escapes staging: " + file.getOverlayPath());
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = assets.open(file.getAssetPath());
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                throw new RuntimeInstallationException(
                        "packaged asset is missing or unreadable: " + file.getAssetPath(), e);
            }
            PayloadIo.verifyDigest(target, file.getSha256());
            applyMode(target, file.isExecutable());
        }
    }

    private static void applyMode(Path target, boolean executable) throws IOException {
        Set<PosixFilePermission> mode = executable
                ? PosixFilePermissions.fromString("rwxr-xr-x")
                : PosixFilePermissions.fromString("rw-r--r--");
        try {
            Files.setPosixFilePermissions(target, mode);
        } catch (UnsupportedOperationException e) {
            // App-private files are already owner-only; a filesystem without
            // POSIX modes (FAT/FUSE) still gets a readable, executable-by-
            // convention file — PRoot honours the mode bits it can read.
        }
    }

    /**
     * Post-extract validation: the declared entrypoint must be an AArch64 ELF
     * (for a script-driven add-on the ELF is the interpreter the script execs
     * through), every declared {@code requiredFiles} member must resolve
     * inside the overlay, and every declared {@code requiredRootfsTools}
     * member must exist in the active rootfs.
     */
    private void validateOverlay(Path staging, Path rootfs, GuestAddonPayloadProfile profile)
            throws IOException, RuntimeInstallationException {
        Path entrypoint = staging.resolve(profile.getEntrypoint());
        if (!Files.isRegularFile(entrypoint)) {
            throw new RuntimeInstallationException(
                    profile.getDisplayName() + " payload is missing " + profile.getEntrypoint());
        }
        ElfAbi.requireAarch64(entrypoint, profile.getDisplayName() + " entrypoint");
        for (String required : profile.getRequiredFiles()) {
            if (!Files.exists(staging.resolve(required))) {
                throw new RuntimeInstallationException(
                        profile.getDisplayName() + " payload is missing " + required);
            }
        }
        for (String tool : profile.getRequiredRootfsTools()) {
            if (!Files.isRegularFile(rootfs.resolve(tool))) {
                throw new RuntimeInstallationException(
                        "installed rootfs lacks the tool required by "
                                + profile.getDisplayName() + ": " + tool);
            }
        }
    }

    /**
     * Record provenance inside the overlay: which pinned artifacts and
     * vendored files produced it, with their digests. Plain text,
     * guest-visible, no secrets.
     */
    private void writeManifest(Path staging, GuestAddonPayloadProfile profile)
            throws IOException {
        StringBuilder manifest = new StringBuilder();
        manifest.append("addon=").append(profile.getAddonId()).append('\n');
        manifest.append("version=").append(profile.getVersion()).append('\n');
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            manifest.append("artifact=").append(artifact.getArtifactId())
                    .append(' ').append(artifact.getPackageVersion())
                    .append(' ').append(artifact.getSha256()).append('\n');
        }
        for (VendoredFile vendored : profile.getVendoredFiles()) {
            manifest.append("vendored=").append(vendored.getOverlayPath())
                    .append(' ').append(vendored.getSha256()).append('\n');
        }
        Files.write(staging.resolve(MANIFEST_NAME),
                manifest.toString().getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW);
    }

    private void activate(Path staging, Path active, Path previous)
            throws IOException, RuntimeInstallationException {
        RuntimePayloadSupport.activatePayload(staging, active, previous, "add-on payload");
    }

    private void publish(
            GuestAddonPayloadProfile profile,
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
            GuestAddonPayloadProfile profile,
            String detail,
            RuntimeInstallationUseCase.ProgressListener listener) {
        publish(profile, RuntimeState.FAILED, detail, listener);
    }
}
