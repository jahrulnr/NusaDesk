package gh.nusashell.nusadesk.infrastructure.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import gh.nusashell.nusadesk.application.runtime.RuntimeSnapshotReconciler;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.domain.boot.BootAutostartDecision;
import gh.nusashell.nusadesk.domain.boot.BootAutostartPolicy;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.infrastructure.proot.GuestServiceBridge;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshDaemon;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidRuntimeStateStore;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;

import java.nio.file.Path;

/**
 * Opt-in boot start for the Linux session (ADR-0037).
 *
 * <p>Declared unexported and not direct-boot-aware, with exactly two filter
 * actions: {@link Intent#ACTION_BOOT_COMPLETED} and
 * {@link Intent#ACTION_MY_PACKAGE_REPLACED} — an app update kills the runtime
 * process, and the second action restores it after replacement.
 * {@code LOCKED_BOOT_COMPLETED} is deliberately absent: credential-encrypted
 * storage is unavailable before the first unlock, and every persisted signal
 * this receiver reads lives there.</p>
 *
 * <p>The receiver is thin by contract: it reads the opt-in and the persisted
 * install gates, hands them to {@link BootAutostartPolicy}, logs the typed
 * outcome, and on {@link BootAutostartDecision#START} calls the same
 * idempotent ensure-running boundary an Activity foreground event uses
 * ({@link RuntimeHostService#ensureRunning(Context)}). Starting a foreground
 * service from this trigger is an explicit exemption from the Android 12+
 * background-start restrictions, and the host's {@code specialUse} type is
 * not one of the FGS types Android 15 blocks from the boot broadcast. The
 * receiver never installs, downloads, or schedules anything, never stops
 * anything, and never crashes the process — an unreadable gate or a refused
 * service start is a logged skip, because the next app-visible launch runs
 * the same boundary anyway.</p>
 *
 * <p>Gate probing is lazy: the opt-in defaults to OFF, and a stock install
 * must not pay for filesystem reads (including the bridge overlay's digest
 * verification) on every boot, so no disk work happens until the user has
 * opted in.</p>
 */
public final class BootStartReceiver extends BroadcastReceiver {

    private static final String TAG = "BootStartReceiver";

    /**
     * Test seam: when set, {@link #onReceive} evaluates these gates instead
     * of probing disk, so JVM tests drive every gate combination without
     * fabricating digest-pinned overlay files. Package-private and never set
     * in production code (same seam convention as
     * {@code LiveMediaService.pipelineOverride}).
     */
    static volatile GateProbe gateProbeOverride;

    /**
     * The persisted, disk-readable inputs {@link BootAutostartPolicy} needs.
     * Presentation state is unreachable from a receiver, so each method reads
     * the same on-disk truth the in-app autostart gate derives.
     */
    interface GateProbe {
        /** Persisted runtime state reconciles to {@code READY}. */
        boolean payloadReady();
        /** The curated OpenSSH add-on overlay is usable on disk. */
        boolean sshAddonPresent();
        /** The guest service-bridge overlay verifies on disk. */
        boolean serviceBridgePresent();
    }

    /** All-false probe for the opted-out path: the decision is already made. */
    private static final GateProbe NO_GATES = new GateProbe() {
        @Override
        public boolean payloadReady() {
            return false;
        }

        @Override
        public boolean sshAddonPresent() {
            return false;
        }

        @Override
        public boolean serviceBridgePresent() {
            return false;
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // The manifest filter only delivers the two reviewed actions; a
            // stray explicit intent still must not start the runtime.
            return;
        }
        Context app = context.getApplicationContext();
        boolean optedIn = new BootAutostartPreferences(app).enabled();
        GateProbe probe = !optedIn ? NO_GATES
                : gateProbeOverride != null ? gateProbeOverride
                : new DiskGateProbe(app);
        BootAutostartDecision decision;
        try {
            decision = BootAutostartPolicy.decide(optedIn,
                    probe.payloadReady(),
                    probe.sshAddonPresent(),
                    probe.serviceBridgePresent());
        } catch (RuntimeException exception) {
            // A gate that cannot be read is a skip, never a start, never a crash.
            Log.w(TAG, "boot gate probe failed; not starting the runtime", exception);
            return;
        }
        Log.i(TAG, action + " -> " + decision);
        if (decision != BootAutostartDecision.START) {
            return;
        }
        try {
            RuntimeHostService.ensureRunning(app);
        } catch (RuntimeException exception) {
            // The platform may still refuse the background start on some
            // builds (an OEM restriction the exemption does not cover); the
            // next app-visible launch runs the same boundary. Never crash.
            Log.w(TAG, "could not request runtime start after " + action, exception);
        }
    }

    /**
     * Production probe: the same persisted signals
     * {@code MainActivity.ensureRuntimeRunning} gates on, read straight from
     * the owning stores and the verified on-disk overlays.
     */
    private static final class DiskGateProbe implements GateProbe {
        private final Context context;

        DiskGateProbe(Context context) {
            this.context = context.getApplicationContext();
        }

        /**
         * {@code AndroidRuntimeStateStore} already fails a {@code READY}
         * record whose active rootfs is missing, and the reconciler turns an
         * interrupted install into an honest failure — the same two steps the
         * launcher applies.
         */
        @Override
        public boolean payloadReady() {
            RuntimeStateStore store = new AndroidRuntimeStateStore(context);
            RuntimeSnapshot snapshot = RuntimeSnapshotReconciler.reconcile(
                    store.load(CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId()));
            return snapshot != null && snapshot.getState() == RuntimeState.READY;
        }

        /**
         * The launcher's "terminal component installed" truth: the same
         * {@link GuestSshDaemon#detect} the system screen uses, covering the
         * activated overlay and a rootfs-resident daemon.
         */
        @Override
        public boolean sshAddonPresent() {
            Path filesDir = context.getFilesDir().toPath();
            Path rootfs = ProotPaths.activeRootfsPath(
                    filesDir, CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId());
            Path overlay = ProotPaths.activeAddonPath(
                    filesDir, CuratedRuntimeCatalog.guestSshAddon().getAddonId());
            return GuestSshDaemon.detect(rootfs, overlay) != null;
        }

        /**
         * The settled-bridge truth a boot can rely on. Add-on install
         * outcomes are never persisted, so "absent" cannot be told apart
         * from "failed" here; only a verified overlay settles the gate.
         */
        @Override
        public boolean serviceBridgePresent() {
            Path overlay = ProotPaths.activeAddonPath(
                    context.getFilesDir().toPath(),
                    CuratedRuntimeCatalog.guestServiceBridge().getAddonId());
            return GuestServiceBridge.detect(overlay) != null;
        }
    }
}
