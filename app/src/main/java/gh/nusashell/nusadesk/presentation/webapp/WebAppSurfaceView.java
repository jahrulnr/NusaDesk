package gh.nusashell.nusadesk.presentation.webapp;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Receives tab creation, selection, and close events on the UI thread. */
    public interface TabListener {
        void onTabsChanged(List<WebAppTabStack.Tab> tabs, String selectedTabId);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WebAppReadinessObserver observer = new WebAppReadinessObserver();
    private final WebAppTabStack tabStack = new WebAppTabStack();
    private final Map<String, WebView> tabWebViews = new LinkedHashMap<>();

    private FrameLayout viewContainer;
    private ScrollView statePanel;
    private TextView stateTitle;
    private TextView stateBody;
    private ProgressBar stateProgress;
    private Button stateAction;

    private WebAppDefinition definition;
    private WebAppWebViewBoundary boundary;
    private ExecutorService probeExecutor;
    /** Alias for the permanent root tab, retained for existing surface logic. */
    private WebView webView;
    private State state = State.PROBING;
    private String failureDetail;
    private boolean released;
    private ReachabilityListener reachabilityListener;
    private TabListener tabListener;

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

    /** Receives tab changes so the shell can render a tab switcher. */
    public void setOnTabListener(TabListener listener) {
        this.tabListener = listener;
        notifyTabsChanged();
    }

    /** Returns an immutable snapshot of this app's live tabs. UI thread only. */
    public List<WebAppTabStack.Tab> getTabs() {
        return tabStack.tabs();
    }

    /** Returns the selected tab id. UI thread only. */
    public String getSelectedTabId() {
        return tabStack.selectedTabId();
    }

    /** Returns a display title for a tab, falling back to its stable id. */
    public String getTabTitle(String tabId) {
        if (WebAppTabStack.ROOT_TAB_ID.equals(tabId) && definition != null) {
            return definition.getDisplayName();
        }
        WebView selected = tabWebViews.get(tabId);
        if (selected != null && selected.getTitle() != null
                && !selected.getTitle().trim().isEmpty()) {
            return selected.getTitle();
        }
        return tabId == null ? "" : tabId;
    }

    /** Selects a live tab and switches its WebView visibility. UI thread only. */
    public boolean selectTab(String tabId) {
        if (!tabStack.select(tabId)) {
            return false;
        }
        showSelectedTab();
        notifyTabsChanged();
        return true;
    }

    /** Closes a child tab. The root tab is permanent. UI thread only. */
    public boolean closeTab(String tabId) {
        if (tabId == null || WebAppTabStack.ROOT_TAB_ID.equals(tabId)
                || !tabStack.contains(tabId)) {
            return false;
        }
        WebView child = tabWebViews.get(tabId);
        boolean closed = tabStack.close(tabId);
        if (!closed) {
            return false;
        }
        destroyTabWebView(tabId, child);
        showSelectedTab();
        notifyTabsChanged();
        return true;
    }

    /**
     * Applies the surface Back contract: page history first, then child-tab
     * close, then false when the root has no history and the shell should return
     * to the launcher. UI thread only.
     */
    public boolean handleBack() {
        WebView selected = tabWebViews.get(tabStack.selectedTabId());
        if (selected != null && selected.canGoBack()) {
            selected.goBack();
            return true;
        }
        String selectedId = tabStack.selectedTabId();
        if (!WebAppTabStack.ROOT_TAB_ID.equals(selectedId)) {
            return closeTab(selectedId);
        }
        return false;
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
        if (!sameApp) {
            closeAllChildTabs();
            destroyWebView();
        }
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
        closeAllChildTabs();
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
            webView = createTabWebView(WebAppTabStack.ROOT_TAB_ID, current);
            tabWebViews.put(WebAppTabStack.ROOT_TAB_ID, webView);
            viewContainer.addView(webView, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        webView.loadUrl(current.getLoadUrl());
        showSelectedTab();
        notifyTabsChanged();
    }

    /** Creates one WebView with the same boundary and window policy as its root. */
    private WebView createTabWebView(String tabId, WebAppWebViewBoundary current) {
        WebView created = new WebView(getContext());
        current.applySettings(created);
        created.getSettings().setSupportMultipleWindows(true);
        created.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        created.setWebViewClient(new SurfaceWebViewClient(
                current.newWebViewClient(externalLinkHandler(), failureListener(tabId))));
        created.setWebChromeClient(new SurfaceWebChromeClient(tabId, current));
        return created;
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
    private WebViewFailureListener failureListener(String tabId) {
        if (WebAppTabStack.ROOT_TAB_ID.equals(tabId)) {
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
        return new WebViewFailureListener() {
            @Override
            public void onLoadError(int errorCode, String description, String failingUrl) {
                closeTab(tabId);
            }

            @Override
            public void onHttpError(int statusCode, String failingUrl) {
                closeTab(tabId);
            }

            @Override
            public void onBlockedNavigation(String url) {
                closeTab(tabId);
            }

            @Override
            public void onRenderProcessGone() {
                closeTab(tabId);
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
     * Owns the WebView window contract. Every accepted child is a tab in the
     * same exact-origin surface, never an untracked overlay or Activity.
     */
    private final class SurfaceWebChromeClient extends WebChromeClient {
        private final String ownerTabId;
        private final WebAppWebViewBoundary webBoundary;

        SurfaceWebChromeClient(String ownerTabId, WebAppWebViewBoundary webBoundary) {
            this.ownerTabId = ownerTabId;
            this.webBoundary = webBoundary;
        }

        @Override
        public boolean onCreateWindow(
                WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (released || !isUserGesture || tabStack.isFull()
                    || !tabStack.contains(ownerTabId) || resultMsg == null
                    || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                return false;
            }
            WebAppTabStack.Tab tab = tabStack.addTab();
            if (tab == null) {
                return false;
            }
            WebView child = createTabWebView(tab.getId(), webBoundary);
            tabWebViews.put(tab.getId(), child);
            viewContainer.addView(child, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            showSelectedTab();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(child);
            try {
                resultMsg.sendToTarget();
            } catch (RuntimeException deliveryFailed) {
                closeTab(tab.getId());
                return false;
            }
            notifyTabsChanged();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            String tabId = tabIdFor(window);
            if (tabId != null && !WebAppTabStack.ROOT_TAB_ID.equals(tabId)) {
                closeTab(tabId);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            notifyTabsChanged();
        }
    }

    private String tabIdFor(WebView target) {
        if (target == null) {
            return null;
        }
        for (Map.Entry<String, WebView> entry : tabWebViews.entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void showSelectedTab() {
        String selectedId = tabStack.selectedTabId();
        for (Map.Entry<String, WebView> entry : tabWebViews.entrySet()) {
            entry.getValue().setVisibility(
                    selectedId.equals(entry.getKey()) ? VISIBLE : GONE);
        }
    }

    private void notifyTabsChanged() {
        if (tabListener != null) {
            tabListener.onTabsChanged(
                    Collections.unmodifiableList(new ArrayList<>(tabStack.tabs())),
                    tabStack.selectedTabId());
        }
    }

    private void destroyTabWebView(String tabId, WebView target) {
        WebView child = target == null ? tabWebViews.get(tabId) : target;
        tabWebViews.remove(tabId);
        if (child == null) {
            return;
        }
        viewContainer.removeView(child);
        child.stopLoading();
        child.setWebChromeClient(null);
        child.setWebViewClient(null);
        child.loadUrl("about:blank");
        child.destroy();
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
        for (String tabId : new ArrayList<>(tabWebViews.keySet())) {
            destroyTabWebView(tabId, null);
        }
        webView = null;
        tabStack.select(WebAppTabStack.ROOT_TAB_ID);
    }

    private void closeAllChildTabs() {
        for (WebAppTabStack.Tab tab : new ArrayList<>(tabStack.tabs())) {
            if (!tab.isRoot()) {
                tabStack.close(tab.getId());
                destroyTabWebView(tab.getId(), null);
            }
        }
        tabStack.select(WebAppTabStack.ROOT_TAB_ID);
        notifyTabsChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
