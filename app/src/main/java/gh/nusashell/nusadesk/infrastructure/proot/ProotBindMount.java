package gh.nusashell.nusadesk.infrastructure.proot;

/**
 * One host-to-guest bind mount for the PRoot bridge.
 *
 * <p>Immutable and validated at construction. Both paths must be absolute and
 * free of {@code ..} traversal; the Android adapter additionally confines
 * non-system host paths to app-private storage. Read-only binds are not exposed
 * because the packaged PRoot build's {@code :ro} support is not device-proven;
 * every bind is therefore read-write and must be a deliberately chosen path.</p>
 */
public final class ProotBindMount {
    private final String hostPath;
    private final String guestPath;

    private ProotBindMount(String hostPath, String guestPath) {
        this.hostPath = hostPath;
        this.guestPath = guestPath;
    }

    /** Create a validated bind mount from absolute host and guest paths. */
    public static ProotBindMount of(String hostPath, String guestPath) {
        return new ProotBindMount(
                ProotPaths.requireAbsolutePath(hostPath, "hostPath"),
                ProotPaths.requireAbsolutePath(guestPath, "guestPath"));
    }

    public String getHostPath() {
        return hostPath;
    }

    public String getGuestPath() {
        return guestPath;
    }

    /** PRoot {@code -b} argument form: {@code hostPath:guestPath}. */
    public String toBindArgument() {
        return hostPath + ":" + guestPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProotBindMount)) {
            return false;
        }
        ProotBindMount that = (ProotBindMount) other;
        return hostPath.equals(that.hostPath) && guestPath.equals(that.guestPath);
    }

    @Override
    public int hashCode() {
        return 31 * hostPath.hashCode() + guestPath.hashCode();
    }

    @Override
    public String toString() {
        return toBindArgument();
    }
}
