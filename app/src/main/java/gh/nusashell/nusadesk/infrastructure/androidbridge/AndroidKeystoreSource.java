package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * {@code AndroidKeyStore} adapter behind the {@code keystore.*} bridge
 * methods, mirroring the Termux {@code Keystore} surface: list (with an
 * optional detailed key-parameter dump), delete, generate, sign, and verify.
 *
 * <p>The adapter only ever returns public material and operation results —
 * private keys never leave the AndroidKeyStore and are never serialised.
 * Every platform failure is a typed {@link KeystoreException} carrying the
 * lowercase-kebab code the module puts on the wire; platform exception
 * messages stay off the wire.</p>
 *
 * <p>All methods are pure {@link java.security} calls on the shared
 * {@code AndroidKeyStore} provider and need no Android permission.</p>
 */
public final class AndroidKeystoreSource {

    /** Typed failure carrying the bridge error code. */
    public static final class KeystoreException extends Exception {
        private final String code;

        KeystoreException(String code) {
            super(code);
            this.code = code;
        }

        /** The typed lowercase-kebab error the guest sees. */
        public String code() {
            return code;
        }
    }

    /** The provider or engine could not honour the call. */
    static final String ERROR_UNAVAILABLE = "keystore-unavailable";
    /** The named key does not exist or is not usable for the verb. */
    static final String ERROR_UNKNOWN_ALIAS = "keystore-unknown-alias";
    /** The requested algorithm/spec/curve/digest combination was rejected. */
    static final String ERROR_INVALID_SPEC = "keystore-invalid-spec";
    /** The key requires user authentication that has not happened. */
    static final String ERROR_AUTH_REQUIRED = "keystore-auth-required";

    private static final String PROVIDER = "AndroidKeyStore";

    /**
     * The keystore entry list in the upstream JSON shape: one object per
     * private-key alias with {@code alias}, {@code algorithm}, {@code size},
     * {@code inside_secure_hardware}, and a {@code user_authentication} object
     * ({@code required}, {@code enforced_by_secure_hardware}, and
     * {@code validity_duration_seconds} only when the key sets one). With
     * {@code detailed} an RSA key additionally reports {@code modulus} and
     * {@code exponent} hex, an EC key {@code x}/{@code y} affine coordinates.
     */
    public String listJson(boolean detailed) throws KeystoreException {
        KeyStore keyStore = keyStore();
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        try {
            writer.beginArray();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                writer.beginObject();
                writer.name("alias").value(alias);
                KeyStore.Entry entry = keyStore.getEntry(alias, null);
                if (entry instanceof KeyStore.PrivateKeyEntry) {
                    writePrivateKey(writer, (KeyStore.PrivateKeyEntry) entry, detailed);
                }
                writer.endObject();
            }
            writer.endArray();
            writer.flush();
        } catch (IOException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
                // StringWriter close cannot fail.
            }
        }
        return out.toString();
    }

    private void writePrivateKey(JsonWriter writer, KeyStore.PrivateKeyEntry entry,
                                 boolean detailed)
            throws IOException, GeneralSecurityException, KeystoreException {
        PrivateKey privateKey = entry.getPrivateKey();
        String algorithm = privateKey.getAlgorithm();
        KeyInfo keyInfo;
        try {
            keyInfo = KeyFactory.getInstance(algorithm)
                    .getKeySpec(privateKey, KeyInfo.class);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
        PublicKey publicKey = entry.getCertificate().getPublicKey();

        writer.name("algorithm").value(algorithm);
        writer.name("size").value(keyInfo.getKeySize());
        if (detailed && publicKey instanceof RSAPublicKey) {
            RSAPublicKey rsa = (RSAPublicKey) publicKey;
            writer.name("modulus").value(rsa.getModulus().toString(16));
            writer.name("exponent").value(rsa.getPublicExponent().toString(16));
        }
        if (detailed && publicKey instanceof ECPublicKey) {
            ECPublicKey ec = (ECPublicKey) publicKey;
            writer.name("x").value(ec.getW().getAffineX().toString(16));
            writer.name("y").value(ec.getW().getAffineY().toString(16));
        }
        writer.name("inside_secure_hardware").value(insideSecureHardware(keyInfo));
        writer.name("user_authentication");
        writer.beginObject();
        writer.name("required").value(keyInfo.isUserAuthenticationRequired());
        writer.name("enforced_by_secure_hardware")
                .value(keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware());
        int validity = keyInfo.getUserAuthenticationValidityDurationSeconds();
        if (validity >= 0) {
            writer.name("validity_duration_seconds").value(validity);
        }
        writer.endObject();
    }

    /**
     * {@code isInsideSecureHardware} is deprecated on API 31 because it does
     * not distinguish StrongBox; the replacement {@code getSecurityLevel}
     * exists only there. Both answer "runs inside hardware" for this field.
     */
    private static boolean insideSecureHardware(KeyInfo keyInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return keyInfo.getSecurityLevel()
                    >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT;
        }
        @SuppressWarnings("deprecation")
        boolean inside = keyInfo.isInsideSecureHardware();
        return inside;
    }

    /** Whether the keystore holds an entry under {@code alias}. */
    public boolean containsAlias(String alias) throws KeystoreException {
        try {
            return keyStore().containsAlias(alias);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    /**
     * Permanently delete one alias. Like upstream this is a no-op when the
     * alias does not exist; the module reports {@code existed} separately.
     */
    public void delete(String alias) throws KeystoreException {
        try {
            keyStore().deleteEntry(alias);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    /**
     * Generate an RSA or EC key pair inside AndroidKeyStore for the given
     * purposes/digests. {@code validitySeconds} &gt; 0 requires user
     * authentication: on API 30+ the timeout is set through
     * {@code setUserAuthenticationParameters} (the device credential or a
     * strong biometric unlocks the key for that window); below API 30 the
     * legacy {@code setUserAuthenticationValidityDurationSeconds} carries the
     * same meaning.
     */
    public void generate(String alias, String algorithm, long size, String curve,
                         long validitySeconds, int purposes, List<String> digests)
            throws KeystoreException {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, purposes);
        builder.setDigests(digests.toArray(new String[0]));
        if (KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm)) {
            builder.setAlgorithmParameterSpec(
                    new RSAKeyGenParameterSpec((int) size, RSAKeyGenParameterSpec.F4));
            builder.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1);
        } else if (KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)) {
            builder.setAlgorithmParameterSpec(new ECGenParameterSpec(curve));
        }
        if (validitySeconds > 0) {
            builder.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters((int) validitySeconds,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL
                                | KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                builder.setUserAuthenticationValidityDurationSeconds((int) validitySeconds);
            }
        }
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(algorithm, PROVIDER);
            generator.initialize(builder.build());
            generator.generateKeyPair();
        } catch (java.security.InvalidAlgorithmParameterException e) {
            throw new KeystoreException(ERROR_INVALID_SPEC);
        } catch (java.security.NoSuchAlgorithmException
                 | java.security.NoSuchProviderException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        } catch (RuntimeException e) {
            // ProviderException and friends: engine-level failures.
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    /**
     * Sign {@code data} with the private key behind {@code alias} under the
     * given {@link Signature} algorithm name (for example
     * {@code SHA256withRSA}); returns the raw signature bytes.
     */
    public byte[] sign(String alias, String algorithm, byte[] data)
            throws KeystoreException {
        KeyStore.Entry entry = entry(alias);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new KeystoreException(ERROR_UNKNOWN_ALIAS);
        }
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
            signature.update(data);
            return signature.sign();
        } catch (UserNotAuthenticatedException e) {
            throw new KeystoreException(ERROR_AUTH_REQUIRED);
        } catch (java.security.NoSuchAlgorithmException
                 | java.security.InvalidKeyException e) {
            throw new KeystoreException(ERROR_INVALID_SPEC);
        } catch (java.security.SignatureException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    /**
     * Verify {@code signature} over {@code data} against the certificate of
     * {@code alias}. A well-formed but wrong signature — and a malformed one —
     * is simply {@code false}; it is never a typed error.
     */
    public boolean verify(String alias, String algorithm, byte[] data,
                          byte[] signatureBytes) throws KeystoreException {
        java.security.cert.Certificate certificate;
        try {
            certificate = keyStore().getCertificate(alias);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
        if (certificate == null) {
            throw new KeystoreException(ERROR_UNKNOWN_ALIAS);
        }
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(certificate.getPublicKey());
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (java.security.SignatureException e) {
            // A malformed signature is a signature that does not verify.
            return false;
        } catch (UserNotAuthenticatedException e) {
            throw new KeystoreException(ERROR_AUTH_REQUIRED);
        } catch (java.security.NoSuchAlgorithmException
                 | java.security.InvalidKeyException e) {
            throw new KeystoreException(ERROR_INVALID_SPEC);
        } catch (RuntimeException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    private KeyStore.Entry entry(String alias) throws KeystoreException {
        try {
            return keyStore().getEntry(alias, null);
        } catch (GeneralSecurityException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }

    private static KeyStore keyStore() throws KeystoreException {
        try {
            KeyStore keyStore = KeyStore.getInstance(PROVIDER);
            keyStore.load(null);
            return keyStore;
        } catch (GeneralSecurityException | IOException e) {
            throw new KeystoreException(ERROR_UNAVAILABLE);
        }
    }
}
