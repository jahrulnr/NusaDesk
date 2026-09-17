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
    public void androidTenNamesTheAppFolderItActuallyUsesAndOffersNothing() {
        WorkspaceUiState state =
                WorkspaceUiState.appFolder("/storage/emulated/0/Android/data/pkg/files/nusadesk");

        assertEquals(WorkspaceUiState.Kind.APP_FOLDER, state.getKind());
        assertEquals(R.string.system_workspace_value_app_folder, state.getValueRes());
        assertEquals(R.string.system_workspace_detail_app_folder, state.getDetailRes());
        assertEquals("the detail names the real folder",
                "/storage/emulated/0/Android/data/pkg/files/nusadesk", state.getDetailArg());
        assertFalse("there is no grant to ask for below API 30", state.hasAction());
        assertEquals(0, state.getActionRes());
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
