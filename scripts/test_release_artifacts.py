#!/usr/bin/env python3
"""Guard release publication against mixed revisions, missing builds and tampering."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import release_artifacts as release


class ReleaseVerificationTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.info = {'version': '3.7.0', 'buildNumber': '70', 'sourceRevision': 'a' * 40}
        self.patch = patch.object(release, 'identity', return_value=self.info)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        for platform in ('windows-x64', 'macos-arm64'):
            asset = self.root / f'installer-{platform}.bin'
            asset.write_bytes(platform.encode())
            self.metadata(platform, assets={asset.name: release.digest(asset)})

    def metadata(self, platform, **changes):
        file = self.root / f'BUILD-{platform}.json'
        data = json.loads(file.read_text()) if file.exists() else dict(self.info, platform=platform)
        data.update(changes)
        file.write_text(json.dumps(data))

    def test_matching_builds_generate_checksums(self):
        release.verify(self.root, 'v3.7.0')
        self.assertEqual(len((self.root / 'SHA256SUMS').read_text().splitlines()), 4)

    def test_different_revision_is_rejected(self):
        self.metadata('macos-arm64', sourceRevision='b' * 40)
        with self.assertRaisesRegex(ValueError, 'Mixed release identities'):
            release.verify(self.root, 'v3.7.0')

    def test_tampered_installer_is_rejected(self):
        (self.root / 'installer-windows-x64.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            release.verify(self.root, 'v3.7.0')

    def test_missing_platform_is_rejected(self):
        (self.root / 'BUILD-macos-arm64.json').unlink()
        with self.assertRaises(FileNotFoundError):
            release.verify(self.root, 'v3.7.0')

    def test_unexpected_asset_is_rejected(self):
        (self.root / 'private-material.txt').write_text('fixture')
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing'):
            release.verify(self.root, 'v3.7.0')

    def test_parent_path_is_rejected(self):
        self.metadata('macos-arm64', assets={'../escape.bin': '0' * 64})
        with self.assertRaisesRegex(ValueError, 'name mismatch'):
            release.verify(self.root, 'v3.7.0')


if __name__ == '__main__':
    unittest.main()
