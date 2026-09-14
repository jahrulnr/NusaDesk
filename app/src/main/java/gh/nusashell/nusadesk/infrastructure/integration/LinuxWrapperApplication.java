package gh.nusashell.nusadesk.infrastructure.integration;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import gh.nusashell.nusadesk.infrastructure.service.RuntimeWorkloadRegistry;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreBridgeCredential;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Application entry point that wires the guest-native runtime workload into
 * {@link RuntimeWorkloadRegistry} once per process.
 *
 * <p>Construction is deliberately cheap: {@code onCreate} only assembles the
 * dependency graph — vault-backed credential, SSH banner probe, trust store —
 * and registers the {@link GuestSshdWorkload}. No process, socket, or keystore
 * work happens until an explicit user action starts the foreground service and
 * the controller calls {@code workload.start}.</p>
 *
 * <p>The guest owns the SSH server: PRoot starts the curated guest's own
 * sshd/dropbear on an ephemeral loopback port, and the already-built Android
 * SSH client connects to that endpoint. When the installed rootfs ships no
 * supported daemon, the workload reports an explicit unavailable state rather
 * than faking a server.</p>
 *
 * <p>Callbacks into the not-thread-safe {@code RuntimeHostController} are
 * marshalled onto the main thread; the guest daemon's blocking setup/probe run
 * on a dedicated daemon worker.</p>
 */
public final class LinuxWrapperApplication extends Application {

    private Executor workloadExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        SshSecurityInitializer.initialize(this);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        workloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "guest-sshd-workload");
            thread.setDaemon(true);
            return thread;
        });

        RuntimeWorkloadRegistry.getInstance().register(new GuestSshdWorkload(
                this,
                new KeystoreBridgeCredential(this),
                new SshBannerProbe(),
                new SharedPreferencesHostKeyTrustStore(this),
                System::currentTimeMillis,
                workloadExecutor,
                mainHandler::post));
    }
}
