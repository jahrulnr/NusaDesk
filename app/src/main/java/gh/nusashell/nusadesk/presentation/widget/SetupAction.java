package gh.nusashell.nusadesk.presentation.widget;

/**
 * The single primary action the unified setup surface offers, derived from the
 * combined rootfs + add-on state. One setup pipeline means one thumb-reachable
 * action: there is no second "Next: add terminal component" button.
 *
 * <p>Pure presentation vocabulary with no Android imports so the combined
 * phase policy is testable in plain JUnit.</p>
 */
public enum SetupAction {
    /** Rootfs is not installed yet; the one action starts the whole pipeline. */
    START,
    /** Rootfs or add-on failed; the one action retries only what is missing. */
    RETRY,
    /** A component is installing or auto-continuing; the action is disabled. */
    INSTALLING,
    /** Both components are active; the setup surface is hidden. */
    HIDDEN
}
