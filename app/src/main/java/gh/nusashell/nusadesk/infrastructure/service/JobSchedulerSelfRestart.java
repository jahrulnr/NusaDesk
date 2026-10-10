package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AppSelfRestart} on the platform's own mechanisms (ADR-0063):
 *
 * <ul>
 *   <li><b>Revival trigger:</b> a {@link JobScheduler} job targeting
 *       {@link RuntimeRevivalJobService}, expedited on API 31+ so it runs as
 *       soon as the system can, plain minimum-latency below that (expedited
 *       does not exist before 31, and neither do the FGS-start rules it
 *       answers). Job execution survives the app's process death — the
 *       scheduler holds it in {@code system_server}, starts a fresh process,
 *       and binds the service there — and is discarded only by a real
 *       force-stop, which is exactly the distinction this path needs. An
 *       expedited job execution is one of the contexts the platform lets
 *       start a foreground service on API 31+; when quota or the platform
 *       refuse even that, the notice posted before the kill stays up as the
 *       one-tap way back in.</li>
 *   <li><b>Tree kill:</b> {@code Process.killProcessGroup(uid, pid)} is a
 *       hidden API — absent from the SDK stub — so the equivalent is used:
 *       every {@code /proc} entry whose {@code Uid:} matches this app's own
 *       uid gets {@code SIGCONT} then {@code SIGKILL}, and this process is
 *       killed last through the public {@link Process#killProcess}. Covering
 *       the uid, not just the process group, also reaches a tracee that left
 *       the group with {@code setsid} — exactly the orphaned {@code sshd}
 *       that keeps {@code 127.0.0.1:22022} bound.</li>
 * </ul>
 *
 * <p>Nothing here is reachable from guest code or an exported component: the
 * job service is declared {@code BIND_JOB_SERVICE}-only, and the kill sweeps
 * only the caller's own uid.</p>
 */
public final class JobSchedulerSelfRestart implements AppSelfRestart {

    private static final String TAG = "SelfRestart";

    /**
     * Fixed job id: re-arming replaces the pending one rather than stacking.
     * The JobScheduler id space is per-uid and shared with the guest-facing
     * termux bridge — a guest could in theory replace this job's id in the
     * sub-second arm-to-kill window, which only costs the revival, not the
     * kill or the notice.
     */
    static final int REVIVAL_JOB_ID = 0x4C52; // "LR"
    /**
     * Notification id for the restart notice, deliberately distinct from the
     * runtime host's foreground notification so cancelling it never touches
     * the service's own slot.
     */
    static final int RESTART_NOTICE_ID = 0x4C53;

    /**
     * Small floor before the job may run: gives the killed tree a beat to be
     * reaped before the fresh process reclaims the fixed port.
     */
    private static final long REVIVAL_MIN_LATENCY_MILLIS = 1_000L;
    /**
     * Hard bound on the plain fallback: Doze and app-standby may defer a
     * minimum-latency job, and a deadline keeps "app is dead, job pending"
     * from lasting arbitrarily long.
     */
    private static final long REVIVAL_DEADLINE_MILLIS = 5 * 60_000L;

    private final Context context;

    public JobSchedulerSelfRestart(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    /**
     * Schedule the revival job — expedited first, plain at the same latency
     * when expedited quota is exhausted — then post the restart notice.
     * The notice is only posted once a trigger is armed: a "restarting"
     * banner with nothing scheduled would lie.
     */
    @Override
    public boolean arm() {
        if (!scheduleRevival()) {
            Log.e(TAG, "no revival trigger could be scheduled");
            return false;
        }
        postNotice();
        return true;
    }

    /**
     * Thaw, then kill every process this uid owns, self last. The
     * {@code SIGCONT} pass comes first because on the wedge kernels a stopped
     * process was observed surviving even {@code SIGKILL} until it resumed —
     * a frozen survivor gets both signals delivered back to back.
     */
    @Override
    public void kill() {
        int selfPid = Process.myPid();
        List<Long> pids = ownProcessPids(Paths.get("/proc"), Process.myUid(), selfPid);
        for (long pid : pids) {
            signalQuietly(pid, OsConstants.SIGCONT);
        }
        for (long pid : pids) {
            signalQuietly(pid, OsConstants.SIGKILL);
        }
        Process.killProcess(selfPid);
        // killProcess on self never returns; this line is the honest end for
        // a platform that somehow let the signal land without killing us.
        System.exit(1);
    }

    private static void signalQuietly(long pid, int signal) {
        try {
            Os.kill((int) pid, signal);
        } catch (ErrnoException | RuntimeException e) {
            // A pid may exit between the scan and the signal; the sweep is
            // best-effort per process and still ends with the self kill.
            Log.w(TAG, "could not signal own pid " + pid, e);
        }
    }

    /**
     * Pids of this uid's live processes except {@code selfPid}, discovered
     * from procfs so a test can point at a fixture: each numeric
     * {@code <procRoot>/<pid>} entry contributes its pid when the first
     * {@code Uid:} field in its {@code status} file equals {@code selfUid}.
     * Entries gone or unreadable between listing and read are skipped — the
     * sweep never signals a process it could not attribute to this uid.
     */
    static List<Long> ownProcessPids(Path procRoot, long selfUid, long selfPid) {
        List<Long> pids = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(procRoot)) {
            for (Path entry : entries) {
                long pid;
                try {
                    pid = Long.parseLong(entry.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid != selfPid && uidOf(entry) == selfUid) {
                    pids.add(pid);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not scan /proc for this app's processes", e);
        }
        return pids;
    }

    /**
     * The real uid recorded in {@code <procDir>/status}, or {@code -1} when
     * the entry is gone, unreadable, or carries no {@code Uid:} line.
     */
    private static long uidOf(Path procDir) {
        String text;
        try {
            text = new String(Files.readAllBytes(procDir.resolve("status")),
                    StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        for (String line : text.split("\n")) {
            if (!line.startsWith("Uid:")) {
                continue;
            }
            String[] fields = line.substring(4).trim().split("\\s+");
            try {
                return Long.parseLong(fields[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Schedule the one-shot revival job; the expedited form is tried first
     * and plain {@code setMinimumLatency} is the quota fallback. An expedited
     * {@link JobInfo} may not carry a delay ({@code build()} throws), so the
     * small latency floor only exists on the fallback.
     *
     * @return whether the platform accepted a schedule
     */
    private boolean scheduleRevival() {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return false;
        }
        ComponentName service = new ComponentName(context, RuntimeRevivalJobService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Expedited jobs exist from API 31 and an expedited execution is
            // one of the contexts allowed to start a foreground service —
            // exactly the permission API-31+ background rules take away.
            try {
                JobInfo expedited = new JobInfo.Builder(REVIVAL_JOB_ID, service)
                        .setExpedited(true)
                        .build();
                if (scheduler.schedule(expedited) == JobScheduler.RESULT_SUCCESS) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Quota or platform refused the expedited form; the plain job
                // below is the same trigger with the small latency floor.
                Log.w(TAG, "expedited revival job refused; falling back", e);
            }
        }
        try {
            JobInfo plain = new JobInfo.Builder(REVIVAL_JOB_ID, service)
                    .setMinimumLatency(REVIVAL_MIN_LATENCY_MILLIS)
                    .setOverrideDeadline(REVIVAL_DEADLINE_MILLIS)
                    .build();
            return scheduler.schedule(plain) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            Log.e(TAG, "JobScheduler refused the revival job", e);
            return false;
        }
    }

    /**
     * The pre-kill notice: explains the app is restarting to recover Linux
     * and doubles as the way back — its content intent is the launcher, so a
     * tap opens the app whose foreground event runs {@code ensureRunning}.
     */
    private void postNotice() {
        postNotice("NusaDesk restarting",
                "Restarting the app to recover the Linux session.");
    }

    /**
     * Reword the restart notice into the tap-to-resume fallback, used by the
     * revival job when the platform refuses the foreground-service start.
     */
    static void postResumeNotice(Context context) {
        new JobSchedulerSelfRestart(context).postNotice(
                "NusaDesk needs a tap",
                "The app could not restart itself. Tap to open it and finish recovering Linux.");
    }

    /** Clear the restart notice once the revived process is up. */
    static void cancelNotice(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(RESTART_NOTICE_ID);
        }
    }

    private void postNotice(String title, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            Notification.Builder builder =
                    new Notification.Builder(context, RuntimeHostService.CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.stat_notify_sync)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .setLocalOnly(true);
            PendingIntent content = launchAppIntent();
            if (content != null) {
                builder.setContentIntent(content);
            }
            // POST_NOTIFICATIONS may be denied on API 33+: notify() then just
            // does not show, which only costs the fallback, never the kill.
            manager.notify(RESTART_NOTICE_ID, builder.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not post the restart notice", e);
        }
    }

    /** Launcher intent for this package, or {@code null} when unresolvable. */
    private PendingIntent launchAppIntent() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(
                context.getPackageName());
        if (launch == null) {
            return null;
        }
        return PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
