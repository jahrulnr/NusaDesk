package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuntimeCatalogEntryTest {
    @Test
    public void ubuntuBaseEntryIsPinnedToArm64AndHttps() {
        RuntimeCatalogEntry entry = CuratedRuntimeCatalog.ubuntuBaseArm64();

        assertEquals("ubuntu-base-arm64", entry.getAppId());
        assertEquals("24.04.5", entry.getVersion());
        assertEquals("linux/arm64", entry.getGuestAbi());
        assertEquals(64, entry.getSha256().length());
        assertEquals("https", entry.getDownloadUrl().substring(0, 5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void catalogRejectsNonHttpsUrls() {
        new RuntimeCatalogEntry(
                "test",
                "Test",
                "1",
                "http://example.test/runtime.tar.gz",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "linux/arm64",
                1,
                2);
    }
}
