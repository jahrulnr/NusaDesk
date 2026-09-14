package gh.nusashell.nusadesk.infrastructure.runtimehost;

import android.annotation.SuppressLint;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Applies the locked-down {@link WebSettings} for the owned loopback runtime
 * WebView.
 *
 * <p>JavaScript is enabled because the runtime UI requires it, but no
 * {@code addJavascriptInterface} bridge is registered here or anywhere else in
 * the foundation. File and universal file-from-URL access are disabled so the
 * trusted loopback origin cannot be turned into a file viewer. DOM storage is
 * enabled for the runtime UI; WebView storage is treated as app data and must be
 * cleared/partitioned when switching target app/profile (handled by the caller
 * when it owns the WebView lifecycle).</p>
 *
 * <p>This configuration does not enable cleartext globally. Cleartext loopback
 * access, when required, is scoped by the manifest network-security config to
 * {@code 127.0.0.1} only; this class does not change that policy.</p>
 */
public final class LoopbackWebViewConfig {

    /**
     * Apply the runtime WebView settings to {@code webView}.
     *
     * @param webView the WebView to configure; must not be null
     */
    // JavaScript is genuinely required by the owned loopback runtime UI, so it
    // cannot be disabled. The SetJavaScriptEnabled lint check is suppressed
    // narrowly for this method because the surrounding configuration enforces
    // the AGENTS.md WebView security boundary: the WebView loads only the
    // host-generated loopback origin (see LoopbackWebViewClient /
    // LoopbackNavigationPolicy), file and universal file-from-URL access are
    // disabled below, and no addJavascriptInterface bridge is ever registered
    // (no broad JS surface exposed to runtime/remote content). This is a
    // scoped, documented exception — not a global suppression.
    @SuppressLint("SetJavaScriptEnabled")
    public void apply(WebView webView) {
        if (webView == null) {
            throw new IllegalArgumentException("webView must not be null");
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // No addJavascriptInterface is ever called by this configuration.
    }
}
