package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Runtime-permission check for one messaging/telephony/contacts operation,
 * kept Android-free for testing.
 *
 * <p>Implementations resolve the current grant per request and must never
 * request permission, never open a permission activity, and never claim a
 * grant they did not observe. The Android permission string is chosen by the
 * adapter for the fixed operation; a guest can never name one.</p>
 */
public interface MessagingPermissionChecker {
    /** Resolve the current grant level for one Android runtime permission. */
    CapabilityPermission check(String androidPermission);
}
