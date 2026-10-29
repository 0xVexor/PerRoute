#!/usr/bin/env python3
"""Stage the pinned standalone routing payload in a disposable local app bundle."""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import platform
import plistlib
import shutil
import tarfile
import tempfile

from macos_preview import ENGINES, architectures, macho
from verify_macos_per_app import DIGESTS, ROOT, VERSION, verified_archive, verified_engine

REVISION = '7054cac5124465ffdb145870d7c01885566711b1'
LICENSE_SHA256 = '650d5e3b99a446fb38e820fa87a49562e0c79eab868fff58618ac487a58e554c'
IDENTITIES = {'sing-box': 'app.perroute.tun-engine', 'xray': 'app.perroute.xray'}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


@contextmanager
def payload():
    with verified_engine() as (engine, architecture):
        archive = ROOT / f'build/macos-per-app-routing/sing-box-{VERSION}-darwin-{architecture}.tar.gz'
        verified_archive(archive, architecture)
        name = f'sing-box-{VERSION}-darwin-{architecture}/LICENSE'
        with tarfile.open(archive, 'r:gz') as package:
            matches = [member for member in package.getmembers() if member.name == name]
            if len(matches) != 1 or not matches[0].isfile() or matches[0].size > 65536:
                raise ValueError('Expected one bounded upstream license')
            with package.extractfile(matches[0]) as stream:
                license_text = stream.read(65537)
        if sha256(license_text) != LICENSE_SHA256:
            raise ValueError('Pinned upstream license does not match')
        yield engine, architecture, license_text


def bundle_paths(app, architecture, inspect=architectures):
    app = Path(app)
    if architecture not in ENGINES or app.is_symlink() or not app.is_dir():
        raise ValueError('Expected an architecture-matched real app bundle')
    app = app.resolve()
    if any(app.is_relative_to(root) for root in (Path('/Applications'), Path.home() / 'Applications')):
        raise ValueError('Routing packaging must use a staging bundle, never an installed app')
    info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
    if info.get('CFBundleIdentifier') != 'app.perroute.spike' or info.get('CFBundleExecutable') != 'PerRoute':
        raise ValueError('Unexpected app identity')
    xray = app / 'Contents/app/resources/xray' / ENGINES[architecture] / 'xray'
    for path in (app / 'Contents', app / 'Contents/Library', xray):
        if path.is_symlink() or path.resolve() != path:
            raise ValueError('Routing components must not traverse symlinks')
    if not macho(xray) or inspect(xray) != {architecture}:
        raise ValueError('Expected the architecture-matched Xray executable')
    return app, xray


def stage(app, architecture=None, *, provider=payload, inspect=architectures, safari_engine=None, safari_source=None):
    architecture = architecture or platform.machine()
    app, xray = bundle_paths(app, architecture, inspect)
    destination = app / 'Contents/Library/Engines'
    if destination.exists() or destination.is_symlink():
        raise ValueError('Refusing to overwrite a routing payload')
    gpl = (ROOT / 'LICENSE').read_bytes()
    if not gpl.startswith(b'                    GNU GENERAL PUBLIC LICENSE') or b'Version 3, 29 June 2007' not in gpl:
        raise ValueError('The GPL version 3 text is required in the routing bundle')
    with provider() as (engine, upstream_architecture, license_text):
        source_manifest = None
        if safari_engine is not None:
            if safari_source is None:
                raise ValueError('Safari engine requires its exact prepared source tree')
            source_manifest = json.loads((safari_source / 'perroute-safari-source.json').read_text())
            if source_manifest.get('upstreamRevision') != REVISION:
                raise ValueError('Unexpected Safari engine source revision')
            for name, expected in source_manifest['modifiedFilesSha256'].items():
                path = safari_source / name
                if not path.resolve().is_relative_to(safari_source.resolve()) or sha256(path.read_bytes()) != expected:
                    raise ValueError('Safari engine source changed after preparation')
            engine = Path(safari_engine)
        expected_architecture = {'arm64': 'arm64', 'x86_64': 'amd64'}[architecture]
        if upstream_architecture != expected_architecture or not macho(engine) or inspect(engine) != {architecture}:
            raise ValueError('The verified routing engine does not match the app architecture')
        if sha256(license_text) != LICENSE_SHA256:
            raise ValueError('Pinned upstream license does not match')
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.routing-', dir=destination.parent) as temporary:
            staged = Path(temporary) / 'Engines'
            staged.mkdir()
            copied = staged / 'sing-box'
            shutil.copyfile(engine, copied)
            copied.chmod(0o755)
            (staged / 'sing-box.LICENSE').write_bytes(license_text)
            (staged / 'GPL-3.0.txt').write_bytes(gpl)
            provenance = {'schemaVersion': 1, 'program': 'sing-box', 'version': VERSION,
                          'revision': REVISION, 'architecture': architecture,
                          'upstreamArchiveSha256': DIGESTS[upstream_architecture],
                          'upstreamBinarySha256': sha256(copied.read_bytes()),
                          'licenseSha256': LICENSE_SHA256,
                          'source': f'https://github.com/SagerNet/sing-box/tree/{REVISION}',
                          'sourceArchive': f'https://github.com/SagerNet/sing-box/archive/{REVISION}.tar.gz',
                          'license': 'GPL-3.0-or-later with upstream naming/association restriction',
                          'modifications': 'Local code signature only; separate executable, no upstream affiliation',
                          'acceptance': 'local-development-staging-only',
                          'releaseSourceComplianceVerified': False}
            if source_manifest is not None:
                provenance.update({'modifications':'PerRoute Safari IPv4 TCP attribution and local code signature',
                    'safariProtocol':1, 'sourceBuild':source_manifest, 'sourceBuiltBinarySha256':sha256(copied.read_bytes())})
                provenance.pop('upstreamBinarySha256', None)
                # Deliver the full modified engine sources, including licenses and
                # go.mod/go.sum, beside the installer. No binary-only patch claim.
                source_archive = ROOT / 'build/macos-safari-engine/perroute-safari-engine-source.tar.gz'
                source_archive.parent.mkdir(parents=True, exist_ok=True)
                with tarfile.open(source_archive, 'w:gz') as archive:
                    archive.add(safari_source, arcname='perroute-safari-engine')
                provenance['modifiedSourceArchiveSha256'] = sha256(source_archive.read_bytes())
            (staged / 'sing-box.provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
            staged.rename(destination)
    return destination / 'sing-box', xray


def sign_payloads(paths, settings, sign, verify, *, safari=False):
    # Explicit identifiers are part of the native trust policy. Seal these before
    # their enclosing app, rather than inheriting arbitrary upstream identifiers.
    for binary in paths:
        identifier = IDENTITIES[binary.name]
        if safari and binary.name == 'sing-box':
            identifier = 'app.perroute.tun-engine.safari-v1'
        sign(binary, settings, identifier)
        requirement = f'identifier "{identifier}" and certificate leaf = H"{settings["sha1"]}"'
        verify('/usr/bin/codesign', '--verify', '--strict', '-R', '=' + requirement, binary, quiet=True)


def enable_candidate_launch(app):
    # Change only the disposable routing candidate, before its enclosing signature is sealed.
    app, _ = bundle_paths(app, platform.machine())
    config = app / 'Contents/app/PerRoute.cfg'
    if config.parent.is_symlink() or config.is_symlink() or not config.is_file():
        raise ValueError('Expected a real candidate launcher configuration')
    text = config.read_text()
    lines = text.splitlines(keepends=True)
    if sum(line.strip() == '[JavaOptions]' for line in lines) != 1:
        raise ValueError('Expected exactly one JavaOptions section')
    if 'perroute.macos.perAppCandidate' in text:
        raise ValueError('Refusing a preconfigured routing candidate flag')
    for index, line in enumerate(lines):
        if line.strip() == '[JavaOptions]':
            lines[index] = line.rstrip('\r\n') + '\n'
            lines.insert(index + 1, 'java-options=-Dperroute.macos.perAppCandidate=true\n')
            break
    config.write_text(''.join(lines))
