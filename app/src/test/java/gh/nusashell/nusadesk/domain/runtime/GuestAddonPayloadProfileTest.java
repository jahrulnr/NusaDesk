package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the curated guest add-on profiles: pinned artifact sets, HTTPS +
 * digest invariants, the fixed guest mount/entrypoint contract, vendored-file
 * pins, and the declarative validation lists the installer enforces.
 */
public class GuestAddonPayloadProfileTest {

    @Test
    public void guestSshAddonPinsSevenArm64ArtifactsOverHttps() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestSshAddon();

        assertEquals("guest-ssh-openssh", profile.getAddonId());
        assertEquals("9.6p1-3ubuntu13", profile.getVersion());
        assertEquals("/opt/lw-ssh", profile.getGuestDir());
        assertEquals("usr/sbin/sshd", profile.getEntrypoint());

        List<PayloadArtifact> artifacts = profile.getArtifacts();
        assertEquals(7, artifacts.size());
        for (PayloadArtifact artifact : artifacts) {
            assertPinnedArm64PoolArtifact(artifact);
        }
        assertTrue(profile.totalCompressedBytes() > 0);
        assertTrue(profile.totalUncompressedBytes() > profile.totalCompressedBytes());
        // The SSH guest setup edits /etc/shadow with the rootfs's own tools.
        assertTrue(profile.getRequiredRootfsTools().contains("usr/bin/perl"));
        assertTrue(profile.getRequiredRootfsTools().contains("usr/bin/grep"));
        assertTrue(profile.getVendoredFiles().isEmpty());
    }

    @Test
    public void guestSshAddonContainsOpenSshServer() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestSshAddon();
        boolean found = false;
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            if (artifact.getArtifactId().startsWith("openssh-server_")) {
                found = true;
                assertEquals("1:9.6p1-3ubuntu13", artifact.getPackageVersion());
            }
        }
        assertTrue("openssh-server must be pinned", found);
    }

    @Test
    public void guestServiceBridgePinsPythonClosureAndVendoredSystemctl() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestServiceBridge();

        assertEquals("guest-service-bridge", profile.getAddonId());
        assertEquals("1.7.1097", profile.getVersion());
        assertEquals("/opt/lw-services", profile.getGuestDir());
        // The entrypoint is the interpreter ELF, not the script: the installer
        // applies one uniform AArch64 ELF check to every add-on.
        assertEquals("usr/bin/python3.12", profile.getEntrypoint());

        List<PayloadArtifact> artifacts = profile.getArtifacts();
        assertEquals(14, artifacts.size());
        for (PayloadArtifact artifact : artifacts) {
            assertPinnedArm64PoolArtifact(artifact);
        }
        boolean foundPython = false;
        for (PayloadArtifact artifact : artifacts) {
            if (artifact.getArtifactId().startsWith("python3.12-minimal_")) {
                foundPython = true;
                assertEquals("3.12.3-1", artifact.getPackageVersion());
            }
        }
        assertTrue("the python3.12 interpreter package must be pinned", foundPython);

        List<VendoredFile> vendored = profile.getVendoredFiles();
        assertEquals(14, vendored.size());
        boolean foundSystemctl = false;
        boolean foundLicence = false;
        boolean foundService = false;
        boolean foundProcUptime = false;
        boolean foundProcStat = false;
        boolean foundSupervisor = false;
        for (VendoredFile file : vendored) {
            assertEquals(64, file.getSha256().length());
            assertTrue(file.getAssetPath().startsWith("services/")
                    || file.getAssetPath().startsWith("compose/"));
            if ("usr/bin/systemctl".equals(file.getOverlayPath())) {
                foundSystemctl = true;
                assertTrue(file.isExecutable());
                // Pinned upstream digest — docker-systemctl-replacement
                // v1.7.1097 @ 8bd65bec (see ADR-0024).
                assertEquals(
                        "5f5a47f321c7a8881dfd01f774106dc4012d664bee238eada86a7112674fea93",
                        file.getSha256());
            }
            if (file.getOverlayPath().endsWith("EUPL-LICENSE.md")) {
                foundLicence = true;
                assertFalse(file.isExecutable());
            }
            if ("usr/bin/service".equals(file.getOverlayPath())) {
                foundService = true;
                assertTrue(file.isExecutable());
            }
            // The synthesized /proc stand-ins bound over the SELinux-blocked
            // real files (ADR-0024).
            if ("lw-proc/uptime".equals(file.getOverlayPath())) {
                foundProcUptime = true;
                assertFalse(file.isExecutable());
            }
            if ("lw-proc/stat".equals(file.getOverlayPath())) {
                foundProcStat = true;
                assertFalse(file.isExecutable());
            }
            // The session supervisor: the tracer's initial tracee that shares
            // the manager and the session daemon into one PRoot tree.
            if ("usr/sbin/lw-session-supervisor".equals(file.getOverlayPath())) {
                foundSupervisor = true;
                assertTrue(file.isExecutable());
            }
        }
        assertTrue("systemctl must be vendored and executable", foundSystemctl);
        assertTrue("the EUPL licence text must be vendored", foundLicence);
        assertTrue("the service shim must be vendored and executable", foundService);
        assertTrue("the /proc/uptime stand-in must be vendored", foundProcUptime);
        assertTrue("the /proc/stat stand-in must be vendored", foundProcStat);
        assertTrue("the session supervisor must be vendored and executable", foundSupervisor);

        assertTrue(profile.getRequiredFiles().contains("usr/bin/systemctl"));
        assertTrue(profile.getRequiredFiles().contains("usr/bin/python3"));
        assertTrue(profile.getRequiredRootfsTools().contains("usr/bin/env"));
    }

    @Test
    public void guestServiceBridgeVendorsPinnedComposePayload() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestServiceBridge();

        java.util.Map<String, VendoredFile> byOverlay = new java.util.HashMap<>();
        for (VendoredFile file : profile.getVendoredFiles()) {
            byOverlay.put(file.getOverlayPath(), file);
        }

        // Byte-for-byte pinned upstream source releases (ADR-0024;
        // assets/compose/THIRD_PARTY_NOTICES.md records provenance). The
        // packaged assets carry a .tgz suffix — AGP's asset merge gunzips
        // .gz assets and strips the suffix, so a .tar.gz asset would never
        // reach the APK under its own name; the overlay still installs them
        // as .tar.gz.
        VendoredFile udockerTar =
                byOverlay.get("usr/local/lib/nusadesk/compose/udocker-1.3.17.tar.gz");
        assertNotNull("the udocker source tarball must be vendored", udockerTar);
        assertEquals("compose/udocker-1.3.17.tgz", udockerTar.getAssetPath());
        assertFalse(udockerTar.isExecutable());
        assertEquals("f97ec97679133b5ada780025e6e263607cb4ec3401786f1db135e46153c90bd1",
                udockerTar.getSha256());
        VendoredFile pyyamlTar =
                byOverlay.get("usr/local/lib/nusadesk/compose/PyYAML-6.0.1.tar.gz");
        assertNotNull("the PyYAML source tarball must be vendored", pyyamlTar);
        assertEquals("compose/PyYAML-6.0.1.tgz", pyyamlTar.getAssetPath());
        assertFalse(pyyamlTar.isExecutable());
        assertEquals("bfdf460b1736c775f2ba9f6a92bca30bc2095067b8a9d77876d1fad6cc3b4a43",
                pyyamlTar.getSha256());

        // Product-owned runner library and launchers.
        VendoredFile runtime =
                byOverlay.get("usr/local/lib/nusadesk/compose/lw_compose_runtime.py");
        assertNotNull("the compose runtime must be vendored", runtime);
        assertFalse(runtime.isExecutable());
        VendoredFile udocker = byOverlay.get("usr/local/bin/udocker");
        assertNotNull("the udocker launcher must be vendored", udocker);
        assertTrue(udocker.isExecutable());
        assertEquals("964e42ce3472c218abaebe4015922aedbb5ee40c1888661e907b2c17f1d66511",
                udocker.getSha256());
        VendoredFile supervisor = byOverlay.get("usr/local/bin/lw-compose-supervisor");
        assertNotNull("the compose supervisor launcher must be vendored", supervisor);
        assertTrue(supervisor.isExecutable());

        // The global supervisor unit plus licence/notice payloads.
        VendoredFile unit =
                byOverlay.get("etc/systemd/system/lw-compose-supervisor.service");
        assertNotNull("the compose supervisor unit must be vendored", unit);
        assertFalse(unit.isExecutable());
        assertEquals("4ab82d21f072dbec7223e16dbfb0c78e7d2d44190da9e897d575557471a5b4af",
                unit.getSha256());
        assertNotNull(byOverlay.get(
                "usr/share/lw-services/udocker/LICENSE-udocker-1.3.17.txt"));
        assertNotNull(byOverlay.get(
                "usr/share/lw-services/compose/THIRD_PARTY_NOTICES.md"));

        // Validation demands every compose member; the test/ harness is not
        // vendored into the overlay.
        for (String required : new String[]{
                "usr/local/bin/udocker",
                "usr/local/bin/lw-compose-supervisor",
                "usr/local/lib/nusadesk/compose/lw_compose_runtime.py",
                "usr/local/lib/nusadesk/compose/udocker-1.3.17.tar.gz",
                "usr/local/lib/nusadesk/compose/PyYAML-6.0.1.tar.gz",
                "usr/share/lw-services/udocker/LICENSE-udocker-1.3.17.txt",
                "usr/share/lw-services/compose/THIRD_PARTY_NOTICES.md",
                "etc/systemd/system/lw-compose-supervisor.service"}) {
            assertTrue("requiredFiles must demand " + required,
                    profile.getRequiredFiles().contains(required));
        }
        for (VendoredFile file : profile.getVendoredFiles()) {
            assertFalse("the test harness must not be vendored",
                    file.getAssetPath().startsWith("compose/test/"));
        }
    }

    @Test
    public void guestAddonsListsEveryCuratedAddon() {
        List<GuestAddonPayloadProfile> addons = CuratedRuntimeCatalog.guestAddons();
        assertEquals(2, addons.size());
        assertEquals("guest-ssh-openssh", addons.get(0).getAddonId());
        assertEquals("guest-service-bridge", addons.get(1).getAddonId());
    }

    private static void assertPinnedArm64PoolArtifact(PayloadArtifact artifact) {
        assertEquals("linux/arm64", artifact.getGuestAbi());
        assertTrue(artifact.getDownloadUrl().startsWith("https://"));
        assertTrue(artifact.getDownloadUrl().startsWith(
                "https://ports.ubuntu.com/ubuntu-ports/pool/"));
        assertEquals(64, artifact.getSha256().length());
        assertTrue(artifact.getCompressedBytes() > 0);
        assertTrue(artifact.getUncompressedBytes() > 0);
        // artifact ids are single safe path segments (used as filenames).
        assertTrue(artifact.getArtifactId().indexOf('/') < 0);
        assertTrue(artifact.getArtifactId().indexOf('\\') < 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void artifactRejectsNonHttpsUrls() {
        new PayloadArtifact(
                "x", "1", "http://example.test/x.deb",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "linux/arm64", 1, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void artifactRejectsBadDigest() {
        new PayloadArtifact(
                "x", "1", "https://example.test/x.deb", "nothex",
                "linux/arm64", 1, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void artifactRejectsTraversalId() {
        new PayloadArtifact(
                "../x", "1", "https://example.test/x.deb",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "linux/arm64", 1, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void vendoredFileRejectsAbsoluteOverlayPath() {
        new VendoredFile("services/x", "/usr/bin/x", false,
                "0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test(expected = IllegalArgumentException.class)
    public void vendoredFileRejectsTraversalAssetPath() {
        new VendoredFile("../x", "usr/bin/x", false,
                "0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test(expected = IllegalArgumentException.class)
    public void vendoredFileRejectsBadDigest() {
        new VendoredFile("services/x", "usr/bin/x", false, "nothex");
    }

    @Test(expected = IllegalArgumentException.class)
    public void profileRejectsRelativeGuestDir() {
        new GuestAddonPayloadProfile(
                "addon", "Addon", "1", "opt/lw-ssh", "usr/sbin/sshd",
                java.util.Collections.singletonList(new PayloadArtifact(
                        "x", "1", "https://example.test/x.deb",
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "linux/arm64", 1, 2)),
                java.util.Collections.<VendoredFile>emptyList(),
                java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList());
    }

    @Test
    public void profileRejectsEmptyArtifacts() {
        try {
            new GuestAddonPayloadProfile(
                    "addon", "Addon", "1", "/opt/x", "usr/sbin/sshd",
                    java.util.Collections.<PayloadArtifact>emptyList(),
                    java.util.Collections.<VendoredFile>emptyList(),
                    java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList());
            fail("expected rejection of empty artifact set");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void profileRejectsAbsoluteRequiredFile() {
        try {
            new GuestAddonPayloadProfile(
                    "addon", "Addon", "1", "/opt/x", "usr/sbin/sshd",
                    java.util.Collections.singletonList(new PayloadArtifact(
                            "x", "1", "https://example.test/x.deb",
                            "0000000000000000000000000000000000000000000000000000000000000000",
                            "linux/arm64", 1, 2)),
                    java.util.Collections.<VendoredFile>emptyList(),
                    java.util.Collections.singletonList("/usr/bin/x"),
                    java.util.Collections.<String>emptyList());
            fail("expected rejection of an absolute requiredFiles entry");
        } catch (IllegalArgumentException expected) {
        }
    }
}
