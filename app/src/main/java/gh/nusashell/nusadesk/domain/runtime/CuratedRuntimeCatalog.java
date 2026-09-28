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

    /**
     * Fixed guest-visible mount point of the guest base-extras add-on
     * overlay: the pinned Mozilla CA certificate set and the {@code openssl}
     * CLI the curated rootfs lacks.
     */
    public static final String BASE_EXTRAS_OVERLAY_GUEST_DIR = "/opt/lw-base";
    /**
     * Guest-relative path of the AArch64 ELF entrypoint the base-extras
     * overlay must provide. {@code ca-certificates} is data only and ships no
     * ELF at all, so the entrypoint is the {@code openssl} interpreter-free
     * CLI from the second artifact — the uniform entrypoint ABI check stays
     * meaningful for this data payload.
     */
    public static final String BASE_EXTRAS_OVERLAY_ENTRYPOINT = "usr/bin/openssl";

    /**
     * Fixed guest-visible mount point of the guest systemd D-Bus-face add-on
     * overlay (ADR-0024): the vendored {@code dbus-daemon}, its client tools
     * and {@code libdbus-1}, the {@code python3-dbus} bindings the
     * {@code busctl} shim imports, and the product-owned
     * {@code org.freedesktop.systemd1} provider that delegates to the
     * existing {@code systemctl} CLI.
     */
    public static final String DBUS_FACE_OVERLAY_GUEST_DIR = "/opt/lw-dbus";
    /**
     * Guest-relative path of the AArch64 ELF entrypoint the D-Bus-face
     * overlay must provide: the {@code dbus-daemon} binary the provider
     * spawns to offer the session bus. The provider and the {@code busctl}
     * shim are scripts, so the uniform ELF entrypoint check is carried by
     * the daemon.
     */
    public static final String DBUS_FACE_OVERLAY_ENTRYPOINT = "usr/bin/dbus-daemon";

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
                // Kept byte-identical to upstream and therefore NOT the file
                // the guest runs: its `#! /usr/bin/env python3` shebang would
                // put the whole bridge behind a PATH lookup. The wrapper below
                // execs this path with an absolute interpreter instead.
                new VendoredFile(
                        "services/systemctl3.py",
                        "usr/lib/nusadesk/systemctl3.py",
                        false,
                        "5f5a47f321c7a8881dfd01f774106dc4012d664bee238eada86a7112674fea93"),
                // Product-owned entrypoint that the guest actually executes,
                // wired to /usr/bin/systemctl. Re-pin this digest with the
                // file; GuestServiceBridgeTest pins its interpreter path
                // against SERVICES_OVERLAY_GUEST_DIR so the two cannot drift.
                new VendoredFile(
                        "services/lw-systemctl",
                        "usr/bin/systemctl",
                        true,
                        "11375c68c0d17b55f676a2692d4cb0a0ff4180c978b2cd61750236bc79bb7078"),
                // Shared unit-search-path list (ADR-0024): the single source
                // of truth both faces read — the lw-systemctl wrapper for
                // `show --property=UnitPath --value`, and the D-Bus provider
                // for Manager.UnitPath — so the CLI and the bus can never
                // disagree. Re-pin this digest with the file.
                new VendoredFile(
                        "services/lw-unit-paths",
                        "usr/share/lw-services/unit-paths",
                        false,
                        "6cce368c0e2d6ccb9671744059c35cc914fd2f7cc8cbd8ca1807f71b6b8991fd"),
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
                        "281e9eca8b6402a0f4108e2c060c082c362201ff1e9a4bc8bda7d7dfccb936a8"),
                // Product-owned user-service manager (ADR-0024): the session
                // manager starts this unit, which runs the vendored systemctl3
                // in --user mode so enabled user units come up with the
                // session. Re-pin this digest with the launcher script.
                new VendoredFile(
                        "services/lw-user-manager",
                        "usr/local/bin/lw-user-manager",
                        true,
                        "f49365fc9b4ca91ce98011286ee1e1132255be5ed4baba71a178878e1dc7a5e2"),
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
     * Curated guest base-extras add-on: the pinned Mozilla CA certificate
     * set plus the {@code openssl} CLI, installed as a private overlay bound
     * at {@link #BASE_EXTRAS_OVERLAY_GUEST_DIR}.
     *
     * <p>The curated {@link #ubuntuBaseArm64()} rootfs ships no CA
     * certificates at all — no {@code /etc/ssl/} tree exists — so every TLS
     * client in the guest fails certificate verification before doing
     * anything. This add-on delivers the missing trust store. The
     * {@code ca-certificates} package is architecture-independent
     * ({@code _all}); it carries the source certs under
     * {@code usr/share/ca-certificates/mozilla/} plus the
     * {@code update-ca-certificates} maintainer tool, but not the generated
     * {@code /etc/ssl/certs/ca-certificates.crt} bundle — on a real Debian
     * that bundle is postinst output, and this product never runs maintainer
     * scripts. {@code GuestBaseExtras} therefore builds the bundle
     * deterministically at wire time by concatenating the pinned
     * {@code mozilla/*.crt} set in sorted order, exactly what
     * {@code update-ca-certificates} emits for the default set.</p>
     *
     * <p>{@code openssl} rides along for three reasons: it is
     * {@code ca-certificates}' declared dependency (the shipped
     * {@code update-ca-certificates} invokes it), the profile contract
     * requires an AArch64 ELF entrypoint and the data-only package provides
     * none, and it is the conventional guest tool for inspecting the very
     * trust store this overlay installs. Its runtime dependencies
     * ({@code libssl3t64}, {@code libc6}) already ship in the base rootfs, so
     * nothing else is extracted for it. Provenance and licence notes are
     * vendored into the overlay at
     * {@code usr/share/lw-base/THIRD_PARTY_NOTICES.md}.</p>
     */
    public static GuestAddonPayloadProfile guestBaseExtras() {
        String pool = "https://ports.ubuntu.com/ubuntu-ports/";
        List<PayloadArtifact> artifacts = Arrays.asList(
                new PayloadArtifact(
                        "ca-certificates_20240203_all",
                        "20240203",
                        pool + "pool/main/c/ca-certificates/ca-certificates_20240203_all.deb",
                        "641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a",
                        "linux/arm64",
                        159_378L,
                        256_156L),
                new PayloadArtifact(
                        "openssl_3.0.13-0ubuntu3_arm64",
                        "3.0.13-0ubuntu3",
                        pool + "pool/main/o/openssl/openssl_3.0.13-0ubuntu3_arm64.deb",
                        "9b7136b1af32fbdefc2eac61bae86f8304c603c7b9a0297b20a1e31c522b024b",
                        "linux/arm64",
                        983_800L,
                        1_712_035L));
        // Provenance note; the digest is re-pinned with the asset, like every
        // vendored file.
        List<VendoredFile> vendoredFiles = Collections.singletonList(
                new VendoredFile(
                        "base-extras/THIRD_PARTY_NOTICES.md",
                        "usr/share/lw-base/THIRD_PARTY_NOTICES.md",
                        false,
                        "9a095f1453f0df751f4d56112faebf44e453cc5946808a76380436c0ce1b26ce"));
        return new GuestAddonPayloadProfile(
                "guest-base-extras",
                "Guest base extras (CA certificates)",
                "20240203",
                BASE_EXTRAS_OVERLAY_GUEST_DIR,
                BASE_EXTRAS_OVERLAY_ENTRYPOINT,
                artifacts,
                vendoredFiles,
                Arrays.asList(
                        "usr/share/ca-certificates/mozilla",
                        "usr/sbin/update-ca-certificates",
                        "usr/share/lw-base/THIRD_PARTY_NOTICES.md"),
                Collections.<String>emptyList());
    }

    /**
     * Curated guest systemd D-Bus-face add-on (ADR-0024): the vendored
     * {@code dbus-daemon} session bus plus the product-owned
     * {@code org.freedesktop.systemd1} provider and {@code busctl} shim,
     * installed as a private overlay bound at
     * {@link #DBUS_FACE_OVERLAY_GUEST_DIR}.
     *
     * <p>The face exists for D-Bus-gated consumers (e.g. openclaw's
     * runtime-bus transport candidate, which probes {@code busctl --user
     * --auto-start=no get-property org.freedesktop.systemd1 … Manager
     * Version}). It is deliberately a separate add-on from the service
     * bridge — the bridge owns the {@code systemctl} surface — but it is a
     * compatibility face, not a second manager: the provider translates
     * D-Bus calls into the same {@code systemctl} CLI the bridge installs
     * and the same unit files, so the two can never disagree. {@code
     * requiredRootfsTools} declares that dependency honestly: the scripts'
     * {@code /usr/bin/python3.12} shebang and {@code /usr/bin/systemctl}
     * both resolve through the bridge's wiring.</p>
     *
     * <p>The artifact set is the real dependency closure of
     * {@code dbus-daemon} + {@code python3-dbus} against the signed
     * {@code dists/noble/main/binary-arm64/Packages} index minus what the
     * curated {@link #ubuntuBaseArm64()} rootfs already ships
     * ({@code libsystemd}, {@code libselinux}, {@code libaudit},
     * {@code libcap-ng}, libc): {@code libapparmor1} and {@code libexpat1}
     * are the only runtime libraries the base lacks. {@code libexpat1} is
     * the same pinned artifact the service bridge already carries — shipped
     * here too so this overlay's closure is self-contained.</p>
     *
     * <p>{@code python3-dbus} is a C extension; the ABI match with the
     * bridge's pinned {@code python3.12} interpreter is pinned structurally:
     * {@code requiredFiles} names
     * {@code _dbus_bindings.cpython-312-aarch64-linux-gnu.so}, so an
     * upstream rebuild against a different interpreter or architecture
     * fails install verification instead of importing at runtime.
     * {@code _dbus_glib_bindings} ships inside the same package but is
     * inert: nothing loads it and {@code libglib} is deliberately absent —
     * the provider speaks the D-Bus wire protocol directly rather than
     * paying GLib's dependency tree for a main loop.</p>
     *
     * <p>Provenance and licences (D-Bus: AFL-2.1/GPL-2.0+ dual; dbus-python:
     * AFL-2.1/GPL-2.0+ and Expat; libapparmor1: LGPL-2.1+; libexpat1: MIT)
     * are vendored into the overlay at
     * {@code usr/share/lw-dbus/THIRD_PARTY_NOTICES.md}.</p>
     */
    public static GuestAddonPayloadProfile guestSystemdBusFace() {
        String pool = "https://ports.ubuntu.com/ubuntu-ports/";
        List<PayloadArtifact> artifacts = Arrays.asList(
                new PayloadArtifact(
                        "dbus-daemon_1.14.10-4ubuntu4_arm64",
                        "1.14.10-4ubuntu4",
                        pool + "pool/main/d/dbus/dbus-daemon_1.14.10-4ubuntu4_arm64.deb",
                        "f86ef31871dee6bf14a6a8dadeb6d6a800772cca42bb2697ed2398ea08c575aa",
                        "linux/arm64",
                        115_004L,
                        371_084L),
                new PayloadArtifact(
                        "dbus-bin_1.14.10-4ubuntu4_arm64",
                        "1.14.10-4ubuntu4",
                        pool + "pool/main/d/dbus/dbus-bin_1.14.10-4ubuntu4_arm64.deb",
                        "60fdfc72ab3dd550b48d044bf45f1573ef890555f243096c32e1ceb7ce318c16",
                        "linux/arm64",
                        38_824L,
                        368_493L),
                new PayloadArtifact(
                        "libdbus-1-3_1.14.10-4ubuntu4_arm64",
                        "1.14.10-4ubuntu4",
                        pool + "pool/main/d/dbus/libdbus-1-3_1.14.10-4ubuntu4_arm64.deb",
                        "c269be28a2ed45d08f85ca2e7eb8a333f7ef5b01a052f4ad561e6834fe4c8964",
                        "linux/arm64",
                        209_862L,
                        486_160L),
                new PayloadArtifact(
                        "dbus-session-bus-common_1.14.10-4ubuntu4_all",
                        "1.14.10-4ubuntu4",
                        pool + "pool/main/d/dbus/dbus-session-bus-common_1.14.10-4ubuntu4_all.deb",
                        "e9b9aaedfea55df0236cad9c5c060aa7e9dfac38084cbf72aae32a9bafae0ec7",
                        "linux/arm64",
                        80_354L,
                        94_605L),
                new PayloadArtifact(
                        "python3-dbus_1.3.2-5build3_arm64",
                        "1.3.2-5build3",
                        pool + "pool/main/d/dbus-python/python3-dbus_1.3.2-5build3_arm64.deb",
                        "1be335428c6731a33f648af5d77c0dbce9d30158957c1f7549b5267422f5090c",
                        "linux/arm64",
                        99_500L,
                        478_371L),
                new PayloadArtifact(
                        "libapparmor1_4.0.0-beta3-0ubuntu3_arm64",
                        "4.0.0-beta3-0ubuntu3",
                        pool + "pool/main/a/apparmor/libapparmor1_4.0.0-beta3-0ubuntu3_arm64.deb",
                        "8c29035a9153c5087a4853dae51db5b804d52be90ca5b6f08e9d9684e6d64448",
                        "linux/arm64",
                        50_004L,
                        153_894L),
                new PayloadArtifact(
                        "libexpat1_2.6.1-2build1_arm64",
                        "2.6.1-2build1",
                        pool + "pool/main/e/expat/libexpat1_2.6.1-2build1_arm64.deb",
                        "f6cea0cbe617519480ab3501166eab7092a9a75332cb186c30eee8107da381b9",
                        "linux/arm64",
                        76_070L,
                        401_563L));
        List<VendoredFile> vendoredFiles = Arrays.asList(
                // Product-owned busctl shim (python3-dbus client; the
                // consumer-facing command). Re-pin this digest with the file.
                new VendoredFile(
                        "services/lw-busctl",
                        "usr/bin/busctl",
                        true,
                        "7d3417aa85c28035233c2fb0f180d2ebdcbc8964a3d77d9bdd7aa3f6ca977260"),
                // Product-owned org.freedesktop.systemd1 provider: owns the
                // name on the session bus it spawns and delegates every
                // answer to the bridge's systemctl CLI. Re-pin this digest
                // with the file.
                new VendoredFile(
                        "services/lw-systemd-dbus-provider",
                        "usr/sbin/lw-systemd-dbus-provider",
                        true,
                        "0c83e2ad072178a918072071047aa6aa5326e5e027f0347c099b3162ab288859"),
                // The provider's user unit, enabled into default.target at
                // wire-up. Re-pin this digest with the file.
                new VendoredFile(
                        "services/lw-systemd-dbus-provider.service",
                        "etc/systemd/user/lw-systemd-dbus-provider.service",
                        false,
                        "ad37536f701514ef4e50f0783d77d1cc6f0a623f54baf6f6a1c375e69afa7cc5"),
                // Provenance/licences for the D-Bus payload. Re-pin with the
                // file.
                new VendoredFile(
                        "services/THIRD_PARTY_NOTICES.md",
                        "usr/share/lw-dbus/THIRD_PARTY_NOTICES.md",
                        false,
                        "d7227c68e7feca289735bb36aef32ed79ab6d60bbfad87192c41bc57cd5dfa28"));
        return new GuestAddonPayloadProfile(
                "guest-systemd-dbus-face",
                "Guest systemd D-Bus face (org.freedesktop.systemd1)",
                "1.14.10-4ubuntu4",
                DBUS_FACE_OVERLAY_GUEST_DIR,
                DBUS_FACE_OVERLAY_ENTRYPOINT,
                artifacts,
                vendoredFiles,
                Arrays.asList(
                        "usr/bin/dbus-uuidgen",
                        "usr/lib/aarch64-linux-gnu/libdbus-1.so.3",
                        "usr/lib/aarch64-linux-gnu/libapparmor.so.1",
                        "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                        "usr/share/dbus-1/session.conf",
                        "usr/lib/python3/dist-packages/dbus",
                        "usr/lib/python3/dist-packages/_dbus_bindings.cpython-312-aarch64-linux-gnu.so",
                        "etc/systemd/user/lw-systemd-dbus-provider.service",
                        "usr/share/lw-dbus/THIRD_PARTY_NOTICES.md"),
                Arrays.asList("usr/bin/systemctl", "usr/bin/python3.12"));
    }

    /**
     * Every curated add-on, in install order. The launcher binds each activated
     * overlay at its fixed guest dir, so the guest always sees whatever add-ons
     * are installed without a caller having to name them.
     */
    public static List<GuestAddonPayloadProfile> guestAddons() {
        return Arrays.asList(guestSshAddon(), guestServiceBridge(), guestBaseExtras(),
                guestSystemdBusFace());
    }
}
