"""Release package checks exercise ZIP contents and actual Git source exports."""
import importlib.util
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[4] / 'scripts'


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DesktopReleaseTest(unittest.TestCase):
    def test_standard_apk_cannot_omit_native_display_or_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            required = ['lib/arm64-v8a/libXlorie.so',
                        'lib/arm64-v8a/libvirglserver.so',
                        'assets/licenses/virglrenderer/virglrenderer-MIT.txt',
                        'assets/licenses/virglrenderer/libepoxy-MIT.txt',
                        'assets/licenses/native-display/Termux-X11-GPL-3.0.txt',
                        'assets/licenses/native-display/submodules.lock']
            for missing in required + [None]:
                with zipfile.ZipFile(apk, 'w') as archive:
                    for name in required:
                        if name != missing:
                            archive.writestr(name, b'fixture')
                if missing:
                    with self.assertRaises(ValueError):
                        load('verify-desktop-apk').verify(apk)
                else:
                    load('verify-desktop-apk').verify(apk)

    def test_source_export_keeps_patched_files_and_excludes_local_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('host', 'native'):
                repo = root / name
                repo.mkdir()
                subprocess.run(['git', 'init', '-q', str(repo)], check=True)
                (repo / 'source.c').write_text('original')
                subprocess.run(['git', '-C', str(repo), 'add', 'source.c'], check=True)
                (repo / 'source.c').write_text('patched')
                (repo / 'untracked-secret').write_text('local fixture')
            dependency = root / 'dependency'
            dependency.mkdir()
            subprocess.run(['git', 'init', '-q', str(dependency)], check=True)
            (dependency / 'COPYING').write_text('dependency license')
            subprocess.run(['git', '-C', str(dependency), 'add', 'COPYING'], check=True)
            subprocess.run(['git', '-C', str(dependency), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.test', 'commit', '-qm', 'fixture'], check=True)
            subprocess.run(['git', '-C', str(root / 'native'), '-c', 'protocol.file.allow=always',
                            'submodule', 'add', '-q', str(dependency), 'dependency'], check=True)
            output = root / 'source.tar.gz'
            load('package-desktop-source').package(output, root / 'host', root / 'native')
            with tarfile.open(output) as archive:
                self.assertEqual(['nusadesk/source.c', 'termux-x11/.gitmodules',
                                  'termux-x11/dependency/COPYING', 'termux-x11/source.c'],
                                 archive.getnames())
                self.assertEqual(b'patched', archive.extractfile('termux-x11/source.c').read())
