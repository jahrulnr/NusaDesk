package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Explicit outcome state of one bounded messaging/telephony/contacts read,
 * kept Android-free for testing.
 *
 * <p>The bridge reports every state except {@link #READING} as a typed
 * non-ok response so a caller can never mistake a failed read for data:
 * {@link #PERMISSION_REQUIRED} means no grant exists and no denial is
 * recorded (the later user-visible consent flow may ask),
 * {@link #PERMISSION_DENIED} means the user previously refused the grant,
 * {@link #NO_TELEPHONY} means the device has no telephony radio, and
 * {@link #UNAVAILABLE} means the platform provider or service is absent.</p>
 */
public enum MessagingReadState {
    /** A bounded set of rows was read from the platform. */
    READING,
    /** The operation needs a runtime permission that is neither granted nor recorded as denied. */
    PERMISSION_REQUIRED,
    /** The operation needs a runtime permission the user previously refused. */
    PERMISSION_DENIED,
    /** The platform provider, service, or radio is absent. */
    UNAVAILABLE,
    /** The device has no telephony support for this operation. */
    NO_TELEPHONY,
    /** The platform rejected or corrupted the read; no values are fabricated. */
    ERROR
}
