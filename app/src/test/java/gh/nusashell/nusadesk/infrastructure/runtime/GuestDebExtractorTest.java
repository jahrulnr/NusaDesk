package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-JVM tests for {@link GuestDebExtractor#extractFromProcess}: the guest
 * decoder emits a raw tar stream (what {@code dpkg-deb --fsys-tarfile} writes
 * to stdout), and this proves the shared {@link PayloadIo#extractTar} safety
 * rules apply identically to deb payloads — traversal, escaping symlinks,
 * unsupported types, and size caps — plus the process-level contract:
 * non-zero exits, stalled decoders, and tree teardown on failure.
 */
public class GuestDebExtractorTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void extractsDecodedTarStream() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.dir("usr/"));
        entries.add(TarSpec.dir("usr/sbin/"));
        entries.add(TarSpec.file("usr/sbin/sshd", "binary-bytes"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);

        Path out = staging.resolve("usr/sbin/sshd");
        assertTrue(Files.isRegularFile(out));
        assertArrayEquals("binary-bytes".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(out));
    }

    @Test
    public void extractsSymlinkSafely() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.dir("usr/lib/"));
        entries.add(TarSpec.file("usr/lib/libreal.so.1", "elf"));
        entries.add(TarSpec.symlink("usr/lib/libreal.so", "libreal.so.1"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);

        assertTrue(Files.isSymbolicLink(staging.resolve("usr/lib/libreal.so")));
    }

    @Test
    public void rejectsTraversalAndDestroysProcess() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.file("../evil", "x"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);
            fail("traversal entry must be rejected");
        } catch (RuntimeInstallationException expected) {
        }
        assertTrue("decoder process must be destroyed on failure", process.destroyed);
    }

    @Test
    public void absolutePathsAreNormalizedInsideStaging() throws Exception {
        // commons-compress strips the leading '/' from tar names, so an
        // absolute member lands inside staging rather than escaping; the
        // resolver still rejects anything that survives as an absolute path.
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.file("/etc/lw-marker", "x"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);

        assertTrue(Files.isRegularFile(staging.resolve("etc/lw-marker")));
    }

    @Test
    public void rejectsEscapingSymlink() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.dir("usr/lib/"));
        entries.add(TarSpec.symlink("usr/lib/escape", "../../../../outside"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);
            fail("escaping symlink must be rejected");
        } catch (RuntimeInstallationException expected) {
        }
    }

    @Test
    public void rejectsEntriesBeyondSizeCap() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.file("big", "0123456789"));
        FakeProcess process = new FakeProcess(buildTar(entries), "", 0);

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 4);
            fail("extraction beyond the size cap must be rejected");
        } catch (RuntimeInstallationException expected) {
        }
        assertTrue(process.destroyed);
    }

    @Test
    public void rejectsTruncatedTarStream() throws Exception {
        // Decoder died mid-stream: the tar ends abruptly inside a file.
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.file("usr/sbin/sshd", "binary-bytes"));
        byte[] full = buildTar(entries);
        byte[] truncated = new byte[700];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        FakeProcess process = new FakeProcess(truncated, "", 0);

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);
            fail("a truncated decoder stream must be rejected");
        } catch (IOException | RuntimeInstallationException expected) {
        }
    }

    @Test
    public void failsOnNonZeroExitWithStderr() throws Exception {
        List<TarSpec> entries = new ArrayList<>();
        entries.add(TarSpec.file("ok", "x"));
        FakeProcess process =
                new FakeProcess(buildTar(entries), "dpkg-deb: archive corrupt", 2);

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 1_000_000);
            fail("non-zero decoder exit must be rejected");
        } catch (RuntimeInstallationException expected) {
            assertTrue(expected.getMessage().contains("archive corrupt"));
        }
    }

    @Test
    public void failsWhenDecoderStalls() throws Exception {
        FakeProcess process = new FakeProcess(new byte[0], "", 0);
        process.neverExits = true;

        Path staging = temp.newFolder("staging").toPath();
        try {
            GuestDebExtractor.extractFromProcess(process, staging, 1_000_000, 50);
            fail("a stalled decoder must be rejected");
        } catch (RuntimeInstallationException expected) {
            assertTrue(expected.getMessage().contains("did not finish"));
        }
        assertTrue(process.destroyed);
    }

    // ---- fixtures ----

    private static final class TarSpec {
        final String name;
        final byte[] content;
        final String linkName;
        final boolean directory;

        private TarSpec(String name, byte[] content, String linkName, boolean directory) {
            this.name = name;
            this.content = content;
            this.linkName = linkName;
            this.directory = directory;
        }

        static TarSpec dir(String name) {
            return new TarSpec(name, null, null, true);
        }

        static TarSpec file(String name, String content) {
            return new TarSpec(name, content.getBytes(StandardCharsets.UTF_8), null, false);
        }

        static TarSpec symlink(String name, String linkName) {
            return new TarSpec(name, null, linkName, false);
        }
    }

    private byte[] buildTar(List<TarSpec> entries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            for (TarSpec spec : entries) {
                TarArchiveEntry entry;
                if (spec.directory) {
                    entry = new TarArchiveEntry(spec.name, TarArchiveEntry.LF_DIR);
                } else if (spec.linkName != null) {
                    entry = new TarArchiveEntry(spec.name, TarArchiveEntry.LF_SYMLINK);
                    entry.setLinkName(spec.linkName);
                } else {
                    entry = new TarArchiveEntry(spec.name, TarArchiveEntry.LF_NORMAL);
                    entry.setSize(spec.content.length);
                }
                entry.setMode(spec.directory ? 0755 : 0644);
                tar.putArchiveEntry(entry);
                if (spec.content != null) {
                    tar.write(spec.content);
                }
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return raw.toByteArray();
    }

    /** Minimal {@link Process} fake: canned stdout/stderr and exit state. */
    private static final class FakeProcess extends Process {
        private final byte[] stdout;
        private final byte[] stderr;
        private final int exitCode;
        boolean destroyed;
        boolean neverExits;

        FakeProcess(byte[] stdout, String stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(stdout);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(stderr);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !neverExits;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            neverExits = false;
            return this;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }
}
