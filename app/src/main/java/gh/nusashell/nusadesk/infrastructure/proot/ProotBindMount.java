package gh.nusashell.nusadesk.infrastructure.proot;

/**
 * One host-to-guest bind mount for the PRoot bridge.
 *
 * <p>Immutable and validated at construction. Both paths must be absolute and
 * free of {@code ..} traversal; the Android adapter additionally confines
 * non-system host paths to app-private storage. Read-only binds are not exposed
 * because the packaged PRoot build's {@code :ro} support is not device-proven;
 * every bind is therefore read-write and must be a deliberately chosen path.</p>
 *
 * <p>A strict bind ({@link #ofStrict}) carries the documented trailing
 * {@code !} on the guest path: PRoot then binds over the literal guest path
 * without dereferencing it when the rootfs carries a symlink there. The
 * default form keeps PRoot's normal behavior of resolving a guest symlink
 * first, so the bind lands on the symlink's target instead.</p>
 */
public final class ProotBindMount {
    private final String hostPath;
    private final String guestPath;
    private final boolean strict;

    private ProotBindMount(String hostPath, String guestPath, boolean strict) {
        this.hostPath = hostPath;
        this.guestPath = guestPath;
        this.strict = strict;
    }

    /** Create a validated bind mount from absolute host and guest paths. */
    public static ProotBindMount of(String hostPath, String guestPath) {
        return new ProotBindMount(
                ProotPaths.requireAbsolutePath(hostPath, "hostPath"),
                ProotPaths.requireAbsolutePath(guestPath, "guestPath"),
                false);
    }

    /**
     * Create a validated strict bind: same paths as {@link #of}, but the
     * {@code -b} argument ends with {@code !} so PRoot binds the host path
     * over the literal guest path even when the rootfs carries a symlink
     * there. A product-owned file bound this way is effective at its
     * conventional path regardless of the rootfs's symlink shape; the
     * underlying guest content is shadowed, never modified.
     */
    public static ProotBindMount ofStrict(String hostPath, String guestPath) {
        return new ProotBindMount(
                ProotPaths.requireAbsolutePath(hostPath, "hostPath"),
                ProotPaths.requireAbsolutePath(guestPath, "guestPath"),
                true);
    }

    public String getHostPath() {
        return hostPath;
    }

    /** The literal guest path, never including the strict {@code !} marker. */
    public String getGuestPath() {
        return guestPath;
    }

    /** Whether this bind uses the no-dereference (trailing {@code !}) form. */
    public boolean isStrict() {
        return strict;
    }

    /**
     * PRoot {@code -b} argument form: {@code hostPath:guestPath}, plus the
     * trailing {@code !} no-dereference marker for a strict bind.
     */
    public String toBindArgument() {
        return hostPath + ":" + guestPath + (strict ? "!" : "");
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
        return hostPath.equals(that.hostPath) && guestPath.equals(that.guestPath)
                && strict == that.strict;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * hostPath.hashCode() + guestPath.hashCode()) + (strict ? 1 : 0);
    }

    @Override
    public String toString() {
        return toBindArgument();
    }
}
