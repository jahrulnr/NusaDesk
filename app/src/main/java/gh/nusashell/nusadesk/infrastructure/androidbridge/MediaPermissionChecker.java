package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Resolves the runtime grant state a live media mode needs, kept Android-free
 * for testing. See {@link CapabilityPermission} for the REQUIRED vs DENIED
 * distinction.
 */
public interface MediaPermissionChecker {

    /**
     * {@link CapabilityPermission#GRANTED} only when every permission the
     * mode needs is granted: CAMERA for a video mode, RECORD_AUDIO for an
     * audio mode, both for {@link LiveMediaMode#BOTH}. A previously denied
     * grant maps to {@link CapabilityPermission#DENIED}, otherwise the
     * missing grant is {@link CapabilityPermission#REQUIRED}. A permission
     * the mode does not use is never required and never affects the result.
     */
    CapabilityPermission check(LiveMediaMode mode);
}
