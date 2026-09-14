package gh.nusashell.nusadesk.infrastructure.ssh;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PublicKey;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for {@link GuestSshHostKeyFiles}: generated keys are standard
 * unencrypted {@code openssh-key-v1} Ed25519 files that MINA's parser reads
 * back, the {@code .pub} line is authorized_keys format, persistence is
 * idempotent, and the private key lands mode 0600.
 */
public class GuestSshHostKeyFilesTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void generatesOpenSshEd25519KeyPair() throws Exception {
        Path dir = temp.newFolder("etc").toPath();
        Path priv = dir.resolve("ssh_host_ed25519_key");
        Path pub = dir.resolve("ssh_host_ed25519_key.pub");

        PublicKey key = GuestSshHostKeyFiles.ensure(priv, pub, "lw-test");

        String pem = new String(Files.readAllBytes(priv), StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertTrue(pem.trim().endsWith("-----END OPENSSH PRIVATE KEY-----"));

        String pubLine = new String(Files.readAllBytes(pub), StandardCharsets.US_ASCII).trim();
        assertTrue(pubLine.startsWith("ssh-ed25519 AAAA"));

        // The file parses back through MINA's decoder to the same public key.
        assertEquals(key, GuestSshHostKeyFiles.parsePublicKey(priv));
    }

    @Test
    public void ensureIsIdempotentAndReusesPersistedKey() throws Exception {
        Path dir = temp.newFolder("etc").toPath();
        Path priv = dir.resolve("ssh_host_ed25519_key");
        Path pub = dir.resolve("ssh_host_ed25519_key.pub");

        PublicKey first = GuestSshHostKeyFiles.ensure(priv, pub, "lw-test");
        PublicKey second = GuestSshHostKeyFiles.ensure(priv, pub, "lw-test");
        assertEquals("existing key must be reused, not regenerated", first, second);
    }

    @Test
    public void privateKeyIsWrittenOwnerOnly() throws Exception {
        Path dir = temp.newFolder("etc").toPath();
        Path priv = dir.resolve("ssh_host_ed25519_key");
        Path pub = dir.resolve("ssh_host_ed25519_key.pub");

        GuestSshHostKeyFiles.ensure(priv, pub, "lw-test");

        Set<?> perms = Files.getPosixFilePermissions(priv);
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms);
    }
}
