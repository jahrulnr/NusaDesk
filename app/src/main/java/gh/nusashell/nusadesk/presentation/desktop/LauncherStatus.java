package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;

/**
 * The launcher's readiness indicator: one honest sentence about whether Linux is
 * usable, and nothing to press.
 *
 * <p>Linux is background infrastructure (ADR-0013): it starts from an Activity
 * foreground event, so the launcher must never offer a start or stop control.
 * What it can do is state where the runtime actually is, folding the three
 * published truths into one answer so they cannot contradict each other:</p>
 *
 * <ol>
 *   <li>the curated system is not installed yet, or the guest terminal
 *       component is missing — that is setup, and setup is what to do next;</li>
 *   <li>otherwise the Android-owned session state decides, and a stopped or
 *       failed session says that Linux starts again on the next app launch
 *       instead of offering a button that would only duplicate the automatic
 *       path.</li>
 * </ol>
 *
 * <p>Pure presentation vocabulary: it maps published states to copy and colour,
 * records whether a state deserves launcher attention, never invents a state,
 * and carries no action.</p>
 */
public final class LauncherStatus {

    /** Coarse grouping used for copy and colour. */
    public enum Kind { SETUP, STARTING, READY, STOPPING, STOPPED, FAILED }

    private final Kind kind;
    private final int labelRes;
    private final int detailRes;
    private final String failureReason;

    private LauncherStatus(Kind kind, int labelRes, int detailRes, String failureReason) {
        this.kind = kind;
        this.labelRes = labelRes;
        this.detailRes = detailRes;
        this.failureReason = failureReason;
    }

    /**
     * Folds install, service, and session truth into the one thing the launcher
     * says about Linux.
     *
     * @param runtime the curated system install snapshot; {@code null} means
     *                nothing is installed yet
     * @param service the guest terminal component state; {@code null} means
     *                missing
     * @param session the published session status; {@code null} means no session
     *                has been published in this process
     */
    public static LauncherStatus of(
            RuntimeSnapshot runtime, GuestSshUiState service, HostRuntimeStatus session) {
        RuntimeState install = runtime == null ? RuntimeState.NOT_INSTALLED : runtime.getState();
        if (install != RuntimeState.READY) {
            return new LauncherStatus(Kind.SETUP, R.string.launcher_status_setup,
                    R.string.launcher_status_setup_system, null);
        }
        GuestSshUiState.Kind serviceKind = service == null
                ? GuestSshUiState.Kind.MISSING : service.getKind();
        if (serviceKind != GuestSshUiState.Kind.INSTALLED) {
            return new LauncherStatus(Kind.SETUP, R.string.launcher_status_setup,
                    detailForService(serviceKind), null);
        }
        SessionState state = session == null ? SessionState.NOT_STARTED : session.getState();
        switch (state) {
            case RUNNING:
            case RECONNECTING:
                return new LauncherStatus(Kind.READY, R.string.launcher_status_ready,
                        R.string.launcher_status_ready_detail, null);
            case STARTING:
            case RECOVERING:
                return new LauncherStatus(Kind.STARTING, R.string.launcher_status_starting,
                        R.string.launcher_status_starting_detail, null);
            case STOPPING:
                return new LauncherStatus(Kind.STOPPING, R.string.launcher_status_stopping,
                        R.string.launcher_status_stopping_detail, null);
            case FAILED:
                return new LauncherStatus(Kind.FAILED, R.string.launcher_status_failed,
                        R.string.launcher_status_failed_detail, normalizeReason(session));
            default:
                return new LauncherStatus(Kind.STOPPED, R.string.launcher_status_stopped,
                        R.string.launcher_status_stopped_detail, null);
        }
    }

    private static int detailForService(GuestSshUiState.Kind serviceKind) {
        switch (serviceKind) {
            case INSTALLING:
                return R.string.launcher_status_setup_installing;
            case FAILED:
                return R.string.launcher_status_setup_service_failed;
            default:
                return R.string.launcher_status_setup_service;
        }
    }

    /** A blank host reason is treated as absent so the UI never prints nothing. */
    private static String normalizeReason(HostRuntimeStatus session) {
        String reason = session == null ? null : session.getFailureReason();
        return reason == null || reason.trim().isEmpty() ? null : reason;
    }

    public Kind getKind() {
        return kind;
    }

    /** Short label for the launcher header pill. */
    public int getLabelRes() {
        return labelRes;
    }

    /** One-sentence explanation of the label. */
    public int getDetailRes() {
        return detailRes;
    }

    /** Non-secret failure reason, or {@code null} when Linux did not fail. */
    public String getFailureReason() {
        return failureReason;
    }

    /** True only when Linux is up and usable. */
    public boolean isReady() {
        return kind == Kind.READY;
    }

    /**
     * True when this status deserves a launcher pill. Normal readiness is the
     * absence of status chrome; setup has its own installer surface, while
     * session transitions, stopped, and failed states need an explicit notice.
     */
    public boolean isVisibleInLauncher() {
        return kind != Kind.SETUP && kind != Kind.READY;
    }

    /** Semantic tint resource for the pill. */
    public int getBackgroundColorRes() {
        switch (kind) {
            case READY:
                return R.color.success_tint;
            case STARTING:
            case STOPPING:
                return R.color.warning_tint;
            case FAILED:
                return R.color.danger_tint;
            default:
                return R.color.surface_subtle;
        }
    }

    /** Semantic foreground resource for the pill. */
    public int getForegroundColorRes() {
        switch (kind) {
            case READY:
                return R.color.success;
            case STARTING:
            case STOPPING:
                return R.color.warning;
            case FAILED:
                return R.color.danger;
            default:
                return R.color.ink_secondary;
        }
    }
}
