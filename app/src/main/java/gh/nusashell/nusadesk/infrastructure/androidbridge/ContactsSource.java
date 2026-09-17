package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one bounded contacts read, kept Android-free for testing.
 *
 * <p>Implementations must enforce the {@link MessagingQuery} bounds, check
 * the read-contacts grant per read, and never fabricate a
 * {@link MessagingReadState#READING} snapshot: a missing or denied grant, an
 * absent provider, or a platform failure is reported as its explicit state.
 * The query is the only guest input; projection, selection, and ordering are
 * fixed by the implementation.</p>
 */
public interface ContactsSource {
    /** Read at most {@code query.getLimit()} bounded contacts matching the optional query. */
    ContactsSnapshot read(MessagingQuery query);
}
