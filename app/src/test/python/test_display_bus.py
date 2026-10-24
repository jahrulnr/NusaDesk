"""Exercise the shipped wrapper with real D-Bus processes, no guest or network."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
import uuid

WRAPPER = Path(__file__).resolve().parents[2] / "main/assets/native-desktop/session-bus.sh"


@unittest.skipUnless(Path("/usr/bin/dbus-daemon").exists() and shutil.which("dbus-run-session"), "host D-Bus required")
class DisplayBusTest(unittest.TestCase):
    def test_wrapper_uses_owned_abstract_address_and_releases_it_on_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            wrapper = Path(directory) / "session-bus.sh"
            shutil.copyfile(WRAPPER, wrapper)
            wrapper.chmod(0o700)
            address = "unix:abstract=nusadesk-display-" + str(uuid.uuid4())
            env = dict(os.environ, NUSADESK_DISPLAY_BUS=address)
            command = ["dbus-run-session", "--dbus-daemon=" + str(wrapper), "--", "/bin/sh", "-c",
                       'printf "%s\\n" "$DBUS_SESSION_BUS_ADDRESS"; read answer']
            process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                reported = process.stdout.readline().strip()
                self.assertEqual(address, reported.split(",guid=")[0])
                self.assertIsNone(process.poll())
                process.communicate("done\n", timeout=5)
                self.assertEqual(0, process.returncode)
                # Rebinding the exact name establishes that the prior bus released it.
                again = subprocess.run(["dbus-run-session", "--dbus-daemon=" + str(wrapper), "--", "/bin/true"],
                                       env=env, capture_output=True, timeout=5)
                self.assertEqual(0, again.returncode, again.stderr)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=5)
