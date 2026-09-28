package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Guest base extras (the {@code guest-base-extras} add-on): the pinned
 * Mozilla CA certificate set and the {@code openssl} CLI, delivered as an
 * overlay bound at {@link CuratedRuntimeCatalog#BASE_EXTRAS_OVERLAY_GUEST_DIR}.
 *
 * <p>The curated Ubuntu Base rootfs ships no CA certificates at all — there
 * is no {@code /etc/ssl/} tree — so every TLS client in the guest fails
 * certificate verification. The {@code ca-certificates} package deliberately
 * does not ship {@code /etc/ssl/certs/ca-certificates.crt} either: upstream,
 * that bundle is output of the {@code update-ca-certificates} maintainer
 * script, which concatenates the package's
 * {@code usr/share/ca-certificates/mozilla/*.crt} sources. This product never
 * runs maintainer scripts, so {@link #wireInto} builds the bundle itself —
 * the same {@code find | sort} order and the same {@code sed -e '$a\'}
 * trailing-newline fix-up the maintainer script applies — and writes it into
 * the overlay at {@code etc/ssl/certs/ca-certificates.crt}. The result is
 * byte-for-byte what {@code update-ca-certificates} emits for the default
 * pinned set.</p>
 *
 * <p>What gets wired into the rootfs, with the same preserve-first symlink
 * discipline as {@link GuestServiceBridge#wireInto}:</p>
 * <ul>
 *   <li>{@code etc/ssl/certs/ca-certificates.crt} → the generated bundle.
 *       This is the conventional path every shipped TLS stack reads first
 *       (OpenSSL's default file, GnuTLS, curl, wget, Python {@code ssl},
 *       Go's x509).</li>
 *   <li>{@code usr/bin/openssl} → the payload's CLI, the conventional TLS
 *       inspection tool and the binary {@code update-ca-certificates}
 *       invokes.</li>
 *   <li>{@code usr/lib/ssl} → the overlay's {@code OPENSSLDIR} (one directory
 *       link covering {@code cert.pem}, {@code certs}, and
 *       {@code openssl.cnf}), so {@code openssl s_client}/{@code verify}
 *       resolve the bundle with no {@code -CAfile} argument.</li>
 *   <li>{@code etc/ssl/openssl.cnf} → the payload's real config file at the
 *       conventional path.</li>
 * </ul>
 *
 * <p>Wiring rules: a missing overlay member is skipped rather than linked
 * dangling; an existing real file or directory in the rootfs always wins
 * over the overlay (guest-owned content is never shadowed); only symlinks
 * are replaced, so rewiring can never delete guest content. The step is
 * idempotent and re-runs on every session start.</p>
 *
 * <p>Two parts of the payload are deliberately <em>not</em> wired:</p>
 * <ul>
 *   <li>{@code usr/sbin/update-ca-certificates} — without the
 *       postinst-generated {@code /etc/ca-certificates.conf} (which lists the
 *       default set) it rebuilds a bundle of only
 *       {@code /usr/local/share/ca-certificates} entries, silently dropping
 *       every Mozilla root. A user who runs it from the overlay path anyway
 *       replaces the bundle symlink with a real file, which the
 *       preserve-first rule then leaves alone — the honest handoff.</li>
 *   <li>The hashed per-certificate links ({@code openssl rehash}'s
 *       {@code <hash>.0} files): nothing shipped requires a CApath-style
 *       directory lookup, and OpenSSL's default verify paths already read
 *       the bundle through {@code cert.pem}. Generating the hash names is
 *       speculative structure until a consumer needs it.</li>
 * </ul>
 *
 * <p>Pure JVM file logic; unit-testable with temp directories.</p>
 */
public final class GuestBaseExtras {
    /**
     * Overlay-relative directory holding the pinned Mozilla source
     * certificates the bundle is generated from.
     */
    private static final String MOZILLA_CERTS_DIR =
            "usr/share/ca-certificates/mozilla";
    /**
     * Overlay-relative path of the generated PEM bundle — the same
     * guest-relative path the rootfs link is created at.
     */
    private static final String BUNDLE_PATH = "etc/ssl/certs/ca-certificates.crt";

    /**
     * Overlay members re-exposed at the identical conventional guest path.
     * Each entry is relative to both the overlay root and the guest root and
     * is wired only when the overlay actually carries it.
     */
    private static final List<String> WIRED_PATHS =
            Collections.unmodifiableList(Arrays.asList(
                    BUNDLE_PATH,
                    "etc/ssl/openssl.cnf",
                    "usr/bin/openssl",
                    "usr/lib/ssl"));

    private final String mountGuestDir;
    private final Path mountHostDir;

    private GuestBaseExtras(String mountGuestDir, Path mountHostDir) {
        this.mountGuestDir = mountGuestDir;
        this.mountHostDir = mountHostDir;
    }

    /**
     * Detect usable base extras: the activated add-on overlay carrying the
     * profile's ELF entrypoint and the pinned certificate source directory.
     * An absent or torn overlay is reported as "not installed" — the install
     * pipeline is the repair path, and a bundle that cannot be generated is
     * never partially wired.
     *
     * @param addonOverlayDir host path of the activated overlay (may be
     *                        null/absent)
     * @return the detected extras, or {@code null} when none usable is
     *         installed
     */
    public static GuestBaseExtras detect(Path addonOverlayDir) {
        if (addonOverlayDir == null || !Files.exists(addonOverlayDir)) {
            return null;
        }
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestBaseExtras();
        if (!Files.isRegularFile(addonOverlayDir.resolve(profile.getEntrypoint()))) {
            return null;
        }
        if (!Files.isDirectory(addonOverlayDir.resolve(MOZILLA_CERTS_DIR))) {
            return null;
        }
        return new GuestBaseExtras(
                CuratedRuntimeCatalog.BASE_EXTRAS_OVERLAY_GUEST_DIR, addonOverlayDir);
    }

    /**
     * Generate the CA bundle inside the overlay, then link the overlay's
     * public guest paths into the active rootfs. Idempotent; see the class
     * docs for the conflict rules.
     *
     * @return the guest-relative paths this call created or refreshed
     *         (existing real files and already-correct links are not listed)
     */
    public List<String> wireInto(Path rootfsDir) throws IOException {
        ensureBundle();
        List<String> linked = new ArrayList<>();
        for (String guestRelative : WIRED_PATHS) {
            if (wireGuestPath(rootfsDir, guestRelative)) {
                linked.add(guestRelative);
            }
        }
        return linked;
    }

    /**
     * Build {@code etc/ssl/certs/ca-certificates.crt} inside the overlay from
     * the pinned {@code mozilla/*.crt} set — the deterministic equivalent of
     * the maintainer script this product never runs. Sources are concatenated
     * in sorted filename order (ASCII names, so natural string order is the
     * upstream {@code sort} order) and each file gains a trailing newline
     * when it lacks one, exactly as the script's {@code sed -e '$a\'} does.
     *
     * <p>The write is atomic (same-directory temp file, then a move) and is
     * skipped when the existing bundle already holds the expected bytes, so
     * rewiring changes nothing on disk.</p>
     *
     * @return true when the bundle is present after this call
     */
    private boolean ensureBundle() throws IOException {
        Path certsDir = mountHostDir.resolve(MOZILLA_CERTS_DIR);
        if (!Files.isDirectory(certsDir)) {
            return false;
        }
        List<Path> certs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(certsDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (entry.getFileName().toString().endsWith(".crt")
                        && Files.isRegularFile(entry)) {
                    certs.add(entry);
                }
            }
        }
        if (certs.isEmpty()) {
            return false;
        }
        certs.sort(Comparator.comparing(path -> path.getFileName().toString()));

        ByteArrayOutputStream bundle = new ByteArrayOutputStream(256 * 1024);
        for (Path cert : certs) {
            byte[] bytes = readAll(cert);
            bundle.write(bytes, 0, bytes.length);
            if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                bundle.write('\n');
            }
        }
        byte[] expected = bundle.toByteArray();

        Path target = mountHostDir.resolve(BUNDLE_PATH);
        if (Files.isRegularFile(target)
                && Arrays.equals(Files.readAllBytes(target), expected)) {
            return true;
        }
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".ca-certificates", ".tmp");
        try {
            Files.write(temp, expected, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomically(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        try {
            Files.setPosixFilePermissions(
                    target, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException exception) {
            // A filesystem without POSIX modes still gets a readable file;
            // the guest reads through the bind as the app's own uid.
        }
        return true;
    }

    private static byte[] readAll(Path file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    /**
     * Link {@code guestRelative} to the same path inside the bound overlay.
     *
     * @return true when a link was created or updated; false when the overlay
     *         lacks the member or the rootfs already has real content there
     */
    private boolean wireGuestPath(Path rootfsDir, String guestRelative) throws IOException {
        Path member = mountHostDir.resolve(guestRelative);
        if (!Files.exists(member)) {
            return false;
        }
        String target = mountGuestDir + "/" + guestRelative;
        Path link = rootfsDir.resolve(guestRelative);
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).toString().equals(target)) {
                return false;
            }
            // A symlink is a pointer, not content: replacing it can never
            // delete anything the guest owns.
            Files.delete(link);
        } else if (Files.exists(link)) {
            // Real guest content wins over the overlay — never shadow it.
            return false;
        }
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(target));
        return true;
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
