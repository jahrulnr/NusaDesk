package gh.nusashell.nusadesk.presentation.terminal;

import android.view.View;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class TerminalRecoveryUiTest {
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void renderFailedSessionForOfflineLayoutInspection() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.setTheme(R.style.AppTheme);
        TerminalAppView view = new TerminalAppView(context);
        view.setBackgroundColor(android.graphics.Color.BLACK);
        view.renderSessionStatus(new HostRuntimeStatus(new SessionSnapshot("s", "ubuntu-base-arm64", "1",
                SessionState.FAILED, null, 1, 2,
                "guest OpenSSH sshd exited unexpectedly (exit code 1)\npath.c:453: detranslate_path: Assertion binding_referrer failed", 0), true, "guest SSH"));
        view.measure(View.MeasureSpec.makeMeasureSpec(414, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(896, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 414, 896);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(414, 896, android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.nio.file.Path output = java.nio.file.Paths.get("build/qa-terminal-recovery.png");
        java.nio.file.Files.createDirectories(output.getParent());
        try (java.io.OutputStream stream = java.nio.file.Files.newOutputStream(output)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        }
        View button = view.findViewById(R.id.attach_action);
        assertTrue(button.getWidth() > 0 && button.getHeight() > 0);
    }

    @Test public void failedLinuxOffersRestartAndPreservesTheFailureReason() {
        TerminalAppView view = new TerminalAppView(RuntimeEnvironment.getApplication());
        int[] restarts = {0};
        view.setOnRestartLinuxListener(v -> restarts[0]++);
        view.renderSessionStatus(new HostRuntimeStatus(new SessionSnapshot("s", "ubuntu-base-arm64", "1",
                SessionState.FAILED, null, 1, 2, "guest OpenSSH sshd exited unexpectedly (exit code 1)", 0),
                true, "guest SSH"));
        Button action = view.findViewById(R.id.attach_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(view.getContext().getString(R.string.terminal_restart_linux), action.getText());
        assertTrue(((android.widget.TextView) view.findViewById(R.id.attach_body))
                .getText().toString().contains("exit code 1"));
        action.performClick();
        assertEquals(1, restarts[0]);
    }
}
