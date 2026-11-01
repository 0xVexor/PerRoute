#!/usr/bin/env python3
"""Restore the approved private preview identity in an ephemeral CI workspace."""
import base64
import io
import hashlib
import json
import shlex
import shutil
import subprocess
import os
from pathlib import Path
import tarfile

root = Path(__file__).resolve().parents[1] / '.perroute-local-signing'
data = os.environ['MACOS_SIGNING_STATE']
if not data:
    raise SystemExit('MACOS_SIGNING_STATE secret is required; never rotate a shipped helper identity implicitly')
root.mkdir(mode=0o700)
expected = {'identity.json', 'certificate.der'}
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(data, validate=True)), mode='r:gz') as archive:
    members = archive.getmembers()
    names = {m.name for m in members}
    if names not in (expected | {'identity.p12'}, expected | {'identity.keychain-db'}) or len(members) != 3:
        raise SystemExit('Unexpected signing-state members')
    for member in members:
        if not member.isfile() or member.size > 1024 * 1024:
            raise SystemExit('Invalid signing-state member')
        with (root / member.name).open('xb') as output:
            os.chmod(output.name, 0o600)
            output.write(archive.extractfile(member).read())
settings = json.loads((root / 'identity.json').read_text())
if hashlib.sha1((root / 'certificate.der').read_bytes()).hexdigest() != settings['sha1']:
    raise SystemExit('Persistent certificate pin does not match')


def run(*args):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True)
    if result.returncode:
        # security arguments contain private passwords. Never echo the command.
        raise SystemExit(f'{Path(args[0]).name} failed preparing the CI signing identity')
    return result.stdout


keychain = root / 'identity.keychain-db'
portable = root / 'identity.p12'
if portable.exists():
    run('/usr/bin/security', 'create-keychain', '-p', settings['password'], keychain)
os.chmod(keychain, 0o600)
run('/usr/bin/security', 'unlock-keychain', '-p', settings['password'], keychain)
run('/usr/bin/security', 'set-keychain-settings', '-lut', '21600', keychain)
if portable.exists():
    run('/usr/bin/security', 'import', portable, '-k', keychain,
        '-P', settings['password'], '-T', '/usr/bin/codesign')
run('/usr/bin/security', 'set-key-partition-list', '-S', 'apple-tool:,apple:,codesign:',
    '-s', '-k', settings['password'], keychain)
previous = shlex.split(run('/usr/bin/security', 'list-keychains', '-d', 'user'))
# This runner is disposable. Search-list registration allows codesign to resolve
# the imported certificate and its key without changing any local developer state.
run('/usr/bin/security', 'list-keychains', '-d', 'user', '-s', keychain, *previous)
portable.unlink(missing_ok=True)
probe = root / 'signing-probe'
shutil.copyfile('/usr/bin/true', probe)
run('/usr/bin/codesign', '--force', '--timestamp=none', '--keychain', keychain,
    '--sign', settings['sha1'], probe)
run('/usr/bin/codesign', '--verify', '--strict', '-R',
    f'certificate leaf = H"{settings["sha1"]}"', probe)
probe.unlink()
print('Persistent preview identity imported and signing probe verified; private material omitted')
