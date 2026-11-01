#!/usr/bin/env python3
"""Check a generated per-app candidate with a pinned standalone engine. Never run a TUN."""
import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

VERSION = '1.14.3'
DIGESTS = {
    'arm64': '0360ce634a04c26a6b4fd957517987bb4f6c8950f63435ce3c5af2fcae5860c5',
    'amd64': '6d002b74d03b478547349cc10af89e007868596e5aa931816d45da9f544715a9',
}
ROOT = Path(__file__).resolve().parents[1]
LIMIT = 64 * 1024 * 1024


def verified_archive(path, architecture):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > LIMIT:
        raise ValueError('Expected a bounded regular official archive')
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest != DIGESTS[architecture]:
        raise ValueError('Pinned sing-box archive digest does not match')


def fetch(path, architecture):
    name = f'sing-box-{VERSION}-darwin-{architecture}.tar.gz'
    request = urllib.request.Request(f'https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/{name}',
                                     headers={'User-Agent': 'PerRoute-offline-routing-check'})
    # Do not publish partial downloads or let a later execution trust an unchecked cached binary.
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as destination:
        temporary = Path(destination.name)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > LIMIT:
                        raise ValueError('Official archive exceeds size limit')
                    destination.write(chunk)
            destination.flush()
            verified_archive(temporary, architecture)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


@contextmanager
def verified_engine():
    if platform.system() != 'Darwin':
        raise ValueError('Native engine validation requires macOS; portable Java tests run separately')
    architecture = {'arm64': 'arm64', 'x86_64': 'amd64'}.get(platform.machine())
    if architecture is None:
        raise ValueError('Unsupported validation architecture')
    cache = ROOT / 'build/macos-per-app-routing'
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f'sing-box-{VERSION}-darwin-{architecture}.tar.gz'
    if not archive.exists():
        fetch(archive, architecture)
    verified_archive(archive, architecture)
    member_name = f'sing-box-{VERSION}-darwin-{architecture}/sing-box'
    with tempfile.TemporaryDirectory(prefix='.engine-check-', dir=cache) as directory:
        executable = Path(directory) / 'sing-box'
        found = False
        with tarfile.open(archive, 'r:gz') as bundle:
            total = 0
            for index, member in enumerate(bundle):
                total += member.size
                if index > 128 or total > 300 * 1024 * 1024:
                    raise ValueError('Unexpected archive structure')
                if member.name == member_name:
                    if found or not member.isfile() or member.size > 200 * 1024 * 1024:
                        raise ValueError('Expected one regular engine binary')
                    with bundle.extractfile(member) as source, executable.open('xb') as output:
                        shutil.copyfileobj(source, output)
                    found = True
        if not found:
            raise ValueError('Official archive is missing its engine')
        executable.chmod(0o500)
        yield executable, architecture


def check(config):
    if config.stat().st_size > 1024 * 1024:
        raise ValueError('Oversized candidate')
    candidate = json.loads(config.read_text())
    inbounds = candidate.get('inbounds', [])
    if len(inbounds) != 1 or inbounds[0].get('type') != 'tun' or inbounds[0].get('auto_route') is not False:
        raise ValueError('Expected the offline candidate with automatic host routes disabled')
    with verified_engine() as (executable, architecture):
        # Only the parser/checker runs here. The separate opt-in process proof uses a loopback
        # listener; neither tool installs this engine or adds it to the normal app package.
        subprocess.run([str(executable), 'version'], check=True, timeout=15)
        subprocess.run([str(executable), 'check', '-c', str(config.resolve())], check=True, timeout=30)
    print(f'Pinned sing-box {VERSION} {architecture}: candidate accepted; no TUN or host setting changed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    arguments = parser.parse_args()
    check(arguments.config)
