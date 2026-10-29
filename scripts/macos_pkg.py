#!/usr/bin/env python3
"""Build an unsigned preview Installer package from the sealed routing app."""
import argparse
import hashlib
import shutil
import json
from pathlib import Path
import platform
import plistlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

from macos_dmg import ROOT, digest, maintenance_instructions, validate, verify_signature
from macos_preview import run

PACKAGE_ID = 'app.perroute.desktop.pkg'


def check_pin(pin):
    if not re.fullmatch(r'[0-9a-f]{40}', pin):
        raise ValueError('Expected a public signing certificate SHA-1 fingerprint')


def script_header():
    return """#!/bin/sh
set -eu
PATH=/usr/bin:/bin:/usr/sbin:/sbin
export PATH
fail() { echo "PerRoute: $1" >&2; exit 1; }
[ "${3:-}" = / ] || fail "Install on the current startup disk only."
[ ! -L /Applications ] || fail "Applications must not be a symbolic link."
if /usr/bin/pgrep -x PerRoute >/dev/null 2>&1; then
    fail "Disconnect and Quit PerRoute before installing."
fi
"""


def requirement(identifier, pin):
    check_pin(pin)
    return f'=identifier "{identifier}" and certificate leaf = H"{pin}"'


def preinstall(pin, previous_pin=None):
    check_pin(pin)
    if previous_pin is not None:
        check_pin(previous_pin)
    app_requirement = requirement('app.perroute.spike', pin)
    helper_requirement = requirement('app.perroute.proxy-helper', pin)
    script = script_header() + """# Never replace software while its durable recovery records are live.
lease='/Library/Application Support/PerRoute/ProxyHelper/lease.plist'
[ ! -e "$lease" ] && [ ! -L "$lease" ] || fail "Proxy recovery is pending. Disconnect with the original app before installing."
tun='/Library/Application Support/PerRoute/TunHelper'
[ ! -L "$tun" ] || fail "Unsafe TUN recovery directory."
if [ -e "$tun" ]; then
    [ -d "$tun" ] || fail "Invalid TUN recovery directory."
    entries=$(/usr/bin/find "$tun" -mindepth 1 -maxdepth 1 -print) || fail "Could not inspect TUN recovery."
    [ -z "$entries" ] || fail "Per-app recovery is pending. Restore it with the original app before installing."
fi
app=/Applications/PerRoute.app
helper=/Library/PrivilegedHelperTools/app.perroute.proxy-helper
plist=/Library/LaunchDaemons/app.perroute.proxy-helper.plist
[ ! -L "$app" ] && [ ! -L "$helper" ] && [ ! -L "$plist" ] || fail "Installed software must not be a symbolic link."
"""
    for variable, current_requirement, previous_identifier in [
            ('app', app_requirement, 'app.perroute.spike'),
            ('helper', helper_requirement, 'app.perroute.proxy-helper')]:
        script += f'if [ -e "${variable}" ]; then\n'
        script += f"    if /usr/bin/codesign --verify --deep --strict -R '{current_requirement}' \"${variable}\"; then :\n"
        if previous_pin is not None:
            previous_requirement = requirement(previous_identifier, previous_pin)
            script += f"    elif /usr/bin/codesign --verify --deep --strict -R '{previous_requirement}' \"${variable}\"; then :\n"
        script += '    else fail "Installed software has an unsupported signing identity. Recover it with its original app first."; fi\nfi\n'
    script += """if [ -e "$plist" ] && [ ! -e "$helper" ]; then
    fail "An incomplete helper installation exists. Recover it with the original app first."
fi
"""
    if previous_pin is not None:
        old_helper = requirement('app.perroute.proxy-helper', previous_pin)
        old_installer = requirement('app.perroute.proxy-installer', previous_pin)
        script += f"""# This package carries the original signed retirement tool for exactly this certificate.
if [ -e "$helper" ] && /usr/bin/codesign --verify --strict -R '{old_helper}' "$helper"; then
    script_dir=$(CDPATH= cd -P "$(/usr/bin/dirname "$0")" && pwd)
    umask 077
    retirement_dir=$(/usr/bin/mktemp -d /private/var/tmp/perroute-retirement.XXXXXX) || fail "Could not stage the original retirement tool."
    trap '/bin/rm -rf "$retirement_dir"' EXIT
    /bin/mkdir -p "$retirement_dir/Contents/MacOS" "$retirement_dir/Contents/Library/HelperTools"
    /bin/cp "$script_dir/previous-installer" "$retirement_dir/Contents/MacOS/PerRouteHelperInstaller"
    /bin/cp "$script_dir/previous-helper" "$retirement_dir/Contents/Library/HelperTools/PerRouteProxyHelper"
    retirement="$retirement_dir/Contents/MacOS/PerRouteHelperInstaller"
    /usr/bin/codesign --verify --strict -R '{old_installer}' "$retirement" || fail "Invalid original retirement tool."
    "$retirement" --uninstall || fail "The original helper could not be retired safely. Installation stopped."
fi
"""
    return script + 'exit 0\n'


def postinstall(pin):
    app_requirement = requirement('app.perroute.spike', pin)
    helper_requirement = requirement('app.perroute.proxy-helper', pin)
    installer_requirement = requirement('app.perroute.proxy-installer', pin)
    return script_header() + f"""app=/Applications/PerRoute.app
installer="$app/Contents/MacOS/PerRouteHelperInstaller"
helper=/Library/PrivilegedHelperTools/app.perroute.proxy-helper
plist=/Library/LaunchDaemons/app.perroute.proxy-helper.plist
/usr/bin/codesign --verify --deep --strict -R '{app_requirement}' "$app" || fail "Installed app signature verification failed."
/usr/bin/codesign --verify --strict -R '{installer_requirement}' "$installer" || fail "Invalid helper installation tool."
if [ -e "$helper" ] || [ -L "$helper" ] || [ -e "$plist" ] || [ -L "$plist" ]; then
    # The signed tool rechecks root ownership, its publisher and proxy/TUN recovery.
    "$installer" --uninstall || fail "Existing helper could not be retired safely. Reopen the app for recovery."
fi
"$installer" || fail "Helper installation failed. Retry this package before connecting."
/usr/bin/codesign --verify --strict -R '{helper_requirement}' "$helper" || fail "Installed helper signature verification failed."
/usr/bin/cmp -s "$app/Contents/Library/HelperTools/PerRouteProxyHelper" "$helper" || fail "Installed helper does not match the app payload."
/bin/launchctl print system/app.perroute.proxy-helper >/dev/null || fail "Helper registration could not be confirmed."
echo "PerRoute app and matching helper installed. No proxy settings were enabled."
exit 0
"""


def previous_recovery_pin(recovery):
    recovery = Path(recovery)
    helper = recovery / 'Contents/Library/HelperTools/PerRouteProxyHelper'
    installer = recovery / 'Contents/MacOS/PerRouteHelperInstaller'
    for name in ['', 'Contents', 'Contents/MacOS', 'Contents/Library', 'Contents/Library/HelperTools']:
        if (recovery / name).is_symlink():
            raise ValueError('Recovery source must not contain symlink directories')
    for path in [helper, installer]:
        if path.is_symlink() or not path.is_file():
            raise ValueError('Original signed helper and retirement tool are required')
    with tempfile.TemporaryDirectory(prefix='perroute-recovery-certificate-') as temporary:
        prefix = Path(temporary) / 'certificate'
        run('/usr/bin/codesign', '--display', f'--extract-certificates={prefix}', helper)
        pin = hashlib.sha1(Path(str(prefix) + '0').read_bytes()).hexdigest()
    for path, identifier in [(helper, 'app.perroute.proxy-helper'), (installer, 'app.perroute.proxy-installer')]:
        run('/usr/bin/codesign', '--verify', '--strict', '-R', requirement(identifier, pin), path)
    return pin


def install_instructions():
    return '''Install PerRoute for macOS

1. Disconnect and Quit any existing PerRoute instance.
2. Open the .pkg in Installer and follow the prompts to install on the startup disk.
3. Open Finder > Applications > PerRoute.
4. Import your server, choose a mode and Connect. Approve Keychain access if requested.

Installer installs and registers the matching background helper using the same
administrator approval as the app. No separate helper setup is needed on Connect.
Installation does not activate system proxy or per-app routing.

Java and proxy engines are included. Requires macOS 13 or newer and the matching CPU.
This preview package is UNSIGNED and NOT notarized. The app retains its local
certificate signature. Installer and app security assessment still apply.
Changing from DMG to PKG is not a fix for an unidentified startup error.
If macOS offers Open Anyway for a trusted download, approve it in Privacy & Security.
If it only says the app cannot be opened, collect the Terminal launch error.
The package stops if recovery is pending or an unknown helper identity is installed.
An explicitly included original signed retirement tool can migrate a known old identity.
See MAINTENANCE.txt before changing publisher/signature or uninstalling.
'''


def distribution(version, architecture):
    root = ET.Element('installer-gui-script', {'minSpecVersion': '2'})
    ET.SubElement(root, 'title').text = f'PerRoute {version}'
    ET.SubElement(root, 'welcome', {'file': 'WELCOME.txt', 'mime-type': 'text/plain'})
    ET.SubElement(root, 'conclusion', {'file': 'CONCLUSION.txt', 'mime-type': 'text/plain'})
    ET.SubElement(root, 'options', {'customize': 'never', 'require-scripts': 'false',
                                   'hostArchitectures': architecture, 'rootVolumeOnly': 'true'})
    ET.SubElement(root, 'domains', {'enable_anywhere': 'false', 'enable_currentUserHome': 'false',
                                   'enable_localSystem': 'true'})
    allowed = ET.SubElement(root, 'allowed-os-versions')
    ET.SubElement(allowed, 'os-version', {'min': '13.0'})
    outline = ET.SubElement(root, 'choices-outline')
    ET.SubElement(outline, 'line', {'choice': 'perroute'})
    choice = ET.SubElement(root, 'choice', {'id': 'perroute', 'visible': 'false', 'title': 'PerRoute'})
    ET.SubElement(choice, 'pkg-ref', {'id': PACKAGE_ID})
    ET.SubElement(root, 'pkg-ref', {'id': PACKAGE_ID, 'version': version,
                                  'onConclusion': 'none'}).text = 'PerRoute-component.pkg'
    return ET.tostring(root, encoding='unicode', xml_declaration=True)


def verify_payload(source, restored):
    """Check bytes, symlink targets and executable bits after actual PKG extraction."""
    def manifest(root):
        result = {}
        for path in root.rglob('*'):
            name = str(path.relative_to(root))
            if path.is_symlink():
                result[name] = ('link', path.readlink().as_posix())
            elif path.is_file():
                result[name] = ('file', digest(path), path.stat().st_mode & 0o111)
        return result
    if manifest(source) != manifest(restored):
        raise ValueError('Extracted Installer payload differs from the signed source app')


def package(app, output, previous_helper_recovery=None):
    if sys.platform != 'darwin':
        raise ValueError('Build on macOS with the target architecture')
    app, output = Path(app).absolute(), Path(output).absolute()
    if app.resolve().is_relative_to(Path('/Applications')) or app.resolve().is_relative_to(Path.home() / 'Applications'):
        raise ValueError('Package a build artifact, never the installed application')
    if output.resolve().is_relative_to(app.resolve()):
        raise ValueError('Output must be outside the signed app')
    architecture = platform.machine()
    version, provenance = validate(app, architecture)
    pin = verify_signature(app)
    previous_pin = previous_recovery_pin(previous_helper_recovery) if previous_helper_recovery else None
    if previous_pin == pin:
        raise ValueError("Previous helper recovery is only needed for a different certificate")
    revision = run('git', '-C', ROOT, 'rev-parse', 'HEAD')
    identity_path = app / 'Contents/Resources/perroute-build.json'
    build_identity = json.loads(identity_path.read_text()) if identity_path.exists() else None
    if build_identity and build_identity['sourceRevision'] != revision:
        raise ValueError('App build identity does not match the source revision being packaged')
    output.mkdir(parents=True, exist_ok=True)
    build_suffix = '-' + build_identity['buildId'] if build_identity else ''
    package_name = f'PerRoute-{version}{build_suffix}-macos-{architecture}-preview.pkg'
    with tempfile.TemporaryDirectory(prefix='.pkg-', dir=output) as temporary:
        work = Path(temporary)
        payload = work / 'payload'
        payload.mkdir()
        run('/usr/bin/ditto', app, payload / 'PerRoute.app')
        scripts = work / 'scripts'
        scripts.mkdir()
        (scripts / 'preinstall').write_text(preinstall(pin, previous_pin))
        (scripts / 'preinstall').chmod(0o755)
        (scripts / 'postinstall').write_text(postinstall(pin))
        (scripts / 'postinstall').chmod(0o755)
        if previous_helper_recovery:
            for source, name in [('Contents/MacOS/PerRouteHelperInstaller', 'previous-installer'),
                                 ('Contents/Library/HelperTools/PerRouteProxyHelper', 'previous-helper')]:
                shutil.copy2(Path(previous_helper_recovery) / source, scripts / name)
        components = work / 'components.plist'
        components.write_bytes(plistlib.dumps([{
            'RootRelativeBundlePath': 'PerRoute.app', 'BundleIsRelocatable': False,
            'BundleIsVersionChecked': True, 'BundleHasStrictIdentifier': True,
            'BundleOverwriteAction': 'upgrade',
        }]))
        component = work / 'PerRoute-component.pkg'
        run('/usr/bin/pkgbuild', '--root', payload, '--component-plist', components,
            '--identifier', PACKAGE_ID, '--version', version, '--install-location', '/Applications',
            '--ownership', 'recommended', '--scripts', scripts, component)
        resources = work / 'resources'
        resources.mkdir()
        (resources / 'WELCOME.txt').write_text(install_instructions())
        (resources / 'CONCLUSION.txt').write_text(
            'PerRoute and its matching background helper are installed. Open PerRoute from Applications.\n'
            'If launch fails, run /Applications/PerRoute.app/Contents/MacOS/PerRoute in Terminal '
            'and report the error. Installation success does not establish launch success.\n')
        dist = work / 'Distribution.xml'
        dist.write_text(distribution(version, architecture))
        archive = work / package_name
        run('/usr/bin/productbuild', '--distribution', dist, '--resources', resources,
            '--package-path', work, archive)
        expanded = work / 'expanded'
        run('/usr/sbin/pkgutil', '--expand-full', archive, expanded)
        restored = expanded / 'PerRoute-component.pkg/Payload/PerRoute.app'
        restored_scripts = expanded / 'PerRoute-component.pkg/Scripts'
        for name in ['preinstall', 'postinstall']:
            if (restored_scripts / name).read_bytes() != (scripts / name).read_bytes():
                raise ValueError('Installer maintenance script changed during packaging')
        if previous_helper_recovery:
            for name, identifier in [('previous-installer', 'app.perroute.proxy-installer'),
                                     ('previous-helper', 'app.perroute.proxy-helper')]:
                if (restored_scripts / name).read_bytes() != (scripts / name).read_bytes():
                    raise ValueError('Original helper retirement payload changed during packaging')
                run('/usr/bin/codesign', '--verify', '--strict', '-R',
                    requirement(identifier, previous_pin), restored_scripts / name)
        verify_payload(app, restored)
        validate(restored, architecture)
        if verify_signature(restored) != pin:
            raise ValueError('Installer changed app signing identity')
        metadata = {'schemaVersion': 1, 'version': version, 'architecture': architecture,
                    'minimumMacOS': '13.0', 'sourceRevision': revision,
                    'sourceTreeDirty': bool(run('git', '-C', ROOT, 'status', '--porcelain', '--untracked-files=normal')),
                    'channel': 'preview', 'format': 'pkg', 'installLocation': '/Applications/PerRoute.app',
                    'signingCertificateSha1': pin, 'developerIdSigned': False,
                    'installerSigned': False, 'notarized': False, 'routingEngine': provenance,
                    'installsHelper': True, 'previousHelperCertificateSha1': previous_pin}
        if build_identity:
            metadata['buildIdentity'] = build_identity
        (output / 'BUILD.json').write_text(json.dumps(metadata, indent=2) + '\n')
        (output / 'INSTALL.txt').write_text(install_instructions())
        (output / 'MAINTENANCE.txt').write_text(
            install_instructions() + '\nAPP AND HELPER MAINTENANCE\n' +
            'UPGRADE\n' + maintenance_instructions(version, architecture).split('UPGRADE\n', 1)[1].split('TEST THIS PREVIEW\n', 1)[0] +
            '\nTEST THIS PREVIEW\nTest installation and first launch on a different Mac after downloading. '
            'Then test helper availability, System proxy Connect/Disconnect, selected Chrome with helpers, '
            'unselected direct traffic, and internet restoration on Quit.\n'
            'A generic launch failure still requires the Terminal error; do not disable Gatekeeper.\n'
            '\nLIMITS\nSafari per-app attribution, UDP/QUIC, IPv6, DNS interception and a kill switch '
            'remain unsupported or unestablished. The browser extension is separate. '
            'Intel execution, notarization and engine source-compliance release review remain open.\n'
            '\nRebuild: sh gradlew --no-daemon packageMacosPkg\n'
            'Verify download: shasum -a 256 -c SHA256SUMS\n')
        checksum = digest(archive)
        archive.replace(output / package_name)
        (output / 'SHA256SUMS').write_text(f'{checksum}  {package_name}\n')
    print(f'PKG payload and signatures verified: {output / package_name}')
    print('Unsigned/unnotarized preview. No app installation, helper registration or network changes performed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/macos-pkg')
    parser.add_argument('--previous-helper-recovery', type=Path, help='Original signed retirement tool and helper for an explicit certificate migration')
    args = parser.parse_args()
    package(args.app, args.output, args.previous_helper_recovery)
