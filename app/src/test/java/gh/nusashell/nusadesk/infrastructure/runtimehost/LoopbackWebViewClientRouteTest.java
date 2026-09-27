package gh.nusashell.nusadesk.infrastructure.runtimehost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Pure routing and challenge tests for {@link LoopbackWebViewClient}. These
 * exercise the exact-origin decision mapping and the auth-challenge gate
 * without instantiating the Android {@code WebViewClient} (which requires an
 * Android runtime). The underlying classifications are covered by
 * {@code LoopbackNavigationPolicyTest} in the domain layer; here we assert the
 * WebViewClient maps each decision to the correct WebView action.
 */
public class LoopbackWebViewClientRouteTest {
    private final LoopbackNavigationPolicy policy =
            new LoopbackNavigationPolicy("http://127.0.0.1:8080");
    private final LoopbackAuthChallengePolicy authPolicy =
            new LoopbackAuthChallengePolicy("http://127.0.0.1:8080");

    @Test
    public void ownedOriginLoadsInWebView() {
        assertEquals(LoopbackWebViewClient.Action.LOAD_IN_WEBVIEW,
                LoopbackWebViewClient.route(policy, "http://127.0.0.1:8080/app"));
    }

    @Test
    public void ownedRootLoadsInWebView() {
        assertEquals(LoopbackWebViewClient.Action.LOAD_IN_WEBVIEW,
                LoopbackWebViewClient.route(policy, "http://127.0.0.1:8080/"));
    }

    @Test
    public void aboutBlankLoadsInWebView() {
        assertEquals(LoopbackWebViewClient.Action.LOAD_IN_WEBVIEW,
                LoopbackWebViewClient.route(policy, "about:blank"));
    }

    @Test
    public void externalHttpUrlIsDelegated() {
        assertEquals(LoopbackWebViewClient.Action.OPEN_EXTERNAL,
                LoopbackWebViewClient.route(policy, "https://example.com/path"));
    }

    @Test
    public void mailtoIsDelegated() {
        assertEquals(LoopbackWebViewClient.Action.OPEN_EXTERNAL,
                LoopbackWebViewClient.route(policy, "mailto:user@example.com"));
    }

    @Test
    public void differentLoopbackPortIsBlockedNotDelegated() {
        assertEquals(LoopbackWebViewClient.Action.BLOCK,
                LoopbackWebViewClient.route(policy, "http://127.0.0.1:9999/"));
    }

    @Test
    public void fileUrlIsBlocked() {
        assertEquals(LoopbackWebViewClient.Action.BLOCK,
                LoopbackWebViewClient.route(policy, "file:///data/local/tmp/secret"));
    }

    @Test
    public void javascriptUrlIsBlocked() {
        assertEquals(LoopbackWebViewClient.Action.BLOCK,
                LoopbackWebViewClient.route(policy, "javascript:alert(1)"));
    }

    @Test
    public void unparseableUrlIsBlocked() {
        assertEquals(LoopbackWebViewClient.Action.BLOCK,
                LoopbackWebViewClient.route(policy, "ht tp://broken"));
    }

    @Test
    public void ownedHostChallengeMayBeAnswered() {
        assertTrue(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "127.0.0.1"));
    }

    @Test
    public void nullHostChallengeIsRefused() {
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, null));
    }

    @Test
    public void anotherLoopbackHostChallengeIsRefused() {
        // The owned host is exactly 127.0.0.1: other loopback spellings and
        // other loopback addresses are not it.
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "localhost"));
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "127.0.0.2"));
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "::1"));
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "[::1]"));
    }

    @Test
    public void lanAddressChallengeIsRefused() {
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "192.168.1.10"));
    }

    @Test
    public void publicHostnameChallengeIsRefused() {
        assertFalse(LoopbackWebViewClient.mayAnswerChallenge(authPolicy, "example.com"));
    }

    @Test
    public void nullAuthPolicyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> LoopbackWebViewClient.mayAnswerChallenge(null, "127.0.0.1"));
    }
}
