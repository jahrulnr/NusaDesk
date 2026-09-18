package gh.nusashell.nusadesk.presentation.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackWebViewConfig;

/**
 * Shows the product contract as a local Markdown document rendered in a WebView.
 *
 * <p>The page, Markdown parser, and Mermaid renderer are all packaged assets
 * served from the owned {@code https://nusadesk.local} origin. The WebView has
 * no JavaScript interface and only the flat, allowlisted documentation files
 * can be loaded. Mermaid is configured in strict mode and turns the diagrams
 * into SVG inside the page.</p>
 */
public final class FoundationContractDialog {
    private static final String ORIGIN = "https://nusadesk.local";
    private static final String PATH_PREFIX = "/how-it-works/";
    private static final String PAGE_URL = ORIGIN + PATH_PREFIX + "index.html";
    private static final String ASSET_DIR = "how-it-works/";
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final WebView webView;
    private final AlertDialog dialog;
    private boolean disposed;

    public FoundationContractDialog(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity must not be null");
        }
        webView = new WebView(activity);
        new LoopbackWebViewConfig().apply(webView);
        webView.setBackgroundColor(activity.getResources().getColor(
                R.color.surface, null));
        webView.setWebViewClient(new DocumentationWebViewClient());

        dialog = new AlertDialog.Builder(activity)
                .setView(webView)
                .create();
        dialog.setCanceledOnTouchOutside(true);
    }

    /** Shows the local Markdown documentation without changing host state. */
    public void show() {
        if (disposed) {
            return;
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = webView.getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.90f),
                    (int) (metrics.heightPixels * 0.72f));
        }
        webView.loadUrl(PAGE_URL);
    }

    /** Releases the WebView when the owning Activity is destroyed. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.destroy();
    }

    private final class DocumentationWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request == null ? null : request.getUrl();
            return url == null || !isOwnedOrigin(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
            Uri url = request == null ? null : request.getUrl();
            if (url == null || !isOwnedOrigin(url)) {
                return null;
            }
            String path = url.getPath();
            if (path == null || !path.startsWith(PATH_PREFIX)) {
                return notFound();
            }
            String file = path.substring(PATH_PREFIX.length());
            if (file.isEmpty() || !SAFE_FILE_NAME.matcher(file).matches()) {
                return notFound();
            }
            try {
                InputStream input = view.getContext().getAssets().open(ASSET_DIR + file);
                Map<String, String> headers = Collections.singletonMap(
                        "Cache-Control", "no-store");
                return new WebResourceResponse(mimeTypeFor(file), "utf-8",
                        200, "OK", headers, input);
            } catch (IOException ignored) {
                return notFound();
            }
        }

        private boolean isOwnedOrigin(Uri url) {
            return "https".equals(url.getScheme())
                    && "nusadesk.local".equals(url.getHost());
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
            if (lower.endsWith(".css")) {
                return "text/css";
            }
            if (lower.endsWith(".js")) {
                return "application/javascript";
            }
            if (lower.endsWith(".md")) {
                return "text/plain";
            }
            return "application/octet-stream";
        }
    }
}
