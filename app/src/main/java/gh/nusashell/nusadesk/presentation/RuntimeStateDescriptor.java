package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Human-facing copy for the Linux system's install state.
 *
 * <p>This is presentation vocabulary, not a second state machine: lifecycle
 * transitions remain owned by the domain, and this class never renders a state
 * the host has not published. It is intentionally plain Java with no Android
 * dependency so every sentence stays unit-testable — which is also why the copy
 * lives here rather than in resources.</p>
 *
 * <p>Two rules keep the launcher honest: a state never claims that a process is
 * running when it is not, and no state describes the product as a preview or a
 * scaffold. The session has its own vocabulary in {@link SessionUiState}; a
 * surface renders one or the other, never both.</p>
 */
public final class RuntimeStateDescriptor {
    private final String label;
    private final String title;
    private final String summary;
    private final String detail;

    private RuntimeStateDescriptor(String label, String title, String summary, String detail) {
        this.label = label;
        this.title = title;
        this.summary = summary;
        this.detail = detail;
    }

    /** Returns the complete, non-empty UI copy for a domain state. */
    public static RuntimeStateDescriptor forState(RuntimeState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }

        switch (state) {
            case NOT_INSTALLED:
                return new RuntimeStateDescriptor(
                        "Not installed",
                        "Linux is not installed",
                        "No Linux system is stored on this device yet.",
                        "Setup downloads a pinned Ubuntu Base system, verifies it against a "
                                + "catalog digest, and stores it privately.");
            case DOWNLOADING:
                return new RuntimeStateDescriptor(
                        "Downloading",
                        "Downloading Linux",
                        "Fetching the verified system from the curated catalog.",
                        "The download is checked against a pinned digest before anything is "
                                + "stored as active.");
            case VERIFYING:
                return new RuntimeStateDescriptor(
                        "Verifying",
                        "Verifying the download",
                        "Checking the download against its pinned digest.",
                        "A payload that does not match exactly is rejected instead of activated.");
            case EXTRACTING:
                return new RuntimeStateDescriptor(
                        "Preparing",
                        "Preparing private files",
                        "Storing the verified system in private app storage.",
                        "The previous known-good version stays in place until the new one is "
                                + "complete.");
            case READY:
                return new RuntimeStateDescriptor(
                        "Linux installed",
                        "Linux system installed",
                        "The verified Linux system is stored privately on this device.",
                        "Start the session from the desktop to use your Linux apps.");
            case STARTING:
                return new RuntimeStateDescriptor(
                        "Starting",
                        "Starting the session",
                        "Waiting for the session to report ready.",
                        "Readiness means a healthy endpoint, not merely a running process.");
            case RUNNING:
                return new RuntimeStateDescriptor(
                        "Running",
                        "Session running",
                        "The session reported a ready loopback endpoint.",
                        "App surfaces attach only after that health check succeeds.");
            case STOPPING:
                return new RuntimeStateDescriptor(
                        "Stopping",
                        "Stopping the session",
                        "Closing the session and cleaning up its processes.",
                        "The session state stays visible so a stop is never mistaken for a crash.");
            case STOPPED:
                return new RuntimeStateDescriptor(
                        "Stopped",
                        "Session stopped",
                        "The session is closed; the Linux system stays installed.",
                        "Start the session again from the desktop when you need it.");
            case RECOVERING:
                return new RuntimeStateDescriptor(
                        "Recovering",
                        "Recovering the session",
                        "Reconciling stored state with the real process before restarting.",
                        "The host reports running only after the endpoint answers again.");
            case FAILED:
                return new RuntimeStateDescriptor(
                        "Needs attention",
                        "Linux needs attention",
                        "The last operation failed; the reason is shown below.",
                        "Nothing is claimed as running until a new session reports ready.");
            default:
                throw new AssertionError("Unhandled runtime state: " + state);
        }
    }

    public String getLabel() {
        return label;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }
}
