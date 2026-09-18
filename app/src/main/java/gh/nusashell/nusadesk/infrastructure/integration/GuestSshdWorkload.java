package gh.nusashell.nusadesk.infrastructure.integration;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidCapabilityBridge;
import gh.nusashell.nusadesk.infrastructure.logs.GuestLogTrim;
import gh.nusashell.nusadesk.infrastructure.logs.SessionLogWriter;
import gh.nusashell.nusadesk.infrastructure.proot.GuestAwarenessReadmeWriter;
import gh.nusashell.nusadesk.infrastructure.proot.GuestEphemeralStateCleaner;
import gh.nusashell.nusadesk.infrastructure.proot.GuestServiceBridge;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshdBindFailureException;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshDaemon;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshdPidFile;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshdStartupLog;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshdStderrMonitor;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSupplementaryGroups;
import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLaunchException;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLaunchSpec;
import gh.nusashell.nusadesk.infrastructure.proot.ProotLauncher;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeWorkload;
import gh.nusashell.nusadesk.infrastructure.service.WorkloadListener;
import gh.nusashell.nusadesk.infrastructure.ssh.GuestSshHostKeyFiles;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshEndpoint;
import gh.nusashell.nusadesk.infrastructure.ssh.SshHostKeyFingerprintCodec;
import gh.nusashell.nusadesk.infrastructure.sshserver.SshBridgeCredential;
import gh.nusashell.nusadesk.infrastructure.sshserver.SshBridgeHealthProbe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Guest-native SSH workload: supervises the curated guest's own OpenSSH
 * daemon under PRoot, bound to the fixed local endpoint
 * {@code 127.0.0.1:22022} ({@link LocalSshEndpoint}, ADR-0013).
 *
 * <p>Start sequence (all on the serialised work executor):
 * <ol>
 *   <li>Reclaim any daemon a previous run left behind (a crashed host process
 *       SIGKILLs the PRoot tracer, which does <em>not</em> kill its tracee) and
 *       supersede the in-process previous session.</li>
 *   <li>Detect a supported daemon — the activated guest-SSH add-on overlay
 *       (bound at {@code /opt/lw-ssh}) or a rootfs-resident {@code sshd}; if
 *       absent the start fails with an explicit payload-not-installed reason
 *       rather than faking a server.</li>
 *   <li>Write the fixed {@code sshd_config} and ensure the persisted guest
 *       host key exists (generated host-side into the private overlay).</li>
 *   <li>Run a fixed guest setup script that provisions the {@code sshd}
 *       privsep account and pins the {@code root} password to the Keystore
 *       token via a direct {@code /etc/shadow} edit (PAM helpers cannot run
 *       inside the guest).</li>
 *   <li>Spawn the session under the locked PRoot spec: when the service
 *       bridge is installed the tracer's initial tracee is the vendored
 *       {@code lw-session-supervisor}, which backgrounds {@code systemctl
 *       init} and runs the daemon as its sibling under the same tracer —
 *       one session is one PRoot tree, the only shape in which a terminal
 *       {@code systemctl} can signal manager-run services (ADR-0024); without
 *       the bridge the daemon itself is the initial tracee. Require the
 *       daemon's own {@code Server listening on 127.0.0.1 port 22022.} report.
 *       There is one attempt: a fixed port that is already held is a typed
 *       {@link GuestSshdBindFailureException} and an honest {@code FAILED}, not
 *       a retry, and the host never attaches to a listener it did not start.</li>
 *   <li>Require a real SSH protocol banner on the reported endpoint, pin the
 *       host public key read from the guest rootfs into the client trust store,
 *       then publish the readiness frame. A pin that cannot be stored fails the
 *       start: the local-only client refuses an unpinned key, so an endpoint
 *       the app cannot pin is not attachable and must not be reported ready.</li>
 *   <li>Keep draining the daemon's output: end-of-file while the session is
 *       current means the guest daemon died, which is reported as a failure so
 *       a dead daemon can never be displayed as {@code RUNNING}.</li>
 * </ol>
 *
 * <p>Teardown signals the daemon itself through its persisted pid file
 * ({@link GuestSshdPidFile}) and then tears down the PRoot tracer. Killing the
 * tracer alone is not a stop: verified on-device, both SIGTERM and SIGKILL to
 * the tracer leave the guest daemon listening, orphaned and holding its port.
 * Every stop path follows the same rule, including one with no daemon tracked
 * in this process: the pid file is reclaimed so an orphan cannot keep its
 * loopback port, and an unexpected daemon-output end-of-file tears the daemon
 * down before the failure is reported.</p>
 *
 * <p>Secrets never cross this class unprotected: the Keystore token reaches the
 * guest only through a fixed environment variable consumed by the internal
 * setup script, is never an argv element (visible to {@code ps}), and is zeroed
 * after use.</p>
 */
public final class GuestSshdWorkload implements RuntimeWorkload {

    private static final String TAG = "GuestSshdWorkload";
    private static final long SETUP_TIMEOUT_MILLIS = 30_000L;
    private static final long BIND_REPORT_TIMEOUT_MILLIS = 10_000L;
    private static final long HEALTH_TIMEOUT_MILLIS = 10_000L;
    /** Short probe used only to describe a bind conflict honestly. */
    private static final long CONFLICT_PROBE_TIMEOUT_MILLIS = 1_000L;
    private static final long STOP_TIMEOUT_MILLIS = 5_000L;
    /** Short confirmation probe after a stop; a live answer is logged loudly. */
    private static final long STOP_VERIFY_TIMEOUT_MILLIS = 1_000L;
    /** How long the manager's pid file may take to appear after exec. */
    private static final long MANAGER_LAUNCH_TIMEOUT_MILLIS = 15_000L;
    /**
     * The manager's graceful-stop window on teardown and reclaim: SIGTERM
     * makes it run every enabled service's stop steps before exiting.
     */
    private static final long MANAGER_STOP_TIMEOUT_MILLIS = 8_000L;
    /** Cadence of the guest journal-file sweep that bounds service log size. */
    private static final long LOG_SWEEP_INTERVAL_MILLIS = 60_000L;
    /** Bound on one journal file before the sweeper tail-trims it in place. */
    private static final long JOURNAL_SWEEP_MAX_BYTES = 2L * 1024 * 1024;

    private final Context context;
    private final ProotLauncher launcher;
    private final Path filesDir;
    private final SshBridgeCredential credential;
    private final SshBridgeHealthProbe healthProbe;
    private final HostKeyTrustStore trustStore;
    private final LongSupplier clock;
    private final Executor workExecutor;
    private final Executor callbackExecutor;

    /** The daemon this workload supervises; written on the work executor only. */
    private volatile ActiveDaemon active;

    /**
     * One supervised daemon: the PRoot tracer process, the endpoint it was
     * proven to serve, and the listener of the session that owns it. The
     * session's guest service manager attaches here once it is up, so every
     * teardown path that owns the daemon also owns the manager.
     */
    private static final class ActiveDaemon {
        private final Process process;
        private final GuestSshDaemon daemon;
        private final Path pidFile;
        private final RuntimePort endpoint;
        private final WorkloadListener listener;
        private final GuestSshdStderrMonitor monitor;
        private final AndroidCapabilityBridge capabilityBridge;
        private final SessionLogWriter sessionLog;
        private volatile boolean stopRequested;
        private volatile ActiveServiceManager serviceManager;

        ActiveDaemon(Process process, GuestSshDaemon daemon, Path pidFile,
                     RuntimePort endpoint, WorkloadListener listener,
                     GuestSshdStderrMonitor monitor,
                     AndroidCapabilityBridge capabilityBridge,
                     SessionLogWriter sessionLog) {
            this.process = process;
            this.daemon = daemon;
            this.pidFile = pidFile;
            this.endpoint = endpoint;
            this.listener = listener;
            this.monitor = monitor;
            this.capabilityBridge = capabilityBridge;
            this.sessionLog = sessionLog;
        }
    }

    /**
     * One supervised guest service manager: the vendored {@code systemctl
     * init} loop running as a tracee inside the session's shared PRoot tree
     * (ADR-0024). The supervisor script — the tracer's initial tracee — owns
     * the manager's lifecycle; the host tracks it through two files the
     * script maintains: the pid file naming the real manager tracee for
     * signalling, and the exit marker whose appearance means the manager
     * exited while the session daemon lives on.
     */
    private static final class ActiveServiceManager {
        private final GuestServiceBridge bridge;
        private final Path pidFile;
        private final Path exitFile;
        private volatile boolean stopRequested;

        ActiveServiceManager(GuestServiceBridge bridge, Path pidFile, Path exitFile) {
            this.bridge = bridge;
            this.pidFile = pidFile;
            this.exitFile = exitFile;
        }
    }

    public GuestSshdWorkload(Context context,
                             SshBridgeCredential credential,
                             SshBridgeHealthProbe healthProbe,
                             HostKeyTrustStore trustStore,
                             LongSupplier clock,
                             Executor workExecutor,
                             Executor callbackExecutor) {
        if (context == null || credential == null || healthProbe == null
                || trustStore == null || clock == null
                || workExecutor == null || callbackExecutor == null) {
            throw new IllegalArgumentException("all dependencies are required");
        }
        this.context = context.getApplicationContext();
        this.launcher = new ProotLauncher(this.context);
        this.filesDir = this.context.getFilesDir().toPath();
        this.credential = credential;
        this.healthProbe = healthProbe;
        this.trustStore = trustStore;
        this.clock = clock;
        this.workExecutor = workExecutor;
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public String workloadId() {
        return "ubuntu-base-arm64/guest-sshd";
    }

    @Override
    public void start(SessionSnapshot session, WorkloadListener listener) {
        if (session == null || listener == null) {
            throw new IllegalArgumentException("session and listener are required");
        }
        workExecutor.execute(() -> startInternal(session, listener));
    }

    @Override
    public void stop(WorkloadListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        workExecutor.execute(() -> {
            // Covers the reclaimUntrackedDaemon path (active == null) too: a
            // previous session may have started the resolver refresh.
            launcher.stopResolverRefresh();
            ActiveDaemon daemon = active;
            active = null;
            if (daemon != null) {
                teardown(daemon);
            } else {
                reclaimUntrackedDaemon();
                clearGuestTmpAfterStop();
            }
            callbackExecutor.execute(listener::onStopped);
        });
    }

    private void startInternal(SessionSnapshot session, WorkloadListener listener) {
        Path rootfs = ProotPaths.activeRootfsPath(filesDir, session.getAppId());
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestSshAddon();
        Path overlay = ProotPaths.activeAddonPath(filesDir, profile.getAddonId());
        GuestSshDaemon daemon = GuestSshDaemon.detect(rootfs, overlay);
        if (daemon == null) {
            fail(listener, "guest SSH payload is not installed; install the "
                    + profile.getDisplayName() + " add-on to enable the "
                    + "guest-native SSH session");
            return;
        }
        // The service bridge is additive: the SSH session does not depend on
        // it, so an absent overlay only means no `systemctl`, not a failure.
        Path serviceOverlay = ProotPaths.activeAddonPath(
                filesDir, CuratedRuntimeCatalog.guestServiceBridge().getAddonId());
        GuestServiceBridge bridge = GuestServiceBridge.detect(serviceOverlay);

        // A previous session (or a crashed host process) can leave a live guest
        // daemon behind: killing the PRoot tracer does not kill its tracee, so
        // reclaim it before starting another one.
        ActiveDaemon previous = active;
        active = null;
        if (previous != null) {
            teardown(previous);
        }
        Path pidFile = daemon.resolvePidFile(rootfs);
        reclaimOrphanedDaemon(daemon, pidFile);
        if (bridge != null) {
            reclaimOrphanedServiceManager(bridge, bridge.resolvePidFile(rootfs));
        }
        try {
            // The active rootfs is persistent app storage, while guest /tmp is
            // session-temporary by contract. Clear stale files after any
            // previous tracer/tracee has been reclaimed, before a new guest
            // process can create or depend on temporary state.
            GuestEphemeralStateCleaner.clearGuestTmp(rootfs);
        } catch (IOException e) {
            fail(listener, "could not reset guest temporary storage: " + e.getMessage());
            return;
        }

        char[] token = credential.token();
        if (token == null || token.length == 0) {
            fail(listener, "local SSH credential is absent");
            return;
        }

        AndroidCapabilityBridge capabilityBridge = new AndroidCapabilityBridge(context);
        SessionLogWriter sessionLog = null;
        try {
            capabilityBridge.start();
            if (bridge != null) {
                // Host-side idempotent wiring: link the overlay's public paths
                // into the rootfs and create the /run/systemd/system marker
                // before any guest process can ask for them.
                bridge.wireInto(rootfs);
            }
            GuestAwarenessReadmeWriter.ensure(rootfs, appVersion());
            writeDaemonConfig(rootfs, daemon);
            PublicKey hostKey = ensureHostKey(rootfs, daemon);
            // Between "boots" the previous session log becomes boot.log.1 and
            // each service journal rotates the same way — one generation, like
            // journalctl -b -1. Runs before any guest process holds them open.
            SessionLogWriter.rotateAtSessionStart(rootfs);
            sessionLog = openSessionLog(rootfs);
            runGuestSetup(session, daemon, token, inheritedGroupEntries(), sessionLog);
            launchVerifiedDaemon(session, listener, daemon, pidFile, hostKey, bridge,
                    capabilityBridge, rootfs, sessionLog);
        } catch (GuestSshdBindFailureException e) {
            Log.e(TAG, "guest sshd fixed-port bind conflict on "
                    + e.getEndpoint().getHost() + ":" + e.getEndpoint().getPort(), e);
            fail(listener, e.getReason());
        } catch (Exception e) {
            Log.e(TAG, "guest sshd start failed", e);
            fail(listener, "guest sshd start failed: " + e.getMessage());
        } finally {
            // Once launchVerifiedDaemon hands the bridge to active, teardown owns
            // it. All rejected/failed starts close the local instance here.
            ActiveDaemon current = active;
            if (current == null || current.capabilityBridge != capabilityBridge) {
                capabilityBridge.close();
            }
            // Same handoff for the boot-log writer: a rejected start closes it
            // here; a supervised one is closed by teardown.
            if (sessionLog != null
                    && (current == null || current.sessionLog != sessionLog)) {
                sessionLog.close();
            }
            zero(token);
        }
    }

    /**
     * Opens the session's boot-log writer inside the rootfs. A failure only
     * costs the log file — never the session it would describe — so it is
     * logged and the session continues without persistence.
     */
    private SessionLogWriter openSessionLog(Path rootfs) {
        try {
            return new SessionLogWriter(rootfs, clock);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "session console log unavailable; boot log disabled", e);
            return null;
        }
    }

    /**
     * Launch the daemon on the fixed loopback port and require its own log to
     * prove it bound that port, then require a real SSH banner on it.
     *
     * <p>Exactly one attempt: the port is a documented product constant
     * ({@link LocalSshEndpoint}, ADR-0013), so a port that is already held is a
     * typed {@link GuestSshdBindFailureException} rather than a reason to move
     * to a port the rest of the app does not know about. The daemon's own
     * report is what attributes the endpoint to the process this host launched;
     * a listener this host did not start is never published, probed as a
     * fallback, or attached to.</p>
     */
    private void launchVerifiedDaemon(SessionSnapshot session, WorkloadListener listener,
                                      GuestSshDaemon daemon, Path pidFile, PublicKey hostKey,
                                      GuestServiceBridge bridge,
                                      AndroidCapabilityBridge capabilityBridge,
                                      Path rootfs, SessionLogWriter sessionLog)
            throws IOException, GuestSshdBindFailureException {
        RuntimePort endpoint = LocalSshEndpoint.endpoint();
        Files.deleteIfExists(pidFile);
        if (bridge != null) {
            // The session supervisor maintains both files; a stale pair must
            // never be mistaken for this run's manager.
            Files.deleteIfExists(bridge.resolvePidFile(rootfs));
            Files.deleteIfExists(bridge.resolveExitFile(rootfs));
        }
        Process process = launchDaemon(session, daemon, endpoint.getPort(), bridge, capabilityBridge,
                rootfs);
        // Until the daemon is handed over to `active`, every exit path tears the
        // tracer down: a rejected start must not leave a guest daemon (or a
        // tracer holding the fixed port) behind.
        boolean supervised = false;
        try {
            Consumer<String> lineSink = sessionLog == null ? null : sessionLog::append;
            GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(
                    process.getErrorStream(), process.getInputStream(),
                    () -> onDaemonOutputClosed(process), lineSink);
            monitor.start();
            GuestSshdStartupLog.Event event = monitor.awaitBindEvent(BIND_REPORT_TIMEOUT_MILLIS);
            if (event == null) {
                String detail = monitor.isClosed()
                        ? "exited before reporting a listener: " + tail(monitor)
                        : "did not report a listener within " + BIND_REPORT_TIMEOUT_MILLIS + " ms";
                throw new IOException("guest " + daemon.label() + " " + detail);
            }
            if (event.getKind() == GuestSshdStartupLog.Kind.BIND_FAILED) {
                throw new GuestSshdBindFailureException(endpoint, probeForListener(endpoint));
            }
            if (!endpoint.getHost().equals(event.getHost())
                    || endpoint.getPort() != event.getPort()) {
                // The daemon reported something other than the fixed port it was
                // handed: never publish an endpoint we did not verify.
                throw new IOException("guest " + daemon.label() + " reported unexpected listener "
                        + event.getHost() + ":" + event.getPort() + " instead of "
                        + endpoint.getHost() + ":" + endpoint.getPort());
            }
            if (!healthProbe.isHealthy(
                    endpoint.getHost(), endpoint.getPort(), HEALTH_TIMEOUT_MILLIS)) {
                throw new IOException("guest " + daemon.label()
                        + " did not emit an SSH banner on "
                        + endpoint.getHost() + ":" + endpoint.getPort());
            }

            pinGuestHostKey(hostKey, endpoint);
            active = new ActiveDaemon(process, daemon, pidFile, endpoint, listener, monitor,
                    capabilityBridge, sessionLog);
            supervised = true;
            // Keep the guest resolver current while the daemon runs: the bound
            // resolv.conf is rewritten in place when the active network changes.
            launcher.startResolverRefresh();
            // The shared tracer's supervisor is already backgrounding
            // `systemctl init`; attach the host's view of it (pid/exit files)
            // so its lifecycle is logged and its services are stopped before
            // the daemon on teardown (ADR-0024).
            if (bridge != null) {
                attachServiceManager(active, bridge, rootfs);
            }
            startLogSweeper(active, rootfs);
            Log.i(TAG, "guest " + daemon.label() + " ready on "
                    + endpoint.getHost() + ":" + endpoint.getPort());
            callbackExecutor.execute(() -> listener.onReadiness(new ReadinessFrame(
                    ReadinessFrame.SUPPORTED_SCHEMA,
                    session.getAppId(), session.getAppVersion(),
                    session.getSessionId(), endpoint,
                    ReadinessHealth.HEALTHY, clock.getAsLong())));
        } finally {
            if (!supervised) {
                // A rejected start can still leave a bound guest daemon behind:
                // the daemon may have written its pid file and bound the fixed
                // port before a health-probe failure, an unexpected listener
                // report, a host-key pin failure, or a bind-event error. Killing
                // the PRoot tracer alone does not kill its tracee (verified
                // on-device), so signal the daemon through its pid file — with
                // the same identity check every stop path uses — before tearing
                // the tracer down. An orphan must not keep holding its port.
                reclaimRejectedDaemon(daemon, pidFile, process, bridge, rootfs);
            }
        }
    }

    /**
     * Reclaim the guest daemon after a rejected start, mirroring the reclaim
     * half of {@link #teardown(ActiveDaemon)}. The daemon may have bound (and
     * written its pid file) before the start was rejected, and stopping the
     * PRoot tracer does not stop its tracee. The pid is only signalled when its
     * command line names this daemon's binary, so a stale or reused pid file
     * can never signal an unrelated process. The pin is not cleared here: in
     * the rejected path no pin was ever stored (a successful pin is what hands
     * the daemon over to {@code active}).
     */
    private void reclaimRejectedDaemon(GuestSshDaemon daemon, Path pidFile, Process process,
                                       GuestServiceBridge bridge, Path rootfs) {
        if (bridge != null) {
            // The shared tracer's supervisor may already have started the
            // manager — and it enabled services — before the start was
            // rejected. Signal the manager first so its graceful stop runs
            // while the tree is still intact.
            Long initPid = GuestSshdPidFile.read(bridge.resolvePidFile(rootfs));
            if (initPid != null && signalGuestProcess(
                    initPid, bridge.getBinaryPath(), bridge.label())) {
                waitForExitMarker(bridge.resolveExitFile(rootfs), MANAGER_STOP_TIMEOUT_MILLIS);
            }
        }
        Long pid = GuestSshdPidFile.read(pidFile);
        if (pid != null && signalGuestProcess(pid, daemon.getBinaryPath(), "guest sshd")) {
            Log.i(TAG, "signalled rejected guest sshd pid " + pid + " to stop");
        }
        stopProcess(process);
    }

    /**
     * Whether something is already answering on the fixed endpoint, used only
     * to describe a bind conflict honestly. A positive answer never becomes an
     * endpoint: the host does not attach to a listener it did not start.
     */
    private boolean probeForListener(RuntimePort endpoint) {
        boolean answering = healthProbe.isHealthy(
                endpoint.getHost(), endpoint.getPort(), CONFLICT_PROBE_TIMEOUT_MILLIS);
        if (answering) {
            Log.w(TAG, endpoint.getHost() + ":" + endpoint.getPort()
                    + " already answers; not attaching to it");
        }
        return answering;
    }

    private Process launchDaemon(SessionSnapshot session, GuestSshDaemon daemon, int port,
                                 GuestServiceBridge bridge,
                                 AndroidCapabilityBridge capabilityBridge,
                                 Path rootfs)
            throws IOException {
        try {
            // One session = one PRoot tree (ADR-0024): with the service bridge
            // installed, the tracer's initial tracee is the session supervisor
            // which backgrounds `systemctl init` and runs the daemon as its
            // sibling — PRoot mediates kill(2), so a terminal `systemctl stop`
            // only reaches a manager-run service when both share the tree.
            List<String> argv = bridge != null
                    ? bridge.sessionArgv(daemon.daemonArgv(port))
                    : daemon.daemonArgv(port);
            boolean fakeRoot = daemon.requiresFakeRoot()
                    || (bridge != null && bridge.requiresFakeRoot());
            List<ProotBindMount> binds = new ArrayList<>(daemon.requiredBinds());
            binds.addAll(capabilityBridge.requiredBinds(rootfs));
            Map<String, String> environment = daemonEnv(daemon);
            environment.putAll(capabilityBridge.environment());
            ProotLaunchSpec spec = launcher.buildSpec(
                    session.getAppId(), argv, binds, environment, fakeRoot);
            return launcher.launchProcess(spec);
        } catch (ProotLaunchException e) {
            throw new IOException("guest " + daemon.label() + " launch rejected", e);
        }
    }

    /**
     * Attach the guest service manager to the verified session: the shared
     * tracer's supervisor is already backgrounding {@code systemctl init} and
     * maintaining its pid/exit files, so the host's part is a watcher thread
     * that turns those files into honest lifecycle logging — the pid file's
     * appearance is the "launched" signal, the exit marker's appearance is
     * the "exited" signal, and a manager that never reports a pid is logged
     * rather than silently absent.
     */
    private void attachServiceManager(ActiveDaemon daemon, GuestServiceBridge bridge,
                                      Path rootfs) {
        ActiveServiceManager manager = new ActiveServiceManager(
                bridge, bridge.resolvePidFile(rootfs), bridge.resolveExitFile(rootfs));
        daemon.serviceManager = manager;
        Thread watcher = new Thread(
                () -> watchServiceManager(daemon, manager), "lw-services-init-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Bounds the per-service journal files while the session runs. The
     * vendored {@code systemctl3} appends service output to
     * {@code /var/log/journal/<unit>.log} without any size policy, and its
     * services hold the files open in append mode — so the sweeper trims
     * them in place ({@link GuestLogTrim}) rather than rotating by rename,
     * which would orphan the open descriptor and let the real file keep
     * growing under an unlinked inode.
     */
    private void startLogSweeper(ActiveDaemon daemon, Path rootfs) {
        Thread sweeper = new Thread(
                () -> sweepGuestLogs(daemon, rootfs), "guest-log-sweep");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private void sweepGuestLogs(ActiveDaemon daemon, Path rootfs) {
        while (active == daemon && !daemon.stopRequested) {
            if (!sleepQuietly(LOG_SWEEP_INTERVAL_MILLIS)) {
                return;
            }
            for (String journalDir : SessionLogWriter.JOURNAL_DIRS) {
                trimJournalDir(rootfs.resolve(journalDir));
            }
        }
    }

    /** Tail-trims every overgrown {@code *.log} in one journal dir; never fails. */
    private static void trimJournalDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.log")) {
            for (Path file : files) {
                try {
                    if (Files.size(file) > JOURNAL_SWEEP_MAX_BYTES) {
                        GuestLogTrim.shrinkToTail(file, SessionLogWriter.KEEP_BYTES);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // One unreadable file must not stop the sweep.
                }
            }
        } catch (IOException ignored) {
            // A journal dir that cannot be listed simply is not swept.
        }
    }

    private void watchServiceManager(ActiveDaemon daemon, ActiveServiceManager manager) {
        boolean launched = false;
        for (long waited = 0; waited < MANAGER_LAUNCH_TIMEOUT_MILLIS; waited += 500L) {
            if (managerGone(daemon, manager)) {
                return;
            }
            if (Files.isRegularFile(manager.pidFile)) {
                launched = true;
                break;
            }
            if (Files.isRegularFile(manager.exitFile)) {
                break;
            }
            if (!sleepQuietly(500L)) {
                return;
            }
        }
        if (managerGone(daemon, manager)) {
            return;
        }
        if (launched) {
            Log.i(TAG, "guest service manager launched; enabled services are autostarting");
        } else {
            Log.w(TAG, "guest service manager did not report a pid within "
                    + MANAGER_LAUNCH_TIMEOUT_MILLIS + " ms");
        }
        while (!managerGone(daemon, manager)
                && !Files.isRegularFile(manager.exitFile)) {
            if (!sleepQuietly(500L)) {
                return;
            }
        }
        if (!managerGone(daemon, manager)) {
            workExecutor.execute(() -> onServiceManagerExited(daemon, manager));
        }
    }

    private boolean managerGone(ActiveDaemon daemon, ActiveServiceManager manager) {
        return active != daemon || daemon.serviceManager != manager
                || daemon.stopRequested || manager.stopRequested;
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The manager's exit marker appeared while its session is still current:
     * the session daemon keeps running — the terminal and endpoint do not
     * depend on the manager — but the loss is logged loudly and the manager
     * detached so teardown does not signal a stale pid.
     */
    private void onServiceManagerExited(ActiveDaemon daemon, ActiveServiceManager manager) {
        if (managerGone(daemon, manager)) {
            return;
        }
        daemon.serviceManager = null;
        Log.w(TAG, "guest service manager exited ("
                + readExitMarker(manager) + "); enabled services are no longer supervised");
    }

    private static String readExitMarker(ActiveServiceManager manager) {
        try {
            return "exit " + new String(
                    Files.readAllBytes(manager.exitFile), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return "exit marker unreadable";
        }
    }

    /**
     * Stop the supervised service manager: signal the guest init tracee so it
     * shuts its enabled services down. The manager shares the session daemon's
     * tracer, so there is no process to destroy here — the bounded wait for
     * the supervisor's exit marker is the manager's graceful-stop window
     * before the daemon itself is stopped.
     */
    private void stopServiceManager(ActiveServiceManager manager) {
        Long pid = GuestSshdPidFile.read(manager.pidFile);
        if (pid == null || !signalGuestProcess(pid, manager.bridge.getBinaryPath(),
                manager.bridge.label())) {
            return;
        }
        Log.i(TAG, "signalled guest service manager pid " + pid + " to stop");
        waitForExitMarker(manager.exitFile, MANAGER_STOP_TIMEOUT_MILLIS);
    }

    /**
     * Bounded wait for the supervisor's manager-exit marker. Returning on
     * timeout is fine: the caller proceeds to stop the daemon, and the
     * supervisor's own post-daemon shutdown bounds whatever the manager still
     * runs inside the tree.
     */
    private static void waitForExitMarker(Path exitFile, long timeoutMillis) {
        for (long waited = 0; waited < timeoutMillis
                && !Files.isRegularFile(exitFile); waited += 100L) {
            if (!sleepQuietly(100L)) {
                return;
            }
        }
    }

    /**
     * Reclaim a service manager a previous session or crashed host process
     * left behind, mirroring {@link #reclaimOrphanedDaemon}: the pid file is
     * trusted only together with the tracee's own command line. The manager's
     * SIGTERM stops its enabled services, so wait for the tracee to exit
     * before the new session wires fresh status state — a manager still
     * writing shutdown marks must not race the wipe.
     */
    private void reclaimOrphanedServiceManager(GuestServiceBridge bridge, Path pidFile) {
        if (!Files.isRegularFile(pidFile)) {
            return;
        }
        Long pid = GuestSshdPidFile.read(pidFile);
        if (pid == null || !signalGuestProcess(pid, bridge.getBinaryPath(), bridge.label())) {
            return;
        }
        Log.i(TAG, "reclaimed orphaned guest service manager pid " + pid);
        for (long waited = 0; waited < MANAGER_STOP_TIMEOUT_MILLIS
                && processAlive(pid); waited += 100L) {
            if (!sleepQuietly(100L)) {
                return;
            }
        }
    }

    /** Whether the host can still reach {@code pid} (kill(2) probe, no signal). */
    private static boolean processAlive(long pid) {
        try {
            Os.kill((int) pid, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stop a daemon a previous run left behind. A host-process crash SIGKILLs
     * the PRoot tracer without killing its tracee, so the pid file can still
     * point at a live daemon holding a loopback port.
     */
    private void reclaimOrphanedDaemon(GuestSshDaemon daemon, Path pidFile) {
        if (!Files.isRegularFile(pidFile)) {
            return;
        }
        Long pid = GuestSshdPidFile.read(pidFile);
        if (pid != null && signalGuestProcess(pid, daemon.getBinaryPath(), "guest sshd")) {
            Log.i(TAG, "reclaimed orphaned guest sshd pid " + pid);
            for (long waited = 0; waited < STOP_TIMEOUT_MILLIS && processAlive(pid);
                 waited += 100L) {
                if (!sleepQuietly(100L)) {
                    return;
                }
            }
        }
    }

    /**
     * Reclaim a guest daemon this process does not track. The service calls
     * {@link #stop} to reconcile a session persisted before a host-process
     * death, and at that point there is no {@link ActiveDaemon}: the tracer is
     * gone while its tracee can still be listening. Without this reclaim the
     * orphan would keep its loopback port until the next start. The pid is only
     * signalled when its command line names this daemon's binary, so a stale
     * pid file can never signal an unrelated process.
     */
    private void reclaimUntrackedDaemon() {
        String appId = CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId();
        Path rootfs = ProotPaths.activeRootfsPath(filesDir, appId);
        Path overlay = ProotPaths.activeAddonPath(
                filesDir, CuratedRuntimeCatalog.guestSshAddon().getAddonId());
        GuestSshDaemon daemon = GuestSshDaemon.detect(rootfs, overlay);
        if (daemon != null) {
            reclaimOrphanedDaemon(daemon, daemon.resolvePidFile(rootfs));
        }
        GuestServiceBridge bridge = GuestServiceBridge.detect(ProotPaths.activeAddonPath(
                filesDir, CuratedRuntimeCatalog.guestServiceBridge().getAddonId()));
        if (bridge != null) {
            reclaimOrphanedServiceManager(bridge, bridge.resolvePidFile(rootfs));
        }
    }

    /**
     * Stop the supervised daemon: signal the guest process itself, then tear
     * down the tracer, then confirm the endpoint no longer answers.
     */
    private void teardown(ActiveDaemon daemon) {
        // The supervised runtime is going away: stop rewriting the guest
        // resolver. Idempotent, so safe alongside the stop() call below.
        launcher.stopResolverRefresh();
        daemon.stopRequested = true;
        // The service manager goes down first so its SIGTERM can run the
        // enabled services' stop steps while the session is still intact.
        ActiveServiceManager manager = daemon.serviceManager;
        daemon.serviceManager = null;
        if (manager != null) {
            manager.stopRequested = true;
            stopServiceManager(manager);
        }
        Long pid = GuestSshdPidFile.read(daemon.pidFile);
        if (pid != null && signalGuestProcess(pid, daemon.daemon.getBinaryPath(),
                "guest sshd")) {
            Log.i(TAG, "signalled guest sshd pid " + pid + " to stop");
        }
        stopProcess(daemon.process);
        // The tracer is down so the pipes are at EOF; a late drain line landing
        // after close is dropped rather than a failure.
        if (daemon.sessionLog != null) {
            daemon.sessionLog.close();
        }
        if (daemon.capabilityBridge != null) {
            daemon.capabilityBridge.close();
        }
        clearGuestTmpAfterStop();
        clearPin(daemon.endpoint);
        if (healthProbe.isHealthy(
                daemon.endpoint.getHost(), daemon.endpoint.getPort(), STOP_VERIFY_TIMEOUT_MILLIS)) {
            Log.w(TAG, "guest sshd still answers on " + daemon.endpoint.getHost() + ":"
                    + daemon.endpoint.getPort() + " after stop");
        } else {
            Log.i(TAG, "guest sshd stopped; 127.0.0.1:" + daemon.endpoint.getPort()
                    + " no longer answers");
        }
    }

    /**
     * Send SIGTERM to a guest pid, but only when the process at that pid is
     * really the process {@code binaryPath} names (a stale pid file must never
     * signal an unrelated process). A pid file whose process is gone is simply
     * stale.
     *
     * @return true when the signal was delivered
     */
    private boolean signalGuestProcess(long pid, String binaryPath, String label) {
        String commandLine = readCommandLine(pid);
        if (commandLine == null) {
            return false;
        }
        if (!GuestSshdPidFile.matchesDaemon(commandLine, binaryPath)) {
            Log.w(TAG, "pid " + pid + " is not this " + label + "; leaving it alone");
            return false;
        }
        try {
            Os.kill((int) pid, OsConstants.SIGTERM);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not signal " + label + " pid " + pid, e);
            return false;
        }
    }

    /** Read {@code /proc/<pid>/cmdline}; {@code null} when the process is gone. */
    private static String readCommandLine(long pid) {
        try {
            byte[] raw = Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/" + pid + "/cmdline"));
            String text = GuestSshdPidFile.commandLineText(raw);
            return text.isEmpty() ? null : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Clear session-temporary guest state after the tracer has stopped. */
    private void clearGuestTmpAfterStop() {
        Path rootfs = ProotPaths.activeRootfsPath(
                filesDir, CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId());
        if (!Files.isDirectory(rootfs)) {
            return;
        }
        try {
            GuestEphemeralStateCleaner.clearGuestTmp(rootfs);
        } catch (IOException e) {
            // Stop remains honest even when cleanup is blocked; the next start
            // retries before launching any guest process.
            Log.w(TAG, "could not clear guest temporary storage after stop", e);
        }
    }

    /** Destroy the tracer process, escalating to SIGKILL after a bounded wait. */
    private static void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * The daemon's output reached EOF: if this daemon is still the supervised
     * one, the guest died under a live session and the host must stop claiming
     * {@code RUNNING}.
     *
     * <p>The pipe closing means the daemon tree is gone, but a tracee can
     * outlive its tracer (the host's own crash behaviour), so the daemon is
     * signalled through its pid file as well: a surviving listener must not be
     * left holding its port as an unmanaged orphan.</p>
     */
    private void onDaemonOutputClosed(Process process) {
        workExecutor.execute(() -> {
            ActiveDaemon daemon = active;
            if (daemon == null || daemon.process != process || daemon.stopRequested) {
                return;
            }
            active = null;
            daemon.stopRequested = true;
            String detail = exitDetail(process);
            Log.e(TAG, "guest " + daemon.daemon.label() + " exited unexpectedly" + detail);
            teardown(daemon);
            callbackExecutor.execute(() -> daemon.listener.onFailed(
                    "guest " + daemon.daemon.label() + " exited unexpectedly" + detail));
        });
    }

    private static String exitDetail(Process process) {
        try {
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                return " (exit code " + process.exitValue() + ")";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }

    /** Read the installed APK version that owns the generated guest README. */
    private String appVersion() throws IOException {
        try {
            String version;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(),
                                PackageManager.PackageInfoFlags.of(0L)).versionName;
            } else {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            }
            if (version == null || version.trim().isEmpty()) {
                throw new IOException("installed APK has no version name");
            }
            return version;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("could not read installed APK version", e);
        }
    }

    /** Extra env for the daemon launch: the overlay library path when used. */
    private Map<String, String> daemonEnv(GuestSshDaemon daemon) {
        Map<String, String> env = new HashMap<>();
        String libraryPath = daemon.overlayLibraryPath();
        if (libraryPath != null) {
            env.put("LD_LIBRARY_PATH", libraryPath);
        }
        return env;
    }

    /**
     * Writes the fixed {@code sshd_config} host-side into the daemon's config
     * dir (overlay {@code etc/} or rootfs {@code etc/ssh}) before every start.
     */
    private void writeDaemonConfig(Path rootfs, GuestSshDaemon daemon) throws IOException {
        Path config = daemon.resolveGuestFile(daemon.configPath(), rootfs);
        Files.createDirectories(config.getParent());
        Files.write(config, daemon.configText().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Ensures the guest host key exists and returns its public half for
     * pinning. The key is generated host-side (the overlay deliberately ships
     * no {@code ssh-keygen}) and persisted inside the app-private overlay so
     * the guest presents a stable identity across restarts.
     */
    private PublicKey ensureHostKey(Path rootfs, GuestSshDaemon daemon) throws IOException {
        try {
            return GuestSshHostKeyFiles.ensure(
                    daemon.resolveGuestFile(daemon.hostKeyPath(), rootfs),
                    daemon.resolveGuestFile(daemon.hostPublicKeyPath(), rootfs),
                    "linuxwrapper-guest");
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("guest host key generation failed", e);
        }
    }

    /**
     * Runs the one-shot guest setup inside the guest: create the privsep
     * directory/account, name the inherited Android group IDs, and set
     * {@code root}'s password to the token. Bounded wait; any non-zero exit or
     * timeout fails the start honestly.
     *
     * <p>Both output pipes are drained into the session log — the setup
     * script's output is the first section of the boot log — and the drain
     * keeps a verbose script from wedging on a full pipe.</p>
     */
    private void runGuestSetup(SessionSnapshot session, GuestSshDaemon daemon, char[] token,
                               List<String> groupEntries, SessionLogWriter sessionLog)
            throws IOException {
        ProotLaunchSpec setup;
        Process process;
        try {
            setup = launcher.buildSpec(session.getAppId(), daemon.setupArgv(),
                    daemon.requiredBinds(), setupEnv(daemon, token, groupEntries),
                    daemon.requiresFakeRoot());
            process = launcher.launchProcess(setup);
        } catch (ProotLaunchException e) {
            throw new IOException("guest sshd setup spec rejected", e);
        }
        Thread outDrain = drainSetupPipe(process.getInputStream(), sessionLog, "guest-setup-out");
        Thread errDrain = drainSetupPipe(process.getErrorStream(), sessionLog, "guest-setup-err");
        try {
            boolean done = process.waitFor(SETUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!done) {
                throw new IOException("guest sshd setup timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("guest sshd setup exited " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("guest sshd setup interrupted");
        } finally {
            process.destroyForcibly();
            joinQuietly(outDrain);
            joinQuietly(errDrain);
        }
    }

    /**
     * Drains one setup-process pipe line by line into the session log. The
     * drain runs even when {@code sessionLog} is null: an unread pipe fills
     * and wedges the writer, so it is never left alone.
     */
    private static Thread drainSetupPipe(InputStream pipe, SessionLogWriter sessionLog,
                                         String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pipe, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sessionLog != null) {
                        sessionLog.append(line);
                    }
                }
            } catch (IOException ignored) {
                // The pipe closing is the normal end of the setup process.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Bounded join so the last drained setup lines are flushed before launch. */
    private static void joinQuietly(Thread thread) {
        try {
            thread.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Guest {@code /etc/group} entries naming the supplementary group IDs this
     * app process really carries into the guest (ADR-0011). PRoot cannot drop
     * them — an untrusted app has no {@code CAP_SETGID} and PRoot {@code -0}
     * only fakes the ids reported by {@code getuid}/{@code getgid} — so the
     * guest's own tools must be able to resolve them or every interactive login
     * prints one {@code groups: cannot find name for group ID} line per ID.
     *
     * <p>Best-effort by design: a read failure is logged and the session starts
     * without the naming, because the login shell does not depend on it and the
     * group membership itself is unaffected.</p>
     */
    private List<String> inheritedGroupEntries() {
        try {
            return GuestSupplementaryGroups.guestGroupEntries(
                    GuestSupplementaryGroups.readGroupIds(
                            Paths.get(GuestSupplementaryGroups.PROC_STATUS)));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read this process's supplementary groups; "
                    + "the guest will show their numeric IDs", e);
            return Collections.emptyList();
        }
    }

    private Map<String, String> setupEnv(GuestSshDaemon daemon, char[] token,
                                         List<String> groupEntries) {
        Map<String, String> env = daemonEnv(daemon);
        env.put(GuestSshDaemon.TOKEN_ENV, new String(token));
        if (!groupEntries.isEmpty()) {
            env.put(GuestSshDaemon.GROUP_ENTRIES_ENV, String.join("\n", groupEntries));
        }
        return env;
    }

    /**
     * Pins the guest host public key into the client trust store under the
     * fixed endpoint scope.
     *
     * <p>The key was generated by this app into the private overlay, so pinning
     * it cannot impersonate a foreign key. The pin is mandatory, not
     * best-effort: the local-only client refuses a key it did not pin
     * ({@code LocalSshSessionFactory.pinnedHostKeyOnly()}), so an endpoint whose
     * key cannot be pinned is not attachable and must not be reported ready.</p>
     *
     * @throws IOException when the pin cannot be stored
     */
    private void pinGuestHostKey(PublicKey key, RuntimePort endpoint) throws IOException {
        try {
            HostKeyFingerprint fingerprint = SshHostKeyFingerprintCodec.toFingerprint(key);
            trustStore.save(new HostKeyRecord(
                    endpoint.getHost() + ":" + endpoint.getPort(),
                    fingerprint, HostKeyTrust.VERIFIED, clock.getAsLong()));
        } catch (Exception e) {
            throw new IOException("could not pin the guest host key for "
                    + endpoint.getHost() + ":" + endpoint.getPort(), e);
        }
    }

    /** Drop this session's pin; a restart re-pins under the new endpoint. */
    private void clearPin(RuntimePort endpoint) {
        if (endpoint == null) {
            return;
        }
        try {
            trustStore.clear(endpoint.getHost() + ":" + endpoint.getPort());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not clear guest host key pin", e);
        }
    }

    private void fail(WorkloadListener listener, String reason) {
        Log.e(TAG, reason);
        ActiveDaemon daemon = active;
        active = null;
        if (daemon != null) {
            teardown(daemon);
        }
        callbackExecutor.execute(() -> listener.onFailed(reason));
    }

    private static String tail(GuestSshdStderrMonitor monitor) {
        List<String> lines = monitor.recentLines();
        if (lines.isEmpty()) {
            return "(no daemon output)";
        }
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }

    private static void zero(char[] token) {
        if (token != null) {
            Arrays.fill(token, '\0');
        }
    }
}
