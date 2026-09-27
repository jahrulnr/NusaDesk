package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;
import gh.nusashell.nusadesk.infrastructure.session.CredentialType;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreCredentialVault;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link KeystoreWebAppCredentialStore} over a Robolectric app context and a
 * real {@link KeystoreCredentialVault}.
 *
 * <p>Robolectric 4.15.1 ships no keystore shadow, so the real
 * {@code AndroidKeyStore} provider does not exist on the JVM. Rather than
 * stubbing the class under test, the suite registers a test-only JCA provider
 * literally named {@code AndroidKeyStore} that keeps a single generated AES key
 * in memory. Every byte still round-trips through the vault's real AES-GCM
 * encrypt/decrypt and real SharedPreferences writes — only the key's custody is
 * faked, which is exactly the part a JVM cannot provide. Hardware-backed key
 * storage therefore remains a device-verification item; nothing here claims
 * it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class KeystoreWebAppCredentialStoreTest {

    private static final String PROVIDER_NAME = "AndroidKeyStore";
    /** Mirrors {@link KeystoreCredentialVault}'s private preferences name. */
    private static final String VAULT_PREFERENCES = "session_credentials";

    private static final WebAppId APP_ID = WebAppId.of("app-1");
    private static final WebAppId OTHER_APP_ID = WebAppId.of("app-2");
    private static final String USERNAME = "alice.the.admin";
    private static final String PASSWORD = "p@ssw0rd-Qm8xT2vL9wR4";

    /** The one in-memory key slot the fake provider serves to the vault. */
    private static volatile SecretKey generatedKey;

    @Before
    public void installInMemoryKeystore() {
        generatedKey = null;
        Security.addProvider(new InMemoryAndroidKeyStoreProvider());
    }

    @After
    public void removeInMemoryKeystore() {
        Security.removeProvider(PROVIDER_NAME);
        generatedKey = null;
    }

    private static KeystoreWebAppCredentialStore store() {
        return KeystoreWebAppCredentialStore.inAppStorage(
                RuntimeEnvironment.getApplication());
    }

    private static SharedPreferences vaultPreferences() {
        return RuntimeEnvironment.getApplication()
                .getSharedPreferences(VAULT_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static WebAppSignInCredential credential() {
        return new WebAppSignInCredential(APP_ID, USERNAME, PASSWORD);
    }

    @Test
    public void credentialIdIsTheWebappPrefixedAppId() {
        assertEquals("webapp.app-1",
                KeystoreWebAppCredentialStore.credentialId(APP_ID));
    }

    @Test
    public void saveThenFindReturnsTheSameCredential() {
        KeystoreWebAppCredentialStore store = store();
        store.save(credential());
        assertEquals(credential(), store.find(APP_ID));
    }

    @Test
    public void aPairIsScopedToItsOwnApp() {
        KeystoreWebAppCredentialStore store = store();
        store.save(credential());
        assertNull("another app must never read this pair", store.find(OTHER_APP_ID));
    }

    @Test
    public void clearForgetsThePair() {
        KeystoreWebAppCredentialStore store = store();
        store.save(credential());
        store.clear(APP_ID);
        assertNull(store.find(APP_ID));
    }

    @Test
    public void findOnAnAppThatNeverSavedReturnsNull() {
        KeystoreWebAppCredentialStore store = store();
        assertNull(store.find(APP_ID));
    }

    @Test
    public void onlyTheEncryptedBlobReachesStorage() throws Exception {
        KeystoreWebAppCredentialStore store = store();
        store.save(credential());

        Map<String, ?> values = vaultPreferences().getAll();
        Object blob = values.get("webapp." + APP_ID.value() + ".blob");
        assertTrue("the vault must store under webapp.<webAppId>",
                blob instanceof String);
        assertEquals("PASSWORD", values.get("webapp." + APP_ID.value() + ".type"));

        String encoded = (String) blob;
        assertFalse(encoded.contains(USERNAME));
        assertFalse(encoded.contains(PASSWORD));

        // The blob is Base64 IV+ciphertext: it must not equal the plaintext
        // encoding and its bytes must not contain either field.
        byte[] ciphertext = Base64.getDecoder().decode(encoded);
        byte[] plaintext = WebAppSignInCodec.encode(credential());
        assertFalse(Arrays.equals(plaintext, ciphertext));
        String ciphertextAsText = new String(ciphertext, StandardCharsets.ISO_8859_1);
        assertFalse(ciphertextAsText.contains(USERNAME));
        assertFalse(ciphertextAsText.contains(PASSWORD));

        // And nothing plaintext reached the SharedPreferences file itself.
        File prefsFile = new File(RuntimeEnvironment.getApplication().getDataDir(),
                "shared_prefs/" + VAULT_PREFERENCES + ".xml");
        if (prefsFile.isFile()) {
            String xml = new String(Files.readAllBytes(prefsFile.toPath()),
                    StandardCharsets.UTF_8);
            assertFalse(xml.contains(USERNAME));
            assertFalse(xml.contains(PASSWORD));
        }
    }

    @Test
    public void anUndecodableStoredBlobReadsAsNoCredential() {
        // A real vault write of bytes that are not a credential: decrypts fine,
        // then the codec must fail it closed instead of throwing.
        KeystoreCredentialVault vault =
                new KeystoreCredentialVault(RuntimeEnvironment.getApplication());
        vault.store(KeystoreWebAppCredentialStore.credentialId(APP_ID),
                CredentialType.PASSWORD,
                "not-a-credential".getBytes(StandardCharsets.UTF_8));

        KeystoreWebAppCredentialStore store = store();
        assertNull(store.find(APP_ID));
    }

    @Test
    public void anUnavailableKeystoreMeansNoStoredCredential() {
        // Removing the provider makes vault.store throw CryptoException; the
        // store must not let that escape and must answer as if nothing exists.
        Security.removeProvider(PROVIDER_NAME);
        KeystoreWebAppCredentialStore store = store();

        store.save(credential());

        assertNull(store.find(APP_ID));
    }

    @Test
    public void nullArgumentsAreRejected() {
        KeystoreWebAppCredentialStore store = store();
        assertThrows(IllegalArgumentException.class,
                () -> new KeystoreWebAppCredentialStore(null));
        assertThrows(IllegalArgumentException.class,
                () -> KeystoreWebAppCredentialStore.inAppStorage(null));
        assertThrows(IllegalArgumentException.class, () -> store.find(null));
        assertThrows(IllegalArgumentException.class, () -> store.save(null));
        assertThrows(IllegalArgumentException.class, () -> store.clear(null));
    }

    // ---- test-only AndroidKeyStore stand-in ----

    /**
     * The key slot both SPI classes share. The vault uses exactly one alias, so
     * the fixture keeps a single key rather than an alias map; the alias the
     * vault asks for is ignored, which is enough for this store's contract.
     */
    private static SecretKey keySlot() {
        return generatedKey;
    }

    /**
     * A provider named {@code AndroidKeyStore} so that both lookups the vault
     * makes — {@code KeyStore.getInstance("AndroidKeyStore")} and
     * {@code KeyGenerator.getInstance("AES", "AndroidKeyStore")} — resolve to
     * the in-memory SPI classes below.
     */
    private static final class InMemoryAndroidKeyStoreProvider extends Provider {
        InMemoryAndroidKeyStoreProvider() {
            super(PROVIDER_NAME, 1.0d, "test-only in-memory keystore");
            put("KeyStore." + PROVIDER_NAME, InMemoryKeyStoreSpi.class.getName());
            put("KeyGenerator.AES", InMemoryKeyGeneratorSpi.class.getName());
        }
    }

    /**
     * Minimal {@link KeyStoreSpi}: mirrors the real {@code AndroidKeyStoreSpi}
     * contract where a key entry is returned without a password — the JDK
     * default {@code engineGetEntry} would instead demand a
     * {@code PasswordProtection} parameter, so it is overridden here.
     */
    public static final class InMemoryKeyStoreSpi extends KeyStoreSpi {
        @Override
        public KeyStore.Entry engineGetEntry(
                String alias, KeyStore.ProtectionParameter protParam) {
            return generatedKey == null
                    ? null : new KeyStore.SecretKeyEntry(generatedKey);
        }

        @Override
        public Key engineGetKey(String alias, char[] password) {
            return keySlot();
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            return null;
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return null;
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return new Date(0L);
        }

        @Override
        public void engineSetKeyEntry(
                String alias, Key key, char[] password, Certificate[] chain) {
            if (key instanceof SecretKey) {
                generatedKey = (SecretKey) key;
            }
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
            throw new UnsupportedOperationException("generated keys only");
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate certificate) {
            throw new UnsupportedOperationException("no certificates");
        }

        @Override
        public void engineDeleteEntry(String alias) {
            generatedKey = null;
        }

        @Override
        public Enumeration<String> engineAliases() {
            return Collections.enumeration(
                    generatedKey == null ? Collections.emptySet()
                            : Collections.singleton("key"));
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return generatedKey != null;
        }

        @Override
        public int engineSize() {
            return generatedKey == null ? 0 : 1;
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return generatedKey != null;
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return false;
        }

        @Override
        public String engineGetCertificateAlias(Certificate certificate) {
            return null;
        }

        @Override
        public void engineStore(OutputStream stream, char[] password) {
            // in-memory: nothing to persist
        }

        @Override
        public void engineLoad(InputStream stream, char[] password) {
            // in-memory: nothing to load
        }
    }

    /**
     * Minimal {@link KeyGeneratorSpi}: ignores the {@code KeyGenParameterSpec}
     * (which only the real provider can honour anyway) and registers the
     * generated key into the shared slot, mirroring how the real
     * {@code AndroidKeyStore} persists a generated key under its alias.
     */
    public static final class InMemoryKeyGeneratorSpi extends KeyGeneratorSpi {
        private SecureRandom random = new SecureRandom();

        @Override
        protected void engineInit(SecureRandom random) {
            this.random = random;
        }

        @Override
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) {
            this.random = random;
        }

        @Override
        protected void engineInit(int keysize, SecureRandom random) {
            this.random = random;
        }

        @Override
        protected SecretKey engineGenerateKey() {
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            generatedKey = new SecretKeySpec(keyBytes, "AES");
            return generatedKey;
        }
    }
}
