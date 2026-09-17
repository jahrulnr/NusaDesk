package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;

import gh.nusashell.nusadesk.infrastructure.service.LiveMediaRegistry;
import gh.nusashell.nusadesk.infrastructure.service.LiveMediaService;

/**
 * Bridge-side live media controller for one guest bridge session.
 *
 * <p>{@link #start()} enforces the product's eligibility rules in order:
 * the session must not be closed, a start must not already be in flight
 * ({@link LiveMediaError#BUSY}), both runtime grants must be present
 * (typed {@code media-permission-required} / {@code media-permission-denied},
 * never a prompt), and the foreground-service start must be allowed by the
 * platform. On API 26+ a background {@code startForegroundService} throws
 * {@link IllegalStateException} — including its API 31+ subclass
 * {@code ForegroundServiceStartNotAllowedException} — which is mapped to
 * {@link LiveMediaError#FOREGROUND_REQUIRED}. The start then waits up to
 * {@link LiveMediaDefaults#START_RESULT_TIMEOUT_MILLIS} for the service's
 * typed result, so a success response is only returned once the RTSP
 * listener is bound and the encoder metadata is ready.</p>
 *
 * <p>The session is owned by the bridge: {@link #close()} stops the service
 * (tearing down camera, encoders, sockets, clients, and threads) and makes
 * further starts fail with {@link LiveMediaError#UNAVAILABLE}.</p>
 */
public final class AndroidLiveMediaController implements LiveMediaController {
    private final Context context;
    private final MediaPermissionChecker permissionChecker;
    private final ForegroundServiceStarter starter;
    private final LiveMediaRegistry registry;
    private final long startResultTimeoutMillis;
    private volatile boolean closed;

    public AndroidLiveMediaController(Context context) {
        this(context, new AndroidMediaPermissionChecker(context),
                new ContextForegroundServiceStarter(context), LiveMediaRegistry.getInstance(),
                LiveMediaDefaults.START_RESULT_TIMEOUT_MILLIS);
    }

    /**
     * Full constructor with the permission check, foreground-service start,
     * and registry seams exposed for JVM tests; the bounded start wait is
     * also injectable so the timeout path is testable without a 4 s sleep.
     */
    AndroidLiveMediaController(Context context, MediaPermissionChecker permissionChecker,
                               ForegroundServiceStarter starter, LiveMediaRegistry registry,
                               long startResultTimeoutMillis) {
        if (context == null || permissionChecker == null || starter == null || registry == null) {
            throw new IllegalArgumentException("controller dependencies must not be null");
        }
        if (startResultTimeoutMillis < 1) {
            throw new IllegalArgumentException("start result timeout must be positive");
        }
        this.context = context.getApplicationContext();
        this.permissionChecker = permissionChecker;
        this.starter = starter;
        this.registry = registry;
        this.startResultTimeoutMillis = startResultTimeoutMillis;
    }

    /** The platform foreground-service start, as a seam for tests. */
    public interface ForegroundServiceStarter {
        void startForegroundService(Intent intent);
    }

    /** Production starter: the platform call that enforces background eligibility. */
    static final class ContextForegroundServiceStarter implements ForegroundServiceStarter {
        private final Context context;

        ContextForegroundServiceStarter(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public void startForegroundService(Intent intent) {
            context.startForegroundService(intent);
        }
    }

    @Override
    public synchronized LiveMediaStatus start(LiveMediaMode mode) {
        if (mode == null) {
            return LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        }
        if (closed) {
            return LiveMediaStatus.failed(mode, LiveMediaError.UNAVAILABLE);
        }
        LiveMediaStatus current = registry.currentStatus();
        LiveMediaState state = current.getState();
        if (state == LiveMediaState.RUNNING) {
            if (current.getMode() == mode) {
                // Idempotent start: the live session is already the answer.
                return current;
            }
            // One media session at a time: the guest must stop it first.
            return LiveMediaStatus.failed(mode, LiveMediaError.MODE_CONFLICT);
        }
        if (state == LiveMediaState.STARTING) {
            return LiveMediaStatus.failed(mode, LiveMediaError.BUSY);
        }
        CapabilityPermission permission = permissionChecker.check(mode);
        if (permission != CapabilityPermission.GRANTED) {
            LiveMediaError error = permission == CapabilityPermission.DENIED
                    ? LiveMediaError.PERMISSION_DENIED : LiveMediaError.PERMISSION_REQUIRED;
            registry.publishResult(LiveMediaStatus.failed(mode, error));
            return LiveMediaStatus.failed(mode, error);
        }
        registry.publishStarting(mode);
        Intent intent = new Intent(context, LiveMediaService.class)
                .setAction(LiveMediaService.ACTION_START)
                .putExtra(LiveMediaService.EXTRA_MODE, mode.name());
        try {
            starter.startForegroundService(intent);
        } catch (IllegalStateException e) {
            // API 26+ background starts throw IllegalStateException; on API 31+
            // the platform throws its ForegroundServiceStartNotAllowedException
            // subclass for the same condition. Either way the app was not
            // foreground-eligible for a camera/microphone service.
            return fail(mode, LiveMediaError.FOREGROUND_REQUIRED);
        } catch (SecurityException e) {
            // The platform refused the start for a missing/revoked grant.
            return fail(mode, LiveMediaError.PERMISSION_DENIED);
        } catch (RuntimeException e) {
            return fail(mode, LiveMediaError.START_FAILED);
        }
        LiveMediaStatus result = registry.awaitStartResult(startResultTimeoutMillis);
        if (result == null) {
            // The service may still be starting its encoders. Stop it before
            // publishing the timeout so a late worker cannot resurrect a
            // stream after this bridge call has already failed.
            context.stopService(new Intent(context, LiveMediaService.class));
            return fail(mode, LiveMediaError.START_FAILED);
        }
        return result;
    }

    private LiveMediaStatus fail(LiveMediaMode mode, LiveMediaError error) {
        LiveMediaStatus failed = LiveMediaStatus.failed(mode, error);
        registry.publishResult(failed);
        return failed;
    }

    @Override
    public synchronized LiveMediaStatus status() {
        return registry.currentStatus();
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        // Idempotent: stopService is a no-op when the service is not running,
        // and the service publishes the stopped state as it tears down.
        context.stopService(new Intent(context, LiveMediaService.class));
        registry.publishStopped();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        // Stop while the controller is still open; stop() intentionally becomes
        // a no-op once closed, so reversing these two operations would leave a
        // live foreground service and encoder pipeline behind the bridge.
        stop();
        closed = true;
    }
}
