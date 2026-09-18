package gh.nusashell.nusadesk.infrastructure.logs;

import java.nio.file.Path;

/**
 * One guest log source the Logs surface can list and tail.
 *
 * <p>An immutable value object: the stable id is the guest-visible path, which
 * is also what the user sees as the detail line — the same string a terminal
 * user would pass to {@code tail -f} inside the guest. {@link #getHostPath()}
 * is the host-side file the reader actually opens; it is always a path inside
 * the active rootfs, never a symlink or an escape (the catalog enforces that).
 */
public final class GuestLog {

    /** Which section of the Logs screen an item belongs to. */
    public enum Section {
        /** Session console stream: boot output and session-level events. */
        BOOT,
        /** Per-service logs written by the guest's own managers. */
        SYSTEM
    }

    private final String id;
    private final String title;
    private final String guestPath;
    private final Path hostPath;
    private final Section section;
    /** Extra qualifier rendered next to the title (e.g. a user-service tag), or empty. */
    private final String qualifier;

    public GuestLog(String id, String title, String guestPath, Path hostPath,
                    Section section, String qualifier) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        if (guestPath == null || guestPath.isEmpty()) {
            throw new IllegalArgumentException("guestPath must not be empty");
        }
        if (hostPath == null) {
            throw new IllegalArgumentException("hostPath must not be null");
        }
        if (section == null) {
            throw new IllegalArgumentException("section must not be null");
        }
        this.id = id;
        this.title = title;
        this.guestPath = guestPath;
        this.hostPath = hostPath;
        this.section = section;
        this.qualifier = qualifier == null ? "" : qualifier;
    }

    /** Stable id: the guest-visible path, unique per listed file. */
    public String getId() {
        return id;
    }

    /** Short display name, e.g. {@code lw-user-manager.service} or {@code This boot}. */
    public String getTitle() {
        return title;
    }

    /** The guest path shown as the item's detail line. */
    public String getGuestPath() {
        return guestPath;
    }

    /** The host file the tail reader opens; inside the active rootfs. */
    public Path getHostPath() {
        return hostPath;
    }

    public Section getSection() {
        return section;
    }

    /** Optional qualifier shown next to the title, or {@code ""}. */
    public String getQualifier() {
        return qualifier;
    }
}
