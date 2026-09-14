package gh.nusashell.nusadesk.infrastructure.runtimehost;

import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Exact-origin {@link WebViewClient} for the owned loopback runtime.
 *
 * <p>Delegates every navigation decision to the pure, unit-tested
 * {@link LoopbackNavigationPolicy} so the security-relevant classification stays
 * deterministic and free of Android types. The client only translates a policy
 * decision into WebView actions:</p>
 *
 * <ul>
 *   <li>{@link LoopbackNavigationPolicy.Decision#OWNED} loads inside the WebView.</li>
 *   <li>{@link LoopbackNavigationPolicy.Decision#EXTERNAL} is cancelled here and
 *       handed to {@link ExternalLinkHandler} so the system browser (or matching
 *       app) opens it outside the WebView, per {@code AGENTS.md}.</li>
 *   <li>{@link LoopbackNavigationPolicy.Decision#BLOCKED} is cancelled and reported
 *       through {@link WebViewFailureListener#onBlockedNavigation(String)} so the
 *       UI can render an explicit blocked state instead of a silent failure.</li>
 * </ul>
 *
 * <p>This client intentionally exposes <em>no</em> {@code addJavascriptInterface}
 * bridge. A future minimal, origin-checked bridge would be added separately and
 * documented; the foundation ships none. Load/resource/render failures are
 * forwarded to {@link WebViewFailureListener} with codes and short descriptions
 * only, never response bodies or secrets.</p>
 *
 * <p>The Android-dependent glue is kept thin; the routing logic is exposed as the
 * pure static {@link #route(LoopbackNavigationPolicy, String)} method so it can be
 * unit-tested without an Android runtime.</p>
 */
public final class LoopbackWebViewClient extends WebViewClient {
    /** Action the WebViewClient takes for a given URL, derived from the navigation policy. */
    public enum Action {
        /** Let the WebView load the URL (the exact owned origin or about:blank). */
        LOAD_IN_WEBVIEW,
        /** Cancel in-WebView loading and hand the URL to the external handler. */
        OPEN_EXTERNAL,
        /** Cancel and report a blocked navigation. */
        BLOCK
    }

    private final LoopbackNavigationPolicy policy;
    private final ExternalLinkHandler externalHandler;
    private final WebViewFailureListener failureListener;

    /**
     * @param policy           classifies URLs against the exact owned loopback origin
     * @param externalHandler  receives external URLs to open outside the WebView
     * @param failureListener  receives blocked-navigation and load/render failure reports
     */
    public LoopbackWebViewClient(
            LoopbackNavigationPolicy policy,
            ExternalLinkHandler externalHandler,
            WebViewFailureListener failureListener) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (externalHandler == null) {
            throw new IllegalArgumentException("externalHandler must not be null");
        }
        if (failureListener == null) {
            throw new IllegalArgumentException("failureListener must not be null");
        }
        this.policy = policy;
        this.externalHandler = externalHandler;
        this.failureListener = failureListener;
    }

    /**
     * Pure routing decision used by this client. Has no Android dependency so it
     * can be exercised directly from a JUnit test.
     *
     * @param policy the navigation policy that classifies the URL
     * @param url    the raw URL to route
     * @return the action the WebViewClient should take; never null
     */
    public static Action route(LoopbackNavigationPolicy policy, String url) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        switch (policy.classify(url)) {
            case OWNED:
                return Action.LOAD_IN_WEBVIEW;
            case EXTERNAL:
                return Action.OPEN_EXTERNAL;
            case BLOCKED:
            default:
                return Action.BLOCK;
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        String url = uri == null ? "" : uri.toString();
        switch (route(policy, url)) {
            case OPEN_EXTERNAL:
                if (uri != null) {
                    externalHandler.openExternal(uri);
                }
                return true;
            case BLOCK:
                failureListener.onBlockedNavigation(url);
                return true;
            case LOAD_IN_WEBVIEW:
            default:
                return false;
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        String url = request.getUrl() == null ? "" : request.getUrl().toString();
        int code = error.getErrorCode();
        CharSequence description = error.getDescription();
        failureListener.onLoadError(code, description == null ? "" : description.toString(), url);
    }

    @Override
    public void onReceivedHttpError(
            WebView view, WebResourceRequest request, WebResourceResponse response) {
        String url = request.getUrl() == null ? "" : request.getUrl().toString();
        failureListener.onHttpError(response.getStatusCode(), url);
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        failureListener.onRenderProcessGone();
        return true;
    }
}
