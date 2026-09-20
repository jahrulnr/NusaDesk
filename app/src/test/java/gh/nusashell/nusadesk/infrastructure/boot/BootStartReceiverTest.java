package gh.nusashell.nusadesk.infrastructure.boot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;

/**
 * The receiver's wiring contract on the JVM (ADR-0037): both reviewed
 * triggers drive the same opt-in gate, the started intent is always the
 * host's existing ensure-running action (never a new action, never an
 * install), and nothing — not even a full-gates override — fires an
 * unrelated action or an opted-out trigger. The gate evaluation itself is
 * covered by {@code BootAutostartPolicyTest}; the real disk probe is
 * exercised once here against an empty files dir, where every gate fails
 * closed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class BootStartReceiverTest {

    @After
    public void tearDown() {
        BootStartReceiver.gateProbeOverride = null;
    }

    @Test
    public void bootCompletedWithOptInDisabledStartsNothing() {
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertTrue("opt-out is the default; nothing may start",
                context.startedServices.isEmpty());
    }

    @Test
    public void packageReplacedWithOptInDisabledStartsNothing() {
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertTrue(context.startedServices.isEmpty());
    }

    @Test
    public void optedInWithAllGatesSetStartsTheEnsureRunningIntent() {
        optInWithGates(true, true, true);
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertEquals(1, context.startedServices.size());
        Intent started = context.startedServices.get(0);
        assertEquals("the boot path reuses the idempotent ensure-running action",
                RuntimeHostService.ACTION_ENSURE_RUNNING, started.getAction());
        assertEquals(RuntimeHostService.class.getName(),
                started.getComponent().getClassName());
    }

    @Test
    public void optedInPackageReplacedWithAllGatesSetAlsoStarts() {
        optInWithGates(true, true, true);
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertEquals(1, context.startedServices.size());
        assertEquals(RuntimeHostService.ACTION_ENSURE_RUNNING,
                context.startedServices.get(0).getAction());
    }

    @Test
    public void optedInWithAnUnsetGateStartsNothing() {
        optInWithGates(true, true, false);
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertTrue("a missing gate is a typed skip, never a start",
                context.startedServices.isEmpty());
    }

    @Test
    public void optedInOnAFreshDeviceStartsNothing() {
        // No override: the real probe runs against an empty files dir, where
        // no persisted READY, no SSH overlay, and no bridge exist — every
        // disk read must fail closed.
        preferences().setEnabled(true);
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertTrue(context.startedServices.isEmpty());
    }

    @Test
    public void unrelatedActionStartsNothingEvenWhenOptedIn() {
        optInWithGates(true, true, true);
        RecordingContext context = new RecordingContext();

        new BootStartReceiver().onReceive(
                context, new Intent("gh.nusashell.nusadesk.UNRELATED"));

        assertTrue("the receiver must ignore anything but the two reviewed actions",
                context.startedServices.isEmpty());
    }

    private static void optInWithGates(
            boolean payloadReady, boolean sshAddon, boolean serviceBridge) {
        preferences().setEnabled(true);
        BootStartReceiver.gateProbeOverride = new BootStartReceiver.GateProbe() {
            @Override
            public boolean payloadReady() {
                return payloadReady;
            }

            @Override
            public boolean sshAddonPresent() {
                return sshAddon;
            }

            @Override
            public boolean serviceBridgePresent() {
                return serviceBridge;
            }
        };
    }

    private static BootAutostartPreferences preferences() {
        return new BootAutostartPreferences(RuntimeEnvironment.getApplication());
    }

    /**
     * Application-context wrapper that records {@code startForegroundService}
     * while every other method (preferences, files dir) still reaches the
     * real application — the same wrapper shape the battery-access tests use.
     */
    private static final class RecordingContext extends ContextWrapper {
        final List<Intent> startedServices = new ArrayList<>();

        RecordingContext() {
            super(RuntimeEnvironment.getApplication());
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ComponentName startForegroundService(Intent service) {
            startedServices.add(service);
            return new ComponentName(this, RuntimeHostService.class);
        }
    }
}
