package gh.nusashell.nusadesk.presentation.workspace;

import gh.nusashell.nusadesk.R;

/**
 * What the Linux system screen shows for the workspace folder.
 *
 * <p>Pure presentation state: each outcome carries the text and the single action
 * it is allowed to offer. Keeping the wording and the action selection here —
 * instead of in the view — is what makes the honest states testable: Android 10
 * gets a workspace in the app's own external folder and is told that plainly,
 * Android 11+ is told why the grant is needed before any folder can be picked,
 * and no state offers an action it cannot honour.</p>
 */
public final class WorkspaceUiState {

    /** Which situation the device is in. */
    public enum Kind {
        /**
         * Below API 30: all-files access does not exist, so no shared folder can
         * be bound; the workspace is the app's own external folder instead.
         */
        APP_FOLDER,
        /** API 30+ without the all-files grant: the user must allow it first. */
        NEEDS_ALL_FILES_ACCESS,
        /** Grant in place, no folder chosen yet. */
        NOT_CHOSEN,
        /** Grant in place and a folder stored. */
        CHOSEN
    }

    private final Kind kind;
    private final String folderLabel;
    private final String detailArg;

    private WorkspaceUiState(Kind kind, String folderLabel, String detailArg) {
        this.kind = kind;
        this.folderLabel = folderLabel;
        this.detailArg = detailArg;
    }

    /** The workspace below API 30: the app's own external folder, named by its path. */
    public static WorkspaceUiState appFolder(String hostPath) {
        return new WorkspaceUiState(Kind.APP_FOLDER, null, hostPath);
    }

    public static WorkspaceUiState needsAllFilesAccess() {
        return new WorkspaceUiState(Kind.NEEDS_ALL_FILES_ACCESS, null, null);
    }

    public static WorkspaceUiState notChosen() {
        return new WorkspaceUiState(Kind.NOT_CHOSEN, null, null);
    }

    public static WorkspaceUiState chosen(String folderLabel) {
        return new WorkspaceUiState(Kind.CHOSEN, folderLabel, null);
    }

    public Kind getKind() {
        return kind;
    }

    /** The chosen folder's label, or {@code null} when nothing was chosen. */
    public String getFolderLabel() {
        return folderLabel;
    }

    /** Value text resource, or 0 when the value is the folder label itself. */
    public int getValueRes() {
        switch (kind) {
            case CHOSEN:
                return 0;
            case APP_FOLDER:
                return R.string.system_workspace_value_app_folder;
            case NEEDS_ALL_FILES_ACCESS:
            case NOT_CHOSEN:
            default:
                return R.string.system_workspace_value_none;
        }
    }

    /** The explanation shown under the value. */
    public int getDetailRes() {
        switch (kind) {
            case APP_FOLDER:
                return R.string.system_workspace_detail_app_folder;
            case NEEDS_ALL_FILES_ACCESS:
                return R.string.system_workspace_detail_permission;
            case CHOSEN:
                return R.string.system_workspace_detail_chosen;
            case NOT_CHOSEN:
            default:
                return R.string.system_workspace_detail_none;
        }
    }

    /** Argument for a detail string with a placeholder, or {@code null}. */
    public String getDetailArg() {
        return detailArg;
    }

    /** The only action this state offers, or 0 when it offers none. */
    public int getActionRes() {
        switch (kind) {
            case NEEDS_ALL_FILES_ACCESS:
                return R.string.system_workspace_action_grant;
            case NOT_CHOSEN:
                return R.string.system_workspace_action_choose;
            case CHOSEN:
                return R.string.system_workspace_action_change;
            case APP_FOLDER:
            default:
                // Android 10 has no bindable shared folder and the built-in
                // picker would need a broader storage grant there, so this state
                // offers no action rather than one it cannot honour (ADR-0047).
                return 0;
        }
    }

    /** Whether this state offers an action at all. */
    public boolean hasAction() {
        return getActionRes() != 0;
    }
}
