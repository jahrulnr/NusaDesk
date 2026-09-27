package gh.nusashell.nusadesk.presentation.webapp;

/**
 * The surface-half of the web-app sign-in flow: the two transitions a surface
 * must expose while an HTTP auth challenge is being answered.
 *
 * <p>A web app that protects its own loopback endpoint is not broken — it is
 * reachable and asking for credentials — so "needs sign-in" is its own surface
 * state, never folded into the generic failure panel (ADR-0058). The sign-in
 * coordinator drives this port: it shows the state while the user is being
 * asked, and hides it optimistically the moment an answer is sent, so the
 * load can resume behind the card. When the server rejects that answer the
 * challenge arrives again and {@link #showSignInRequired()} is re-entered —
 * the hide is a prediction, not a promise.</p>
 *
 * <p>Both methods are called on the main thread by
 * {@link WebAppSignInCoordinator}; implementations are ordinary view state.</p>
 */
public interface WebAppSignInState {

    /**
     * Renders "this app needs a sign-in": a stable state panel with one action
     * that re-drives the load so the challenge can be answered again. Called
     * before or while the sign-in card is up, and re-entered whenever a
     * supplied credential is rejected.
     */
    void showSignInRequired();

    /**
     * Returns the surface to its loaded presentation so the page can resume
     * behind — or after — the card. Optimistic: a rejected answer brings the
     * surface straight back through {@link #showSignInRequired()}.
     */
    void hideSignInRequired();
}
