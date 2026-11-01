#!/usr/bin/env python3
"""Build a certificate-pinned, status-only SMAppService feasibility probe."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'native/macos-helper-probe'
OUTPUT = ROOT / 'build/macos-helper-probe'
STATE = Path.home() / 'Library/Application Support/PerRoute/HelperProbeSigning'
APP_ID = 'app.perroute.helper-probe'
SERVICE = APP_ID + '.daemon'
APP = OUTPUT / 'PerRoute Helper Probe.app'
INSTALL = Path('/Applications/PerRoute Helper Probe.app')


def run(*args, quiet=False):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True)
    if result.returncode:
        # Do not include argv: Keychain commands carry a local signing password.
        raise SystemExit(f'{Path(str(args[0])).name} failed: {result.stderr.strip()}')
    if not quiet and result.stdout:
        print(result.stdout, end='')
    return result.stdout


def private(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise SystemExit(f'Refusing unsafe local signing file: {path.name}')


def identity():
    directory = STATE.lstat()
    if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.getuid() or directory.st_mode & 0o077:
        raise SystemExit('Unsafe local signing directory')
    private(STATE / 'identity.json')
    settings = json.loads((STATE / 'identity.json').read_text())
    certificate = STATE / 'certificate.der'
    private(certificate)
    private(STATE / 'identity.keychain-db')
    if hashlib.sha1(certificate.read_bytes()).hexdigest() != settings['sha1']:
        raise SystemExit('Stored signing certificate changed; identity rotation needs explicit handling')
    return settings


def create_identity(target=STATE):
    if target.exists():
        if target != STATE:
            raise SystemExit('Temporary identity destination already exists')
        identity()
        print('Reusing the existing local identity; no certificate rotation.')
        return
    target.mkdir(parents=True, mode=0o700)
    if target.is_symlink() or target.stat().st_uid != os.getuid():
        raise SystemExit('Unsafe signing directory')
    os.chmod(target, 0o700)
    import secrets
    password = secrets.token_urlsafe(32)
    keychain = target / 'identity.keychain-db'
    # security create-keychain can modify the search list. Restore exactly that list,
    # and address our dedicated keychain explicitly for all signing operations.
    import shlex
    previous = shlex.split(run('/usr/bin/security', 'list-keychains', '-d', 'user', quiet=True))
    try:
        with tempfile.TemporaryDirectory(prefix='perroute-signing-') as temp:
            work = Path(temp)
            config = work / 'certificate.cnf'
            config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=v3\nprompt=no\n[dn]\nCN=PerRoute Local Helper Probe\n[v3]\nbasicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=critical,codeSigning\n')
            run('/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '3650',
                '-config', config, '-keyout', work / 'key.pem', '-out', work / 'cert.pem', quiet=True)
            run('/usr/bin/openssl', 'x509', '-in', work / 'cert.pem', '-outform', 'DER', '-out', target / 'certificate.der')
            os.chmod(target / 'certificate.der', 0o600)
            password_file = work / 'password'
            password_file.write_text(password)
            os.chmod(password_file, 0o600)
            run('/usr/bin/openssl', 'pkcs12', '-export', '-inkey', work / 'key.pem', '-in', work / 'cert.pem',
                '-name', 'PerRoute Local Helper Probe', '-out', work / 'identity.p12', '-passout', f'file:{password_file}')
            run('/usr/bin/security', 'create-keychain', '-p', password, keychain, quiet=True)
            os.chmod(keychain, 0o600)
            run('/usr/bin/security', 'unlock-keychain', '-p', password, keychain, quiet=True)
            run('/usr/bin/security', 'import', work / 'identity.p12', '-k', keychain, '-P', password,
                '-T', '/usr/bin/codesign', quiet=True)
            run('/usr/bin/security', 'set-key-partition-list', '-S', 'apple-tool:,apple:,codesign:', '-s',
                '-k', password, keychain, quiet=True)
            settings = {'password': password, 'sha1': hashlib.sha1((target / 'certificate.der').read_bytes()).hexdigest()}
            descriptor = os.open(target / 'identity.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as stream:
                json.dump(settings, stream)
    finally:
        run('/usr/bin/security', 'list-keychains', '-d', 'user', '-s', *previous, quiet=True)
    print('Created a dedicated local signing identity. No global certificate trust was added.')


def compile_binary(target, source, frameworks, pin, universal=False, info=None):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / 'SigningPin.h').write_text(f'#define PR_PROBE_CERT_SHA1 "{pin}"\n')
    command = ['xcrun', 'clang', '-fobjc-arc', '-fmodules', '-Wall', '-Wextra', '-Werror',
               '-mmacosx-version-min=13.0', '-I', str(OUTPUT), '-I', str(SOURCES)]
    if universal:
        command += ['-arch', 'arm64', '-arch', 'x86_64']
    for framework in ['Foundation', 'Security', *frameworks]:
        command += ['-framework', framework]
    command += [str(SOURCES / 'Identity.m'), str(SOURCES / source)]
    if info:
        command += [f'-Wl,-sectcreate,__TEXT,__info_plist,{info}']
    run(*command, '-o', target)


def policy_tests():
    compile_binary(OUTPUT / 'HelperCompileCheck', 'Helper.m', ['SystemConfiguration'], '0' * 40, universal=True)
    compile_binary(OUTPUT / 'HostCompileCheck', 'Host.m', ['ServiceManagement'], '0' * 40, universal=True)
    compile_binary(OUTPUT / 'PolicyTests', 'PolicyTests.m', [], '0' * 40)
    run(OUTPUT / 'PolicyTests')


def sign(target, identifier, settings, keychain=None):
    run('/usr/bin/codesign', '--force', '--options', 'runtime', '--timestamp=none',
        '--keychain', keychain or STATE / 'identity.keychain-db', '--sign', settings['sha1'], '--identifier', identifier, target)
    run('/usr/bin/codesign', '--verify', '--strict', target)


def build():
    settings = identity()
    run('/usr/bin/security', 'unlock-keychain', '-p', settings['password'], STATE / 'identity.keychain-db', quiet=True)
    if APP.exists():
        # Replacing a registered bundle can leave launchd running the old helper.
        result = subprocess.run([str(APP / 'Contents/MacOS/PerRouteHelperProbe'), 'status'], capture_output=True, text=True)
        if result.returncode != 0 or json.loads(result.stdout).get('status') not in (0, 3):
            raise SystemExit('Unregister the existing probe before rebuilding; registration state is not safely replaceable')
        shutil.rmtree(APP)
    contents = APP / 'Contents'
    for subdir in ['MacOS', 'Library/HelperTools', 'Library/LaunchDaemons']:
        (contents / subdir).mkdir(parents=True, exist_ok=True)
    app_info = {'CFBundleIdentifier': APP_ID, 'CFBundleExecutable': 'PerRouteHelperProbe', 'CFBundleName': 'PerRoute Helper Probe',
                'CFBundlePackageType': 'APPL', 'CFBundleVersion': '1', 'LSMinimumSystemVersion': '13.0', 'LSUIElement': True}
    (contents / 'Info.plist').write_bytes(plistlib.dumps(app_info))
    helper_info = OUTPUT / 'HelperInfo.plist'
    helper_info.write_bytes(plistlib.dumps({'CFBundleIdentifier': SERVICE, 'CFBundleVersion': '1'}))
    launch = {'Label': SERVICE, 'BundleProgram': 'Contents/Library/HelperTools/PerRouteHelperProbe',
              'AssociatedBundleIdentifiers': [APP_ID], 'MachServices': {SERVICE: True}, 'ProcessType': 'Interactive'}
    (contents / 'Library/LaunchDaemons' / f'{SERVICE}.plist').write_bytes(plistlib.dumps(launch))
    helper = contents / 'Library/HelperTools/PerRouteHelperProbe'
    compile_binary(helper, 'Helper.m', ['SystemConfiguration'], settings['sha1'], universal=True, info=helper_info)
    compile_binary(contents / 'MacOS/PerRouteHelperProbe', 'Host.m', ['ServiceManagement'], settings['sha1'], universal=True)
    sign(helper, SERVICE, settings)
    sign(APP, APP_ID, settings)
    run(contents / 'MacOS/PerRouteHelperProbe', 'status')
    print(f'Built {APP}. No service registered; no network changes.')


def signature_tests():
    settings = identity()
    run('/usr/bin/security', 'unlock-keychain', '-p', settings['password'], STATE / 'identity.keychain-db', quiet=True)
    helper = APP / 'Contents/Library/HelperTools/PerRouteHelperProbe'
    if not helper.is_file():
        raise SystemExit('Build the probe before signature tests')
    compile_binary(OUTPUT / 'SignatureTests', 'SignatureTests.m', [], settings['sha1'])
    with tempfile.TemporaryDirectory(prefix='perroute-signature-tests-') as temp:
        work = Path(temp)
        wrong_id, adhoc, tampered, wrong_certificate = [work / name for name in ['wrong-id', 'adhoc', 'tampered', 'wrong-certificate']]
        for target in [wrong_id, adhoc, tampered, wrong_certificate]:
            shutil.copy2(helper, target)
        sign(wrong_id, APP_ID, settings)
        run('/usr/bin/codesign', '--force', '--sign', '-', '--identifier', SERVICE, adhoc)
        with tampered.open('ab') as stream:
            stream.write(b'changed after signing')
        temporary_identity = work / 'signing'
        try:
            create_identity(temporary_identity)
            other = json.loads((temporary_identity / 'identity.json').read_text())
            sign(wrong_certificate, SERVICE, other, temporary_identity / 'identity.keychain-db')
            run(OUTPUT / 'SignatureTests', helper, 'accept', wrong_id, 'reject', adhoc, 'reject',
                tampered, 'reject', wrong_certificate, 'reject')
        finally:
            if (temporary_identity / 'identity.keychain-db').exists():
                run('/usr/bin/security', 'delete-keychain', temporary_identity / 'identity.keychain-db', quiet=True)


def install(replace=False):
    identity()
    run(APP / 'Contents/MacOS/PerRouteHelperProbe', 'status')
    if INSTALL.is_symlink():
        raise SystemExit('Refusing a symlink install destination')
    if INSTALL.exists():
        if not replace:
            raise SystemExit('Probe exists; unregister it and use install --replace explicitly')
        existing = json.loads(run(INSTALL / 'Contents/MacOS/PerRouteHelperProbe', 'status', quiet=True))
        if existing.get('status') != 0:
            raise SystemExit('Unregister the installed probe before replacement')
        # The pinned existing host validates its signature and embedded helper first.
        shutil.rmtree(INSTALL)
    shutil.copytree(APP, INSTALL)
    run(INSTALL / 'Contents/MacOS/PerRouteHelperProbe', 'status')
    print(f'Installed {INSTALL}. Registration is a separate explicit command.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['test', 'create-identity', 'build', 'install', 'test-signatures'])
    parser.add_argument('--replace', action='store_true', help='Replace only a validated, unregistered probe installation')
    args = parser.parse_args()
    if args.replace and args.action != 'install':
        parser.error('--replace applies only to install')
    if os.uname().sysname != 'Darwin':
        parser.error('This probe requires macOS 13 or newer')
    if args.action == 'create-identity':
        create_identity()
    elif args.action == 'test':
        policy_tests()
    elif args.action == 'build':
        build()
    elif args.action == 'test-signatures':
        signature_tests()
    else:
        install(args.replace)


if __name__ == '__main__':
    main()
