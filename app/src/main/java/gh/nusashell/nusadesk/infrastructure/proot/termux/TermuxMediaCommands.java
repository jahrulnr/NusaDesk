package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The media-player Termux-compat command (wave 2, W2g):
 * {@code termux-media-player}.
 *
 * <p>A thin flag mapping onto {@code mediaplayer.*}: {@code play <file>}
 * stages the file into guest {@code /tmp} and passes that guest path per the
 * staging rule (contract §8.1), with the file's basename as the display name;
 * {@code play} with no argument resumes; {@code info}, {@code pause}, and
 * {@code stop} take no arguments. Upstream's texts arrive in the {@code
 * message} response field and are printed verbatim; the playback itself runs
 * in the host's mediaPlayback foreground service.</p>
 */
public final class TermuxMediaCommands {

    private TermuxMediaCommands() {
    }

    /** The media domain commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(
                mediaPlayer()));
    }

    private static TermuxCommand mediaPlayer() {
        return new TermuxCommand(
                "termux-media-player",
                String.join("\n",
                        "import os",
                        "import shutil",
                        "import tempfile",
                        "",
                        "USAGE = ('Usage: termux-media-player cmd [args]'",
                        "         + '\\n'",
                        "         + '\\nhelp        Shows this help'",
                        "         + '\\ninfo        Displays current playback information'",
                        "         + '\\nplay        Resumes playback if paused'",
                        "         + '\\nplay <file> Plays specified media file'",
                        "         + '\\npause       Pauses playback'",
                        "         + '\\nstop        Quits playback')",
                        "",
                        "",
                        "def play_file(path):",
                        "    # Staging rule: the guest copies the file into rootfs",
                        "    # /tmp and passes that guest path; the host resolves",
                        "    # it there. The staged copy is removed on every path.",
                        "    source = os.path.realpath(path)",
                        "    fd, staging = tempfile.mkstemp(prefix='nusadesk-media-',",
                        "                                 dir='/tmp')",
                        "    try:",
                        "        with os.fdopen(fd, 'wb') as out:",
                        "            with open(source, 'rb') as handle:",
                        "                shutil.copyfileobj(handle, out)",
                        "        return tc.bridge_call('mediaplayer.play',",
                        "                              {'path': staging,",
                        "                               'name': os.path.basename(source)})",
                        "    finally:",
                        "        try:",
                        "            os.unlink(staging)",
                        "        except OSError:",
                        "            pass",
                        "",
                        "",
                        "def main(argv):",
                        "    if not argv or argv[0] in ('help', '-h', '--help'):",
                        "        print(USAGE)",
                        "        return 0",
                        "    command = argv[0]",
                        "    if command == 'play':",
                        "        if len(argv) > 2:",
                        "            print('Error! termux-media-player can only play'",
                        "                  + ' one file at a time!')",
                        "            return 1",
                        "        if len(argv) == 2:",
                        "            if not os.path.isfile(argv[1]):",
                        "                print(\"Error: '\" + argv[1] + \"' is not a file!\")",
                        "                return 1",
                        "            reading = play_file(argv[1])",
                        "        else:",
                        "            reading = tc.bridge_call('mediaplayer.play')",
                        "        print(reading.get('message', ''))",
                        "        return 0",
                        "    if command in ('info', 'pause', 'stop'):",
                        "        if len(argv) > 1:",
                        "            print(\"Error! '\" + command",
                        "                  + \"' takes no arguments!\")",
                        "            return 1",
                        "        reading = tc.bridge_call('mediaplayer.' + command)",
                        "        print(reading.get('message', ''))",
                        "        return 0",
                        "    print(\"termux-media-player: Invalid cmd: '\"",
                        "          + command + \"'\")",
                        "    print(USAGE)",
                        "    return 1",
                        ""),
                Arrays.asList(
                        "- `termux-media-player play [file]` - plays a media",
                        "  file through the host's mediaPlayback foreground",
                        "  service (the file is staged through guest `/tmp`);",
                        "  bare `play` resumes a paused track. `pause`,",
                        "  `stop`, and `info` take no arguments and print",
                        "  upstream's status text (`info` adds `state`,",
                        "  `track`, `position_ms`, `duration_ms` fields to",
                        "  the bridge response).",
                        "- Errors are typed: `mediaplayer-unavailable`,",
                        "  `mediaplayer-play-failed`, `foreground-required`,",
                        "  `invalid-argument`; upstream keeps exit 0 on",
                        "  platform errors, this port reports them on exit 1."));
    }
}
