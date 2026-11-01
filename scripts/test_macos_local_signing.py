"""Protect installed helper authentication when persistent signing state disappears."""
from pathlib import Path
import tempfile
import unittest

from macos_local_helper import require_signing_state


class LocalSigningTests(unittest.TestCase):
    def test_fresh_checkout_can_create_its_first_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            require_signing_state(root / 'signing', [root / 'app', root / 'helper'])

    def test_missing_identity_refuses_each_installed_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ['app', 'helper', 'daemon.plist']:
                installed = root / name
                installed.touch()
                with self.assertRaisesRegex(SystemExit, 'Restore the original signing state'):
                    require_signing_state(root / 'signing', [installed])

    def test_dangling_installed_symlink_does_not_allow_rotation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            installed = root / 'app'
            installed.symlink_to(root / 'missing')
            with self.assertRaises(SystemExit):
                require_signing_state(root / 'signing', [installed])

    def test_existing_identity_remains_eligible_for_normal_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'signing').mkdir()
            (root / 'helper').touch()
            require_signing_state(root / 'signing', [root / 'helper'])


if __name__ == '__main__':
    unittest.main()
