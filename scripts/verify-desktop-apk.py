"""Reject a regular APK that would remove the native desktop on update."""
import sys
import zipfile


def verify(path):
    with zipfile.ZipFile(path) as apk:
        for name in (
            'lib/arm64-v8a/libXlorie.so',
            'lib/arm64-v8a/libvirglserver.so',
            'assets/licenses/virglrenderer/virglrenderer-MIT.txt',
            'assets/licenses/virglrenderer/libepoxy-MIT.txt',
            'assets/licenses/native-display/Termux-X11-GPL-3.0.txt',
            'assets/licenses/native-display/submodules.lock',
        ):
            if name not in apk.namelist() or apk.getinfo(name).file_size == 0:
                raise ValueError('Desktop APK is missing ' + name)


if __name__ == '__main__':
    verify(sys.argv[1])
    print('Desktop binary and native license materials present: ' + sys.argv[1])
