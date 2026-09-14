package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Names the supplementary group IDs a guest process really carries, so the
 * guest's own account tools can resolve them (ADR-0011).
 *
 * <p>PRoot is a compatibility layer, not a credential boundary: an Android
 * app cannot drop or change its real supplementary groups (that needs
 * {@code CAP_SETGID}, and PRoot {@code -0} only fakes the ids reported by
 * {@code getuid}/{@code getgid}), so every guest process inherits the app's
 * Android group IDs (e.g. {@code 3003}). Guest glibc then fails to map them
 * through {@code /etc/group}, and coreutils' {@code groups} — run by Ubuntu's
 * {@code /etc/bash.bashrc} on every interactive login — prints one
 * {@code groups: cannot find name for group ID N} line per inherited ID.</p>
 *
 * <p>The fix is representation, not suppression: the guest's group database is
 * extended with entries for the group IDs the kernel really reports for this
 * process tree, so {@code groups}, {@code id}, and {@code ls -l} show them by
 * name. Nothing is filtered, hidden, or faked — a guest admin sees the real
 * membership, now named.</p>
 *
 * <p>Names are {@code aid_<name>} for Android AIDs documented in AOSP's
 * {@code android_filesystem_config.h}, and {@code aid_<gid>} for anything else,
 * so an entry can never be mistaken for a guest-owned account or collide with
 * guest group names. Entries are only ever added for IDs the guest does not
 * already name (the guest's own {@code /etc/group} wins).</p>
 *
 * <p>Pure JVM: no Android imports, no process execution, no writes. Unit
 * testable against a literal {@code /proc/<pid>/status} text.</p>
 */
public final class GuestSupplementaryGroups {

    /** Host file reporting this process's real supplementary group IDs. */
    public static final String PROC_STATUS = "/proc/self/status";

    /** Prefix that marks a group entry as an inherited Android AID. */
    public static final String AID_PREFIX = "aid_";

    /**
     * Android AID names that can be granted to an app process, pinned from
     * AOSP {@code system/core/libcutils/include/private/android_filesystem_config.h}
     * (values are platform ABI and do not change between Android releases).
     * The names below {@code 1022}/{@code 1025} are deliberately absent: AOSP
     * marks those IDs unused. Verified against {@code id} output on an Android
     * 10 arm64 device for every ID it grants an app process.
     */
    private static final Map<Integer, String> AID_NAMES = aidNames();

    private GuestSupplementaryGroups() {
    }

    /**
     * Parse the {@code Groups:} line of a {@code /proc/<pid>/status} text.
     *
     * @return the supplementary group IDs in kernel order; empty when the text
     *         carries no {@code Groups:} line. Non-numeric tokens, zero, and
     *         negative values are ignored: this list only affects how the guest
     *         names real group IDs, so a junk token must not break a session.
     */
    public static List<Integer> parseGroupIds(String procStatusText) {
        if (procStatusText == null) {
            return Collections.emptyList();
        }
        for (String line : procStatusText.split("\n")) {
            if (!line.startsWith("Groups:")) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            for (String token : line.substring("Groups:".length()).trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(token);
                    if (id > 0) {
                        ids.add(id);
                    }
                } catch (NumberFormatException ignored) {
                    // Best-effort naming only; see the method contract.
                }
            }
            return Collections.unmodifiableList(ids);
        }
        return Collections.emptyList();
    }

    /** Read and parse {@link #PROC_STATUS} (or any equivalent status file). */
    public static List<Integer> readGroupIds(Path procStatusFile) throws IOException {
        if (procStatusFile == null) {
            throw new IllegalArgumentException("procStatusFile must not be null");
        }
        return parseGroupIds(
                new String(Files.readAllBytes(procStatusFile), StandardCharsets.UTF_8));
    }

    /**
     * Guest {@code /etc/group} lines for the given group IDs: ascending,
     * de-duplicated, {@code name:x:gid:} with no members. IDs that are not
     * positive are dropped (group {@code 0} is root's group in the guest and is
     * never renamed).
     */
    public static List<String> guestGroupEntries(List<Integer> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> entries = new ArrayList<>();
        for (int gid : new TreeSet<>(groupIds)) {
            if (gid <= 0) {
                continue;
            }
            entries.add(nameFor(gid) + ":x:" + gid + ":");
        }
        return Collections.unmodifiableList(entries);
    }

    /** Guest group name for one Android group ID. */
    public static String nameFor(int gid) {
        String name = AID_NAMES.get(gid);
        return AID_PREFIX + (name != null ? name : Integer.toString(gid));
    }

    private static Map<Integer, String> aidNames() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(1000, "system");
        names.put(1001, "radio");
        names.put(1002, "bluetooth");
        names.put(1003, "graphics");
        names.put(1004, "input");
        names.put(1005, "audio");
        names.put(1006, "camera");
        names.put(1007, "log");
        names.put(1008, "compass");
        names.put(1009, "mount");
        names.put(1010, "wifi");
        names.put(1011, "adb");
        names.put(1012, "install");
        names.put(1013, "media");
        names.put(1014, "dhcp");
        names.put(1015, "sdcard_rw");
        names.put(1016, "vpn");
        names.put(1017, "keystore");
        names.put(1018, "usb");
        names.put(1019, "drm");
        names.put(1020, "mdnsr");
        names.put(1021, "gps");
        names.put(1023, "media_rw");
        names.put(1024, "mtp");
        names.put(1026, "drmrpc");
        names.put(1027, "nfc");
        names.put(1028, "sdcard_r");
        names.put(1029, "clat");
        names.put(1030, "loop_radio");
        names.put(1031, "media_drm");
        names.put(1032, "package_info");
        names.put(1033, "sdcard_pics");
        names.put(1034, "sdcard_av");
        names.put(1035, "sdcard_all");
        names.put(1036, "logd");
        names.put(1037, "shared_relro");
        names.put(1038, "dbus");
        names.put(1039, "tunnel");
        names.put(1040, "accessibility");
        names.put(1041, "vendor_graphics");
        names.put(1042, "vendor_connectivity");
        names.put(1043, "tracing");
        names.put(1044, "research");
        names.put(1045, "ota_update");
        names.put(1046, "vendor_storage");
        names.put(1078, "ext_data_rw");
        names.put(1079, "ext_obb_rw");
        names.put(2000, "shell");
        names.put(2001, "cache");
        names.put(2002, "diag");
        names.put(3001, "net_bt_admin");
        names.put(3002, "net_bt");
        names.put(3003, "inet");
        names.put(3004, "net_raw");
        names.put(3005, "net_admin");
        names.put(3006, "net_bw_stats");
        names.put(3007, "net_bw_acct");
        names.put(3009, "readproc");
        names.put(3010, "wakelock");
        names.put(3011, "uhid");
        names.put(3012, "readtracefs");
        names.put(3013, "gpuservice");
        names.put(9997, "everybody");
        names.put(9998, "misc");
        names.put(9999, "nobody");
        Map<Integer, String> pinned = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : names.entrySet()) {
            if (!entry.getValue().matches("[a-z0-9_]{1,24}")) {
                // A pinned name must be a valid, inert group name: it is
                // written into the guest's /etc/group by a shell step.
                throw new IllegalStateException("invalid pinned AID name: " + entry);
            }
            pinned.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(pinned);
    }
}
