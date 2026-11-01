"""Offline refusal and signing-order tests for local routing payload staging."""
from contextlib import contextmanager
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch
from macos_routing_bundle import ROOT, stage, sign_payloads, enable_candidate_launch


class RoutingBundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='perroute-routing-bundle-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.app = self.root / 'PerRoute.app'
        self.contents = self.app / 'Contents'
        self.contents.mkdir(parents=True)
        self.info = {'CFBundleIdentifier': 'app.perroute.spike', 'CFBundleExecutable': 'PerRoute'}
        (self.contents / 'Info.plist').write_bytes(plistlib.dumps(self.info))
        self.xray = self.contents / 'app/resources/xray/macos-arm64/xray'
        self.xray.parent.mkdir(parents=True)
        self.xray.write_bytes(b'\xcf\xfa\xed\xfeSyntheticXray')
        self.engine = self.root / 'engine'
        self.engine.write_bytes(b'\xcf\xfa\xed\xfeSyntheticRoutingEngine')
        self.upstream_arch = 'arm64'
        self.license = (ROOT / 'src/main/resources/licenses/sing-box-LICENSE').read_bytes()

    @contextmanager
    def provider(self):
        yield self.engine, self.upstream_arch, self.license

    def stage(self, inspect=lambda path: {'arm64'}):
        return stage(self.app, 'arm64', provider=self.provider, inspect=inspect)

    def test_complete_staging_preserves_xray_and_records_provenance(self):
        before = self.xray.read_bytes()
        engine, xray = self.stage()
        self.assertEqual(engine.read_bytes(), self.engine.read_bytes())
        self.assertEqual(xray.read_bytes(), before)
        self.assertEqual(engine.stat().st_mode & 0o777, 0o755)
        self.assertEqual((engine.parent / 'sing-box.LICENSE').read_bytes(), self.license)
        self.assertIn(b'GNU GENERAL PUBLIC LICENSE', (engine.parent / 'GPL-3.0.txt').read_bytes())
        provenance = json.loads((engine.parent / 'sing-box.provenance.json').read_text())
        self.assertFalse(provenance['releaseSourceComplianceVerified'])
        self.assertEqual(provenance['architecture'], 'arm64')
        self.assertEqual(provenance['version'], '1.14.3')

    def test_wrong_architecture_refused_before_publish(self):
        self.upstream_arch = 'amd64'
        with self.assertRaises(ValueError): self.stage()
        self.assertFalse((self.contents / 'Library/Engines').exists())
        self.upstream_arch = 'arm64'
        with self.assertRaises(ValueError): self.stage(lambda path: {'x86_64'})

    def test_tampered_license_refused(self):
        self.license += b'tampered'
        with self.assertRaises(ValueError): self.stage()
        self.assertFalse((self.contents / 'Library/Engines').exists())

    def test_existing_payload_preserved(self):
        engine, _ = self.stage()
        with self.assertRaises(ValueError): self.stage()
        self.assertEqual(engine.read_bytes(), self.engine.read_bytes())

    def test_aliased_app_and_component_refused(self):
        alias = self.root / 'Alias.app'; alias.symlink_to(self.app)
        with self.assertRaises(ValueError): stage(alias, 'arm64', provider=self.provider)
        self.xray.unlink()
        self.xray.symlink_to(self.engine)
        with self.assertRaises(ValueError): self.stage()
        self.assertEqual(self.engine.read_bytes(), b'\xcf\xfa\xed\xfeSyntheticRoutingEngine')

    def test_aliased_parent_refused(self):
        library = self.root / 'foreign-library'; library.mkdir()
        (self.contents / 'Library').symlink_to(library)
        with self.assertRaises(ValueError): self.stage()
        self.assertEqual(list(library.iterdir()), [])

    def test_bundle_identity_refused(self):
        self.info['CFBundleIdentifier'] = 'other.app'
        (self.contents / 'Info.plist').write_bytes(plistlib.dumps(self.info))
        with self.assertRaises(ValueError): self.stage()

    def test_installed_destination_refused(self):
        with patch('macos_routing_bundle.Path.is_dir', return_value=True), patch('macos_routing_bundle.Path.is_symlink', return_value=False):
            with self.assertRaisesRegex(ValueError, 'installed app'):
                stage(Path('/Applications/PerRoute.app'), 'arm64', provider=self.provider)

    def test_explicit_identity_and_certificate_verification(self):
        paths = self.stage(); events = []
        def sign(path, settings, identifier): events.append(('sign', path.name, identifier))
        def verify(*args, **kwargs): events.append(('verify', args))
        sign_payloads(paths, {'sha1': 'A'*40}, sign, verify)
        self.assertEqual(events[0], ('sign', 'sing-box', 'app.perroute.tun-engine'))
        self.assertEqual(events[2], ('sign', 'xray', 'app.perroute.xray'))
        self.assertIn('=identifier "app.perroute.tun-engine" and certificate leaf = H"'+'A'*40+'"', events[1][1])
        self.assertIn('=identifier "app.perroute.xray" and certificate leaf = H"'+'A'*40+'"', events[3][1])

    def launcher(self, text='[Application]\napp.mainclass=main\n[JavaOptions]\njava-options=-Xmx1g\n[ArgOptions]\n'):
        config = self.contents / 'app/PerRoute.cfg'
        config.write_text(text)
        return config

    def enable(self):
        # Path/identity refusal has separate coverage above. Isolate the launcher edit here.
        with patch('macos_routing_bundle.bundle_paths', return_value=(self.app, self.xray)):
            enable_candidate_launch(self.app)

    def test_candidate_launch_preserves_existing_options(self):
        config = self.launcher()
        before = config.read_text()
        self.enable()
        flag = 'java-options=-Dperroute.macos.perAppCandidate=true\n'
        self.assertEqual(config.read_text(), before.replace('[JavaOptions]\n', '[JavaOptions]\n' + flag))
        with self.assertRaises(ValueError): self.enable()

    def test_invalid_launcher_sections_preserved(self):
        for text in ['[Application]\n', '[JavaOptions]\n[JavaOptions]\n']:
            config = self.launcher(text)
            with self.assertRaises(ValueError): self.enable()
            self.assertEqual(config.read_text(), text)

    def test_aliased_launcher_preserved(self):
        config = self.launcher()
        foreign = self.root / 'foreign.cfg'
        foreign.write_text(config.read_text())
        config.unlink()
        config.symlink_to(foreign)
        before = foreign.read_text()
        with self.assertRaises(ValueError): self.enable()
        self.assertEqual(foreign.read_text(), before)


if __name__ == '__main__':
    unittest.main()
