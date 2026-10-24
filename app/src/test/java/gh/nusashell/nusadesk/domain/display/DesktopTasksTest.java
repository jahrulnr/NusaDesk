package gh.nusashell.nusadesk.domain.display;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopTasksTest {
    private static LinuxDesktopApp app(String id) { return new LinuxDesktopApp(id + ".desktop", id, "/usr/share/applications/" + id + ".desktop", null); }
    @Test public void openingAnotherAppPreservesTheFirstAndResumeDoesNotRestartIt() {
        DesktopTasks tasks = new DesktopTasks();
        tasks.select(app("editor")); tasks.ready("editor.desktop"); tasks.select(app("settings"));
        assertEquals(2, tasks.snapshot().size());
        assertEquals(DesktopTask.State.MINIMIZED, tasks.snapshot().get(0).getState());
        assertEquals(DesktopTask.State.STARTING, tasks.selected().getState());
        tasks.ready("settings.desktop"); tasks.select(app("editor"));
        assertEquals(DesktopTask.State.RUNNING, tasks.selected().getState());
        assertEquals(DesktopTask.State.MINIMIZED, tasks.snapshot().get(1).getState());
    }
    @Test public void minimizingWhileStartingDoesNotPretendTheAppIsReady() {
        DesktopTasks tasks = new DesktopTasks(); tasks.select(app("editor")); tasks.minimize();
        assertNull(tasks.selected()); assertEquals(DesktopTask.State.STARTING, tasks.snapshot().get(0).getState());
        tasks.ready("editor.desktop"); assertEquals(DesktopTask.State.MINIMIZED, tasks.snapshot().get(0).getState());
    }
    @Test public void exitAndSessionStopRemoveTheirTasks() {
        DesktopTasks tasks = new DesktopTasks(); tasks.select(app("editor")); tasks.select(app("settings"));
        tasks.remove("editor.desktop"); assertEquals("settings.desktop", tasks.selectedId());
        tasks.remove("settings.desktop"); assertNull(tasks.selected()); assertTrue(tasks.snapshot().isEmpty());
        tasks.select(app("editor")); tasks.clear(); assertTrue(tasks.snapshot().isEmpty());
    }
    @Test public void failureCanBeRetriedAndSnapshotsDoNotChangeUnderTheView() {
        DesktopTasks tasks = new DesktopTasks(); tasks.select(app("editor"));
        java.util.List<DesktopTask> old = tasks.snapshot(); tasks.fail("editor.desktop", "closed before mapping");
        assertEquals(DesktopTask.State.STARTING, old.get(0).getState());
        assertEquals(DesktopTask.State.FAILED, tasks.selected().getState());
        tasks.select(app("editor")); assertEquals(DesktopTask.State.STARTING, tasks.selected().getState());
        assertThrows(UnsupportedOperationException.class, () -> old.clear());
    }
    @Test public void aLateReadyMarkerCannotResurrectAFailedTask() {
        DesktopTasks tasks = new DesktopTasks(); tasks.select(app("editor"));
        tasks.fail("editor.desktop", "closed before mapping");
        tasks.ready("editor.desktop");
        assertEquals(DesktopTask.State.FAILED, tasks.selected().getState());
        assertEquals("closed before mapping", tasks.selected().getDetail());
        // Retry still works: selecting the app again resets it to STARTING and ready applies.
        tasks.select(app("editor")); tasks.ready("editor.desktop");
        assertEquals(DesktopTask.State.RUNNING, tasks.selected().getState());
    }
}
