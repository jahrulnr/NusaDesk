package gh.nusashell.nusadesk.infrastructure.sshserver;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Port that launches the <em>fixed curated</em> guest interactive shell.
 *
 * <p>This is the security boundary between the SSH bridge and the Linux guest.
 * The guest argv is fixed by the implementation (the PRoot adapter); the caller
 * <strong>never</strong> supplies a command string. The bridge routes SSH
 * shell channels here so an authenticated client gets the curated guest shell
 * (for example {@code /bin/sh} inside the PRoot rootfs). SSH exec channels,
 * which carry an arbitrary client command string, are refused by the bridge
 * and never reach this port &mdash; the host must not run arbitrary host
 * {@link ProcessBuilder} command strings (AGENTS.md).</p>
 *
 * <p>The launcher owns the guest invocation and the stream pumps: it bridges
 * the given {@code stdin}/{@code stdout}/{@code stderr} to the guest process
 * and returns a {@link GuestShellHandle} the bridge uses to resize, await, and
 * tear down the shell. The handle's teardown must be bounded.</p>
 *
 * <p>This port carries no Android and no MINA types so a deterministic fake can
 * stand in for the PRoot adapter in unit tests.</p>
 */
public interface GuestShellLauncher {

    /**
     * Launch the fixed curated guest shell, bridging the given streams.
     *
     * @param stdin  bytes the SSH client sends (the shell's stdin)
     * @param stdout bytes the shell writes back to the client
     * @param stderr bytes the shell writes to the client's stderr
     * @param ptySize initial PTY dimensions reported by the client
     * @return a handle to manage the launched shell
     * @throws GuestShellLaunchException if the guest shell cannot start
     */
    GuestShellHandle launchShell(InputStream stdin, OutputStream stdout, OutputStream stderr, PtySize ptySize)
            throws GuestShellLaunchException;
}
