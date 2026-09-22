package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;

/**
 * Resolves a guest-visible absolute path to the host path inside the active
 * curated rootfs, and back.
 *
 * <p>The guest sees the active rootfs tree as its {@code /}, so a file the
 * guest names (a camera output path, a file to share, a media-scan target) has
 * exactly one host counterpart under
 * {@code <filesDir>/linux-wrapper/runtimes/ubuntu-base-arm64/active}. A
 * capability method that must read or write a guest file resolves it here
 * instead of guessing, and a path that leaves the rootfs is a typed
 * {@code invalid-argument} rather than a write outside the guest's own tree.</p>
 *
 * <p>Guest paths under a bind mount (the user's workspace folder, the service
 * overlay) are <em>not</em> reachable this way: the bind shadows the rootfs
 * path. The generated guest commands therefore stage a file inside the rootfs
 * (guest {@code /tmp}) before a bridge call that carries a path, and move the
 * result to the user's destination themselves, where the guest's own view is
 * authoritative.</p>
 */
final class GuestFilePathResolver {
    /** Thrown when a guest path cannot be used. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    /** Curated runtime whose active tree the guest sees as {@code /}. */
    private static final String RUNTIME_APP_ID = "ubuntu-base-arm64";

    /** Bounded guest path length; far beyond any real filename. */
    private static final int MAX_GUEST_PATH_CHARS = 4096;

    private final Path rootfs;

    GuestFilePathResolver(Context context) {
        this(ProotPaths.activeRootfsPath(
                context.getApplicationContext().getFilesDir().toPath(), RUNTIME_APP_ID));
    }

    GuestFilePathResolver(Path rootfs) {
        if (rootfs == null) {
            throw new IllegalArgumentException("rootfs must not be null");
        }
        this.rootfs = rootfs;
    }

    /** The active rootfs host directory. */
    Path rootfs() {
        return rootfs;
    }

    /** Whether the active rootfs exists (the guest is installed). */
    boolean available() {
        return Files.isDirectory(rootfs, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Resolve a guest path that the caller intends to create or overwrite. The
     * parent directory must exist inside the rootfs and must not be a symlink
     * that escapes it.
     */
    Path resolveForWrite(String guestPath) {
        Path resolved = resolve(guestPath);
        Path parent = resolved.getParent();
        if (parent == null) {
            throw new Invalid("guest path has no parent directory");
        }
        Path realParent = realPathInsideRootfs(parent);
        Path target = realParent.resolve(resolved.getFileName().toString());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(target)) {
            throw new Invalid("guest path is a symlink");
        }
        return target;
    }

    /**
     * Resolve a guest path the caller intends to read. The file must exist and
     * its real location must stay inside the rootfs.
     */
    Path resolveForRead(String guestPath) {
        Path resolved = resolve(guestPath);
        Path real = realPathInsideRootfs(resolved);
        if (!Files.isRegularFile(real)) {
            throw new Invalid("guest path is not a regular file");
        }
        return real;
    }

    /** The guest-visible path for a host path inside the rootfs. */
    String toGuestPath(Path hostPath) {
        Path normalized = hostPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(rootfs)) {
            throw new Invalid("host path is outside the active rootfs");
        }
        String relative = rootfs.relativize(normalized).toString();
        return "/" + relative.replace(java.io.File.separatorChar, '/');
    }

    /**
     * Validate and normalize one guest absolute path: no traversal segment, no
     * control character, and a normalized location inside the rootfs.
     */
    private Path resolve(String guestPath) {
        if (guestPath == null || guestPath.isEmpty()) {
            throw new Invalid("guest path must not be empty");
        }
        if (guestPath.length() > MAX_GUEST_PATH_CHARS) {
            throw new Invalid("guest path is too long");
        }
        if (!guestPath.startsWith("/")) {
            throw new Invalid("guest path must be absolute");
        }
        for (int i = 0; i < guestPath.length(); i++) {
            char c = guestPath.charAt(i);
            if (Character.isISOControl(c)) {
                throw new Invalid("guest path contains a control character");
            }
        }
        String[] segments = guestPath.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new Invalid("guest path must not traverse");
            }
        }
        Path resolved = rootfs.resolve(Paths.get(guestPath.substring(1)).normalize()).normalize();
        if (!resolved.startsWith(rootfs)) {
            throw new Invalid("guest path escapes the active rootfs");
        }
        return resolved;
    }

    /** Resolve symlinks and require the result to stay inside the rootfs. */
    private Path realPathInsideRootfs(Path path) {
        try {
            Path real = path.toRealPath();
            if (!real.startsWith(rootfs.toRealPath())) {
                throw new Invalid("guest path escapes the active rootfs");
            }
            return real;
        } catch (IOException e) {
            throw new Invalid("guest path is not reachable");
        }
    }
}
