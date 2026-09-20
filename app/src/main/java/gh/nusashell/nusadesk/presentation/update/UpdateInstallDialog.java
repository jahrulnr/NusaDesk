package gh.nusashell.nusadesk.presentation.update;

import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;

import java.util.Locale;

/**
 * The assisted in-app update popup (ADR-0039): identity, a determinate
 * progress bar with an EMA speed and a time estimate while the release asset
 * streams into the app's cache, the checksum state, and the hand-off to the
 * platform's own installer confirmation. Nothing is applied silently — the
 * popup ends at the platform's dialog, where the user taps Install.
 *
 * <p>The dialog owns the presentation state machine and the math: it tracks
 * which state it rendered and dispatches the primary tap to the typed
 * {@link Host} methods, and derives the speed/estimate from the progress
 * ticks it renders. The host wires the callbacks once and stays thin.</p>
 *
 * <p>Cancel during streaming closes the popup with the state honest: the
 * host decides what happens to a partial cache file, and the popup never
 * claims a finished download that was not verified.</p>
 */
public final class UpdateInstallDialog extends Dialog {

    /** What the host does per dialog state. */
    public interface Host {
        /** The verified-or-cached asset is ready: stage the platform install. */
        void onInstallReady();

        /** The one-time Android gate: open the unknown-apps settings step. */
        void onAllowInstalls();

        /** A failed attempt: re-run the flow. */
        void onRetry();

        /** The browser hand-off (the release page) stays available. */
        void onReleasePage();

        /** The popup closed (cancel or dismiss); the host cleans up listeners. */
        void onPopupClosed();
    }

    /** A progress tick: bytes read so far and the expected total, -1 when unknown. */
    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private enum State {
        PREPARING, DOWNLOADING, VERIFYING, INSTALLING,
        NEEDS_ALLOWANCE, ABORTED, FAILED, UNAVAILABLE
    }

    private TextView detail;
    private TextView status;
    private ProgressBar progress;
    private Button action;
    private Button cancel;
    private Button release;
    private Host host;
    private State state = State.PREPARING;

    /** Speed/ETA bookkeeping: EMA over the ticks the dialog renders. */
    private long lastBytes = -1L;
    private long lastTickNanos = 0L;
    private double emaBytesPerSecond = 0.0;

    public UpdateInstallDialog(Context context) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.widget_update_install);
        detail = findViewById(R.id.update_install_detail);
        status = findViewById(R.id.update_install_status);
        progress = findViewById(R.id.update_install_progress);
        action = findViewById(R.id.update_install_action);
        cancel = findViewById(R.id.update_install_cancel);
        release = findViewById(R.id.update_install_release);
        action.setOnClickListener(v -> dispatchPrimary());
        cancel.setOnClickListener(v -> dismiss());
        release.setOnClickListener(v -> {
            if (host != null) {
                host.onReleasePage();
            }
        });
        setOnDismissListener(d -> {
            if (host != null) {
                host.onPopupClosed();
            }
        });
    }

    public void showWithHost(Host host) {
        this.host = host;
        show();
    }

    /** Renders the identity line: tag and a preformatted size. */
    public void renderIdentity(String tag, String sizeText) {
        detail.setText(getContext().getString(R.string.update_install_detail, tag, sizeText));
    }

    /** Indeterminate state while the host probes the cache. */
    public void renderPreparing() {
        state = State.PREPARING;
        progress.setIndeterminate(true);
        status.setText(R.string.update_install_preparing);
        showAction(false);
    }

    /** Renders one progress tick while streaming: percent, EMA speed, estimate. */
    public void onProgress(long bytesRead, long totalBytes) {
        state = State.DOWNLOADING;
        if (progress.isIndeterminate()) {
            progress.setIndeterminate(false);
        }
        if (lastBytes >= 0 && lastTickNanos > 0) {
            long deltaBytes = bytesRead - lastBytes;
            long deltaNanos = System.nanoTime() - lastTickNanos;
            if (deltaNanos > 0 && deltaBytes >= 0) {
                double instant = deltaBytes * 1_000_000_000.0 / deltaNanos;
                emaBytesPerSecond = emaBytesPerSecond == 0.0
                        ? instant
                        : 0.3 * instant + 0.7 * emaBytesPerSecond;
            }
        }
        lastBytes = bytesRead;
        lastTickNanos = System.nanoTime();
        if (totalBytes > 0) {
            progress.setProgress((int) Math.min(100L, bytesRead * 100L / totalBytes));
        }
        status.setText(statusText(bytesRead, totalBytes, emaBytesPerSecond));
        showAction(false);
    }

    /** Checksum verification state after the stream completed. */
    public void renderVerifying() {
        state = State.VERIFYING;
        progress.setIndeterminate(true);
        status.setText(R.string.update_install_verifying);
        showAction(false);
    }

    /** The platform installer is staged; the user confirms there. */
    public void renderInstalling() {
        state = State.INSTALLING;
        progress.setIndeterminate(true);
        status.setText(R.string.update_install_installing);
        showAction(false);
    }

    /** The one-time Android gate: allow installs staged by this app. */
    public void renderNeedsUnknownSources() {
        state = State.NEEDS_ALLOWANCE;
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setText(R.string.update_install_unknown_sources_detail);
        action.setText(R.string.update_install_action_allow);
        showAction(true);
    }

    /** The user cancelled the platform installer; the cache is kept. */
    public void renderAborted() {
        state = State.ABORTED;
        progress.setIndeterminate(false);
        progress.setProgress(100);
        status.setText(R.string.update_install_aborted);
        action.setText(R.string.update_install_action_install);
        showAction(true);
    }

    /**
     * The verified-or-cached asset is about to be staged: full progress and
     * the Install label, so a restaged retry reads the same as the first run.
     */
    public void renderReady() {
        state = State.ABORTED; // the primary is "Install" in both states
        progress.setIndeterminate(false);
        progress.setProgress(100);
        action.setText(R.string.update_install_action_install);
        showAction(true);
    }

    /** True while the popup waits for the unknown-apps grant (a resume point). */
    public boolean isWaitingForAllowance() {
        return state == State.NEEDS_ALLOWANCE;
    }

    /** A typed failure with the reason; the primary becomes a retry. */
    public void renderFailed(String detailText) {
        state = State.FAILED;
        progress.setIndeterminate(false);
        status.setText(getContext().getString(R.string.update_install_failed, detailText));
        action.setText(R.string.update_install_action_retry);
        showAction(true);
    }

    /** The channel did not report a usable HTTPS asset; the browser path remains. */
    public void renderUnavailable() {
        state = State.UNAVAILABLE;
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setText(R.string.update_install_unavailable);
        action.setText(R.string.update_install_action_release);
        showAction(true);
    }

    /** Forget the EMA state before a fresh download attempt. */
    public void resetProgress() {
        lastBytes = -1L;
        lastTickNanos = 0L;
        emaBytesPerSecond = 0.0;
        progress.setIndeterminate(false);
        progress.setProgress(0);
    }

    private void dispatchPrimary() {
        if (host == null) {
            return;
        }
        switch (state) {
            case NEEDS_ALLOWANCE:
                host.onAllowInstalls();
                break;
            case ABORTED:
                host.onInstallReady();
                break;
            case FAILED:
                host.onRetry();
                break;
            case UNAVAILABLE:
                host.onReleasePage();
                break;
            default:
                // PREPARING/DOWNLOADING/VERIFYING/INSTALLING render no primary.
                break;
        }
    }

    private void showAction(boolean visible) {
        action.setVisibility(visible ? Button.VISIBLE : Button.GONE);
    }

    /** The rendered line: bytes, EMA speed, and a time estimate when known. */
    private String statusText(long bytesRead, long totalBytes, double bytesPerSecond) {
        StringBuilder text = new StringBuilder();
        if (totalBytes > 0) {
            text.append(formatBytes(bytesRead)).append(" of ").append(formatBytes(totalBytes));
        } else {
            text.append(formatBytes(bytesRead));
        }
        if (bytesPerSecond > 0) {
            text.append(" • ").append(formatBytes((long) bytesPerSecond)).append("/s");
            if (totalBytes > bytesRead) {
                long secondsLeft = (long) ((totalBytes - bytesRead) / bytesPerSecond);
                text.append(" • ~").append(formatSeconds(secondsLeft)).append(" left");
            }
        }
        return text.toString();
    }

    /** One decimal MB (or KB below one megabyte); shared with the host's identity line. */
    public static String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }

    private static String formatSeconds(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
