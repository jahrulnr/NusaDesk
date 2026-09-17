package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one bounded SMS inbox read, kept Android-free for testing.
 *
 * <p>Implementations must enforce the {@link MessagingQuery} bounds, check
 * the read-sms grant per read, and never fabricate a
 * {@link MessagingReadState#READING} snapshot: a missing or denied grant, an
 * absent provider, or a platform failure is reported as its explicit state.
 * The query is the only guest input; projection, selection, and ordering are
 * fixed by the implementation. Sending SMS is a side-effecting operation and
 * is out of scope for this read-only contract.</p>
 */
public interface SmsSource {
    /** Read at most {@code query.getLimit()} inbox messages matching the optional address query. */
    SmsSnapshot read(MessagingQuery query);
}
