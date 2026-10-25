# ADR-0078: Native display endpoint in guest session environments

## Status

Accepted for the optional native-enabled build. GUI application compatibility
continues to require separate runtime evidence.

## Decision

Native-enabled NusaDesk advertises its guest-local X11 address, `DISPLAY=:1`,
to interactive SSH shells, SSH exec commands, and the guest session supervisor.
The latter also supplies it to the existing system/user service managers and
their children. The default build does not advertise a display it does not ship.
The address is defined by `NativeDesktopPort.X11_DISPLAY`; the internal GUI
launcher uses the same constant.

The guest sshd uses `SetEnv`, since it constructs a session environment instead
of inheriting arbitrary daemon variables. The supervisor receives the same
address through the existing PRoot environment seam. No user shell profile is
rewritten, no client `AcceptEnv` policy is added, and SSH X11 forwarding stays
disabled: the connection is to the local guest socket. No Wayland display or
Xauthority cookie is invented.

This corrects applications that use the environment to detect desktop
capability. In the inspected NusaShell checkout, both install scripts skip
Electron/Pet prompts without `DISPLAY` or `WAYLAND_DISPLAY`; Pet also inherits
the parent environment or consults the user manager environment. NusaDesk must
supply the endpoint across both launch paths.

`DISPLAY` names an endpoint; it is not a health result. The existing native
launch path still starts the X server and window manager on demand. Setting the
variable does not itself start a server, select a native surface, create an
Android overlay, or establish Electron/SDL/GPU compatibility. Terminal-launched
windows belong to the guest display and appear in its native Android surface.
Already-running shells and NusaShell processes retain their old environment;
they must be started again after the new guest session begins.

## Verification

The daemon-config regression verifies native/default behavior and retained
`X11Forwarding no`. The physical QA driver has separate login-shell, exec and
user-manager environment checks and runs `xdpyinfo` without an explicit display
argument against a started native surface. Actual installer/Pet/Electron results
are recorded separately from those environment checks.
