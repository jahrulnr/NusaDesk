package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PersistableBundle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreBridgeCredential;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshEndpoint;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;
import gh.nusashell.nusadesk.infrastructure.ssh.StrictHostKeyVerifier;

/**
 * The Android {@link JobService} backing the {@code jobscheduler.*} bridge
 * methods. The platform owns scheduling; this service owns the one thing a
 * guest job is allowed to do: run its bound script inside the live guest
 * session.
 *
 * <p>NusaDesk owns exactly one guest session, and a job only runs while that
 * session is alive. When the platform fires a trigger with the session down
 * ({@link RuntimeStatusBus} reports no running runtime with an endpoint) the
 * job records {@link CommsJobStore#RESULT_SESSION_DOWN} — the same typed fact
 * the {@code jobscheduler-unavailable:the guest session must be running}
 * bridge error describes — and finishes without rescheduling. No fake timer,
 * no host-side shell, no background claim.</p>
 *
 * <p>Execution reuses the existing authenticated loopback SSH channel into
 * the guest ({@code 127.0.0.1:22022}, vault-held credential, pinned host key)
 * — the same channel a terminal uses — so the script runs with the guest's
 * own userspace. The command is the single quoted script path; JobService
 * never forwards arbitrary host commands.</p>
 *
 * <p>Must be declared in the manifest as a service holding
 * {@code android.permission.BIND_JOB_SERVICE}; the coordinator owns that
 * declaration. Until then {@code JobScheduler.schedule} reports failure and
 * the module surfaces {@code jobscheduler-unavailable}.</p>
 */
public final class CommsJobService extends JobService {

    /** PersistableBundle key carrying the guest-side script path. */
    public static final String EXTRA_SCRIPT = "nusadesk.job.script";

    private static final String TAG = "CommsJobService";
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long AUTH_TIMEOUT_MS = 10_000;
    private static final long CHANNEL_OPEN_MS = 10_000;
    /** Hard cap for one job's guest-side run; a job that outlives it is stopped. */
    private static final long EXEC_TIMEOUT_MS = 300_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        int jobId = params.getJobId();
        PersistableBundle extras = params.getExtras();
        String script = extras == null ? null : extras.getString(EXTRA_SCRIPT);
        if (script == null || script.isEmpty()) {
            CommsJobStore.record(this, jobId, CommsJobStore.RESULT_FAILED, null);
            return false;
        }
        if (!guestSessionRunning()) {
            CommsJobStore.record(this, jobId, CommsJobStore.RESULT_SESSION_DOWN, null);
            jobFinished(params, false);
            return false;
        }
        worker.execute(() -> runJob(params, jobId, script));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        ClientSession session = activeSession.getAndSet(null);
        if (session != null) {
            try {
                session.close(true);
            } catch (RuntimeException ignored) {
                // Best-effort; the job is being stopped regardless.
            }
        }
        if (params != null) {
            CommsJobStore.record(this, params.getJobId(),
                    CommsJobStore.RESULT_FAILED, null);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /**
     * The single honest signal that the one guest session is alive: a runtime
     * in {@code RUNNING} with a concrete loopback endpoint. A foreground
     * service alone does not count.
     */
    private boolean guestSessionRunning() {
        HostRuntimeStatus status = RuntimeStatusBus.getInstance().current();
        return status != null && status.isRuntimeRunning();
    }

    private void runJob(JobParameters params, int jobId, String script) {
        Integer exitCode = null;
        String result = CommsJobStore.RESULT_FAILED;
        try {
            exitCode = execInGuest(script);
            result = CommsJobStore.RESULT_RAN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "job " + jobId + " exec failed: " + e.getClass().getSimpleName());
        }
        CommsJobStore.record(this, jobId, result, exitCode);
        jobFinished(params, false);
    }

    /**
     * Run {@code script} inside the guest over the authenticated SSH channel
     * and return the remote exit status. Output is drained-and-discarded
     * (bounded) so a chatty script cannot block on a full pipe; the exit code
     * is the only outcome that crosses back.
     */
    private int execInGuest(String script) throws Exception {
        SshSecurityInitializer.initialize(this);
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(new StrictHostKeyVerifier(
                new SharedPreferencesHostKeyTrustStore(this),
                LocalSshSessionFactory.pinnedHostKeyOnly(),
                LocalSshEndpoint.HOST_KEY_SCOPE,
                System::currentTimeMillis));
        client.start();
        try {
            ClientSession session = client
                    .connect(LocalSshSessionFactory.USERNAME,
                            LocalSshEndpoint.HOST, LocalSshEndpoint.PORT)
                    .verify(CONNECT_TIMEOUT_MS)
                    .getSession();
            try {
                activeSession.set(session);
                char[] password = new KeystoreVaultCredentialProvider(this)
                        .password(KeystoreBridgeCredential.CREDENTIAL_ID);
                try {
                    session.addPasswordIdentity(new String(password));
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
                session.auth().verify(AUTH_TIMEOUT_MS);

                try (ChannelExec channel =
                             session.createExecChannel(shellQuote(script))) {
                    channel.open().verify(CHANNEL_OPEN_MS);
                    drain(channel.getInvertedOut());
                    drain(channel.getInvertedErr());
                    channel.waitFor(
                            EnumSet.of(ClientChannelEvent.CLOSED),
                            EXEC_TIMEOUT_MS);
                    Integer status = channel.getExitStatus();
                    return status == null ? -1 : status.intValue();
                }
            } finally {
                activeSession.compareAndSet(session, null);
                session.close(true);
            }
        } finally {
            client.stop();
        }
    }

    /** Remote {@code /bin/sh -c} quoting for the single script-path word. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Drain a channel stream into a bounded sink so the remote side never
     * deadlocks on output; job output is not retained or surfaced.
     */
    private void drain(InputStream in) {
        if (in == null) {
            return;
        }
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                while (in.read(buffer) >= 0) {
                    // Discard: bounded by the stream, not retained.
                }
            } catch (IOException ignored) {
                // Channel closed mid-drain.
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
