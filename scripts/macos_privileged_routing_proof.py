"""One-shot administrator comparison for the owned loopback fixture, never an installer."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import tempfile
import time

SOURCE = Path(__file__).resolve().parents[1] / 'native/macos-routing-proof/PrivilegedProof.c'


def digest(path):
    with path.open('rb') as contents:
        return hashlib.file_digest(contents, 'sha256').hexdigest()


def prepare_launcher(engine, config, directory):
    header = directory / 'ProofPins.h'
    pins = {'PROOF_ENGINE': str(engine.resolve(strict=True)), 'PROOF_ENGINE_HASH': digest(engine),
            'PROOF_CONFIG': str(config.resolve(strict=True)), 'PROOF_CONFIG_HASH': digest(config)}
    header.write_text(''.join(f'#define {key} {json.dumps(value)}\n' for key, value in pins.items()) +
                      f'#define PROOF_UID {os.getuid()}\n')
    executable = directory / 'PrivilegedProof'
    subprocess.run(['xcrun', 'clang', '-Wall', '-Wextra', '-Werror', '-Wno-deprecated-declarations',
                    '-mmacosx-version-min=13.0', '-I', str(directory), str(SOURCE), '-o', str(executable)],
                   check=True, timeout=30)
    subprocess.run([str(executable), '--verify-only'], check=True, timeout=5)
    if os.geteuid() != 0:
        refusal = subprocess.run([str(executable)], capture_output=True, timeout=5)
        if refusal.returncode != 77:
            raise RuntimeError('One-shot launcher did not refuse unprivileged execution')
    return executable


@contextmanager
def privileged_engine(engine, config, port, prepare_only=False):
    subprocess.run([str(engine), 'check', '-c', str(config)], check=True, timeout=15)
    with tempfile.TemporaryDirectory(prefix='perroute-root-comparison-') as temporary:
        executable = prepare_launcher(engine, config, Path(temporary))
        if prepare_only:
            print('Privileged comparison prepared and pins checked; no authorization or root execution', flush=True)
            yield False
            return
        # Only this exact, freshly compiled no-argument launcher is approved. It hashes/copies
        # fixed payload bytes to root-owned private staging and kills its engine after 20 seconds.
        script = 'do shell script ' + json.dumps(shlex.quote(str(executable))) + ' with administrator privileges'
        process = subprocess.Popen(['/usr/bin/osascript', '-e', script], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        started = False
        print('Approve the macOS administrator dialog for the one-shot loopback comparison.', flush=True)
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    output, error = process.communicate()
                    raise RuntimeError(f'Privileged comparison did not start: {output.strip()} {error.strip()}')
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                        started = True
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError('Administrator approval/startup timed out')
            yield True
        finally:
            if started:
                # An ordinary user cannot kill a root child. The fixed root supervisor owns
                # termination even when a request assertion fails or this Python process exits.
                try:
                    output, error = process.communicate(timeout=25)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=5)
                    raise RuntimeError('Root supervisor completion was not observed; do not claim cleanup')
                if process.returncode:
                    raise RuntimeError(f'Privileged supervisor failed: {error.strip()}')
                if 'root staging removed' not in output:
                    raise RuntimeError('Root cleanup confirmation missing')
                print(output.strip(), flush=True)
            elif process.poll() is None:
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=5)
