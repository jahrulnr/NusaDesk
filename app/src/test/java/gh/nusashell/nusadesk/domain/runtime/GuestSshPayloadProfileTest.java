package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the curated guest-SSH add-on profile: pinned artifact set, HTTPS +
 * digest invariants, and the fixed guest mount/entrypoint contract.
 */
public class GuestSshPayloadProfileTest {

    @Test
    public void guestSshAddonPinsSevenArm64ArtifactsOverHttps() {
        GuestSshPayloadProfile profile = CuratedRuntimeCatalog.guestSshAddon();

        assertEquals("guest-ssh-openssh", profile.getAddonId());
        assertEquals("9.6p1-3ubuntu13", profile.getVersion());
        assertEquals("/opt/lw-ssh", profile.getGuestDir());
        assertEquals("usr/sbin/sshd", profile.getEntrypoint());

        List<PayloadArtifact> artifacts = profile.getArtifacts();
        assertEquals(7, artifacts.size());
        for (PayloadArtifact artifact : artifacts) {
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
        assertTrue(profile.totalCompressedBytes() > 0);
        assertTrue(profile.totalUncompressedBytes() > profile.totalCompressedBytes());
    }

    @Test
    public void guestSshAddonContainsOpenSshServer() {
        GuestSshPayloadProfile profile = CuratedRuntimeCatalog.guestSshAddon();
        boolean found = false;
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            if (artifact.getArtifactId().startsWith("openssh-server_")) {
                found = true;
                assertEquals("1:9.6p1-3ubuntu13", artifact.getPackageVersion());
            }
        }
        assertTrue("openssh-server must be pinned", found);
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
    public void profileRejectsRelativeGuestDir() {
        new GuestSshPayloadProfile(
                "addon", "Addon", "1", "opt/lw-ssh", "usr/sbin/sshd",
                java.util.Collections.singletonList(new PayloadArtifact(
                        "x", "1", "https://example.test/x.deb",
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "linux/arm64", 1, 2)));
    }

    @Test
    public void profileRejectsEmptyArtifacts() {
        try {
            new GuestSshPayloadProfile(
                    "addon", "Addon", "1", "/opt/x", "usr/sbin/sshd",
                    java.util.Collections.<PayloadArtifact>emptyList());
            fail("expected rejection of empty artifact set");
        } catch (IllegalArgumentException expected) {
        }
    }
}
