package gh.nusashell.nusadesk.application.workspace;

import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;

/**
 * Persists the workspace folder the user picked.
 *
 * <p>The workspace is a user choice, not derived state: it must survive process
 * death, activity recreation, and reboots, so the runtime can bind the same
 * folder again on the next start without asking again. A stored value that no
 * longer translates to a usable host path is treated as absent rather than
 * guessed at (see {@link WorkspaceFolder#restore}).</p>
 */
public interface WorkspaceStore {

    /** The stored workspace, or {@code null} when the user has not chosen one. */
    WorkspaceFolder load();

    /** Stores the user's choice, replacing any previous one. */
    void save(WorkspaceFolder folder);

    /** Drops the stored choice; the guest then has no workspace until one is picked. */
    void clear();
}
