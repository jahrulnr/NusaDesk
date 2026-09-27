package gh.nusashell.nusadesk.presentation.webapp;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;

/**
 * The native sign-in card shown when a registered web app's own loopback
 * endpoint answers with an HTTP auth challenge (ADR-0058).
 *
 * <p>This is deliberately a plain {@link View} overlay and not a page and not
 * a dialog:</p>
 *
 * <ul>
 *   <li>An injected HTML form would put the password inside the WebView
 *       renderer — inside the very surface whose "no JavaScript interface" and
 *       exact-origin rules exist to keep host power away from page content.
 *       Keeping the form native keeps the secret out of that renderer
 *       entirely; it travels only through {@code HttpAuthHandler.proceed}.</li>
 *   <li>A platform {@code AlertDialog} was rejected in favour of a card that
 *       matches the NusaDesk surface language: opaque, centred, carrying the
 *       app mark, and styled with the existing dialog/form resources.</li>
 *   <li>The overlay is full-size and opaque so the aborted load behind it is
 *       never visible, and it consumes touches so the page underneath cannot
 *       be driven while a challenge is unanswered.</li>
 * </ul>
 *
 * <p>The card owns no credential logic. It validates the two fields with the
 * same {@link WebAppSignInCredential} normalizers the domain enforces, reports
 * the result through {@link Callback}, and never keeps, logs, or renders the
 * password — the field is cleared on every {@link #show} and its text is only
 * handed to the submit callback. The username is the safe half: the last
 * submitted one is remembered for the life of the card so a rejected sign-in
 * is a one-field edit, and the coordinator may prefill the stored username via
 * {@link #prefillUsername(String)} when a saved pair was refused.</p>
 *
 * <p>Lifecycle: {@link #show} attaches the overlay to the root given at
 * construction (the surface), re-rendering in place if it is already up so a
 * re-prompt never stacks a second card; {@link #hide} detaches it and is
 * idempotent. The system Back key while the card is up is a cancel, wired on
 * the overlay itself so no host has to know the card exists.</p>
 */
public final class WebAppSignInCard {

    /**
     * Receives the card's outcome. Exactly one terminal call —
     * {@link #onSubmitted} or {@link #onCancelled} — is made per
     * {@link #show}; {@link #setSubmitting(boolean)} locks it while the answer
     * is in flight.
     */
    public interface Callback {
        /**
         * The user submitted a validated pair. {@code username} is trimmed per
         * {@link WebAppSignInCredential#normalizeUsername}; {@code password} is
         * the raw field text, never trimmed; {@code remember} asks the caller
         * to persist the pair in the credential vault.
         */
        void onSubmitted(String username, String password, boolean remember);

        /**
         * The user dismissed the card without signing in (Cancel or Back). The
         * outstanding challenge must still be answered — with a cancel.
         */
        void onCancelled();
    }

    private final ViewGroup root;

    private View overlay;
    private View card;
    private TextView title;
    private TextView body;
    private TextView realm;
    private TextView rejected;
    private EditText username;
    private TextView usernameError;
    private EditText password;
    private TextView passwordError;
    private ImageView appMark;
    private CheckBox remember;
    private Button cancel;
    private Button submit;

    private Callback callback;
    private String lastUsername;
    private String storedUsername;
    /** The app's own launcher icon, or null for the NusaDesk mark fallback. */
    private Bitmap appIcon;

    /**
     * @param root the container the overlay is attached to — the app's surface,
     *             so the card appears and disappears with it
     * @throws IllegalArgumentException when {@code root} is null
     */
    public WebAppSignInCard(ViewGroup root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root;
    }

    /**
     * Shows the card, or re-renders it in place when it is already up.
     *
     * @param appName   the app's display name, interpolated into the texts
     * @param realm     the server-supplied realm, shown as context when it is
     *                  useful text; hidden for null or blank
     * @param hasStored whether a saved pair exists, so the username the
     *                  coordinator prefill stands can be preferred
     * @param rejected  whether the last supplied pair was refused — shows an
     *                  inline message, never the pair itself
     * @param callback  receives this showing's outcome; a new show replaces
     *                  any stale one
     */
    public void show(String appName, String realm, boolean hasStored,
                     boolean rejected, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        ensureOverlay();
        this.callback = callback;

        String name = appName == null ? "" : appName;
        title.setText(root.getContext().getString(R.string.webapp_signin_title, name));
        body.setText(root.getContext().getString(R.string.webapp_signin_body));

        String realmText = realm == null ? "" : realm.trim();
        this.realm.setVisibility(realmText.isEmpty() ? View.GONE : View.VISIBLE);
        if (!realmText.isEmpty()) {
            this.realm.setText(root.getContext()
                    .getString(R.string.webapp_signin_realm, realmText));
        }

        this.rejected.setVisibility(rejected ? View.VISIBLE : View.GONE);

        usernameError.setVisibility(View.GONE);
        passwordError.setVisibility(View.GONE);
        password.setText("");
        remember.setChecked(true);

        String prefill = hasStored && storedUsername != null ? storedUsername : lastUsername;
        username.setText(prefill == null ? "" : prefill);
        // A re-render is also a fresh attempt, so any lock left by a previous
        // submit is released here. Without this a refused pair would come back
        // as a card the user can no longer type into.
        setSubmitting(false);

        // Focus lands on the field the user needs next. Back does not depend on
        // this: it is routed by the host, because a Back key event never
        // reaches a child view's key listener (see handleBackPressed).
        overlay.requestFocus();
        (prefill == null || prefill.isEmpty() ? username : password).requestFocus();
    }

    /**
     * Detaches the overlay. Idempotent, and safe while the host is gone: view
     * removal needs no live Activity.
     */
    public void hide() {
        callback = null;
        if (password != null) {
            // Wipe the field before the reference goes: the detached EditText
            // would otherwise keep the secret alive until it is collected.
            password.setText("");
        }
        if (overlay != null) {
            root.removeView(overlay);
        }
        overlay = null;
        card = null;
        title = null;
        body = null;
        realm = null;
        rejected = null;
        username = null;
        usernameError = null;
        password = null;
        passwordError = null;
        appMark = null;
        remember = null;
        cancel = null;
        submit = null;
    }

    /** Whether the overlay is currently attached to the root. */
    public boolean isShowing() {
        return overlay != null && overlay.getParent() == root;
    }

    /**
     * The username of the stored pair, supplied by the coordinator when a
     * saved credential was refused, so the re-prompt starts filled. Package
     * seam — the public {@link #show} signature is frozen, so the stored
     * username arrives through here instead.
     */
    void prefillUsername(String username) {
        this.storedUsername = username;
    }

    /**
     * Supplies the app's own launcher icon, or {@code null} to fall back to the
     * NusaDesk mark the layout already carries. Package seam for the same
     * reason as {@link #prefillUsername(String)}.
     *
     * <p>Showing the app's own icon is what tells the user which app is asking
     * for a password — a mark belonging to the launcher would be ambiguous on
     * a screen that is otherwise entirely this app.</p>
     *
     * <p>Public because the shell owns the decoded tile icons and is in the
     * parent package, unlike the coordinator that lives beside this card.</p>
     */
    public void setAppIcon(Bitmap appIcon) {
        this.appIcon = appIcon;
        if (overlay != null) {
            applyAppIcon();
        }
    }

    private void applyAppIcon() {
        if (appMark == null) {
            return;
        }
        if (appIcon == null) {
            appMark.setImageResource(R.mipmap.ic_launcher);
        } else {
            appMark.setImageBitmap(appIcon);
        }
    }

    private void ensureOverlay() {
        if (overlay != null) {
            return;
        }
        overlay = LayoutInflater.from(root.getContext())
                .inflate(R.layout.widget_webapp_signin, root, false);
        card = overlay.findViewById(R.id.webapp_signin_card);
        appMark = overlay.findViewById(R.id.webapp_signin_icon);
        applyAppIcon();
        title = overlay.findViewById(R.id.webapp_signin_title);
        body = overlay.findViewById(R.id.webapp_signin_body);
        realm = overlay.findViewById(R.id.webapp_signin_realm);
        rejected = overlay.findViewById(R.id.webapp_signin_rejected);
        username = overlay.findViewById(R.id.webapp_signin_username);
        usernameError = overlay.findViewById(R.id.webapp_signin_username_error);
        password = overlay.findViewById(R.id.webapp_signin_password);
        passwordError = overlay.findViewById(R.id.webapp_signin_password_error);
        remember = overlay.findViewById(R.id.webapp_signin_remember);
        cancel = overlay.findViewById(R.id.webapp_signin_cancel);
        submit = overlay.findViewById(R.id.webapp_signin_submit);

        rejected.setText(R.string.webapp_signin_rejected);
        submit.setOnClickListener(view -> submit());
        cancel.setOnClickListener(view -> dispatchCancelled());
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });

        // Back is routed here by the host, not by a key listener on this
        // overlay: a Back key event goes to the focused view and, when that
        // view declines, straight to the Activity — it does not bubble through
        // parent OnKeyListeners. With the fields focused (which is where they
        // are, so the user can type), a listener here would never fire and a
        // cancelled prompt would leave its challenge unanswered. The host's
        // back contract calls handleBackPressed() instead.
        overlay.setFocusableInTouchMode(true);

        // Cap the card like the capability dialogs cap their window: full
        // width inside the screen margins, never wider than
        // nusadesk_dialog_max_width, recomputed on every surface resize.
        overlay.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> sizeCard());
        root.addView(overlay);
    }

    /** Applies the min(available, max_width) rule to the card column. */
    private void sizeCard() {
        if (card == null) {
            return;
        }
        int margin = root.getResources()
                .getDimensionPixelSize(R.dimen.nusadesk_dialog_screen_margin);
        int maxWidth = root.getResources()
                .getDimensionPixelSize(R.dimen.nusadesk_dialog_max_width);
        int width = root.getWidth();
        if (width <= 0) {
            return; // not laid out yet; the next layout pass retries
        }
        int target = Math.min(width - 2 * margin, maxWidth);
        if (target <= 0) {
            return;
        }
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params.width != target) {
            params.width = target;
            card.setLayoutParams(params);
        }
    }

    /**
     * Validates both fields with the domain normalizers so a malformed value
     * becomes an inline error on the field that caused it instead of an
     * exception or a wasted sign-in attempt. Only a valid pair reaches the
     * callback, and nothing here touches the password beyond handing it on.
     */
    private void submit() {
        usernameError.setVisibility(View.GONE);
        passwordError.setVisibility(View.GONE);
        String normalizedUsername;
        String rawPassword;
        try {
            normalizedUsername = WebAppSignInCredential
                    .normalizeUsername(username.getText().toString());
        } catch (IllegalArgumentException invalid) {
            usernameError.setText(R.string.webapp_signin_error_username);
            usernameError.setVisibility(View.VISIBLE);
            username.requestFocus();
            return;
        }
        try {
            rawPassword = WebAppSignInCredential
                    .normalizePassword(password.getText().toString());
        } catch (IllegalArgumentException invalid) {
            passwordError.setText(R.string.webapp_signin_error_password);
            passwordError.setVisibility(View.VISIBLE);
            password.requestFocus();
            return;
        }
        lastUsername = normalizedUsername;
        Callback current = callback;
        callback = null;
        if (current != null) {
            current.onSubmitted(
                    normalizedUsername, rawPassword, remember.isChecked());
        }
    }

    /**
     * Handles an Android Back gesture while this card is up.
     *
     * <p>Back is a cancel, not a silent swallow: the challenge this card is
     * answering is still pending, so leaving it unanswered would hang the load
     * with no visible reason. Called by the host's back routing rather than by
     * a key listener here — see the note where the overlay is created.</p>
     *
     * @return true when a pending challenge was cancelled; false when there was
     *         nothing of this card's to dismiss, so the shell keeps the gesture
     */
    public boolean handleBackPressed() {
        if (callback == null) {
            return false;
        }
        dispatchCancelled();
        return true;
    }

    private void dispatchCancelled() {
        Callback current = callback;
        callback = null;
        if (current != null) {
            current.onCancelled();
        }
    }

    /**
     * Makes the card inert while a submitted pair is being checked.
     *
     * <p>The card is deliberately <em>not</em> dismissed here. It waits for
     * {@link gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder#handlePageLoaded()}
     * to confirm the page really arrived, or for a fresh challenge to re-render
     * it as a rejection. Dismissing on submit and reopening on refusal is what
     * made a wrong password flash the whole surface twice.</p>
     *
     * @param submitting true to lock the form while the answer is in flight
     */
    public void setSubmitting(boolean submitting) {
        if (overlay == null) {
            return;
        }
        username.setEnabled(!submitting);
        password.setEnabled(!submitting);
        remember.setEnabled(!submitting);
        cancel.setEnabled(!submitting);
        submit.setEnabled(!submitting);
        submit.setText(submitting
                ? R.string.webapp_signin_checking
                : R.string.webapp_signin_submit);
    }
}
