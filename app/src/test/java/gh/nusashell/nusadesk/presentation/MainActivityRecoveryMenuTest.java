package gh.nusashell.nusadesk.presentation;

import android.app.Application;
import android.content.Intent;
import android.view.MenuItem;
import android.widget.PopupMenu;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.*;
import gh.nusashell.nusadesk.domain.session.*;
import gh.nusashell.nusadesk.domain.terminal.*;
import gh.nusashell.nusadesk.infrastructure.service.*;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPopupMenu;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MainActivityRecoveryMenuTest {
    @Before public void setup() throws Exception {
        Field f = MainActivity.class.getDeclaredField("processStartCheckConsumed");
        f.setAccessible(true); f.setBoolean(null, true);
        RuntimeEnvironment.getApplication().getSharedPreferences("update_check", 0).edit()
                .putLong("last_check_at", System.currentTimeMillis()).commit();
        TerminalTabsRegistry.getInstance().clear();
    }
    @After public void cleanup() { TerminalTabsRegistry.getInstance().clear(); }

    @Test public void failedSessionWithoutTabsStillHasAnActionableRestartMenu() throws Exception {
        var lifecycle = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = lifecycle.get();
            invoke(activity, "renderTerminalMenu", new Class<?>[]{TerminalTabsSnapshot.class}, TerminalTabsSnapshot.empty());
            Application application = RuntimeEnvironment.getApplication();
            ShadowApplication shadow = Shadow.extract(application);
            while (shadow.getNextStartedService() != null) { }
            PopupMenu menu = openMenu(activity);
            assertEquals(1, menu.getMenu().size());
            click(menu, activity.getString(R.string.terminal_restart_linux));
            Intent intent = shadow.getNextStartedService();
            assertNotNull(intent);
            assertEquals(RuntimeHostService.ACTION_RESTART, intent.getAction());
        } finally { lifecycle.destroy(); }
    }

    @Test public void commandReconnectTargetsItsOwnTabAndRejectsAStaleMenu() throws Exception {
        var lifecycle = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = lifecycle.get();
            List<FakeTransport> transports = new ArrayList<>();
            TerminalTabsController port = new TerminalTabsController(() -> {
                FakeTransport transport = new FakeTransport(); transports.add(transport); return transport;
            }, ignored -> { }, Runnable::run);
            HostRuntimeStatus running = new HostRuntimeStatus(new SessionSnapshot("s", "ubuntu-base-arm64", "1",
                    SessionState.RUNNING, new gh.nusashell.nusadesk.domain.network.RuntimePort("127.0.0.1", 22022), 1, 2, "", 0), true, "test");
            port.onRuntimeStatus(running);
            TerminalTabSnapshot devin = port.openOrSelectCommand("devin", "devin");
            port.select("shell");
            TerminalTabsRegistry.getInstance().register(port);
            RuntimeStatusBus.getInstance().publish(running);
            invoke(activity, "renderTerminalAppMenu", new Class<?>[]{String.class, TerminalTabsSnapshot.class}, "devin", port.snapshot());
            PopupMenu menu = openMenu(activity);
            click(menu, activity.getString(R.string.terminal_reconnect));
            assertEquals(3, transports.size());
            assertEquals("devin", transports.get(2).command);
            assertFalse("the unrelated shell remains open", transports.get(0).closed);
            port.close(devin.getId());
            click(menu, activity.getString(R.string.terminal_reconnect));
            assertEquals("a stale reconnect must not open another transport", 3, transports.size());
        } finally { lifecycle.destroy(); }
    }

    private static PopupMenu openMenu(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("appSurfaceHost"); field.setAccessible(true);
        AppSurfaceHostView host = (AppSurfaceHostView) field.get(activity);
        host.findViewById(R.id.taskbar_more).performClick();
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu(); assertNotNull(menu); return menu;
    }
    private static void click(PopupMenu menu, String title) {
        for (int i = 0; i < menu.getMenu().size(); i++) {
            MenuItem item = menu.getMenu().getItem(i);
            if (title.contentEquals(item.getTitle())) {
                menu.getMenu().performIdentifierAction(item.getItemId(), 0); return;
            }
        }
        fail("missing menu action: " + title);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args);
    }
    private static final class FakeTransport implements gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransport {
        String command;
        boolean closed;
        public void start(gh.nusashell.nusadesk.infrastructure.ssh.SshSessionConfig config, String command,
                          gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener listener) {
            this.command = command;
        }
        public void write(byte[] bytes) { }
        public void resize(int cols, int rows) { }
        public void close() { closed = true; }
    }
}
