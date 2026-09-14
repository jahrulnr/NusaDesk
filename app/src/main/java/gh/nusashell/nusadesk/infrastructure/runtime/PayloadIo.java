package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared payload pipeline for the curated runtime and its add-on profiles:
 * bounded HTTPS download, SHA-256 verification, traversal-safe tar extraction,
 * atomic activation, and best-effort cleanup.
 *
 * <p>All security invariants of the download path live here so the rootfs
 * installer and the guest-SSH add-on installer enforce them identically:
 * no redirects, exact size and digest, no absolute/escaping paths, no device
 * nodes or unexpected file types, capped entry count and extracted bytes,
 * symlink targets confined to the staging root, and activation by atomic
 * rename only after verification.</p>
 */
final class PayloadIo {
    static final int MAX_ARCHIVE_ENTRIES = 20_000;
    /** Serializes rootfs and add-on installs so their staging trees never race. */
    static final Object INSTALL_LOCK = new Object();

    private PayloadIo() {
    }

    /** Download progress sink; receives an integer percent 0–100. */
    interface ProgressSink {
        void onPercent(int percent);
    }

    static String normalizeFailureMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String detail = current.getMessage();
            if (detail != null) {
                String lower = detail.toLowerCase(Locale.ROOT);
                if (lower.contains("no space") || lower.contains("enospc")) {
                    return "Insufficient storage during installation. Free space and retry.";
                }
            }
            current = current.getCause();
        }
        return exception.getMessage() == null ? "installation failed" : exception.getMessage();
    }

    /**
     * Downloads {@code url} to {@code partial}: HTTPS only, no redirect
     * following, exact byte count, and a hard cap at the catalog size.
     */
    static void download(
            String url,
            long expectedBytes,
            Path partial,
            ProgressSink progress)
            throws IOException, RuntimeInstallationException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new RuntimeInstallationException(
                    "catalog URL must use HTTPS: " + url);
        }
        URLConnection connection = uri.toURL().openConnection();
        if (!(connection instanceof HttpURLConnection)) {
            throw new RuntimeInstallationException("catalog URL is not an HTTPS HTTP resource");
        }
        HttpURLConnection http = (HttpURLConnection) connection;
        http.setConnectTimeout(20_000);
        http.setReadTimeout(30_000);
        http.setInstanceFollowRedirects(false);
        http.setRequestProperty("User-Agent", "LinuxWrapperAndroidBase/0.1");
        try {
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new RuntimeInstallationException(
                        "payload download returned HTTP " + http.getResponseCode());
            }
            long contentLength = http.getContentLengthLong();
            if (contentLength > 0 && contentLength != expectedBytes) {
                throw new RuntimeInstallationException("payload size does not match the catalog");
            }
            try (InputStream input = new BufferedInputStream(http.getInputStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                         partial,
                         StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE))) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int lastPercent = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > expectedBytes) {
                        throw new RuntimeInstallationException("payload exceeds the catalog size limit");
                    }
                    output.write(buffer, 0, read);
                    int percent = (int) ((total * 100) / expectedBytes);
                    if (percent >= lastPercent + 5) {
                        lastPercent = percent;
                        progress.onPercent(percent);
                    }
                }
                if (total != expectedBytes) {
                    throw new RuntimeInstallationException("payload download was incomplete");
                }
            }
        } finally {
            http.disconnect();
        }
    }

    static void verifyDigest(Path archive, String expected)
            throws IOException, RuntimeInstallationException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            String actual = toHex(digest.digest());
            if (!expected.equalsIgnoreCase(actual)) {
                throw new RuntimeInstallationException("payload digest does not match the catalog");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeInstallationException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Streams one tar input into {@code staging} enforcing the shared payload
     * rules: bounded entry count and extracted size, confined paths, safe
     * symlinks and hard links, and directories/regular files only.
     */
    static void extractTar(TarArchiveInputStream tar, Path staging, long uncompressedCap)
            throws IOException, RuntimeInstallationException {
        Files.createDirectories(staging);
        long extractedBytes = 0;
        int entryCount = 0;
        List<HardLinkSpec> hardLinks = new ArrayList<>();
        ArchiveEntry archiveEntry;
        byte[] buffer = new byte[32 * 1024];
        while ((archiveEntry = tar.getNextEntry()) != null) {
            if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                throw new RuntimeInstallationException("payload contains too many archive entries");
            }
            TarArchiveEntry tarEntry = (TarArchiveEntry) archiveEntry;
            Path target = resolveSafe(staging, tarEntry.getName());
            if (tarEntry.isDirectory()) {
                Files.createDirectories(target);
                applyTarMode(target, tarEntry.getMode());
                continue;
            }
            if (tarEntry.isSymbolicLink()) {
                createSafeLink(staging, target, tarEntry.getLinkName());
                continue;
            }
            if (tarEntry.isLink()) {
                hardLinks.add(new HardLinkSpec(
                        target, tarEntry.getLinkName(), tarEntry.getMode()));
                continue;
            }
            if (!tarEntry.isFile()) {
                throw new RuntimeInstallationException(
                        "payload contains an unsupported file type: " + tarEntry.getName());
            }
            long size = tarEntry.getSize();
            if (size < 0 || extractedBytes > uncompressedCap - size) {
                throw new RuntimeInstallationException("payload exceeds the extraction size limit");
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                int read;
                while ((read = tar.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            extractedBytes += size;
            applyTarMode(target, tarEntry.getMode());
        }
        long hardLinkBytes = 0;
        for (HardLinkSpec hardLink : hardLinks) {
            hardLinkBytes += createSafeHardLink(
                    staging, hardLink.target, hardLink.linkName, hardLink.mode);
            if (extractedBytes > uncompressedCap - hardLinkBytes) {
                throw new RuntimeInstallationException(
                        "payload exceeds the extraction size limit after hard links");
            }
        }
    }

    static Path resolveSafe(Path root, String archiveName)
            throws RuntimeInstallationException {
        if (archiveName == null || archiveName.isEmpty()
                || archiveName.startsWith("/")
                || archiveName.contains("\\")) {
            throw new RuntimeInstallationException("payload contains an unsafe archive path");
        }
        Path target = root.resolve(archiveName).normalize();
        if (!target.startsWith(root)) {
            throw new RuntimeInstallationException("payload path escapes the staging directory");
        }
        return target;
    }

    static void createSafeLink(Path root, Path target, String linkName)
            throws IOException, RuntimeInstallationException {
        if (linkName == null || linkName.isEmpty() || linkName.contains("\\")) {
            throw new RuntimeInstallationException(
                    "payload contains an unsafe symbolic link: " + target + " -> " + linkName);
        }
        Path linkTarget;
        if (linkName.startsWith("/")) {
            // Absolute paths are guest-root paths. Rebase them as relative links
            // so they can never resolve against the Android host filesystem.
            linkTarget = root.resolve(linkName.substring(1)).normalize();
        } else {
            linkTarget = target.getParent().resolve(linkName).normalize();
        }
        if (!linkTarget.startsWith(root)) {
            throw new RuntimeInstallationException(
                    "payload link escapes the staging directory: " + target + " -> " + linkName);
        }
        Path relativeTarget = target.getParent().relativize(linkTarget);
        if (relativeTarget.toString().isEmpty()) {
            throw new RuntimeInstallationException("payload contains a self-referencing symbolic link");
        }
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, relativeTarget);
    }

    static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later activation must never mistake cleanup failure for success.
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    static String formatMiB(long bytes) {
        return Long.toString((bytes + (1024 * 1024) - 1) / (1024 * 1024));
    }

    static final class HardLinkSpec {
        final Path target;
        final String linkName;
        final int mode;

        HardLinkSpec(Path target, String linkName, int mode) {
            this.target = target;
            this.linkName = linkName;
            this.mode = mode;
        }
    }

    static long createSafeHardLink(
            Path root,
            Path target,
            String linkName,
            int mode)
            throws IOException, RuntimeInstallationException {
        if (linkName == null || linkName.isEmpty() || linkName.contains("\\")) {
            throw new RuntimeInstallationException(
                    "payload contains an unsafe hard link: " + target + " -> " + linkName);
        }
        Path linkTarget = linkName.startsWith("/")
                ? root.resolve(linkName.substring(1)).normalize()
                : root.resolve(linkName).normalize();
        if (!linkTarget.startsWith(root)
                || !linkTarget.toFile().exists()
                || linkTarget.toFile().isDirectory()) {
            throw new RuntimeInstallationException(
                    "payload hard link target unavailable: " + linkTarget
                            + " exists=" + linkTarget.toFile().exists());
        }
        Files.createDirectories(target.getParent());
        // Preserve content safely even on Android filesystems that reject hard-link creation.
        try {
            try (InputStream input = new BufferedInputStream(
                         new FileInputStream(linkTarget.toFile()));
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(target.toFile(), false))) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            throw new RuntimeInstallationException(
                    "hard link copy failed: " + exception.getClass().getSimpleName()
                            + " " + exception.getMessage(), exception);
        }
        applyTarMode(target, mode);
        return linkTarget.toFile().length();
    }

    static void applyTarMode(Path path, int mode) throws IOException {
        int permissions = mode & 0777;
        if (permissions == 0) {
            return;
        }
        StringBuilder symbolic = new StringBuilder(9);
        int[] masks = { 0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001 };
        char[] values = { 'r', 'w', 'x' };
        for (int index = 0; index < masks.length; index++) {
            symbolic.append((permissions & masks[index]) == 0
                    ? '-'
                    : values[index % values.length]);
        }
        Set<PosixFilePermission> filePermissions =
                PosixFilePermissions.fromString(symbolic.toString());
        Files.setPosixFilePermissions(path, filePermissions);
    }
}
