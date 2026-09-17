package gh.nusashell.nusadesk.domain.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Product-owned catalog for the first proof profile. Entries are version-pinned
 * and must be reviewed before changing their source or digest.
 */
public final class CuratedRuntimeCatalog {
    /** Fixed guest-visible mount point of the guest-SSH add-on overlay. */
    public static final String SSH_OVERLAY_GUEST_DIR = "/opt/lw-ssh";
    /** Guest-relative daemon path the SSH overlay must provide. */
    public static final String SSH_OVERLAY_ENTRYPOINT = "usr/sbin/sshd";

    /**
     * Fixed guest-visible mount point of the guest service-bridge add-on
     * overlay (ADR-0024): the vendored {@code systemctl} replacement plus the
     * pinned Python 3 runtime it runs on.
     */
    public static final String SERVICES_OVERLAY_GUEST_DIR = "/opt/lw-services";
    /**
     * Guest-relative path of the AArch64 ELF entrypoint the service-bridge
     * overlay must provide: the Python 3.12 interpreter that execs the
     * vendored {@code systemctl} script.
     */
    public static final String SERVICES_OVERLAY_ENTRYPOINT = "usr/bin/python3.12";

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
    public static GuestAddonPayloadProfile guestSshAddon() {
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
        return new GuestAddonPayloadProfile(
                "guest-ssh-openssh",
                "Guest SSH server (OpenSSH)",
                "9.6p1-3ubuntu13",
                SSH_OVERLAY_GUEST_DIR,
                SSH_OVERLAY_ENTRYPOINT,
                artifacts,
                Collections.<VendoredFile>emptyList(),
                Collections.<String>emptyList(),
                Arrays.asList("usr/bin/perl", "usr/bin/grep", "usr/bin/chmod"));
    }

    /**
     * Curated guest service-bridge add-on (ADR-0024): the vendored
     * {@code systemctl} replacement script plus the pinned Ubuntu Noble
     * GA-pocket Python 3 runtime that executes it, installed as a private
     * overlay bound at {@link #SERVICES_OVERLAY_GUEST_DIR}.
     *
     * <p>The Python artifact set is the real dependency closure of
     * {@code python3} against the signed
     * {@code dists/noble/main/binary-arm64/Packages} index minus everything the
     * curated {@link #ubuntuBaseArm64()} rootfs already ships: the seven
     * python3 packages plus {@code libexpat1}, {@code libsqlite3-0},
     * {@code libreadline8t64}, {@code readline-common}, {@code tzdata},
     * {@code media-types}, and {@code netbase}. The {@code python3.12}
     * interpreter is statically linked against its own runtime (it needs only
     * {@code libm}, {@code libz}, {@code libexpat}, {@code libc}), so no
     * {@code libpython3.12} shared-library package is part of the closure.</p>
     *
     * <p>The script itself is vendored into the app package
     * ({@code assets/services/systemctl3.py}) from the pinned upstream commit
     * {@code 8bd65bec50650fcc740e2d55c32162ba7bafdfc8} (tag {@code v1.7.1097})
     * of {@code gdraheim/docker-systemctl-replacement}, EUPL-licensed, with its
     * licence text alongside. Both are digest-pinned like every downloaded
     * artifact. {@code usr/bin/service} is a small product-owned
     * {@code service(8)}-compatible shim that delegates to the vendored
     * {@code systemctl}.</p>
     *
     * <p>The same overlay carries the compose payload (Phase 3B): the
     * product-owned udocker/compose runner {@code lw_compose_runtime.py} with
     * its {@code udocker} and {@code lw-compose-supervisor} launchers, the
     * byte-for-byte pinned udocker 1.3.17 (Apache-2.0) and PyYAML 6.0.1 (MIT)
     * source tarballs with their licence/notice texts, and the global
     * {@code lw-compose-supervisor.service} unit the bridge enables before
     * {@code systemctl init}. The launchers run under the payload's
     * {@code python3.12}; the upstream udocker helper tarball is never
     * shipped or downloaded — the inner PRoot is the packaged NusaDesk build.
     * Provenance and the honest runtime contract live in
     * {@code assets/compose/THIRD_PARTY_NOTICES.md}, vendored into the
     * overlay at {@code usr/share/lw-services/compose/}.</p>
     */
    public static GuestAddonPayloadProfile guestServiceBridge() {
        String pool = "https://ports.ubuntu.com/ubuntu-ports/";
        List<PayloadArtifact> artifacts = Arrays.asList(
                new PayloadArtifact(
                        "python3_3.12.3-0ubuntu1_arm64",
                        "3.12.3-0ubuntu1",
                        pool + "pool/main/p/python3-defaults/python3_3.12.3-0ubuntu1_arm64.deb",
                        "6ee6fcd0dae8beaa3090704c21864cf44121646444d14127cf675131ced5d6cb",
                        "linux/arm64",
                        24_100L,
                        42_176L),
                new PayloadArtifact(
                        "python3-minimal_3.12.3-0ubuntu1_arm64",
                        "3.12.3-0ubuntu1",
                        pool + "pool/main/p/python3-defaults/python3-minimal_3.12.3-0ubuntu1_arm64.deb",
                        "7aed0e332aeb4de38ae918a35c8853e9ac1c85e50f16790395bb50a7cee29c7b",
                        "linux/arm64",
                        27_214L,
                        91_741L),
                new PayloadArtifact(
                        "python3.12_3.12.3-1_arm64",
                        "3.12.3-1",
                        pool + "pool/main/p/python3.12/python3.12_3.12.3-1_arm64.deb",
                        "c73fb14a5bdbdc9a026d62d4bee3aeec0f1d5df0c40abd6cc136403b1b24df9a",
                        "linux/arm64",
                        650_732L,
                        712_229L),
                new PayloadArtifact(
                        "python3.12-minimal_3.12.3-1_arm64",
                        "3.12.3-1",
                        pool + "pool/main/p/python3.12/python3.12-minimal_3.12.3-1_arm64.deb",
                        "c5ce70a0cc04dbe9e7e6a1bdf21c0989f7d7c69ef2fd53ff98a6d3ac00d02d5f",
                        "linux/arm64",
                        2_251_184L,
                        7_902_606L),
                new PayloadArtifact(
                        "libpython3.12-minimal_3.12.3-1_arm64",
                        "3.12.3-1",
                        pool + "pool/main/p/python3.12/libpython3.12-minimal_3.12.3-1_arm64.deb",
                        "905e0dfd142df77fe170c6a4b19512cc627d95ee1df98a7d919936a3aad265f6",
                        "linux/arm64",
                        828_606L,
                        5_116_126L),
                new PayloadArtifact(
                        "libpython3.12-stdlib_3.12.3-1_arm64",
                        "3.12.3-1",
                        pool + "pool/main/p/python3.12/libpython3.12-stdlib_3.12.3-1_arm64.deb",
                        "f5669a5361bcb028bbbcf88d5811f83298278ae8247eff249164a5e0333c9a7e",
                        "linux/arm64",
                        2_036_086L,
                        10_478_583L),
                new PayloadArtifact(
                        "libpython3-stdlib_3.12.3-0ubuntu1_arm64",
                        "3.12.3-0ubuntu1",
                        pool + "pool/main/p/python3-defaults/libpython3-stdlib_3.12.3-0ubuntu1_arm64.deb",
                        "f6fb3faf5bba564776a040f2911233dc56caccb7627c81cebae3bc919f5d5d0a",
                        "linux/arm64",
                        9_896L,
                        20_136L),
                new PayloadArtifact(
                        "libexpat1_2.6.1-2build1_arm64",
                        "2.6.1-2build1",
                        pool + "pool/main/e/expat/libexpat1_2.6.1-2build1_arm64.deb",
                        "f6cea0cbe617519480ab3501166eab7092a9a75332cb186c30eee8107da381b9",
                        "linux/arm64",
                        76_070L,
                        401_563L),
                new PayloadArtifact(
                        "libsqlite3-0_3.45.1-1ubuntu2_arm64",
                        "3.45.1-1ubuntu2",
                        pool + "pool/main/s/sqlite3/libsqlite3-0_3.45.1-1ubuntu2_arm64.deb",
                        "b71c08ea650c212b2c8fa5bbcef9957e5e85c8f5c484a85cfb8961a8271bf376",
                        "linux/arm64",
                        703_114L,
                        1_532_563L),
                new PayloadArtifact(
                        "libreadline8t64_8.2-4build1_arm64",
                        "8.2-4build1",
                        pool + "pool/main/r/readline/libreadline8t64_8.2-4build1_arm64.deb",
                        "7f46b2f3ca588cd2f05d2cfb6844017309760b4201105cdfda4b339f9e6c69da",
                        "linux/arm64",
                        153_046L,
                        498_416L),
                new PayloadArtifact(
                        "readline-common_8.2-4build1_all",
                        "8.2-4build1",
                        pool + "pool/main/r/readline/readline-common_8.2-4build1_all.deb",
                        "879bfd7f8a9bc4c0f7cdc777cdd8bc6de5f8c4a2ac80c060322a1b22f13504bb",
                        "linux/arm64",
                        56_484L,
                        58_889L),
                new PayloadArtifact(
                        "tzdata_2024a-2ubuntu1_all",
                        "2024a-2ubuntu1",
                        pool + "pool/main/t/tzdata/tzdata_2024a-2ubuntu1_all.deb",
                        "f5bca0c788a4fc465c435f7e8a54b2594e9cbf1a22abb263a59e393f1cb666e1",
                        "linux/arm64",
                        273_318L,
                        740_320L),
                new PayloadArtifact(
                        "media-types_10.1.0_all",
                        "10.1.0",
                        pool + "pool/main/m/media-types/media-types_10.1.0_all.deb",
                        "31bfb7eec55ab6d34a50ba995150e1498d4cb897714085d8025e330d3b529747",
                        "linux/arm64",
                        27_474L,
                        82_830L),
                new PayloadArtifact(
                        "netbase_6.4_all",
                        "6.4",
                        pool + "pool/main/n/netbase/netbase_6.4_all.deb",
                        "8cdbc9c3dca01e660759bf9d840f72e45ac72faf5d19ca1faecacaf6a60c1a87",
                        "linux/arm64",
                        13_112L,
                        22_269L));
        List<VendoredFile> vendoredFiles = Arrays.asList(
                // systemctl3.py — docker-systemctl-replacement v1.7.1097,
                // commit 8bd65bec50650fcc740e2d55c32162ba7bafdfc8, EUPL-1.2.
                new VendoredFile(
                        "services/systemctl3.py",
                        "usr/bin/systemctl",
                        true,
                        "5f5a47f321c7a8881dfd01f774106dc4012d664bee238eada86a7112674fea93"),
                new VendoredFile(
                        "services/EUPL-LICENSE.md",
                        "usr/share/lw-services/EUPL-LICENSE.md",
                        false,
                        "67e6b5e4f3f4c6b3c4fbca47f0202823f38344f03f1b54e3dce9aef17406b0a1"),
                // Product-owned `service(8)` shim delegating to the vendored
                // systemctl replacement; re-pin this digest with the file.
                new VendoredFile(
                        "services/lw-service-shim",
                        "usr/bin/service",
                        true,
                        "c30cb355248db37f4b32d81c97bf3f83ec5f0e4fd545623052e4faec9f7036e1"),
                // Synthesized /proc substitutes bound over the real ones.
                // SELinux denies the app domains /proc/uptime and /proc/stat;
                // without them the replacement's boot-time probe returns
                // "now" and its read path truncates every status file it
                // touches (services always read "inactive", the manager
                // reports "offline"). Any well-formed content works: the only
                // consumed field is /proc/stat's btime, and a value near the
                // epoch means "this session is the boot" — status files the
                // bridge wipes at session start stay readable afterwards.
                new VendoredFile(
                        "services/lw-proc-uptime",
                        "lw-proc/uptime",
                        false,
                        "aee2405d14e2b7bce47ef53a8bf9ae9d829ee61214df681d75de97f049db60c2"),
                new VendoredFile(
                        "services/lw-proc-stat",
                        "lw-proc/stat",
                        false,
                        "5bfa6dda17c3b8026629a2f6a0627c4edbd7fe725f926a1c05d2416d53b75e5f"),
                // Product-owned session supervisor: the session's single PRoot
                // tracer runs this script as its initial tracee so the service
                // manager and the session daemon share one process tree —
                // PRoot mediates kill(2), so a terminal `systemctl stop` can
                // only reach a manager-run service when both live under the
                // same tracer. Re-pin this digest with the file.
                new VendoredFile(
                        "services/lw-session-supervisor",
                        "usr/sbin/lw-session-supervisor",
                        true,
                        "3d8ec11a9e5c0c185bb2eb75b74b8ed2e2f260b32b7056c9b6afebc445f8d790"),
                // Product-owned user-service manager (ADR-0024): the session
                // manager starts this unit, which runs the vendored systemctl3
                // in --user mode so enabled user units come up with the
                // session. Re-pin this digest with the launcher script.
                new VendoredFile(
                        "services/lw-user-manager",
                        "usr/local/bin/lw-user-manager",
                        true,
                        "c50ce5b3192a8f90c0b0ace9427081ae8872dbd221b9f72d3268f0085d2e8b3d"),
                // Re-pin this digest with the unit file.
                new VendoredFile(
                        "services/lw-user-manager.service",
                        "etc/systemd/system/lw-user-manager.service",
                        false,
                        "bef810d075eedfc8d71f541b3601f9d90b558ca386f0002b99a8815827255fc9"),
                // Compose payload (Phase 3B). The tarballs are byte-for-byte
                // upstream source releases — udocker 1.3.17 (Apache-2.0,
                // github.com/indigo-dc/udocker tag 1.3.17) and PyYAML 6.0.1
                // (MIT, github.com/yaml/pyyaml tag 6.0.1) — extracted
                // guest-side by the product-owned runner after re-verifying
                // these digests; the upstream udocker helper tarball is
                // never bundled or downloaded.
                //
                // The asset names end in .tgz, not .tar.gz: AGP's asset
                // merge (MergedAssetWriter) gunzips any asset whose
                // extension is "gz" and strips the suffix before the APK is
                // written, and noCompress cannot prevent it — the installed
                // overlay paths keep the upstream .tar.gz names the runner
                // expects, only the packaged asset names differ.
                new VendoredFile(
                        "compose/udocker-1.3.17.tgz",
                        "usr/local/lib/nusadesk/compose/udocker-1.3.17.tar.gz",
                        false,
                        "f97ec97679133b5ada780025e6e263607cb4ec3401786f1db135e46153c90bd1"),
                new VendoredFile(
                        "compose/PyYAML-6.0.1.tgz",
                        "usr/local/lib/nusadesk/compose/PyYAML-6.0.1.tar.gz",
                        false,
                        "bfdf460b1736c775f2ba9f6a92bca30bc2095067b8a9d77876d1fad6cc3b4a43"),
                // Upstream licence text and the compose provenance/limitation
                // notes ride inside the overlay.
                new VendoredFile(
                        "compose/LICENSE-udocker-1.3.17.txt",
                        "usr/share/lw-services/udocker/LICENSE-udocker-1.3.17.txt",
                        false,
                        "b40930bbcf80744c86c46a12bc9da056641d722716c378f5659b9e555ef833e1"),
                new VendoredFile(
                        "compose/THIRD_PARTY_NOTICES.md",
                        "usr/share/lw-services/compose/THIRD_PARTY_NOTICES.md",
                        false,
                        "858151096b56789356738d4d1203a6188f11132b12e9e3d24bb9f3e41c00ab11"),
                // Product-owned compose runtime library and launchers
                // (#!/usr/bin/python3.12 shebangs; also runnable as
                // `python3.12 <path>`). Re-pin these digests with the files.
                new VendoredFile(
                        "compose/lw_compose_runtime.py",
                        "usr/local/lib/nusadesk/compose/lw_compose_runtime.py",
                        false,
                        "b9f7eaa89a8c9220a30f19b18ac8a0e261e262c2d97715232e466cbc0c6481d3"),
                new VendoredFile(
                        "compose/lw-udocker",
                        "usr/local/bin/udocker",
                        true,
                        "964e42ce3472c218abaebe4015922aedbb5ee40c1888661e907b2c17f1d66511"),
                new VendoredFile(
                        "compose/lw-compose-supervisor",
                        "usr/local/bin/lw-compose-supervisor",
                        true,
                        "8c38ebffab0fe6e4fe425e058ac835a9a7ab3e06d28cb4e0742a7b5cf9d9a744"),
                // Global compose supervisor unit: product-owned bootstrap
                // enabled before `systemctl init` by GuestServiceBridge
                // (the vendored systemctl3 only restart-supervises units
                // enabled at init). Re-pin this digest with the file.
                new VendoredFile(
                        "compose/etc/systemd/system/lw-compose-supervisor.service",
                        "etc/systemd/system/lw-compose-supervisor.service",
                        false,
                        "4ab82d21f072dbec7223e16dbfb0c78e7d2d44190da9e897d575557471a5b4af"));
        return new GuestAddonPayloadProfile(
                "guest-service-bridge",
                "Guest services (systemctl)",
                "1.7.1097",
                SERVICES_OVERLAY_GUEST_DIR,
                SERVICES_OVERLAY_ENTRYPOINT,
                artifacts,
                vendoredFiles,
                Arrays.asList(
                        "usr/bin/systemctl",
                        "usr/bin/service",
                        "usr/bin/python3",
                        "usr/local/bin/udocker",
                        "usr/local/bin/lw-compose-supervisor",
                        "usr/local/lib/nusadesk/compose/lw_compose_runtime.py",
                        "usr/local/lib/nusadesk/compose/udocker-1.3.17.tar.gz",
                        "usr/local/lib/nusadesk/compose/PyYAML-6.0.1.tar.gz",
                        "usr/share/lw-services/udocker/LICENSE-udocker-1.3.17.txt",
                        "usr/share/lw-services/compose/THIRD_PARTY_NOTICES.md",
                        "etc/systemd/system/lw-compose-supervisor.service"),
                Arrays.asList("usr/bin/env", "usr/bin/sh"));
    }

    /**
     * Every curated add-on, in install order. The launcher binds each activated
     * overlay at its fixed guest dir, so the guest always sees whatever add-ons
     * are installed without a caller having to name them.
     */
    public static List<GuestAddonPayloadProfile> guestAddons() {
        return Arrays.asList(guestSshAddon(), guestServiceBridge());
    }
}
