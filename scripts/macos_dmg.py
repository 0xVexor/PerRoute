#!/usr/bin/env python3
"""Package the sealed account-free desktop app without installing it or changing its signature."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import sys
import tempfile

from macos_preview import ENGINES, architectures, macho, run

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            checksum.update(block)
    return checksum.hexdigest()


def validate(app, architecture, inspect=architectures):
    app = Path(app)
    if architecture not in ENGINES or app.is_symlink() or not app.is_dir():
        raise ValueError('Expected a real architecture-matched app bundle')
    root = app.resolve()
    contents = root / 'Contents'
    info = plistlib.loads((contents / 'Info.plist').read_bytes())
    version = info.get('CFBundleShortVersionString', '')
    if info.get('CFBundleIdentifier') != 'app.perroute.spike' or info.get('CFBundleExecutable') != 'PerRoute' or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Unexpected app identity or version')
    if int(str(info.get('LSMinimumSystemVersion', '0')).split('.')[0]) < 13:
        raise ValueError('A minimum macOS 13 declaration is required')
    required = [
        'MacOS/PerRoute', 'MacOS/PerRouteHelperInstaller', 'MacOS/PerRouteCoreSupervisor',
        'Library/HelperTools/PerRouteProxyHelper', 'Library/Engines/sing-box',
        'Frameworks/libPerRouteProxyBridge.dylib', 'Frameworks/libPerRouteDesktopBridge.dylib',
        'runtime/Contents/Home/lib/server/libjvm.dylib',
        f'app/resources/xray/{ENGINES[architecture]}/xray',
        'Resources/PerRoute.icns', 'app/resources/LICENSE', 'app/resources/THIRD_PARTY_NOTICES.md',
        'Library/Engines/sing-box.LICENSE', 'Library/Engines/GPL-3.0.txt',
        'Library/Engines/sing-box.provenance.json',
    ]
    for name in required:
        path = contents / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'Missing or aliased component: {name}')
    if (contents / 'Library/SystemExtensions').exists() or (contents / 'Library/LaunchDaemons/app.perroute.proxy-helper.plist').exists():
        raise ValueError('Do not mix Network Extension/SMAppService payloads into the account-free app')
    for path in root.rglob('*'):
        if path.is_symlink():
            if not path.resolve().is_relative_to(root):
                raise ValueError('An app symlink escapes the bundle')
            continue
        if path.suffix.lower() in {'.pem', '.p12', '.pfx', '.pk8', '.keychain-db', '.jks', '.keystore', '.exe', '.dll'} or path.name == 'identity.json':
            raise ValueError(f'Private signing material or foreign executable in app: {path.name}')
        if macho(path) and architecture not in inspect(path):
            raise ValueError(f'Wrong native architecture: {path.relative_to(root)}')
    for name in required[:9]:
        if not macho(contents / name):
            raise ValueError(f'Component is not native code: {name}')
    for name in required[:5] + [required[8]]:
        if not os.access(contents / name, os.X_OK):
            raise ValueError(f'Component is not executable: {name}')
    provenance = json.loads((contents / 'Library/Engines/sing-box.provenance.json').read_text())
    if provenance.get('architecture') != architecture:
        raise ValueError('Routing engine provenance does not match this architecture')
    if inspect(contents / required[8]) != {architecture} or inspect(contents / required[4]) != {architecture}:
        raise ValueError('The proxy and routing engines must match the target exactly')
    return version, provenance


def verify_signature(app):
    run('/usr/bin/codesign', '--verify', '--deep', '--strict', app)
    # Read only the public certificate. Private signing state never enters packaging or its output.
    with tempfile.TemporaryDirectory(prefix='perroute-public-certificate-') as temporary:
        prefix = Path(temporary) / 'certificate'
        run('/usr/bin/codesign', '--display', f'--extract-certificates={prefix}', app)
        certificate = Path(str(prefix) + '0')
        if not certificate.is_file():
            raise ValueError('A certificate-signed app is required; ad-hoc signing breaks helper authentication')
        pin = hashlib.sha1(certificate.read_bytes()).hexdigest()
    provenance = json.loads((Path(app) / 'Contents/Library/Engines/sing-box.provenance.json').read_text())
    engine_identity = 'app.perroute.tun-engine.safari-v1' if provenance.get('safariProtocol') == 1 else 'app.perroute.tun-engine'
    for name, identity in [('', 'app.perroute.spike'), ('Contents/MacOS/PerRouteHelperInstaller', 'app.perroute.proxy-installer'),
                           ('Contents/Library/HelperTools/PerRouteProxyHelper', 'app.perroute.proxy-helper'),
                           ('Contents/Library/Engines/sing-box', engine_identity),
                           (f'Contents/app/resources/xray/{ENGINES[platform.machine()]}/xray', 'app.perroute.xray')]:
        requirement = f'=identifier "{identity}" and certificate leaf = H"{pin}"'
        run('/usr/bin/codesign', '--verify', '--strict', '-R', requirement, Path(app) / name)
    return pin


def instructions(version, architecture):
    return f'''Install PerRoute {version}

1. Drag PerRoute.app onto the Applications folder beside it.
2. Eject the PerRoute disk.
3. Open Finder > Applications > PerRoute.
4. Import your server, choose a connection mode, then click Connect.
5. Approve the administrator request to install PerRoute's helper.
   Approve Keychain access if asked.

Do not open PerRoute directly from this disk.

If macOS blocks the app, go to System Settings > Privacy & Security >
Open Anyway after trying to open it. This preview is not notarized.

Requires macOS 13 or newer. Java is included.
'''


def maintenance_instructions(version, architecture):
    return f'''PerRoute {version} for macOS 13 or newer, {architecture}
Account-free preview. Locally certificate-signed, NOT Apple Developer ID signed or notarized.

INSTALL
1. Open the DMG and drag PerRoute.app to Applications. Do not run it from this disk image.
2. Eject the image and open /Applications/PerRoute.app.
3. macOS may block the unnotarized app. For a download you trust, use System Settings >
   Privacy & Security > Open Anyway after attempting to open it, then confirm directly.
   See https://support.apple.com/102445 . Do not disable Gatekeeper system-wide.
4. Import your server configuration in PerRoute, choose System proxy or Smart per-app and Connect.
5. Approve the one-time administrator request to install the matched background helper.
   Approve Keychain access directly if requested. Normal Connect/Disconnect does not reinstall it.

The Java runtime, Xray and routing components are included; users do not need Java or build tools.
An Apple Developer account is not required to use this preview. The package does not add trust
certificates or register the helper itself. Helper installation happens only after your approval.

UPGRADE
Disconnect and fully Quit first. Replace the app in Applications with the same publisher's build.
Keep the publisher's signing identity stable. If helper signatures change, remove the old helper
using the OLD app before replacing it. Never overwrite a running app or delete a live helper.
Automatic updates and rollback are not implemented on macOS.

UNINSTALL
1. Disconnect and fully Quit PerRoute.
2. In Terminal run this BEFORE deleting the app:
   /Applications/PerRoute.app/Contents/MacOS/PerRoute --proxy-helper-unregister-interactive
3. Approve any Keychain and administrator dialogs directly. Continue only if the command succeeds
   and reports registered=false and pending=false. If it fails, keep the app and restore/retry.
4. Move /Applications/PerRoute.app to Trash. This retains your imported configurations and Keychain
   data. Helper recovery directories are retained deliberately; do not remove them while recovery
   is pending. Deleting all personal data is separate from uninstalling the app and helper.
If no helper was ever installed, skip step 2 after confirming --proxy-helper-status reports
registered=false. Do not use this removal command while another PerRoute instance is running.

TEST THIS PREVIEW
After removing the previous app/helper, copy from this DMG, launch from Applications and import
or select a working configuration. Check first-time approval, System proxy Connect/Disconnect,
then Chrome including helpers in Smart per-app. Keep other VPN/proxy tools disconnected for a
clear comparison. Test website content, not just an IP check. Check normal internet after Disconnect.

LIMITS
Smart per-app currently captures selected-app IPv4 TCP. Safari shared WebKit ownership, UDP/QUIC,
IPv6, DNS interception and a per-app kill switch are not established. Use System proxy for Safari.
The Chrome extension is a separate product and is NOT installed by this DMG.
This build is for user acceptance, not a claim of completed notarization, Intel testing, or release
source-compliance review. Bundled GPL notices/provenance and source references are retained.

CHECK DOWNLOAD
Compare the adjacent SHA256SUMS file with: shasum -a 256 -c SHA256SUMS
BUILD.json identifies the source revision, architecture and public signing certificate fingerprint.

BUILD AGAIN
Use a Mac of the target CPU architecture with JDK 21, Xcode Command Line Tools and Python 3.
From a checkout of the recorded source revision run:
  sh gradlew --no-daemon packageMacosDmg
Set JAVA_HOME to JDK 21 if needed. First use needs internet for Gradle dependencies and the pinned
Xray/routing-engine archives. Both engine archives are checksum-verified. Later builds reuse caches.
Output: build/macos-installer/ . SHA256SUMS covers the DMG. The task mounts the image read-only,
checks its app signatures and components, then ejects it. It never installs the app or helper.
The local signing identity is created once in a dedicated private Keychain and reused. Keep that
identity stable for future updates. Do not upload its keychain, password or identity.json. Only the
public certificate fingerprint appears in BUILD.json. A new publisher identity requires explicit
helper removal before changing builds. This is a repeatable build process, not a claim of
byte-for-byte identical DMGs; filesystem/image metadata may vary.
Build Apple Silicon on Apple Silicon. Intel needs its own build and live acceptance.
Before publishing broadly, complete clean downloaded-install acceptance and the recorded engine
source/license compliance review. This preview does not mark those release gates complete.
A checksum detects corruption; obtain both files from the same trusted publisher release.
'''


def package(app, output):
    architecture = platform.machine()
    if sys.platform != 'darwin':
        raise ValueError('Build this DMG on macOS with the target architecture and bundled JVM')
    app, output = Path(app), Path(output)
    if app.resolve().is_relative_to(Path('/Applications')) or app.resolve().is_relative_to(Path.home() / 'Applications'):
        raise ValueError('Package a build artifact, never the installed application')
    if output.resolve().is_relative_to(app.resolve()):
        raise ValueError('Output must be outside the signed app')
    version, provenance = validate(app, architecture)
    pin = verify_signature(app)
    output.mkdir(parents=True, exist_ok=True)
    revision = run('git', '-C', ROOT, 'rev-parse', 'HEAD')
    dirty = bool(run('git', '-C', ROOT, 'status', '--porcelain', '--untracked-files=normal'))
    metadata = {'schemaVersion': 1, 'version': version, 'architecture': architecture, 'minimumMacOS': '13.0',
                'sourceRevision': revision, 'sourceTreeDirty': dirty, 'signingCertificateSha1': pin,
                'developerIdSigned': False, 'notarized': False, 'channel': 'preview',
                'source': f'https://github.com/k4vrin/PerRoute/tree/{revision}',
                'routingEngine': provenance}
    name = f'PerRoute-{version}-macos-{architecture}-preview.dmg'
    with tempfile.TemporaryDirectory(prefix='.dmg-', dir=output) as temporary:
        work = Path(temporary)
        payload = work / 'payload'
        payload.mkdir()
        run('/usr/bin/ditto', app, payload / 'PerRoute.app')
        verify_signature(payload / 'PerRoute.app')
        (payload / 'Applications').symlink_to('/Applications')
        (payload / 'Install PerRoute.txt').write_text(instructions(version, architecture))
        metadata_text = json.dumps(metadata, indent=2) + '\n'
        image = work / name
        run('/usr/bin/hdiutil', 'create', '-volname', f'PerRoute {version}', '-srcfolder', payload,
            '-fs', 'HFS+', '-format', 'UDZO', image)
        run('/usr/bin/hdiutil', 'verify', image)
        mount = work / 'mounted'
        mount.mkdir()
        run('/usr/bin/hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', mount, image)
        try:
            validate(mount / 'PerRoute.app', architecture)
            if verify_signature(mount / 'PerRoute.app') != pin:
                raise ValueError('Mounted app signing identity changed')
            if os.readlink(mount / 'Applications') != '/Applications':
                raise ValueError('Applications shortcut changed')
            if (mount / 'Install PerRoute.txt').read_text() != instructions(version, architecture):
                raise ValueError('Mounted installation guide changed')
        finally:
            run('/usr/bin/hdiutil', 'detach', mount)
        checksum = f'{digest(image)}  {name}\n'
        shutil.copyfile(payload / 'Install PerRoute.txt', output / 'INSTALL.txt')
        (output / 'BUILD.json').write_text(metadata_text)
        (output / 'MAINTENANCE.txt').write_text(maintenance_instructions(version, architecture))
        image.replace(output / name)
        (output / 'SHA256SUMS').write_text(checksum)
    print(f'DMG verified: {output / name}')
    print(f'Source: {revision}; dirty={dirty}; account-free preview, not notarized')
    print('No app installation, helper registration, or network changes performed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/macos-installer')
    args = parser.parse_args()
    package(args.app, args.output)
