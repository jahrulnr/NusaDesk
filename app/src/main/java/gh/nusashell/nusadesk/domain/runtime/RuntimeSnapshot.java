package gh.nusashell.nusadesk.domain.runtime;

/** Immutable status snapshot that can cross presentation/application boundaries. */
public final class RuntimeSnapshot {
    private final String appId;
    private final RuntimeState state;
    private final String detail;
    private final int port;
    private final long updatedAtEpochMillis;

    public RuntimeSnapshot(
            String appId,
            RuntimeState state,
            String detail,
            int port,
            long updatedAtEpochMillis) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("updatedAtEpochMillis must not be negative");
        }
        this.appId = appId;
        this.state = state;
        this.detail = detail == null ? "" : detail;
        this.port = port;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    public String getAppId() {
        return appId;
    }

    public RuntimeState getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    /** Returns zero until the owned process announces a concrete port. */
    public int getPort() {
        return port;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }
}
