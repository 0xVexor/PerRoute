#!/usr/bin/env python3
"""Validate common release identity and stage only this build's public assets."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def identity(tag=None):
    version = re.search(r'^appVersion=(\d+\.\d+\.\d+)$', (ROOT / 'gradle.properties').read_text(), re.M).group(1)
    if tag is not None and tag != f'v{version}':
        raise ValueError(f'Tag {tag!r} does not match shared version v{version}')
    def git(*args):
        return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()
    if git('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Release build identity requires full Git history')
    return {'version': version, 'buildNumber': git('rev-list', '--count', 'HEAD'),
            'sourceRevision': git('rev-parse', 'HEAD')}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def stage(platform):
    info = identity()
    version = info['version']
    output = ROOT / 'build/release' / platform
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    if platform == 'windows-x64':
        source = ROOT / 'dist'
        names = [f'PerRoute-Setup-{version}.exe', f'PerRoute-Portable-{version}.zip', f'PerRoute-Browser-{version}.zip']
        with zipfile.ZipFile(source / names[1]) as archive:
            prefix = f'PerRoute-{version}/'
            if archive.read(prefix + 'perroute-version.txt').decode().strip() != version:
                raise ValueError('Portable version mismatch')
            # The JVM resource must agree with the native installer version and source.
            matched = False
            import io
            for name in archive.namelist():
                if name.endswith('.jar'):
                    with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                        if 'perroute-build.properties' in jar.namelist():
                            properties = dict(line.split('=', 1) for line in jar.read('perroute-build.properties').decode().splitlines())
                            if properties != {'version': version, 'number': info['buildNumber'], 'revision': info['sourceRevision']}:
                                raise ValueError('Packaged JVM build identity mismatch')
                            matched = True
            if not matched:
                raise ValueError('Packaged JVM build identity missing')
        for name in names:
            shutil.copyfile(source / name, output / name)
    else:
        source = ROOT / 'build/macos-safari-pkg'
        metadata = json.loads((source / 'BUILD.json').read_text())['buildIdentity']
        if any(str(metadata[key]) != value for key, value in info.items()):
            raise ValueError('macOS package build identity mismatch')
        packages = list(source.glob(f'PerRoute-{version}-b{info["buildNumber"]}-*-macos-arm64-preview.pkg'))
        if len(packages) != 1:
            raise ValueError('Expected one matching macOS package')
        shutil.copyfile(packages[0], output / packages[0].name)
        for path in source.glob('PerRoute-Safari-engine-source-*.tar.gz'):
            if info['sourceRevision'][:12] in path.name:
                shutil.copyfile(path, output / path.name)
        # Carry installation/recovery guidance and source alongside the standalone PKG.
        with zipfile.ZipFile(output / f'PerRoute-macOS-arm64-{version}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(source.iterdir()):
                if path.is_file():
                    archive.write(path, path.name)
    info['platform'] = platform
    info['assets'] = {path.name: digest(path) for path in sorted(output.iterdir())}
    (output / f'BUILD-{platform}.json').write_text(json.dumps(info, indent=2) + '\n')
    print(json.dumps(info, indent=2))



def verify(directory, tag):
    expected = identity(tag)
    directory = Path(directory)
    assets = set()
    for platform in ('windows-x64', 'macos-arm64'):
        metadata = json.loads((directory / f'BUILD-{platform}.json').read_text())
        if metadata['platform'] != platform or any(metadata[key] != value for key, value in expected.items()):
            raise ValueError('Mixed release identities')
        for name, checksum in metadata['assets'].items():
            if Path(name).name != name or name in assets or digest(directory / name) != checksum:
                raise ValueError('Release asset checksum or name mismatch')
            assets.add(name)
    actual = {p.name for p in directory.iterdir() if p.is_file()}
    if actual != assets | {'BUILD-windows-x64.json', 'BUILD-macos-arm64.json'}:
        raise ValueError('Unexpected or missing release assets')
    (directory / 'SHA256SUMS').write_text(''.join(
        f'{digest(directory / name)}  {name}\n' for name in sorted(actual)))
    print(f'Verified {len(assets)} assets from {expected["sourceRevision"]}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--stage', choices=['windows-x64', 'macos-arm64'])
    args = parser.parse_args()
    if args.verify:
        verify(args.verify, args.tag)
    elif args.stage:
        stage(args.stage)
    else:
        print(json.dumps(identity(args.tag)))
