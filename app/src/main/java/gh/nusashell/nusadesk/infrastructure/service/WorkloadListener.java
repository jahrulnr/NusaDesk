package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.session.ReadinessFrame;

/**
 * Callback the host registers with a {@link RuntimeWorkload}.
 *
 * <p>The workload reports readiness frames and terminal outcomes here. The
 * host validates a readiness frame before transitioning to {@code RUNNING}, so
 * a workload cannot force a running state by merely claiming it. This carries
 * no Android types.
 */
public interface WorkloadListener {

    /** No-op listener for teardown-only calls where the host already knows the outcome. */
    WorkloadListener NONE = new WorkloadListener() {
        @Override
        public void onReadiness(ReadinessFrame frame) {
        }

        @Override
        public void onStopped() {
        }

        @Override
        public void onFailed(String reason) {
        }
    };

    /** Guest emitted a readiness frame; the host decides whether it authorises {@code RUNNING}. */
    void onReadiness(ReadinessFrame frame);

    /** Guest stopped cleanly. */
    void onStopped();

    /** Guest failed; {@code reason} should be non-blank. */
    void onFailed(String reason);
}
