package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistry;
import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.infrastructure.homescreen.PinnedShortcutProof;
import gh.nusashell.nusadesk.infrastructure.service.*;
import gh.nusashell.nusadesk.infrastructure.ssh.*;
import gh.nusashell.nusadesk.infrastructure.terminal.SharedPreferencesTerminalCommandStore;

/** A cold homescreen launch must not lose its command before the service is ready. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PinnedRuntimeLaunchTest {
    @Test public void coldCommandRunsOnceAfterReadinessAndNotAfterCancellation() throws Exception {
        exercise(false, false);
    }
    @Test public void navigationCancelsAWaitingCommand() throws Exception {
        exercise(true, false);
    }
    @Test public void waitingCommandSurvivesActivityRecreation() throws Exception {
        exercise(false, true);
    }
    private void exercise(boolean cancel, boolean recreate) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Field state = RuntimeStatusBus.class.getDeclaredField("lastStatus");
        state.setAccessible(true);
        Object previous = state.get(RuntimeStatusBus.getInstance());
        Field registry = TerminalTabsRegistry.class.getDeclaredField("controller");
        registry.setAccessible(true);
        Object previousTabs = registry.get(TerminalTabsRegistry.getInstance());
        state.set(RuntimeStatusBus.getInstance(), null);
        TerminalTabsRegistry.getInstance().clear();
        TerminalCommandApp app = new TerminalCommandRegistry(
                new SharedPreferencesTerminalCommandStore(context)).add("Fixture", null, "printf fixture");
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_KIND", "TERMINAL_APP")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_ID", app.getId().value())
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF", PinnedShortcutProof.token(context));
        ActivityController<MainActivity> activity = null;
        try {
            activity = Robolectric.buildActivity(MainActivity.class, intent).create().start();
            Field pending = MainActivity.class.getDeclaredField("pendingPinnedId");
            pending.setAccessible(true);
            assertEquals(app.getId().value(), pending.get(activity.get()));
            if (recreate) {
                Bundle saved = new Bundle();
                activity.saveInstanceState(saved).stop().destroy();
                activity = Robolectric.buildActivity(MainActivity.class, new Intent()).create(saved).start();
                assertEquals(app.getId().value(), pending.get(activity.get()));
            }
            if (cancel) {
                java.lang.reflect.Method home = MainActivity.class.getDeclaredMethod("showShell");
                home.setAccessible(true);
                home.invoke(activity.get());
            }
            List<String> commands = new ArrayList<>();
            TerminalTabsController tabs = new TerminalTabsController(() -> new TerminalTransport() {
                public void start(SshSessionConfig config, String command, SshSessionListener listener) {
                    if (command != null) commands.add(command);
                }
                public void write(byte[] data) { }
                public void resize(int cols, int rows) { }
                public void close() { }
            }, snapshot -> { }, Runnable::run);
            TerminalTabsRegistry.getInstance().register(tabs);
            HostRuntimeStatus ready = new HostRuntimeStatus(new SessionSnapshot("pin-test",
                    "ubuntu-base-arm64", "0.14.4", SessionState.RUNNING,
                    new RuntimePort("127.0.0.1", 22022), 1, 1, "", 0), true, "ssh");
            tabs.onRuntimeStatus(ready);
            RuntimeStatusBus.getInstance().publish(ready);
            ShadowLooper.idleMainLooper();
            RuntimeStatusBus.getInstance().publish(ready);
            ShadowLooper.idleMainLooper();
            assertEquals(cancel ? 0 : 1, commands.size());
            if (!cancel) assertEquals("printf fixture", commands.get(0));
            assertNull(pending.get(activity.get()));
        } finally {
            if (activity != null) activity.stop().destroy();
            state.set(RuntimeStatusBus.getInstance(), previous);
            registry.set(TerminalTabsRegistry.getInstance(), previousTabs);
        }
    }
}
