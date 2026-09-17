package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Resolves the camera + microphone grant state for one {@code media.start},
 * kept Android-free for testing. See {@link CapabilityPermission} for the
 * REQUIRED vs DENIED distinction.
 */
public interface MediaPermissionChecker {

    /**
     * {@link CapabilityPermission#GRANTED} only when both CAMERA and
     * RECORD_AUDIO are granted; a previously denied grant maps to
     * {@link CapabilityPermission#DENIED}, otherwise the missing grant is
     * {@link CapabilityPermission#REQUIRED}.
     */
    CapabilityPermission check();
}
