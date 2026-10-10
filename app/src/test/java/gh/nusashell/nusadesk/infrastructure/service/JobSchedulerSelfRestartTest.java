package gh.nusashell.nusadesk.infrastructure.service;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The own-uid process discovery behind the self-restart tree kill
 * (ADR-0063): only processes whose {@code /proc/<pid>/status} Uid field is
 * the app's own uid are swept, the caller's own pid is always excluded (it is
 * killed separately, last), and anything unreadable or foreign is skipped so
 * a signal can never reach a process the app does not own.
 */
public class JobSchedulerSelfRestartTest {

    private static final long SELF_UID = 10_420L;
    private static final long SELF_PID = 3_171L;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void ownProcessPidsKeepsOwnUidExceptSelf() throws IOException {
        Path proc = folder.newFolder("proc").toPath();
        writeProcEntry(proc, 3_464, SELF_UID);   // a tracer child of the app
        writeProcEntry(proc, 3_470, SELF_UID);   // a tracee that setsid'd away
        writeProcEntry(proc, SELF_PID, SELF_UID); // the app itself — excluded
        writeProcEntry(proc, 900, 1_000L);        // someone else's process

        List<Long> pids = new ArrayList<>(
                JobSchedulerSelfRestart.ownProcessPids(proc, SELF_UID, SELF_PID));
        // DirectoryStream order is unspecified; the kill does not care.
        Collections.sort(pids);

        assertEquals(Arrays.asList(3_464L, 3_470L), pids);
    }

    @Test
    public void ownProcessPidsSkipsUnreadableAndNonNumericEntries() throws IOException {
        Path proc = folder.newFolder("proc").toPath();
        writeProcEntry(proc, 3_464, SELF_UID);
        Files.createDirectories(proc.resolve("self"));     // not a pid
        Files.createDirectories(proc.resolve("400"));      // gone: no status
        Path garbled = Files.createDirectories(proc.resolve("401"));
        Files.write(garbled.resolve("status"),
                "Name:\tproc\nState:\tS\nUid:\tnot-a-number\n"
                        .getBytes(StandardCharsets.US_ASCII));

        assertEquals(Collections.singletonList(3_464L),
                JobSchedulerSelfRestart.ownProcessPids(proc, SELF_UID, SELF_PID));
    }

    /**
     * Writes a fake {@code /proc/<pid>} entry carrying the kernel's
     * {@code status} layout — the discovery reads only the first
     * {@code Uid:} field.
     */
    private static void writeProcEntry(Path proc, long pid, long uid)
            throws IOException {
        Path dir = Files.createDirectories(proc.resolve(Long.toString(pid)));
        Files.write(dir.resolve("status"),
                ("Name:\tlibproot.so\nState:\tt (tracing stop)\nPid:\t" + pid
                        + "\nUid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid
                        + "\n").getBytes(StandardCharsets.US_ASCII));
    }
}
