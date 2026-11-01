"""Refusal checks for the opt-in native proof's pre-execution boundary."""
import copy
from pathlib import Path
import tempfile
import os
import platform
import subprocess
import sys
import unittest
from unittest.mock import patch

from prove_macos_process_routing import assert_loopback_fixture
from verify_macos_per_app import check, verified_archive


class ProofBoundaryTests(unittest.TestCase):
    def fixture(self):
        return {'log': {'disabled': True},
                'inbounds': [{'type': 'direct', 'tag': 'perroute-tun-spike', 'listen': '127.0.0.1',
                              'listen_port': 30001, 'network': 'tcp', 'override_address': '127.0.0.1',
                              'override_port': 30002}],
                'outbounds': [{'type': 'direct', 'tag': 'unmatched-direct'}] + [
                    {'type': 'socks', 'tag': f'xray-loopback-{port}', 'server': '127.0.0.1',
                     'server_port': port, 'version': '5'} for port in (30003, 30004)],
                'route': {'auto_detect_interface': True, 'rules': [
                    {'process_path': ['/fixture/engine'], 'action': 'route', 'outbound': 'unmatched-direct'},
                    {'process_path': ['/fixture/client'], 'inbound': ['perroute-tun-spike'], 'network': 'tcp',
                     'action': 'route', 'outbound': 'xray-loopback-30003'}], 'final': 'unmatched-direct'}}

    def validate(self, config):
        assert_loopback_fixture(config, 30001, 30002, [30003, 30004])

    def test_fixed_loopback_fixture(self):
        self.validate(self.fixture())

    def test_refuse_capture_and_other_destinations(self):
        mutations = [
            lambda c: c['inbounds'][0].update(type='tun', auto_route=True),
            lambda c: c['inbounds'][0].update(listen='0.0.0.0'),
            lambda c: c['inbounds'][0].update(override_address='192.0.2.1'),
            lambda c: c['inbounds'][0].update(override_port=443),
            lambda c: c['inbounds'][0].update(network='udp'),
            lambda c: c['outbounds'][1].update(server='192.0.2.1'),
            lambda c: c['outbounds'].append({'type': 'http', 'server': '192.0.2.1'}),
            lambda c: c.update(dns={'servers': []}),
            lambda c: c['route'].update(rule_set=[{'url': 'https://example.invalid'}]),
            lambda c: c.update(log={'level': 'debug'}),
            lambda c: c['route']['rules'][1].update(override_address='192.0.2.1'),
            lambda c: c['route']['rules'][1].update(action='resolve'),
            lambda c: c['route'].update(final='unknown'),
            lambda c: c['route'].update(auto_detect_interface=False),
            lambda c: c['route'].update(rules=[]),
        ]
        for mutate in mutations:
            config = copy.deepcopy(self.fixture())
            mutate(config)
            with self.subTest(config=config), self.assertRaises(ValueError):
                self.validate(config)

    def test_conflicting_proof_modes_refused(self):
        script = Path(__file__).with_name('prove_macos_process_routing.py')
        result = subprocess.run([sys.executable, str(script), '--java', 'unused', '--classpath', 'unused',
                                 '--client', 'unused', '--probe', 'unused', '--native-only', '--privileged'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('not allowed with argument', result.stderr)

    def test_tampered_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'archive.tar.gz'
            path.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'digest'):
                verified_archive(path, 'arm64')

    def test_checker_refuses_activation_before_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.json'
            path.write_text('{"inbounds":[{"type":"tun","auto_route":true}]}')
            with patch('verify_macos_per_app.verified_engine') as stage:
                with self.assertRaises(ValueError):
                    check(path)
                stage.assert_not_called()


class PrivilegedLauncherTests(unittest.TestCase):
    @unittest.skipUnless(platform.system() == 'Darwin' and os.geteuid() != 0, 'macOS ordinary-user lifecycle')
    def test_deadline_reaps_term_ignoring_child(self):
        from macos_privileged_routing_proof import prepare_launcher, SOURCE
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            engine, config = root / 'engine', root / 'config'
            engine.write_text("#!/bin/sh\ntrap '' TERM\nexec /bin/sleep 60\n")
            config.write_text('{}')
            prepare_launcher(engine, config, root)
            # This disposable test copy alone bypasses the UID check. The real launcher
            # still refuses ordinary users, as checked above; no administrator is used.
            source = root / 'Lifecycle.c'
            source.write_text(SOURCE.read_text().replace(
                'int main(', '#define geteuid() ((uid_t)0)\nint main(', 1))
            executable = root / 'Lifecycle'
            subprocess.run(['xcrun', 'clang', '-Wall', '-Wextra', '-Werror',
                            '-Wno-deprecated-declarations', '-mmacosx-version-min=13.0',
                            '-I', str(root), str(source), '-o', str(executable)], check=True, timeout=30)
            before = set(Path('/private/tmp').glob('perroute-root-proof.*'))
            result = subprocess.run([str(executable)], capture_output=True, text=True, timeout=28)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('root staging removed', result.stdout)
            self.assertEqual(set(Path('/private/tmp').glob('perroute-root-proof.*')), before)

    @unittest.skipUnless(platform.system() == 'Darwin' and os.geteuid() != 0, 'macOS ordinary-user native validation')
    def test_fixed_payload_pins_and_refusals(self):
        from macos_privileged_routing_proof import prepare_launcher
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            engine, config = root / 'engine', root / 'config'
            engine.write_bytes(b'synthetic engine, never executed')
            config.write_bytes(b'synthetic config, never interpreted')
            executable = prepare_launcher(engine, config, root)
            original_engine, original_config = engine.read_bytes(), config.read_bytes()
            def refused():
                result = subprocess.run([str(executable), '--verify-only'], capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 78)
            engine.write_bytes(b'tampered engine')
            refused()
            engine.write_bytes(original_engine)
            config.write_bytes(b'tampered config')
            refused()
            config.write_bytes(original_config)
            renamed = root / 'renamed'
            engine.rename(renamed)
            engine.symlink_to(renamed)
            refused()
            engine.unlink()
            renamed.rename(engine)
            config.write_bytes(b'x' * (1024 * 1024 + 1))
            refused()
            config.write_bytes(original_config)
            config.unlink()
            config.mkdir()
            refused()
            invalid_argument = subprocess.run([str(executable), '--run-arbitrary'], capture_output=True, timeout=5)
            self.assertEqual(invalid_argument.returncode, 77)


if __name__ == '__main__':
    unittest.main()
