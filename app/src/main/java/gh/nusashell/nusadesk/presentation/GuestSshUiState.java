package gh.nusashell.nusadesk.presentation;

/**
 * Immutable UI state for the guest-SSH payload, rendered by the session cards
 * when the curated runtime is installed but the add-on is not yet usable.
 *
 * <p>{@link Kind#INSTALLED} means a usable daemon was detected on disk (the
 * activated overlay or a rootfs-resident {@code sshd}); it never means a
 * session is running. {@link Kind#MISSING} offers the install affordance,
 * {@link Kind#INSTALLING} renders live progress, and {@link Kind#FAILED}
 * surfaces the reason with a retry.</p>
 */
public final class GuestSshUiState {
    public enum Kind { INSTALLED, MISSING, INSTALLING, FAILED }

    private static final GuestSshUiState INSTALLED = new GuestSshUiState(Kind.INSTALLED, null);
    private static final GuestSshUiState MISSING = new GuestSshUiState(Kind.MISSING, null);

    private final Kind kind;
    private final String detail;

    private GuestSshUiState(Kind kind, String detail) {
        this.kind = kind;
        this.detail = detail;
    }

    public static GuestSshUiState installed() {
        return INSTALLED;
    }

    public static GuestSshUiState missing() {
        return MISSING;
    }

    public static GuestSshUiState installing(String detail) {
        return new GuestSshUiState(Kind.INSTALLING, detail);
    }

    public static GuestSshUiState failed(String detail) {
        return new GuestSshUiState(Kind.FAILED, detail);
    }

    public Kind getKind() {
        return kind;
    }

    /** Non-secret progress or failure detail; may be null. */
    public String getDetail() {
        return detail;
    }
}
