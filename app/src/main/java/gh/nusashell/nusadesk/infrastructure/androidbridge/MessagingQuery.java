package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * The only guest input accepted by the messaging/telephony/contacts reads,
 * kept Android-free for testing.
 *
 * <p>The guest may send a bounded {@code limit} and one optional sanitized
 * {@code query} string. Everything else — projection, selection, ordering,
 * URI, and column names — is fixed by the adapter, so a guest can neither
 * exfiltrate fields outside the contract nor inject SQL. The query is
 * interpreted by each source as its natural filter (contacts: name/number
 * search; call log: number match; SMS: address match) and is matched
 * literally through {@link MessagingReadPolicy#escapeLikeLiteral}.</p>
 */
public final class MessagingQuery {
    private final int limit;
    private final String query;

    private MessagingQuery(int limit, String query) {
        this.limit = limit;
        this.query = query;
    }

    /** A bounded read with the default row cap and no filter. */
    public static MessagingQuery all() {
        return of(MessagingReadPolicy.DEFAULT_LIMIT, null);
    }

    /**
     * A bounded read with at most {@code limit} rows and no filter.
     *
     * @throws IllegalArgumentException when {@code limit} is outside {@code [1, MAX_ROWS]}
     */
    public static MessagingQuery of(int limit) {
        return of(limit, null);
    }

    /**
     * A bounded read with at most {@code limit} rows and an optional query.
     * The query is sanitized at this boundary: control characters are
     * dropped, the value is trimmed, and the length is capped, so a later
     * adapter never sees raw guest text.
     *
     * @throws IllegalArgumentException when {@code limit} is outside {@code [1, MAX_ROWS]}
     */
    public static MessagingQuery of(int limit, String query) {
        if (limit < 1 || limit > MessagingReadPolicy.MAX_ROWS) {
            throw new IllegalArgumentException(
                    "limit must be within [1, " + MessagingReadPolicy.MAX_ROWS + "]");
        }
        return new MessagingQuery(limit, MessagingReadPolicy.sanitizeQuery(query));
    }

    /** Row cap for this read. */
    public int getLimit() {
        return limit;
    }

    /** Sanitized query text; empty when the guest sent no filter. */
    public String getQuery() {
        return query;
    }

    /** True when the guest sent a non-blank filter. */
    public boolean hasQuery() {
        return !query.isEmpty();
    }

    /**
     * Literal {@code LIKE} pattern (percent-escaped and wrapped in {@code %}),
     * or {@code null} when there is no filter. The caller must pass
     * {@code ESCAPE '\'} with the pattern.
     */
    public String likePattern() {
        return hasQuery()
                ? "%" + MessagingReadPolicy.escapeLikeLiteral(query) + "%"
                : null;
    }
}
