package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;
import gh.nusashell.nusadesk.domain.session.HostKeyTrustPolicy;

import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

/**
 * Strict SSH server-key verifier built on the existing domain
 * {@link HostKeyTrustPolicy} and {@link HostKeyTrustStore}.
 *
 * <p>Verification rules (security constraints, not optional polish):</p>
 * <ul>
 *   <li><b>VERIFIED</b> — the presented fingerprint matches the trusted record:
 *       accept.</li>
 *   <li><b>UNKNOWN</b> — no prior record: invoke the
 *       {@link FirstHostKeyTrustCallback}. Accept only if it returns
 *       {@code true}, then persist the decision via
 *       {@link HostKeyTrustPolicy#trust}. Reject otherwise, storing nothing.</li>
 *   <li><b>MISMATCH</b> — the presented fingerprint differs from a trusted
 *       record: <em>always reject</em>. The first-trust callback is never
 *       consulted for a mismatch; silent replacement is forbidden. The user
 *       must resolve a mismatch through an explicit, separate path
 *       ({@link HostKeyTrustPolicy#replaceAfterMismatch}) outside this
 *       verifier.</li>
 * </ul>
 *
 * <p>This implements MINA SSHD's {@link ServerKeyVerifier} so it can be installed
 * on an {@code SshClient} via {@code setServerKeyVerifier}. The host scope is
 * fixed at construction (from {@link SshSessionConfig#hostKeyScope()}) rather
 * than parsed from the {@link SocketAddress}, so trust records are stable
 * across reconnects regardless of how MINA reports the address.</p>
 */
public final class StrictHostKeyVerifier implements ServerKeyVerifier {

    private final HostKeyTrustStore trustStore;
    private final FirstHostKeyTrustCallback firstTrustCallback;
    private final String hostKeyScope;
    private final LongSupplier clock;
    private final Consumer<String> onReject;

    /**
     * @param trustStore        persistence for trust decisions
     * @param firstTrustCallback invoked only on first contact (UNKNOWN)
     * @param hostKeyScope      the host:port scope used as the trust key
     * @param clock             source of {@code now} in epoch millis
     */
    public StrictHostKeyVerifier(
            HostKeyTrustStore trustStore,
            FirstHostKeyTrustCallback firstTrustCallback,
            String hostKeyScope,
            LongSupplier clock) {
        this(trustStore, firstTrustCallback, hostKeyScope, clock, null);
    }

    /**
     * @param onReject invoked with a reason when verification refuses a key
     *                 (mismatch or declined first trust); may be {@code null}
     */
    public StrictHostKeyVerifier(
            HostKeyTrustStore trustStore,
            FirstHostKeyTrustCallback firstTrustCallback,
            String hostKeyScope,
            LongSupplier clock,
            Consumer<String> onReject) {
        if (trustStore == null) {
            throw new IllegalArgumentException("trustStore must not be null");
        }
        if (firstTrustCallback == null) {
            throw new IllegalArgumentException("firstTrustCallback must not be null");
        }
        if (hostKeyScope == null || hostKeyScope.trim().isEmpty()) {
            throw new IllegalArgumentException("hostKeyScope must not be blank");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.trustStore = trustStore;
        this.firstTrustCallback = firstTrustCallback;
        this.hostKeyScope = hostKeyScope;
        this.clock = clock;
        this.onReject = onReject;
    }

    @Override
    public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
        HostKeyFingerprint presented = SshHostKeyFingerprintCodec.toFingerprint(serverKey);
        HostKeyRecord known = trustStore.load(hostKeyScope);
        HostKeyTrust decision = HostKeyTrustPolicy.evaluate(known, presented);

        switch (decision) {
            case VERIFIED:
                return true;
            case UNKNOWN:
                if (!firstTrustCallback.trustNewHost(hostKeyScope, presented)) {
                    reject("first-contact host key for " + hostKeyScope + " was not trusted");
                    return false;
                }
                HostKeyRecord trusted =
                        HostKeyTrustPolicy.trust(known, presented, hostKeyScope, clock.getAsLong());
                trustStore.save(trusted);
                return true;
            case MISMATCH:
            default:
                // Strict: never silently replace a trusted key. The user must
                // resolve a mismatch through an explicit, separate path.
                reject("host key for " + hostKeyScope + " changed; refusing connection");
                return false;
        }
    }

    private void reject(String reason) {
        if (onReject != null) {
            onReject.accept(reason);
        }
    }
}
