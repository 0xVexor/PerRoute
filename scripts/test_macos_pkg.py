"""Installer policy and extraction checks without installing software or changing the host."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from macos_pkg import distribution, preinstall, verify_payload


class PkgTests(unittest.TestCase):
    def test_startup_disk_architecture_and_os_are_restricted(self):
        root = ET.fromstring(distribution('3.6.0', 'arm64'))
        self.assertEqual(root.find('options').get('hostArchitectures'), 'arm64')
        self.assertEqual(root.find('domains').get('enable_currentUserHome'), 'false')
        self.assertEqual(root.find('allowed-os-versions/os-version').get('min'), '13.0')

    def test_preinstall_parses_and_rejects_non_startup_disk_before_host_checks(self):
        script = preinstall('a' * 40)
        subprocess.run(['/bin/sh', '-n'], input=script, text=True, check=True)
        result = subprocess.run(['/bin/sh', '-s', '--', 'package', '/Applications', '/Volumes/Other'],
                                input=script, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('startup disk only', result.stderr)

    def test_preinstall_rejects_fingerprint_injection(self):
        with self.assertRaises(ValueError):
            preinstall('"; exit 0; #')

    def test_payload_checks_bytes_executable_bits_and_symlink_targets(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'source'
            source.mkdir()
            binary = source / 'launcher'
            binary.write_bytes(b'original')
            binary.chmod(0o755)
            (source / 'link').symlink_to('launcher')
            restored = Path(temporary) / 'restored'
            shutil.copytree(source, restored, symlinks=True)
            verify_payload(source, restored)
            (restored / 'launcher').chmod(0o644)
            with self.assertRaises(ValueError): verify_payload(source, restored)
            (restored / 'launcher').chmod(0o755)
            (restored / 'launcher').write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_payload(source, restored)
            (restored / 'launcher').write_bytes(b'original')
            (restored / 'link').unlink()
            (restored / 'link').symlink_to('elsewhere')
            with self.assertRaises(ValueError): verify_payload(source, restored)


if __name__ == '__main__':
    unittest.main()
