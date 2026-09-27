package gh.nusashell.nusadesk.infrastructure.runtimehost;

import android.net.Uri;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>HTTP authentication challenges are handled the same way. The client
 * overrides {@code onReceivedHttpAuthRequest} so the platform default — which
 * would consult {@code WebViewDatabase} — never runs: the database persists
 * credentials in plaintext app-private storage, so a web app's sign-in pair
 * lives exclusively in the Keystore vault behind {@link HttpAuthResponder}
 * (ADR-0058). A challenge is handed to the responder only when the pure
 * {@link #mayAnswerChallenge(LoopbackAuthChallengePolicy, String)} decision
 * accepts its host as the owned host; every other challenge is cancelled. The
 * host check is the strongest the public SDK allows: the callback supplies a
 * host and a realm but no port, so a challenge cannot be pinned to the app's
 * own port — that limit is documented in {@link LoopbackAuthChallengePolicy}
 * and ADR-0058 rather than worked around.</p>
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
    private final LoopbackAuthChallengePolicy authPolicy;
    private final HttpAuthResponder authResponder;

    /**
     * @param policy           classifies URLs against the exact owned loopback origin
     * @param externalHandler  receives external URLs to open outside the WebView
     * @param failureListener  receives blocked-navigation and load/render failure reports
     * @param authPolicy       decides which HTTP auth challenges may be answered
     * @param authResponder    answers an accepted challenge, asynchronously and
     *                         exactly once
     */
    public LoopbackWebViewClient(
            LoopbackNavigationPolicy policy,
            ExternalLinkHandler externalHandler,
            WebViewFailureListener failureListener,
            LoopbackAuthChallengePolicy authPolicy,
            HttpAuthResponder authResponder) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (externalHandler == null) {
            throw new IllegalArgumentException("externalHandler must not be null");
        }
        if (failureListener == null) {
            throw new IllegalArgumentException("failureListener must not be null");
        }
        if (authPolicy == null) {
            throw new IllegalArgumentException("authPolicy must not be null");
        }
        if (authResponder == null) {
            throw new IllegalArgumentException("authResponder must not be null");
        }
        this.policy = policy;
        this.externalHandler = externalHandler;
        this.failureListener = failureListener;
        this.authPolicy = authPolicy;
        this.authResponder = authResponder;
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

    /**
     * Pure auth-challenge decision used by this client, in the same style as
     * {@link #route(LoopbackNavigationPolicy, String)}: no Android dependency,
     * so it is unit-testable without an Android runtime.
     *
     * @param authPolicy    the challenge policy bound to the owned origin
     * @param challengeHost the host passed to {@code onReceivedHttpAuthRequest}
     * @return {@code true} only when the challenge names the owned host and may
     *         be answered; {@code false} for a null host or anything else
     */
    public static boolean mayAnswerChallenge(
            LoopbackAuthChallengePolicy authPolicy, String challengeHost) {
        if (authPolicy == null) {
            throw new IllegalArgumentException("authPolicy must not be null");
        }
        return authPolicy.classify(challengeHost) == LoopbackAuthChallengePolicy.Decision.OWNED;
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

    /**
     * Hands an HTTP auth challenge to the responder when it names the owned
     * host, and cancels it otherwise. Overriding this callback is what keeps
     * the framework away from {@code WebViewDatabase}, which would store the
     * pair in plaintext.
     *
     * <p>The {@link HttpAuthResponder.Answer} given to the responder is guarded
     * by an {@link AtomicBoolean} so exactly one terminal call reaches the
     * handler: the first of {@code proceed}/{@code cancel} wins, a second call
     * is ignored, and a responder that throws synchronously leaves the
     * challenge cancelled rather than leaving the load hung.</p>
     */
    @Override
    public void onReceivedHttpAuthRequest(
            WebView view, HttpAuthHandler handler, String host, String realm) {
        if (!mayAnswerChallenge(authPolicy, host)) {
            handler.cancel();
            return;
        }
        AtomicBoolean answered = new AtomicBoolean(false);
        HttpAuthResponder.Answer answer = new HttpAuthResponder.Answer() {
            @Override
            public void proceed(String username, String password) {
                if (answered.compareAndSet(false, true)) {
                    handler.proceed(username, password);
                }
            }

            @Override
            public void cancel() {
                if (answered.compareAndSet(false, true)) {
                    handler.cancel();
                }
            }
        };
        try {
            authResponder.onChallenge(host, realm == null ? "" : realm, answer);
        } catch (RuntimeException responderFailed) {
            answer.cancel();
        }
    }
}
