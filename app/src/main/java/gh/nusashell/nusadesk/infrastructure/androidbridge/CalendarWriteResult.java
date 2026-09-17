package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Outcome of one calendar write, Android-free.
 *
 * <p>A write is either a success carrying the affected event id, or one typed
 * code — never a bare failure. The codes are the bounded guest contract; no
 * provider exception text, URI, or account name crosses the bridge.</p>
 */
public final class CalendarWriteResult {
    /** A write-calendar grant the request needs is missing, no denial recorded. */
    public static final String ERROR_PERMISSION_REQUIRED = "calendar-permission-required";
    /** A write-calendar grant the request needs was denied. */
    public static final String ERROR_PERMISSION_DENIED = "calendar-permission-denied";
    /** No writable calendar exists on this device. */
    public static final String ERROR_UNAVAILABLE = "calendar-unavailable";
    /** The target calendar exists but the app may not write to it. */
    public static final String ERROR_READ_ONLY = "calendar-read-only";
    /** The target event does not exist (or was already removed). */
    public static final String ERROR_NOT_FOUND = "calendar-not-found";
    /** The provider rejected the write for another reason. */
    public static final String ERROR_FAILED = "calendar-failed";

    private final long eventId;
    private final String errorCode;

    private CalendarWriteResult(long eventId, String errorCode) {
        this.eventId = eventId;
        this.errorCode = errorCode;
    }

    /** The write succeeded; {@code eventId} is the affected event. */
    public static CalendarWriteResult ok(long eventId) {
        if (eventId <= 0L) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        return new CalendarWriteResult(eventId, null);
    }

    /** The write failed with one bounded code. */
    public static CalendarWriteResult failed(String errorCode) {
        if (errorCode == null || errorCode.trim().isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        return new CalendarWriteResult(0L, errorCode);
    }

    public boolean isOk() {
        return errorCode == null;
    }

    /** Affected event id, or zero when the write failed. */
    public long getEventId() {
        return eventId;
    }

    /** Bounded failure code, or {@code null} on success. */
    public String getErrorCode() {
        return errorCode;
    }
}
