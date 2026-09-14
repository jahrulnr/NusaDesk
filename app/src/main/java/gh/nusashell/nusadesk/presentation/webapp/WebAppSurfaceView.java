package gh.nusashell.nusadesk.presentation.webapp;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.runtimehost.ExternalLinkHandler;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackWebViewClient;
import gh.nusashell.nusadesk.infrastructure.runtimehost.WebViewFailureListener;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppReadinessObserver;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppWebViewBoundary;

import java.util.concurrent.ExecutorService;

/**
 * One user web app, opened as a maximized surface.
 *
 * <p>The order is the contract (ADR-0014, {@code android-webview-hosting}): the
 * app's loopback endpoint is observed asynchronously <em>first</em>, and only a
 * reachable endpoint is handed to a WebView that is bound to
 * {@link WebAppWebViewBoundary}. Loading before the probe would paint a browser
 * error page instead of an honest "not running yet" state, and the boundary is
 * what keeps the WebView on exactly {@code http://127.0.0.1:<port>/}: a
 * different loopback port is a different origin and is blocked, external links
 * leave for the system browser, and no JavaScript interface is registered.</p>
 *
 * <p>Every state is explicit and recoverable — probing, unreachable, failed
 * (blocked navigation or a dead renderer), and loaded — and every non-loaded
 * state offers the one action that can change it. Reachability is not health:
 * the surface never claims the app itself is working.</p>
 *
 * <p>Surface lifecycle: the WebView is created lazily on the first reachable
 * probe and retained while the surface is switched by visibility, so scrollback
 * and in-page state survive a trip to the launcher. Re-binding to the same
 * definition keeps the live page; re-binding to a changed definition, or
 * retrying, starts a fresh probe and a fresh load.</p>
 */
public final class WebAppSurfaceView extends FrameLayout {

    /** What the surface currently shows. */
    public enum State { PROBING, UNREACHABLE, FAILED, LOADED }

    /**
     * Receives the moment this app's own endpoint proved reachable, before the
     * page is loaded.
     *
     * <p>Reachability is the only moment anything else can honestly ask that
     * endpoint for something — the launcher uses it to pick up the app's favicon
     * for its tile — so the surface reports it instead of leaving the launcher to
     * guess when the app started.</p>
     */
    public interface ReachabilityListener {
        void onEndpointReachable(WebAppDefinition definition);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WebAppReadinessObserver observer = new WebAppReadinessObserver();

    private FrameLayout viewContainer;
    private ScrollView statePanel;
    private TextView stateTitle;
    private TextView stateBody;
    private ProgressBar stateProgress;
    private Button stateAction;

    private WebAppDefinition definition;
    private WebAppWebViewBoundary boundary;
    private ExecutorService probeExecutor;
    private WebView webView;
    private State state = State.PROBING;
    private String failureDetail;
    private boolean released;
    private ReachabilityListener reachabilityListener;

    public WebAppSurfaceView(Context context) {
        super(context);
        init();
    }

    public WebAppSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_web_app_surface, this, true);
        viewContainer = findViewById(R.id.webapp_view_container);
        statePanel = findViewById(R.id.webapp_state_panel);
        stateTitle = findViewById(R.id.webapp_state_title);
        stateBody = findViewById(R.id.webapp_state_body);
        stateProgress = findViewById(R.id.webapp_state_progress);
        stateAction = findViewById(R.id.webapp_state_action);
        stateAction.setOnClickListener(view -> reload());
        render();
    }

    /** The app this surface shows, or {@code null} before it is bound. */
    public WebAppDefinition getDefinition() {
        return definition;
    }

    /** The state currently rendered, so the host never has to guess. */
    public State getState() {
        return state;
    }

    /** Receives each transition to a reachable endpoint; may be called repeatedly. */
    public void setOnReachabilityListener(ReachabilityListener listener) {
        this.reachabilityListener = listener;
    }

    /**
     * Binds this surface to one registered app and starts its readiness
     * observation. A surface that is already showing this exact app keeps its
     * live page, so returning from the launcher does not throw the app's state
     * away; anything else re-probes and reloads.
     *
     * @param definition the registered app
     * @param executor   background executor for the bounded probe
     */
    public void bind(WebAppDefinition definition, ExecutorService executor) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        boolean sameApp = definition.equals(this.definition);
        this.definition = definition;
        this.boundary = new WebAppWebViewBoundary(definition);
        this.probeExecutor = executor;
        if (sameApp && state == State.LOADED && webView != null) {
            render();
            return;
        }
        startProbe();
    }

    /** Re-probes and reloads; the only action any state offers. */
    public void reload() {
        if (definition == null) {
            return;
        }
        // A retry starts from a clean renderer: a crashed or blocked page must
        // not be reused.
        destroyWebView();
        startProbe();
    }

    private void startProbe() {
        state = State.PROBING;
        failureDetail = null;
        render();
        ExecutorService executor = probeExecutor;
        if (executor != null) {
            executor.execute(this::probe);
        }
    }

    /** Observes the endpoint once, off the main thread. */
    private void probe() {
        WebAppDefinition target = definition;
        if (target == null) {
            return;
        }
        WebAppReadinessObserver.Result result = observer.observe(target.getGuestPort());
        mainHandler.post(() -> onProbeResult(target, result));
    }

    private void onProbeResult(WebAppDefinition target, WebAppReadinessObserver.Result result) {
        if (released || definition == null || !definition.getId().equals(target.getId())) {
            return; // a newer bind superseded this probe
        }
        if (result.isReachable()) {
            state = State.LOADED;
            render();
            if (reachabilityListener != null) {
                reachabilityListener.onEndpointReachable(target);
            }
            loadWebView();
            return;
        }
        failureDetail = result.getDetail();
        state = State.UNREACHABLE;
        render();
    }

    // ---- WebView boundary ----

    private void loadWebView() {
        WebAppWebViewBoundary current = boundary;
        if (current == null) {
            return;
        }
        if (webView == null) {
            webView = new WebView(getContext());
            current.applySettings(webView);
            webView.setWebViewClient(new SurfaceWebViewClient(
                    current.newWebViewClient(externalLinkHandler(), failureListener())));
            viewContainer.addView(webView, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        webView.loadUrl(current.getLoadUrl());
    }

    /**
     * Hands a URL classified as external to the system browser. Presentation owns
     * this because it is the layer allowed to start an Activity.
     */
    private ExternalLinkHandler externalLinkHandler() {
        return uri -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException noHandler) {
                // No browser or app can open it; the surface stays where it is.
            }
        };
    }

    /**
     * Turns the boundary's failure reports into surface states: a blocked
     * navigation or a dead renderer is reported instead of leaving a silent
     * blank page behind.
     */
    private WebViewFailureListener failureListener() {
        return new WebViewFailureListener() {
            @Override
            public void onLoadError(int errorCode, String description, String failingUrl) {
                showFailure(description);
            }

            @Override
            public void onHttpError(int statusCode, String failingUrl) {
                showFailure("HTTP " + statusCode);
            }

            @Override
            public void onBlockedNavigation(String url) {
                showFailure(getContext().getString(R.string.webapp_state_blocked));
            }

            @Override
            public void onRenderProcessGone() {
                showFailure(getContext().getString(R.string.webapp_state_crashed));
            }
        };
    }

    private void showFailure(String detail) {
        if (released) {
            return;
        }
        mainHandler.post(() -> {
            failureDetail = detail;
            state = State.FAILED;
            render();
        });
    }

    /**
     * Keeps the boundary's security decisions and adds only what the surface
     * needs: a main-frame filter, so a missing subresource cannot replace a
     * working page with a failure panel. The origin policy itself stays entirely
     * in {@link LoopbackWebViewClient}, which this delegates every decision to.
     */
    private final class SurfaceWebViewClient extends WebViewClient {
        private final LoopbackWebViewClient delegate;

        SurfaceWebViewClient(LoopbackWebViewClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return delegate.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                delegate.onReceivedError(view, request, error);
            }
        }

        @Override
        public void onReceivedHttpError(
                WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request != null && request.isForMainFrame()) {
                delegate.onReceivedHttpError(view, request, response);
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return delegate.onRenderProcessGone(view, detail);
        }
    }

    // ---- Rendering ----

    private void render() {
        WebAppDefinition current = definition;
        if (current == null) {
            statePanel.setVisibility(GONE);
            return;
        }
        String name = current.getDisplayName();
        stateAction.setVisibility(GONE);
        stateProgress.setVisibility(GONE);
        switch (state) {
            case PROBING:
                statePanel.setVisibility(VISIBLE);
                stateTitle.setText(getContext().getString(R.string.webapp_state_opening, name));
                stateBody.setText("");
                stateProgress.setVisibility(VISIBLE);
                break;
            case UNREACHABLE:
                statePanel.setVisibility(VISIBLE);
                stateTitle.setText(getContext().getString(
                        R.string.webapp_state_unreachable_title, name));
                stateBody.setText(getContext().getString(
                        R.string.webapp_state_unreachable_body, current.getGuestPort()));
                stateAction.setVisibility(VISIBLE);
                break;
            case FAILED:
                statePanel.setVisibility(VISIBLE);
                stateTitle.setText(getContext().getString(
                        R.string.webapp_state_failed_title, name));
                stateBody.setText(getContext().getString(
                        R.string.webapp_state_failed_body, failureText()));
                stateAction.setVisibility(VISIBLE);
                break;
            case LOADED:
            default:
                statePanel.setVisibility(GONE);
                break;
        }
    }

    private String failureText() {
        return failureDetail == null || failureDetail.trim().isEmpty()
                ? getContext().getString(R.string.terminal_failed_unknown)
                : failureDetail;
    }

    /** Tears the WebView down. Idempotent; also called on detach. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        destroyWebView();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        viewContainer.removeView(webView);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setWebViewClient(null);
        webView.destroy();
        webView = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
