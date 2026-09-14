package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.GuestSshPayloadProfile;

/**
 * Application boundary for installing one curated guest SSH add-on profile.
 *
 * <p>The add-on is a private overlay directory activated alongside an already
 * installed runtime; it never modifies the active rootfs. Snapshots are
 * reported under the profile's add-on id through the same
 * {@link RuntimeInstallationUseCase.ProgressListener} contract.</p>
 */
public interface GuestSshAddonInstallUseCase {
    /**
     * Installs {@code profile} for the runtime identified by
     * {@code runtimeAppId}, which must already be installed.
     */
    void install(
            GuestSshPayloadProfile profile,
            String runtimeAppId,
            RuntimeInstallationUseCase.ProgressListener listener)
            throws RuntimeInstallationException;
}
