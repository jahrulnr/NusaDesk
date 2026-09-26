package gh.nusashell.nusadesk.presentation.system;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Display state of one independently installable guest toolkit — USB/ADB or
 * Termux commands (ADR-0057) — on System &gt; One-click install and the setup
 * offer. Pure Java so the disk-truth-versus-snapshot precedence is
 * unit-testable.
 *
 * <p>The installed flag is the authority: it comes from the guest rootfs
 * marker/files, so a previously installed toolkit reports
 * {@link Kind#INSTALLED} regardless of a stale or absent install snapshot.
 * Install snapshots only ever live in memory, which is why a fresh process
 * sees an installed toolkit as installed but a failed attempt as simply not
 * installed.</p>
 */
public final class ServicesExtraState {

    /** What one toolkit's row communicates. */
    public enum Kind {
        /** Present on disk; nothing to do. */
        INSTALLED,
        /** Provisioning is publishing progress right now. */
        INSTALLING,
        /** Absent and idle; the Install action is available. */
        AVAILABLE,
        /** The last attempt failed; the action is Try again. */
        FAILED
    }

    private final Kind kind;
    /** Progress detail while installing, the failure reason after a failure. */
    private final String detail;
    /** Whether a tap on the action may start an install right now. */
    private final boolean actionEnabled;

    private ServicesExtraState(Kind kind, String detail, boolean actionEnabled) {
        this.kind = kind;
        this.detail = detail;
        this.actionEnabled = actionEnabled;
    }

    /**
     * Derives one toolkit row's state.
     *
     * @param snapshot    the latest provisioning snapshot for this toolkit, or
     *                    {@code null} when it was never attempted this process
     * @param installed   whether the toolkit's guest files/marker exist
     * @param installBusy whether the serialized install pipeline is busy (any
     *                    component — a concurrent rootfs/SSH install must also
     *                    disable the action)
     */
    public static ServicesExtraState of(
            RuntimeSnapshot snapshot, boolean installed, boolean installBusy) {
        if (installed) {
            return new ServicesExtraState(Kind.INSTALLED, null, false);
        }
        Kind state = Kind.AVAILABLE;
        String detail = null;
        if (snapshot != null) {
            RuntimeState runtime = snapshot.getState();
            if (runtime == RuntimeState.DOWNLOADING
                    || runtime == RuntimeState.VERIFYING
                    || runtime == RuntimeState.EXTRACTING) {
                state = Kind.INSTALLING;
                detail = snapshot.getDetail();
            } else if (runtime == RuntimeState.FAILED) {
                state = Kind.FAILED;
                detail = snapshot.getDetail();
            }
        }
        boolean actionEnabled =
                (state == Kind.AVAILABLE || state == Kind.FAILED) && !installBusy;
        return new ServicesExtraState(state, detail, actionEnabled);
    }

    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isActionEnabled() {
        return actionEnabled;
    }
}
