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

    private GuestAgentSeedWriter() {
    }

    /** Guest-relative paths of every file in the seeded bundle, in order. */
    public static List<String> seededRelativePaths() {
        List<String> paths = new ArrayList<>();
        for (SeededFile file : seedFiles()) {
            paths.add(file.relativePath);
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
     * @return whether any file or the bookkeeping record was written
     * @throws IOException on a real write/read failure; unsafe fixed paths
     *         skip the affected entry instead of throwing
     */
    public static GuestAwarenessReadmeWriter.Result ensure(Path activeRootfs,
            String appVersion) throws IOException {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
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
        for (SeededFile file : seedFiles()) {
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
            byte[] wanted = file.content.getBytes(StandardCharsets.UTF_8);
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

    /**
     * The exact {@code /root/.agents/AGENTS.md} text. Public so tests and
     * documentation tooling can pin the shipped content.
     */
    public static String agentsMdContent() {
        return String.join("\n",
                "# NusaDesk guest - global agent instructions",
                "",
                "> Seeded by the NusaDesk Android app. Unlike the generated files",
                "> at `/root/README.md` and `/root/docs/`, this file and the",
                "> whole `/root/.agents/` tree belong to the user: the app",
                "> updates a seeded file only while it is byte-identical to",
                "> what it installed and never recreates one that was deleted.",
                "> Edit freely.",
                "",
                "You are inside the NusaDesk guest: an Ubuntu Base ARM64 userland",
                "running under PRoot on an Android device. That is a",
                "compatibility layer, not a VM and not a container with its own",
                "kernel. `root` here is guest root - every process still runs as",
                "the Android app's uid inside the app's SELinux domain.",
                "",
                "## Environment facts",
                "",
                "- `/tmp` is wiped at every session start and `/run` holds",
                "  per-session state (the bridge env, service markers). Nothing",
                "  durable belongs in either.",
                "- `/root/README.md` and `/root/docs/*.md` are app-generated and",
                "  rewritten on session start - read them, never edit them.",
                "- `systemctl` and `service` are the vendored systemctl3 bridge,",
                "  not systemd. Only units enabled when `systemctl init` ran are",
                "  restart-supervised, and `systemctl stop` on an enabled unit",
                "  does not survive a session restart.",
                "- Containers run through `udocker compose` only, and published",
                "  `ports:` are rejected - services stay inside the guest.",
                "- Guest storage (`/root`, `/home`, `/opt`, `/usr/local`, `/etc`,",
                "  `/var/lib`) persists across sessions and is covered by the",
                "  app's guest backup.",
                "- The user's shared Android workspace, when they picked one, is",
                "  bound at `/root/nusadesk` - real host storage visible to",
                "  Android.",
                "- The guest SSH endpoint is fixed at `127.0.0.1:22022`.",
                "",
                "## Reaching Android",
                "",
                "Two sanctioned doors - pick by what the task needs. The skill",
                "`skills/termux-api-vs-android-cli/SKILL.md` has the full",
                "decision.",
                "",
                "- **`termux-*` commands (optional capability clients) and",
                "  `nusadesk-*` commands (native NusaDesk capability clients).**",
                "  Use installed bridge commands for Android features: notifications,",
                "  SMS/contacts/call log, telephony, calendar, location,",
                "  sensors, battery, clipboard, dialogs, speech, camera and",
                "  microphone capture, Bluetooth, Wi-Fi extras, installed",
                "  packages, usage stats, overlays, USB pass-through, and LAN",
                "  file serving. Prefer these: grants and failures come back as",
                "  typed errors, output is documented JSON, and the bridge only",
                "  answers a fixed method allowlist.",
                "- **`android-cli` (native tools).** Runs the device's own",
                "  binaries as the app uid: `getprop`, `toolbox`/`toybox`",
                "  applets, `sh`, `exec <path>`, and `su` on rooted devices,",
                "  plus `status`, `doctor`, and `permissions`. Use it for device",
                "  state or utilities the bridge does not cover. `/system/bin`",
                "  is deliberately not on `PATH` - go through `android-cli`.",
                "- The real Termux:API app can never serve this guest - it is",
                "  locked to the Termux app uid and signature. If the user",
                "  installed optional Termux commands under System > One-click",
                "  install, these `termux-*` clients use NusaDesk's bridge.",
                "  Likewise, `nusadesk-usb` and guest `adb` need the independent",
                "  USB / ADB choice; neither command set is presumed present.",
                "- A platform denial is an honest boundary, not a bug: an app",
                "  uid cannot run `pm`, `am`, `dumpsys`, `screencap`, and",
                "  friends. The higher tiers - the device's own `adbd` once the",
                "  user enables `adb tcpip`, or `su` on a rooted device - are",
                "  the user's own enablement decision, not something to work",
                "  around.",
                "",
                "## House rules for agents",
                "",
                "- Never print, log, or copy the session token in",
                "  `/run/nusadesk/android-bridge.env`; the generated CLIs read",
                "  it themselves.",
                "- Do not hand-roll bridge calls with invented methods or",
                "  params - use the installed CLIs.",
                "- Do not kill `sshd`, `lw-session-supervisor`, or `systemctl",
                "  init` - they are the session you are running inside.",
                "- Guest root is still an app uid: no mounts, no raw devices,",
                "  no kernel changes. Test a capability before relying on it.",
                "- To detach the seeded files from future updates permanently,",
                "  delete `/var/lib/nusadesk/agent-seed.state`; to get a fresh",
                "  seed instead, delete the seeded files together with that",
                "  state file.",
                "",
                "") + "\n";
    }

    /**
     * The exact {@code skills/termux-api-vs-android-cli/SKILL.md} text.
     * Public so tests and documentation tooling can pin the shipped content.
     */
    public static String skillContent() {
        return String.join("\n",
                "---",
                "name: " + SKILL_NAME,
                "description: Choose between the NusaDesk capability-bridge",
                "  commands (`termux-*`/`nusadesk-*`) and the native",
                "  `android-cli` tools - or the higher adb-shell/root tiers -",
                "  whenever a guest task touches an Android capability, device",
                "  state, or a device binary.",
                "---",
                "",
                "# Termux API vs android-cli",
                "",
                "The guest has two sanctioned doors into Android, and they are",
                "not interchangeable: the bridge is a typed, permission-aware",
                "API while `android-cli` runs the device's own binaries under",
                "the app's uid. Decide in this order.",
                "",
                "## 1. A bridge command covers it: use it",
                "",
                "If an installed `termux-*` or `nusadesk-*` command covers the",
                "task, prefer it. `termux-*` and `nusadesk-usb`/guest `adb`",
                "are separate opt-in toolkits: check `command -v` before",
                "using them; only the user enables a missing one from System",
                "> One-click install. Never enable it silently as a workaround.",
                "Bridge calls return typed errors",
                "(`<capability>-permission-required`, `-permission-denied`,",
                "`-unavailable`, `-busy`, `-timeout`, `invalid-argument`),",
                "documented JSON output, bounded reads, and stable exit codes",
                "(`0` result, `1` typed error, `2` usage or transport).",
                "",
                "- `termux-*` - optional Termux:API client surface, command- and",
                "  JSON-compatible with Termux: battery, location, sensors,",
                "  SMS list/send, contacts, call log, telephony,",
                "  notifications, clipboard, toast and dialogs, TTS and",
                "  speech-to-text, media playback, camera info/photo,",
                "  microphone record, NFC, infrared, fingerprint, torch,",
                "  vibrate, volume, brightness, wallpaper, wifi info/scan,",
                "  downloads, keystore, job scheduler, SAF storage, share,",
                "  USB. The installed set and its exact field subsets are in",
                "  `/root/docs/termux-compat.md`.",
                "- `nusadesk-android` - live camera/mic media sessions, the",
                "  bounded calendar (list/add/update/delete), media-player",
                "  routing, `bridge info`.",
                "- `nusadesk-bt` - Bluetooth adapter state, pairing, BLE, GATT,",
                "  RFCOMM, the bounded keyboard/mouse HID role, headset voice",
                "  recognition.",
                "- `nusadesk-wifi`, `nusadesk-pkg`, `nusadesk-usage`,",
                "  `nusadesk-overlay`, `nusadesk-loc` - wifi hotspot/",
                "  suggestions/locks, package list/info/launch, usage stats,",
                "  overlay plates, background location.",
                "- `nusadesk-serve` + `nusadesk-net` - serve the workspace over",
                "  HTTP on the LAN and find the address to hand out.",
                "- `nusadesk-usb` + the wrapped `adb` (optional USB/ADB choice)",
                "  - USB pass-through; the stock guest `adb` sees devices",
                "  attached to the phone's USB port.",
                "",
                "A missing grant is a typed result, not a crash. The command",
                "that needs the grant names it; `android-cli permissions`",
                "lists every declared grant's state plus the Settings action",
                "that opens it. Grants are the user's own decision - a denied",
                "one stays denied; do not retry-loop or work around it.",
                "",
                "## 2. A device binary or device state: android-cli",
                "",
                "When the need is the device's own tooling or state rather",
                "than a capability API, use `android-cli`:",
                "",
                "- `android-cli getprop [name]`, `toybox <applet>`, `toolbox",
                "  `<applet>`, `sh` - the phone's own binaries run natively as",
                "  the app uid;",
                "- `android-cli exec <absolute-path> [args...]` - any binary",
                "  under the bound trees, no verb allowlist;",
                "- `android-cli status` / `doctor` - report the tier and probe",
                "  what exists; `android-cli permissions` is the one",
                "  bridge-backed verb (declared grant states).",
                "",
                "Every call prints an `android-cli: tier=… source=native",
                "device=…` line on stderr (quiet with `-q`) and propagates the",
                "child's exit code. `/system/bin`",
                "is never on the guest `PATH` - it would shadow the Ubuntu",
                "userland - so reach it through `android-cli`, not by adding",
                "it to `PATH` or symlinking binaries.",
                "",
                "## 3. The platform denies it: a higher tier, not a workaround",
                "",
                "Power comes from the uid, not the binary. `pm`, `am`,",
                "`dumpsys`, `screencap`, `input`, `settings put` and similar",
                "are denied to an app uid by the platform's own checks - the",
                "child's own error passes through verbatim with an",
                "`android-cli: hint:` line. The honest answers are:",
                "",
                "- the **shell tier**: the device's own `adbd` over loopback,",
                "  once the user enables `adb tcpip` on the device - their",
                "  enablement decision, not wired by default;",
                "- the **root tier**: `android-cli su` on a rooted device - one",
                "  Magisk prompt per grant.",
                "",
                "Never pretend a denied verb ran, never silently retry, and",
                "never claim guest root is device root.",
                "",
                "## Never",
                "",
                "- Do not contact the real Termux:API app: it is",
                "  signature-locked to the Termux uid and rejects any other",
                "  caller. The `termux-*` names here are served by NusaDesk's",
                "  bridge.",
                "- Do not call the bridge socket directly with an invented",
                "  method or params - the allowlist is fixed; use the",
                "  installed CLIs.",
                "- Do not read or print the session token in",
                "  `/run/nusadesk/android-bridge.env`.",
                "- Do not add `/system/bin` to `PATH`.",
                "",
                "## Exit codes",
                "",
                "- `termux-*`/`nusadesk-*`: `0` result, `1` typed bridge error,",
                "  `2` usage or unreachable bridge.",
                "- `android-cli`: the child's own exit code; `2` usage, `5`",
                "  tool absent (or device `su` missing on a non-rooted phone).",
                "",
                "") + "\n";
    }

    /** One bundled seed file: a fixed guest-relative path plus its text. */
    private static final class SeededFile {
        private final String relativePath;
        private final String content;

        SeededFile(String relativePath, String content) {
            this.relativePath = relativePath;
            this.content = content;
        }
    }

    private static List<SeededFile> seedFiles() {
        List<SeededFile> files = new ArrayList<>();
        files.add(new SeededFile(GUEST_AGENTS_MD_RELATIVE_PATH, agentsMdContent()));
        files.add(new SeededFile(SKILL_FILE_RELATIVE_PATH, skillContent()));
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
