#!/usr/bin/env python3
"""Build the Safari preview PKG without installing it or running acceptance tests."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

from macos_safari_engine import ROOT, REVISION, ARCHIVE_SHA256, stage
from macos_local_helper import build
from macos_pkg import package


def assemble(app, previous=None):
    if subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain'], text=True).strip():
        raise SystemExit('Commit source changes before building an identifiable installer')
    cache = ROOT / 'build/macos-safari-engine'
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / 'upstream.tar.gz'
    if not archive.exists():
        url = f'https://codeload.github.com/SagerNet/sing-box/tar.gz/{REVISION}'
        with urllib.request.urlopen(url, timeout=60) as response, tempfile.NamedTemporaryFile(dir=cache, delete=False) as out:
            temporary = Path(out.name)
            try:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 64 * 1024 * 1024:
                        raise ValueError('Source archive exceeds the pinned size bound')
                    out.write(chunk)
                out.flush()
                if hashlib.sha256(temporary.read_bytes()).hexdigest() != ARCHIVE_SHA256:
                    raise ValueError('Engine source digest mismatch')
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='.installer-build-', dir=cache) as temporary:
        work = Path(temporary)
        source = stage(archive, work / 'source')
        engine = work / 'sing-box'
        subprocess.run(['go', 'build', '-trimpath', '-buildvcs=false', '-o', str(engine.resolve()), './cmd/sing-box'], cwd=source, check=True)
        build(app, routing=True, skip_tests=True, signing_state=ROOT / '.perroute-local-signing',
              previous_helper_recovery=previous, safari_engine=engine, safari_source=source)
    output = ROOT / 'build/macos-safari-pkg'
    package(ROOT / 'build/macos-routing-helper/PerRoute.app', output, previous)
    metadata = json.loads((output / 'BUILD.json').read_text())
    identity = metadata['buildIdentity']
    source_name = f'PerRoute-Safari-engine-source-{identity["buildId"]}.tar.gz'
    shutil.copyfile(cache / 'perroute-safari-engine-source.tar.gz', output / source_name)
    collector = output / 'Collect Safari Logs.command'
    collector.write_text('''#!/bin/sh
set -eu
umask 077
report=$(/usr/bin/mktemp "$HOME/Desktop/PerRoute-Safari-Logs.XXXXXX")
{
  echo 'PerRoute Safari diagnostic report'
  echo 'PACKAGE_BUILD_IDENTITY'
  /usr/bin/sw_vers
  /usr/bin/uname -m
  echo 'Installed app build:'
  /bin/cat /Applications/PerRoute.app/Contents/Resources/perroute-build.json 2>/dev/null || true
  echo '\nSafari attribution events from the last 20 minutes:'
  /usr/bin/log show --last 20m --style compact --info --predicate 'subsystem == "app.perroute.safari"' 2>&1 || true
} > "$report"
/usr/bin/pbcopy < "$report"
echo "Logs copied to the clipboard and saved to $report"
echo 'Paste these logs and Settings > Activity log > Copy all into the chat.'
'''.replace('PACKAGE_BUILD_IDENTITY', identity['buildId']))
    collector.chmod(0o755)
    (output / 'SAFARI-TEST.txt').write_text(f'''PerRoute {identity['version']} ({identity['buildId']})

1. Disconnect and Quit the current PerRoute. Install this PKG into Applications.
2. Open PerRoute > Settings and confirm this build number/revision.
3. In Smart per-app, select Safari and assign its server. Connect.
4. Try Safari alongside another WebKit application that is not selected.
5. Check Safari's exit address, traffic counter and normal browsing. Disconnect
   and confirm ordinary browsing returns. Report any failure or unexpected route.
6. After reproducing, double-click Collect Safari Logs.command. It copies a
   report to the clipboard and saves it on your Desktop. Paste it into the chat.
   Also paste Settings > Activity log > Copy all. The attribution report records
   build identity, macOS version, PIDs, UID and bounded outcome counters. It does
   not record destinations, visited URLs, page content or server credentials.

This is an unsigned, unnotarized local preview. Safari IPv4 TCP attribution is
implemented for acceptance; final installation/isolation tests have not run.
UDP/QUIC, IPv6, comprehensive DNS leak protection and kill switch remain outside
this feature. A failed ownership lookup does not imply fail-closed protection.
The modified engine's complete source is included in {source_name}.
''')
    files = [path for path in output.iterdir() if path.is_file() and path.name != 'SHA256SUMS']
    (output / 'SHA256SUMS').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n'
        for path in sorted(files)))
    print(f'Safari installer ready: {output}; no installation or live acceptance test performed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--previous-helper-recovery', type=Path)
    args = parser.parse_args()
    assemble(args.app, args.previous_helper_recovery)
