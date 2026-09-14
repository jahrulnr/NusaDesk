package gh.nusashell.nusadesk.infrastructure.session;

import android.content.Context;

import gh.nusashell.nusadesk.infrastructure.sshserver.SshBridgeCredential;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Per-install opaque token for the loopback SSH bridge, generated once and
 * held encrypted by {@link KeystoreCredentialVault} (AES-256-GCM under an
 * Android Keystore key).
 *
 * <p>The token exists to authenticate the in-app SSH client to the bridge;
 * loopback reachability alone is not authentication (AGENTS.md). It is never
 * placed in a URL, process argument, intent extra, readiness frame, or log.
 * The bridge compares it in constant time; the client resolves it through the
 * same vault under {@link #CREDENTIAL_ID} so both sides read the identical
 * per-install secret.</p>
 *
 * <p>The same credential id doubles as the vault key for the client-side
 * password lookup; the token text is a Base64 string so it survives
 * {@code char[]}/{@code byte[]} conversion losslessly.</p>
 */
public final class KeystoreBridgeCredential implements SshBridgeCredential {

    /** Credential id shared by the bridge authenticator and the in-app client. */
    public static final String CREDENTIAL_ID = "local-ssh-bridge";

    private static final int TOKEN_BYTES = 32;

    private final KeystoreCredentialVault vault;
    private final SecureRandom random;

    public KeystoreBridgeCredential(Context context) {
        this(new KeystoreCredentialVault(context), new SecureRandom());
    }

    /** Test seam: injects the vault and randomness source. */
    KeystoreBridgeCredential(KeystoreCredentialVault vault, SecureRandom random) {
        if (vault == null) {
            throw new IllegalArgumentException("vault must not be null");
        }
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        this.vault = vault;
        this.random = random;
    }

    /**
     * Returns the per-install token, generating and vaulting it on first use.
     * The returned array is a fresh copy; callers may zero it after use.
     * Never {@code null} and never empty — generation failures throw, so the
     * bridge's "no credential means no start" contract is preserved.
     */
    @Override
    public char[] token() {
        byte[] secret = vault.load(CREDENTIAL_ID);
        if (secret == null) {
            byte[] raw = new byte[TOKEN_BYTES];
            random.nextBytes(raw);
            secret = Base64.getEncoder().encodeToString(raw)
                    .getBytes(StandardCharsets.US_ASCII);
            Arrays.fill(raw, (byte) 0);
            try {
                vault.store(CREDENTIAL_ID, CredentialType.PASSWORD, secret);
            } catch (RuntimeException e) {
                Arrays.fill(secret, (byte) 0);
                throw e;
            }
        }
        try {
            return new String(secret, StandardCharsets.US_ASCII).toCharArray();
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
