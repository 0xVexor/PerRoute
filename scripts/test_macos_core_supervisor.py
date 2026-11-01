"""Owned disposable children only. Does not launch PerRoute or change networking."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import unittest

SUPERVISOR = str(Path(sys.argv.pop(1)).resolve())
FIXTURE = str(Path(sys.argv.pop(1)).resolve())


def gone(pid, timeout=6):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        time.sleep(.03)
    return False


class SupervisorTests(unittest.TestCase):
    def start(self, mode='wait'):
        owner = subprocess.Popen([sys.executable, '-c', '''
import json, subprocess, sys
p = subprocess.Popen(sys.argv[1:], stdout=subprocess.PIPE, text=True)
line = p.stdout.readline().strip()
assert p.stdout.readline().strip() == 'fixture ready'
print(json.dumps({'supervisor': p.pid, 'child': int(line.split()[1])}), flush=True)
p.wait()
''', SUPERVISOR, FIXTURE, mode], stdout=subprocess.PIPE, text=True)
        identities = json.loads(owner.stdout.readline())
        self.addCleanup(self.cleanup, owner, identities)
        return owner, identities

    def cleanup(self, owner, identities):
        if owner.poll() is None:
            owner.terminate()
            owner.wait(timeout=5)
        # Parent death must make the supervisor reap its own child.
        self.assertTrue(gone(identities['child']))
        self.assertTrue(gone(identities['supervisor']))
        owner.stdout.close()

    def test_parent_crash_reaps_exact_child(self):
        sentinel = subprocess.Popen(['/bin/sleep', '60'])
        try:
            owner, ids = self.start()
            owner.kill()
            owner.wait(timeout=5)
            self.assertTrue(gone(ids['child']))
            self.assertTrue(gone(ids['supervisor']))
            self.assertIsNone(sentinel.poll())
        finally:
            sentinel.terminate()
            sentinel.wait(timeout=5)

    def test_parent_crash_forces_stubborn_child_after_grace(self):
        owner, ids = self.start('ignore-term')
        started = time.monotonic()
        owner.kill()
        owner.wait(timeout=5)
        self.assertTrue(gone(ids['child']))
        self.assertTrue(gone(ids['supervisor']))
        self.assertGreaterEqual(time.monotonic() - started, 1.8)

    def test_supervisor_termination_reaps_child(self):
        owner, ids = self.start()
        os.kill(ids['supervisor'], signal.SIGTERM)
        owner.wait(timeout=5)
        self.assertTrue(gone(ids['child']))
        self.assertTrue(gone(ids['supervisor']))

    def test_child_exit_status_and_ordered_output(self):
        p = subprocess.Popen([SUPERVISOR, FIXTURE, 'exit'], stdout=subprocess.PIPE, text=True)
        output = p.communicate(timeout=5)[0].splitlines()
        self.assertEqual(p.returncode, 42)
        self.assertRegex(output[0], r'^PRCORE1 [1-9][0-9]*$')
        self.assertEqual(output[1:], ['fixture output'])

    def test_exec_failure_is_reaped(self):
        p = subprocess.run([SUPERVISOR, '/nonexistent/perroute-test-child'], capture_output=True, text=True, timeout=5)
        self.assertEqual(p.returncode, 127)
        pid = int(p.stdout.split()[1])
        self.assertTrue(gone(pid))

    def test_invalid_invocation(self):
        self.assertEqual(subprocess.run([SUPERVISOR], capture_output=True, timeout=5).returncode, 78)


if __name__ == '__main__':
    unittest.main()
