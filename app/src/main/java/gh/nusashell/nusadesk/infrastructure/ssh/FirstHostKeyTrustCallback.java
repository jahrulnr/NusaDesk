package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;

/**
 * Caller-provided callback invoked exactly when a host key is seen for the
 * first time (no prior trust record exists).
 *
 * <p>This is the <em>only</em> path that authorises trusting a previously
 * unseen host key. It must represent a deliberate user decision, never an
 * automatic "yes". Returning {@code false} aborts the connection without
 * storing anything. A key that <em>differs</em> from an already-trusted record
 * is a mismatch and is never routed here — strict verification refuses it
 * outright (see {@link StrictHostKeyVerifier}).</p>
 */
public interface FirstHostKeyTrustCallback {

    /**
     * @param host        the host:port scope being trusted
     * @param fingerprint the presented key fingerprint
     * @return {@code true} to trust and persist this key, {@code false} to abort
     */
    boolean trustNewHost(String host, HostKeyFingerprint fingerprint);
}
