package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Source of the opaque per-install credential required to authenticate to the
 * SSH bridge server.
 *
 * <p>The bridge is <strong>never no-auth</strong>: the server refuses to start
 * when the token is {@code null} or empty, and refuses every connection that does
 * not present a matching token. The token is opaque &mdash; the server does not
 * interpret, decode, or persist it; it only compares it in constant time. It is
 * returned as a {@code char[]} so the caller (and the server on stop) can zero
 * the buffer promptly. The token must never appear in logs, URLs, process
 * arguments, or error messages (AGENTS.md security rules).</p>
 *
 * <p>This is a host-side port only. It carries no Android types so the server
 * remains unit-testable on a plain JVM.</p>
 */
public interface SshBridgeCredential {

    /**
     * @return the opaque per-install token, never {@code null} or empty for a
     *         valid installation. The caller must zero the returned array after
     *         use. Returning {@code null} or an empty array makes the server
     *         refuse to start (never no-auth).
     */
    char[] token();
}
