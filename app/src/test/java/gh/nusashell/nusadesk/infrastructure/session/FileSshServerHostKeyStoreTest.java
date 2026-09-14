package gh.nusashell.nusadesk.infrastructure.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FileSshServerHostKeyStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void absentDirectoryLoadsNull() throws Exception {
        FileSshServerHostKeyStore store =
                new FileSshServerHostKeyStore(folder.newFolder().toPath());
        assertNull(store.load());
    }

    @Test
    public void saveThenLoadRoundTripsTheSameKeyPair() throws Exception {
        Path dir = folder.newFolder().toPath();
        FileSshServerHostKeyStore store = new FileSshServerHostKeyStore(dir);
        KeyPair pair = generateRsa();

        store.save(pair);
        KeyPair loaded = store.load();

        assertArrayEquals(pair.getPrivate().getEncoded(), loaded.getPrivate().getEncoded());
        assertArrayEquals(pair.getPublic().getEncoded(), loaded.getPublic().getEncoded());
    }

    @Test
    public void corruptPrivateHalfLoadsNullAndCleansUp() throws Exception {
        Path dir = folder.newFolder().toPath();
        FileSshServerHostKeyStore store = new FileSshServerHostKeyStore(dir);
        store.save(generateRsa());
        Files.write(dir.resolve("hostkey.priv"), new byte[]{1, 2, 3});

        assertNull(store.load());
        // The corrupt pair is deleted so the next save/load cycle is clean.
        assertNull(store.load());
    }

    @Test
    public void missingPublicHalfLoadsNull() throws Exception {
        Path dir = folder.newFolder().toPath();
        FileSshServerHostKeyStore store = new FileSshServerHostKeyStore(dir);
        store.save(generateRsa());
        Files.delete(dir.resolve("hostkey.pub"));

        assertNull(store.load());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyPairSaveThrows() throws Exception {
        new FileSshServerHostKeyStore(folder.newFolder().toPath()).save(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullDirectoryThrows() {
        new FileSshServerHostKeyStore(null);
    }

    @Test
    public void saveIsIdempotentAndOverwrites() throws Exception {
        Path dir = folder.newFolder().toPath();
        FileSshServerHostKeyStore store = new FileSshServerHostKeyStore(dir);
        KeyPair first = generateRsa();
        KeyPair second = generateRsa();
        store.save(first);
        store.save(second);
        assertArrayEquals(second.getPrivate().getEncoded(),
                store.load().getPrivate().getEncoded());
        // Two RSA-2048 keys will not collide.
        assertEquals(false, Arrays.equals(first.getPrivate().getEncoded(),
                second.getPrivate().getEncoded()));
    }

    private static KeyPair generateRsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
