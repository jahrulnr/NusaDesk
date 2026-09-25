package gh.nusashell.nusadesk.presentation.widget;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;


import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackWebViewConfig;
import gh.nusashell.nusadesk.presentation.terminal.TerminalMessage;
import gh.nusashell.nusadesk.presentation.terminal.TerminalMessageCodec;
import gh.nusashell.nusadesk.presentation.terminal.TerminalPendingWrites;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Real WebView terminal host for the packaged xterm bundle.
 *
 * <p>Loads <em>only</em> the packaged terminal assets, served over an owned
 * {@code https://linuxwrapper.local} origin via {@link WebViewClient#shouldInterceptRequest}
 * (not a {@code file://} URL, which has no valid origin and would force an insecure
 * {@code "*"} target on {@code postMessage}). JavaScript is enabled because xterm.js
 * requires it, but <b>no {@code addJavascriptInterface} is ever registered</b>: terminal
 * I/O crosses the boundary exclusively through a framework {@link WebMessagePort}
 * channel, with a small injected shim that adapts the existing
 * {@code window.LinuxWrapperTerminal} object to the port. The shim is injected only
 * after {@link WebViewClient#onPageFinished} for the exact trusted terminal URL, so
 * the bridge is origin-scoped.</p>
 *
 * <p>Every message received from the page is validated by
 * {@link TerminalMessageCodec} (size, shape, type, field types); invalid messages
 * are dropped silently and never logged. The host side encodes only the fixed
 * host&rarr;page commands.</p>
 *
 * <p>Lifecycle: the WebView and port are torn down in {@link #release()} and on
 * detach. The host never loads remote content; external links (none are present in
 * the trusted page, but defensively) are sent to the system browser.</p>
 */
public final class TerminalBridgeView extends FrameLayout {

    /** Owned origin serving the packaged terminal assets. Not a real network host. */
    static final String ORIGIN = "https://linuxwrapper.local";
    private static final String TERMINAL_PATH_PREFIX = "/terminal/";
    private static final String TERMINAL_PAGE = TERMINAL_PATH_PREFIX + "terminal.html";
    private static final String TERMINAL_URL = ORIGIN + TERMINAL_PAGE;
    private static final String ASSET_DIR = "terminal/";

    /** Filenames served from the terminal asset directory. No traversal, no paths. */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    /** Listener for events arriving from the trusted page. Called on the UI thread. */
    public interface Listener {
        /** The bridge shim wired the port and is ready to receive commands. */
        void onBridgeReady();
        /** User typed data that should be sent to the remote shell stdin. */
        void onInput(String data);
        /** The terminal's column/row size changed (user or layout driven). */
        void onTerminalResize(int cols, int rows);
    }

    private final WebView webView;
    private final TextView overlay;
    private Button scrollBottomButton;
    private final LoopbackWebViewConfig webConfig = new LoopbackWebViewConfig();

    private Listener listener;
    private WebMessagePort hostPort;
    private boolean released;
    /**
     * The packaged page owns its own dark background, but a WebView paints its
     * default white surface first. Until the page has actually reported ready,
     * this view keeps an opaque terminal-coloured overlay on top so no frame can
     * ever show a white rectangle where the shell should be.
     */
    private boolean pageReady;
    /**
     * True once the packaged page has reported READY through its wired port —
     * the first moment a host->page write can actually be delivered. Kept
     * separate from {@link #pageReady} (which already turns true at
     * {@code onPageFinished}) because a host-owned tab session can produce its
     * first output inside exactly that window (ADR-0054): writes arriving
     * before READY are held in {@link #pendingWrites} and flushed in order
     * instead of being dropped.
     */
    private boolean portReady;
    private final TerminalPendingWrites pendingWrites = new TerminalPendingWrites();
    private CharSequence requestedOverlay;

    public TerminalBridgeView(Context context) {
        this(context, null);
    }

    public TerminalBridgeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        webView = new WebView(context);
        // The terminal must be able to hold view focus in touch mode so that
        // hardware/injected key events reach the shell instead of falling back
        // to a previously focused button (Enter would otherwise click it).
        webView.setFocusableInTouchMode(true);
        // Every touch reaches the WebView unmodified: xterm 6 renders rows as
        // real DOM text, so the platform's own long-press selection (and its
        // Copy action mode) works on it with no app-owned clipboard code, while
        // the packaged touch-scroll adapter turns one-finger drags into
        // scrollback scrolling inside the page.
        overlay = new TextView(context);
        overlay.setVisibility(GONE);
        overlay.setBackgroundResource(R.drawable.terminal_overlay);
        overlay.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
        overlay.setPadding(pad, pad, pad, pad);
        overlay.setTextSize(15f);
        // Fixed terminal ink, not the theme's: the terminal surface is dark in
        // every theme, so theme ink would be invisible in light mode.
        overlay.setTextColor(getResources().getColor(R.color.terminal_ink, null));
        scrollBottomButton = new Button(context);
        scrollBottomButton.setText(R.string.terminal_scroll_to_bottom);
        scrollBottomButton.setTextSize(12f);
        scrollBottomButton.setMinHeight((int) (40 * getResources().getDisplayMetrics().density));
        scrollBottomButton.setMinWidth((int) (88 * getResources().getDisplayMetrics().density));
        scrollBottomButton.setContentDescription("Scroll terminal to live output");
        scrollBottomButton.setBackgroundResource(R.drawable.terminal_live_surface);
        scrollBottomButton.setTextColor(getResources().getColor(R.color.terminal_key_ink, null));
        scrollBottomButton.setVisibility(GONE);
        scrollBottomButton.setOnClickListener(v -> scrollToBottom());
        int terminalSurface = getResources().getColor(R.color.terminal_surface, null);
        setBackgroundColor(terminalSurface);
        webView.setBackgroundColor(terminalSurface);
        addView(webView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(overlay, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LayoutParams liveParams = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        int liveMargin = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);
        liveParams.setMargins(liveMargin, liveMargin, liveMargin, liveMargin);
        addView(scrollBottomButton, liveParams);
        // xterm fits itself to the container it is given, so the host must refit
        // whenever the surface actually changes size — rotation, entering or
        // leaving an app surface, or a system font-scale change. Without this the
        // guest PTY keeps whatever geometry it was opened with (observed: 24x80
        // in landscape while the visible surface was far smaller).
        addOnLayoutChangeListener((view, left, top, right, bottom,
                                   oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                refitAfterLayout();
            }
        });
        configure();
        loadTerminal();
        applyOverlay();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Write SSH stdout to the terminal. Must be called on the UI thread. */
    public void writeStdout(String data) {
        if (released) {
            return;
        }
        if (!portReady) {
            pendingWrites.add(data, false); // overflow: the new chunk is dropped
            return;
        }
        postToPage(TerminalMessage.text(TerminalMessage.Type.WRITE, data));
    }

    /** Write SSH stderr to the terminal. Must be called on the UI thread. */
    public void writeStderr(String data) {
        if (released) {
            return;
        }
        if (!portReady) {
            pendingWrites.add(data, true);
            return;
        }
        postToPage(TerminalMessage.text(TerminalMessage.Type.WRITE_STDERR, data));
    }

    /** Resize the remote PTY and refit the terminal. Must be called on the UI thread. */
    public void setTerminalSize(int cols, int rows) {
        postToPage(TerminalMessage.size(TerminalMessage.Type.SET_SIZE, cols, rows));
    }

    /** Refit the terminal to its container. Must be called on the UI thread. */
    public void fit() {
        postToPage(TerminalMessage.signal(TerminalMessage.Type.FIT));
    }

    /** Focus the terminal for input. Must be called on the UI thread. */
    public void focus() {
        webView.requestFocus();
        webView.post(() -> {
            if (!webView.hasFocus() && isAttachedToWindow()) {
                webView.requestFocus();
            }
        });
        postToPage(TerminalMessage.signal(TerminalMessage.Type.FOCUS));
    }

    /** Scrollback-aware shortcut: return to the live prompt after browsing history. */
    public void scrollToBottom() {
        scrollBottomButton.setVisibility(GONE);
        postToPage(TerminalMessage.signal(TerminalMessage.Type.SCROLL_BOTTOM));
    }

    /**
     * Clear the screen and the scrollback buffer. Used when the surface
     * switches its content source — the Logs surface selects a different
     * file — so output from the previous source is never carried over.
     * Must be called on the UI thread.
     */
    public void clear() {
        scrollBottomButton.setVisibility(GONE);
        // A reset means the queued source is gone too: writes still waiting for
        // the page belong to the previous content and must never flush into it.
        pendingWrites.clear();
        postToPage(TerminalMessage.signal(TerminalMessage.Type.RESET));
    }

    /**
     * Set the xterm font size in px for this surface (the Logs viewer renders
     * denser than the interactive shell). Clamped to a sane range; the page
     * refits afterwards so column count follows the new cell size. Must be
     * called on the UI thread, after the bridge reports ready.
     */
    public void setFontSize(int px) {
        int clamped = Math.max(8, Math.min(px, 24));
        postToPage(TerminalMessage.size(TerminalMessage.Type.FONT_SIZE, clamped, 0));
    }

    /**
     * Enable or disable Android's native text selection for this surface. Read-only
     * log viewers disable it so a long press cannot steal an ongoing scroll
     * gesture; the interactive shell leaves it enabled for Copy.
     * Must be called on the UI thread after the page reports ready.
     */
    public void setNativeTextSelectionEnabled(boolean enabled) {
        String value = enabled ? "true" : "false";
        webView.evaluateJavascript(
                "(function(){var t=window.LinuxWrapperTerminal;"
                        + "if(t&&t.setNativeTextSelectionEnabled){"
                        + "t.setNativeTextSelectionEnabled(" + value + ");}})();",
                null);
    }

    /**
     * Show a state overlay over the terminal. Pass {@code null} or empty to hide.
     * Used for loading/connecting/reconnecting/failed states — never to fake a
     * live terminal. Until the packaged page has reported ready the requested
     * text is replaced by an explicit loading state, so the surface is never a
     * blank rectangle.
     */
    public void showOverlay(CharSequence text) {
        requestedOverlay = text;
        applyOverlay();
    }

    /** True once the packaged terminal page has wired its message port. */
    public boolean isPageReady() {
        return pageReady;
    }

    private void applyOverlay() {
        CharSequence text = pageReady
                ? requestedOverlay
                : getContext().getString(R.string.terminal_loading);
        if (text == null || text.length() == 0) {
            overlay.setVisibility(GONE);
            return;
        }
        overlay.setText(text);
        overlay.setVisibility(VISIBLE);
    }

    /**
     * Refit the terminal once the pending layout pass has given this view its
     * real size. Called from the layout-change listener, so it is safe on
     * rotation and on entering/leaving an app surface.
     */
    private void refitAfterLayout() {
        if (!isAttachedToWindow() || released || !pageReady) {
            return;
        }
        post(() -> {
            if (!released && getWidth() > 0 && getHeight() > 0) {
                fit();
            }
        });
    }

    /** Tear down the bridge and destroy the WebView. Idempotent. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        pageReady = false;
        portReady = false;
        pendingWrites.clear();
        closePort();
        listener = null;
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.removeJavascriptInterface("terminal"); // none registered; defensive no-op
        webView.destroy();
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    private void configure() {
        webConfig.apply(webView);
        webView.setWebViewClient(new TerminalWebViewClient());
        // No addJavascriptInterface is ever called. The bridge is WebMessagePort only.
    }

    private void loadTerminal() {
        webView.loadUrl(TERMINAL_URL);
    }

    private void postToPage(TerminalMessage message) {
        WebMessagePort port = hostPort;
        if (port == null) {
            return; // page not ready yet; the SSH controller should not be writing.
        }
        port.postMessage(new WebMessage(TerminalMessageCodec.encode(message)));
    }

    /**
     * Deliver every write queued before the page's port was ready, in arrival
     * order. Called once from the READY branch, before the fit/focus
     * handshake; after it the queue is empty and writes post directly.
     */
    private void flushPendingWrites() {
        for (TerminalPendingWrites.Entry entry : pendingWrites.drain()) {
            postToPage(TerminalMessage.text(
                    entry.isStderr()
                            ? TerminalMessage.Type.WRITE_STDERR
                            : TerminalMessage.Type.WRITE,
                    entry.getText()));
        }
    }

    private void closePort() {
        WebMessagePort port = hostPort;
        hostPort = null;
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) {
                // Best-effort teardown.
            }
        }
    }

    private void initBridge() {
        closePort();
        WebMessagePort[] channel = webView.createWebMessageChannel();
        hostPort = channel[0];
        hostPort.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort port, WebMessage message) {
                handlePageMessage(message);
            }
        });
        // Inject the shim first, then transfer the page's port. The page's MessagePort
        // queues messages until onmessage is set, so there is no delivery race.
        webView.evaluateJavascript(SHIM_JS, null);
        webView.postWebMessage(
                new WebMessage("", new WebMessagePort[]{channel[1]}),
                Uri.parse(ORIGIN));
    }

    private void handlePageMessage(WebMessage message) {
        if (message == null) {
            return;
        }
        TerminalMessage decoded = TerminalMessageCodec.decode(message.getData());
        if (decoded == null) {
            return; // invalid/oversized/unknown — drop silently, do not log content.
        }
        if (decoded.getType() == TerminalMessage.Type.READY) {
            // The packaged page is live and painted: drop the loading cover,
            // deliver every write queued before the port existed (in order),
            // then hand the surface its real geometry. Flushing first keeps a
            // freshly opened tab's first output ahead of the fit/focus that
            // could itself trigger page-side repaint work.
            pageReady = true;
            portReady = true;
            applyOverlay();
            flushPendingWrites();
            if (getWidth() > 0 && getHeight() > 0) {
                fit();
            }
            Listener ready = listener;
            if (ready != null) {
                ready.onBridgeReady();
            }
            return;
        }
        Listener l = listener;
        if (l == null) {
            return;
        }
        switch (decoded.getType()) {
            case INPUT:
                l.onInput(decoded.getData());
                break;
            case SCROLL_STATE:
                // The page reports whether the viewport sits away from the live
                // bottom; the button offers a jump back to live output.
                scrollBottomButton.setVisibility(
                        decoded.isScrolledBack() ? VISIBLE : GONE);
                break;
            case RESIZE:
                l.onTerminalResize(decoded.getCols(), decoded.getRows());
                break;
            default:
                // Host-originated tags are never accepted from the page.
                break;
        }
    }

    /**
     * Serves the packaged terminal assets over the owned origin and keeps every
     * other navigation inside the WebView's origin policy. External links leave
     * for the system browser.
     */
    private final class TerminalWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url == null) {
                return true;
            }
            if (ORIGIN.equals(url.getScheme() + "://" + url.getHost())) {
                return false; // owned origin: let the WebView load it (intercepted below)
            }
            // Any non-owned link leaves the WebView for the system browser.
            Intent intent = new Intent(Intent.ACTION_VIEW, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                view.getContext().startActivity(intent);
            } catch (Exception ignored) {
                // No browser handler; stay put.
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url == null) {
                return null;
            }
            String scheme = url.getScheme();
            String host = url.getHost();
            if (!"https".equals(scheme) || !ORIGIN.substring("https://".length()).equals(host)) {
                return null; // not our origin; let WebView decide (we never navigate here)
            }
            String path = url.getPath();
            if (path == null || !path.startsWith(TERMINAL_PATH_PREFIX)) {
                return notFound();
            }
            String file = path.substring(TERMINAL_PATH_PREFIX.length());
            // Reject any path component, traversal, or query tricks: serve a flat file only.
            if (file.isEmpty() || !SAFE_FILE_NAME.matcher(file).matches()) {
                return notFound();
            }
            try {
                InputStream in = view.getContext().getAssets().open(ASSET_DIR + file);
                // Packaged assets change with the APK; a stale cached page would
                // silently drop newer bridge commands (e.g. fontSize/reset).
                Map<String, String> headers = Collections.singletonMap(
                        "Cache-Control", "no-store");
                return new WebResourceResponse(mimeTypeFor(file), "utf-8",
                        200, "OK", headers, in);
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (TERMINAL_URL.equals(url)) {
                // The trusted page has painted, so the loading cover can go and
                // the surface can take its real geometry. The page's own READY
                // message still drives the fit/focus handshake with the shell.
                pageReady = true;
                initBridge();
                applyOverlay();
                refitAfterLayout();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found",
                    Collections.emptyMap(),
                    new ByteArrayInputStream(new byte[0]));
        }

        private String mimeTypeFor(String file) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html")) {
                return "text/html";
            }
            if (lower.endsWith(".js")) {
                return "application/javascript";
            }
            if (lower.endsWith(".css")) {
                return "text/css";
            }
            if (lower.endsWith(".json")) {
                return "application/json";
            }
            if (lower.endsWith(".txt")) {
                return "text/plain";
            }
            return "application/octet-stream";
        }
    }

    /**
     * Minimal, origin-scoped shim injected after the trusted page loads. It bridges
     * the existing {@code window.LinuxWrapperTerminal} object (write/resize/fit/focus
     * and onInput/onResize callbacks) to the transferred MessagePort. It contains no
     * terminal emulation of its own; all rendering is upstream xterm.js.
     */
    private static final String SHIM_JS =
"(function(){'use strict';"
+ "var term=window.LinuxWrapperTerminal;"
+ "if(!term){return;}"
+ "var port=null;"
+ "function send(o){if(!port){return;}try{port.postMessage(JSON.stringify(o));}catch(e){}}"
+ "window.addEventListener('message',function(e){"
+   "if(!e||!e.ports||!e.ports.length){return;}"
+   "port=e.ports[0];"
+   "port.onmessage=function(ev){"
+     "var m;try{m=JSON.parse(ev.data);}catch(err){return;}"
+     "if(!m||typeof m!=='object'){return;}"
+     "switch(m.t){"
+       "case 'write':term.write(m.d==null?'':m.d);break;"
+       "case 'writeStderr':term.writeStderr(m.d==null?'':m.d);break;"
+       "case 'setSize':if(typeof m.c==='number'&&typeof m.r==='number'){term.resize(m.c,m.r);}break;"
+       "case 'fit':term.fit();break;"
+       "case 'focus':term.focus();break;"
+       "case 'scrollBottom':if(window.LinuxWrapperTouchScroll){window.LinuxWrapperTouchScroll.scrollToBottom();}break;"
+       "case 'reset':if(term.reset){term.reset();}break;"
+       "case 'fontSize':if(typeof m.d==='number'&&term.fontSize){term.fontSize(m.d);}break;"
+     "}"
+   "};"
+   "term.onInput(function(d){send({t:'input',d:d});});"
+   "term.onResize(function(c,r){send({t:'resize',c:c,r:r});});"
+   "term.onScrollState(function(d){send({t:'scrollState',d:d});});"
+   "send({t:'ready'});"
   // Report the size the page actually renders. xterm only fires onResize on a
   // change, so without this a reloaded page (Activity recreation) whose fit
   // lands on the same size as before would leave the remote PTY at a stale
   // size.
+   "try{var s=term.size&&term.size();"
+ "if(s&&typeof s.cols==='number'&&typeof s.rows==='number'){send({t:'resize',c:s.cols,r:s.rows});}}catch(e){}"
+ "});"
+ "})();";
}
