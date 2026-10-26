# ADR-0069: Native desktop controls and installed-app discovery

## Status

ADR-0080 supersedes the default-off packaging and GPL distribution gate below
with regular desktop APKs and accompanying source materials.

Accepted for the enabled native-display build. Extends ADR-0068; the native
renderer stays opt-in and its GPL distribution gate stays open. ADR-0070
supersedes the original single-app/close-before-switch boundary with retained
GUI tasks and minimize/resume.

## Context

The first native slice launched one fixed Mousepad profile. The next accepted
slice requires usable touch scrolling, selection, IME and Linux keys, an
assistive orb, zoom/layout controls, and installed `.desktop` apps on Home.
These applications still run in the one existing ARM64 guest.

## Decision

- Keep Android presentation controls behind `NativeDesktopInput`; the native
  adapter owns IME, pointer and renderer details. Touch mode maps tap to click,
  swipe to wheel and hold to right click. Drag mode explicitly holds the left
  button for selection. Pointer mode moves a relative cursor; two fingers scroll
  at Fit and pan when zoomed. Physical mouse events retain their own button and
  wheel path.
- Use an Editable `BaseInputConnection`: mirror composing text and corrections
  into the guest, preserve code points, and translate local caret/selection
  changes into Linux key events. Reset local IME context on target navigation.
  This is local composition context, not a readback of the GTK document.
- Put a draggable, edge-snapping orb inside the app surface. Its scrollable panel
  contains IME toggle, Touch/Drag/Pointer, navigation keys and one-shot
  Ctrl/Alt/Shift. Clamp the orb to the current application area on keyboard and
  orientation changes. No system-overlay permission is needed.
- Separate presentation zoom (100–400%, with pinch and Fit) from guest logical
  layout (Auto, 960 or 1280 pixels in the panel). The latter resizes the X11
  desktop and gives applications different layout space. Neither setting is
  advertised as changing toolkit/font DPI; the server's existing 144 DPI stays.
- Discover installed GUI apps with guest GLib/GIO, through a packaged Python
  stdlib/ctypes script staged atomically at `/opt/lw-desktop/desktop_apps.py`.
  Query through the existing authenticated local SSH bridge. Do not add a
  second PRoot/session or a host-side `Exec` parser.
- Scan `$XDG_DATA_HOME/applications` (default
  `~/.local/share/applications`), `~/usr/share/applications`, and applications
  directories in `$XDG_DATA_DIRS` (default `/usr/local/share:/usr/share`). IDs
  follow the relative filename with directory separators replaced by hyphens.
  Earlier roots override later roots, including Hidden entries. GIO handles
  localized names, visibility and TryExec. Terminal entries are omitted from
  this GUI catalog; existing terminal-command registrations remain separate.
- Send ID/name/path/icon metadata to Android. Home shows installed entries in
  the existing searchable grid, using guest PNG icons or its usual monogram.
  Refresh on foreground, runtime-ready and Home return. Revalidate the ID at
  launch, so a removed/hidden entry cannot launch from stale metadata.
- Let GIO expand the selected desktop file's `Exec`, quoting, field codes and
  working directory. Force its Exec fallback with an unavailable private D-Bus
  address: a supervised child PID is required. Own the spawned process group,
  wait for an EWMH client window before readiness, and terminate its group on
  teardown. D-Bus-only and self-daemonizing applications need separate profiles.
  This path is not a general session-bus integration.
- Keep one GUI application. Ask before closing an active different application;
  users save their work first. Switching tears down the old display/workload
  before starting the selected entry. Hiding the app or returning Home retains
  the current surface and workload.

## Validation and consequences

Focused JVM/Robolectric tests cover composition, correction, surrogate pairs,
caret/selection, gestures, orb controls, typed status, metadata/icon resolution,
launcher search and shell argument boundaries. Python tests exercise real host
GIO parsing and spawning, XDG precedence/visibility, and field-code expansion.
`make test` includes these Python tests; the Linux test host needs Python 3 and
GLib/GIO (`libgio-2.0.so.0`, `libglib-2.0.so.0`, `libgobject-2.0.so.0`).

Runtime evidence covers one Android 10 custom ROM and does not establish every
OEM/API/IME or app.
There is no automatic GUI package installer or arbitrary architecture support.

GIO launch behavior follows the [GLib desktop-app manager API](https://docs.gtk.org/gio-unix/method.DesktopAppInfo.launch_uris_as_manager.html)
and the [Desktop Entry specification](https://specifications.freedesktop.org/desktop-entry/latest/).
The Android APK adds no GLib dependency; the helper uses the guest's installed
GLib libraries. The Python helper is repository-owned code under the repository
license; the Termux:X11 adaptation keeps its separate GPL provenance.

## Parentless dialog startup correction (2026-10-24)

Text Editor Settings launches `mousepad --preferences`. Its only window is
`_NET_WM_WINDOW_TYPE_DIALOG`, with no `WM_TRANSIENT_FOR`. Openbox's existing
normal-window rule does not match it, leaving a small window on a full-size X11
canvas even at 100% zoom. During the mapped-client startup probe, the guest
helper now requests horizontal/vertical EWMH maximization and disables WM
decorations for parentless dialogs. Owned dialogs and menus are excluded.
This changes window layout, not renderer zoom or font DPI. It does not force
every later dialog to fullscreen or override application fixed-size limits.
The protocol follows the [EWMH window-state request](https://specifications.freedesktop.org/wm/latest/ar01s05.html);
actual device geometry and screenshots were captured on the physical API 29
device.
