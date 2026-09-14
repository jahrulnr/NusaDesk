package gh.nusashell.nusadesk.infrastructure.ssh;

import android.content.Context;

import gh.nusashell.nusadesk.infrastructure.session.CredentialType;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreCredentialVault;

import java.nio.charset.StandardCharsets;

/**
 * {@link SshCredentialProvider} adapter backed by the existing Android
 * Keystore-encrypted {@link KeystoreCredentialVault}.
 *
 * <p>The vault stores all secrets as encrypted {@code byte[]}; this adapter
 * surfaces passwords as {@code char[]} (UTF-8 decoded) and private keys as raw
 * {@code byte[]} so the bridge can zero them. The decoded/returned arrays are
 * fresh copies owned by the caller; the vault's internal plaintext is also a
 * fresh allocation. The caller (the bridge) is responsible for zeroing them
 * after use.</p>
 *
 * <p>Requires a {@link Context} because the vault is backed by app-private
 * SharedPreferences and the AndroidKeyStore. Construct it once and reuse it;
 * the underlying vault caches the keystore key.</p>
 */
public final class KeystoreVaultCredentialProvider implements SshCredentialProvider {

    private final KeystoreCredentialVault vault;

    public KeystoreVaultCredentialProvider(Context context) {
        this.vault = new KeystoreCredentialVault(context);
    }

    /** Constructor for tests or callers that already own a vault instance. */
    public KeystoreVaultCredentialProvider(KeystoreCredentialVault vault) {
        if (vault == null) {
            throw new IllegalArgumentException("vault must not be null");
        }
        this.vault = vault;
    }

    @Override
    public char[] password(String credentialId) {
        byte[] raw = vault.load(credentialId);
        if (raw == null) {
            return null;
        }
        try {
            return new String(raw, StandardCharsets.UTF_8).toCharArray();
        } finally {
            zero(raw);
        }
    }

    @Override
    public byte[] privateKey(String credentialId) {
        return vault.load(credentialId);
    }

    @Override
    public CredentialType typeOf(String credentialId) {
        return vault.typeOf(credentialId);
    }

    @Override
    public boolean exists(String credentialId) {
        return vault.exists(credentialId);
    }

    private static void zero(byte[] data) {
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] = 0;
            }
        }
    }
}
