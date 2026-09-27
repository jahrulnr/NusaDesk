package gh.nusashell.nusadesk.domain.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The sign-in pair's own invariants. What matters here is that a value the
 * wire format cannot carry is refused at construction instead of being stored
 * and then failing forever at the server.
 */
public class WebAppSignInCredentialTest {

    private static final WebAppId APP = WebAppId.of("app-1");

    @Test
    public void aNormalPairKeepsBothHalvesExactly() {
        WebAppSignInCredential credential =
                new WebAppSignInCredential(APP, "root", "  spaces kept  ");

        assertEquals(APP, credential.getWebAppId());
        assertEquals("root", credential.getUsername());
        assertEquals("the password is opaque and is never trimmed",
                "  spaces kept  ", credential.getPassword());
    }

    @Test
    public void theUsernameIsTrimmed() {
        assertEquals("root",
                new WebAppSignInCredential(APP, "  root  ", "pw").getUsername());
    }

    @Test
    public void aColonInTheUsernameIsRefused() {
        // Basic auth sends "user-id:password" and the server splits at the
        // first colon, so "a:b" with password "pw" would arrive as user "a"
        // and password "b:pw" — permanently unauthenticable, with a wrong
        // password as the only visible symptom. Refusing it here turns that
        // into an inline error on the field that caused it.
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizeUsername("a:b"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebAppSignInCredential(APP, "root:admin", "pw"));
    }

    @Test
    public void aColonInThePasswordIsFine() {
        // Only the username is split off; the password is the remainder and
        // may contain anything at all.
        assertEquals("b:c",
                new WebAppSignInCredential(APP, "root", "b:c").getPassword());
    }

    @Test
    public void malformedNamesAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizeUsername(null));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizeUsername("   "));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizeUsername("root\nadmin"));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizeUsername(
                        repeat('a', WebAppSignInCredential.MAX_USERNAME_LENGTH + 1)));
    }

    @Test
    public void malformedPasswordsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizePassword(null));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizePassword(""));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizePassword(
                        repeat('b', WebAppSignInCredential.MAX_PASSWORD_LENGTH + 1)));
    }

    @Test
    public void anEmptyPasswordIsNotTheSameAsASpace() {
        assertEquals(" ", WebAppSignInCredential.normalizePassword(" "));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCredential.normalizePassword(""));
    }

    @Test
    public void toStringNeverCarriesEitherHalf() {
        WebAppSignInCredential credential = new WebAppSignInCredential(APP, "root", "hunter2");

        String rendered = credential.toString();
        assertFalse(rendered.contains("root"));
        assertFalse(rendered.contains("hunter2"));
        assertTrue("the id is safe to name and is what makes the line useful",
                rendered.contains("app-1"));
    }

    @Test
    public void aMissingAppIdIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebAppSignInCredential(null, "root", "pw"));
    }

    private static String repeat(char character, int times) {
        StringBuilder text = new StringBuilder(times);
        for (int index = 0; index < times; index++) {
            text.append(character);
        }
        return text.toString();
    }
}
