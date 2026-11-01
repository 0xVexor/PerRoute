#!/usr/bin/env python3
"""Check real pinned payload staging using ad-hoc identities, without installing or running a TUN."""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
from macos_preview import ENGINES
from macos_routing_bundle import IDENTITIES, ROOT, stage


def check(app, config):
    architecture = platform.machine()
    if architecture not in ENGINES:
        raise ValueError('Unsupported macOS architecture')
    output = ROOT / 'build/macos-routing-check'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.bundle-', dir=output) as directory:
        staged = Path(directory) / 'PerRoute.app'
        contents = staged / 'Contents'
        contents.mkdir(parents=True)
        shutil.copyfile(app / 'Contents/Info.plist', contents / 'Info.plist')
        xray_relative = Path('Contents/app/resources/xray') / ENGINES[architecture] / 'xray'
        target = staged / xray_relative
        target.parent.mkdir(parents=True)
        shutil.copyfile(app / xray_relative, target)
        target.chmod(0o755)
        paths = stage(staged)
        for binary in paths:
            identifier = IDENTITIES[binary.name]
            subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', '--identifier', identifier, str(binary)], check=True)
            subprocess.run(['/usr/bin/codesign', '--verify', '--strict', '-R', f'=identifier "{identifier}"', str(binary)], check=True)
        subprocess.run([str(paths[0]), 'version'], check=True, timeout=15)
        subprocess.run([str(paths[0]), 'check', '-c', str(config.resolve())], check=True, timeout=30)
    print('Pinned routing/Xray components: real architecture, ad-hoc identity and parser checks passed. No installation, signing key or TUN activation.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    arguments = parser.parse_args()
    check(arguments.app, arguments.config)
