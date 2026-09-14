package gh.nusashell.nusadesk.infrastructure.sshserver;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;

/**
 * Immutable result of one {@link SshBridgeServer#start} attempt.
 *
 * <p>On success the result carries the machine-readable {@link ReadinessFrame}
 * published only after the loopback bind and health check both succeeded. On
 * failure the frame is {@code null} and {@link #getDetail()} carries a
 * non-secret reason. The frame is the bridge's readiness contract with the
 * host: the future service integration forwards it to the runtime controller
 * exactly as it would forward a guest-emitted frame.</p>
 */
public final class SshBridgeStartResult {

    private final SshBridgeState state;
    private final String detail;
    private final ReadinessFrame readinessFrame;

    public SshBridgeStartResult(SshBridgeState state, String detail, ReadinessFrame readinessFrame) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = state;
        this.detail = detail == null ? "" : detail;
        this.readinessFrame = readinessFrame;
    }

    public SshBridgeState getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * @return the readiness frame, or {@code null} unless the server reached
     *         {@link SshBridgeState#RUNNING} with a confirmed healthy endpoint.
     */
    public ReadinessFrame getReadinessFrame() {
        return readinessFrame;
    }

    /** @return {@code true} when the server is running and the frame is ready. */
    public boolean isReady() {
        return state == SshBridgeState.RUNNING && readinessFrame != null && readinessFrame.isReady();
    }

    /**
     * @return the published loopback endpoint, or {@code null} on failure.
     */
    public RuntimePort getEndpoint() {
        return readinessFrame == null ? null : readinessFrame.getEndpoint();
    }
}
