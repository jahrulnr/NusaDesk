package gh.nusashell.nusadesk.infrastructure.session;

import org.junit.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class AesGcmCryptoTest {
    private final AesGcmCrypto crypto = new AesGcmCrypto();

    private static SecretKey newKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    @Test
    public void encryptThenDecryptRoundTrips() throws Exception {
        SecretKey key = newKey();
        byte[] plaintext = "ssh-private-key-material".getBytes("UTF-8");
        byte[] encrypted = crypto.encrypt(key, plaintext);
        byte[] decrypted = crypto.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void ciphertextIsNotPlaintextAndCarriesIv() throws Exception {
        SecretKey key = newKey();
        byte[] plaintext = "secret".getBytes("UTF-8");
        byte[] encrypted = crypto.encrypt(key, plaintext);
        assertFalse(Arrays.equals(plaintext, encrypted));
        // 12-byte IV prepended to ciphertext+tag.
        assertEquals(12 + plaintext.length + 16, encrypted.length);
    }

    @Test
    public void eachEncryptionUsesAFreshIv() throws Exception {
        SecretKey key = newKey();
        byte[] plaintext = "same-input".getBytes("UTF-8");
        byte[] a = crypto.encrypt(key, plaintext);
        byte[] b = crypto.encrypt(key, plaintext);
        // Different IVs must yield different ciphertexts.
        assertNotEquals(Arrays.hashCode(a), Arrays.hashCode(b));
    }

    @Test
    public void tamperedCiphertextFailsAuthentication() throws Exception {
        SecretKey key = newKey();
        byte[] encrypted = crypto.encrypt(key, "secret".getBytes("UTF-8"));
        encrypted[encrypted.length - 1] ^= 0x01;
        try {
            crypto.decrypt(key, encrypted);
            fail();
        } catch (CryptoException expected) {
            // GCM tag verification must fail.
        }
    }

    @Test
    public void wrongKeyCannotDecrypt() throws Exception {
        byte[] encrypted = crypto.encrypt(newKey(), "secret".getBytes("UTF-8"));
        try {
            crypto.decrypt(newKey(), encrypted);
            fail();
        } catch (CryptoException expected) {
            // expected
        }
    }

    @Test
    public void rejectsTooShortCiphertext() throws Exception {
        try {
            crypto.decrypt(newKey(), new byte[12]);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullArguments() throws Exception {
        try {
            crypto.encrypt(null, new byte[1]);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            crypto.encrypt(newKey(), null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
