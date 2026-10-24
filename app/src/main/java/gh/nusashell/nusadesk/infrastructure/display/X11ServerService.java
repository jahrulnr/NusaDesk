package gh.nusashell.nusadesk.infrastructure.display;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;

/** Bound private process hosting Xorg. Its lifetime follows the runtime host's binding. */
public final class X11ServerService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private X11Server server;

    @Override public void onCreate() {
        super.onCreate();
        try {
            Path root = ProotPaths.activeRootfsPath(getFilesDir().toPath(),
                    CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId());
            if (Files.isSymbolicLink(root.resolve("tmp")) || !Files.isDirectory(root.resolve("tmp"))
                    || !Files.isDirectory(root.resolve("usr/share/X11/xkb"))) {
                throw new IllegalStateException("Linux GUI requires the guest XKB data");
            }
            Os.setenv("TMPDIR", root.resolve("tmp").toString(), true);
            Os.setenv("XKB_CONFIG_ROOT", root.resolve("usr/share/X11/xkb").toString(), true);
            System.loadLibrary("Xlorie");
            server = new X11Server();
            if (!server.start(new String[]{":1", "-nolisten", "tcp", "-noreset", "-dpi", "144"})) {
                throw new IllegalStateException("native X server refused startup");
            }
        } catch (Exception | LinkageError error) {
            Log.e("NusaDeskX11", "display server failed", error);
            main.post(() -> Process.killProcess(Process.myPid()));
        }
    }

    private final IX11Server.Stub binder = new IX11Server.Stub() {
        @Override public ParcelFileDescriptor connectRenderer() {
            FutureTask<ParcelFileDescriptor> task = new FutureTask<>(() -> {
                if (server == null) throw new IllegalStateException("display server is unavailable");
                return server.getXConnection();
            });
            main.post(task);
            try {
                return task.get(3, TimeUnit.SECONDS);
            } catch (Exception error) {
                task.cancel(false);
                throw new IllegalStateException("display connection failed", error);
            }
        }
        @Override public void shutdown() {
            main.post(() -> Process.killProcess(Process.myPid()));
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }
}
