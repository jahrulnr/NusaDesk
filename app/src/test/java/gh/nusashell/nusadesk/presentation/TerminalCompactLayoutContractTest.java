package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the compact fullscreen shell needed when a phone IME is visible. */
public class TerminalCompactLayoutContractTest {

    private static String read(String modulePath, String repoPath) throws Exception {
        Path path = Paths.get(modulePath);
        if (!Files.isRegularFile(path)) {
            path = Paths.get(repoPath);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void phoneTerminalChromeUsesCompactHeights() throws Exception {
        String dimens = read("src/main/res/values/dimens.xml",
                "app/src/main/res/values/dimens.xml");

        assertTrue(dimens.contains(
                "<dimen name=\"task_bar_height\">40dp</dimen>"));
        assertTrue(dimens.contains(
                "<dimen name=\"terminal_key_height\">40dp</dimen>"));
    }

    @Test
    public void activityHidesStatusBarWithApi29AndModernPaths() throws Exception {
        String source = read("src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java",
                "app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");
        String styles = read("src/main/res/values/styles.xml",
                "app/src/main/res/values/styles.xml");

        assertTrue(source.contains("WindowInsets.Type.statusBars()"));
        assertTrue(source.contains("View.SYSTEM_UI_FLAG_FULLSCREEN"));
        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_FULLSCREEN"));
        assertTrue(source.contains("getWindowVisibleDisplayFrame"));
        assertTrue(styles.contains(
                "<item name=\"android:windowFullscreen\">true</item>"));
    }
}
