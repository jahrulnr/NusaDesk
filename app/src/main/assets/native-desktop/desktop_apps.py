#!/usr/bin/env python3
"""Guest-only desktop discovery/launch using GLib's desktop-entry implementation."""
import ctypes as C
import json
import os
from pathlib import Path
import signal
import sys
import time

os.environ.setdefault("XDG_CURRENT_DESKTOP", "NusaDesk")
P = C.c_void_p


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


try:
    GIO = C.CDLL("libgio-2.0.so.0")
    GLIB = C.CDLL("libglib-2.0.so.0")
    OBJECT = C.CDLL("libgobject-2.0.so.0")
    # A missing symbol (AttributeError) is the same failure as a missing
    # library: no usable GTK desktop stack, one classified marker.
    new_info = bind(GIO, "g_desktop_app_info_new_from_filename", P, C.c_char_p)
    unref = bind(OBJECT, "g_object_unref", None, P)
    free = bind(GLIB, "g_free", None, P)
    name_of = bind(GIO, "g_app_info_get_display_name", C.c_char_p, P)
    exec_of = bind(GIO, "g_app_info_get_executable", C.c_char_p, P)
    show = bind(GIO, "g_app_info_should_show", C.c_int, P)
    hidden = bind(GIO, "g_desktop_app_info_get_is_hidden", C.c_int, P)
    boolean = bind(GIO, "g_desktop_app_info_get_boolean", C.c_int, P, C.c_char_p)
    get_string = bind(GIO, "g_desktop_app_info_get_string", P, P, C.c_char_p)
except (OSError, AttributeError) as error:
    print("__NUSADESK_GUI_FAILED__Desktop library unavailable: " + str(error), file=sys.stderr, flush=True)
    raise SystemExit(1)


def text(info, key):
    value = get_string(info, key.encode())
    try:
        return C.string_at(value).decode("utf-8") if value else ""
    finally:
        if value:
            free(value)


def roots():
    home = Path.home()
    data_home = Path(os.environ.get("XDG_DATA_HOME", str(home / ".local/share")))
    result = [data_home / "applications", home / "usr/share/applications"]
    result += [Path(p) / "applications" for p in os.environ.get("XDG_DATA_DIRS", "/usr/local/share:/usr/share").split(":") if p]
    return list(dict.fromkeys(result))


def files():
    seen = set()
    for root in roots():
        try:
            if not root.is_dir():
                continue
            paths = sorted(root.rglob("*.desktop"))
        except OSError as error:
            print("Skipping unreadable applications root " + str(root) + ": " + str(error), file=sys.stderr)
            continue
        for path in paths:
            desktop_id = str(path.relative_to(root)).replace(os.sep, "-")
            if desktop_id not in seen:
                seen.add(desktop_id)  # Hidden user entries also mask system entries.
                yield desktop_id, path


def icon_path(icon):
    if icon.startswith("/") and Path(icon).is_file() and Path(icon).suffix.lower() == ".png":
        # Resolve inside the guest: an absolute target of a version symlink
        # otherwise resolves against Android's root when the host decodes it.
        return str(Path(icon).resolve())
    if "/" in icon or ".." in icon:
        # A themed icon name is a single segment; separators or parent
        # traversal are spec-invalid and must not escape the theme roots.
        return ""
    for base in (Path.home() / ".local/share/icons", Path.home() / ".icons", Path("/usr/share/icons")):
        for theme in ("hicolor", "Adwaita"):
            for size in ("48x48", "64x64", "128x128", "256x256", "32x32"):
                path = base / theme / size / "apps" / (icon + ".png")
                if path.is_file():
                    return str(path.resolve())
    path = Path("/usr/share/pixmaps") / (icon + ".png")
    return str(path.resolve()) if path.is_file() else ""


def entry(desktop_id, path):
    info = new_info(os.fsencode(path))
    if not info:
        return None
    try:
        if hidden(info) or not show(info) or boolean(info, b"Terminal") or not exec_of(info):
            return None
        icon = text(info, "Icon")
        return {"id": desktop_id, "name": name_of(info).decode("utf-8"),
                "path": str(path), "icon": icon, "icon_path": icon_path(icon),
                "wm_class": text(info, "StartupWMClass")}
    finally:
        unref(info)


def scan():
    result = []
    for desktop_id, path in files():
        try:
            item = entry(desktop_id, path)
        except Exception as error:
            # One malformed entry must not empty the whole catalog.
            print("Skipping malformed desktop entry " + str(path) + ": " + str(error), file=sys.stderr)
            continue
        if item:
            result.append(item)
    return sorted(result, key=lambda item: (item["name"].casefold(), item["id"]))


def primary_dialog(types, transient_for, dialog_atom):
    """A dialog launched as an application is a kiosk root; owned dialogs are not."""
    return dialog_atom in types and not transient_for


def owned_windows(windows, properties, groups, wm_classes=(), group_of=os.getpgid):
    """Keep a task's process-group windows and transients, preserving stacking order.

    A window is owned when its _NET_WM_PID's process group is supervised, or its
    WM_CLASS — either of the two strings — matches the desktop entry's
    StartupWMClass, the freedesktop association that survives a respawn into a
    new process group (ADR-0086)."""
    owned = set()
    wanted = set(wm_classes)
    for window in windows:
        for pid in properties(window, "_NET_WM_PID"):
            try:
                if group_of(pid) in groups:
                    owned.add(window)
            except OSError:
                # A vanished or foreign-owned process has no group to match.
                pass
        if window not in owned and wanted:
            if any(value in wanted for value in properties(window, "WM_CLASS")):
                owned.add(window)
    changed = True
    while changed:
        changed = False
        for window in windows:
            if window not in owned and any(parent in owned for parent in properties(window, "WM_TRANSIENT_FOR")):
                owned.add(window)
                changed = True
    return [window for window in windows if window in owned]


def foreground_request():
    try:
        return json.loads(Path("/tmp/nusadesk-foreground.json").read_text())
    except (OSError, ValueError):
        return {"id": "", "token": ""}


def property_reader(display, atom, get_property, release):
    """Read one X11 window property as its decoded values: unsigned longs for a
    format-32 property (pids, atoms, window ids) or the NUL-separated strings of
    a format-8 STRING property such as WM_CLASS."""
    def values(window, name):
        actual, count, remaining = C.c_ulong(), C.c_ulong(), C.c_ulong()
        fmt, data = C.c_int(), P()
        status = get_property(display, window, atom(name), 0, 4096, 0, 0,
                              C.byref(actual), C.byref(fmt), C.byref(count), C.byref(remaining), C.byref(data))
        try:
            if status != 0 or not data:
                return []
            if fmt.value == 32:
                return list(C.cast(data, C.POINTER(C.c_ulong))[:count.value])
            if fmt.value == 8:
                raw = C.string_at(data, count.value)
                return [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part]
            return []
        finally:
            if data:
                release(data)
    return values


def window_mapped(groups=None, desktop_id=None, visibility=None, wm_classes=()):
    x = C.CDLL("libX11.so.6")
    display = bind(x, "XOpenDisplay", P, C.c_char_p)(None)
    if not display:
        return False
    # A client can disappear between the EWMH list and its property query.
    # Keep BadWindow/protocol errors local to this probe instead of Xlib exiting.
    errors = []
    error_type = C.CFUNCTYPE(C.c_int, P, P)
    error_handler = error_type(lambda display, event: errors.append(True) or 0)
    set_error_handler = bind(x, "XSetErrorHandler", P, P)
    previous_handler = set_error_handler(C.cast(error_handler, P))
    try:
        root = bind(x, "XDefaultRootWindow", C.c_ulong, P)(display)
        intern = bind(x, "XInternAtom", C.c_ulong, P, C.c_char_p, C.c_int)
        def atom(name):
            return intern(display, name.encode(), 0)
        get_property = bind(x, "XGetWindowProperty", C.c_int, P, C.c_ulong, C.c_ulong, C.c_long,
                            C.c_long, C.c_int, C.c_ulong, P, P, P, P, P)
        release = bind(x, "XFree", C.c_int, P)
        property_values = property_reader(display, atom, get_property, release)

        windows = property_values(root, "_NET_CLIENT_LIST_STACKING") or property_values(root, "_NET_CLIENT_LIST")
        if groups is not None:
            windows = owned_windows(windows, property_values, groups, wm_classes)
        previous = visibility if visibility is not None else {}
        old_windows = previous.get("windows", set())
        for window in windows:
            if window not in old_windows and primary_dialog(property_values(window, "_NET_WM_WINDOW_TYPE"),
                              property_values(window, "WM_TRANSIENT_FOR"), atom("_NET_WM_WINDOW_TYPE_DIALOG")):
                fit_primary_dialog(x, display, root, window, atom)
        if desktop_id is not None:
            request = foreground_request()
            changed = request.get("token") != previous.get("token")
            targets = windows if changed else [w for w in windows if w not in old_windows]
            if request.get("id") == desktop_id:
                close_token = request.get("close", "")
                if close_token and close_token != previous.get("close"):
                    # Ask each primary client to close. GTK owns unsaved-document prompts.
                    for window in windows:
                        if not property_values(window, "WM_TRANSIENT_FOR"):
                            send_message(x, display, root, window, atom("_NET_CLOSE_WINDOW"), (0, 2, 0, 0, 0))
                    previous["close"] = close_token
                for window in targets:
                    bind(x, "XMapRaised", C.c_int, P, C.c_ulong)(display, window)
                if targets:
                    send_message(x, display, root, windows[-1], atom("_NET_ACTIVE_WINDOW"), (2, 0, 0, 0, 0))
            else:
                for window in reversed(targets):
                    bind(x, "XIconifyWindow", C.c_int, P, C.c_ulong, C.c_int)(display, window, 0)
            previous["token"] = request.get("token")
        previous["windows"] = set(windows)
        bind(x, "XSync", C.c_int, P, C.c_int)(display, 0)
        return bool(windows) and not errors
    finally:
        bind(x, "XCloseDisplay", C.c_int, P)(display)
        set_error_handler(previous_handler)


def fit_primary_dialog(x, display, root, window, atom):
    """Use WM protocols to fit a parentless dialog, without stretching rendered pixels."""
    # Xlib represents format-32 property elements as native unsigned longs.
    motif = (C.c_ulong * 5)(2, 0, 0, 0, 0)  # MWM_HINTS_DECORATIONS, no decorations.
    change = bind(x, "XChangeProperty", C.c_int, P, C.c_ulong, C.c_ulong, C.c_ulong,
                  C.c_int, C.c_int, P, C.c_int)
    change(display, window, atom("_MOTIF_WM_HINTS"), atom("_MOTIF_WM_HINTS"), 32, 0, C.cast(motif, P), 5)

    send_message(x, display, root, window, atom("_NET_WM_STATE"),
                 (1, atom("_NET_WM_STATE_MAXIMIZED_HORZ"), atom("_NET_WM_STATE_MAXIMIZED_VERT"), 1, 0))


CLIENT_MESSAGE = 33  # XClientMessageEvent.type for an EWMH client message.
SUBSTRUCTURE_EVENT_MASK = (1 << 20) | (1 << 19)  # SubstructureRedirectMask | SubstructureNotifyMask.


def send_message(x, display, root, window, message_type, values):
    class ClientMessage(C.Structure):
        _fields_ = [("type", C.c_int), ("serial", C.c_ulong), ("send_event", C.c_int),
                    ("display", P), ("window", C.c_ulong), ("message_type", C.c_ulong),
                    ("format", C.c_int), ("data", C.c_long * 5)]
    class XEvent(C.Union):
        _fields_ = [("client", ClientMessage), ("pad", C.c_long * 24)]
    event = XEvent()
    event.client = ClientMessage(CLIENT_MESSAGE, 0, 0, display, window, message_type, 32, (C.c_long * 5)(*values))
    send = bind(x, "XSendEvent", C.c_int, P, C.c_ulong, C.c_int, C.c_long, P)
    send(display, root, 0, SUBSTRUCTURE_EVENT_MASK, C.byref(event))


def close_owned_windows(groups, wm_classes):
    """Ask each owned primary window to close through EWMH. Teardown uses it so
    a respawned window that left the supervised process group — adopted by its
    StartupWMClass — is asked to quit instead of lingering as an orphan."""
    try:
        x = C.CDLL("libX11.so.6")
    except OSError:
        return
    display = bind(x, "XOpenDisplay", P, C.c_char_p)(None)
    if not display:
        return
    # Same vanishing-client hazard as the supervision probe: keep protocol
    # errors local to this sweep instead of letting Xlib print to stderr.
    error_type = C.CFUNCTYPE(C.c_int, P, P)
    error_handler = error_type(lambda display, event: 0)
    set_error_handler = bind(x, "XSetErrorHandler", P, P)
    previous_handler = set_error_handler(C.cast(error_handler, P))
    try:
        root = bind(x, "XDefaultRootWindow", C.c_ulong, P)(display)
        intern = bind(x, "XInternAtom", C.c_ulong, P, C.c_char_p, C.c_int)
        def atom(name):
            return intern(display, name.encode(), 0)
        get_property = bind(x, "XGetWindowProperty", C.c_int, P, C.c_ulong, C.c_ulong, C.c_long,
                            C.c_long, C.c_int, C.c_ulong, P, P, P, P, P)
        release = bind(x, "XFree", C.c_int, P)
        property_values = property_reader(display, atom, get_property, release)
        windows = property_values(root, "_NET_CLIENT_LIST_STACKING") or property_values(root, "_NET_CLIENT_LIST")
        for window in owned_windows(windows, property_values, groups, wm_classes):
            if not property_values(window, "WM_TRANSIENT_FOR"):
                send_message(x, display, root, window, atom("_NET_CLOSE_WINDOW"), (0, 2, 0, 0, 0))
        bind(x, "XSync", C.c_int, P, C.c_int)(display, 0)
    finally:
        bind(x, "XCloseDisplay", C.c_int, P)(display)
        set_error_handler(previous_handler)


# How long the supervision loop waits for an owned window to return once no
# supervised process remains: ~3 s at the 0.15 s probe interval, covering an
# application update -> respawn gap before the task reports CLOSED (ADR-0086).
UNMAPPED_GRACE = 20


def launch(desktop_id):
    selected = next((item for item in scan() if item["id"] == desktop_id), None)
    if not selected:
        raise RuntimeError("Desktop application is no longer installed or visible: " + desktop_id)
    # A supervised PID is required. An unavailable private bus makes GIO use its
    # documented Exec spawn path, preserving quoting, field codes, Path and %k.
    child_bus = os.environ.get("DBUS_SESSION_BUS_ADDRESS")
    os.environ["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=" + os.environ.get("TMPDIR", "/tmp") + "/nusadesk-no-session-bus"
    context = bind(GIO, "g_app_launch_context_new", P)()
    if child_bus:
        # GIO resolves Exec in the launcher without bus activation, while the
        # child receives its task-owned dbus-run-session address for GTK teardown.
        bind(GIO, "g_app_launch_context_setenv", None, P, C.c_char_p, C.c_char_p)(
            context, b"DBUS_SESSION_BUS_ADDRESS", child_bus.encode())
    info = new_info(os.fsencode(selected["path"]))
    # StartupWMClass adopts respawned windows into this task's ownership.
    wm_classes = (selected["wm_class"],) if selected["wm_class"] else ()
    pids = []
    callback_type = C.CFUNCTYPE(None, P, C.c_int, P)
    callback = callback_type(lambda unused, pid, data: pids.append(pid))
    launch_fn = bind(GIO, "g_desktop_app_info_launch_uris_as_manager", C.c_int,
                     P, P, P, C.c_int, P, P, callback_type, P, P)
    libc = C.CDLL(None)
    error = P()
    def interrupted(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        # DO_NOT_REAP_CHILD | SEARCH_PATH | CHILD_INHERITS_STDIN. setsid is a C
        # child-setup callback, avoiding Python execution after GLib's fork.
        ok = launch_fn(info, None, context, 2 | 4 | 32, C.cast(libc.setsid, P), None, callback, None, C.byref(error))
        if not ok:
            if error:
                class GError(C.Structure):
                    _fields_ = [("domain", C.c_uint), ("code", C.c_int), ("message", C.c_char_p)]
                message = C.cast(error, C.POINTER(GError)).contents.message.decode("utf-8")
                bind(GLIB, "g_error_free", None, P)(error)
                raise RuntimeError(message)
            raise RuntimeError("GIO could not launch the desktop application")
        if not pids:
            raise RuntimeError("Desktop launch did not provide a supervised process")
        waiting = set(pids)
        deadline = time.monotonic() + 12
        ready = False
        visibility = {}
        # The task is window-supervised: it lives while a supervised process or
        # an owned window exists, so an app that hands off to a respawned
        # process (its pid exits non-zero while its window stays mapped) is not
        # reported as a crash (ADR-0086).
        unmapped = 0
        pending = None
        while True:
            mapped = window_mapped(set(pids), desktop_id, visibility, wm_classes)
            if mapped:
                # This probe follows any exit recorded in a previous iteration:
                # an owned window has survived or returned within the grace.
                pending = None
                if not ready:
                    print("__NUSADESK_GUI_READY__", flush=True)
                    ready = True
            for pid in list(waiting):
                done, status = os.waitpid(pid, os.WNOHANG)
                if done:
                    waiting.remove(pid)
                    code = os.waitstatus_to_exitcode(status)
                    print("__NUSADESK_GUI_EXIT__" + str(code), flush=True)
                    if code:
                        # The window snapshot precedes waitpid and may belong
                        # to this crashed process. Only a subsequent probe can
                        # confirm a handoff and cancel its pending failure.
                        pending = code
            if not ready and time.monotonic() >= deadline:
                raise RuntimeError("Application did not map an X11 window")
            if waiting or mapped:
                unmapped = 0
            else:
                unmapped += 1
                if unmapped >= UNMAPPED_GRACE:
                    if pending is not None:
                        raise RuntimeError("Linux application exited with status " + str(pending))
                    break
            time.sleep(.15)
        if not ready:
            raise RuntimeError("Application exited before mapping a window")
        print("__NUSADESK_GUI_CLOSED__", flush=True)
    finally:
        try:
            close_owned_windows(set(pids), wm_classes)
        except Exception as error:
            # Best-effort sweep: a note keeps it diagnosable in the guest log
            # without masking the launch loop's own outcome.
            print("Window close sweep skipped: " + str(error), file=sys.stderr)
        for pid in pids:
            try:
                os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 2
        remaining = set(pids)
        while remaining and time.monotonic() < deadline:
            for pid in list(remaining):
                try:
                    os.waitpid(pid, os.WNOHANG)
                except ChildProcessError:
                    pass
                try:
                    os.killpg(pid, 0)
                except ProcessLookupError:
                    remaining.remove(pid)
            if remaining:
                time.sleep(.05)
        for pid in remaining:
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        unref(info)
        unref(context)
        if child_bus is None:
            os.environ.pop("DBUS_SESSION_BUS_ADDRESS", None)
        else:
            os.environ["DBUS_SESSION_BUS_ADDRESS"] = child_bus


if __name__ == "__main__":
    try:
        if len(sys.argv) == 2 and sys.argv[1] == "scan":
            print("__NUSADESK_DESKTOPS__" + json.dumps(scan(), ensure_ascii=False), flush=True)
        elif len(sys.argv) == 3 and sys.argv[1] == "launch":
            launch(sys.argv[2])
        else:
            raise ValueError("Usage: desktop_apps.py scan | launch DESKTOP_ID")
    except Exception as error:
        print("__NUSADESK_GUI_FAILED__" + str(error), file=sys.stderr, flush=True)
        sys.exit(1)
