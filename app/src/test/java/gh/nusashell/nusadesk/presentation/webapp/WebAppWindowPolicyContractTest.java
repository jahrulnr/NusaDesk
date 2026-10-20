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

    /**
     * Guards the file-upload wiring (ADR-0059): a page's file request is
     * answered by the surface's own chooser, and the system picker's result is
     * routed back to the surface that owns it. The upload path adds nothing to
     * the WebView boundary — the renderer reads the picked URI itself — so the
     * boundary assertions above stay as they are.
     */
    @Test
    public void pageFileRequestsAreAnsweredThroughTheExplicitUploadPath() throws Exception {
        String surface = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");
        String activity = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");

        assertTrue("the platform file-chooser callback is answered",
                surface.contains("onShowFileChooser("));
        assertTrue(surface.contains("fileChooser.show(fileChooserParams, filePathCallback)"));
        assertTrue(surface.contains("deliverUploadResult"));
        assertTrue("the picker result reaches the surface that owns it",
                activity.contains("WebAppFileChooser.REQUEST_PICK_UPLOAD"));
        assertTrue(activity.contains("onWebAppUploadPicked"));
    }

    /**
     * Guards the auth-challenge wiring that a JVM suite cannot execute: the
     * surface hands the sign-in responder into every tab's client, the
     * wrapping client forwards the challenge (it is registered in place of the
     * boundary's client, so without the forward the challenge silently dies),
     * and a 401 on the own origin becomes AUTH_REQUIRED instead of a failure.
     */
    @Test
    public void httpAuthChallengesReachTheResponderAndBecomeTheirOwnState() throws Exception {
        String source = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");

        assertTrue(source.contains("implements WebAppSignInState"));
        assertTrue(source.contains("AUTH_REQUIRED"));
        assertTrue(source.contains("signInResponder"));
        assertTrue(source.contains("onReceivedHttpAuthRequest"));
        assertTrue(source.contains("delegate.onReceivedHttpAuthRequest"));
        assertTrue(source.contains("statusCode == 401"));
        assertTrue(source.contains("ERROR_AUTHENTICATION"));
        assertTrue(source.contains("showSignInRequired()"));
        assertTrue(source.contains("hideSignInRequired()"));
        // The delegating client must forward a finished page, or the sign-in
        // card that now waits for one could never be dismissed.
        assertTrue(source.contains("onPageFinished"));
        assertTrue(source.contains("handlePageLoaded()"));
    }

    /**
     * Guards the ADR-0061 recovery rules that only a real renderer can execute:
     * a child tab is never torn down from inside its own WebView callback (that
     * destroy is what took the process down when a page opened a dead link in a
     * popup), a blocked popup says why the tap did nothing, and the task bar
     * offers the web app's reload action, so a page that navigated somewhere
     * with no way back can be recovered without editing the app definition.
     */
    @Test
    public void childTeardownIsDeferredAndTheReloadActionIsWired() throws Exception {
        String surface = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");
        String activity = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");
        String host = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/desktop/AppSurfaceHostView.java");

        assertTrue("every child teardown goes through the deferred helper",
                surface.contains("private void postChildTabClose("));
        assertTrue("the helper is what keeps the WebView out of its own callback",
                surface.contains("mainHandler.post(() -> closeTab(tabId))"));
        assertTrue("the four child failure listeners (load error, HTTP error, "
                        + "blocked navigation, dead renderer) all defer their close",
                countOf(surface, "postChildTabClose(tabId);") >= 4);
        assertTrue("a blocked popup says why the tap did nothing",
                surface.contains("postBlockedLinkNotice()"));
        assertTrue("a refused window transport defers its cleanup too",
                surface.contains("postChildTabClose(tab.getId())"));
        assertTrue("the task bar owns the reload affordance",
                host.contains("setRefreshAction("));
        assertTrue("a web app wires the action to the surface's own reload",
                activity.contains("setRefreshAction(surface::reload)"));
        assertTrue("a surface that cannot be reloaded clears the action",
                activity.contains("setRefreshAction(null)"));
    }

    /**
     * Guards the ADR-0064 cache actions: the web-app menu offers the no-cache
     * reload and the cache clear, and the surface implements them against the
     * WebView cache — the reload drops it and reloads in place, the clear
     * leaves the page alone and reports its outcome.
     */
    @Test
    public void cacheActionsAreWiredFromTheMenuToTheWebViewCache() throws Exception {
        String surface = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");
        String activity = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");

        assertTrue("the menu offers the in-place no-cache reload",
                activity.contains("R.string.webapp_menu_reload_no_cache"));
        assertTrue("the menu offers the cache clear",
                activity.contains("R.string.webapp_menu_clear_cache"));
        assertTrue("the no-cache reload lives on the surface",
                surface.contains("public void reloadWithoutCache()"));
        assertTrue("it drops the WebView cache and reloads the current page",
                surface.contains("selected.clearCache(true)")
                        && surface.contains("selected.reload()"));
        assertTrue("the cache clear lives on the surface",
                surface.contains("public void clearWebCache()"));
        assertTrue("the clear reports its outcome",
                surface.contains("R.string.webapp_cache_cleared_toast"));
    }

    private static int countOf(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }
}
