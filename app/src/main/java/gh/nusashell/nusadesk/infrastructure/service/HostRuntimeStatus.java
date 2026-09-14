package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import java.util.Objects;

/**
 * Immutable snapshot the host service renders into a foreground notification.
 *
 * <p>Wraps the optional {@link SessionSnapshot} with the workload registration
 * flag so the notification policy can distinguish "service foreground, no
 * runtime available" from "service foreground, runtime running". A foreground
 * service does <em>not</em> imply a running runtime; use {@link #isRuntimeRunning()}
 * which requires both {@code RUNNING} state and a concrete loopback endpoint.
 *
 * <p>Carries no Android types so it can be asserted in plain unit tests.
 */
public final class HostRuntimeStatus {

    private final SessionSnapshot snapshot;
    private final boolean workloadRegistered;
    private final String workloadId;

    public HostRuntimeStatus(SessionSnapshot snapshot, boolean workloadRegistered, String workloadId) {
        this.snapshot = snapshot;
        this.workloadRegistered = workloadRegistered;
        this.workloadId = workloadId == null ? "" : workloadId;
    }

    /** Returns the session snapshot, or {@code null} before any session has been started. */
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    /** Returns the session state, or {@code NOT_STARTED} when no session exists yet. */
    public SessionState getState() {
        return snapshot == null ? SessionState.NOT_STARTED : snapshot.getState();
    }

    /** Returns the loopback endpoint, or {@code null} until a readiness frame is accepted. */
    public RuntimePort getEndpoint() {
        return snapshot == null ? null : snapshot.getEndpoint();
    }

    public boolean isWorkloadRegistered() {
        return workloadRegistered;
    }

    public String getWorkloadId() {
        return workloadId;
    }

    public String getFailureReason() {
        return snapshot == null ? "" : snapshot.getFailureReason();
    }

    /**
     * True only when the session is {@code RUNNING} and a concrete loopback
     * endpoint exists. This is the single honest signal that a runtime is up;
     * the service being foreground is not sufficient.
     */
    public boolean isRuntimeRunning() {
        return snapshot != null
                && snapshot.getState() == SessionState.RUNNING
                && snapshot.getEndpoint() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HostRuntimeStatus)) {
            return false;
        }
        HostRuntimeStatus that = (HostRuntimeStatus) o;
        return workloadRegistered == that.workloadRegistered
                && Objects.equals(snapshot, that.snapshot)
                && Objects.equals(workloadId, that.workloadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshot, workloadRegistered, workloadId);
    }
}
