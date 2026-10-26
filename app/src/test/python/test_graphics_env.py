"""Execute the actual guest renderer selection script with isolated sockets/tools."""
import os
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
SCRIPT = ROOT / 'app/src/main/assets/native-desktop/graphics-env.sh'


class GraphicsEnvironmentTest(unittest.TestCase):
    def launch_electron(self, driver):
        with tempfile.TemporaryDirectory(prefix='electron graphics ') as directory:
            root = Path(directory)
            env_script = root / 'env.sh'
            env_script.write_text('export GALLIUM_DRIVER=' + driver + '\n')
            wrapper = (SCRIPT.parent / 'gpu-electron.sh').read_text().replace(
                '. /opt/lw-desktop/graphics-env.sh', '. "' + str(env_script) + '"')
            launcher = root / 'launch.sh'
            launcher.write_text(wrapper)
            code = 'import os,json,sys; print(json.dumps([sys.argv[1:], os.environ.get("MESA_GLES_VERSION_OVERRIDE")]))'
            result = subprocess.run(['/bin/sh', str(launcher), '/usr/bin/python3', '-c', code,
                                     'an argument with spaces', "a'b"],
                                    env=dict(os.environ, MESA_GLES_VERSION_OVERRIDE='stale'),
                                    text=True, capture_output=True, check=True)
            return json.loads(result.stdout)

    def test_electron_compatibility_is_explicit_and_preserves_arguments(self):
        arguments, version = self.launch_electron('virpipe')
        self.assertEqual(['an argument with spaces', "a'b", '--use-gl=angle',
                          '--use-angle=gles', '--ignore-gpu-blocklist'], arguments)
        self.assertEqual('3.0', version)

    def test_electron_fallback_has_no_gpu_flags_or_stale_overrides(self):
        arguments, version = self.launch_electron('llvmpipe')
        self.assertEqual(['an argument with spaces', "a'b"], arguments)
        self.assertIsNone(version)

    def select(self, response=None, status=0, connected=True):
        with tempfile.TemporaryDirectory(prefix='graphics-') as directory:
            root = Path(directory)
            with socket.socket(socket.AF_UNIX) as server:
                if connected:
                    server.bind(str(root / 'vtest'))
                probe = root / 'python3'
                probe.write_text('#!/bin/sh\nprintf "%s\\n" ' + repr(response or '')
                                 + '\nexit ' + str(status) + '\n')
                probe.chmod(0o700)
                script = SCRIPT.read_text().replace('/tmp/.virgl', str(root / 'vtest'))
                script = script.replace('/tmp/nusadesk-graphics.log', str(root / 'probe.log'))
                env = dict(os.environ, PATH=str(root) + ':' + os.environ['PATH'],
                           GALLIUM_DRIVER='virpipe', VTEST_SOCKET_NAME='stale')
                result = subprocess.run(['/bin/sh', '-eu', '-c', script + '\n'
                                         'printf "%s:%s:%s" "$LIBGL_ALWAYS_SOFTWARE" '
                                         '"$GALLIUM_DRIVER" "${VTEST_SOCKET_NAME:-}"'],
                                        env=env, text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                return result.stdout.replace(str(root / 'vtest'), 'socket')

    def test_verified_virgl_is_selected(self):
        self.assertEqual('1:virpipe:socket', self.select('{"renderer": "virgl (Mali-T880)", "verified": true}'))

    def test_cpu_renderer_cannot_be_mistaken_for_gpu(self):
        self.assertEqual('1:llvmpipe:', self.select('{"renderer": "llvmpipe"}'))

    def test_failed_probe_falls_back_even_if_it_printed_virgl(self):
        self.assertEqual('1:llvmpipe:', self.select('{"verified": true}', status=1))

    def test_missing_server_clears_stale_gpu_environment(self):
        self.assertEqual('1:llvmpipe:', self.select(connected=False))


if __name__ == '__main__':
    unittest.main()
