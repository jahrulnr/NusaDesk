package gh.nusashell.nusadesk.presentation;

/**
 * Optional screen contract for views that render the guest-SSH payload state.
 * Always invoked on the main thread; the state carries no secrets.
 */
public interface GuestSshStatusAware {

    /** Render the latest guest-SSH payload state. */
    void renderGuestSsh(GuestSshUiState state);
}
