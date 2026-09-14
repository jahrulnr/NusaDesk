package gh.nusashell.nusadesk.domain.runtime;

import java.util.Arrays;
import java.util.List;

/**
 * Product-owned catalog for the first proof profile. Entries are version-pinned
 * and must be reviewed before changing their source or digest.
 */
public final class CuratedRuntimeCatalog {
    private CuratedRuntimeCatalog() {
    }

    /** Ubuntu Base Noble arm64 rootfs used for the first install proof. */
    public static RuntimeCatalogEntry ubuntuBaseArm64() {
        return new RuntimeCatalogEntry(
                "ubuntu-base-arm64",
                "Ubuntu Base (ARM64)",
                "24.04.5",
                "https://cdimage.ubuntu.com/cdimage/ubuntu-base/releases/24.04/release/"
                        + "ubuntu-base-24.04.5-base-arm64.tar.gz",
                "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                "linux/arm64",
                29_936_675L,
                104_728_695L);
    }

    /**
     * Curated guest-SSH add-on: OpenSSH server plus its missing shared-library
     * dependencies, as pinned Ubuntu Noble GA-pocket {@code .deb} artifacts for
     * arm64.
     *
     * <p>Every artifact lives at a permanent GA-pocket pool URL on
     * {@code ports.ubuntu.com} (release-pocket pool files are never removed on
     * supersede) and is pinned to the SHA-256 recorded by the signed
     * {@code dists/noble/main/binary-arm64/Packages} index. ADR-0010 records
     * the daemon choice (OpenSSH: Dropbear 2022.83 re-execs itself per
     * connection through {@code execveat(fd,...)}, which the packaged PRoot
     * build cannot translate) and the per-package licenses.</p>
     */
    public static GuestSshPayloadProfile guestSshAddon() {
        String pool = "https://ports.ubuntu.com/ubuntu-ports/";
        List<PayloadArtifact> artifacts = Arrays.asList(
                new PayloadArtifact(
                        "openssh-server_9.6p1-3ubuntu13_arm64",
                        "1:9.6p1-3ubuntu13",
                        pool + "pool/main/o/openssh/openssh-server_9.6p1-3ubuntu13_arm64.deb",
                        "3db79ddc9865b04b8601638ed1443e40522ec2bd8b44f6ee4e64b28107dfe49f",
                        "linux/arm64",
                        501_312L,
                        2_115_295L),
                new PayloadArtifact(
                        "libgssapi-krb5-2_1.20.1-6ubuntu2_arm64",
                        "1.20.1-6ubuntu2",
                        pool + "pool/main/k/krb5/libgssapi-krb5-2_1.20.1-6ubuntu2_arm64.deb",
                        "27d6f3321a5b126ff1cfa76399506bb11e53ac38b5d263c3acdae2452356932d",
                        "linux/arm64",
                        141_356L,
                        397_407L),
                new PayloadArtifact(
                        "libkrb5-3_1.20.1-6ubuntu2_arm64",
                        "1.20.1-6ubuntu2",
                        pool + "pool/main/k/krb5/libkrb5-3_1.20.1-6ubuntu2_arm64.deb",
                        "45f2427ee2de6006348e6acfc6f104a4624fbe8577275ce7ab6a9a510842cdc3",
                        "linux/arm64",
                        349_134L,
                        1_067_874L),
                new PayloadArtifact(
                        "libk5crypto3_1.20.1-6ubuntu2_arm64",
                        "1.20.1-6ubuntu2",
                        pool + "pool/main/k/krb5/libk5crypto3_1.20.1-6ubuntu2_arm64.deb",
                        "6b372dcd362202fba55fe40dabc9e6216fb96757b3f908a0056049fd1d3e6706",
                        "linux/arm64",
                        85_566L,
                        261_967L),
                new PayloadArtifact(
                        "libkrb5support0_1.20.1-6ubuntu2_arm64",
                        "1.20.1-6ubuntu2",
                        pool + "pool/main/k/krb5/libkrb5support0_1.20.1-6ubuntu2_arm64.deb",
                        "f24500abe0707ad5f16371e9f511d48923d8378f240afe9caa77a91b4854a95a",
                        "linux/arm64",
                        33_890L,
                        134_846L),
                new PayloadArtifact(
                        "libkeyutils1_1.6.3-3build1_arm64",
                        "1.6.3-3build1",
                        pool + "pool/main/k/keyutils/libkeyutils1_1.6.3-3build1_arm64.deb",
                        "d141e94ed3b32ea6540f2e927a83b3d42cd4378256a1aad60cda583ffdbd78af",
                        "linux/arm64",
                        9_654L,
                        70_396L),
                new PayloadArtifact(
                        "libwrap0_7.6.q-33_arm64",
                        "7.6.q-33",
                        pool + "pool/main/t/tcp-wrappers/libwrap0_7.6.q-33_arm64.deb",
                        "cb7aec6dd9e6e26df584d2e33f3c00b2d9c17430251f41bdf2a2878098b70db6",
                        "linux/arm64",
                        48_456L,
                        101_408L));
        return new GuestSshPayloadProfile(
                "guest-ssh-openssh",
                "Guest SSH server (OpenSSH)",
                "9.6p1-3ubuntu13",
                GuestSshPayloadProfile.GUEST_DIR,
                GuestSshPayloadProfile.ENTRYPOINT,
                artifacts);
    }
}
