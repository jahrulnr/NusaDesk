package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;

/**
 * The card's view wiring under Robolectric: attach/detach, which affordances
 * appear for which flags, the field validation that guards the callback, and
 * the cancel paths. Focus, IME, and pixel output still need a device — the
 * shadow hierarchy does not render.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppSignInCardTest {

    private FrameLayout root;
    private WebAppSignInCard card;
    private RecordingCallback callback;

    @Before
    public void setUp() {
        root = new FrameLayout(RuntimeEnvironment.getApplication());
        card = new WebAppSignInCard(root);
        callback = new RecordingCallback();
    }

    @Test
    public void showAttachesOneOpaqueFullSizeOverlay() {
        show();

        assertTrue(card.isShowing());
        assertEquals(1, root.getChildCount());
        View overlay = root.getChildAt(0);
        assertNotNull("the overlay is opaque — the aborted load stays hidden",
                overlay.getBackground());
        ViewGroup.LayoutParams params = overlay.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height);
    }

    @Test
    public void theAppsOwnIconWinsOverTheNusaDeskFallback() {
        Bitmap icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);

        card.setAppIcon(icon);
        show();

        Drawable drawable = this.<ImageView>find(R.id.webapp_signin_icon).getDrawable();
        assertTrue("the prompt wears the app's own icon, so the user can see "
                        + "which app is asking for a password",
                drawable instanceof BitmapDrawable);
        assertSame(icon, ((BitmapDrawable) drawable).getBitmap());
    }

    @Test
    public void submittingLocksTheFormWithoutDismissingIt() {
        show();

        card.setSubmitting(true);

        assertTrue("the card must stay up — dismissing it here is the flicker",
                card.isShowing());
        assertFalse(this.<EditText>find(R.id.webapp_signin_username).isEnabled());
        assertFalse(this.<EditText>find(R.id.webapp_signin_password).isEnabled());
        assertFalse(this.<CheckBox>find(R.id.webapp_signin_remember).isEnabled());
        assertFalse(this.<Button>find(R.id.webapp_signin_submit).isEnabled());
        assertEquals(root.getContext().getString(R.string.webapp_signin_checking),
                this.<Button>find(R.id.webapp_signin_submit).getText().toString());

        card.setSubmitting(false);

        assertEquals(root.getContext().getString(R.string.webapp_signin_submit),
                this.<Button>find(R.id.webapp_signin_submit).getText().toString());
        assertTrue(this.<Button>find(R.id.webapp_signin_submit).isEnabled());
    }

    @Test
    public void hideDetachesAndIsIdempotent() {
        show();

        card.hide();
        card.hide();

        assertFalse(card.isShowing());
        assertEquals(0, root.getChildCount());
    }

    @Test
    public void showingAgainReRendersInsteadOfStacking() {
        show();
        card.show("Notebook", "", false, true, callback);

        assertEquals(1, root.getChildCount());
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_rejected).getVisibility());
    }

    @Test
    public void realmIsShownOnlyWhenItIsUsefulText() {
        card.show("Notebook", "  ", false, false, callback);
        assertEquals(View.GONE, find(R.id.webapp_signin_realm).getVisibility());

        card.show("Notebook", "files", false, false, callback);
        TextView realm = find(R.id.webapp_signin_realm);
        assertEquals(View.VISIBLE, realm.getVisibility());
        assertEquals(root.getContext().getString(R.string.webapp_signin_realm, "files"),
                realm.getText().toString());
    }


    @Test
    public void aBlankUsernameIsRejectedInline() {
        show();
        EditText username = find(R.id.webapp_signin_username);
        username.setText("   ");
        passwordField().setText("secret");

        submit();

        assertEquals(0, callback.submissions);
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_username_error).getVisibility());
        assertTrue(card.isShowing());
    }

    @Test
    public void anEmptyPasswordIsRejectedInline() {
        show();
        usernameField().setText("root");

        submit();

        assertEquals(0, callback.submissions);
        assertEquals(View.VISIBLE, find(R.id.webapp_signin_password_error).getVisibility());
    }

    @Test
    public void usernameIsTrimmedAndThePasswordIsPreserved() {
        show();
        usernameField().setText("  root  ");
        passwordField().setText("  secret  ");

        submit();

        assertEquals(1, callback.submissions);
        assertEquals("root", callback.username);
        assertEquals("  secret  ", callback.password);
        assertTrue("remember is on by default", callback.remember);
    }

    @Test
    public void thePasswordFieldIsMaskedAndNeverAutofilled() {
        show();

        EditText password = find(R.id.webapp_signin_password);
        assertTrue((password.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0);
        assertTrue((password.getInputType() & InputType.TYPE_CLASS_TEXT) != 0);
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, password.getImportantForAutofill());
    }

    @Test
    public void theLastUsernameIsPrefilledForTheNextPrompt() {
        show();
        usernameField().setText("  root  ");
        passwordField().setText("pw");
        submit();

        card.show("Notebook", null, false, true, callback);

        assertEquals("root", this.<EditText>find(R.id.webapp_signin_username)
                .getText().toString());
        assertEquals("", this.<EditText>find(R.id.webapp_signin_password)
                .getText().toString());
    }

    @Test
    public void theStoredUsernamePrefillWinsWhenSupplied() {
        card.prefillUsername("saved_user");

        card.show("Notebook", null, true, true, callback);

        assertEquals("saved_user", this.<EditText>find(R.id.webapp_signin_username)
                .getText().toString());
    }


    @Test
    public void cancelFiresOnce() {
        show();

        find(R.id.webapp_signin_cancel).performClick();
        find(R.id.webapp_signin_cancel).performClick();

        assertEquals(1, callback.cancels);
    }

    @Test
    public void backIsACancel() {
        show();

        // Routed through the host's back contract rather than a key event on the
        // overlay: a Back key event goes to the focused view and, when that
        // view declines, straight to the Activity. It never bubbles through a
        // parent OnKeyListener, so a listener here would silently never fire and
        // the pending challenge would hang. See WebAppSignInCard.handleBackPressed.
        assertTrue(card.handleBackPressed());

        assertEquals(1, callback.cancels);
    }

    @Test
    public void backIsNotClaimedWhenNoChallengeIsPending() {
        card.show("Notebook", null, false, false, callback);
        assertTrue(card.handleBackPressed());

        // Nothing left to dismiss: the shell must keep the gesture.
        assertFalse(card.handleBackPressed());
        assertEquals(1, callback.cancels);
    }

    @Test
    public void theTitleNamesTheApp() {
        show();

        assertEquals(root.getContext().getString(R.string.webapp_signin_title, "Notebook"),
                this.<TextView>find(R.id.webapp_signin_title).getText().toString());
    }

    private void show() {
        card.show("Notebook", null, false, false, callback);
    }

    private void submit() {
        find(R.id.webapp_signin_submit).performClick();
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return (T) root.findViewById(id);
    }

    /**
     * Typed accessors for the two text fields. A chained
     * {@code find(id).setText(...)} cannot infer the generic type and binds to
     * {@code View}, so the fields the tests type into are read through these.
     */
    private EditText usernameField() {
        return this.<EditText>find(R.id.webapp_signin_username);
    }

    private EditText passwordField() {
        return this.<EditText>find(R.id.webapp_signin_password);
    }

    /** The card's outcome, recorded. */
    private static final class RecordingCallback implements WebAppSignInCard.Callback {
        int submissions;
        int cancels;
        String username;
        String password;
        boolean remember;

        @Override
        public void onSubmitted(String username, String password, boolean remember) {
            submissions++;
            this.username = username;
            this.password = password;
            this.remember = remember;
        }

        @Override
        public void onCancelled() {
            cancels++;
        }

    }
}
