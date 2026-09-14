package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLaunchException;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLaunchSpec;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLauncher;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Decodes a verified {@code .deb} by piping the guest's own
 * {@code dpkg-deb --fsys-tarfile} output through {@link PayloadIo#extractTar}.
 *
 * <p>Why a guest-side decoder: Noble {@code data.tar} members are zstd, and no
 * zstd decoder survives on Android — zstd-jni's embedded {@code .so} is a glibc
 * binary (bionic cannot resolve {@code libpthread.so.0}), and the pure-Java
 * aircompressor requires {@code sun.misc.Unsafe} which ART does not provide
 * (both verified by on-device failure). The active Ubuntu Base rootfs already
 * ships {@code /usr/bin/dpkg-deb} plus {@code libzstd.so.1}, so the verified
 * guest toolchain decodes its own packages via the curated PRoot bridge. Only
 * the decoded tar stream crosses the boundary: every file-level safety rule
 * (traversal, file types, symlinks, size caps) is still enforced by
 * {@link PayloadIo#extractTar} in Java. No maintainer scripts run; dpkg-deb is
 * used strictly as a decoder, never as a package manager.</p>
 */
final class GuestDebExtractor {
    /** Fixed guest mountpoint the verified deb's directory is bound to. */
    static final String GUEST_INPUT_DIR = "/lw-in";
    /** Rootfs tool that performs the decode (verified present by the caller). */
    static final String DPKG_DEB = "usr/bin/dpkg-deb";
    private static final long DECODE_TIMEOUT_MS = 120_000L;
    private static final int MAX_STDERR_BYTES = 8 * 1024;

    private final ProotLauncher launcher;
    private final String runtimeAppId;

    GuestDebExtractor(ProotLauncher launcher, String runtimeAppId) {
        this.launcher = launcher;
        this.runtimeAppId = runtimeAppId;
    }

    /**
     * Decode {@code deb} into {@code staging}: bind the deb's directory at
     * {@value #GUEST_INPUT_DIR}, run {@code dpkg-deb --fsys-tarfile} under
     * PRoot, and stream stdout through {@link PayloadIo#extractTar}.
     */
    void extract(Path deb, Path staging, long uncompressedCap)
            throws IOException, RuntimeInstallationException {
        String guestDeb = GUEST_INPUT_DIR + "/" + deb.getFileName();
        ProotLaunchSpec spec;
        try {
            spec = launcher.buildSpec(
                    runtimeAppId,
                    Arrays.asList("/" + DPKG_DEB, "--fsys-tarfile", guestDeb),
                    Collections.singletonList(
                            ProotBindMount.of(deb.getParent().toString(), GUEST_INPUT_DIR)),
                    Collections.emptyMap());
        } catch (ProotLaunchException exception) {
            throw new RuntimeInstallationException(
                    "could not prepare the guest deb decoder", exception);
        }
        Process process;
        try {
            process = launcher.launchProcess(spec);
        } catch (ProotLaunchException exception) {
            throw new RuntimeInstallationException(
                    "could not start the guest deb decoder", exception);
        }
        extractFromProcess(process, staging, uncompressedCap);
    }

    /**
     * Stream a running decoder process's stdout tar into {@code staging} and
     * require a clean exit. Package-visible for unit tests with fake
     * processes. On any failure the process tree is destroyed forcibly.
     */
    static void extractFromProcess(Process process, Path staging, long uncompressedCap)
            throws IOException, RuntimeInstallationException {
        extractFromProcess(process, staging, uncompressedCap, DECODE_TIMEOUT_MS);
    }

    /** Package-visible variant with an explicit decode timeout for tests. */
    static void extractFromProcess(
            Process process, Path staging, long uncompressedCap, long timeoutMs)
            throws IOException, RuntimeInstallationException {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread drainer = drainBounded(process.getErrorStream(), stderr);
        try {
            try (TarArchiveInputStream tar =
                    new TarArchiveInputStream(process.getInputStream())) {
                PayloadIo.extractTar(tar, staging, uncompressedCap);
            }
        } catch (IOException | RuntimeInstallationException | RuntimeException failure) {
            process.destroyForcibly();
            throw failure;
        }
        int exitCode = awaitExit(process, timeoutMs);
        joinQuietly(drainer);
        if (exitCode != 0) {
            throw new RuntimeInstallationException(
                    "guest deb decoder failed (exit " + exitCode + "): "
                            + stderr.toString(StandardCharsets.UTF_8.name()).trim());
        }
    }

    private static int awaitExit(Process process, long timeoutMs)
            throws IOException, RuntimeInstallationException {
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RuntimeInstallationException(
                        "guest deb decoder did not finish in time");
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while waiting for the guest deb decoder");
        }
    }

    /** Drain stderr on a daemon thread so the child cannot block on a full pipe. */
    private static Thread drainBounded(InputStream source, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (sink.size() < MAX_STDERR_BYTES) {
                        sink.write(buffer, 0, Math.min(read, MAX_STDERR_BYTES - sink.size()));
                    }
                }
            } catch (IOException ignored) {
                // Best-effort diagnostics only.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
