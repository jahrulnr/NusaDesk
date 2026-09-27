package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;

/**
 * The surface's AUTH_REQUIRED rendering under Robolectric: the state is its
 * own (never the failed panel), its single action re-drives the load through
 * the same probe every state uses, and the shared action button falls back to
 * "Try again" everywhere else. The probe is the real one on a real worker —
 * nothing listens on the fixture port, so a bind settles into UNREACHABLE and
 * no WebView is ever created, keeping the test off the renderer entirely.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppSurfaceViewSignInTest {

    private ExecutorService executor;
    private WebAppSurfaceView surface;
    private WebAppDefinition definition;

    @Before
    public void setUp() {
        executor = Executors.newSingleThreadExecutor();
        surface = new WebAppSurfaceView(RuntimeEnvironment.getApplication());
        definition = new WebAppDefinition(
                // Port 1 is privileged and never served: the probe is refused
                // instantly instead of waiting out a timeout or hitting a real
                // listener by accident.
                WebAppId.of("app-1"), "Notebook", null, 1, 0L, 0L, 0);
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void signInRequiredIsItsOwnStateWithItsOwnAction() {
        bind();

        surface.showSignInRequired();

        assertEquals(WebAppSurfaceView.State.AUTH_REQUIRED, surface.getState());
        assertEquals(View.VISIBLE, panel().getVisibility());
        assertEquals(getString(R.string.webapp_state_signin_title, "Notebook"),
                this.<TextView>find(R.id.webapp_state_title).getText().toString());
        assertEquals(getString(R.string.webapp_state_signin_body),
                this.<TextView>find(R.id.webapp_state_body).getText().toString());
        Button action = find(R.id.webapp_state_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(getString(R.string.webapp_state_signin_action),
                action.getText().toString());
    }

    @Test
    public void hidingSignInRequiredReturnsOptimisticallyToLoaded() {
        bind();
        surface.showSignInRequired();

        surface.hideSignInRequired();

        assertEquals(WebAppSurfaceView.State.LOADED, surface.getState());
        assertEquals(View.GONE, panel().getVisibility());
    }

    @Test
    public void theSignInActionReDrivesTheLoadThroughAProbe() {
        bind();
        surface.showSignInRequired();

        find(R.id.webapp_state_action).performClick();
        settle();

        assertEquals("the retry is the same probe every state offers, so a "
                        + "dead app lands on unreachable instead of a bare card",
                WebAppSurfaceView.State.UNREACHABLE, surface.getState());
        assertEquals("the shared action label falls back to retry",
                getString(R.string.webapp_state_retry),
                this.<Button>find(R.id.webapp_state_action).getText().toString());
    }

    @Test
    public void backGoesToAPendingSignInBeforeAnythingElse() {
        boolean[] asked = {false};
        surface.bind(definition, executor, new HttpAuthResponder() {
            @Override
            public void onChallenge(
                    String host, String realm, HttpAuthResponder.Answer answer) {
                answer.cancel();
            }

            @Override
            public boolean handleBack() {
                asked[0] = true;
                return true;
            }
        });
        settle();

        assertTrue("the surface consumed Back on the responder's behalf",
                surface.handleBack());
        assertTrue("the responder owns Back while its prompt is up", asked[0]);
    }

    @Test
    public void backIsNotClaimedWhenNoSignInPromptIsUp() {
        bind();

        assertFalse("nothing of the sign-in flow is showing, so Back must fall "
                + "through to the surface's own contract", surface.handleBack());
    }

    @Test
    public void aMissingResponderIsRefusedAtBind() {
        try {
            surface.bind(definition, executor, null);
            fail("bind must reject a null sign-in responder");
        } catch (IllegalArgumentException expected) {
            // Documented contract: a surface with no responder can never sign in.
        }
    }

    private void bind() {
        surface.bind(definition, executor, (host, realm, answer) -> answer.cancel());
        settle();
        assertEquals("the fixture port answers nothing, so the surface is "
                + "honestly unreachable before sign-in is exercised",
                WebAppSurfaceView.State.UNREACHABLE, surface.getState());
    }

    /**
     * Drains the main looper until the background probe's posted result has
     * been applied. The executor stays alive — a reload reuses it.
     */
    private void settle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            if (surface.getState() != WebAppSurfaceView.State.PROBING) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for the probe");
                return;
            }
        }
        fail("the probe did not settle within 10 seconds");
    }

    private String getString(int res, Object... args) {
        return RuntimeEnvironment.getApplication().getString(res, args);
    }

    private View panel() {
        return find(R.id.webapp_state_panel);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return (T) surface.findViewById(id);
    }
}
