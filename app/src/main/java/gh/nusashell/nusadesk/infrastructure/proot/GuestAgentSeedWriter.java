package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the user-editable agent bundle into the guest: a global
 * {@code /root/.agents/AGENTS.md} plus starter skills under
 * {@code /root/.agents/skills/}, headed by the Termux-API-vs-{@code
 * android-cli} decision skill.
 *
 * <p>This writer deliberately has different semantics from the generated
 * awareness bundle ({@link GuestAwarenessReadmeWriter}): those files are
 * app-managed and rewritten on every session start, while {@code ~/.agents}
 * is the user's own tree. The contract here is <em>seed, not manage</em>:</p>
 *
 * <ul>
 *   <li>a bundled path that has never been seeded and does not exist is
 *       written — that covers a fresh install and a fresh rootfs;</li>
 *   <li>a seeded file whose bytes still match the recorded install digest is
 *       the app's own unmodified copy: when an app update ships new content
 *       it is replaced, so upgrades reach users who never edited;</li>
 *   <li>a file whose bytes differ from the recorded digest was edited, and a
 *       path that exists but was never seeded is foreign: neither is ever
 *       overwritten;</li>
 *   <li>a seeded path that no longer exists was deleted by the user: it is
 *       never recreated, so recurring session starts do not undo that
 *       removal. The same holds for a foreign path that later disappears —
 *       the record says the location was claimed by the user, not vacated
 *       for the app.</li>
 * </ul>
 *
 * <p>The bookkeeping lives outside the seeded tree at
 * {@code /var/lib/nusadesk/agent-seed.state} so deleting
 * {@code ~/.agents} — including the record file inside it would be deleted
 * too — cannot masquerade as a fresh install. The state file lives and dies
 * with the rootfs and sits inside the backup scope, so a reinstall re-seeds
 * and a restore keeps files and bookkeeping consistent. Each line is either
 * {@code <sha256> <path>} (the app wrote that path at that digest) or
 * {@code - <path>} (a user-owned path the app must never write).</p>
 *
 * <p>Occupancy is respected, never fought: a symlink or non-directory on any
 * fixed parent, or a seeded path that exists but is not a plain regular
 * file, marks that path foreign instead of writing through it, and an
 * unsafe state-file path skips the whole bundle for the run. A corrupt state
 * file is rebuilt from scratch rather than trusted: present bundled paths
 * are recorded foreign — nothing is ever overwritten from an unreadable
 * baseline — and absent ones are seeded again. Real I/O failures still
 * propagate like every other guest writer.</p>
 *
 * <p>The seeded texts carry no app-version stamp on purpose: the digest in
 * the state file is the real change signal, so a rebuild with unchanged
 * text writes nothing and never touches an unmodified seeded file.</p>
 */
public final class GuestAgentSeedWriter {

    /** Guest-relative agent directory, seeded under the guest's /root. */
    public static final String GUEST_AGENTS_RELATIVE_PATH = "root/.agents";
    /** Guest-relative path of the global agent instruction file. */
    public static final String GUEST_AGENTS_MD_RELATIVE_PATH =
            GUEST_AGENTS_RELATIVE_PATH + "/AGENTS.md";
    /** Guest-relative directory that receives the seeded skills. */
    public static final String GUEST_SKILLS_RELATIVE_PATH =
            GUEST_AGENTS_RELATIVE_PATH + "/skills";
    /** Name of the bundled decision skill directory. */
    public static final String SKILL_NAME = "termux-api-vs-android-cli";
    /** Guest-relative path of the seeded skill file. */
    public static final String SKILL_FILE_RELATIVE_PATH =
            GUEST_SKILLS_RELATIVE_PATH + "/" + SKILL_NAME + "/SKILL.md";
    /** Guest-relative bookkeeping directory, outside the seeded tree. */
    public static final String STATE_DIR_RELATIVE_PATH = "var/lib/nusadesk";
    /** Guest-relative seed bookkeeping file. */
    public static final String STATE_FILE_RELATIVE_PATH =
            STATE_DIR_RELATIVE_PATH + "/agent-seed.state";

    private static final String SEEDED_PERMISSIONS = "rw-r--r--";
    private static final String TEMP_PREFIX = ".nusadesk-";
    private static final String TEMP_SUFFIX = ".tmp";
    /** State-file value marking a user-owned path the app must never write. */
    private static final String FOREIGN = "-";
    private static final int SHA256_HEX_CHARS = 64;

    /** Bundled seed asset paths under the guest-docs asset root. */
    private static final String AGENTS_MD_ASSET_PATH = "agents/AGENTS.md";
    private static final String SKILL_ASSET_PATH =
            "agents/skills/" + SKILL_NAME + "/SKILL.md";
    /** Placeholder in the skill asset's frontmatter. */
    private static final String SKILL_NAME_TOKEN = "{{SKILL_NAME}}";
    /**
     * The seed bundle as {@code {guest-relative path, asset path}} pairs —
     * one table so the file list and the path list can never desync.
     */
    private static final String[][] SEED_FILES = {
            {GUEST_AGENTS_MD_RELATIVE_PATH, AGENTS_MD_ASSET_PATH},
            {SKILL_FILE_RELATIVE_PATH, SKILL_ASSET_PATH},
    };

    private GuestAgentSeedWriter() {
    }

    /** Guest-relative paths of every file in the seeded bundle, in order. */
    public static List<String> seededRelativePaths() {
        List<String> paths = new ArrayList<>(SEED_FILES.length);
        for (String[] entry : SEED_FILES) {
            paths.add(entry[0]);
        }
        return Collections.unmodifiableList(paths);
    }

    /**
     * Seed or reconcile the agent bundle against the on-disk guest tree and
     * the recorded seed state, per the class contract.
     *
     * @param activeRootfs validated active rootfs directory
     * @param appVersion APK version name, not user input; recorded in the
     *        bookkeeping header only — seeded file bodies carry no version
     * @param docSource source of the bundled seed files
     * @return whether any file or the bookkeeping record was written
     * @throws IOException on a real write/read failure; unsafe fixed paths
     *         skip the affected entry instead of throwing
     */
    public static GuestAwarenessReadmeWriter.Result ensure(Path activeRootfs,
            String appVersion,
            GuestAwarenessReadmeWriter.GuestDocSource docSource)
            throws IOException {
        if (activeRootfs == null || docSource == null) {
            throw new IllegalArgumentException("activeRootfs/docSource must not be null");
        }
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);

        Path stateDir = prepareDirectory(activeRootfs, STATE_DIR_RELATIVE_PATH);
        if (stateDir == null) {
            return GuestAwarenessReadmeWriter.Result.UNCHANGED;
        }
        Path stateFile = stateDir.resolve("agent-seed.state");
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return GuestAwarenessReadmeWriter.Result.UNCHANGED;
        }
        Map<String, String> state = readState(stateFile);

        boolean changed = false;
        for (SeededFile file : seedFiles(docSource)) {
            String recorded = state.get(file.relativePath);
            Path target = activeRootfs.resolve(file.relativePath);
            // The parent walk never creates anything: deciding whether a file
            // is absent-by-deletion or absent-because-never-seeded must happen
            // before any directory appears, otherwise the check itself would
            // resurrect a tree the user deleted.
            ParentState parent = checkParent(activeRootfs, parentOf(file.relativePath));
            if (parent == ParentState.UNSAFE) {
                // A fixed parent segment is occupied by a symlink or a plain
                // file: never write through it. Pin the path foreign only
                // when nothing is recorded yet, so a surviving seed record
                // still applies if the real directory returns.
                if (recorded == null) {
                    state.put(file.relativePath, FOREIGN);
                    changed = true;
                }
                continue;
            }
            byte[] wanted = file.content;
            String wantedDigest = sha256Hex(wanted);
            if (parent == ParentState.SAFE
                    && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                String actual = readDigestOrNull(target);
                if (actual == null) {
                    // Unreadable: it is certainly not our unmodified copy, so
                    // never overwrite it. Pin it foreign when unrecorded, for
                    // the same reason a pre-existing user file is pinned.
                    if (recorded == null) {
                        state.put(file.relativePath, FOREIGN);
                        changed = true;
                    }
                } else if (recorded == null) {
                    state.put(file.relativePath, FOREIGN);
                    changed = true;
                } else if (!FOREIGN.equals(recorded) && actual.equals(recorded)
                        && !actual.equals(wantedDigest)) {
                    // The unmodified copy we installed: an app update may
                    // replace it with the new shipped text.
                    writeAtomically(target, wanted, SEEDED_PERMISSIONS);
                    state.put(file.relativePath, wantedDigest);
                    changed = true;
                }
                // A digest mismatch means the user edited; a FOREIGN record
                // means the path was never ours. Neither is ever overwritten.
            } else if (parent == ParentState.SAFE
                    && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                // A symlink, directory, or other non-regular file claims the
                // path: never follow or replace it. Pin it foreign only when
                // nothing is recorded yet, for the same reason as above.
                if (recorded == null) {
                    state.put(file.relativePath, FOREIGN);
                    changed = true;
                }
            } else if (recorded == null) {
                // Absent and never recorded: seed it. The create walk can only
                // return null if the tree raced to unsafe in the meantime, in
                // which case the file is left for a later run.
                if (prepareDirectory(activeRootfs, parentOf(file.relativePath)) != null) {
                    writeAtomically(target, wanted, SEEDED_PERMISSIONS);
                    state.put(file.relativePath, wantedDigest);
                    changed = true;
                }
            }
            // recorded != null with the file absent: deleted by the user —
            // keep the record so recurring starts never resurrect it.
        }

        byte[] stateText = stateContent(state, version).getBytes(StandardCharsets.UTF_8);
        String existingState = Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)
                ? readDigestOrNull(stateFile) : null;
        if (existingState == null || !existingState.equals(sha256Hex(stateText))) {
            // A missing or unreadable bookkeeping file is rewritten; the
            // atomic move replaces the path itself, so even a read-protected
            // state file cannot block the session.
            writeAtomically(stateFile, stateText, SEEDED_PERMISSIONS);
            changed = true;
        }
        return changed ? GuestAwarenessReadmeWriter.Result.UPDATED
                : GuestAwarenessReadmeWriter.Result.UNCHANGED;
    }

    /** One bundled seed file: a fixed guest-relative path plus its bytes. */
    private static final class SeededFile {
        private final String relativePath;
        private final byte[] content;

        SeededFile(String relativePath, byte[] content) {
            this.relativePath = relativePath;
            this.content = content;
        }
    }

    /**
     * The bundled seed files, read from {@code guest-docs/agents/}: asset
     * paths match the {@code .agents} tree minus the leading dot. Seeded
     * bodies carry no version; only {@code {{SKILL_NAME}}} is substituted so
     * the skill frontmatter cannot desync from {@link #SKILL_NAME}.
     */
    private static List<SeededFile> seedFiles(
            GuestAwarenessReadmeWriter.GuestDocSource source) throws IOException {
        List<SeededFile> files = new ArrayList<>();
        for (String[] entry : SEED_FILES) {
            files.add(new SeededFile(entry[0],
                    new String(source.read(entry[1]), StandardCharsets.UTF_8)
                            .replace(SKILL_NAME_TOKEN, SKILL_NAME)
                            .getBytes(StandardCharsets.UTF_8)));
        }
        return files;
    }

    /** Guest-relative parent directory of a bundled seed path. */
    private static String parentOf(String relativePath) {
        return relativePath.substring(0, relativePath.lastIndexOf('/'));
    }

    /**
     * The bookkeeping file: one record per known path - either the SHA-256 of
     * the bytes the app installed, or {@code -} for a path the user owns.
     */
    private static String stateContent(Map<String, String> state, String version) {
        StringBuilder text = new StringBuilder();
        text.append("# NusaDesk agent seed bookkeeping - app state, not config.\n");
        text.append("# Written by app version: ").append(version).append('\n');
        text.append("# '<sha256> <path>' = bytes the app installed;")
                .append(" '- <path>' = a user-owned path it must never write.\n");
        for (Map.Entry<String, String> entry : state.entrySet()) {
            text.append(entry.getValue()).append(' ').append(entry.getKey())
                    .append('\n');
        }
        return text.toString();
    }

    /**
     * Read the state file into an insertion-ordered map. A missing file is an
     * empty map; a corrupt one is rebuilt empty as well - the conservative
     * recovery records present bundled paths foreign, which can freeze stale
     * seed records but can never overwrite user bytes. Records for paths the
     * bundle no longer ships are kept verbatim: they remain truthful about
     * who owns that path.
     */
    private static Map<String, String> readState(Path stateFile) {
        Map<String, String> state = new LinkedHashMap<>();
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return state;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException notText) {
            return state;
        } catch (IOException unreadable) {
            // Unreadable bookkeeping is rebuilt empty like a corrupt one:
            // present bundled paths are then recorded foreign (nothing is
            // overwritten) instead of failing the session start.
            return new LinkedHashMap<>();
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                return new LinkedHashMap<>();
            }
            String value = line.substring(0, space);
            String path = line.substring(space + 1).trim();
            boolean digest = value.length() == SHA256_HEX_CHARS && isHex(value);
            if ((!digest && !FOREIGN.equals(value)) || path.isEmpty()
                    || path.indexOf('/') < 0 || path.startsWith("/")
                    || path.contains("..")) {
                return new LinkedHashMap<>();
            }
            state.put(path, value);
        }
        return state;
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Digest of a readable file, or {@code null} when it cannot be read.
     * Detection only: callers treat an unreadable file as "not provably
     * ours" and leave it alone.
     */
    private static String readDigestOrNull(Path file) {
        try {
            return sha256Hex(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** How a seed file's fixed parent directory stands. */
    private enum ParentState {
        /** Every segment exists and is a real directory. */
        SAFE,
        /** A segment does not exist yet; the path is free to create. */
        MISSING,
        /** A segment is a symlink or a non-directory; never write through. */
        UNSAFE
    }

    /**
     * Walk one fixed relative path <em>without creating anything</em>: the
     * answer distinguishes a missing tree (safe to create when a file is
     * actually seeded) from an occupied one (symlink or non-directory,
     * never written through). Keeping this create-free is what lets a
     * deleted {@code ~/.agents} stay deleted.
     */
    private static ParentState checkParent(Path base, String relative) {
        Path cursor = base;
        for (String segment : relative.split("/")) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    return ParentState.UNSAFE;
                }
            } else {
                return ParentState.MISSING;
            }
        }
        return ParentState.SAFE;
    }

    /**
     * Resolve one fixed relative parent, creating missing segments. Returns
     * {@code null} - instead of throwing - the moment a segment is a symlink
     * or not a real directory: an occupied agent path is a user decision to
     * leave alone, not a fault that should fail the session start.
     */
    private static Path prepareDirectory(Path base, String relative)
            throws IOException {
        Path cursor = base;
        for (String segment : relative.split("/")) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
        return cursor;
    }

    /** Stage {@code bytes} beside {@code target} and move them into place. */
    private static void writeAtomically(Path target, byte[] bytes, String permissions)
            throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            setPermissions(temporary, permissions);
            moveAtomically(temporary, target);
            setPermissions(target, permissions);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw failure;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailed) {
                fallbackFailed.addSuppressed(atomicFailed);
                throw fallbackFailed;
            }
        }
    }

    private static void setPermissions(Path file, String permissions) {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
            // The app owns the extracted rootfs; providers without POSIX mode
            // support still leave the regular file writable by the owner.
        }
    }
}
