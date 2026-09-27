package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * Round-trip and fail-closed coverage for the {@code username NUL password}
 * blob the credential vault stores. The format is the part that can silently
 * corrupt a password into a wrong-password loop, so it is exercised on the
 * plain JVM; the Keystore glue around it is covered by
 * {@code KeystoreWebAppCredentialStoreTest}.
 */
public class WebAppSignInCodecTest {

    private static final WebAppId APP_ID = WebAppId.of("app-1");

    private static WebAppSignInCredential roundTrip(WebAppSignInCredential credential) {
        return WebAppSignInCodec.decode(APP_ID, WebAppSignInCodec.encode(credential));
    }

    @Test
    public void aPlainPairRoundTrips() {
        WebAppSignInCredential credential =
                new WebAppSignInCredential(APP_ID, "alice", "s3cret");
        WebAppSignInCredential restored = roundTrip(credential);
        assertEquals(credential, restored);
        assertEquals("alice", restored.getUsername());
        assertEquals("s3cret", restored.getPassword());
    }

    @Test
    public void aUsernameContainingASpaceRoundTrips() {
        WebAppSignInCredential credential =
                new WebAppSignInCredential(APP_ID, "jane doe", "s3cret");
        assertEquals(credential, roundTrip(credential));
    }

    @Test
    public void aPasswordContainingNulRoundTripsByteForByte() {
        // NUL is the separator, so a password carrying it survives only because
        // the decode splits at the FIRST NUL and leaves the rest opaque.
        WebAppSignInCredential credential =
                new WebAppSignInCredential(APP_ID, "alice", "pa\0ss\0word");
        WebAppSignInCredential restored = roundTrip(credential);
        assertEquals(credential, restored);
        assertEquals("pa\0ss\0word", restored.getPassword());
    }

    @Test
    public void aPasswordContainingNewlinesRoundTripsByteForByte() {
        WebAppSignInCredential credential =
                new WebAppSignInCredential(APP_ID, "alice", "line1\nline2\r\n");
        WebAppSignInCredential restored = roundTrip(credential);
        assertEquals(credential, restored);
        assertEquals("line1\nline2\r\n", restored.getPassword());
    }

    @Test
    public void anEmptyOrMissingBlobDecodesToNoCredential() {
        assertNull(WebAppSignInCodec.decode(APP_ID, null));
        assertNull(WebAppSignInCodec.decode(APP_ID, new byte[0]));
    }

    @Test
    public void aBlobWithNoSeparatorDecodesToNoCredential() {
        assertNull(WebAppSignInCodec.decode(
                APP_ID, "username".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aBlobWithAnEmptyUsernameDecodesToNoCredential() {
        assertNull(WebAppSignInCodec.decode(
                APP_ID, "\0password".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aBlobWithAnEmptyPasswordDecodesToNoCredential() {
        assertNull(WebAppSignInCodec.decode(
                APP_ID, "user\0".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aBlobThatIsNotUtf8TextDecodesToNoCredential() {
        // Lone continuation bytes decode to replacement characters with no NUL
        // separator; the point is that decode returns null rather than throwing.
        assertNull(WebAppSignInCodec.decode(
                APP_ID, new byte[]{(byte) 0x80, (byte) 0xFE, (byte) 0xFD}));
    }

    @Test
    public void credentialToStringRevealsNeitherField() {
        WebAppSignInCredential credential = new WebAppSignInCredential(
                APP_ID, "alice.the.admin", "s3cret-Qm8xT2vL9wR4");
        String rendered = credential.toString();
        assertFalse("username must never reach toString()",
                rendered.contains("alice.the.admin"));
        assertFalse("password must never reach toString()",
                rendered.contains("s3cret-Qm8xT2vL9wR4"));
    }

    @Test
    public void wipeZeroesTheBlob() {
        byte[] blob = WebAppSignInCodec.encode(
                new WebAppSignInCredential(APP_ID, "alice", "s3cret"));
        WebAppSignInCodec.wipe(blob);
        assertArrayEquals(new byte[blob.length], blob);
    }

    @Test
    public void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCodec.encode(null));
        assertThrows(IllegalArgumentException.class,
                () -> WebAppSignInCodec.decode(null, new byte[]{'x'}));
    }
}
