package gh.nusashell.nusadesk.infrastructure.runtimehost;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure routing tests for {@link LoopbackWebViewClient#route}. These exercise the
 * exact-origin decision mapping without instantiating the Android
 * {@code WebViewClient} (which requires an Android runtime). The underlying
 * classification is covered by {@code LoopbackNavigationPolicyTest} in the
 * domain layer; here we assert the WebViewClient maps each decision to the
 * correct WebView action.
 */
public class LoopbackWebViewClientRouteTest {
    private final LoopbackNavigationPolicy policy =
            new LoopbackNavigationPolicy("http://127.0.0.1:8080");

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
}
