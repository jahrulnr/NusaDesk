package qa.nusadesk.lifecycle;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.display.NativeDesktopSession;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.MainActivity;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.application.webapp.WebAppCommandSession;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.webapp.SharedPreferencesWebAppStore;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppRestartSessions;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** QA-only, release-signed instrumentation. Exercises the installed app's actual code. */
public final class LifecycleDriver extends Instrumentation {
    private final JSONObject evidence = new JSONObject();
    private Path output;
    private MainActivity activity;
    private String mode = "all";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); if (arguments != null) mode = arguments.getString("mode", "all"); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            output = context.getFilesDir().toPath().resolve("qa-lifecycle");
            Files.createDirectories(output);
            evidence.put("androidApi", android.os.Build.VERSION.SDK_INT);
            evidence.put("model", android.os.Build.MODEL);
            evidence.put("processContext", new String(Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/self/attr/current")), StandardCharsets.UTF_8).trim());
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            onMain(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            await("runtime startup", () -> RuntimeStatusBus.getInstance().current() != null
                    && RuntimeStatusBus.getInstance().current().isRuntimeRunning(), 60000);
            if (mode.equals("all")) {
                coldBindingStop(context);
                combinedLifecycle(context);
            }
            if (!mode.equals("nusashell-ui") && !mode.startsWith("pet-")) displayEnvironment(context);
            if (mode.equals("nusashell")) nusashellInstall(context);
            if (mode.equals("nusashell-ui") || mode.startsWith("pet-")) nusashellUi(context);
            evidence.put("passed", true);
            result.putString("stream", "Lifecycle QA passed\n" + evidence + "\n");
        } catch (Throwable error) {
            try { evidence.put("passed", false).put("error", android.util.Log.getStackTraceString(error)); }
            catch (Exception ignored) { }
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) onMain(() -> activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            try { Files.write(output.resolve("result.json"), evidence.toString(2).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception error) { result.putString("writeError", error.toString()); }
        }
        finish(evidence.optBoolean("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void coldBindingStop(Context context) throws Exception {
        progress("Stop during delayed native binding");
        NativeDesktopSession session = NativeDesktopSession.get(context);
        NativeDesktopSession.stopBeforeTmpCleanup();
        Path root = ProotPaths.activeRootfsPath(context.getFilesDir().toPath(), "ubuntu-base-arm64");
        Path sentinel = root.resolve("tmp/qa-lifecycle-sentinel");
        Files.write(sentinel, new byte[]{1});
        Context previous = (Context) field(session, "context");
        DelayedBinding delayed = new DelayedBinding(previous);
        setField(session, "context", delayed);
        JSONObject run = new JSONObject();
        try {
            run.put("startAtMs", SystemClock.elapsedRealtime());
            onMain(() -> openNative());
            check(delayed.requested.await(10, TimeUnit.SECONDS), "display did not request binding");
            long stopAt = SystemClock.elapsedRealtime();
            run.put("stopRequestedAtMs", stopAt);
            check(delayed.deliveredAt == 0, "callback already delivered before Stop");
            onMain(() -> RuntimeHostService.requestStop(context));
            await("guest Stop", () -> RuntimeStatusBus.getInstance().current().getState() == SessionState.STOPPED, 60000);
            run.put("stoppedAtMs", SystemClock.elapsedRealtime());
            run.put("binderObservedAtMs", delayed.observedAt);
            run.put("callbackDeliveredAtMs", delayed.deliveredAt);
            run.put("oldDisplayPid", delayed.pid);
            check(delayed.deliveredAt > stopAt, "Stop was not during pending binding");
            check(delayed.pid > 0, "no native display process observed");
            check(!Files.exists(java.nio.file.Paths.get("/proc/" + delayed.pid)), "old display survived Stop");
            check(!Files.exists(root.resolve("tmp/.X11-unix/X1")), "old display socket survived Stop");
            check(!Files.exists(sentinel), "guest temporary storage was not cleaned");
            run.put("pidDead", true).put("socketAbsent", true).put("tmpSentinelAbsent", true);
        } finally {
            setField(session, "context", previous);
        }
        onMain(() -> RuntimeHostService.ensureRunning(context));
        await("guest restart", () -> RuntimeStatusBus.getInstance().current().isRuntimeRunning(), 60000);
        onMain(() -> openNative());
        await("native app restart", () -> {
            try { return field(session, "state").toString().equals("RUNNING"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }, 30000);
        int newPid = displayPid(context);
        check(newPid > 0 && newPid != delayed.pid, "display process was not replaced");
        run.put("newDisplayPid", newPid).put("runningAgainAtMs", SystemClock.elapsedRealtime());
        screenshot("native-restarted.png");
        evidence.put("coldBindingStop", run);
        NativeDesktopSession.stopBeforeTmpCleanup();
        onMain(() -> invoke(activity, "showShell"));
    }

    private void openNative() {
        try {
            Object controller = field(activity, "nativeDesktop");
            Method method = controller.getClass().getMethod("openLinuxApp", LinuxDesktopApp.class);
            method.invoke(controller, new LinuxDesktopApp("org.xfce.mousepad.desktop", "Mousepad",
                    "/usr/share/applications/org.xfce.mousepad.desktop", null));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void displayEnvironment(Context context) throws Exception {
        progress("Fresh login, exec and user-service DISPLAY environment");
        JSONObject run = new JSONObject();
        evidence.put("displayEnvironment", run);
        String shell = ssh(context, null, "printf '\\137\\137QA_DISPLAY__%s\\n' \"$DISPLAY\"; exit\n");
        check(shell.contains("__QA_DISPLAY__:1"), "login shell did not inherit DISPLAY=:1: " + shell);
        run.put("loginShellDisplay", ":1");
        onMain(() -> openNative());
        NativeDesktopSession session = NativeDesktopSession.get(context);
        await("native app for inherited DISPLAY probe", () -> {
            try { return field(session, "state").toString().equals("RUNNING"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }, 30000);
        String exec = ssh(context, "printf '\\137\\137QA_DISPLAY__%s\\n' \"$DISPLAY\"; "
                + "xdpyinfo >/dev/null && printf '\\137\\137QA_X11_OK__\\n'; "
                + "python3 -c 'import mmap,os,tempfile; fd,p=tempfile.mkstemp(dir=\"/dev/shm\"); "
                + "os.ftruncate(fd,4096); a=mmap.mmap(fd,4096); b=mmap.mmap(fd,4096); "
                + "a[0:2]=b\"ok\"; assert b[0:2]==b\"ok\"; a.close(); b.close(); os.close(fd); os.unlink(p); "
                + "print(\"__QA_SHM_OK__\")'", null);
        check(exec.contains("__QA_DISPLAY__:1") && exec.contains("__QA_X11_OK__"),
                "exec session could not use inherited display: " + exec);
        run.put("execDisplay", ":1").put("xdpyinfoWithoutExplicitDisplay", true);
        check(exec.contains("__QA_SHM_OK__"), "guest /dev/shm mapping failed: " + exec);
        run.put("twoSharedFileMappings", true);
        JSONArray managers = new JSONArray();
        try (java.nio.file.DirectoryStream<Path> proc = Files.newDirectoryStream(java.nio.file.Paths.get("/proc"))) {
            for (Path entry : proc) {
                if (!entry.getFileName().toString().matches("[0-9]+")) continue;
                try {
                    String args = new String(Files.readAllBytes(entry.resolve("cmdline")), StandardCharsets.UTF_8).replace('\0', ' ');
                    if (!args.contains("systemctl3.py --user init")) continue;
                    String env = new String(Files.readAllBytes(entry.resolve("environ")), StandardCharsets.UTF_8);
                    check(java.util.Arrays.asList(env.split("\u0000")).contains("DISPLAY=:1"), "user manager did not inherit DISPLAY");
                    managers.put(Integer.parseInt(entry.getFileName().toString()));
                } catch (java.io.IOException inaccessibleOrGone) { }
            }
        }
        check(managers.length() > 0, "no user service manager environment was verified");
        run.put("userManagerPidsWithDisplay", managers);
        screenshot("native-with-inherited-display.png");
        NativeDesktopSession.stopBeforeTmpCleanup();
        onMain(() -> invoke(activity, "showShell"));
    }

    private String ssh(Context context, String command, String shellInput) throws Exception {
        return ssh(context, command, shellInput, 45, false);
    }

    private void nusashellInstall(Context context) throws Exception {
        progress("Inspect /root/NusaShell checkout");
        String inspect = ssh(context, "/bin/bash -lic 'cd /root/NusaShell && printf \"DISPLAY=%s\\n\" \"$DISPLAY\"; "
                + "git rev-parse HEAD; git status --short; cat VERSION; command -v go node npm make; "
                + "sed -n \"140,158p\" Makefile; df -k /root'", null);
        Files.write(output.resolve("nusashell-inspect.log"), inspect.getBytes(StandardCharsets.UTF_8));
        evidence.put("nusashellCheckout", inspect);
        onMain(() -> openNative());
        await("display before installer", () -> {
            try { return field(NativeDesktopSession.get(context), "state").toString().equals("RUNNING"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }, 30000);
        progress("make install in the guest; decline service, accept desktop and pet prompts");
        Files.write(output.resolve("nusashell-install.log"), new byte[0]);
        progress("Install documented Pet build prerequisites in the guest");
        String prepared = ssh(context, "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "
                + "pkg-config libsdl2-dev libx11-dev libxext-dev 2>&1; qa_deps_status=$?; "
                + "printf '\\137\\137QA_DEPS_STATUS__%s\\n' \"$qa_deps_status\"; exit \"$qa_deps_status\"", null, 900, true);
        check(prepared.contains("__QA_DEPS_STATUS__0"), "Pet build prerequisites failed; see nusashell-install.log");
        evidence.put("guestPetBuildPrerequisitesInstalled", true);
        String installed = ssh(context, "/bin/bash -lic 'cd /root/NusaShell && "
                + "NUSASHELL_BUILD_DIR=/root/NusaShell/apps/electron/dist/linux-arm64-unpacked make install'; "
                + "qa_install_status=$?; printf '\\137\\137QA_INSTALL_STATUS__%s\\n' \"$qa_install_status\"; exit \"$qa_install_status\"",
                null, 1800, true);
        check(installed.contains("__QA_INSTALL_STATUS__0"), "NusaShell make install failed; see nusashell-install.log");
        evidence.put("nusashellMakeInstall", true);
        screenshot("nusashell-after-install.png");
    }

    private void nusashellUi(Context context) throws Exception {
        progress("Launch installed Electron desktop entry");
        Path root = ProotPaths.activeRootfsPath(context.getFilesDir().toPath(), "ubuntu-base-arm64");
        check(Files.isRegularFile(root.resolve("root/.local/share/applications/nusashell-desktop.desktop")),
                "installed Electron desktop entry is absent");
        NativeDesktopSession session = NativeDesktopSession.get(context);
        JSONObject run = new JSONObject(); evidence.put("nusashellGui", run);
        // This installed app is running as the PRoot guest's root. Chromium
        // rejects that regardless of the helper's apparently-setuid mode.
        // Adapt only the known launcher just produced by the authorized install.
        Path launcher = root.resolve("root/.local/bin/nusashell-desktop");
        String original = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
        String plain = "#!/usr/bin/env sh\nexec \"/root/.local/share/nusashell-electron/current/nusashell-desktop\" \"$@\"\n";
        String rootLauncher = "#!/usr/bin/env sh\nexec \"/root/.local/share/nusashell-electron/current/nusashell-desktop\" --no-sandbox \"$@\"\n";
        check(original.equals(plain) || original.equals(rootLauncher), "unexpected launcher; preserve user edits");
        if (original.equals(plain)) Files.write(launcher, rootLauncher.getBytes(StandardCharsets.UTF_8));
        run.put("rootLauncherUsesNoSandbox", true);
        onMain(() -> {
            try {
                Object controller = field(activity, "nativeDesktop");
                controller.getClass().getMethod("openLinuxApp", LinuxDesktopApp.class).invoke(controller,
                        new LinuxDesktopApp("nusashell-desktop.desktop", "NusaShell",
                                "/root/.local/share/applications/nusashell-desktop.desktop", null));
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        await("Electron native task", () -> {
            try {
                String state = field(session, "state").toString();
                return state.equals("RUNNING") || state.equals("FAILED");
            } catch (Exception error) { throw new IllegalStateException(error); }
        }, 60000);
        screenshot("electron-native.png");
        check(field(session, "state").toString().equals("RUNNING"), "Electron failed: " + field(session, "detail"));
        run.put("electronMapped", true);
        progress("Launch installed Pet on the same native X11 display");
        if (mode.startsWith("pet-")) ssh(context, "pkill -TERM -x nusashell-pets || :", null);
        String rendererPrefix = mode.equals("pet-software") ? "SDL_RENDER_DRIVER=software "
                : mode.equals("pet-unmanaged") ? "SDL_X11_FORCE_OVERRIDE_REDIRECT=1 " : "";
        int duration = mode.startsWith("pet-") ? 45 : 15;
        Path pidFile = root.resolve("tmp/nusadesk-pet-qa.pid");
        Path logFile = root.resolve("tmp/nusadesk-pet-qa.log");
        check(!Files.exists(pidFile), "Pet QA PID file already exists");
        CompletableFuture<String> pet = CompletableFuture.supplyAsync(() -> {
            try {
                return ssh(context, "/bin/bash -lic '" + rendererPrefix + "nusashell-pets >/tmp/nusadesk-pet-qa.log 2>&1 & qa_pet_pid=$!; "
                        + "echo \"$qa_pet_pid\" >/tmp/nusadesk-pet-qa.pid; "
                        + "trap \"kill $qa_pet_pid 2>/dev/null || :; wait $qa_pet_pid 2>/dev/null || :\" EXIT; sleep " + duration + "; "
                        + "kill -0 \"$qa_pet_pid\" && printf \"__QA_PET_LIVED__\\n\"'", null, 120, false);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        try {
            await("Pet launch PID", () -> Files.isRegularFile(pidFile) || pet.isDone(), 30000);
            check(Files.isRegularFile(pidFile), "Pet command did not create its PID file");
            int pid = Integer.parseInt(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
            Thread.sleep(4000);
            screenshot("electron-with-pet.png");
            String result = pet.get(150, TimeUnit.SECONDS);
            String log = Files.exists(logFile) ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8) : "";
            Files.write(output.resolve("pet-runtime.log"), log.getBytes(StandardCharsets.UTF_8));
            check(result.contains("__QA_PET_LIVED__"), "Pet exited early: " + log);
            run.put("petPid", pid).put("petSurvivedSeconds", duration).put("requestedRenderer", rendererPrefix);
        } finally {
            pet.get(150, TimeUnit.SECONDS);
            Files.deleteIfExists(pidFile); Files.deleteIfExists(logFile);
        }
    }

    private String ssh(Context context, String command, String shellInput, long timeoutSeconds, boolean install) throws Exception {
        gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridge channel = new gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridge(
                new gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider(context),
                new gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore(context),
                gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory.pinnedHostKeyOnly(),
                new gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy(1, 0, 0), System::currentTimeMillis);
        StringBuilder text = new StringBuilder(); CountDownLatch closed = new CountDownLatch(1);
        java.util.Set<String> answered = new java.util.HashSet<>();
        channel.start(gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory.create(80, 24), command,
                new gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener() {
                    public void onState(gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState state, String detail) {
                        if (state == gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState.RUNNING && shellInput != null)
                            channel.write(shellInput.getBytes(StandardCharsets.UTF_8));
                    }
                    public void onStdout(byte[] bytes, int count) {
                        synchronized (text) {
                            text.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                            if (install) {
                                try { Files.write(output.resolve("nusashell-install.log"), java.util.Arrays.copyOf(bytes, count),
                                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                                catch (Exception error) { throw new IllegalStateException(error); }
                                String[] prompts = {"Install nusashell as a login service (autostart)? [y/N]",
                                        "Build and install Electron desktop wrapper? [y/N]",
                                        "Build and install desktop pet (Linux only)? [y/N]"};
                                for (int i = 0; i < prompts.length; i++) {
                                    if (text.indexOf(prompts[i]) >= 0 && answered.add(prompts[i]))
                                        channel.write((i == 0 ? "n\n" : "y\n").getBytes(StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                    public void onStderr(byte[] bytes, int count) { onStdout(bytes, count); }
                    public void onClosed(String reason) { closed.countDown(); }
                });
        try {
            check(closed.await(timeoutSeconds, TimeUnit.SECONDS), "SSH QA command did not close");
            synchronized (text) { return text.toString(); }
        } finally { channel.close(); channel.awaitClosed(10000); }
    }

    private void combinedLifecycle(Context context) throws Exception {
        progress("Combined app retention and repeated restart");
        final int port = 18986;
        check(!listening(port), "QA port already occupied");
        Path root = ProotPaths.activeRootfsPath(context.getFilesDir().toPath(), "ubuntu-base-arm64");
        Path script = root.resolve("tmp/qa-lifecycle-http.py");
        check(!Files.exists(script), "QA script already exists");
        Files.write(script, ("import http.server,os,signal,sys,time\n"
                + "class H(http.server.BaseHTTPRequestHandler):\n"
                + " def do_GET(self):\n"
                + "  b=str(os.getpid()).encode();self.send_response(200);self.end_headers();self.wfile.write(b)\n"
                + " def log_message(self,*args): pass\n"
                + "def stop(*args):\n time.sleep(2);sys.exit(0)\n"
                + "signal.signal(signal.SIGHUP,stop)\nsignal.signal(signal.SIGTERM,stop)\n"
                + "http.server.HTTPServer(('127.0.0.1',18986),H).serve_forever()\n").getBytes(StandardCharsets.UTF_8));
        WebAppRegistry registry = new WebAppRegistry(new SharedPreferencesWebAppStore(context));
        WebAppDefinition app = registry.add("QA lifecycle server", null, port,
                "exec python3 /tmp/qa-lifecycle-http.py");
        JSONObject run = new JSONObject();
        evidence.put("combinedLifecycle", run);
        try {
            showWeb(app);
            int first = awaitPid(port, -1);
            run.put("initialPid", first);
            onMain(() -> invoke(activity, "showShell"));
            showWeb(app);
            check(httpPid(port) == first, "Home/reopen duplicated server");
            run.put("homeReopenPid", httpPid(port));
            screenshot("combined-ready.png");
            JSONArray repeats = new JSONArray();
            int previous = first;
            for (int i = 0; i < 3; i++) {
                progress("Restart trial " + (i + 1));
                AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>();
                long trigger = SystemClock.elapsedRealtime();
                onMain(() -> {
                    try {
                        ((gh.nusashell.nusadesk.presentation.webapp.WebAppServerCoordinator)
                                field(activity, "webAppServers")).restartWebAppServer(app);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    CompletableFuture<Void> one = WebAppRestartSessions.get(context).pending(app.getId().value());
                    check(one != null, "restart did not become pending");
                    check(one == WebAppRestartSessions.get(context).restart(app), "restart did not deduplicate");
                    future.set(one);
                    invoke(activity, "showShell");
                });
                if (i == 1) recreateActivity();
                future.get().get(30, TimeUnit.SECONDS);
                int next = awaitPid(port, previous);
                check(!Files.exists(java.nio.file.Paths.get("/proc/" + previous)), "old server survived restart");
                repeats.put(new JSONObject().put("oldPid", previous).put("newPid", next)
                        .put("triggerAtMs", trigger).put("readyAtMs", SystemClock.elapsedRealtime()));
                previous = next;
                showWeb(app);
                awaitWebPid(app, next);
            }
            run.put("restartRepeats", repeats);
            run.put("recreatedDuringSecondRestart", true);
            screenshot("combined-restarted.png");

            // The occupied listener is ours, outside the guest command. It must
            // survive the failed restart, and no replacement tab may be opened.
            AtomicReference<CompletableFuture<Void>> stopped = new AtomicReference<>();
            progress("Occupied port must reject replacement");
            java.util.concurrent.ExecutorService cleanup = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                onMain(() -> stopped.set(WebAppCommandSession.stopAndAwait(app.getId(),
                        TerminalTabsRegistry.getInstance().port(), cleanup)));
                stopped.get().get(15, TimeUnit.SECONDS);
                awaitNoServer(port);
                try (java.net.ServerSocket occupied = new java.net.ServerSocket()) {
                    occupied.setReuseAddress(true);
                    occupied.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                    AtomicReference<CompletableFuture<Void>> blocked = new AtomicReference<>();
                    onMain(() -> blocked.set(WebAppRestartSessions.get(context).restart(app)));
                    try { blocked.get().get(15, TimeUnit.SECONDS); throw new AssertionError("occupied port restart succeeded"); }
                    catch (java.util.concurrent.ExecutionException expected) {
                        check(expected.getCause().getMessage().contains("still owns port"), "wrong occupied-port failure");
                    }
                    check(!occupied.isClosed(), "restart closed an unrelated listener");
                    onMain(() -> {
                        for (gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot tab
                                : TerminalTabsRegistry.getInstance().port().snapshot().getTabs()) {
                            check(!WebAppCommandSession.ownerId(app.getId()).equals(tab.getCommandAppId()),
                                    "occupied-port failure still launched a command");
                        }
                    });
                    run.put("occupiedPortRejectedWithoutReplacement", true);
                }
            } finally { cleanup.shutdownNow(); }
            showWeb(app);
            previous = awaitPid(port, previous);

            AtomicReference<CompletableFuture<Void>> cancelled = new AtomicReference<>();
            AtomicReference<WebAppDefinition> edited = new AtomicReference<>();
            progress("Edit cancellation");
            onMain(() -> {
                cancelled.set(WebAppRestartSessions.get(context).restart(app));
                try {
                    edited.set(registry.update(app.getId().value(), "QA edited", null, port,
                            "sleep 1; exec python3 /tmp/qa-lifecycle-http.py"));
                } catch (Exception error) { throw new IllegalStateException(error); }
                WebAppRestartSessions.get(context).cancel(app.getId().value());
                WebAppCommandSession.invalidateChangedCommand(edited.get(), TerminalTabsRegistry.getInstance().port());
                invoke(activity, "showShell");
            });
            check(cancelled.get().isCancelled(), "edit did not cancel restart");
            awaitNoServer(port);
            run.put("editCancelledWithoutReplacement", true);
            showWeb(edited.get());
            int editedPid = awaitPid(port, previous);
            run.put("editedLaunchPid", editedPid);
            progress("Guest Stop during server restart");
            byte[] serverSource = Files.readAllBytes(script);
            onMain(() -> {
                cancelled.set(WebAppRestartSessions.get(context).restart(edited.get()));
                RuntimeHostService.requestStop(context);
            });
            await("guest Stop during restart", () -> RuntimeStatusBus.getInstance().current().getState() == SessionState.STOPPED, 60000);
            try { cancelled.get().get(15, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expected) { }
            awaitNoServer(port);
            check(!Files.exists(java.nio.file.Paths.get("/proc/" + editedPid)), "guest Stop left its server alive");
            run.put("guestStopDuringRestartPidDead", editedPid).put("guestStopNoReplacement", true);
            onMain(() -> RuntimeHostService.ensureRunning(context));
            await("guest restart after combined Stop", () -> RuntimeStatusBus.getInstance().current().isRuntimeRunning(), 60000);
            Files.write(script, serverSource);
            showWeb(edited.get());
            editedPid = awaitPid(port, editedPid);
            run.put("afterGuestRestartPid", editedPid);
            progress("Delete cancellation");
            onMain(() -> {
                cancelled.set(WebAppRestartSessions.get(context).restart(edited.get()));
                registry.delete(app.getId().value());
                WebAppRestartSessions.get(context).cancel(app.getId().value());
                WebAppCommandSession.stop(app.getId(), TerminalTabsRegistry.getInstance().port());
                invoke(activity, "showShell");
            });
            check(cancelled.get().isCancelled(), "delete did not cancel restart");
            awaitNoServer(port);
            check(!Files.exists(java.nio.file.Paths.get("/proc/" + editedPid)), "deleted server survived");
            run.put("deleteCancelledWithoutReplacement", true);
        } finally {
            onMain(() -> {
                WebAppRestartSessions.get(context).cancel(app.getId().value());
                WebAppCommandSession.stop(app.getId(), TerminalTabsRegistry.getInstance().port());
                registry.delete(app.getId().value());
                invoke(activity, "refreshUserApps"); invoke(activity, "showShell");
            });
            Files.deleteIfExists(script);
        }
    }

    private void showWeb(WebAppDefinition app) {
        onMain(() -> {
            try {
                Method method = MainActivity.class.getDeclaredMethod("showWebApp", WebAppDefinition.class);
                method.setAccessible(true); method.invoke(activity, app);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
    }
    private void awaitWebPid(WebAppDefinition app, int pid) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            AtomicReference<String> rendered = new AtomicReference<>(); CountDownLatch evaluated = new CountDownLatch(1);
            onMain(() -> {
                try {
                    Object surface = ((Map<?, ?>) field(activity, "webAppSurfaces")).get(app.getId().value());
                    android.webkit.WebView view = surface == null ? null : (android.webkit.WebView) field(surface, "webView");
                    if (view == null) { evaluated.countDown(); return; }
                    view.evaluateJavascript("document.body ? document.body.innerText.trim() : ''", value -> {
                        rendered.set(value); evaluated.countDown();
                    });
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            evaluated.await(1, TimeUnit.SECONDS);
            if (("\"" + pid + "\"").equals(rendered.get())) return;
            check(SystemClock.elapsedRealtime() < deadline, "WebView did not render the new server PID " + pid);
            Thread.sleep(100);
        } while (true);
    }
    private void progress(String label) {
        Bundle status = new Bundle(); status.putString("stream", label + "\n"); sendStatus(0, status);
    }
    private void recreateActivity() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity previous = activity;
        onMain(previous::recreate);
        activity = (MainActivity) monitor.waitForActivityWithTimeout(10000);
        removeMonitor(monitor);
        check(activity != null && activity != previous, "Activity did not recreate");
        onMain(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    }
    private static boolean listening(int port) throws Exception {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250); return true;
        } catch (java.net.ConnectException refused) { return false; }
    }
    private static int httpPid(int port) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/").openConnection();
            connection.setConnectTimeout(250); connection.setReadTimeout(250);
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] value = new byte[32]; int count = input.read(value);
                return Integer.parseInt(new String(value, 0, count, StandardCharsets.UTF_8));
            }
        } catch (Exception unavailable) { return -1; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static int awaitPid(int port, int old) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        int pid;
        do {
            pid = httpPid(port);
            if (pid > 0 && pid != old) return pid;
            check(SystemClock.elapsedRealtime() < deadline, "server did not become ready"); Thread.sleep(100);
        } while (true);
    }
    private static void awaitNoServer(int port) throws Exception {
        await("server port release", () -> {
            try { return !listening(port); } catch (Exception error) { return false; }
        }, 15000);
        // A cancelled worker must remain cancelled after its delayed release fence settles.
        Thread.sleep(3000);
        check(!listening(port), "cancelled restart started a replacement");
    }

    private static final class DelayedBinding extends ContextWrapper {
        final CountDownLatch requested = new CountDownLatch(1);
        final Map<ServiceConnection, ServiceConnection> forwarding = new HashMap<>();
        volatile long observedAt, deliveredAt;
        volatile int pid;
        DelayedBinding(Context context) { super(context); }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            ServiceConnection proxy = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    observedAt = SystemClock.elapsedRealtime(); pid = displayPid(DelayedBinding.this);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        deliveredAt = SystemClock.elapsedRealtime();
                        connection.onServiceConnected(name, binder);
                    }, 1500);
                }
                public void onServiceDisconnected(ComponentName name) { connection.onServiceDisconnected(name); }
                public void onBindingDied(ComponentName name) { connection.onBindingDied(name); }
                public void onNullBinding(ComponentName name) { connection.onNullBinding(name); }
            };
            forwarding.put(connection, proxy);
            boolean bound = super.bindService(intent, proxy, flags); requested.countDown(); return bound;
        }
        @Override public void unbindService(ServiceConnection connection) {
            super.unbindService(forwarding.remove(connection));
        }
    }
    private static int displayPid(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (process.processName.equals("gh.nusashell.nusadesk:native_display")) return process.pid;
        }
        return -1;
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(500);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (java.io.OutputStream file = Files.newOutputStream(output.resolve(name))) {
            check(bitmap != null, "screenshot unavailable"); bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
        }
    }
    private void onMain(Runnable action) { runOnMainSync(action); waitForIdleSync(); }
    private static void await(String label, BooleanSupplier ready, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!ready.getAsBoolean()) {
            check(SystemClock.elapsedRealtime() < deadline, "timed out: " + label); Thread.sleep(50);
        }
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void invoke(Object owner, String name) {
        try { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
