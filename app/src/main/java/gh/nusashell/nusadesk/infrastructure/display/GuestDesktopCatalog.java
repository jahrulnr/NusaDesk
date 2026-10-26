package gh.nusashell.nusadesk.infrastructure.display;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import gh.nusashell.nusadesk.application.display.LinuxDesktopCatalog;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridge;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;

/** GIO parses desktop files in the guest. Android receives metadata, never an Exec command. */
public final class GuestDesktopCatalog implements LinuxDesktopCatalog {
    static final String SCRIPT = "/opt/lw-desktop/desktop_apps.py";
    private static final String MARKER = "__NUSADESK_DESKTOPS__";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final List<Listener> queued = new ArrayList<>();
    private boolean closed;
    private volatile SshClientBridge ssh;
    private volatile int generation;
    private boolean busy;

    public GuestDesktopCatalog(Context context) {
        this(context, Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "desktop-catalog"); thread.setDaemon(true); return thread;
        }));
    }
    GuestDesktopCatalog(Context context, ExecutorService worker) {
        this.context = context.getApplicationContext(); this.worker = worker;
    }
    static Path rootfs(Context context) {
        return ProotPaths.activeRootfsPath(context.getFilesDir().toPath(), CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId());
    }
    static void stage(Context context) throws IOException {
        Path parent = rootfs(context).resolve("opt");
        if (Files.isSymbolicLink(parent)) throw new IOException("guest opt must be a real directory");
        Files.createDirectories(parent);
        Path directory = parent.resolve("lw-desktop");
        if (Files.isSymbolicLink(directory)) throw new IOException("desktop helper directory must be real");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".desktop-apps-", ".py");
        try (InputStream source = context.getAssets().open("native-desktop/desktop_apps.py")) {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, directory.resolve("desktop_apps.py"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        Path bus = directory.resolve("session-bus.sh");
        Path busTemporary = Files.createTempFile(directory, ".session-bus-", ".sh");
        try (InputStream source = context.getAssets().open("native-desktop/session-bus.sh")) {
            Files.copy(source, busTemporary, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(busTemporary, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.move(busTemporary, bus, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(busTemporary); }
        stageGraphicsAsset(context, directory, "graphics-env.sh", false);
        stageGraphicsAsset(context, directory, "graphics_probe.py", false);
        stageGraphicsAsset(context, directory, "gpu-electron.sh", true);
    }
    private static void stageGraphicsAsset(Context context, Path directory, String name, boolean executable) throws IOException {
        Path temporary = Files.createTempFile(directory, ".graphics-", ".tmp");
        try (InputStream source = context.getAssets().open("native-desktop/" + name)) {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (executable) Files.setPosixFilePermissions(temporary,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void refresh(Listener listener) {
        if (closed) return;
        if (busy) { queued.add(listener); return; }
        // Runtime down: the request is dropped without a callback; the caller re-issues
        // refresh when the next runtime-running status arrives.
        if (RuntimeStatusBus.getInstance().current() == null || !RuntimeStatusBus.getInstance().current().isRuntimeRunning()) return;
        busy = true;
        int attempt = ++generation;
        main.postDelayed(() -> finish(attempt, listener, null, "Desktop app scan timed out"), 15000);
        worker.execute(() -> {
            try {
                stage(context);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ByteArrayOutputStream errors = new ByteArrayOutputStream();
                SshClientBridge command = new SshClientBridge(new KeystoreVaultCredentialProvider(context),
                        new SharedPreferencesHostKeyTrustStore(context), LocalSshSessionFactory.pinnedHostKeyOnly(),
                        new SshReconnectPolicy(1, 0, 0), System::currentTimeMillis);
                synchronized (GuestDesktopCatalog.this) {
                    if (attempt != generation) { command.close(); return; }
                    ssh = command;
                }
                command.start(LocalSshSessionFactory.create(80, 24), "python3 " + SCRIPT + " scan", new SshSessionListener() {
                    public void onState(SshSessionState state, String detail) { }
                    public void onStdout(byte[] data, int length) { synchronized (output) { output.write(data, 0, length); } }
                    public void onStderr(byte[] data, int length) { synchronized (errors) { errors.write(data, 0, length); } }
                    public void onClosed(String reason) {
                        try {
                            String text;
                            synchronized (output) { text = new String(output.toByteArray(), StandardCharsets.UTF_8); }
                            int marker = text.indexOf(MARKER);
                            if (marker < 0) {
                                String errorText;
                                synchronized (errors) { errorText = new String(errors.toByteArray(), StandardCharsets.UTF_8); }
                                throw new IOException(failureDetail(text, errorText, reason));
                            }
                            String json = text.substring(marker + MARKER.length()).split("[\\r\\n]", 2)[0];
                            finish(attempt, listener, decode(json, rootfs(context)), null);
                        } catch (Exception error) { finish(attempt, listener, null, error.getMessage()); }
                    }
                });
            } catch (Exception error) { finish(attempt, listener, null, error.getMessage()); }
        });
    }
    static List<LinuxDesktopApp> decode(String json, Path root) throws Exception {
        JSONArray rows = new JSONArray(json);
        List<LinuxDesktopApp> apps = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.getJSONObject(i);
            String icon = item.optString("icon_path");
            String uri = null;
            if (icon.startsWith("/")) {
                Path file = root.resolve(icon.substring(1)).normalize();
                if (file.startsWith(root) && Files.isRegularFile(file) && file.toRealPath().startsWith(root.toRealPath())) uri = file.toUri().toString();
            }
            apps.add(new LinuxDesktopApp(item.getString("id"), item.getString("name"), item.getString("path"), uri));
        }
        return Collections.unmodifiableList(apps);
    }
    private void finish(int attempt, Listener listener, List<LinuxDesktopApp> apps, String error) {
        main.post(() -> {
            if (attempt != generation || !busy) return;
            busy = false;
            SshClientBridge old = ssh; ssh = null;
            if (old != null) worker.execute(old::close);
            listener.onApps(apps, error);
            if (!queued.isEmpty() && !closed) {
                List<Listener> followup = new ArrayList<>(queued); queued.clear();
                refresh((found, failure) -> { for (Listener request : followup) request.onApps(found, failure); });
            }
        });
    }
    static String failureDetail(String output, String errors, String reason) {
        return (errors + "\n" + output + "\n" + reason).trim();
    }
    @Override public synchronized void close() {
        generation++; busy = false; closed = true; queued.clear();
        SshClientBridge old = ssh; ssh = null;
        if (old != null) worker.execute(old::close);
        worker.shutdown();
    }
}
