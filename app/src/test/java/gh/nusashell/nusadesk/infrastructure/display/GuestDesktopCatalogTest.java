package gh.nusashell.nusadesk.infrastructure.display;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GuestDesktopCatalogTest {
    @Test public void metadataAndIconResolveWithinTheGuest() throws Exception {
        Path root = Files.createTempDirectory("desktop-catalog");
        Path icon = root.resolve("usr/share/icons/editor.png");
        Files.createDirectories(icon.getParent()); Files.write(icon, new byte[]{1});
        List<LinuxDesktopApp> apps = GuestDesktopCatalog.decode("[{\"id\":\"editor.desktop\",\"name\":\"Editor ü\",\"path\":\"/usr/share/applications/editor.desktop\",\"icon_path\":\"/usr/share/icons/editor.png\"}]", root);
        assertEquals("Editor ü", apps.get(0).getName());
        assertEquals(icon.toUri().toString(), apps.get(0).getIconUri());
        assertThrows(UnsupportedOperationException.class, () -> apps.clear());
    }
    @Test public void escapingOrMissingIconsUseTheNormalTileFallback() throws Exception {
        Path root = Files.createTempDirectory("desktop-catalog");
        Path outside = Files.createTempFile("outside", ".png");
        Files.createSymbolicLink(root.resolve("icon.png"), outside);
        for (String path : new String[]{"/icon.png", "/../" + outside.getFileName(), "/missing.png", "relative.png"}) {
            String json = "[{\"id\":\"editor.desktop\",\"name\":\"Editor\",\"path\":\"/usr/share/applications/editor.desktop\",\"icon_path\":\"" + path + "\"}]";
            assertNull(GuestDesktopCatalog.decode(json, root).get(0).getIconUri());
        }
    }
}
