package gh.nusashell.nusadesk.domain.backup;

/**
 * The three guest-backup modes offered in the UI.
 *
 * <p>{@link #FULL} archives the active runtime rootfs plus the active guest
 * add-ons, session state and the guest SSH host key, and is the only mode that
 * can bootstrap a fresh install. {@link #HOME} and {@link #CUSTOM} archive
 * selected rootfs subtrees and merge into an already-installed runtime.</p>
 *
 * <p>The wire value is the string stored in the archive manifest and is part
 * of the format contract.</p>
 */
public enum BackupMode {
    FULL("full"),
    HOME("home"),
    CUSTOM("custom");

    private final String wireValue;

    BackupMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    /** @return the mode for a manifest wire value, or {@code null} if unknown. */
    public static BackupMode fromWireValue(String value) {
        for (BackupMode mode : values()) {
            if (mode.wireValue.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
