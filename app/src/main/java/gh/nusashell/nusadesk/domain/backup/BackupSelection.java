package gh.nusashell.nusadesk.domain.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one export should contain: a {@link BackupMode} plus, for HOME and
 * CUSTOM, the selected top-level guest directories in their guest-absolute
 * spelling ({@code "/usr/local"}). FULL always carries an empty root list.
 *
 * <p>Selection is validated here, at the domain boundary, so the UI can never
 * be the only enforcement: a CUSTOM selection outside
 * {@link BackupScopePolicy#allowedCustomRoots()} cannot be constructed.</p>
 */
public final class BackupSelection {

    private final BackupMode mode;
    private final List<String> roots;

    private BackupSelection(BackupMode mode, List<String> roots) {
        if (mode == null) {
            throw new IllegalArgumentException("backup mode must not be null");
        }
        BackupScopePolicy.validateManifestRoots(mode, roots);
        this.mode = mode;
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
    }

    /** @return a selection covering the whole active runtime, add-ons, and state. */
    public static BackupSelection full() {
        return new BackupSelection(BackupMode.FULL, Collections.emptyList());
    }

    /** @return a selection covering {@code /root} and {@code /home}. */
    public static BackupSelection home() {
        return new BackupSelection(BackupMode.HOME, BackupScopePolicy.homeRoots());
    }

    /**
     * @return a selection over the given allowlisted guest roots, canonicalised
     *         into allowlist order.
     * @throws IllegalArgumentException when empty or outside the allowlist.
     */
    public static BackupSelection custom(List<String> roots) {
        return new BackupSelection(
                BackupMode.CUSTOM, BackupScopePolicy.validateCustomRoots(roots));
    }

    public BackupMode getMode() {
        return mode;
    }

    /** @return the selected guest-absolute roots; empty for FULL. */
    public List<String> getRoots() {
        return roots;
    }
}
