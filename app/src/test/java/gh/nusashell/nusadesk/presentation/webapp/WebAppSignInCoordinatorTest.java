package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppCredentialStore;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;

/**
 * The coordinator's decision rules, driven through a real card on a real root
 * view under Robolectric. The worker is a direct executor and the main handler
 * is flushed with {@link ShadowLooper#idleMainLooper()}, so every scenario runs
 * deterministically while still exercising the same worker→main hand-off the
 * app uses. What this cannot prove is the WebView's own part — challenge
 * timing, the renderer, {@code HttpAuthHandler} — which needs a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppSignInCoordinatorTest {

    private static final String HOST = "127.0.0.1";

    private FrameLayout root;
    private WebAppSignInCard card;
    private MemoryStore store;
    private RecordingState surface;
    private WebAppSignInCoordinator coordinator;
    private WebAppDefinition definition;

    @Before
    public void setUp() {
        definition = new WebAppDefinition(
                WebAppId.of("app-1"), "Notebook", null, 18090, 0L, 0L, 0);
        root = new FrameLayout(RuntimeEnvironment.getApplication());
        card = new WebAppSignInCard(root);
        store = new MemoryStore();
        surface = new RecordingState();
        coordinator = new WebAppSignInCoordinator(definition, store, card, surface,
                Runnable::run, new Handler(Looper.getMainLooper()));
    }

    @Test
    public void challengeWithoutStoredPairShowsTheCardAndAnswersNothingYet() {
        RecordingAnswer answer = challenge("files");

        assertTrue(card.isShowing());
        assertEquals(1, surface.shows);
        assertEquals(0, surface.hides);
        assertEquals("the challenge stays parked for the user", 0, answer.calls);
    }

    @Test
    public void submittedPairProceedsAndIsStoredWhenRemembered() {
        RecordingAnswer answer = challenge("");

        typeCredentials("root", "  spaced secret  ");
        submit();

        assertEquals("root", answer.proceededUsername);
        assertEquals("the password is handed on untrimmed",
                "  spaced secret  ", answer.proceededPassword);
        assertEquals(1, answer.calls);
        assertEquals(new WebAppSignInCredential(
                definition.getId(), "root", "  spaced secret  "), store.stored);
    }

    @Test
    public void theCardHoldsUntilThePageArrivesSoARejectedPairDoesNotFlash() {
        RecordingAnswer answer = challenge("");
        typeCredentials("root", "wrong");
        submit();

        assertTrue("the card stays up while the answer is in flight — hiding it "
                + "here and reopening it on refusal is the flicker", card.isShowing());
        assertEquals("and the surface is never told to leave its sign-in state",
                0, surface.hides);
        assertFalse(this.<Button>find(R.id.webapp_signin_submit).isEnabled());

        assertTrue("a finished page is what finally closes it",
                coordinator.handlePageLoaded());

        assertFalse(card.isShowing());
        assertEquals("the coordinator closes its own card; leaving the sign-in "
                + "state is the surface's call, made in onPageFinished", 0, surface.hides);
        assertEquals("and a page with nothing in flight is not its business",
                false, coordinator.handlePageLoaded());
    }

    @Test
    public void aChallengeWhileAPairIsInFlightRePromptsAsARejection() {
        challenge("");
        typeCredentials("root", "wrong");
        submit();
        assertTrue(card.isShowing());

        challenge("files"); // the server refused the pair

        assertEquals("the second attempt never re-announces a sign-in state",
                0, surface.hides);
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_rejected).getVisibility());
        assertTrue("the form is live again for a correction",
                this.<Button>find(R.id.webapp_signin_submit).isEnabled());
        assertEquals("and the refused page did not count as a load",
                false, coordinator.handlePageLoaded());
    }

    @Test
    public void submittedPairWithoutRememberIsNeverStored() {
        RecordingAnswer answer = challenge(null);

        typeCredentials("root", "secret");
        this.<CheckBox>find(R.id.webapp_signin_remember).setChecked(false);
        submit();

        assertEquals("root", answer.proceededUsername);
        assertNull(store.stored);
    }

    @Test
    public void cancelledCardCancelsTheChallengeOnce() {
        RecordingAnswer answer = challenge(null);

        find(R.id.webapp_signin_cancel).performClick();

        assertTrue(answer.cancelled);
        assertEquals(1, answer.calls);
        assertFalse(card.isShowing());
        assertEquals("the surface stays in its sign-in-required state",
                1, surface.shows);
        assertEquals(0, surface.hides);
    }

    @Test
    public void storedPairIsSentOnceSilently() {
        store.stored = credential("saved_user", "saved_pw");

        RecordingAnswer answer = challenge("files");

        assertEquals("saved_user", answer.proceededUsername);
        assertEquals("saved_pw", answer.proceededPassword);
        assertEquals(1, answer.calls);
        assertFalse("the card never interrupts a stored answer", card.isShowing());
        assertEquals(1, surface.hides);
        assertEquals(0, surface.shows);
    }

    @Test
    public void refusedStoredPairRepromptsOnceWithTheUsernamePrefilled() {
        store.stored = credential("saved_user", "saved_pw");
        challenge("files");

        RecordingAnswer second = challenge("files");

        assertTrue(card.isShowing());
        assertEquals(1, surface.shows);
        assertEquals("the stored pair is not re-sent", 0, second.calls);
        assertEquals("the stored username is prefilled, the password is not",
                "saved_user", username().getText().toString());
        assertEquals("", password().getText().toString());
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_rejected).getVisibility());
    }

    @Test
    public void storedPairIsNeverRetriedAutomatically() {
        store.stored = credential("saved_user", "saved_pw");
        RecordingAnswer first = challenge("files");

        RecordingAnswer second = challenge("files");
        typeCredentials("still", "wrong");
        submit(); // remembered by default, so the store now holds the wrong pair
        RecordingAnswer third = challenge("files");

        assertEquals("the stored pair went out exactly once", "saved_user",
                first.proceededUsername);
        assertEquals("the typed pair proceeded because the user asked", "still",
                second.proceededUsername);
        assertEquals("the freshly saved pair is not silently retried either",
                0, third.calls);
        assertTrue(card.isShowing());
    }

    @Test
    public void aJustSavedPairIsNotAutoRetriedAgainstTheVeryNextChallenge() {
        RecordingAnswer first = challenge("files");
        typeCredentials("root", "wrong");
        submit(); // remembered by default: the wrong pair is now the stored pair

        RecordingAnswer second = challenge("files");

        assertEquals("once the user is typing, retries stay the user's call",
                0, second.calls);
        assertTrue(card.isShowing());
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_rejected).getVisibility());
        assertEquals("root", first.proceededUsername);
    }

    @Test
    public void aSecondChallengeWhileTheCardIsUpIsCancelledNotStacked() {
        RecordingAnswer first = challenge("one");
        RecordingAnswer second = challenge("two");

        assertTrue(second.cancelled);
        assertEquals(1, second.calls);
        assertEquals("the parked challenge still belongs to the card", 0, first.calls);
        assertEquals("no second card is stacked on the root", 1, root.getChildCount());

        typeCredentials("root", "pw");
        submit();
        assertEquals("root", first.proceededUsername);
    }

    private RecordingAnswer challenge(String realm) {
        RecordingAnswer answer = new RecordingAnswer();
        coordinator.onChallenge(HOST, realm, answer);
        ShadowLooper.idleMainLooper();
        return answer;
    }

    private void typeCredentials(String name, String secret) {
        username().setText(name);
        password().setText(secret);
    }

    private void submit() {
        find(R.id.webapp_signin_submit).performClick();
        ShadowLooper.idleMainLooper();
    }

    private EditText username() {
        return find(R.id.webapp_signin_username);
    }

    private EditText password() {
        return find(R.id.webapp_signin_password);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return (T) root.findViewById(id);
    }

    private WebAppSignInCredential credential(String username, String password) {
        return new WebAppSignInCredential(definition.getId(), username, password);
    }

    /** One challenge's outcome, recorded. */
    @Test
    public void resetCancelsAParkedChallengeAndDropsTheCard() {
        RecordingAnswer answer = challenge("files");
        assertTrue(card.isShowing());
        assertEquals(0, answer.calls);

        // The surface began a fresh load, so the navigation this challenge
        // belonged to is gone. Left parked, it would let the user's next
        // submission answer a handler the WebView already superseded.
        coordinator.reset();

        assertEquals(1, answer.calls);
        assertTrue(answer.cancelled);
        assertFalse(card.isShowing());
    }

    @Test
    public void aChallengeArrivingAfterAResetOpensAFreshCard() {
        challenge("files");
        coordinator.reset();

        RecordingAnswer fresh = challenge("files");

        assertFalse("the cancelled challenge must not leave the new one looking "
                + "like a duplicate", fresh.cancelled);
        assertTrue(card.isShowing());
        assertEquals(0, fresh.calls);
    }

    private static final class RecordingAnswer implements HttpAuthResponder.Answer {
        int calls;
        boolean cancelled;
        String proceededUsername;
        String proceededPassword;

        @Override
        public void proceed(String username, String password) {
            calls++;
            proceededUsername = username;
            proceededPassword = password;
        }

        @Override
        public void cancel() {
            calls++;
            cancelled = true;
        }
    }

    /** The surface's sign-in toggle, recorded. */
    private static final class RecordingState implements WebAppSignInState {
        int shows;
        int hides;

        @Override
        public void showSignInRequired() {
            shows++;
        }

        @Override
        public void hideSignInRequired() {
            hides++;
        }
    }

    /** An in-memory {@link WebAppCredentialStore}. */
    private static final class MemoryStore implements WebAppCredentialStore {
        WebAppSignInCredential stored;

        @Override
        public WebAppSignInCredential find(WebAppId webAppId) {
            return stored;
        }

        @Override
        public void save(WebAppSignInCredential credential) {
            stored = credential;
        }

        @Override
        public void clear(WebAppId webAppId) {
            stored = null;
        }
    }
}
