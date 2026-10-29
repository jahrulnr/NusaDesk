package gh.nusashell.nusadesk.infrastructure.webapp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import gh.nusashell.nusadesk.domain.webapp.DownloadFileName;

/** One incomplete download; only a length-checked completion publishes its filename. */
final class GuestDownloadFile implements AutoCloseable {
    private final Path directory;
    private final Path partial;
    private final String name;
    private final long expected;
    private final OutputStream output;
    private long count;

    GuestDownloadFile(Path rootfs, String name, long expected) throws IOException {
        this.name = DownloadFileName.safe(name);
        this.expected = expected;
        if (!Files.isDirectory(rootfs, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Linux is not installed");
        Path root = rootfs.resolve("root");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Guest /root is unavailable");
        directory = root.resolve("Downloads");
        try { Files.createDirectory(directory); } catch (FileAlreadyExistsException exists) { /* Verify below. */ }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Guest /root/Downloads is not a directory");
        partial = Files.createTempFile(directory, ".nusadesk-download-", ".part");
        try { output = Files.newOutputStream(partial); }
        catch (IOException failure) { Files.deleteIfExists(partial); throw failure; }
    }

    void append(byte[] bytes, int length) throws IOException {
        if (expected >= 0 && length > expected - count) throw new IOException("Download exceeds advertised size");
        output.write(bytes, 0, length);
        count += length;
    }

    long count() { return count; }

    String finish() throws IOException {
        if (expected >= 0 && expected != count) throw new IOException("Incomplete response");
        output.close();
        for (int number = 0; number < 10_000; number++) {
            Path target = directory.resolve(DownloadFileName.numbered(name, number));
            // ATOMIC_MOVE may replace an existing target; the default move must not.
            try { return Files.move(partial, target).getFileName().toString(); }
            catch (FileAlreadyExistsException collision) { /* Keep the guest file and try a suffix. */ }
        }
        throw new IOException("Too many files with this name");
    }

    @Override public void close() throws IOException {
        try { output.close(); } finally { Files.deleteIfExists(partial); }
    }
}
