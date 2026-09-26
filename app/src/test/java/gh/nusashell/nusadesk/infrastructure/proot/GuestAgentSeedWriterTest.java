package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Tests the seeded (not managed) agent bundle contract:
 * {@code /root/.agents/} is written once, upgraded only while unmodified,
 * and never resurrected after a user deletes it. The bookkeeping lives at
 * {@code /var/lib/nusadesk/agent-seed.state} outside the seeded tree.
 */
public class GuestAgentSeedWriterTest {

    private static final String AGENTS_MD = GuestAgentSeedWriter.GUEST_AGENTS_MD_RELATIVE_PATH;
    private static final String SKILL = GuestAgentSeedWriter.SKILL_FILE_RELATIVE_PATH;
    private static final String STATE = GuestAgentSeedWriter.STATE_FILE_RELATIVE_PATH;
    private static final String VERSION = "0.1.0";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void freshInstallSeedsAgentsMdAndSkill() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));

        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Path skill = rootfs.resolve(SKILL);
        assertTrue(Files.isRegularFile(agentsMd));
        assertTrue(Files.isRegularFile(skill));
        assertTrue(Files.isWritable(agentsMd));
        assertTrue(Files.isWritable(skill));

        String agentsText = text(agentsMd);
        assertTrue(agentsText.contains("NusaDesk"));
        assertTrue(agentsText.contains("android-cli"));
        assertTrue(agentsText.contains("termux-"));
        assertTrue(agentsText.contains("byte-identical"));

        String skillText = text(skill);
        assertTrue(skillText.contains("name: termux-api-vs-android-cli"));
        assertTrue(skillText.contains("description:"));

        // Bookkeeping sits outside the seeded tree and records digests.
        Path stateFile = rootfs.resolve(STATE);
        assertTrue(Files.isRegularFile(stateFile));
        String state = text(stateFile);
        assertTrue(state.contains(sha256(GuestAgentSeedWriter.agentsMdContent()) + " " + AGENTS_MD));
        assertTrue(state.contains(sha256(GuestAgentSeedWriter.skillContent()) + " " + SKILL));
        assertFalse(Files.exists(rootfs.resolve("root/.agents").resolve("agent-seed.state")));
    }

    @Test
    public void everySeededPathLivesUnderTheAgentsTree() {
        List<String> paths = GuestAgentSeedWriter.seededRelativePaths();
        assertEquals(2, paths.size());
        for (String path : paths) {
            assertTrue(path.startsWith("root/.agents/"));
            assertFalse(path.contains(".."));
        }
    }

    @Test
    public void secondRunLeavesEverythingUntouched() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        long mtime = Files.getLastModifiedTime(rootfs.resolve(AGENTS_MD)).toMillis();

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));
        assertEquals(mtime,
                Files.getLastModifiedTime(rootfs.resolve(AGENTS_MD)).toMillis());
    }

    @Test
    public void upgradeReplacesOnlyTheUnmodifiedSeed() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);

        // Simulate an older shipped copy: the file on disk and the recorded
        // digest both describe bytes that are no longer the bundled text.
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        byte[] oldShipped = "shipped text from an older app version\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(agentsMd, oldShipped);
        rewriteStateEntry(rootfs, AGENTS_MD, sha256(oldShipped));

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));
        assertEquals(GuestAgentSeedWriter.agentsMdContent(), text(agentsMd));
        assertTrue(text(rootfs.resolve(STATE))
                .contains(sha256(GuestAgentSeedWriter.agentsMdContent()) + " " + AGENTS_MD));
    }

    @Test
    public void userEditedFileIsNeverOverwrittenAcrossVersions() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Files.write(agentsMd, "my own instructions\n".getBytes(StandardCharsets.UTF_8));

        // The state header notes the new app version, so the run reports a
        // write - but the edited file itself is never touched.
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAgentSeedWriter.ensure(rootfs, "9.9.9"));
        assertEquals("my own instructions\n", text(agentsMd));
        // The recorded baseline is still the installed digest, so a later
        // shipped change keeps hands off this file too.
        assertTrue(text(rootfs.resolve(STATE))
                .contains(sha256(GuestAgentSeedWriter.agentsMdContent()) + " " + AGENTS_MD));
    }

    @Test
    public void preExistingUserFileIsMarkedForeignAndKept() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Files.createDirectories(rootfs.resolve("root/.agents"));
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Files.write(agentsMd, "user file from before the feature existed\n"
                .getBytes(StandardCharsets.UTF_8));

        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        assertEquals("user file from before the feature existed\n", text(agentsMd));
        assertTrue(text(rootfs.resolve(STATE)).contains("- " + AGENTS_MD));

        // A foreign path that later disappears is never seeded over.
        Files.delete(agentsMd);
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        assertFalse(Files.exists(agentsMd));
    }

    @Test
    public void deletedSeedFileIsNotRecreatedOnLaterStarts() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        Path skill = rootfs.resolve(SKILL);
        Files.delete(skill);

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));
        assertFalse(Files.exists(skill));
        // The seed record survives so the deletion is remembered.
        assertTrue(text(rootfs.resolve(STATE)).contains(SKILL));
    }

    @Test
    public void deletingTheWholeAgentsTreeIsNotUndone() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        deleteRecursively(rootfs.resolve("root/.agents"));

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));
        assertFalse(Files.exists(rootfs.resolve("root/.agents")));
        // The state file lives outside .agents, so it survived the deletion.
        assertTrue(Files.isRegularFile(rootfs.resolve(STATE)));
    }

    @Test
    public void symlinkedAgentsDirSkipsTheBundleInsteadOfWritingThrough() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createDirectories(rootfs.resolve("root"));
        Files.createSymbolicLink(rootfs.resolve("root/.agents"), outside);

        GuestAgentSeedWriter.ensure(rootfs, VERSION);

        assertFalse(Files.exists(outside.resolve("AGENTS.md")));
        assertTrue(Files.isSymbolicLink(rootfs.resolve("root/.agents")));
        // The paths are pinned foreign so a later deletion still reseeds
        // nothing.
        String state = text(rootfs.resolve(STATE));
        assertTrue(state.contains("- " + AGENTS_MD));
        assertTrue(state.contains("- " + SKILL));
    }

    @Test
    public void seededPathReplacedBySymlinkIsNotFollowed() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Path elsewhere = temporary.newFile("elsewhere.md").toPath();
        Files.delete(agentsMd);
        Files.createSymbolicLink(agentsMd, elsewhere);

        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        assertTrue(Files.isSymbolicLink(agentsMd));
        assertFalse(text(elsewhere).contains("NusaDesk guest"));
    }

    @Test
    public void corruptStateFileNeverOverwritesPresentFiles() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Files.write(agentsMd, "user edit after seeding\n".getBytes(StandardCharsets.UTF_8));
        Path stateFile = rootfs.resolve(STATE);
        Files.write(stateFile, "garbage not a manifest\n".getBytes(StandardCharsets.UTF_8));

        GuestAgentSeedWriter.ensure(rootfs, VERSION);

        // From an unreadable baseline every present path is treated as
        // user-owned; the file is preserved and pinned foreign.
        assertEquals("user edit after seeding\n", text(agentsMd));
        String state = text(stateFile);
        assertTrue(state.contains("- " + AGENTS_MD));
        assertTrue(state.contains("- " + SKILL));
    }

    @Test
    public void missingStateDirFallbackSkipsBundle() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Files.createDirectories(rootfs.resolve("var/lib"));
        Path outside = temporary.newFolder("outside").toPath();
        Files.createSymbolicLink(rootfs.resolve("var/lib/nusadesk"), outside);

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAgentSeedWriter.ensure(rootfs, VERSION));
        assertFalse(Files.exists(rootfs.resolve("root/.agents")));
        assertFalse(Files.exists(outside.resolve("agent-seed.state")));
    }

    /**
     * Detection reads are best-effort: an unreadable seeded file (or an
     * unreadable bookkeeping file) must never fail the session start. The
     * seeded file is treated as not-provably-ours and left alone; the state
     * file is rebuilt by an atomic replace.
     */
    @Test
    public void unreadableSeedOrStateNeverFailsTheSession() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        Path agentsMd = rootfs.resolve(AGENTS_MD);
        Files.setPosixFilePermissions(agentsMd,
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        assumeTrue("needs a non-root process for mode 000 to deny reads",
                !Files.isReadable(agentsMd));

        // Must not throw, and the unreadable seeded file must survive.
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        assertTrue(Files.exists(agentsMd));

        Path state = rootfs.resolve(STATE);
        Files.setPosixFilePermissions(state,
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        assumeTrue("needs a non-root process for mode 000 to deny reads",
                !Files.isReadable(state));

        // Must not throw; the bookkeeping is rebuilt conservatively.
        GuestAgentSeedWriter.ensure(rootfs, VERSION);
        assertTrue(Files.isRegularFile(state));
    }

    @Test
    public void rejectsUnsafeVersionText() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        try {
            GuestAgentSeedWriter.ensure(rootfs, "0.1.0\nrm -rf /");
            org.junit.Assert.fail("expected multiline version to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("single safe line"));
        }
    }

    /**
     * Rewrite one state-file entry's digest, keeping the line format the
     * writer emits ({@code <value> <path>}).
     */
    private static void rewriteStateEntry(Path rootfs, String path, String value)
            throws IOException {
        Path stateFile = rootfs.resolve(STATE);
        StringBuilder rewritten = new StringBuilder();
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            if (line.endsWith(" " + path)) {
                rewritten.append(value).append(' ').append(path).append('\n');
            } else {
                rewritten.append(line).append('\n');
            }
        }
        Files.write(stateFile, rewritten.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted(
                    java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    private static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String sha256(String content) throws Exception {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
