package gh.nusashell.nusadesk.presentation;

/**
 * Whether a shell surface hosts text input the soft keyboard serves.
 *
 * <p>Declared per surface so the host can tell legitimate input focus from a
 * stray one when a transition settles the keyboard. It does not gate the
 * dismiss: {@link MainActivity} hides the IME on every surface switch, because
 * no screen opens mid-typing and input-owning surfaces re-establish focus on
 * their own once shown. For a surface that owns no text input the host also
 * sweeps focus out of it, so a hidden field cannot hold focus behind another
 * screen; input-owning surfaces keep their focus (a WebView's focused field,
 * a half-filled form) for when the user comes back.</p>
 */
public interface SoftKeyboardAware {

    /** True when this surface contains a text field the IME may serve. */
    boolean ownsTextInput();
}
