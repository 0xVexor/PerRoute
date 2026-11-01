#!/usr/bin/env python3
"""Validate and package an account-free local macOS preview without installing or launching it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile

MACHO = {b'\xfe\xed\xfa\xce', b'\xce\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xcf\xfa\xed\xfe', b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca', b'\xca\xfe\xba\xbf', b'\xbf\xba\xfe\xca'}
ENGINES = {'arm64': 'macos-arm64', 'x86_64': 'macos-x64'}


def run(*arguments):
    result = subprocess.run([str(a) for a in arguments], capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError(f'{Path(str(arguments[0])).name} failed: {result.stderr.strip()}')
    return result.stdout.strip()


def architectures(path):
    return set(run('/usr/bin/lipo', '-archs', path).split())


def macho(path):
    if path.is_symlink() or not path.is_file() or path.suffix == '.class':
        return False
    with path.open('rb') as stream:
        return stream.read(4) in MACHO


def validate(app, architecture, inspect=architectures):
    if architecture not in ENGINES:
        raise ValueError('Unsupported architecture')
    app = Path(app)
    if app.is_symlink() or not app.is_dir():
        raise ValueError('App must be a real bundle directory')
    root = app.resolve()
    contents = root / 'Contents'
    info = plistlib.loads((contents / 'Info.plist').read_bytes())
    version = info.get('CFBundleShortVersionString', '')
    if info.get('CFBundleIdentifier') != 'app.perroute.spike' or info.get('CFBundleExecutable') != 'PerRoute' or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Unexpected app identity or version')
    floor = str(info.get('LSMinimumSystemVersion', '0')).split('.')
    if not floor[0].isdigit() or int(floor[0]) < 13:
        raise ValueError('The preview requires a declared minimum macOS 13')
    engine = contents / 'app/resources/xray' / ENGINES[architecture] / 'xray'
    required = [contents / 'MacOS/PerRoute', contents / 'runtime/Contents/Home/lib/server/libjvm.dylib', engine,
                contents / 'Library/HelperTools/PerRouteProxyHelper',
                contents / 'Frameworks/libPerRouteProxyBridge.dylib',
                contents / 'Frameworks/libPerRouteDesktopBridge.dylib',
                contents / 'Library/LaunchDaemons/app.perroute.proxy-helper.plist',
                contents / 'Resources/PerRoute.icns', contents / 'app/resources/LICENSE',
                contents / 'app/resources/THIRD_PARTY_NOTICES.md', contents / 'MacOS/PerRouteCoreSupervisor']
    for path in required:
        if not path.is_file() or path.is_symlink():
            raise ValueError(f'Missing or aliased required component: {path.relative_to(root)}')
    if (contents / 'Library/SystemExtensions').exists():
        raise ValueError('The unproved Network Extension must not ship in this limited preview')
    binaries = []
    for path in root.rglob('*'):
        if path.is_symlink() and not path.resolve().is_relative_to(root):
            raise ValueError('Bundle contains a symlink escaping its root')
        if path.suffix.lower() in {'.pem', '.p12', '.pfx', '.pk8', '.keychain-db', '.jks', '.keystore'} or 'HelperProbe' in path.name:
            raise ValueError('Development signing/probe material found in bundle')
        if macho(path):
            if architecture not in inspect(path):
                raise ValueError(f'Wrong native architecture: {path.relative_to(root)}')
            binaries.append(path)
    if not macho(required[1]):
        raise ValueError('The bundled JVM library is missing or invalid')
    for executable in [required[0], engine, required[3], required[-1]]:
        if not macho(executable) or not os.access(executable, os.X_OK):
            raise ValueError('Required native executable is missing or not executable')
    if inspect(engine) != {architecture}:
        raise ValueError('The Xray engine must match the preview architecture exactly')
    return version, binaries


def package(app, output, architecture):
    if sys.platform != 'darwin' or architecture != platform.machine():
        raise ValueError('Build on a Mac of the target architecture; the bundled JVM is host-specific')
    app, output = Path(app), Path(output)
    if output.resolve().is_relative_to(app.resolve()):
        raise ValueError('Output must be outside the source app bundle')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.preview-', dir=output) as temporary:
        work = Path(temporary)
        staged = work / 'PerRoute.app'
        shutil.copytree(app, staged, symlinks=True)
        engines = staged / 'Contents/app/resources/xray'
        for item in engines.iterdir():
            if item.name not in {ENGINES[architecture], 'geoip.dat', 'geosite.dat', 'README.txt'}:
                if item.is_dir() and not item.is_symlink():
                    shutil.rmtree(item)
                else:
                    item.unlink()
        (staged / 'Contents/app/resources/jnidispatch.dll').unlink(missing_ok=True)
        version, binaries = validate(staged, architecture)
        # Ad-hoc sealing needs no keychain, certificate or Developer ID. Preserve the launcher's
        # existing JVM entitlements and sign nested native code before its containing bundles.
        bundle_executables = {staged / 'Contents/MacOS/PerRoute', staged / 'Contents/runtime/Contents/MacOS/libjli.dylib'}
        for binary in binaries:
            # Signing a bundle's main executable implicitly signs the bundle; defer it until nested
            # code is sealed, otherwise codesign rejects the still-unsigned copied frameworks.
            if binary not in bundle_executables:
                run('/usr/bin/codesign', '--force', '--sign', '-', '--preserve-metadata=entitlements', binary)
        run('/usr/bin/codesign', '--force', '--sign', '-', staged / 'Contents/runtime')
        run('/usr/bin/codesign', '--force', '--sign', '-', '--preserve-metadata=entitlements', staged)
        run('/usr/bin/codesign', '--verify', '--deep', '--strict', staged)
        validate(staged, architecture)
        name = f'PerRoute-{version}-macos-{architecture}-preview.zip'
        archive = work / name
        run('/usr/bin/ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', staged, archive)
        checksum = hashlib.sha256()
        with archive.open('rb') as stream:
            for block in iter(lambda: stream.read(1_048_576), b''):
                checksum.update(block)
        digest = checksum.hexdigest()
        destination = output / f'{version}-{architecture}-{digest[:12]}'
        # A fresh destination prevents silently replacing an earlier preview or its evidence.
        destination.mkdir()
        shutil.move(str(archive), destination / name)
        manifest = {'schemaVersion': 1, 'platform': 'macos', 'architecture': architecture, 'version': version,
                    'minimumMacOS': '13.0', 'artifact': name, 'bytes': (destination / name).stat().st_size,
                    'sha256': digest, 'signing': 'ad-hoc', 'notarized': False,
                    'modes': ['local-proxy', 'authorized-system-proxy'], 'automaticUpdates': False,
                    'acceptance': 'local-build-validation-only'}
        (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps({'artifact': str(destination / name), 'manifest': str(destination / 'manifest.json'), 'sha256': digest}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--architecture', choices=list(ENGINES), default=platform.machine())
    args = parser.parse_args()
    try:
        package(args.app, args.output, args.architecture)
    except (ValueError, OSError, plistlib.InvalidFileException) as error:
        parser.exit(1, f'Preview packaging refused: {error}\n')


if __name__ == '__main__':
    main()
