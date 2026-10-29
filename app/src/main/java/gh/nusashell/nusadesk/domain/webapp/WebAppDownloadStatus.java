package gh.nusashell.nusadesk.domain.webapp;

/** Immutable progress or terminal result of one WebView GET download. */
public final class WebAppDownloadStatus {
    public enum State { QUEUED, DOWNLOADING, SAVED, FAILED }
    private final State state;
    private final String name;
    private final long bytes;
    private final long total;
    private final String detail;

    public WebAppDownloadStatus(State state, String name, long bytes, long total, String detail) {
        this.state = state;
        this.name = name;
        this.bytes = bytes;
        this.total = total;
        this.detail = detail;
    }

    public State getState() { return state; }
    public String getName() { return name; }
    public long getBytes() { return bytes; }
    public long getTotal() { return total; }
    public String getDetail() { return detail; }
}
