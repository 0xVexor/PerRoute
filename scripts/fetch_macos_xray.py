#!/usr/bin/env python3
"""Fetch the host Xray used by desktop packaging with the repository's pinned browser lock."""
import hashlib
import json
from pathlib import Path
import platform
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            result.update(chunk)
    return result.hexdigest()


def fetch():
    target = {'arm64': 'macos-arm64', 'x86_64': 'macos-x64'}[platform.machine()]
    lock = json.loads((ROOT / 'browser/helper/xray/xray.lock.json').read_text())
    asset = lock['assets'][target]
    directory = ROOT / 'tools/xray' / target
    binary = directory / 'xray'
    if binary.is_symlink() or directory.is_symlink():
        raise ValueError('Refusing aliased Xray cache')
    if binary.exists():
        if sha256(binary) != asset['binarySha256']:
            raise ValueError('Cached Xray differs from the pinned binary; preserve it separately before rebuilding')
        binary.chmod(0o755)
        print(f'Pinned {target} Xray verified: {lock["version"]}')
        return
    if lock['source'] != 'https://github.com/XTLS/Xray-core/releases/download':
        raise ValueError('Unexpected Xray download source')
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.download-', dir=directory) as temporary:
        archive = Path(temporary) / 'xray.zip'
        url = f'{lock["source"]}/{lock["version"]}/{asset["file"]}'
        with urllib.request.urlopen(url, timeout=60) as response, archive.open('wb') as output:
            size = 0
            for chunk in iter(lambda: response.read(1048576), b''):
                size += len(chunk)
                if size > 134217728:
                    raise ValueError('Xray download exceeds the bound')
                output.write(chunk)
        if sha256(archive) != asset['sha256']:
            raise ValueError('Xray archive checksum mismatch')
        with zipfile.ZipFile(archive) as package:
            members = [item for item in package.infolist() if item.filename == 'xray']
            if len(members) != 1 or members[0].file_size > 134217728:
                raise ValueError('Expected one bounded Xray executable')
            staged = Path(temporary) / 'xray'
            with package.open(members[0]) as source, staged.open('wb') as output:
                import shutil
                shutil.copyfileobj(source, output)
            if sha256(staged) != asset['binarySha256']:
                raise ValueError('Extracted Xray checksum mismatch')
            staged.chmod(0o755)
            staged.replace(binary)
            if 'LICENSE' in package.namelist():
                info = package.getinfo('LICENSE')
                if info.file_size > 131072:
                    raise ValueError('Unexpected license size')
                (directory / 'LICENSE').write_bytes(package.read(info))
    print(f'Pinned {target} Xray downloaded and verified: {lock["version"]}')


if __name__ == '__main__':
    fetch()
