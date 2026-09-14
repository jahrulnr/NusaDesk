package gh.nusashell.nusadesk.infrastructure.ssh;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;
import java.util.Collection;

/**
 * Generates and persists the guest {@code sshd} host key in OpenSSH format.
 *
 * <p>The curated SSH overlay intentionally ships no {@code ssh-keygen} (that
 * binary lives in {@code openssh-client}, which the profile does not need), so
 * the Android host produces the key material instead: an Ed25519 key pair from
 * the bundled {@code net.i2p.crypto:eddsa} provider, serialized with Apache
 * MINA's {@link OpenSSHKeyPairResourceWriter} into the standard unencrypted
 * {@code openssh-key-v1} PEM that guest {@code sshd} reads natively.</p>
 *
 * <p>The key is written into app-private storage (the add-on overlay's
 * {@code etc/} directory) with mode {@code 0600}, persisted so the guest
 * presents a stable identity across restarts, and never logged. Generation is
 * idempotent: an existing key is parsed back and reused.</p>
 */
public final class GuestSshHostKeyFiles {
    private GuestSshHostKeyFiles() {
    }

    /**
     * Ensure a guest host key exists at {@code privateKeyPath}; create and
     * persist it when absent.
     *
     * @param privateKeyPath host-side path of the {@code ssh_host_ed25519_key}
     * @param publicKeyPath  host-side path of the {@code .pub} line
     * @param comment        non-secret comment embedded in the key
     * @return the public key, for pinning into the client trust store
     */
    public static PublicKey ensure(Path privateKeyPath, Path publicKeyPath, String comment)
            throws IOException, GeneralSecurityException {
        if (Files.isRegularFile(privateKeyPath)) {
            return parsePublicKey(privateKeyPath);
        }
        registerEdDsaProvider();
        KeyPair keyPair = generate();
        write(privateKeyPath, publicKeyPath, keyPair, comment);
        return keyPair.getPublic();
    }

    /**
     * Parse the public half of an existing {@code openssh-key-v1} private key
     * file through MINA's parser (the private material never leaves disk).
     */
    public static PublicKey parsePublicKey(Path privateKeyPath)
            throws IOException, GeneralSecurityException {
        byte[] pem = Files.readAllBytes(privateKeyPath);
        OpenSSHKeyPairResourceParser parser = new OpenSSHKeyPairResourceParser();
        Collection<KeyPair> pairs = parser.loadKeyPairs(
                null, NamedResource.ofName(privateKeyPath.toString()),
                FilePasswordProvider.EMPTY, new ByteArrayInputStream(pem));
        if (pairs == null || pairs.isEmpty()) {
            throw new GeneralSecurityException("guest host key is not a valid OpenSSH key");
        }
        return pairs.iterator().next().getPublic();
    }

    private static void registerEdDsaProvider() {
        if (Security.getProvider("EdDSA") == null) {
            Security.addProvider(new net.i2p.crypto.eddsa.EdDSASecurityProvider());
        }
    }

    private static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EdDSA", "EdDSA");
        return generator.generateKeyPair();
    }

    private static void write(
            Path privateKeyPath,
            Path publicKeyPath,
            KeyPair keyPair,
            String comment)
            throws IOException, GeneralSecurityException {
        Files.createDirectories(privateKeyPath.getParent());
        // Write-then-rename so a crash mid-write cannot leave a half key that
        // sshd would read as its identity.
        Path privateTmp = privateKeyPath.resolveSibling(privateKeyPath.getFileName() + ".tmp");
        Path publicTmp = publicKeyPath.resolveSibling(publicKeyPath.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(
                privateTmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, null, out);
        }
        Files.setPosixFilePermissions(privateTmp,
                PosixFilePermissions.fromString("rw-------"));
        try (OutputStream out = Files.newOutputStream(
                publicTmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(
                    keyPair.getPublic(), comment, out);
        }
        moveAtomically(privateTmp, privateKeyPath);
        moveAtomically(publicTmp, publicKeyPath);
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** OpenSSH authorized_keys-style public key line (for tests and pinning). */
    public static String publicKeyLine(PublicKey key) {
        return PublicKeyEntry.toString(key);
    }
}
