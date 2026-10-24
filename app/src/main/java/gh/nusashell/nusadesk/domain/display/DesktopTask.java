package gh.nusashell.nusadesk.domain.display;

/** Immutable live GUI task snapshot; a minimized app keeps its process and document. */
public final class DesktopTask {
    public enum State { STARTING, RUNNING, MINIMIZED, FAILED }
    private final LinuxDesktopApp app;
    private final State state;
    private final String detail;
    public DesktopTask(LinuxDesktopApp app, State state, String detail) {
        this.app = java.util.Objects.requireNonNull(app);
        this.state = java.util.Objects.requireNonNull(state);
        this.detail = detail == null ? "" : detail;
    }
    public LinuxDesktopApp getApp() { return app; }
    public State getState() { return state; }
    public String getDetail() { return detail; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof DesktopTask)) return false;
        DesktopTask that = (DesktopTask) other;
        return app.equals(that.app) && state == that.state && detail.equals(that.detail);
    }
    @Override public int hashCode() { return java.util.Objects.hash(app, state, detail); }
}
