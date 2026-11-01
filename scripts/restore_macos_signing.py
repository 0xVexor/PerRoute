#!/usr/bin/env python3
"""Restore the approved private preview identity in an ephemeral CI workspace."""
import base64
import io
import os
from pathlib import Path
import tarfile

root = Path(__file__).resolve().parents[1] / '.perroute-local-signing'
data = os.environ['MACOS_SIGNING_STATE']
if not data:
    raise SystemExit('MACOS_SIGNING_STATE secret is required; never rotate a shipped helper identity implicitly')
root.mkdir(mode=0o700)
expected = {'identity.json', 'identity.keychain-db', 'certificate.der'}
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(data, validate=True)), mode='r:gz') as archive:
    members = archive.getmembers()
    if {m.name for m in members} != expected or len(members) != len(expected):
        raise SystemExit('Unexpected signing-state members')
    for member in members:
        if not member.isfile() or member.size > 1024 * 1024:
            raise SystemExit('Invalid signing-state member')
        with (root / member.name).open('xb') as output:
            os.chmod(output.name, 0o600)
            output.write(archive.extractfile(member).read())
print('Restored persistent preview identity without displaying private material')
