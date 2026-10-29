package gh.nusashell.nusadesk.presentation.webapp;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppDownloadPort;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus.State;
import gh.nusashell.nusadesk.infrastructure.runtimehost.ExternalLinkHandler;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackNavigationPolicy.Decision;
import gh.nusashell.nusadesk.infrastructure.runtimehost.WebViewFailureListener;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppWebViewBoundary;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppBinaryDownloads;

/** Native progress/result banner shared by the root and child WebViews of one app. */
final class WebAppDownloadUi implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebAppDownloadPort downloads;
    private final View panel;
    private final TextView text;
    private final ProgressBar progress;
    private final Button retry;
    private final Button dismiss;
    private boolean closed;
    private final java.util.Map<WebView, WebAppBinaryDownloads> binary = new java.util.IdentityHashMap<>();

    WebAppDownloadUi(View root, WebAppDownloadPort downloads) {
        this.downloads = downloads;
        panel = root.findViewById(R.id.webapp_download_panel);
        text = root.findViewById(R.id.webapp_download_text);
        progress = root.findViewById(R.id.webapp_download_progress);
        retry = root.findViewById(R.id.webapp_download_retry);
        dismiss = root.findViewById(R.id.webapp_download_dismiss);
        dismiss.setOnClickListener(view -> panel.setVisibility(View.GONE));
    }

    void attach(WebView view, WebAppWebViewBoundary boundary, ExternalLinkHandler external,
                WebViewFailureListener failure) {
        binary.put(view, new WebAppBinaryDownloads(view, boundary.getLoadUrl(), downloads,
                value -> render(value, null)));
        view.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (closed) return;
            if (binary.get(view).navigate(url, URLUtil.guessFileName(url, disposition, mime))) return;
            Decision decision = boundary.classify(url);
            if (decision == Decision.EXTERNAL) { external.openExternal(Uri.parse(url)); return; }
            if (decision != Decision.OWNED || url.startsWith("about:")) {
                failure.onBlockedNavigation(url); return;
            }
            String agent = userAgent == null ? view.getSettings().getUserAgentString() : userAgent;
            String name = URLUtil.guessFileName(url, disposition, mime);
            start(url, agent, name, external, failure);
        });
    }

    boolean navigate(WebView view, String url) {
        WebAppBinaryDownloads bridge = binary.get(view);
        return bridge != null && bridge.navigate(url, "");
    }

    void navigationStarted(WebView view) {
        WebAppBinaryDownloads bridge = binary.get(view);
        if (bridge != null) bridge.navigationStarted();
    }

    void detach(WebView view) {
        WebAppBinaryDownloads bridge = binary.remove(view);
        if (bridge != null) bridge.close();
    }

    private void start(String url, String agent, String name, ExternalLinkHandler external,
                       WebViewFailureListener failure) {
        if (closed) return;
        String cookie = CookieManager.getInstance().getCookie(url);
        Runnable repeat = () -> start(url, agent, name, external, failure);
        downloads.download(url, agent, cookie, name, new WebAppDownloadPort.Listener() {
            @Override public void onStatus(WebAppDownloadStatus status) {
                main.post(() -> { if (!closed) render(status, repeat); });
            }
            @Override public void onExternal(String target) {
                main.post(() -> {
                    if (!closed) { panel.setVisibility(View.GONE); external.openExternal(Uri.parse(target)); }
                });
            }
            @Override public void onBlocked(String target) {
                main.post(() -> {
                    if (!closed) { panel.setVisibility(View.GONE); failure.onBlockedNavigation(target); }
                });
            }
        });
    }

    private void render(WebAppDownloadStatus status, Runnable repeat) {
        State state = status.getState();
        boolean active = state == State.QUEUED || state == State.DOWNLOADING;
        panel.setVisibility(View.VISIBLE);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        retry.setVisibility(state == State.FAILED && repeat != null ? View.VISIBLE : View.GONE);
        dismiss.setVisibility(active ? View.GONE : View.VISIBLE);
        retry.setOnClickListener(repeat == null ? null : view -> repeat.run());
        String message;
        if (state == State.QUEUED) {
            progress.setIndeterminate(true);
            message = panel.getContext().getString(R.string.webapp_download_queued, status.getName());
        } else if (state == State.DOWNLOADING) {
            boolean known = status.getTotal() > 0;
            int percent = known ? (int) Math.min(100, status.getBytes() * 100.0 / status.getTotal()) : 0;
            progress.setIndeterminate(!known);
            progress.setProgress(percent);
            String amount = known ? percent + "%" : Formatter.formatShortFileSize(panel.getContext(), status.getBytes());
            message = panel.getContext().getString(R.string.webapp_download_active, status.getName(), amount);
        } else if (state == State.SAVED) {
            message = panel.getContext().getString(R.string.webapp_download_saved, status.getName());
        } else {
            message = panel.getContext().getString(R.string.webapp_download_failed, status.getName(), status.getDetail());
            if (repeat == null) message += "\n" + panel.getContext().getString(R.string.webapp_export_again);
        }
        text.setText(message);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (WebAppBinaryDownloads bridge : binary.values()) bridge.close();
        binary.clear();
        downloads.close();
        panel.setVisibility(View.GONE);
        retry.setOnClickListener(null);
        dismiss.setOnClickListener(null);
    }
}
