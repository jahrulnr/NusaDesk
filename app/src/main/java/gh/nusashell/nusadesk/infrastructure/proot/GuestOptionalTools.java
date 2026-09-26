package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * The two opt-in guest tool bundles and their persisted install state.
 *
 * <p>Everything {@link GuestAwarenessReadmeWriter#ensure} writes is core —
 * it is part of every session. The bundles here are different: each one is
 * installed only when the user chose it (at setup, or later from System),
 * and the choice is recorded in the guest rootfs itself as a small marker
 * file under {@link #GUEST_STATE_DIR_RELATIVE_PATH}. That directory sits
 * under {@code /var/lib}, inside the guest backup scope, so the choice
 * survives both session restarts and a guest backup restore.</p>
 *
 * <ul>
 *   <li>{@link Kind#USB_ADB} — the USB pass-through set:
 *       {@code /usr/local/bin/nusadesk-usb}
 *       ({@link GuestUsbCliWriter}), {@code nusadesk-usbd}
 *       ({@link GuestUsbDaemonWriter}), the {@code adb} shadow wrapper and
 *       {@code /opt/nusadesk/libusb-shim.c} ({@link GuestUsbShimWriter}).</li>
 *   <li>{@link Kind#TERMUX} — the generated {@code termux-*} command clients
 *       and {@code docs/termux-compat.md} ({@link GuestTermuxCompatWriter}).
 *       The shared {@code termux_compat} module is deliberately not part of
 *       this bundle: {@code android-cli} and the {@code nusadesk-*} clients
 *       import it, so the core install keeps it regardless of this
 *       choice.</li>
 * </ul>
 *
 * <p>Adoption of pre-marker installs: an app version before this split
 * always wrote both bundles, so a rootfs can carry the generated files with
 * no marker. {@link #reconcile} — run by
 * {@link GuestAwarenessReadmeWriter#ensure} on every session start — treats
 * a bundle's own marked files (for example a {@code nusadesk-usb} script
 * carrying its generated header, or any marked {@code termux-*} command) as
 * proof the user had that bundle, writes the marker, and refreshes the
 * files. It never deletes: a kind with neither marker nor marked files is
 * left alone, and files at managed paths that lack the generated marker are
 * foreign — they are never counted as an install and are never touched
 * while the kind stays uninstalled. Once a kind is installed its fixed
 * paths are app-managed like the rest of the bundle: stale bytes are
 * replaced on refresh, including a foreign file at the same path when the
 * user explicitly installs the kind.</p>
 *
 * <p>Safety follows the same rules as every other guest writer: only fixed
 * paths are written, writes are staged and atomically moved, and a symlink
 * or non-directory occupying a fixed parent fails closed with an
 * {@link IOException} rather than writing outside the rootfs. A marker path
 * taken by a symlink is not counted as installed and is never followed; an
 * explicit {@link #install} replaces the link itself atomically, never its
 * target.</p>
 */
public final class GuestOptionalTools {

    /** One independently installable guest tool bundle. */
    public enum Kind {
        /** USB pass-through: nusadesk-usb, nusadesk-usbd, adb wrapper, shim source. */
        USB_ADB("usb-adb"),
        /** Termux command compatibility: the termux-* clients and their doc. */
        TERMUX("termux");

        private final String markerName;

        Kind(String markerName) {
            this.markerName = markerName;
        }
    }

    /**
     * Guest-relative directory holding one install marker file per kind.
     * Kept beside the agent-seed bookkeeping under {@code /var/lib/nusadesk},
     * which is inside the guest backup scope.
     */
    public static final String GUEST_STATE_DIR_RELATIVE_PATH =
            "var/lib/nusadesk/optional-tools";

    private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";
    private static final String FILE_PERMISSIONS = "rw-r--r--";
    private static final int MARKER_SCAN_LINES = 8;

    private GuestOptionalTools() {
    }

    /** Guest-relative install marker path for one kind. */
    public static String markerRelativePath(Kind kind) {
        return GUEST_STATE_DIR_RELATIVE_PATH + "/" + kind.markerName;
    }

    /**
     * Whether the bundle is installed or adoptable: its marker is a regular
     * file, or pre-marker generated files carrying their marker are still in
     * the rootfs. Best-effort for callers that render a state — an I/O
     * failure during the legacy scan reports {@code false} rather than
     * throwing; {@link #reconcile} and {@link #install} surface real
     * failures.
     *
     * @param activeRootfs validated active rootfs directory
     * @param kind the bundle to query
     */
    public static boolean isInstalled(Path activeRootfs, Kind kind) {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return installRecordedOrAdoptable(activeRootfs, kind);
    }

    /**
     * Install one bundle: write its guest files at the fixed paths and then
     * record the marker. Idempotent — bytes that already match are left
     * untouched and an unchanged bundle reports
     * {@link GuestAwarenessReadmeWriter.Result#UNCHANGED}. Safe to call
     * while a session is live; writes are atomic moves.
     *
     * @param activeRootfs validated active rootfs directory
     * @param appVersion APK version name, not user input
     * @param kind the bundle to install
     * @return whether any file or the marker was written
     * @throws IOException when a fixed path is unsafe or cannot be written
     */
    public static GuestAwarenessReadmeWriter.Result install(Path activeRootfs,
            String appVersion, Kind kind) throws IOException {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        boolean updated = ensureKindFiles(kind, activeRootfs, version);
        Path stateDir = GuestAwarenessReadmeWriter.prepareDirectory(activeRootfs,
                GUEST_STATE_DIR_RELATIVE_PATH,
                "guest /" + GUEST_STATE_DIR_RELATIVE_PATH);
        updated |= GuestAwarenessReadmeWriter.ensureFile(
                stateDir.resolve(kind.markerName),
                markerContent(kind, version).getBytes(StandardCharsets.UTF_8),
                FILE_PERMISSIONS);
        return updated ? GuestAwarenessReadmeWriter.Result.UPDATED
                : GuestAwarenessReadmeWriter.Result.UNCHANGED;
    }

    /**
     * Session-start reconciliation: for every kind that is installed (its
     * marker exists) or adoptable (pre-marker generated files are present),
     * refresh the bundle files and write the marker. A kind with neither is
     * skipped entirely — nothing is created, deleted, or overwritten for it.
     * {@link GuestAwarenessReadmeWriter#ensure} already runs this, so the
     * session-start caller needs no separate call.
     *
     * @param activeRootfs validated active rootfs directory
     * @param appVersion APK version name, not user input
     * @return whether any file or marker was written
     * @throws IOException when a fixed path is unsafe or cannot be written
     */
    public static GuestAwarenessReadmeWriter.Result reconcile(Path activeRootfs,
            String appVersion) throws IOException {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
        }
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        boolean updated = false;
        for (Kind kind : Kind.values()) {
            if (installRecordedOrAdoptable(activeRootfs, kind)) {
                updated |= install(activeRootfs, version, kind)
                        == GuestAwarenessReadmeWriter.Result.UPDATED;
            }
        }
        return updated ? GuestAwarenessReadmeWriter.Result.UPDATED
                : GuestAwarenessReadmeWriter.Result.UNCHANGED;
    }

    /** Marker recorded, or a pre-marker marked install present. Detection only. */
    private static boolean installRecordedOrAdoptable(Path activeRootfs, Kind kind) {
        if (Files.isRegularFile(activeRootfs.resolve(markerRelativePath(kind)),
                LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return legacyInstallPresent(activeRootfs, kind);
    }

    /**
     * Whether files this app's earlier version generated for the bundle are
     * present, recognised by the marker text in their head lines. Only
     * marked files count — a user file at the same path is foreign and never
     * proves an install. Detection is best-effort: an unreadable directory or
     * file answers "not present" instead of failing the session start.
     */
    private static boolean legacyInstallPresent(Path activeRootfs, Kind kind) {
        switch (kind) {
            case USB_ADB:
                return fileCarriesMarker(
                        activeRootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH),
                        "NusaDesk USB pass-through CLI")
                        || fileCarriesMarker(
                        activeRootfs.resolve(GuestUsbDaemonWriter.GUEST_DAEMON_RELATIVE_PATH),
                        "NusaDesk USB driver daemon")
                        || fileCarriesMarker(
                        activeRootfs.resolve(GuestUsbShimWriter.WRAPPER_GUEST_PATH),
                        "NusaDesk adb USB shim wrapper")
                        || fileCarriesMarker(
                        activeRootfs.resolve(GuestUsbShimWriter.SHIM_GUEST_PATH),
                        "NusaDesk libusb shim");
            case TERMUX:
                Path bin = activeRootfs.resolve(
                        GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
                return Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS)
                        && GuestTermuxCompatWriter.hasMarkedCommand(bin);
            default:
                throw new IllegalStateException("unhandled kind " + kind);
        }
    }

    /** Write one bundle's guest files; @return whether any file was updated. */
    private static boolean ensureKindFiles(Kind kind, Path activeRootfs,
            String version) throws IOException {
        switch (kind) {
            case USB_ADB:
                Path bin = GuestAwarenessReadmeWriter.prepareDirectory(activeRootfs,
                        GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH,
                        "guest /usr/local/bin");
                Path shimDir = GuestAwarenessReadmeWriter.prepareDirectory(activeRootfs,
                        "opt/nusadesk", "guest /opt/nusadesk");
                boolean updated = false;
                updated |= GuestAwarenessReadmeWriter.ensureFile(
                        bin.resolve(GuestUsbCliWriter.CLI_FILE_NAME),
                        GuestUsbCliWriter.scriptContent(version)
                                .getBytes(StandardCharsets.UTF_8),
                        EXECUTABLE_PERMISSIONS);
                updated |= GuestAwarenessReadmeWriter.ensureFile(
                        bin.resolve(GuestUsbDaemonWriter.DAEMON_FILE_NAME),
                        GuestUsbDaemonWriter.scriptContent(version)
                                .getBytes(StandardCharsets.UTF_8),
                        EXECUTABLE_PERMISSIONS);
                updated |= GuestAwarenessReadmeWriter.ensureFile(
                        bin.resolve(GuestUsbShimWriter.WRAPPER_FILE_NAME),
                        GuestUsbShimWriter.adbWrapperContent(version)
                                .getBytes(StandardCharsets.UTF_8),
                        EXECUTABLE_PERMISSIONS);
                updated |= GuestAwarenessReadmeWriter.ensureFile(
                        shimDir.resolve(GuestUsbShimWriter.SHIM_FILE_NAME),
                        GuestUsbShimWriter.shimSource(version)
                                .getBytes(StandardCharsets.UTF_8),
                        FILE_PERMISSIONS);
                return updated;
            case TERMUX:
                return GuestTermuxCompatWriter.ensure(activeRootfs, version)
                        == GuestAwarenessReadmeWriter.Result.UPDATED;
            default:
                throw new IllegalStateException("unhandled kind " + kind);
        }
    }

    private static String markerContent(Kind kind, String version) {
        return "# NusaDesk optional guest tools - install marker\n"
                + "# kind: " + kind.markerName + "\n"
                + "# installed by app version: " + version + "\n";
    }

    /**
     * Whether a regular file carries {@code marker} in its first lines.
     * Symlinks and non-regular files answer {@code false}; the read never
     * follows a link swapped in after the type check. This is a detection
     * probe only: an unreadable file is simply not proof of an install and
     * must never fail the whole session start.
     */
    private static boolean fileCarriesMarker(Path file, String marker) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(file, options);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         Channels.newInputStream(channel), StandardCharsets.UTF_8))) {
                for (int line = 0; line < MARKER_SCAN_LINES; line++) {
                    String text = reader.readLine();
                    if (text == null) {
                        return false;
                    }
                    if (text.contains(marker)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
