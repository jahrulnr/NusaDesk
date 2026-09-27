package gh.nusashell.nusadesk.infrastructure.runtimehost;

/**
 * Answers an HTTP authentication challenge raised by the owned loopback origin.
 *
 * <p>Without this collaborator the WebView has no way into an app that protects
 * its own endpoint: the platform default consults {@code WebViewDatabase},
 * finds nothing, and cancels the load, so the user sees a bare 401 and a
 * "could not be shown" panel for an app that is running perfectly (ADR-0058).</p>
 *
 * <p>The contract is deliberately asynchronous. The challenge arrives on the
 * main thread while a dialog may be the only way to obtain a username and
 * password, and blocking that thread waiting for the user would deadlock the
 * very dialog being asked for. An implementation must therefore return
 * immediately and call exactly one {@link Answer} method later, from any
 * thread.</p>
 *
 * <p>An implementation receives only challenges whose host
 * {@link LoopbackAuthChallengePolicy} has already accepted, so it never has to
 * re-check the origin. It is still the implementation's job to keep the secret
 * out of logs, out of {@code toString()}, and out of any destination other
 * than the app's own origin.</p>
 */
public interface HttpAuthResponder {

    /**
     * Asks the user for credentials for a challenge, or supplies the ones
     * already stored for this app.
     *
     * @param host   the challenged host, already accepted as the owned origin
     * @param realm  the server-supplied realm, shown to the user for context
     * @param answer the single reply this challenge must receive
     */
    void onChallenge(String host, String realm, Answer answer);

    /**
     * Whether the host must route an Android Back gesture here before anything
     * else does.
     *
     * <p>An implementation that has a visible prompt on screen answers
     * {@code true} and dismisses it; one that has nothing to dismiss answers
     * {@code false} so Back keeps reaching the shell. The default is
     * {@code false} because the common case — a stored credential that needs no
     * prompt at all — must not swallow the user's Back.</p>
     *
     * <p>This exists because a Back key event never reaches an ordinary child
     * view: {@code ViewRootImpl} dispatches it to the focused view, and when
     * that view declines, the event goes to the Activity's own back handling
     * rather than bubbling through parent {@code OnKeyListener}s the way touch
     * events do. A sign-in card whose fields hold focus therefore has to be
     * reached through the host's back routing, not through its own key
     * listener.</p>
     *
     * @return true when this responder consumed the Back gesture
     */
    default boolean handleBack() {
        return false;
    }

    /**
     * Tells the responder that the load its challenge belonged to is gone.
     *
     * <p>Called by the host before it starts a fresh load and when its surface
     * is released. A responder still holding a challenge must answer it — by
     * cancelling — rather than keep it: the navigation that created the
     * challenge has been superseded, and a submission aimed at a superseded
     * handler produces a loaded page that never arrives.</p>
     *
     * <p>The default does nothing, which is correct for an implementation that
     * never holds a challenge between calls.</p>
     */
    default void reset() {
    }

    /**
     * Tells the responder that a page in its surface finished loading, so a
     * sign-in it was waiting on can be closed.
     *
     * <p>This is what lets a prompt stay on screen until the answer is
     * actually known. Hiding the card at submit time and reopening it when a
     * refused pair comes back flashes the surface twice for every wrong
     * password; holding the card until the load finishes shows one state
     * instead of two.</p>
     *
     * @return true when this responder was waiting on a sign-in and has now
     *         closed it, so the caller may leave its sign-in state
     */
    default boolean handlePageLoaded() {
        return false;
    }

    /** The one reply a challenge receives. */
    interface Answer {

        /**
         * Completes the challenge with a username and password.
         *
         * <p>Called at most once per challenge. A wrong pair is not an error
         * here: the server answers 401 again, which is a fresh challenge.</p>
         *
         * @param username the sign-in name
         * @param password the sign-in secret
         */
        void proceed(String username, String password);

        /**
         * Abandons the challenge. The load stays unauthenticated and the
         * surface keeps an explicit "needs sign-in" state; this is never
         * reported as a broken app.
         */
        void cancel();
    }
}
