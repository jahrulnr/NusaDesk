package gh.nusashell.nusadesk.infrastructure.session;

import android.content.Context;

import gh.nusashell.nusadesk.infrastructure.sshserver.SshServerHostKeyStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Persists the loopback SSH bridge's RSA host key in app-private storage so the
 * bridge presents a stable identity across restarts.
 *
 * <p>The private and public halves are stored as PKCS#8 / X.509 encodings in a
 * directory under {@code filesDir}, which is already mode-0700 app-private.
 * A corrupt or partially written pair is deleted and reported as absent so the
 * bridge generates a fresh key rather than starting with a mismatched pair —
 * the client trust record is scoped to host:port and re-seeded on each start,
 * so regeneration cannot silently impersonate a previously trusted key.</p>
 *
 * <p>The path-based core is deliberately free of Android types so the codec
 * round-trip and corruption handling are unit-testable on a plain JVM;
 * {@link #inAppStorage(Context)} is the thin Android factory.</p>
 */
public final class FileSshServerHostKeyStore implements SshServerHostKeyStore {

    private static final String DIRECTORY = "ssh-bridge";
    private static final String PRIVATE_KEY_FILE = "hostkey.priv";
    private static final String PUBLIC_KEY_FILE = "hostkey.pub";
    private static final String KEY_ALGORITHM = "RSA";

    private final Path directory;

    public FileSshServerHostKeyStore(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        this.directory = directory;
    }

    /** App-private factory: stores the key pair under {@code filesDir/ssh-bridge}. */
    public static FileSshServerHostKeyStore inAppStorage(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new FileSshServerHostKeyStore(
                context.getApplicationContext().getFilesDir().toPath().resolve(DIRECTORY));
    }

    /** Returns the persisted key pair, or {@code null} if absent or corrupt. */
    @Override
    public KeyPair load() {
        Path privatePath = directory.resolve(PRIVATE_KEY_FILE);
        Path publicPath = directory.resolve(PUBLIC_KEY_FILE);
        if (!Files.isRegularFile(privatePath) || !Files.isRegularFile(publicPath)) {
            return null;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException | IOException e) {
            deleteQuietly(privatePath);
            deleteQuietly(publicPath);
            return null;
        }
    }

    /**
     * Persists the key pair. The private half is written with owner-only
     * permissions as defence in depth on top of the app-private directory.
     */
    @Override
    public void save(KeyPair keyPair) {
        if (keyPair == null || keyPair.getPrivate() == null || keyPair.getPublic() == null) {
            throw new IllegalArgumentException("keyPair must have both halves");
        }
        try {
            Files.createDirectories(directory);
            Path privatePath = directory.resolve(PRIVATE_KEY_FILE);
            Path publicPath = directory.resolve(PUBLIC_KEY_FILE);
            Files.write(privatePath, keyPair.getPrivate().getEncoded());
            Files.write(publicPath, keyPair.getPublic().getEncoded());
            privatePath.toFile().setReadable(false, false);
            privatePath.toFile().setReadable(true, true);
            privatePath.toFile().setWritable(false, false);
            privatePath.toFile().setWritable(true, true);
        } catch (IOException e) {
            throw new IllegalStateException("could not persist SSH bridge host key", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort corruption cleanup
        }
    }
}
