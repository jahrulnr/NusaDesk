package gh.nusashell.nusadesk.infrastructure.service;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * The one-shot revival trigger behind the one-tap recovery escalation
 * (ADR-0063). Scheduled by {@link JobSchedulerSelfRestart} right before the
 * app kills its own process tree, then delivered by the platform into a fresh
 * process — the job survives process death because the scheduler keeps it in
 * {@code system_server}.
 *
 * <p>{@link #onStartJob} re-enters the same {@code ensureRunning} boundary an
 * app launch uses; it owns no lifecycle of its own. An expedited job is one
 * of the contexts the platform lets start a foreground service on API 31+;
 * when even that is refused — out of expedited quota, or an OEM deviation —
 * the restart notice the dying process already posted is reworded into the
 * tap-to-resume fallback, never a Settings trip.</p>
 */
public final class RuntimeRevivalJobService extends JobService {

    private static final String TAG = "RuntimeRevivalJob";

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean resumed = false;
        try {
            resumed = RuntimeHostService.ensureRunning(this) != null;
        } catch (RuntimeException e) {
            // A refused foreground-service start (background-start rules,
            // OEM deviation) still leaves the honest path: the notice.
            Log.w(TAG, "revival could not start the runtime host service", e);
        }
        if (resumed) {
            JobSchedulerSelfRestart.cancelNotice(this);
        } else {
            JobSchedulerSelfRestart.postResumeNotice(this);
        }
        jobFinished(params, false);
        return false;
    }

    /**
     * The platform is revoking the job's window. Nothing to redo — the intent
     * was already handed to the service — so no reschedule is requested.
     */
    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
