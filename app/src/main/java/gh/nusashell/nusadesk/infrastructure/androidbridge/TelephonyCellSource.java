package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one bounded cell-info read, kept Android-free for testing.
 *
 * <p>Implementations must resolve the location grant per read (cell info is
 * location data) and never fabricate a {@link MessagingReadState#READING}
 * snapshot: a missing or denied grant, a device without telephony, an absent
 * service, or a platform failure is reported as its explicit state. Cell
 * identity is redacted from every entry.</p>
 */
public interface TelephonyCellSource {
    /** Read the bounded, identity-redacted cell snapshot. */
    TelephonyCellInfo read();
}
