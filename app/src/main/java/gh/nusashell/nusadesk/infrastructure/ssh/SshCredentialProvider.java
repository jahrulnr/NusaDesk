package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.infrastructure.session.CredentialType;

/**
 * Source of SSH credentials for {@link SshClientBridge}.
 *
 * <p>This is the "vault adapter" port: passwords are returned as {@code char[]}
 * and private keys as {@code byte[]} so the bridge can zero the buffers
 * immediately after authentication. Callers must treat every returned array as
 * secret, consume it promptly, overwrite it with zeros, and never log it. The
 * bridge does not cache credentials; it requests them once per connect/reconnect
 * and zeroes them on completion.</p>
 *
 * <p>Returning {@code null} for the material needed by the configured
 * {@link CredentialType} is treated as "credential not available" and aborts the
 * connection with a {@link SshSessionState#FAILED} state rather than falling
 * back to insecure auth.</p>
 */
public interface SshCredentialProvider {

    /**
     * @return the password for the credential, or {@code null} if absent.
     *         The caller must zero the returned array.
     */
    char[] password(String credentialId);

    /**
     * @return the private key bytes (PEM/OpenSSH) for the credential, or
     *         {@code null} if absent. The caller must zero the returned array.
     */
    byte[] privateKey(String credentialId);

    /**
     * @return the credential kind, or {@code null} if no credential is stored.
     */
    CredentialType typeOf(String credentialId);

    /** @return {@code true} if any credential is stored for the id. */
    boolean exists(String credentialId);
}
