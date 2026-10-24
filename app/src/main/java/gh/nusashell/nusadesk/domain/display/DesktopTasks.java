package gh.nusashell.nusadesk.domain.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One foreground GUI task among retained applications. No process or Android
 * ownership; the owning session confines mutation to the main thread.
 */
public final class DesktopTasks {
    private final Map<String, DesktopTask> tasks = new LinkedHashMap<>();
    private String selected;
    public void select(LinuxDesktopApp app) {
        minimize();
        selected = app.getId();
        DesktopTask old = tasks.get(selected);
        DesktopTask.State state = old == null || old.getState() == DesktopTask.State.FAILED
                ? DesktopTask.State.STARTING : old.getState() == DesktopTask.State.MINIMIZED
                ? DesktopTask.State.RUNNING : old.getState();
        tasks.put(selected, new DesktopTask(app, state, ""));
    }
    public void minimize() {
        DesktopTask old = selected();
        if (old != null && old.getState() == DesktopTask.State.RUNNING) {
            tasks.put(selected, new DesktopTask(old.getApp(), DesktopTask.State.MINIMIZED, ""));
        }
        selected = null;
    }
    public void ready(String id) {
        DesktopTask old = tasks.get(id);
        // A late ready marker must not resurrect a FAILED task.
        if (old != null && old.getState() != DesktopTask.State.FAILED) {
            tasks.put(id, new DesktopTask(old.getApp(), id.equals(selected)
                    ? DesktopTask.State.RUNNING : DesktopTask.State.MINIMIZED, ""));
        }
    }
    public void fail(String id, String detail) {
        DesktopTask old = tasks.get(id);
        if (old != null) tasks.put(id, new DesktopTask(old.getApp(), DesktopTask.State.FAILED, detail));
    }
    public void remove(String id) { tasks.remove(id); if (id.equals(selected)) selected = null; }
    public DesktopTask selected() { return tasks.get(selected); }
    public String selectedId() { return selected; }
    public List<DesktopTask> snapshot() { return Collections.unmodifiableList(new ArrayList<>(tasks.values())); }
    public void clear() { tasks.clear(); selected = null; }
}
