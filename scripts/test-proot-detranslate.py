#!/usr/bin/env python3
"""Compile real PRoot path functions with deterministic binding/procfs fixtures.

Usage: test-proot-detranslate.py /path/to/pinned/proot-source
No downloaded source is copied into the repository. Assertions stay enabled.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]) / 'src/path/path.c'
text = source.read_text()
functions = []
for signature in ['int detranslate_path(', 'bool belongs_to_guestfs(',
                  'Comparison compare_paths2(', 'Comparison compare_paths(']:
    start = text.index(signature)
    end = text.index('\n}', start) + 2
    functions.append(text[start:end])
with tempfile.TemporaryDirectory(prefix='proot-path-test-') as directory:
    root = Path(directory)
    (root / 'path-functions.inc').write_text('\n\n'.join(functions))
    fixture = Path(__file__).resolve().parent / 'tests/proot-detranslate.c'
    binary = root / 'path-test'
    subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-UNDEBUG',
                    '-I', str(root), str(fixture), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
