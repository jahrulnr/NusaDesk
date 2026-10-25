"""Release metadata automation against disposable repository fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[4] / 'scripts/bump-version.py'


class BumpVersionTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('bump_version', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'docs/changelogs').mkdir(parents=True)
        self.write('VERSION', '0.14.5\n')
        self.write('SECURITY.md', 'The current version is `0.14.5`.\n')
        self.write('CHANGELOG.md', '# Changelog\n\nThe current line (`0.14.x`) stays here.\n\n'
                   '## [Unreleased]\n\n### Fixed\n\n- Keep this exact note.\n\n'
                   '## [0.14.5] - 2026-10-26\n\n- Existing release.\n\n'
                   '## Older releases\n\n- [0.13.x](docs/changelogs/0.13.md)\n')

    def write(self, name, text):
        (self.root / name).write_text(text, encoding='utf-8')

    def read(self, name):
        return (self.root / name).read_text(encoding='utf-8')

    def bump(self, version=None):
        return self.module.bump(self.root, version, '2026-10-27')

    def snapshot(self):
        return {p.relative_to(self.root): p.read_bytes()
                for p in self.root.rglob('*') if p.is_file()}

    def test_plain_make_keeps_validation_as_default_instead_of_bumping(self):
        result = subprocess.run(['make', '--no-print-directory', '-n', '-f',
                                 str(SCRIPT.parent.parent / 'Makefile')],
                                cwd=self.root, capture_output=True, text=True, check=True)
        self.assertIn('scripts/bump-version.py --check', result.stdout.splitlines()[0])
        self.assertNotIn('scripts/bump-version.py ""', result.stdout)

    def test_default_patch_moves_unreleased_and_preserves_history(self):
        self.bump()
        self.assertEqual('0.14.6\n', self.read('VERSION'))
        self.assertIn('`0.14.6`', self.read('SECURITY.md'))
        self.assertIn('## [Unreleased]\n\n## [0.14.6] - 2026-10-27\n\n### Fixed', self.read('CHANGELOG.md'))
        self.assertEqual(1, self.read('CHANGELOG.md').count('- Keep this exact note.'))
        self.assertIn('## [0.14.5] - 2026-10-26\n\n- Existing release.', self.read('CHANGELOG.md'))
        self.module.check(self.root)

    def test_same_version_repairs_security_without_consuming_notes_or_redating(self):
        changelog = self.read('CHANGELOG.md')
        self.write('SECURITY.md', 'The current version is `0.14.4`.\n')
        with self.assertRaises(ValueError):
            self.module.check(self.root)
        self.bump('0.14.5')
        before = self.snapshot()
        self.bump('0.14.5')
        self.assertEqual(before, self.snapshot())
        self.assertEqual(changelog, self.read('CHANGELOG.md'))
        self.module.check(self.root)

    def test_minor_rollover_archives_old_line_and_preserves_older_index(self):
        self.bump('0.15.0')
        changelog = self.read('CHANGELOG.md')
        self.assertIn('`0.15.x`', changelog)
        self.assertIn('## [0.15.0]', changelog)
        self.assertNotIn('## [0.14.5]', changelog)
        self.assertIn('[0.14.x](docs/changelogs/0.14.md)', changelog)
        self.assertIn('## [0.14.5] - 2026-10-26\n\n- Existing release.', self.read('docs/changelogs/0.14.md'))
        self.module.check(self.root)

    def test_invalid_or_older_versions_leave_every_file_unchanged(self):
        for version in ['0.14.4', '0.14.1000', 'v0.14.6', '00.14.6', '1.2', '1.2.3-beta']:
            with self.subTest(version=version):
                before = self.snapshot()
                with self.assertRaises(ValueError):
                    self.bump(version)
                self.assertEqual(before, self.snapshot())

    def test_missing_marker_or_duplicate_release_does_not_partially_write(self):
        for name, text in [('SECURITY.md', 'No version sentence.\n'),
                           ('CHANGELOG.md', self.read('CHANGELOG.md') + '\n## [0.14.5] - 2026-10-26\n')]:
            with self.subTest(name=name):
                self.write(name, text)
                before = self.snapshot()
                with self.assertRaises(ValueError):
                    self.bump()
                self.assertEqual(before, self.snapshot())
                self.write('SECURITY.md', 'The current version is `0.14.5`.\n')

    def test_archive_collision_preserves_original_files(self):
        self.write('docs/changelogs/0.14.md', 'Do not replace existing history.\n')
        before = self.snapshot()
        with self.assertRaises(ValueError):
            self.bump('0.15.0')
        self.assertEqual(before, self.snapshot())

    def test_write_failure_rolls_back_changes_including_new_archive(self):
        original = self.module.atomic_write
        def fail_version(path, text):
            if path.name == 'VERSION' and text == '0.15.0\n':
                raise OSError('simulated write failure')
            original(path, text)
        before = self.snapshot()
        with patch.object(self.module, 'atomic_write', side_effect=fail_version):
            with self.assertRaises(OSError):
                self.bump('0.15.0')
        self.assertEqual(before, self.snapshot())
