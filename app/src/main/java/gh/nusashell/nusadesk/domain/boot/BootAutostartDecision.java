package gh.nusashell.nusadesk.domain.boot;

/**
 * Typed outcome of the boot-start decision (ADR-0037).
 *
 * <p>Every outcome is explicit so the receiver can log exactly why a boot or
 * package-replacement trigger did — or did not — ask the runtime host to start
 * a session. A skip is never a repair: the policy and its callers never
 * install, download, or schedule anything.</p>
 */
public enum BootAutostartDecision {
    /** Ask the runtime host to ensure the session is up. */
    START,
    /**
     * The user never enabled "Start Linux at boot" (the default) or turned it
     * back off. The overwhelmingly common outcome — the install default is
     * OFF, so a stock install does nothing at boot.
     */
    SKIP_OPT_OUT,
    /**
     * The curated rootfs is not in the persisted {@code READY} state: never
     * installed, failed, or an interrupted install reconciled to a failure.
     * The boot path cannot fix any of these — installation is a user-visible
     * pipeline, so this is a skip, not a retry.
     */
    SKIP_PAYLOAD_NOT_READY,
    /**
     * The curated OpenSSH add-on overlay is absent: the session would start
     * with no terminal component to serve, so the trigger does nothing.
     */
    SKIP_SSH_ADDON_MISSING,
    /** The required guest service/Python overlay is absent; no usable session yet. */
    SKIP_BRIDGE_NOT_SETTLED
}
