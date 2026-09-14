package gh.nusashell.nusadesk.infrastructure.network;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Android-free tests for {@link GuestResolvConfWriter} using a temp directory.
 * Pins the atomic-write contract, the no-valid-resolver graceful return, and
 * the bounded content.
 */
public class GuestResolvConfWriterTest {

    @Test
    public void writesValidResolvConf() throws IOException {
        Path target = Files.createTempDirectory("resolv-test").resolve("resolv.conf");
        Path result = GuestResolvConfWriter.write(target, Arrays.asList("8.8.8.8", "1.1.1.1"));
        assertEquals(target, result);
        assertTrue(Files.isRegularFile(target));
        String content = new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", content);
    }

    @Test
    public void returnsNullWhenNoValidResolver() throws IOException {
        Path target = Files.createTempDirectory("resolv-test").resolve("resolv.conf");
        Path result = GuestResolvConfWriter.write(target, Collections.singletonList("dns.google"));
        assertNull(result);
        assertFalse("no file written when there is no valid resolver", Files.exists(target));
    }

    @Test
    public void returnsNullForEmptyCandidates() throws IOException {
        Path target = Files.createTempDirectory("resolv-test").resolve("resolv.conf");
        assertNull(GuestResolvConfWriter.write(target, Collections.emptyList()));
        assertFalse(Files.exists(target));
    }

    @Test
    public void rewriteReplacesPreviousContent() throws IOException {
        Path target = Files.createTempDirectory("resolv-test").resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Arrays.asList("8.8.8.8", "1.1.1.1", "9.9.9.9"));
        GuestResolvConfWriter.write(target, Collections.singletonList("1.1.1.1"));
        String content = new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("nameserver 1.1.1.1\n", content);
    }

    @Test
    public void createsParentDirectory() throws IOException {
        Path dir = Files.createTempDirectory("resolv-test");
        Path target = dir.resolve("nested/deep/resolv.conf");
        GuestResolvConfWriter.write(target, Collections.singletonList("8.8.8.8"));
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    public void fileIsBoundedInSize() throws IOException {
        Path target = Files.createTempDirectory("resolv-test").resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Arrays.asList("1.1.1.1", "8.8.8.8", "9.9.9.9", "4.4.4.4"));
        long size = Files.size(target);
        assertTrue("file size " + size + " within bound", size <= GuestResolvConfWriter.MAX_FILE_BYTES);
    }

    @Test
    public void leavesNoTempFileOnSuccess() throws IOException {
        Path dir = Files.createTempDirectory("resolv-test");
        Path target = dir.resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Collections.singletonList("8.8.8.8"));
        long temps = java.util.stream.Stream.of(dir.toFile().listFiles())
                .filter(f -> f.getName().startsWith("resolv-") && f.getName().endsWith(".tmp"))
                .count();
        assertEquals("no leftover temp files", 0, temps);
    }
}
