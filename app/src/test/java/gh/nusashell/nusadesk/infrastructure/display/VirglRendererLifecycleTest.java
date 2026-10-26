package gh.nusashell.nusadesk.infrastructure.display;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public final class VirglRendererLifecycleTest {
    @Test public void closeReapsTheOwnedChildAndRemovesItsSocket() throws Exception {
        Path root = Files.createTempDirectory("virgl-");
        Files.createDirectories(root.resolve("tmp"));
        Path executable = root.resolve("libvirglserver.so");
        Path pid = root.resolve("pid");
        Files.writeString(executable, "#!/bin/sh\necho $$ > '" + pid + "'\nexec /bin/sleep 60\n");
        assertTrue(executable.toFile().setExecutable(true));
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo();
                info.nativeLibraryDir = root.toString();
                return info;
            }
        };
        long child;
        try (VirglRendererProcess renderer = VirglRendererProcess.start(context, root)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("renderer child did not execute", Files.exists(pid));
            child = Long.parseLong(Files.readString(pid).trim());
            assertTrue(Files.exists(java.nio.file.Paths.get("/proc", Long.toString(child))));
            // Stand-in for the endpoint the real renderer creates on Android.
            Files.writeString(VirglRendererProcess.socketPath(root), "endpoint");
        }
        assertFalse(Files.exists(java.nio.file.Paths.get("/proc", Long.toString(child))));
        assertFalse(Files.exists(VirglRendererProcess.socketPath(root)));
    }
}
