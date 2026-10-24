package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Guards the ADR-0065 declaration half that a Robolectric test cannot see:
 * every shell surface declares whether it owns text input, and every surface
 * transition goes through {@code settleKeyboardFor} — including the launcher
 * return, whose omission was the bug this policy exists for. The behavioral
 * half is {@link MainActivityKeyboardTest}.
 */
public class SoftKeyboardContractTest {

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
    public void everySurfaceDeclaresItsTextInputOwnership() throws Exception {
        String base = "app/src/main/java/gh/nusashell/nusadesk/presentation/";
        assertDeclares(source(base + "desktop/DesktopHomeView.java"), true);
        assertDeclares(source(base + "desktop/AppFormView.java"), true);
        assertDeclares(source(base + "webapp/WebAppSurfaceView.java"), true);
        assertDeclares(source(base + "terminal/TerminalAppView.java"), true);
        assertDeclares(source(base + "logs/LogsScreenView.java"), false);
        assertDeclares(source(base + "system/SystemScreenView.java"), false);
    }

    private static void assertDeclares(String source, boolean expected) {
        assertTrue("the surface declares SoftKeyboardAware",
                source.contains("SoftKeyboardAware"));
        int signature = source.indexOf("public boolean ownsTextInput()");
        assertTrue("ownsTextInput() is implemented", signature >= 0);
        // Bind the answer to the method body itself — a stray same-literal
        // return elsewhere in the file must not satisfy this.
        String body = source.substring(signature,
                Math.min(signature + 300, source.length()));
        assertTrue("ownsTextInput() answers " + expected,
                body.contains("return " + expected + ";"));
    }

    @Test
    public void everySurfaceTransitionSettlesTheKeyboard() throws Exception {
        String base = "app/src/main/java/gh/nusashell/nusadesk/presentation/";
        String activity = source(base + "MainActivity.java");
        String webAppServers = source(base + "webapp/WebAppServerCoordinator.java");

        // One helper, hit by all four transitions: launcher, fixed surfaces,
        // web apps, and terminal-command apps (the last via the coordinator).
        assertTrue("the settle helper exists with the decor-token fallback",
                activity.contains("private void settleKeyboardFor(View surface)")
                        && activity.contains("getWindow().getDecorView().getWindowToken()"));
        assertTransition(activity, "private void showShell()", "desktopHome");
        assertTransition(activity, "private void showSurface(DesktopDestination destination)",
                "surface");
        assertTransition(activity, "private void showWebApp(WebAppDefinition definition)",
                "surface");
        assertTrue(activity.contains("showCommandSurface(app.getId().value(), app.getDisplayName(), surface)"));
        assertTransition(webAppServers,
                "void showCommandSurface(String id, String title, TerminalAppView surface)", "surface");
    }

    /**
     * Guards the in-surface half of ADR-0065: a web-app tab that goes GONE has
     * its WebView focus cleared, so a hidden tab's DOM-focused field cannot
     * keep the keyboard open while another tab renders.
     */
    @Test
    public void aHiddenWebAppTabReleasesKeyboardFocus() throws Exception {
        String surface = source(
                "app/src/main/java/gh/nusashell/nusadesk/presentation/webapp/WebAppSurfaceView.java");

        int showSelected = surface.indexOf("private void showSelectedTab()");
        assertTrue("showSelectedTab exists", showSelected >= 0);
        String body = surface.substring(showSelected,
                Math.min(showSelected + 900, surface.length()));
        assertTrue("the outgoing tab's WebView is cleared of focus",
                body.contains("webView.clearFocus()"));
    }

    private static void assertTransition(String source, String declaration, String target) {
        int signature = source.indexOf(declaration + " {");
        assertTrue(declaration + " exists", signature >= 0);
        // The first brace at method indentation closes this method's body.
        int methodEnd = source.indexOf("\n    }", signature + 1);
        String body = source.substring(signature,
                methodEnd < 0 ? source.length() : methodEnd);
        assertTrue(declaration + " settles the keyboard for " + target,
                body.contains("settleKeyboardFor(" + target + ")"));
    }
}
