package gh.nusashell.nusadesk.application.display;

/** One selected guest GUI, independent of Android views and process implementation. */
public interface NativeDesktopPort {
    /** Guest-local X11 endpoint; its presence in an environment is not a readiness verdict. */
    String X11_DISPLAY = ":1";
    enum State { APP_CLOSED, STOPPED, STARTING, RUNNING, FAILED, MISSING_COMPONENTS }
    interface Listener {
        /** A real guest protocol probe determines readiness; detail explains a non-ready state. */
        void onStatus(State state, String detail);
    }
    void open();
    void subscribe(Listener listener);
    void unsubscribe(Listener listener);
}
