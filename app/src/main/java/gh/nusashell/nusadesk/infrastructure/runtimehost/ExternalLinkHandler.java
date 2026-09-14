package gh.nusashell.nusadesk.infrastructure.runtimehost;

import android.net.Uri;

/**
 * Hand-off for URLs classified as {@link LoopbackNavigationPolicy.Decision#EXTERNAL}.
 *
 * <p>Implementations live in the presentation layer and typically launch an
 * {@code ACTION_VIEW} Intent for the system browser or the matching external
 * app. Keeping this as an infrastructure interface lets the WebView boundary
 * stay free of presentation dependencies while still routing external links
 * out of the WebView, as required by {@code AGENTS.md} and the
 * {@code android-webview-hosting} skill.</p>
 */
public interface ExternalLinkHandler {
    /**
     * Open a non-owned URL outside the WebView.
     *
     * @param uri the external URL to hand off; never null
     */
    void openExternal(Uri uri);
}
