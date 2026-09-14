package gh.nusashell.nusadesk.infrastructure.session;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android-free AES-GCM (GCM/NoPadding, 128-bit tag, 12-byte random IV) helper.
 *
 * <p>Kept free of Android types so the cryptographic core can be unit-tested on
 * the JVM with a JDK-generated key. {@link KeystoreCredentialVault} supplies
 * an AndroidKeyStore-backed {@link SecretKey}; the algorithm and wire format
 * (IV prepended to ciphertext+tag) are identical for both key sources.
 *
 * <p>The IV is generated with {@link SecureRandom} and prepended to the output.
 * Callers must store the whole blob verbatim and pass it back to
 * {@link #decrypt(SecretKey, byte[])}.
 */
final class AesGcmCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    byte[] encrypt(SecretKey key, byte[] plaintext) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException exception) {
            throw new CryptoException("encryption failed", exception);
        }
    }

    byte[] decrypt(SecretKey key, byte[] ivAndCiphertext) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (ivAndCiphertext == null || ivAndCiphertext.length <= IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("invalid ciphertext");
        }
        try {
            byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_LENGTH_BYTES, ivAndCiphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new CryptoException("decryption failed", exception);
        }
    }
}
