package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.backup.BackupFailure;
import gh.nusashell.nusadesk.application.backup.BackupResult;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupSelection;
import gh.nusashell.nusadesk.domain.backup.LastBackupRun;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Backup &amp; restore page renders its three modes, exposes the CUSTOM
 * allowlist, and surfaces typed terminal states — all through real framework
 * inflation on the API levels the launch guard covers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31})
public class SystemBackupPageViewTest {

    private static SystemBackupPageView page() {
        Context context = RuntimeEnvironment.getApplication();
        return new SystemBackupPageView(context);
    }

    private static CheckBox customBox(SystemBackupPageView page, String root) {
        LinearLayout panel = page.findViewById(R.id.system_backup_custom_panel);
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof CheckBox && root.equals(child.getTag())) {
                return (CheckBox) child;
            }
        }
        return null;
    }

    @Test
    public void thePageInflatesWithItsTitleAndActions() {
        SystemBackupPageView page = page();
        TextView title = page.findViewById(R.id.system_page_title);
        assertEquals(page.getContext().getString(R.string.system_backup_title),
                title.getText().toString());
        assertNotNull(page.findViewById(R.id.system_backup_export_action));
        assertNotNull(page.findViewById(R.id.system_backup_import_action));
    }

    @Test
    public void theDefaultSelectionIsFull() {
        SystemBackupPageView page = page();
        BackupSelection selection = page.currentSelection();
        assertNotNull(selection);
        assertEquals(BackupMode.FULL, selection.getMode());
        assertTrue(selection.getRoots().isEmpty());
    }

    @Test
    public void customSelectionComesFromTheAllowlistCheckboxes() {
        SystemBackupPageView page = page();
        page.findViewById(R.id.system_backup_mode_custom).performClick();
        assertEquals(View.VISIBLE,
                page.findViewById(R.id.system_backup_custom_panel).getVisibility());

        // No folder checked: the selection is invalid and the export stays inert.
        assertNull(page.currentSelection());

        customBox(page, "/etc").setChecked(true);
        customBox(page, "/var/lib").setChecked(true);
        BackupSelection selection = page.currentSelection();
        assertNotNull(selection);
        assertEquals(BackupMode.CUSTOM, selection.getMode());
        assertTrue(selection.getRoots().contains("/etc"));
        assertTrue(selection.getRoots().contains("/var/lib"));
    }

    @Test
    public void exportAndImportReachTheHostListener() {
        SystemBackupPageView page = page();
        AtomicReference<BackupSelection> exported = new AtomicReference<>();
        AtomicBoolean imported = new AtomicBoolean(false);
        page.setListener(new SystemBackupPageView.Listener() {
            @Override
            public void onExportRequested(BackupSelection selection) {
                exported.set(selection);
            }

            @Override
            public void onImportRequested() {
                imported.set(true);
            }
        });

        page.findViewById(R.id.system_backup_export_action).performClick();
        assertEquals(BackupMode.FULL, exported.get().getMode());

        page.findViewById(R.id.system_backup_import_action).performClick();
        assertTrue(imported.get());
    }

    @Test
    public void busyDisablesActionsAndResultReEnablesThem() {
        SystemBackupPageView page = page();
        View export = page.findViewById(R.id.system_backup_export_action);
        View importButton = page.findViewById(R.id.system_backup_import_action);

        page.setBusy(true);
        assertFalse(export.isEnabled());
        assertFalse(importButton.isEnabled());
        assertTrue(page.isBusy());

        page.renderResult(BackupResult.failed(BackupFailure.RUNTIME_MISMATCH,
                "not a NusaDesk runtime"));
        assertFalse(page.isBusy());
        assertTrue(export.isEnabled());
        TextView result = page.findViewById(R.id.system_backup_result_value);
        assertEquals(View.VISIBLE, result.getVisibility());
        assertTrue("the typed failure code must reach the user",
                result.getText().toString().contains("runtime-mismatch"));
    }

    @Test
    public void cancelledPickerShowsATerminalMessage() {
        SystemBackupPageView page = page();
        page.setBusy(true);
        page.renderCancelled();
        assertFalse(page.isBusy());
        TextView result = page.findViewById(R.id.system_backup_result_value);
        assertEquals(View.VISIBLE, result.getVisibility());
        assertEquals(page.getContext().getString(R.string.system_backup_cancelled),
                result.getText().toString());
    }

    @Test
    public void lastRunOutcomeRendersWithItsTypedCode() {
        SystemBackupPageView page = page();
        TextView lastRun = page.findViewById(R.id.system_backup_last_run_value);
        assertEquals(View.GONE, lastRun.getVisibility());
        page.renderLastRun(new LastBackupRun(
                LastBackupRun.OPERATION_RESTORE,
                false, "runtime-required", 1_726_000_000_000L));
        assertEquals(View.VISIBLE, lastRun.getVisibility());
        assertTrue(lastRun.getText().toString().contains("runtime-required"));
    }

    @Test
    public void theBackRowReachesItsListener() {
        SystemBackupPageView page = page();
        AtomicBoolean back = new AtomicBoolean(false);
        page.setOnBackListener(view -> back.set(true));
        page.findViewById(R.id.system_page_back).performClick();
        assertTrue(back.get());
    }
}
