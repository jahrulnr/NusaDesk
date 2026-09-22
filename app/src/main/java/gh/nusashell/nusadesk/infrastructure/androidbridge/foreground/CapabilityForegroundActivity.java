package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost.PendingOperation;

/**
 * Transparent host activity for interactive capability operations.
 *
 * <p>The manifest entry (owned by the coordinator, not this file) declares
 * it {@code Theme.Translucent.NoTitleBar}, unexported, {@code noHistory}, and
 * excluded from recents; the class itself never installs a layout. On
 * {@link #onCreate} it claims the operation parked in
 * {@link CapabilityForegroundHost} — finishing immediately when none exists
 * — attaches itself so a host timeout can finish it, and runs the
 * operation. A synchronously completed operation finishes the activity at
 * once; an asynchronous one (for example a runtime-permission prompt) keeps
 * it alive until the matching callback delivers through the result sink.</p>
 *
 * <p>The activity never talks to the bridge socket: it only settles the
 * parked operation through {@link CapabilityForegroundHost#complete}. It
 * must not crash under any condition — every path either finishes quietly
 * or settles the operation with a typed error.</p>
 */
public final class CapabilityForegroundActivity extends Activity {
    /** The operation this instance claimed; null until {@link #onCreate} claims one. */
    private PendingOperation pending;
    /** Callback the running operation registered for runtime-permission results. */
    private PermissionResultHandler permissionResultHandler;
    /** Callback the running operation registered for an activity result (SAF pickers). */
    private ActivityResultHandler activityResultHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PendingOperation current = CapabilityForegroundHost.pendingOperation();
        if (current == null || !current.claim()) {
            finish();
            return;
        }
        pending = current;
        current.attachActivity(this);
        ForegroundOperation.ResultSink sink = new ForegroundOperation.ResultSink() {
            @Override
            public void success(Map<String, Object> fields) {
                deliver(fields, null);
            }

            @Override
            public void error(String typedError) {
                deliver(null, typedError);
            }
        };
        try {
            current.operation().run(this, current.params(), sink);
        } catch (RuntimeException e) {
            // An operation must report its own typed error; a thrown failure
            // is reported as a platform refusal and still finishes cleanly.
            sink.error(CapabilityForegroundHost.ERROR_UNAVAILABLE);
        }
    }

    /**
     * Register the handler that receives the runtime-permission result while
     * this activity is running. Called by an operation during
     * {@link ForegroundOperation#run}.
     */
    void setPermissionResultHandler(PermissionResultHandler handler) {
        this.permissionResultHandler = handler;
    }

    /**
     * Register the handler that receives the activity result while this
     * activity is running (SAF document/tree pickers). Called by an operation
     * during {@link ForegroundOperation#run}.
     */
    void setActivityResultHandler(ActivityResultHandler handler) {
        this.activityResultHandler = handler;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionResultHandler handler = permissionResultHandler;
        if (handler != null) {
            handler.onResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResultHandler handler = activityResultHandler;
        if (handler != null) {
            handler.onResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        PendingOperation current = pending;
        if (current != null && !current.isDone()) {
            // The activity died without a delivered result (user navigation,
            // process pressure, task removal): settle the parked wait instead
            // of leaving the bridge thread to time out.
            CapabilityForegroundHost.complete(current, null,
                    CapabilityForegroundHost.ERROR_CANCELLED);
        }
        super.onDestroy();
    }

    /** Settle the parked operation and finish; safe from any thread. */
    private void deliver(Map<String, Object> fields, String typedError) {
        CapabilityForegroundHost.complete(pending, fields, typedError);
        try {
            runOnUiThread(this::finish);
        } catch (RuntimeException e) {
            // The activity is already going away; the result was delivered.
        }
    }

    /** Receives one runtime-permission result for the running operation. */
    interface PermissionResultHandler {
        void onResult(int requestCode, String[] permissions, int[] grantResults);
    }

    /** Receives one picker result for the running operation. */
    interface ActivityResultHandler {
        void onResult(int requestCode, int resultCode, Intent data);
    }
}
