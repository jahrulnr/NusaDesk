package gh.nusashell.nusadesk.infrastructure.session;

/**
 * Raised when AES-GCM encryption or decryption fails. Messages are generic on
 * purpose: they never include key material, plaintext, or ciphertext so a
 * captured exception cannot leak a secret.
 */
public final class CryptoException extends RuntimeException {
    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
