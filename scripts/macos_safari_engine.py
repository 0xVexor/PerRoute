#!/usr/bin/env python3
"""Prepare a pinned Safari engine source tree. Never install or run a routing engine."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = '7054cac5124465ffdb145870d7c01885566711b1'
ARCHIVE_SHA256 = '03597bdd7fb4bd3a6350c3aff98968efeb5f4baf5d840c4389284d951fc2899d'


def replace_once(root, name, before, after):
    target = root / name
    text = target.read_text()
    if text.count(before) != 1:
        raise ValueError(f'Pinned source does not match the expected patch anchor: {name}')
    target.write_text(text.replace(before, after, 1))


def stage(archive, destination):
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Expected a bounded regular source archive')
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        raise ValueError('Pinned engine source archive does not match')
    if destination.exists() or destination.is_symlink():
        raise ValueError('Refusing to replace an existing source tree')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.safari-source-', dir=destination.parent) as temporary:
        extraction = Path(temporary).resolve()
        with tarfile.open(archive, 'r:gz') as package:
            members = package.getmembers()
            if len(members) > 20000 or sum(member.size for member in members) > 256 * 1024 * 1024:
                raise ValueError('Unexpected engine source size')
            # Only directories and regular files exist in this pinned archive.
            # Extract explicitly to support the system Python without trusting
            # archive links, permissions or parent-directory traversal.
            for member in members:
                target = extraction / member.name
                if not target.resolve().is_relative_to(extraction) or not (member.isdir() or member.isfile()):
                    raise ValueError('Unsafe engine source member')
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(member) as input_file, target.open('xb') as output_file:
                        shutil.copyfileobj(input_file, output_file)
        source = extraction / f'sing-box-{REVISION}'
        replace_once(source, 'option/rule.go',
                     '\tProcessPathRegex         badoption.Listable[string]',
                     '\tSafariResponsiblePath    string `json:"safari_responsible_path,omitempty"`\n'
                     '\tProcessPathRegex         badoption.Listable[string]')
        replace_once(source, 'route/rule/rule_default.go', '\tif len(options.ProcessPath) > 0 {',
                     '\tif options.SafariResponsiblePath != "" {\n'
                     '\t\titem, err := NewSafariResponsibleItem(options.SafariResponsiblePath)\n'
                     '\t\tif err != nil { return nil, err }\n'
                     '\t\trule.items = append(rule.items, item)\n'
                     '\t\trule.allItems = append(rule.allItems, item)\n\t}\n'
                     '\tif len(options.ProcessPath) > 0 {')
        replace_once(source, 'route/rule_conds.go',
                     'func isProcessRule(rule option.DefaultRule) bool {\n\treturn ',
                     'func isProcessRule(rule option.DefaultRule) bool {\n\treturn rule.SafariResponsiblePath != "" || ')
        replace_once(source, 'common/process/searcher_darwin_shared.go',
                     '\t\towner := &adapter.ConnectionOwner{\n\t\t\tUserId: entry.uid,',
                     '\t\towner := &adapter.ConnectionOwner{\n\t\t\tProcessID: entry.pid,\n\t\t\tUserId: entry.uid,')
        overlay = ROOT / 'native/macos-safari-engine'
        for name in ('safari_responsibility_darwin.go', 'safari_responsibility_stub.go'):
            shutil.copyfile(overlay / name, source / 'common/process' / name)
        for name in ('rule_item_safari.go', 'rule_item_safari_test.go'):
            shutil.copyfile(overlay / name, source / 'route/rule' / name)
        for name in ('ResponsibleApplication.h', 'ResponsibleApplicationInternal.h',
                     'ResponsibleApplication.c', 'ResponsibleApplicationDarwin.c'):
            text = (ROOT / 'native/macos-tun' / name).read_text()
            prefix = '//go:build darwin && cgo\n\n' if name.endswith('.c') else ''
            (source / 'common/process' / name).write_text(prefix + text)
        manifest = {'upstreamRevision': REVISION, 'sourceArchiveSha256': ARCHIVE_SHA256,
                    'scope': 'Safari IPv4 TCP responsible-application rule',
                    'acceptance': 'source-candidate-only; no installed or routed Safari acceptance'}
        changed = ['option/rule.go', 'route/rule/rule_default.go', 'route/rule_conds.go',
                   'common/process/searcher_darwin_shared.go']
        changed += ['common/process/' + name for name in (
            'safari_responsibility_darwin.go', 'safari_responsibility_stub.go',
            'ResponsibleApplication.h', 'ResponsibleApplicationInternal.h',
            'ResponsibleApplication.c', 'ResponsibleApplicationDarwin.c')]
        changed += ['route/rule/' + name for name in ('rule_item_safari.go', 'rule_item_safari_test.go')]
        manifest['modifiedFilesSha256'] = {
            name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in sorted(changed)}
        (source / 'perroute-safari-source.json').write_text(json.dumps(manifest, indent=2) + '\n')
        source.rename(destination)
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(stage(args.source_archive, args.output))
