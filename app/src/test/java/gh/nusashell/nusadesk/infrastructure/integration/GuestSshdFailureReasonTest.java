package gh.nusashell.nusadesk.infrastructure.integration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshdStderrMonitor;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestSshdFailureReasonTest {
    @Test public void tracerAssertionSurvivesIntoTheFailureShownByTheUi() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c",
                "printf '%s\\n' 'path.c:453: detranslate_path: Assertion binding_referrer failed' >&2; exit 1").start();
        CountDownLatch closed = new CountDownLatch(1);
        GuestSshdStderrMonitor monitor = GuestSshdStderrMonitor.forProcess(process, closed::countDown);
        monitor.start();
        assertTrue(closed.await(4, TimeUnit.SECONDS));
        String reason = GuestSshdWorkload.unexpectedExitReason(process, "OpenSSH sshd", monitor);
        assertTrue(reason.contains("exit code 1"));
        assertTrue(reason.contains("detranslate_path: Assertion binding_referrer failed"));
    }
}
