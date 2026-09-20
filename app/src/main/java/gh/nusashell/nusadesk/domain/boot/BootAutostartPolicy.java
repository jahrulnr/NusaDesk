package gh.nusashell.nusadesk.domain.boot;

/**
 * Pure decision for the opt-in boot start (ADR-0037).
 *
 * <p>Mirrors the gates the in-app autostart boundary applies before calling
 * {@code RuntimeHostService.ensureRunning} — runtime {@code READY}, terminal
 * component installed, service bridge settled — plus the opt-in that only
 * exists on this path. Inputs are booleans so the policy carries no Android
 * or persistence knowledge; the receiver owns how each signal is read.</p>
 *
 * <p>The evaluation order is deliberate: the consent gate runs first, then
 * the install gates in the same order the presentation applies them. The
 * policy never triggers install, download, or package work — a missing
 * payload is a typed skip, documented in {@link BootAutostartDecision}.</p>
 */
public final class BootAutostartPolicy {

    private BootAutostartPolicy() {
    }

    /**
     * Decide what a {@code BOOT_COMPLETED} or {@code MY_PACKAGE_REPLACED}
     * trigger may do.
     *
     * @param optedIn              the user's "Start Linux at boot" setting
     * @param payloadReady         persisted runtime state reconciles to
     *                             {@code READY}
     * @param sshAddonPresent      the curated OpenSSH add-on overlay is
     *                             detected on disk
     * @param serviceBridgePresent the guest service-bridge overlay is
     *                             detected on disk
     * @return the typed outcome; never {@code null}
     */
    public static BootAutostartDecision decide(
            boolean optedIn,
            boolean payloadReady,
            boolean sshAddonPresent,
            boolean serviceBridgePresent) {
        if (!optedIn) {
            return BootAutostartDecision.SKIP_OPT_OUT;
        }
        if (!payloadReady) {
            return BootAutostartDecision.SKIP_PAYLOAD_NOT_READY;
        }
        if (!sshAddonPresent) {
            return BootAutostartDecision.SKIP_SSH_ADDON_MISSING;
        }
        if (!serviceBridgePresent) {
            return BootAutostartDecision.SKIP_BRIDGE_NOT_SETTLED;
        }
        return BootAutostartDecision.START;
    }
}
