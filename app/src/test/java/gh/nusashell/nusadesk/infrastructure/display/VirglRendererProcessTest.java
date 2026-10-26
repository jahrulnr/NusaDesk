package gh.nusashell.nusadesk.infrastructure.display;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class VirglRendererProcessTest {
    @Test public void realAppRootFitsWithoutTruncation() throws Exception {
        assertEquals("/data/user/0/gh.nusashell.nusadesk/files/linux-wrapper/runtimes/ubuntu-base-arm64/active/tmp/.virgl",
                VirglRendererProcess.socketPath(Paths.get("/data/user/0/gh.nusashell.nusadesk/files/linux-wrapper/runtimes/ubuntu-base-arm64/active")).toString());
    }

    @Test public void rejectsNamesThatVtestWouldSilentlyTruncate() throws Exception {
        try {
            VirglRendererProcess.socketPath(Paths.get("/" + "a".repeat(100)));
            fail("oversized Unix path accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unix socket limit"));
        }
    }

    @Test public void limitCountsEncodedBytes() throws Exception {
        try {
            VirglRendererProcess.socketPath(Paths.get("/" + "é".repeat(50)));
            fail("oversized UTF-8 path accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unix socket limit"));
        }
    }
}
