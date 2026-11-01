#!/usr/bin/env python3
"""Stage a persistent certificate-pinned local helper build. Does not install or launch it."""
import argparse
import json
import platform
from pathlib import Path
import shutil
import subprocess
import tempfile
import macos_helper_probe as signing
from macos_preview import ENGINES, macho
from macos_routing_bundle import stage as stage_routing, sign_payloads, enable_candidate_launch

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'native/macos-helper'


def compile_native(target, sources, settings, frameworks, *, library=False, info=None):
    command = ['xcrun', 'clang', '-fobjc-arc', '-fmodules', '-Wall', '-Wextra', '-Werror',
               '-arch', 'arm64', '-arch', 'x86_64', '-mmacosx-version-min=13.0',
               '-DPR_LOCAL_CERT_SHA1="' + settings['sha1'] + '"', '-I', SOURCES]
    if library:
        command += ['-dynamiclib', '-install_name', '@rpath/libPerRouteProxyBridge.dylib']
    for framework in ['Foundation', 'Security', *frameworks]:
        command += ['-framework', framework]
    command += [SOURCES / name for name in ['ProxyPolicy.m', *sources]]
    if info:
        command += ['-Wl,-sectcreate,__TEXT,__info_plist,' + str(info)]
    signing.run(*command, '-o', target)


def seal(path, settings, identifier=None):
    command = ['/usr/bin/codesign', '--force', '--timestamp=none', '--preserve-metadata=entitlements',
               '--keychain', signing.STATE / 'identity.keychain-db', '--sign', settings['sha1']]
    if identifier:
        command += ['--identifier', identifier]
    signing.run(*command, path, quiet=True)


def require_signing_state(state, installed_paths):
    if not state.exists() and any(path.exists() or path.is_symlink() for path in installed_paths):
        raise SystemExit('Local signing state is missing while PerRoute is installed. Restore the original signing state '
                         'or explicitly retire the installed app/helper before creating a new publisher identity.')


def build(app, *, routing=False):
    output = ROOT / ("build/macos-routing-helper" if routing else "build/macos-local-helper")
    require_signing_state(signing.STATE, [Path('/Applications/PerRoute.app'),
                                        Path('/Library/PrivilegedHelperTools/app.perroute.proxy-helper'),
                                        Path('/Library/LaunchDaemons/app.perroute.proxy-helper.plist')])
    if not signing.STATE.exists():
        signing.create_identity()
    settings = signing.identity()
    signing.run('/usr/bin/security', 'unlock-keychain', '-p', settings['password'],
                signing.STATE / 'identity.keychain-db', quiet=True)
    output.mkdir(parents=True, exist_ok=True)
    policy = output / 'LocalPolicyTests'
    compile_native(policy, ['ProxyJournal.m', 'PolicyTests.m'], settings, [])
    signing.run(policy)
    identity_test = output / 'LocalIdentityTests'
    compile_native(identity_test, ['LocalIdentityTests.m'], settings, [])
    signing.run(identity_test, 'app.perroute.spike', 'no')
    seal(identity_test, settings, 'app.perroute.spike')
    signing.run(identity_test, 'app.perroute.spike', 'yes')
    signing.run(identity_test, 'app.perroute.proxy-helper', 'no')
    seal(identity_test, settings, 'wrong.identifier')
    signing.run(identity_test, 'app.perroute.spike', 'no')
    with tempfile.TemporaryDirectory(prefix='perroute-local-foreign-') as temporary:
        foreign = Path(temporary) / 'signing'
        try:
            signing.create_identity(foreign)
            different = json.loads((foreign / 'identity.json').read_text())
            signing.sign(identity_test, 'app.perroute.spike', different, keychain=foreign / 'identity.keychain-db')
            signing.run(identity_test, 'app.perroute.spike', 'no')
        finally:
            if (foreign / 'identity.keychain-db').exists():
                signing.run('/usr/bin/security', 'delete-keychain', foreign / 'identity.keychain-db', quiet=True)
    print('Local identity: 5 real-signature checks passed; no service changes.')
    destination = output / 'PerRoute.app'
    # Build in disposable staging, so an interrupted build never leaves a partial install candidate.
    with tempfile.TemporaryDirectory(prefix='.local-', dir=output) as temporary:
        staged = Path(temporary) / 'PerRoute.app'
        shutil.copytree(app, staged, symlinks=True)
        contents = staged / 'Contents'
        # Remove unused platform payloads before signing, never from a sealed delivery app.
        resources = contents / 'app/resources'
        (resources / 'jnidispatch.dll').unlink(missing_ok=True)
        for entry in (resources / 'xray').iterdir():
            if entry.name not in {ENGINES[platform.machine()], 'geoip.dat', 'geosite.dat', 'README.txt'}:
                if entry.is_dir() and not entry.is_symlink():
                    shutil.rmtree(entry)
                else:
                    entry.unlink()
        helper = contents / 'Library/HelperTools/PerRouteProxyHelper'
        bridge = contents / 'Frameworks/libPerRouteProxyBridge.dylib'
        installer = contents / 'MacOS/PerRouteHelperInstaller'
        compile_native(helper, ['ProxyJournal.m', 'ProxyHelper.m', 'TunService.m', '../macos-tun/TunStore.m',
                                '../macos-tun/TunSession.m', '../macos-tun/EngineWorker.c', '../macos-tun/CoreOwnership.m',
                                '../macos-tun/TunAuthority.m', '../macos-tun/TunNetwork.m', '../macos-tun/TunPolicy.m'], settings, ['SystemConfiguration'], info=SOURCES / 'HelperInfo.plist')
        compile_native(bridge, ['ProxyBridge.m'], settings, ['ServiceManagement'], library=True)
        compile_native(installer, ['LocalInstaller.m', '../macos-tun/TunStore.m'], settings, [])
        # The local service is installed with fixed ProgramArguments. Never register the incompatible
        # SMAppService BundleProgram manifest from this account-free artifact.
        (contents / 'Library/LaunchDaemons/app.perroute.proxy-helper.plist').unlink(missing_ok=True)
        routing_payloads = stage_routing(staged) if routing else ()
        if routing:
            enable_candidate_launch(staged)
        runtime_executable = contents / 'runtime/Contents/MacOS/libjli.dylib'
        launcher = contents / 'MacOS/PerRoute'
        for binary in staged.rglob('*'):
            if macho(binary) and binary not in (launcher, runtime_executable, helper, installer, *routing_payloads):
                seal(binary, settings)
        sign_payloads(routing_payloads, settings, seal, signing.run)
        # The administrator installer must be inert without elevation.
        refused = subprocess.run([str(installer)], capture_output=True, text=True)
        if refused.returncode != 78:
            raise SystemExit('Unprivileged installer did not refuse execution')
        seal(helper, settings, 'app.perroute.proxy-helper')
        seal(installer, settings, 'app.perroute.proxy-installer')
        seal(contents / 'runtime', settings)
        seal(staged, settings, 'app.perroute.spike')
        signing.run('/usr/bin/codesign', '--verify', '--deep', '--strict', staged, quiet=True)
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(staged, destination)
    print(f'Local helper build staged: {destination}. No installation or network changes.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--routing', action='store_true', help='Include the pinned per-app engine in separate local staging')
    arguments = parser.parse_args()
    build(arguments.app, routing=arguments.routing)
