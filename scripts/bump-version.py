#!/usr/bin/env python3
"""Prepare release metadata without committing, tagging, building, or publishing."""
import argparse
from datetime import date
from pathlib import Path
import os
import re
import stat
import tempfile

VERSION = re.compile(r'(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\Z')
RELEASE = re.compile(r'^## \[([0-9]+\.[0-9]+\.[0-9]+)\](?: - [^\n]+)?$', re.MULTILINE)
SECURITY = re.compile(r'(The current version is `)([0-9]+\.[0-9]+\.[0-9]+)(`)')


def version_tuple(value):
    if not VERSION.fullmatch(value):
        raise ValueError(f'Invalid version {value!r}: use major.minor.patch, each field 0..999')
    return tuple(map(int, value.split('.')))


def metadata(root):
    marker = (root / 'VERSION').read_text(encoding='utf-8').strip()
    version_tuple(marker)
    changelog = (root / 'CHANGELOG.md').read_text(encoding='utf-8')
    security = (root / 'SECURITY.md').read_text(encoding='utf-8')
    unreleased = list(re.finditer(r'^## \[Unreleased\]$', changelog, re.MULTILINE))
    releases = list(RELEASE.finditer(changelog))
    if len(unreleased) != 1 or not releases or unreleased[0].start() >= releases[0].start():
        raise ValueError('CHANGELOG.md needs one [Unreleased] section before released sections')
    labels = [m[1] for m in releases]
    if len(set(labels)) != len(labels):
        raise ValueError('CHANGELOG.md contains duplicate release versions')
    if len(list(SECURITY.finditer(security))) != 1:
        raise ValueError('SECURITY.md needs one "The current version is `x.y.z`" marker')
    return marker, changelog, security, unreleased[0], releases


def check(root):
    """Fail early if generated APK metadata and current documentation disagree."""
    marker, _, security, _, releases = metadata(root)
    if releases[0][1] != marker or SECURITY.search(security)[2] != marker:
        raise ValueError(f'Release metadata differs from VERSION={marker}; run '
                         f'make bump-version NEW_VERSION={marker}')
    return marker


def atomic_write(path, text):
    """Replace one file while preserving permissions and cleaning temp files."""
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def bump(root, requested=None, release_date=None):
    """Validate the entire update first; write VERSION last and roll back on I/O failure."""
    root = Path(root)
    current, changelog, security, unreleased, releases = metadata(root)
    old = version_tuple(current)
    target = requested or f'{old[0]}.{old[1]}.{old[2] + 1}'
    new = version_tuple(target)
    if new < old or new < version_tuple(releases[0][1]):
        raise ValueError('A release version must not go backwards')
    updates = {}
    if target != releases[0][1]:
        if any(m[1] == target for m in releases):
            raise ValueError(f'CHANGELOG.md already contains {target}')
        pending = changelog[unreleased.end():releases[0].start()].strip('\n')
        history = changelog[releases[0].start():]
        prefix = changelog[:unreleased.end()]
        previous_line = '.'.join(releases[0][1].split('.')[:2])
        next_line = '.'.join(target.split('.')[:2])
        if previous_line != next_line:
            index = history.find('## Older releases\n')
            if index < 0:
                raise ValueError('CHANGELOG.md needs an Older releases index for a minor/major rollover')
            archive = root / f'docs/changelogs/{previous_line}.md'
            if archive.exists():
                raise ValueError(f'Archive already exists: {archive.relative_to(root)}')
            if not archive.parent.is_dir():
                raise ValueError('Missing docs/changelogs directory')
            updates[archive] = (f'# Changelog — {previous_line}.x\n\n'
                                f'Older release notes for the {previous_line} line. Current notes live in '
                                '[`CHANGELOG.md`](../../CHANGELOG.md).\n\n' + history[:index].strip('\n') + '\n')
            versions = ', '.join(m[1] for m in releases)
            history = history[index:].replace('## Older releases\n',
                f'## Older releases\n\n- [{previous_line}.x](docs/changelogs/{previous_line}.md) — {versions}', 1)
            prefix = prefix.replace(f'`{previous_line}.x`', f'`{next_line}.x`')
        stamp = release_date or date.today().isoformat()
        date.fromisoformat(stamp)
        notes = f'{pending}\n\n' if pending else ''
        changelog = prefix + f'\n\n## [{target}] - {stamp}\n\n' + notes + history
    updates[root / 'CHANGELOG.md'] = changelog
    updates[root / 'SECURITY.md'] = SECURITY.sub(lambda m: m[1] + target + m[3], security)
    updates[root / 'VERSION'] = target + '\n'
    # Preserve original contents so an ordinary write error cannot leave only
    # half a bump applied. A hard process/power loss is caught by check-version.
    originals = {path: path.read_text(encoding='utf-8') if path.exists() else None for path in updates}
    written = []
    try:
        for path, text in updates.items():
            if originals[path] != text:
                atomic_write(path, text)
                written.append(path)
    except OSError:
        for path in reversed(written):
            if originals[path] is None:
                path.unlink()
            else:
                atomic_write(path, originals[path])
        raise
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', nargs='?', default='', help='explicit version; default: next patch')
    parser.add_argument('--check', action='store_true', help='validate current metadata without writing')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        if args.check:
            print('Release metadata consistent: ' + check(root))
        else:
            print('Release metadata prepared: ' + bump(root, args.version))
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
