package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.*;
import android.net.Uri;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.shadow.api.Shadow;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppDownloadPort;
import gh.nusashell.nusadesk.domain.webapp.*;
import gh.nusashell.nusadesk.infrastructure.runtimehost.WebViewFailureListener;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppWebViewBoundary;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppDownloadUiTest {
    private WebAppSurfaceView root;
    private WebView web;
    private WebAppDownloadUi ui;
    private FakeDownloads downloads;
    private String external, blocked;
    private static final String ORIGIN = "http://127.0.0.1:3088";

    @Before public void setUp() {
        root = new WebAppSurfaceView(RuntimeEnvironment.getApplication());
        web = new WebView(root.getContext());
        downloads = new FakeDownloads();
        ui = new WebAppDownloadUi(root, downloads);
        ui.attach(web, new WebAppWebViewBoundary(new WebAppDefinition(
                WebAppId.of("download-ui"), "Downloads", null, 3088, 0, 0, 0)),
                uri -> external = uri.toString(), new WebViewFailureListener() {
                    public void onLoadError(int code, String message, String url) { fail(message); }
                    public void onHttpError(int code, String url) { fail("HTTP " + code); }
                    public void onBlockedNavigation(String url) { blocked = url; }
                    public void onRenderProcessGone() { fail("renderer"); }
                });
    }

    @After public void tearDown() { ui.close(); web.destroy(); root.release(); }

    @Test public void listenerCapturesCookieUaAndServerFilename() {
        CookieManager.getInstance().setCookie(ORIGIN, "session=one");
        tap(ORIGIN + "/file");
        assertEquals("session=one", downloads.cookie);
        assertEquals("WebView-UA", downloads.agent);
        assertEquals("notes.pdf", downloads.name);
        downloads.status(WebAppDownloadStatus.State.DOWNLOADING, 50, 100, null);
        assertEquals(View.VISIBLE, root.findViewById(R.id.webapp_download_panel).getVisibility());
        assertTrue(text().contains("50%"));
        assertEquals(50, ((ProgressBar) root.findViewById(R.id.webapp_download_progress)).getProgress());
        downloads.status(WebAppDownloadStatus.State.SAVED, 100, 100, null);
        assertEquals("Saved to /root/Downloads/notes.pdf", text());
        root.findViewById(R.id.webapp_download_dismiss).performClick();
        assertEquals(View.GONE, root.findViewById(R.id.webapp_download_panel).getVisibility());
    }

    @Test public void failureKeepsPageAndRetryCapturesFreshSession() {
        tap(ORIGIN + "/file");
        downloads.status(WebAppDownloadStatus.State.FAILED, 0, -1, "HTTP 500");
        assertTrue(text().contains("HTTP 500"));
        assertEquals(View.VISIBLE, root.findViewById(R.id.webapp_download_retry).getVisibility());
        CookieManager.getInstance().setCookie(ORIGIN, "session=two");
        root.findViewById(R.id.webapp_download_retry).performClick();
        assertEquals(2, downloads.calls);
        assertEquals("session=two", downloads.cookie);
    }

    @Test public void externalAndBlockedDownloadsUseExistingNavigationBoundary() {
        tap("https://example.org/file");
        assertEquals("https://example.org/file", external);
        for (String url : new String[] {"blob:" + ORIGIN + "/id", "about:blank", "about:blank#fragment", "http://127.0.0.1:22022/file"}) {
            tap(url); assertEquals(url, blocked);
        }
        assertEquals(0, downloads.calls);
    }

    @Test public void redirectsReportedByWorkerAreRoutedOnUiThread() {
        tap(ORIGIN + "/file");
        downloads.listener.onExternal("https://example.org/next");
        ShadowLooper.idleMainLooper();
        assertEquals("https://example.org/next", external);
        downloads.listener.onBlocked("http://127.0.0.1:22022/next");
        ShadowLooper.idleMainLooper();
        assertEquals("http://127.0.0.1:22022/next", blocked);
    }

    @Test public void closeCancelsPortAndSuppressesLateCallbacks() {
        tap(ORIGIN + "/file");
        ui.close();
        downloads.status(WebAppDownloadStatus.State.SAVED, 100, 100, null);
        assertTrue(downloads.closed);
        assertEquals(View.GONE, root.findViewById(R.id.webapp_download_panel).getVisibility());
    }

    private String text() { return ((TextView) root.findViewById(R.id.webapp_download_text)).getText().toString(); }
    private void tap(String url) {
        ShadowWebView shadow = Shadow.extract(web);
        shadow.getDownloadListener().onDownloadStart(url, "WebView-UA",
                "attachment; filename=\"notes.pdf\"", "application/pdf", 100);
    }

    private static final class FakeDownloads implements WebAppDownloadPort {
        Listener listener;
        String cookie, agent, name;
        int calls;
        boolean closed;
        public void download(String url, String agent, String cookie, String name, Listener listener) {
            this.agent = agent; this.cookie = cookie; this.name = name; this.listener = listener; calls++;
        }
        public BinaryTransfer beginBinary(String name, long size, Listener listener, Completion ready) { throw new UnsupportedOperationException(); }
        public void close() { closed = true; }
        void status(WebAppDownloadStatus.State state, long bytes, long total, String detail) {
            listener.onStatus(new WebAppDownloadStatus(state, name, bytes, total, detail));
            ShadowLooper.idleMainLooper();
        }
    }
}
