package gh.nusashell.nusadesk.infrastructure.sshserver;

import java.security.KeyPair;

/**
 * Persistence boundary for the SSH bridge server's host key.
 *
 * <p>The server loads the host key at start; if none is present it generates a
 * fresh key pair and saves it through this interface. Keeping load/save behind
 * a port keeps filesystem, Android Keystore, and app-private storage details
 * out of the server core, so the server is unit-testable with an in-memory
 * store and the real persistence adapter is wired by the caller.</p>
 *
 * <p>Host keys are generated at first start in app-private storage, never
 * shipped inside a public catalog artifact, and never logged (ADR-0007). A
 * saved key must be returned verbatim by a subsequent {@link #load()} so the
 * server presents a stable identity across restarts.</p>
 */
public interface SshServerHostKeyStore {

    /**
     * @return the persisted host key pair, or {@code null} if none is stored.
     *         The server generates and saves one when this returns {@code null}.
     */
    KeyPair load();

    /**
     * Persist a generated host key pair so future starts reuse it.
     *
     * @param keyPair the key pair to persist; never {@code null}
     */
    void save(KeyPair keyPair);
}
