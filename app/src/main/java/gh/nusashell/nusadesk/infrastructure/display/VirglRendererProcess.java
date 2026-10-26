package gh.nusashell.nusadesk.infrastructure.display;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Android GLES vtest child; the private X11 service owns its entire lifetime. */
final class VirglRendererProcess implements AutoCloseable {
    private final Process child;
    private final Path socket;

    private VirglRendererProcess(Process child, Path socket) {
        this.child = child;
        this.socket = socket;
    }

    static VirglRendererProcess start(Context context, Path root) throws IOException {
        Path socket = socketPath(root);
        Files.deleteIfExists(socket);
        Process child = new ProcessBuilder(context.getApplicationInfo().nativeLibraryDir + "/libvirglserver.so",
                "--no-fork", "--multi-clients", "--use-egl-surfaceless", "--use-gles",
                "--socket-path", socket.toString())
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("tmp/nusadesk-virgl.log").toFile()).start();
        // Socket existence is not GPU readiness. Guest Mesa performs a GL probe
        // before choosing virpipe; a failed probe retains software rendering.
        return new VirglRendererProcess(child, socket);
    }

    static Path socketPath(Path root) throws IOException {
        Path socket = root.resolve("tmp/.virgl");
        // Linux sockaddr_un.sun_path has 108 bytes, including its NUL.
        // Upstream vtest truncates longer names instead of rejecting them.
        if (socket.toString().getBytes(StandardCharsets.UTF_8).length >= 108) {
            throw new IOException("GPU socket path exceeds the Unix socket limit");
        }
        return socket;
    }

    @Override public void close() {
        child.destroy();
        try {
            if (!child.waitFor(1, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(1, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(socket);
        } catch (InterruptedException error) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            Log.w("NusaDeskVirgl", "renderer socket cleanup failed", error);
        }
    }
}
