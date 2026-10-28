# Native desktop and GPU rendering

## Use the display NusaDesk already owns

NusaDesk regular releases include an Android-native X11 server and
a launcher for Linux GUI apps in this same Ubuntu ARM64 guest.
The host starts display `:1`, one Openbox window manager and a
display session bus when a GUI tile opens. A plain SSH shell may
have no DISPLAY; that does not mean desktop support is missing.
Do not start another X server, Xvfb, VNC, xrdp, Wayland compositor
or full GNOME/KDE/Xfce desktop just to open one application.
Do not replace the host-owned Openbox or launch a second instance.

The shared GUI prerequisites are openbox, xkb-data, x11-utils,
Python 3, GLib/GIO and dbus-run-session. Check what is already
installed; if NusaDesk reports missing components, install only
those components with --no-install-recommends. GPU forwarding
also requires guest Mesa libraries with the virpipe driver.

## Install the requested GUI application

1. Check `dpkg --print-architecture` (arm64). Choose the upstream
   Linux ARM64/aarch64 glibc desktop build, not x86_64, an Android
   APK, or the similarly named CLI. For OpenCode, select Desktop
   ARM64 from https://opencode.ai/v2/docs and inspect that release's
   package metadata; a terminal tile running `opencode` is a
   separate CLI app and does not satisfy a desktop request.
2. Install the app and its actual runtime dependencies. For a local
   .deb inspect `dpkg-deb -f ./app-arm64.deb Architecture Depends Recommends`,
   then use `apt-get install --no-install-recommends ./app-arm64.deb`.
   These are example filenames: use the actual downloaded artifact.
   Prefer a .deb or extracted distribution in PRoot; an AppImage
   may need extraction instead of a FUSE mount. Do not infer missing
   dependencies from the app name or assume every GUI is Electron.
3. Reuse the package's .desktop entry. If none exists, create one
   under `/root/.local/share/applications/` with `Type=Application`,
   `Terminal=false` and an absolute Exec path to the GUI binary.
   NusaDesk scans that directory, `/root/usr/share/applications/`,
   and the XDG application directories (normally
   `/usr/local/share/applications/` and `/usr/share/applications/`).
4. Return to All apps to refresh the catalog, then open the GUI
   tile. The host supplies DISPLAY, graphics selection and D-Bus.
   A visible tile alone is not proof that the app launched or used GPU.

Example .desktop contents, with the installed path substituted:

```ini
[Desktop Entry]
Type=Application
Name=My GUI
Exec=/opt/my-gui/my-gui
Terminal=false
```

Openbox Configuration/obconf and Zutty are separate Linux apps
whose .desktop files may appear after package installation. They
are not prerequisites just for displaying another GUI. Check
package dependencies before removing anything; `openbox` itself
is required by the host. To hide an unwanted tile without removing
packages, copy its .desktop file to the user applications directory
under the same desktop ID and set `NoDisplay=true` in that copy.

## GPU route and app-specific launchers

The path is guest Mesa virpipe -> `/tmp/.virgl` -> Android
virglrenderer -> vendor EGL/GLES -> GPU. It needs no new guest
Vulkan/DRM driver, root or second display. The Android host owns
the renderer lifecycle; do not launch a second virgl server.
Each GUI launch sources `/opt/lw-desktop/graphics-env.sh`. It
selects `GALLIUM_DRIVER=virpipe` only after a bounded EGL pixel
draw/readback probe passes, otherwise it retains `llvmpipe`.
`LIBGL_ALWAYS_SOFTWARE=1` is also set on the virpipe route: it
selects Mesa's loader and alone does not prove CPU rendering.

For a confirmed Electron build, an explicit .desktop Exec can use:

```ini
Exec=/opt/lw-desktop/gpu-electron.sh /opt/my-gui/my-gui
```

This wrapper probes first and applies the tested GLES/ANGLE and
Mesa version compatibility flags only on virpipe. It forwards
arguments to the existing app launcher. Do not apply Electron flags
to GTK/Qt/Tauri apps or export these overrides globally. Diagnose
that toolkit's X11/EGL support and dependencies separately.

## Verify and diagnose

Open a GUI tile first so the host display/renderer is started.
In a guest shell the following selects and reports the same route:

```sh
export DISPLAY=:1 GDK_BACKEND=x11
. /opt/lw-desktop/graphics-env.sh
printf 'driver=%s socket=%s\n' "$GALLIUM_DRIVER" "${VTEST_SOCKET_NAME:-unset}"
cat /tmp/nusadesk-graphics.log
```

Require `driver=virpipe` plus a fresh probe reporting
`"verified": true`, renderer `virgl (...)`
and the expected red/blue pixel values. Also verify the real GUI
window and, where available, its own renderer diagnostics: passing
the Mesa probe does not prove a toolkit selected GPU rendering.
Logs: `/tmp/nusadesk-graphics.log`, `/tmp/nusadesk-virgl.log` and
`/tmp/nusadesk-wm.log`. Socket presence, a PID, DISPLAY, or Android
hardware-accelerated presentation alone are not GPU evidence.
`glxinfo -B` can fail with X_GetImage BadMatch on the tested route;
use the shipped EGL pixel probe rather than replacing the stack.
If /opt/lw-desktop helpers are absent, return to All apps to stage
them; check the installed NusaDesk build if native support is missing.

This is best-effort GLES forwarding, not full OpenGL/Vulkan
conformance, zero-copy, video acceleration or a general GPU driver.
The tested Mali-T880 route has an MSAA/WebGL framebuffer limitation;
apps requiring it may need software rendering. Keep any workaround
local to that app and report the actual renderer/failure. Do not
claim every GUI app or every Android GPU is accelerated.
`/tmp` and these graphics logs are session-temporary. Store installed
apps and .desktop entries in persistent guest directories.

> Docs generated by the NusaDesk Android app (version %APP_VERSION%). Manual edits may be overwritten on the next session start.

