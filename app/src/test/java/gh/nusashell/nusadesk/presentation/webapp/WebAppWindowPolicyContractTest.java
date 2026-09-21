package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Guards the Android-dependent popup boundary that cannot be exercised by the
 * JVM suite's real Chromium renderer. The pure tab lifecycle is covered by
 * {@link WebAppTabStackTest}; this test keeps the security-critical WebView
 * wiring explicit during future refactors.
 */
public class WebAppWindowPolicyContractTest {

    private static String source(String relativePath) throws Exception {
        File[] candidates = {
                new File(relativePath),
                new File("../" + relativePath),
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("could not find " + relativePath);
    }

    @Test
    public void surfaceOwnsChildWindowsThroughTheExplicitWebViewPolicy() throws Exception {
        String source = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");

        assertTrue(source.contains("setSupportMultipleWindows(true)"));
        assertTrue(source.contains("setJavaScriptCanOpenWindowsAutomatically(false)"));
        assertTrue(source.contains("setWebChromeClient(new SurfaceWebChromeClient"));
        assertTrue(source.contains("onCreateWindow("));
        assertTrue(source.contains("isUserGesture"));
        assertTrue(source.contains("WebView.WebViewTransport"));
        assertTrue(source.contains("onCloseWindow(WebView window)"));
        assertTrue(source.contains("current.newWebViewClient"));
        assertTrue(source.contains("handleBack()"));
    }
}
