import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "main/assets/native-desktop/desktop_apps.py"
spec = importlib.util.spec_from_file_location("desktop_apps", SCRIPT)
apps = importlib.util.module_from_spec(spec)
try:
    spec.loader.exec_module(apps)
except (SystemExit, OSError) as error:
    raise unittest.SkipTest("desktop_apps.py needs the GLib/GIO desktop stack: " + str(error))


class DesktopAppsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.home = self.root / "home"
        self.user = self.home / ".local/share/applications"
        self.system = self.root / "system/applications"
        self.user.mkdir(parents=True)
        self.system.mkdir(parents=True)
        self.env = patch.dict(os.environ, {"HOME": str(self.home), "XDG_DATA_HOME": str(self.user.parent), "XDG_DATA_DIRS": str(self.system.parent)})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def desktop(self, directory, name="sample.desktop", fields=""):
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("[Desktop Entry]\nType=Application\nName=Sample\nExec=/bin/true\n" + fields, encoding="utf-8")
        return path

    def test_user_entry_masks_system_and_hidden_masks_both(self):
        self.desktop(self.system)
        self.desktop(self.user, fields="Name=User override\n")
        self.assertEqual("User override", apps.scan()[0]["name"])
        self.desktop(self.user, fields="Hidden=true\n")
        self.assertEqual([], apps.scan())

    def test_home_usr_and_local_share_and_system_are_scanned(self):
        custom = self.home / "usr/share/applications"
        self.desktop(custom, "custom.desktop")
        self.desktop(self.user, "local.desktop")
        self.desktop(self.system, "nested/system.desktop")
        self.assertEqual({"custom.desktop", "local.desktop", "nested-system.desktop"}, {x["id"] for x in apps.scan()})

    def test_one_malformed_entry_does_not_empty_the_catalog(self):
        import contextlib, io
        self.desktop(self.system, "good.desktop")
        self.desktop(self.system, "bad.desktop")
        real_entry = apps.entry
        def malformed(desktop_id, path):
            if desktop_id == "bad.desktop":
                raise UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid Name")
            return real_entry(desktop_id, path)
        errors = io.StringIO()
        with patch.object(apps, "entry", side_effect=malformed), contextlib.redirect_stderr(errors):
            self.assertEqual(["good.desktop"], [x["id"] for x in apps.scan()])
        self.assertIn("bad.desktop", errors.getvalue())

    def test_one_unreadable_applications_root_does_not_empty_the_catalog(self):
        self.desktop(self.system, "good.desktop")
        denied = unittest.mock.Mock()
        denied.is_dir.return_value = True
        denied.rglob.side_effect = OSError("permission denied")
        with patch.object(apps, "roots", return_value=[denied, self.system]):
            self.assertEqual(["good.desktop"], [x["id"] for x in apps.scan()])

    def test_icon_name_with_separator_or_traversal_is_rejected(self):
        self.assertEqual("", apps.icon_path("../../outside"))
        self.assertEqual("", apps.icon_path("subdir/icon"))
        self.assertEqual("", apps.icon_path("definitely-missing-icon"))
        icon = self.root / "icon.png"
        icon.write_bytes(b"png")
        self.assertEqual(str(icon), apps.icon_path(str(icon)))

    def test_desktop_visibility_and_tryexec_use_glib(self):
        self.desktop(self.system, "terminal.desktop", "Terminal=true\n")
        self.desktop(self.system, "nodisplay.desktop", "NoDisplay=true\n")
        self.desktop(self.system, "missing.desktop", "TryExec=/does-not-exist\n")
        self.desktop(self.system, "xfce.desktop", "OnlyShowIn=XFCE;\n")
        self.desktop(self.system, "valid.desktop")
        self.assertEqual(["valid.desktop"], [x["id"] for x in apps.scan()])

    def test_glib_expands_quoted_exec_fields_and_owns_real_child(self):
        output = self.root / "args.json"
        fields = f'''Exec=/usr/bin/python3 -c "import json,sys;open('{output}','w').write(json.dumps(sys.argv[1:]))" %c %k %% %U
DBusActivatable=true
'''
        desktop = self.desktop(self.system, "org.example.NativeQa.desktop", fields)
        with patch.object(apps, "window_mapped", return_value=True):
            apps.launch(desktop.name)
        self.assertEqual(["Sample", str(desktop), "%"], json.loads(output.read_text()))

    def test_mapped_parentless_dialog_requests_fit_but_owned_dialog_and_menu_do_not(self):
        import ctypes as C
        atoms = {name: number for number, name in enumerate([
            "_NET_CLIENT_LIST", "_NET_CLIENT_LIST_STACKING", "_NET_WM_WINDOW_TYPE", "WM_TRANSIENT_FOR",
            "_NET_WM_WINDOW_TYPE_DIALOG", "_NET_WM_WINDOW_TYPE_POPUP_MENU", "_NET_ACTIVE_WINDOW"], 1)}
        dialog = atoms["_NET_WM_WINDOW_TYPE_DIALOG"]
        values = {(100, atoms["_NET_CLIENT_LIST"]): [10, 20, 30],
                  (10, atoms["_NET_WM_WINDOW_TYPE"]): [dialog],
                  (20, atoms["_NET_WM_WINDOW_TYPE"]): [dialog],
                  (20, atoms["WM_TRANSIENT_FOR"]): [10],
                  (30, atoms["_NET_WM_WINDOW_TYPE"]): [atoms["_NET_WM_WINDOW_TYPE_POPUP_MENU"]]}
        allocated = []
        def get_property(display, window, atom, offset, length, delete, kind, actual, fmt, count, remaining, data):
            row = values.get((window, atom), [])
            array = (C.c_ulong * len(row))(*row)
            allocated.append(array)
            C.cast(fmt, C.POINTER(C.c_int))[0] = 32
            C.cast(count, C.POINTER(C.c_ulong))[0] = len(row)
            C.cast(data, C.POINTER(C.c_void_p))[0] = C.cast(array, C.c_void_p)
            return 0
        mapped, iconified = [], []
        functions = {"XMapRaised": lambda display, window: mapped.append(window) or 1,
                     "XIconifyWindow": lambda display, window, screen: iconified.append(window) or 1,
                     "XOpenDisplay": lambda unused: 1, "XDefaultRootWindow": lambda display: 100,
                     "XInternAtom": lambda display, name, unused: atoms[name.decode()],
                     "XGetWindowProperty": get_property, "XFree": lambda data: 0,
                     "XSync": lambda display, discard: 0, "XCloseDisplay": lambda display: 0,
                     "XSetErrorHandler": lambda callback: None}
        with patch.object(apps.C, "CDLL", return_value=object()), \
             patch.object(apps, "bind", side_effect=lambda lib, name, *args: functions[name]), \
             patch.object(apps, "fit_primary_dialog") as fit:
            self.assertTrue(apps.window_mapped())
            self.assertEqual(1, fit.call_count)
            self.assertEqual(10, fit.call_args.args[3])
            with patch.object(apps, "owned_windows", return_value=[]):
                self.assertFalse(apps.window_mapped({500}, "editor.desktop", {}))
                self.assertEqual(1, fit.call_count)  # another task's window cannot report readiness.
            visibility = {}
            with patch.object(apps, "owned_windows", return_value=[10]), \
                 patch.object(apps, "foreground_request", return_value={"id": "settings.desktop", "token": "1"}):
                self.assertTrue(apps.window_mapped({500}, "editor.desktop", visibility))
                self.assertEqual([10], iconified)
                self.assertEqual([], mapped)
            with patch.object(apps, "owned_windows", return_value=[10]), \
                 patch.object(apps, "foreground_request", return_value={"id": "editor.desktop", "token": "2"}), \
                 patch.object(apps, "send_message") as activate:
                self.assertTrue(apps.window_mapped({500}, "editor.desktop", visibility))
                self.assertEqual([10], mapped)
                self.assertEqual(10, activate.call_args.args[3])

    def test_tasks_own_only_their_process_group_and_transitive_dialogs(self):
        properties = {(10, "_NET_WM_PID"): [101], (20, "_NET_WM_PID"): [202],
                      (30, "WM_TRANSIENT_FOR"): [10], (40, "WM_TRANSIENT_FOR"): [30]}
        read = lambda window, name: properties.get((window, name), [])
        self.assertEqual([10, 30, 40], apps.owned_windows([10, 20, 30, 40], read, {100}, lambda pid: pid - 1))
        self.assertEqual([20], apps.owned_windows([10, 20, 30, 40], read, {201}, lambda pid: pid - 1))
        self.assertEqual([], apps.owned_windows([20], read, {100}, lambda pid: (_ for _ in ()).throw(ProcessLookupError())))
        self.assertEqual([], apps.owned_windows([20], read, {100}, lambda pid: (_ for _ in ()).throw(PermissionError(13, "denied"))))

    def test_only_parentless_dialogs_are_promoted_to_kiosk_roots(self):
        dialog, normal, menu = 10, 11, 12
        self.assertTrue(apps.primary_dialog([dialog], [], dialog))
        self.assertFalse(apps.primary_dialog([dialog], [123], dialog))
        self.assertFalse(apps.primary_dialog([dialog], [0], dialog))
        self.assertFalse(apps.primary_dialog([normal], [], dialog))
        self.assertFalse(apps.primary_dialog([menu], [], dialog))
        self.assertFalse(apps.primary_dialog([], [], dialog))

    def test_nonzero_app_exit_is_reported_even_after_a_window_was_ready(self):
        self.desktop(self.system, "crash.desktop", "Exec=/bin/sh -c \"exit 23\"\n")
        with patch.object(apps, "window_mapped", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "status 23"):
                apps.launch("crash.desktop")

    def test_removed_or_hidden_app_cannot_launch_from_stale_catalog(self):
        self.desktop(self.user, fields="Hidden=true\n")
        with self.assertRaisesRegex(RuntimeError, "no longer installed"):
            apps.launch("sample.desktop")

    def test_successful_child_completion_has_an_explicit_closed_marker(self):
        import contextlib, io
        self.desktop(self.system, "normal.desktop", "Exec=/bin/true\n")
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(apps, "window_mapped", return_value=True):
            apps.launch("normal.desktop")
        self.assertIn("__NUSADESK_GUI_CLOSED__", output.getvalue())
        self.assertNotIn("__NUSADESK_GUI_FAILED__", output.getvalue())

    def test_missing_gio_has_a_failure_marker_instead_of_an_unclassified_traceback(self):
        import contextlib, io, runpy
        errors = io.StringIO()
        with patch.object(apps.C, "CDLL", side_effect=OSError("libgio unavailable")), contextlib.redirect_stderr(errors):
            with self.assertRaises(SystemExit) as stopped:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(1, stopped.exception.code)
        self.assertIn("__NUSADESK_GUI_FAILED__Desktop library unavailable", errors.getvalue())
        self.assertIn("libgio unavailable", errors.getvalue())

    def test_missing_gio_symbol_has_a_failure_marker_instead_of_an_unclassified_traceback(self):
        import contextlib, io, runpy
        errors = io.StringIO()
        with patch.object(apps.C, "CDLL", return_value=object()), contextlib.redirect_stderr(errors):
            with self.assertRaises(SystemExit) as stopped:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(1, stopped.exception.code)
        self.assertIn("__NUSADESK_GUI_FAILED__Desktop library unavailable", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
