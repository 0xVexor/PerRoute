"""Negative packaging checks use disposable synthetic bundles; no host settings are touched."""
import json
from pathlib import Path
import plistlib
import tempfile
import unittest

from macos_dmg import digest, instructions, maintenance_instructions, validate


class DmgValidationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.app = Path(self.temporary.name) / 'PerRoute.app'
        self.contents = self.app / 'Contents'
        self.contents.mkdir(parents=True)
        self.info = {'CFBundleIdentifier': 'app.perroute.spike', 'CFBundleExecutable': 'PerRoute',
                     'CFBundleShortVersionString': '3.6.0', 'LSMinimumSystemVersion': '13.0'}
        self.write_info()
        binaries = ['MacOS/PerRoute', 'MacOS/PerRouteHelperInstaller', 'MacOS/PerRouteCoreSupervisor',
                    'Library/HelperTools/PerRouteProxyHelper', 'Library/Engines/sing-box',
                    'Frameworks/libPerRouteProxyBridge.dylib', 'Frameworks/libPerRouteDesktopBridge.dylib',
                    'runtime/Contents/Home/lib/server/libjvm.dylib', 'app/resources/xray/macos-arm64/xray']
        for name in binaries:
            path = self.contents / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'\xcf\xfa\xed\xfeSyntheticBinary')
            path.chmod(0o755)
        for name in ['Resources/PerRoute.icns', 'app/resources/LICENSE', 'app/resources/THIRD_PARTY_NOTICES.md',
                     'Library/Engines/sing-box.LICENSE', 'Library/Engines/GPL-3.0.txt']:
            path = self.contents / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        (self.contents / 'Library/Engines/sing-box.provenance.json').write_text(json.dumps({'architecture': 'arm64'}))

    def write_info(self):
        (self.contents / 'Info.plist').write_bytes(plistlib.dumps(self.info))

    def validate(self, inspect=lambda path: {'arm64'}):
        return validate(self.app, 'arm64', inspect)

    def test_complete_bundle_and_checksum(self):
        self.assertEqual(self.validate()[0], '3.6.0')
        self.assertEqual(len(digest(self.contents / 'Info.plist')), 64)

    def test_missing_helper_is_rejected(self):
        (self.contents / 'Library/HelperTools/PerRouteProxyHelper').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing or aliased'): self.validate()

    def test_private_signing_material_is_rejected(self):
        (self.contents / 'identity.p12').write_text('not-a-key')
        with self.assertRaisesRegex(ValueError, 'Private signing material'): self.validate()

    def test_wrong_cpu_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Wrong native architecture'):
            self.validate(lambda path: {'x86_64'})

    def test_symlink_escape_is_rejected(self):
        (self.contents / 'external').symlink_to('/Applications')
        with self.assertRaisesRegex(ValueError, 'symlink escapes'): self.validate()

    def test_advertised_old_os_is_rejected(self):
        self.info['LSMinimumSystemVersion'] = '12.0'
        self.write_info()
        with self.assertRaisesRegex(ValueError, 'minimum macOS'): self.validate()

    def test_mixed_helper_registration_is_rejected(self):
        folder = self.contents / 'Library/LaunchDaemons'
        folder.mkdir()
        (folder / 'app.perroute.proxy-helper.plist').write_text('old-service')
        with self.assertRaisesRegex(ValueError, 'SMAppService'): self.validate()

    def test_mismatched_provenance_is_rejected(self):
        (self.contents / 'Library/Engines/sing-box.provenance.json').write_text('{"architecture":"x86_64"}')
        with self.assertRaisesRegex(ValueError, 'provenance'): self.validate()

    def test_non_executable_installer_is_rejected(self):
        (self.contents / 'MacOS/PerRouteHelperInstaller').chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'not executable'): self.validate()

    def test_instructions_do_not_claim_notarization_or_browser_install(self):
        guide = instructions('3.6.0', 'arm64')
        self.assertLess(len(guide.split()), 120)
        self.assertIn('Do not open PerRoute directly from this disk', guide)
        text = maintenance_instructions('3.6.0', 'arm64')
        self.assertIn('NOT Apple Developer ID signed or notarized', text)
        self.assertIn('NOT installed by this DMG', text)
        self.assertIn('--proxy-helper-unregister-interactive', text)


if __name__ == '__main__':
    unittest.main()
