"""Exercise the verifier against real Git submodule revisions, without network."""
from pathlib import Path
import subprocess
import tempfile
import unittest

VERIFY = Path(__file__).resolve().parents[4] / "scripts/verify-x11-submodules.sh"

class SubmoduleLockTest(unittest.TestCase):
    def test_changed_revision_and_uninitialized_checkout_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            child, parent = base / "child", base / "parent"
            def git(path, *args):
                return subprocess.check_output(["git", "-c", "user.name=QA", "-c", "user.email=qa@example.invalid", "-c", "protocol.file.allow=always", "-C", str(path), *args], stderr=subprocess.DEVNULL, text=True).strip()
            child.mkdir(); parent.mkdir()
            for repo in (child, parent): git(repo, "init", "-q")
            (child / "source").write_text("one")
            git(child, "add", "."); git(child, "commit", "-qm", "one")
            git(parent, "submodule", "add", "-q", str(child), "dependency")
            lock = base / "lock"
            lock.write_text(git(parent, "submodule", "status", "--recursive") + "\n")
            def verify():
                return subprocess.run(["bash", str(VERIFY), str(parent), str(lock)], capture_output=True).returncode
            self.assertEqual(0, verify())
            checkout = parent / "dependency"
            (checkout / "source").write_text("two")
            git(checkout, "add", "."); git(checkout, "commit", "-qm", "two")
            self.assertNotEqual(0, verify())
            git(parent, "submodule", "deinit", "-f", "dependency")
            self.assertNotEqual(0, verify())
