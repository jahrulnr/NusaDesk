package gh.nusashell.nusadesk.presentation.webapp;

import android.os.Handler;

import java.util.concurrent.Executor;

import gh.nusashell.nusadesk.application.webapp.WebAppCredentialStore;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;

/**
 * Answers an owned web app's HTTP auth challenges by mediating between three
 * collaborators: the encrypted {@link WebAppCredentialStore}, the native
 * {@link WebAppSignInCard}, and the surface's {@link WebAppSignInState} panel
 * (ADR-0058).
 *
 * <p>Threading is the reason this class exists at all. The challenge arrives
 * on the main thread while the WebView waits, and the contract forbids
 * blocking it — but reading the credential vault is decrypt work that must
 * not run on main. So {@link #onChallenge} hops to the injected
 * {@code worker} for the store lookup and back to the injected {@code main}
 * handler for every decision and view touch. All mutable state below is
 * main-thread confined; the worker only ever runs vault I/O.</p>
 *
 * <p>The two repetition guards are the whole reliability story:</p>
 *
 * <ul>
 *   <li>A stored pair is tried at most once per coordinator — that is, per
 *       surface open. A refused saved password can only be stale, and
 *       re-sending it is a guaranteed loop, so a second challenge after the
 *       attempt shows the card with {@code rejected} instead.</li>
 *   <li>Once the user has typed a pair, automatic reuse stops entirely
 *       ({@code manualTried}). Without this gate a freshly saved but wrong
 *       pair would be silently re-sent once more on the very next challenge.
 *       From then on every retry is an explicit user choice.</li>
 * </ul>
 *
 * <p>The challenge callback exposes a host and a realm but no URL and no
 * port, so two simultaneous challenges are indistinguishable and a second
 * card could never be aimed at "the other" request. The coordinator is
 * therefore single-flight: while a card is parked with an unanswered
 * {@link Answer}, a new arrival is cancelled rather than stacked.</p>
 *
 * <p>The hide-before-proceed ordering is deliberate. On any answer —
 * stored or typed — the surface returns to {@code LOADED} and the card
 * drops <em>before</em> {@code proceed} is called, so a correct pair resumes
 * the load with no flicker. A wrong pair makes the server challenge again,
 * which lands back in {@link #dispatch} with {@code rejected = true}: the
 * state panel and card reappear. The save of a "remember me" pair is fired
 * on the worker in the same instant as {@code proceed}, never awaited —
 * disk latency must not hold the load, and the store treats a vault failure
 * as "nothing stored", which the next challenge reads as no pair.</p>
 */
public final class WebAppSignInCoordinator implements HttpAuthResponder {

    private final WebAppDefinition definition;
    private final WebAppCredentialStore store;
    private final WebAppSignInCard card;
    private final WebAppSignInState surface;
    private final Executor worker;
    private final Handler main;

    // Main-thread confined: only touched inside main.post(...) or callbacks
    // the card fires on the UI thread.
    private Answer pending;
    private boolean storedTried;
    private boolean manualTried;
    /** A pair was sent and its page has not come back yet. */
    private boolean awaitingPage;

    private final WebAppSignInCard.Callback cardCallback =
            new WebAppSignInCard.Callback() {
                @Override
                public void onSubmitted(String username, String password, boolean remember) {
                    manualTried = true;
                    Answer answer = pending;
                    pending = null;
                    if (remember) {
                        worker.execute(() -> saveQuietly(username, password));
                    }
                    // The card stays up and goes inert. Hiding it here and
                    // reopening it when a refused pair comes back flashes the
                    // whole surface twice on every wrong password; holding it
                    // until handlePageLoaded() is called shows one state.
                    awaitingPage = true;
                    card.setSubmitting(true);
                    if (answer != null) {
                        answer.proceed(username, password);
                    }
                }

                @Override
                public void onCancelled() {
                    Answer answer = pending;
                    pending = null;
                    card.hide();
                    if (answer != null) {
                        answer.cancel();
                    }
                    // The surface stays in its sign-in-required state: its
                    // panel is what remains, with one explicit way back in.
                }

            };

    /**
     * @param definition the app this coordinator answers for
     * @param store      the per-app credential vault port
     * @param card       the native sign-in card bound to the app's surface
     * @param surface    the surface's sign-in state toggle
     * @param worker     executor for vault I/O, never the main thread's work
     * @param main       handler that returns work to the UI thread
     * @throws IllegalArgumentException when any argument is null
     */
    public WebAppSignInCoordinator(WebAppDefinition definition,
                                 WebAppCredentialStore store,
                                 WebAppSignInCard card,
                                 WebAppSignInState surface,
                                 Executor worker,
                                 Handler main) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (card == null) {
            throw new IllegalArgumentException("card must not be null");
        }
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        if (main == null) {
            throw new IllegalArgumentException("main must not be null");
        }
        this.definition = definition;
        this.store = store;
        this.card = card;
        this.surface = surface;
        this.worker = worker;
        this.main = main;
    }

    /**
     * Starts answering one challenge: the credential lookup runs on the
     * worker, and the decision lands back on the main handler. Returns at
     * once — the {@link Answer} is completed later, exactly once.
     *
     * <p>The lookup sits <em>inside</em> the worker task, not inside the
     * posted runnable: it reads SharedPreferences and performs an
     * AndroidKeyStore load plus an AES-GCM decrypt, and none of that may run
     * on the thread that is drawing the card.</p>
     */
    @Override
    public void onChallenge(String host, String realm, Answer answer) {
        if (answer == null) {
            throw new IllegalArgumentException("answer must not be null");
        }
        worker.execute(() -> {
            WebAppSignInCredential stored = findQuietly();
            main.post(() -> dispatch(realm, answer, stored));
        });
    }

    /**
     * Abandons any challenge this coordinator is still holding, because the
     * load it belonged to is gone.
     *
     * <p>The surface calls this before it starts a fresh probe and when it is
     * released. Without it a parked {@link Answer} outlives the navigation
     * that created it: the new load challenges again, {@link #dispatch} sees a
     * card still parked and cancels the fresh challenge, and the user's next
     * submission would then answer a handler the WebView has already
     * superseded — producing a "loaded" state over a blank page with nothing
     * left to retry. Cancelling here keeps the abandoned challenge answered
     * exactly once and lets the next one open a real card.</p>
     */
    @Override
    public void reset() {
        Answer abandoned = pending;
        pending = null;
        awaitingPage = false;
        card.hide();
        if (abandoned != null) {
            abandoned.cancel();
        }
    }

    /**
     * The one decision point, on the main thread. A never-tried stored pair is
     * sent without ever surfacing a prompt; anything else — nothing stored,
     * stored pair already refused, or a manual pair already sent — is the
     * user's question to answer through the card.
     */
    private void dispatch(String realm, Answer answer, WebAppSignInCredential stored) {
        // A challenge while a submitted pair is still in flight means the pair
        // was refused: the card reappears as a rejection rather than a prompt.
        if (awaitingPage) {
            awaitingPage = false;
        }
        if (pending != null) {
            // A card is already parked on an earlier challenge. The challenge
            // carries no URL, so this arrival cannot be told apart from the
            // one the user is answering; it is cancelled rather than queued or
            // stacked.
            answer.cancel();
            return;
        }
        if (stored != null && !storedTried && !manualTried) {
            storedTried = true;
            surface.hideSignInRequired();
            card.hide();
            answer.proceed(stored.getUsername(), stored.getPassword());
            return;
        }
        pending = answer;
        if (stored != null) {
            card.prefillUsername(stored.getUsername());
        }
        surface.showSignInRequired();
        card.show(definition.getDisplayName(), realm, stored != null,
                storedTried || manualTried, cardCallback);
    }

    /**
     * Routes an Android Back gesture to the card while one is up.
     *
     * <p>The host calls this before the shell decides what Back means, because
     * a visible sign-in prompt must be dismissed before the surface or the
     * launcher gets the gesture — and the prompt's challenge is still pending
     * until it is answered or cancelled.</p>
     *
     * @return true when the card cancelled a pending challenge
     */
    @Override
    public boolean handleBack() {
        return card.handleBackPressed();
    }

    /**
     * Closes a sign-in whose page actually arrived.
     *
     * @return true when a submitted pair's page loaded, so the surface may
     *         leave its sign-in state
     */
    @Override
    public boolean handlePageLoaded() {
        if (!awaitingPage) {
            return false;
        }
        awaitingPage = false;
        card.hide();
        return true;
    }

    /** Reads the vault on the worker; a read failure means "no stored pair". */
    private WebAppSignInCredential findQuietly() {
        try {
            return store.find(definition.getId());
        } catch (RuntimeException readFailed) {
            return null;
        }
    }

    /**
     * Persists a remembered pair on the worker. The card already ran the same
     * normalizers, but the value object is re-validated at the boundary anyway;
     * a store failure is swallowed — the pair was already used for the in-flight
     * challenge, so a lost save only means no pair for next time.
     */
    private void saveQuietly(String username, String password) {
        try {
            store.save(new WebAppSignInCredential(
                    definition.getId(), username, password));
        } catch (RuntimeException saveFailed) {
            // Deliberate no-op: the secret is never retried or logged.
        }
    }
}
