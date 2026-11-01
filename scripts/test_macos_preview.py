import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from macos_preview import validate


class PreviewTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'PerRoute.app'
        self.contents = self.root / 'Contents'
        self.info = {'CFBundleIdentifier': 'app.perroute.spike', 'CFBundleExecutable': 'PerRoute',
                     'CFBundleShortVersionString': '3.0.0', 'LSMinimumSystemVersion': '13.0'}
        native = ['MacOS/PerRoute', 'runtime/Contents/Home/lib/server/libjvm.dylib', 'app/resources/xray/macos-arm64/xray',
                  'Library/HelperTools/PerRouteProxyHelper', 'Frameworks/libPerRouteProxyBridge.dylib',
                  'Frameworks/libPerRouteDesktopBridge.dylib', 'MacOS/PerRouteCoreSupervisor']
        for name in native:
            target = self.contents / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'\xcf\xfa\xed\xfeFixture')
            target.chmod(0o755)
        for name in ['Library/LaunchDaemons/app.perroute.proxy-helper.plist', 'Resources/PerRoute.icns',
                     'app/resources/LICENSE', 'app/resources/THIRD_PARTY_NOTICES.md']:
            target = self.contents / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'fixture')
        self.write_info()

    def write_info(self):
        (self.contents / 'Info.plist').write_bytes(plistlib.dumps(self.info))

    def check_bundle(self):
        return validate(self.root, 'arm64', lambda path: {'arm64'})

    def test_complete_bundle(self):
        version, binaries = self.check_bundle()
        self.assertEqual(version, '3.0.0')
        self.assertEqual(len(binaries), 7)

    def test_identity_and_version(self):
        for key, value in [('CFBundleIdentifier', 'other'), ('CFBundleExecutable', 'other'),
                           ('CFBundleShortVersionString', '../bad'), ('LSMinimumSystemVersion', '12.0')]:
            with self.subTest(key=key):
                original = self.info[key]
                self.info[key] = value
                self.write_info()
                with self.assertRaises(ValueError): self.check_bundle()
                self.info[key] = original
        self.write_info()

    def test_missing_required_components(self):
        for name in ['Resources/PerRoute.icns', 'Frameworks/libPerRouteDesktopBridge.dylib', 'app/resources/LICENSE', 'MacOS/PerRouteCoreSupervisor']:
            target = self.contents / name
            saved = target.read_bytes()
            target.unlink()
            with self.subTest(component=name), self.assertRaises(ValueError): self.check_bundle()
            target.write_bytes(saved)

    def test_wrong_architecture(self):
        with self.assertRaises(ValueError): validate(self.root, 'arm64', lambda path: {'x86_64'})
        with self.assertRaises(ValueError): validate(self.root, 'arm64', lambda path: {'arm64', 'x86_64'})
        with self.assertRaises(ValueError): validate(self.root, 'windows')

    def test_no_unproved_extension_or_signing_material(self):
        for name in ['Library/SystemExtensions', 'certificate.pem', 'identity.keychain-db', 'PerRouteHelperProbe']:
            target = self.contents / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.mkdir() if name.endswith('SystemExtensions') else target.write_bytes(b'fixture')
            with self.subTest(component=name), self.assertRaises(ValueError): self.check_bundle()
            target.rmdir() if target.is_dir() else target.unlink()

    def test_escaping_symlink(self):
        alias = self.contents / 'escape'
        alias.symlink_to(Path(self.temporary.name) / 'outside')
        with self.assertRaises(ValueError): self.check_bundle()

    def test_nonexecutable_engine(self):
        (self.contents / 'app/resources/xray/macos-arm64/xray').chmod(0o644)
        with self.assertRaises(ValueError): self.check_bundle()

    def test_nonexecutable_supervisor(self):
        (self.contents / 'MacOS/PerRouteCoreSupervisor').chmod(0o644)
        with self.assertRaises(ValueError): self.check_bundle()

    def test_aliased_required_component(self):
        engine = self.contents / 'app/resources/xray/macos-arm64/xray'
        engine.unlink()
        engine.symlink_to(self.contents / 'MacOS/PerRoute')
        with self.assertRaises(ValueError): self.check_bundle()


if __name__ == '__main__':
    unittest.main()
