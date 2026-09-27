package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackNavigationPolicy;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackWebViewClient;
import gh.nusashell.nusadesk.infrastructure.runtimehost.WebViewFailureListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Tests for the per-app WebView boundary. The origin decisions are pure and are
 * exercised directly; the {@code WebViewClient} wiring tests run under
 * Robolectric because instantiating the client's {@code WebViewClient}
 * superclass needs an Android runtime.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppWebViewBoundaryTest {
    private final WebAppWebViewBoundary boundary = boundaryFor(8080);
    private final WebAppWebViewBoundary otherApp = boundaryFor(9090);

    private static final WebViewFailureListener NO_FAILURES = new WebViewFailureListener() {
        @Override
        public void onLoadError(int errorCode, String description, String failingUrl) {
        }

        @Override
        public void onHttpError(int statusCode, String failingUrl) {
        }

        @Override
        public void onBlockedNavigation(String url) {
        }

        @Override
        public void onRenderProcessGone() {
        }
    };

    private static WebAppWebViewBoundary boundaryFor(int guestPort) {
        return new WebAppWebViewBoundary(new WebAppDefinition(
                WebAppId.of("app-" + guestPort), "Notes", null, guestPort, 0L, 0L, 0));
    }

    @Test
    public void loadUrlIsTheGeneratedEndpoint() {
        assertEquals("http://127.0.0.1:8080/", boundary.getLoadUrl());
        assertEquals("http://127.0.0.1:9090/", otherApp.getLoadUrl());
    }

    @Test
    public void theOwnedOriginAndItsOwnResourcesLoadInTheWebView() {
        assertEquals(LoopbackNavigationPolicy.Decision.OWNED, boundary.classify("http://127.0.0.1:8080/"));
        assertEquals(LoopbackNavigationPolicy.Decision.OWNED,
                boundary.classify("http://127.0.0.1:8080/index.html"));
        assertEquals(LoopbackNavigationPolicy.Decision.OWNED,
                boundary.classify("http://127.0.0.1:8080/assets/app.js?v=2"));
        assertEquals(LoopbackNavigationPolicy.Decision.OWNED,
                boundary.classify("http://127.0.0.1:8080/api/items#top"));
        assertEquals(LoopbackNavigationPolicy.Decision.OWNED, boundary.classify("about:blank"));
    }

    @Test
    public void anotherAppsPortIsBlockedNotHandedToTheBrowser() {
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("http://127.0.0.1:9090/"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("http://127.0.0.1:1/"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("http://127.0.0.1:65535/"));
    }

    @Test
    public void theSamePortUnderAnotherLoopbackAliasIsStillAnotherOrigin() {
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("http://localhost:8080/"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("http://[::1]:8080/"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("https://127.0.0.1:8080/"));
    }

    @Test
    public void externalHttpsLinksLeaveTheWebViewForTheSystemBrowser() {
        assertEquals(LoopbackNavigationPolicy.Decision.EXTERNAL,
                boundary.classify("https://example.com/docs"));
        assertEquals(LoopbackNavigationPolicy.Decision.EXTERNAL,
                boundary.classify("http://example.com/docs"));
        assertEquals(LoopbackNavigationPolicy.Decision.EXTERNAL,
                boundary.classify("mailto:user@example.com"));
    }

    @Test
    public void fileContentAndScriptUrlsAreBlocked() {
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("file:///data/local/tmp/secret"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("file:///android_asset/terminal/terminal.html"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("content://com.android.providers.media.documents/document/image%3A1"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("javascript:alert(1)"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("data:text/html,<h1>x</h1>"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("blob:http://127.0.0.1:8080/abc"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify("about:config"));
    }

    @Test
    public void malformedAndMissingUrlsAreBlocked() {
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED, boundary.classify(null));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED, boundary.classify(""));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED, boundary.classify("ht tp://broken"));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED, boundary.classify("127.0.0.1:8080"));
    }

    @Test
    public void oneBoundaryNeverAcceptsAnotherAppsOrigin() {
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                otherApp.classify(boundary.getLoadUrl()));
        assertEquals(LoopbackNavigationPolicy.Decision.BLOCKED,
                boundary.classify(otherApp.getLoadUrl()));
    }

    @Test
    public void rejectsANullDefinition() {
        try {
            new WebAppWebViewBoundary(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void exposesTheDefinitionItGuards() {
        assertNotNull(boundary.getDefinition());
        assertEquals(8080, boundary.getDefinition().getGuestPort());
    }

    @Test
    public void newWebViewClientBuildsTheBoundaryClient() {
        LoopbackWebViewClient client = boundary.newWebViewClient(
                uri -> { }, NO_FAILURES, (host, realm, answer) -> { });
        assertNotNull(client);
    }

    @Test
    public void newWebViewClientRejectsMissingCollaborators() {
        try {
            boundary.newWebViewClient(null, NO_FAILURES, (host, realm, answer) -> { });
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            boundary.newWebViewClient(uri -> { }, null, (host, realm, answer) -> { });
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            boundary.newWebViewClient(uri -> { }, NO_FAILURES, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
