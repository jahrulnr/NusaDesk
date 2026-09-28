package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.PayloadArtifact;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for {@link GuestBaseExtras}: the catalog profile pins, the
 * deterministic CA-bundle generation (what the never-run
 * {@code update-ca-certificates} would emit), and the rootfs symlink wiring
 * contract — never shadowing real guest content, safe to re-run every
 * session start.
 */
public class GuestBaseExtrasTest {

    private static final String BUNDLE = "etc/ssl/certs/ca-certificates.crt";
    private static final String MOZILLA = "usr/share/ca-certificates/mozilla";

    @Rule
    public final TemporaryFolder rootfs = new TemporaryFolder();

    @Rule
    public final TemporaryFolder overlay = new TemporaryFolder();

    @Test
    public void catalogProfilePinsTheBaseExtrasPayload() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestBaseExtras();

        assertEquals("guest-base-extras", profile.getAddonId());
        assertEquals("20240203", profile.getVersion());
        assertEquals("/opt/lw-base", profile.getGuestDir());
        // ca-certificates ships no ELF at all (it is an _all data package);
        // the entrypoint is the openssl CLI, which also satisfies the uniform
        // AArch64 install-time check and is the payload's TLS tool.
        assertEquals("usr/bin/openssl", profile.getEntrypoint());

        List<PayloadArtifact> artifacts = profile.getArtifacts();
        assertEquals(2, artifacts.size());
        PayloadArtifact certs = artifacts.get(0);
        assertEquals("ca-certificates_20240203_all", certs.getArtifactId());
        assertEquals("20240203", certs.getPackageVersion());
        assertEquals(
                "https://ports.ubuntu.com/ubuntu-ports/pool/main/c/ca-certificates/"
                        + "ca-certificates_20240203_all.deb",
                certs.getDownloadUrl());
        assertEquals(
                "641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a",
                certs.getSha256());
        assertEquals(159_378L, certs.getCompressedBytes());
        assertEquals(256_156L, certs.getUncompressedBytes());
        PayloadArtifact openssl = artifacts.get(1);
        assertEquals("openssl_3.0.13-0ubuntu3_arm64", openssl.getArtifactId());
        assertEquals("3.0.13-0ubuntu3", openssl.getPackageVersion());
        assertEquals(
                "https://ports.ubuntu.com/ubuntu-ports/pool/main/o/openssl/"
                        + "openssl_3.0.13-0ubuntu3_arm64.deb",
                openssl.getDownloadUrl());
        assertEquals(
                "9b7136b1af32fbdefc2eac61bae86f8304c603c7b9a0297b20a1e31c522b024b",
                openssl.getSha256());
        assertEquals(983_800L, openssl.getCompressedBytes());
        assertEquals(1_712_035L, openssl.getUncompressedBytes());
        for (PayloadArtifact artifact : artifacts) {
            // The field records the guest ABI the artifact installs into; the
            // _all ca-certificates deb carries the same value as the other
            // _all catalog artifacts (tzdata, netbase, media-types).
            assertEquals("linux/arm64", artifact.getGuestAbi());
            assertTrue(artifact.getDownloadUrl().startsWith(
                    "https://ports.ubuntu.com/ubuntu-ports/pool/"));
        }

        // The vendored set is the provenance note only; its digest is pinned
        // like every vendored file.
        assertEquals(1, profile.getVendoredFiles().size());
        VendoredFile notices = profile.getVendoredFiles().get(0);
        assertEquals("base-extras/THIRD_PARTY_NOTICES.md", notices.getAssetPath());
        assertEquals("usr/share/lw-base/THIRD_PARTY_NOTICES.md",
                notices.getOverlayPath());
        assertFalse(notices.isExecutable());
        assertEquals(
                "9a095f1453f0df751f4d56112faebf44e453cc5946808a76380436c0ce1b26ce",
                notices.getSha256());

        assertTrue(profile.getRequiredFiles().contains(MOZILLA));
        assertTrue(profile.getRequiredFiles()
                .contains("usr/sbin/update-ca-certificates"));
        assertTrue(profile.getRequiredFiles()
                .contains("usr/share/lw-base/THIRD_PARTY_NOTICES.md"));
        // Bundle generation and wiring are host-side Java; no guest tool is
        // consumed at setup time.
        assertTrue(profile.getRequiredRootfsTools().isEmpty());
    }

    @Test
    public void guestBaseExtrasIsRegisteredAsACuratedAddon() {
        List<GuestAddonPayloadProfile> addons = CuratedRuntimeCatalog.guestAddons();
        assertEquals(4, addons.size());
        assertEquals("guest-base-extras", addons.get(2).getAddonId());
    }

    @Test
    public void detectReturnsNullWithoutThePinnedPayload() throws Exception {
        assertNull(GuestBaseExtras.detect(null));
        assertNull(GuestBaseExtras.detect(overlay.getRoot().toPath()));
        // The ELF alone is not the payload: an overlay without the cert set
        // cannot produce a bundle, so it is "not installed", never "usable".
        touch(overlay.getRoot(), "usr/bin/openssl");
        assertNull(GuestBaseExtras.detect(overlay.getRoot().toPath()));
        extras();
        assertNotNull(GuestBaseExtras.detect(overlay.getRoot().toPath()));
    }

    @Test
    public void wireIntoBuildsTheBundleFromTheSortedCertSet() throws Exception {
        GuestBaseExtras extras = extrasWithoutCerts();
        // Written out of order on purpose, with B lacking a trailing newline:
        // the bundle must be the sorted set concatenated, each cert
        // newline-terminated — the same bytes update-ca-certificates emits.
        write(overlay.getRoot(), MOZILLA + "/C_Root.crt", "cert-C");
        write(overlay.getRoot(), MOZILLA + "/A_Root.crt", "cert-A\n");
        write(overlay.getRoot(), MOZILLA + "/B_Root.crt", "cert-B");

        List<String> wired = extras.wireInto(rootfs.getRoot().toPath());

        byte[] bundle = Files.readAllBytes(
                overlay.getRoot().toPath().resolve(BUNDLE));
        assertArrayEquals("cert-A\ncert-B\ncert-C\n".getBytes(StandardCharsets.US_ASCII),
                bundle);
        assertLinked(rootfs.getRoot(), BUNDLE,
                "/opt/lw-base/etc/ssl/certs/ca-certificates.crt");
        assertTrue(wired.contains(BUNDLE));
    }

    @Test
    public void wireIntoLinksTheOpensslSurfaceAtConventionalPaths() throws Exception {
        GuestBaseExtras extras = extras();

        List<String> wired = extras.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), "usr/bin/openssl",
                "/opt/lw-base/usr/bin/openssl");
        assertLinked(rootfs.getRoot(), "usr/lib/ssl",
                "/opt/lw-base/usr/lib/ssl");
        assertLinked(rootfs.getRoot(), "etc/ssl/openssl.cnf",
                "/opt/lw-base/etc/ssl/openssl.cnf");
        assertTrue(wired.contains("usr/bin/openssl"));
        assertTrue(wired.contains("usr/lib/ssl"));
        assertTrue(wired.contains("etc/ssl/openssl.cnf"));
    }

    @Test
    public void wireIntoIsIdempotentAndByteIdentical() throws Exception {
        GuestBaseExtras extras = extras();
        extras.wireInto(rootfs.getRoot().toPath());
        byte[] first = Files.readAllBytes(overlay.getRoot().toPath().resolve(BUNDLE));

        List<String> second = extras.wireInto(rootfs.getRoot().toPath());

        assertTrue("rewiring must be a no-op", second.isEmpty());
        assertArrayEquals(first,
                Files.readAllBytes(overlay.getRoot().toPath().resolve(BUNDLE)));
        assertLinked(rootfs.getRoot(), BUNDLE,
                "/opt/lw-base/etc/ssl/certs/ca-certificates.crt");
    }

    @Test
    public void wireIntoNeverShadowsRealGuestContent() throws Exception {
        GuestBaseExtras extras = extras();
        // A real bundle the guest owns (e.g. an apt-managed ca-certificates
        // install or a hand-maintained file) wins over the overlay link.
        write(rootfs.getRoot(), BUNDLE, "guest-owned-bundle\n");
        touch(rootfs.getRoot(), "usr/bin/openssl");

        List<String> wired = extras.wireInto(rootfs.getRoot().toPath());

        assertFalse(wired.contains(BUNDLE));
        assertFalse(wired.contains("usr/bin/openssl"));
        assertFalse(Files.isSymbolicLink(rootfs.getRoot().toPath().resolve(BUNDLE)));
        assertArrayEquals("guest-owned-bundle\n".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(rootfs.getRoot().toPath().resolve(BUNDLE)));
        assertFalse(Files.isSymbolicLink(
                rootfs.getRoot().toPath().resolve("usr/bin/openssl")));
    }

    @Test
    public void wireIntoRefreshesAStaleSymlink() throws Exception {
        GuestBaseExtras extras = extras();
        link(rootfs.getRoot(), BUNDLE, "/opt/lw-old/etc/ssl/certs/ca-certificates.crt");

        extras.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), BUNDLE,
                "/opt/lw-base/etc/ssl/certs/ca-certificates.crt");
    }

    @Test
    public void wireIntoProducesNoBundleWithoutThePinnedCertSet() throws Exception {
        GuestBaseExtras extras = extras();
        // The cert set disappears between detection and wire-up (a torn
        // overlay): the wiring stays fail-closed — no dangling bundle link —
        // while the members that do exist still link.
        deleteRecursively(overlay.getRoot().toPath().resolve(MOZILLA));

        List<String> wired = extras.wireInto(rootfs.getRoot().toPath());

        assertFalse(Files.exists(overlay.getRoot().toPath().resolve(BUNDLE)));
        assertFalse(Files.exists(rootfs.getRoot().toPath().resolve(BUNDLE),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(wired.contains(BUNDLE));
        assertTrue(wired.contains("usr/bin/openssl"));
    }

    /**
     * The overlay usable by the wiring tests: the openssl half plus the
     * pinned cert source directory. File contents are irrelevant — the
     * payload members stand in for the verified .deb extraction.
     */
    private GuestBaseExtras extras() throws Exception {
        write(overlay.getRoot(), MOZILLA + "/ISRG_Root_X1.crt", "cert-isrg\n");
        write(overlay.getRoot(), MOZILLA + "/DigiCert_Global_Root_CA.crt", "cert-digicert\n");
        return extrasWithoutCerts();
    }

    /**
     * A detected overlay carrying the openssl surface and the (possibly
     * empty) cert source directory — the tests add their own sources.
     */
    private GuestBaseExtras extrasWithoutCerts() throws Exception {
        touch(overlay.getRoot(), "usr/bin/openssl");
        touch(overlay.getRoot(), "etc/ssl/openssl.cnf");
        Files.createDirectories(overlay.getRoot().toPath().resolve("usr/lib/ssl"));
        Files.createDirectories(overlay.getRoot().toPath().resolve(MOZILLA));
        GuestBaseExtras extras = GuestBaseExtras.detect(overlay.getRoot().toPath());
        assertNotNull("staged overlay must detect", extras);
        return extras;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry)) {
                    deleteRecursively(entry);
                } else {
                    Files.delete(entry);
                }
            }
        }
        Files.delete(dir);
    }

    private void assertLinked(File root, String guestRelative, String target) throws Exception {
        Path link = root.toPath().resolve(guestRelative);
        assertTrue(guestRelative + " must be a symlink", Files.isSymbolicLink(link));
        assertEquals(Paths.get(target), Files.readSymbolicLink(link));
    }

    private void touch(File base, String relativePath) throws Exception {
        File f = new File(base, relativePath);
        assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
        if (!f.exists()) {
            assertTrue(f.createNewFile());
        }
    }

    private void write(File base, String relativePath, String content) throws Exception {
        Path file = base.toPath().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.US_ASCII));
    }

    private void link(File base, String relativePath, String target) throws Exception {
        Path link = base.toPath().resolve(relativePath);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(target));
    }
}
