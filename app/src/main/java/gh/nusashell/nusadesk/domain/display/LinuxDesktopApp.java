package gh.nusashell.nusadesk.domain.display;

import java.util.Objects;

/** One installed graphical desktop entry, identified by its XDG desktop-file ID. */
public final class LinuxDesktopApp {
    private final String id, name, path, iconUri;
    public LinuxDesktopApp(String id, String name, String path, String iconUri) {
        if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty()
                || path == null || !path.startsWith("/") || !path.endsWith(".desktop")) {
            throw new IllegalArgumentException("desktop entry requires an ID, name and absolute desktop-file path");
        }
        this.id = id; this.name = name; this.path = path; this.iconUri = iconUri;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }
    /** Opaque host-chosen token (a verified file: URI today); deliberately unchecked by this value object. */
    public String getIconUri() { return iconUri; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof LinuxDesktopApp)) return false;
        LinuxDesktopApp that = (LinuxDesktopApp) other;
        return id.equals(that.id) && name.equals(that.name) && path.equals(that.path) && Objects.equals(iconUri, that.iconUri);
    }
    @Override public int hashCode() { return Objects.hash(id, name, path, iconUri); }
}
