package gh.nusashell.nusadesk.presentation.webapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
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
import android.widget.Toast;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.runtimehost.ExternalLinkHandler;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;
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
 * (blocked navigation or a dead renderer), sign-in required, and loaded — and
 * every non-loaded state offers the one action that can change it.
 * Reachability is not health: the surface never claims the app itself is
 * working, and an app that answers with an auth challenge is never reported
 * as broken — {@link #AUTH_REQUIRED} is a state of its own (ADR-0058).</p>
 *
 * <p>Surface lifecycle: the WebView is created lazily on the first reachable
 * probe and retained while the surface is switched by visibility, so scrollback
 * and in-page state survive a trip to the launcher. Re-binding to the same
 * definition keeps the live page; re-binding to a changed definition, or
 * retrying, starts a fresh probe and a fresh load.</p>
 *
 * <p>A page's file input — and the File System Access pickers WebView routes
 * through the same callback — is answered by {@link WebAppFileChooser}, which
 * owns the source choice, the picker launch, and the one answer the page's
 * callback must receive (ADR-0059). The host routes the system picker's
 * activity result back in through {@link #deliverUploadResult(int, Intent)}.</p>
 */
public final class WebAppSurfaceView extends FrameLayout implements WebAppSignInState {

    /** What the surface currently shows. */
    public enum State { PROBING, UNREACHABLE, FAILED, LOADED, AUTH_REQUIRED }

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
    private HttpAuthResponder signInResponder;
    private WebAppFileChooser fileChooser;
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
        fileChooser = new WebAppFileChooser(getContext());
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
        // A pending sign-in prompt owns Back first: the challenge it is
        // answering is still open, so the gesture must cancel it before it
        // reaches page history, a child tab, or the shell.
        if (signInResponder != null && signInResponder.handleBack()) {
            return true;
        }
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
     * @param definition      the registered app
     * @param executor        background executor for the bounded probe
     * @param signInResponder answers the app's own HTTP auth challenges; every
     *                        tab's WebView client forwards its challenge here,
     *                        so a sign-in prompt belongs to the whole surface
     * @throws IllegalArgumentException when any argument is null
     */
    public void bind(WebAppDefinition definition, ExecutorService executor,
                     HttpAuthResponder signInResponder) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (signInResponder == null) {
            throw new IllegalArgumentException("signInResponder must not be null");
        }
        boolean sameApp = definition.equals(this.definition);
        if (!sameApp) {
            closeAllChildTabs();
            destroyWebView();
        }
        this.definition = definition;
        this.boundary = new WebAppWebViewBoundary(definition);
        this.probeExecutor = executor;
        this.signInResponder = signInResponder;
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

    /**
     * Delivers a picker's or the camera's activity result to the page that asked
     * for it. The framework delivers activity results to the Activity, so the
     * host routes every upload result through here; the surface that owns the
     * pending chooser consumes it.
     *
     * @return {@code true} when this surface was waiting for the result
     */
    public boolean deliverUploadResult(int requestCode, int resultCode, Intent data) {
        return fileChooser.deliver(requestCode, resultCode, data);
    }

    /**
     * Delivers the camera-permission outcome to the chooser that asked for it.
     *
     * @return {@code true} when this surface was waiting for the grant
     */
    public boolean deliverUploadPermissionResult(int requestCode, boolean granted) {
        return requestCode == WebAppFileChooser.REQUEST_CAMERA_PERMISSION
                && fileChooser.deliverCameraPermissionResult(granted);
    }

    private void startProbe() {
        // A fresh load supersedes whatever the previous one was waiting for.
        // The responder is told first, so a sign-in challenge parked from an
        // abandoned navigation is cancelled instead of outliving it and
        // answering the next submission into a dead handler.
        if (signInResponder != null) {
            signInResponder.reset();
        }
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
                current.newWebViewClient(
                        externalLinkHandler(), failureListener(tabId), signInResponder)));
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
            } catch (RuntimeException noHandler) {
                // No browser or app can open it, or the platform's own resolver
                // refused: the surface stays where it is. A link must never be
                // able to take the process down (ADR-0061).
            }
        };
    }

    /**
     * Closes a child tab after the current callback returns.
     *
     * <p>A WebView must never be destroyed from inside one of its own
     * callbacks: a failure report such as {@code onBlockedNavigation} arrives
     * while the renderer is committing a navigation, and tearing the WebView
     * down there takes the whole process with it instead of closing a tab
     * (ADR-0061). Every child teardown triggered by a WebView callback goes
     * through here; a teardown the user asked for (Back, a menu close) stays
     * synchronous because no renderer callback is on the stack.</p>
     */
    private void postChildTabClose(String tabId) {
        mainHandler.post(() -> closeTab(tabId));
    }

    /**
     * States, in words, that a tapped link was refused, so the tap is never
     * silent. Posted like every other state report; the wording matches the
     * blocked failure panel.
     */
    private void postBlockedLinkNotice() {
        mainHandler.post(() -> Toast.makeText(getContext(),
                R.string.webapp_link_blocked_toast, Toast.LENGTH_SHORT).show());
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
                    // ERROR_AUTHENTICATION is the load abandoned by a refused
                    // or cancelled challenge — an app asking to be signed into,
                    // not a broken one.
                    if (errorCode == WebViewClient.ERROR_AUTHENTICATION) {
                        showSignInRequired();
                    } else {
                        showFailure(description);
                    }
                }

                @Override
                public void onHttpError(int statusCode, String failingUrl) {
                    if (statusCode == 401) {
                        showSignInRequired();
                    } else {
                        showFailure("HTTP " + statusCode);
                    }
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
        // A child tab follows the same rule for every HTTP error, including a
        // 401: the sign-in card belongs to the whole surface, not to a popup,
        // so an auth-protected child closes like any other failed page. Its
        // challenge still ran through the surface's responder first, so a
        // submitted pair is already stored before the tab goes away.
        //
        // Every close is posted (ADR-0061): these reports arrive from inside
        // the child's own WebView callbacks, and destroying a WebView there is
        // what killed the process when a page opened a dead link in a popup.
        return new WebViewFailureListener() {
            @Override
            public void onLoadError(int errorCode, String description, String failingUrl) {
                postChildTabClose(tabId);
            }

            @Override
            public void onHttpError(int statusCode, String failingUrl) {
                postChildTabClose(tabId);
            }

            @Override
            public void onBlockedNavigation(String url) {
                // The address is outside this app by policy, not broken: say so
                // before the popup goes away, so the tap is not a silent no-op.
                postBlockedLinkNotice();
                postChildTabClose(tabId);
            }

            @Override
            public void onRenderProcessGone() {
                postChildTabClose(tabId);
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

    // ---- Sign-in state (WebAppSignInState) ----

    /**
     * Renders "this app needs a sign-in". Driven by the surface's
     * {@link HttpAuthResponder} when a challenge cannot be answered silently;
     * the native card sits on top while it is up, so the same state also backs
     * the cancelled prompt. UI thread only.
     */
    @Override
    public void showSignInRequired() {
        if (released) {
            return;
        }
        state = State.AUTH_REQUIRED;
        render();
    }

    /**
     * Optimistically returns to {@link State#LOADED} so the page resumes the
     * moment an answer is sent. A rejected answer re-enters
     * {@link #showSignInRequired()} through the next challenge. UI thread only.
     */
    @Override
    public void hideSignInRequired() {
        if (released) {
            return;
        }
        state = State.LOADED;
        render();
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
                // The transport refused the delivery; the child that was added
                // for it is closed after this callback returns (ADR-0061).
                postChildTabClose(tab.getId());
                return false;
            }
            notifyTabsChanged();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            String tabId = tabIdFor(window);
            if (tabId != null && !WebAppTabStack.ROOT_TAB_ID.equals(tabId)) {
                // Posted like every other child teardown: the rule is that no
                // WebView is destroyed from inside one of its own callbacks
                // (ADR-0061).
                postChildTabClose(tabId);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            notifyTabsChanged();
        }

        /**
         * Answers a page's file request (ADR-0059). Returning {@code false}
         * would leave the platform to fail the chooser silently, so every
         * request this surface can serve is served here; the callback is
         * answered exactly once by {@link WebAppFileChooser}.
         */
        @Override
        public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {
            if (released) {
                return false;
            }
            return fileChooser.show(fileChooserParams, filePathCallback);
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

        /**
         * A finished page is the only proof that a submitted sign-in was
         * accepted, so it is what closes the card. The responder is asked
         * rather than the state being flipped directly: it knows whether a pair
         * was still in flight, and a plain page load with no sign-in pending
         * must leave the surface exactly as it found it.
         */
        @Override
        public void onPageFinished(WebView view, String url) {
            delegate.onPageFinished(view, url);
            if (signInResponder != null && signInResponder.handlePageLoaded()) {
                hideSignInRequired();
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
        public void onReceivedHttpAuthRequest(
                WebView view, HttpAuthHandler handler, String host, String realm) {
            // Not main-frame filtered: the delegate's own host policy already
            // scopes the answer to the app's origin, whatever frame asked.
            delegate.onReceivedHttpAuthRequest(view, handler, host, realm);
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
        stateAction.setText(R.string.webapp_state_retry);
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
            case AUTH_REQUIRED:
                statePanel.setVisibility(VISIBLE);
                stateTitle.setText(getContext().getString(
                        R.string.webapp_state_signin_title, name));
                stateBody.setText(R.string.webapp_state_signin_body);
                // "Sign in" re-drives the load so the server challenges again
                // and the card reappears — the same retry the other states get.
                stateAction.setText(R.string.webapp_state_signin_action);
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
        fileChooser.cancel();
        if (signInResponder != null) {
            signInResponder.reset();
        }
        destroyWebView();
    }

    private void destroyWebView() {
        // The WebViews being destroyed own any pending file-chooser callback,
        // so a chooser waiting on them is dropped with them (and its dialog
        // dismissed) instead of answering a renderer that is gone.
        fileChooser.cancel();
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
