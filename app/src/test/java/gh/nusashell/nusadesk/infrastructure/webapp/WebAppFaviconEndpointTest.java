package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The favicon URL is derived, never user input, so it is asserted without a
 * device: it must be the app's own generated origin plus exactly
 * {@code /favicon.ico}, whatever the stored record says.
 */
public class WebAppFaviconEndpointTest {

    private static WebAppDefinition webApp(int port) {
        return new WebAppDefinition(
                WebAppId.of("notebook"), "Notebook", null, port, 1, 1, 0);
    }

    @Test
    public void derivesTheFaviconUrlFromTheAppsOwnGeneratedOrigin() {
        assertEquals("http://127.0.0.1:8000/favicon.ico",
                WebAppFaviconEndpoint.forDefinition(webApp(8000)));
        assertEquals("http://127.0.0.1:1/favicon.ico",
                WebAppFaviconEndpoint.forDefinition(webApp(1)));
        assertEquals("http://127.0.0.1:65535/favicon.ico",
                WebAppFaviconEndpoint.forDefinition(webApp(65535)));
    }

    @Test
    public void theFaviconUrlIsTheEndpointUrlPlusTheOneFaviconPath() {
        WebAppDefinition definition = webApp(8080);

        assertEquals(definition.getEndpointUrl() + WebAppFaviconEndpoint.FAVICON_PATH,
                WebAppFaviconEndpoint.forDefinition(definition));
        assertEquals("favicon.ico", WebAppFaviconEndpoint.FAVICON_PATH);
    }

    @Test
    public void theStoredIconNeverChangesTheFaviconUrl() {
        WebAppDefinition withIcon = new WebAppDefinition(
                WebAppId.of("notebook"), "Notebook", "content://media/external/images/1",
                8000, 1, 1, 0);

        assertEquals(WebAppFaviconEndpoint.forDefinition(webApp(8000)),
                WebAppFaviconEndpoint.forDefinition(withIcon));
    }

    @Test
    public void anInvalidPortCannotReachThisCodeAtAll() {
        // The definition validates its own port, so no endpoint can be built from
        // a reserved or out-of-range one.
        try {
            webApp(GuestPortPolicy.RESERVED_GUEST_SSH_PORT);
            fail("expected the reserved terminal port to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reserved"));
        }
    }

    @Test
    public void rejectsAMissingDefinition() {
        try {
            WebAppFaviconEndpoint.forDefinition(null);
            fail("expected a null definition to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
