package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.session.SessionState;

/**
 * Deterministic, Android-free policy that maps a {@link HostRuntimeStatus} to
 * the content and behaviour of the host foreground notification.
 *
 * <p>Keeping this pure lets the notification wording, ongoing flag, and Stop
 * action visibility be locked by unit tests independently of the Android
 * notification framework. The service is a thin adapter around these decisions.
 *
 * <p>User-visible strings are intentionally in-code for this slice because the
 * ownership boundary forbids editing {@code strings.xml}; localisation should
 * move them to resources in a later slice.
 */
public final class RuntimeNotificationPolicy {

    private RuntimeNotificationPolicy() {
    }

    /** Notification content title for a given status. */
    public static String title(HostRuntimeStatus status) {
        if (status == null) {
            return "Linux runtime";
        }
        switch (status.getState()) {
            case NOT_STARTED:
                return "Linux runtime";
            case STARTING:
                return "Starting Linux runtime";
            case RUNNING:
                return status.isRuntimeRunning() ? "Linux runtime running" : "Linux runtime connecting";
            case RECONNECTING:
                return "Reconnecting Linux runtime";
            case STOPPING:
                return "Stopping Linux runtime";
            case STOPPED:
                return "Linux runtime stopped";
            case FAILED:
                return "Linux runtime failed";
            case RECOVERING:
                return "Recovering Linux runtime";
            case CANCELLED:
                return "Linux runtime cancelled";
            default:
                return "Linux runtime";
        }
    }

    /** Notification content text for a given status. */
    public static String text(HostRuntimeStatus status) {
        if (status == null) {
            return "";
        }
        if (status.getState() == SessionState.NOT_STARTED && !status.isWorkloadRegistered()) {
            return "No runtime workload registered.";
        }
        if (status.getState() == SessionState.FAILED) {
            String reason = status.getFailureReason();
            return reason.isEmpty() ? "Runtime failed." : reason;
        }
        if (status.isRuntimeRunning() && status.getEndpoint() != null) {
            return "Loopback " + status.getEndpoint().toUrl("http");
        }
        if (status.getState() == SessionState.STOPPED) {
            return "Stopped. Open the app to start it again.";
        }
        return "";
    }

    /**
     * Whether the notification must be ongoing (non-dismissible) because the
     * runtime is active or transitioning.
     */
    public static boolean isOngoing(HostRuntimeStatus status) {
        if (status == null) {
            return false;
        }
        switch (status.getState()) {
            case STARTING:
            case RUNNING:
            case RECONNECTING:
            case STOPPING:
            case RECOVERING:
                return true;
            default:
                return false;
        }
    }

    /** Whether the Stop action should be shown for a given status. */
    public static boolean showsStopAction(HostRuntimeStatus status) {
        if (status == null) {
            return false;
        }
        switch (status.getState()) {
            case STARTING:
            case RUNNING:
            case RECONNECTING:
            case RECOVERING:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the service must remain in the foreground for this status. A
     * foreground service is only justified while work is active or
     * transitioning; terminal states must release the foreground slot.
     */
    public static boolean requiresForeground(HostRuntimeStatus status) {
        return isOngoing(status);
    }
}
