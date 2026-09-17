package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Location-grant policy for reading cell info, kept Android-free for testing.
 *
 * <p>{@code TelephonyManager.getAllCellInfo()} requires fine location on
 * Android 10/11 (API 29/30); from Android 12 (API 31) a coarse grant is also
 * accepted by the platform. The policy mirrors that matrix so the adapter can
 * fail typed on API 29/30 with only a coarse grant instead of hitting a
 * platform {@code SecurityException}.</p>
 */
public final class CellInfoAccessPolicy {
    private CellInfoAccessPolicy() {
    }

    /**
     * Whether the resolved grant is enough to read cell info on the given API
     * level. {@link LocationGrant#REQUIRED} and {@link LocationGrant#DENIED}
     * never allow the read; the adapter maps those to their typed states
     * before consulting this policy.
     */
    public static boolean allows(LocationGrant grant, int apiLevel) {
        if (grant == LocationGrant.FINE) {
            return true;
        }
        return grant == LocationGrant.COARSE_ONLY && apiLevel >= 31;
    }
}
