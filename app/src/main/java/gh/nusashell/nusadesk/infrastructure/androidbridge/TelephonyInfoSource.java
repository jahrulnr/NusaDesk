package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one bounded telephony device snapshot, kept Android-free for
 * testing.
 *
 * <p>Implementations must never fabricate a
 * {@link MessagingReadState#READING} snapshot: a device without telephony,
 * an absent service, or a platform failure is reported as its explicit
 * state. Permission-gated enrichment fields are carried only when the grant
 * was observed at read time.</p>
 */
public interface TelephonyInfoSource {
    /** Read the bounded device telephony snapshot. */
    TelephonyDeviceInfo read();
}
