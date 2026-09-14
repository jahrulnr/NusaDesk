package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.session.SessionSnapshot;

/**
 * Port implemented by the runtime supervisor (the packaged PRoot + loopback
 * SSH bridge) and consumed by {@link RuntimeHostService}.
 *
 * <p>The host never starts arbitrary code. It only delegates start/stop to the
 * single workload registered in {@link RuntimeWorkloadRegistry}. While no
 * workload is registered the service refuses to start a runtime and surfaces a
 * typed failure instead.
 *
 * <p>This contract lives in the infrastructure layer because the host shell
 * and the supervisor are both infrastructure components. It carries no Android
 * types so it can be exercised by plain unit tests.
 */
public interface RuntimeWorkload {

    /** Stable identifier of this workload, for example {@code "ubuntu-base-arm64/ssh"}. */
    String workloadId();

    /**
     * Begin the guest process and report state through the listener. Must be
     * idempotent for the registered workload and must not block the caller
     * indefinitely; readiness is delivered asynchronously via the listener.
     *
     * @param session the {@code STARTING} snapshot issued by the host for this
     *                attempt; the workload echoes its app id, version, and
     *                session id back in the readiness frame so the host can
     *                verify identity before accepting {@code RUNNING}
     */
    void start(SessionSnapshot session, WorkloadListener listener);

    /** Stop the guest process gracefully, reporting the outcome via the listener. */
    void stop(WorkloadListener listener);
}
