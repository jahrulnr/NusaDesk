package gh.nusashell.nusadesk.presentation.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import gh.nusashell.nusadesk.R;

import org.junit.Test;

/**
 * The workspace card is the only place the product asks for a broad storage
 * grant, so its copy and its action are pinned: each state explains itself, and a
 * state only offers an action it can actually honour. Android 10 in particular
 * must name the folder it really uses — the app's own external folder — instead
 * of either inviting a pick that could not be bound or claiming no workspace
 * exists.
 */
public class WorkspaceUiStateTest {

    @Test
    public void androidTenNamesTheAppFolderItActuallyUsesAndOffersTheInAppPicker() {
        WorkspaceUiState state =
                WorkspaceUiState.appFolder("/storage/emulated/0/Android/media/pkg/nusadesk");

        assertEquals(WorkspaceUiState.Kind.APP_FOLDER, state.getKind());
        assertEquals(R.string.system_workspace_value_app_folder, state.getValueRes());
        assertEquals(R.string.system_workspace_detail_app_folder, state.getDetailRes());
        assertEquals("the detail names the real folder",
                "/storage/emulated/0/Android/media/pkg/nusadesk", state.getDetailArg());
        // The in-app browser walks the app's own tree without any extra grant,
        // so Android 10 can pick a folder after all (ADR-0047).
        assertEquals(R.string.system_workspace_action_choose, state.getActionRes());
        assertTrue(state.hasAction());
        assertNull(state.getFolderLabel());
    }

    @Test
    public void missingGrantExplainsItselfAndOffersTheSettingsAction() {
        WorkspaceUiState state = WorkspaceUiState.needsAllFilesAccess();

        assertEquals(R.string.system_workspace_detail_permission, state.getDetailRes());
        assertEquals(R.string.system_workspace_action_grant, state.getActionRes());
        assertNull(state.getDetailArg());
        assertTrue(state.hasAction());
    }

    @Test
    public void emptyChoiceOffersThePicker() {
        WorkspaceUiState state = WorkspaceUiState.notChosen();

        assertEquals(R.string.system_workspace_detail_none, state.getDetailRes());
        assertEquals(R.string.system_workspace_action_choose, state.getActionRes());
        assertEquals(R.string.system_workspace_value_none, state.getValueRes());
    }

    @Test
    public void chosenFolderShowsItsLabelAndOffersAChange() {
        WorkspaceUiState state = WorkspaceUiState.chosen("Documents/nusadesk");

        assertEquals(WorkspaceUiState.Kind.CHOSEN, state.getKind());
        assertEquals("Documents/nusadesk", state.getFolderLabel());
        assertEquals("the label is the value", 0, state.getValueRes());
        assertEquals(R.string.system_workspace_detail_chosen, state.getDetailRes());
        assertEquals(R.string.system_workspace_action_change, state.getActionRes());
    }
}
