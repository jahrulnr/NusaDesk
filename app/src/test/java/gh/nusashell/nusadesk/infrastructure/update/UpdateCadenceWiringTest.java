package gh.nusashell.nusadesk.infrastructure.update;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the cadence wiring the 2026-09-21 report exposed as missing: a device
 * with the official 0.6.1 installed from the GitHub page — never touched by
 * adb — never saw the 0.6.2 banner, because every launch inside the floor was
 * silently throttled and nothing re-asked while the app stayed open. Two
 * behaviours must not disappear again: a fresh process asks once whatever the
 * store says (a reboot, a force-stop, a fresh launch), and a foreground poll
 * keeps re-asking while the app is open.
 *
 * <p>A wiring pin, not a substitute for the device run: both paths are
 * measured on a device with readable preferences, and this only keeps the
 * calls from disappearing.</p>
 */
public class UpdateCadenceWiringTest {

    @Test
    public void aFreshProcessAsksWithoutWaitingForTheFloor() throws Exception {
        String activity = read(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");

        assertTrue("the coordinator must keep a per-process flag",
                activity.contains("processStartCheckConsumed"));
        assertTrue("the flag must reach the cadence rule",
                activity.contains(
                        "isDue(System.currentTimeMillis(), installedVersion, freshProcess)"));
    }

    @Test
    public void aForegroundPollKeepsAskingWhileTheAppIsOpen() throws Exception {
        String activity = read(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");

        assertTrue("onStart must arm the poll", activity.contains("scheduleUpdatePoll();"));
        assertTrue("onStop must drop it",
                activity.contains("mainHandler.removeCallbacks(updatePoll)"));
        assertTrue("the tick must re-post itself",
                activity.contains("mainHandler.postDelayed(this, UPDATE_POLL_TICK_MILLIS)"));
    }

    private static String read(String relative) throws Exception {
        String[] candidates = {relative, "../" + relative};
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing file: " + relative);
    }
}
