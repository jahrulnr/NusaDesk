"""Export tracked host and patched recursive X11 sources beside the release APK.

Use working-tree bytes so the applied NusaDesk patch is included. Git's tracked
file list excludes build products, caches and local signing material.
"""
import argparse
from pathlib import Path
import subprocess
import tarfile


def package(output, host, native):
    with tarfile.open(output, 'w:gz') as archive:
        for prefix, root in (('nusadesk', host), ('termux-x11', native)):
            names = subprocess.check_output(
                ['git', '-C', str(root), 'ls-files', '-z', '--recurse-submodules'])
            for name in names.decode().split('\0'):
                if name:
                    archive.add(root / name, arcname=prefix + '/' + name, recursive=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('native_source', type=Path)
    args = parser.parse_args()
    package(args.output, Path(__file__).resolve().parents[1], args.native_source)
