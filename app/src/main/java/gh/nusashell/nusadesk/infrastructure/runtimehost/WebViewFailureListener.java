package gh.nusashell.nusadesk.infrastructure.runtimehost;

/**
 * Surface for WebView load failures, blocked navigations, and render-process
 * crashes so the presentation layer can render explicit failure/retry/stopped
 * states instead of a blank screen.
 *
 * <p>None of the callbacks echo secrets or arbitrary server response bodies;
 * only codes, short descriptions, and the failing URL are reported.</p>
 */
public interface WebViewFailureListener {
    /** A network/resource load error occurred. */
    void onLoadError(int errorCode, String description, String failingUrl);

    /** An HTTP error response was received for a resource. */
    void onHttpError(int statusCode, String failingUrl);

    /** A navigation to a blocked (unowned/unsafe) URL was cancelled. */
    void onBlockedNavigation(String url);

    /** The WebView render process died; the host must reload or show a stopped state. */
    void onRenderProcessGone();
}
