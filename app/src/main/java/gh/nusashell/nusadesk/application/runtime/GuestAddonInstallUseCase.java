package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;

/**
 * Application boundary for installing one curated guest add-on profile.
 *
 * <p>An add-on is a private overlay directory activated alongside an already
 * installed runtime; it never modifies the active rootfs. Snapshots are
 * reported under the profile's add-on id through the same
 * {@link RuntimeInstallationUseCase.ProgressListener} contract.</p>
 */
public interface GuestAddonInstallUseCase {
    /**
     * Installs {@code profile} for the runtime identified by
     * {@code runtimeAppId}, which must already be installed.
     */
    void install(
            GuestAddonPayloadProfile profile,
            String runtimeAppId,
            RuntimeInstallationUseCase.ProgressListener listener)
            throws RuntimeInstallationException;
}
