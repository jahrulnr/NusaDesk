package gh.nusashell.nusadesk.infrastructure.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * Android Keystore-backed encrypted credential storage for SSH passwords and
 * private keys.
 *
 * <p>A single AES-256-GCM key is generated inside the {@code AndroidKeyStore}
 * (non-exportable, hardware-backed where the device supports it) and used to
 * wrap each credential with a fresh random IV. The IV is prepended to the
 * ciphertext and the whole blob is Base64-encoded into app-private
 * SharedPreferences. Only the encrypted form ever touches disk; plaintext
 * exists only in the byte array returned to the caller and is never logged.
 *
 * <p>This is real platform security, not an invented scheme: the key material
 * never leaves the keystore, and AES-GCM provides both confidentiality and
 * integrity (tampered ciphertext fails to authenticate). The non-secret
 * connection metadata that pairs with a credential belongs in
 * {@link SharedPreferencesSessionMetadataStore}, not here.
 */
public final class KeystoreCredentialVault {
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "linuxwrapper_session_credentials";
    private static final String PREFERENCES = "session_credentials";
    private static final int KEY_SIZE_BITS = 256;

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_BLOB = "blob";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    private final SharedPreferences preferences;
    private final AesGcmCrypto crypto;

    public KeystoreCredentialVault(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        this.crypto = new AesGcmCrypto();
    }

    /**
     * Encrypts and stores a credential, replacing any existing entry for the
     * same id.
     */
    @SuppressLint("ApplySharedPref")
    public void store(String credentialId, CredentialType type, byte[] secret) {
        if (credentialId == null || credentialId.trim().isEmpty()) {
            throw new IllegalArgumentException("credentialId must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        SecretKey key = key();
        byte[] encrypted = crypto.encrypt(key, secret);
        String blob = Base64.getEncoder().encodeToString(encrypted);
        String prefix = prefix(credentialId);
        boolean committed = preferences.edit()
                .putString(prefix + FIELD_TYPE, type.name())
                .putString(prefix + FIELD_BLOB, blob)
                .putLong(prefix + FIELD_UPDATED_AT, System.currentTimeMillis())
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist credential");
        }
    }

    /**
     * Decrypts and returns the secret, or {@code null} if no credential is
     * stored. Corrupt or tampered entries are dropped and reported as absent
     * rather than surfaced.
     */
    public byte[] load(String credentialId) {
        String prefix = prefix(credentialId);
        String blob = preferences.getString(prefix + FIELD_BLOB, null);
        if (blob == null) {
            return null;
        }
        try {
            byte[] encrypted = Base64.getDecoder().decode(blob);
            return crypto.decrypt(key(), encrypted);
        } catch (IllegalArgumentException | CryptoException exception) {
            // Corrupt or tampered ciphertext: do not pretend the credential is usable.
            delete(credentialId);
            return null;
        }
    }

    /** Returns the stored credential type, or {@code null} if absent. */
    public CredentialType typeOf(String credentialId) {
        String prefix = prefix(credentialId);
        String value = preferences.getString(prefix + FIELD_TYPE, null);
        if (value == null) {
            return null;
        }
        try {
            return CredentialType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            delete(credentialId);
            return null;
        }
    }

    @SuppressLint("ApplySharedPref")
    public void delete(String credentialId) {
        String prefix = prefix(credentialId);
        preferences.edit()
                .remove(prefix + FIELD_TYPE)
                .remove(prefix + FIELD_BLOB)
                .remove(prefix + FIELD_UPDATED_AT)
                .commit();
    }

    public boolean exists(String credentialId) {
        String prefix = prefix(credentialId);
        return preferences.getString(prefix + FIELD_BLOB, null) != null;
    }

    private SecretKey key() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
            if (existing instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            // IV randomness is provided by SecureRandom in AesGcmCrypto, so
            // caller-supplied IVs are accepted here.
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(false)
                    .build());
            return generator.generateKey();
        } catch (Exception exception) {
            throw new CryptoException("keystore key unavailable", exception);
        }
    }

    private static String prefix(String credentialId) {
        if (credentialId == null || credentialId.trim().isEmpty()) {
            throw new IllegalArgumentException("credentialId must not be blank");
        }
        return credentialId.replaceAll("[^A-Za-z0-9._-]", "_") + ".";
    }
}
