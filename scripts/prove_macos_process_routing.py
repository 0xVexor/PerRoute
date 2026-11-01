#!/usr/bin/env python3
"""Opt-in native process routing proof. Opens loopback TCP only, never TUN or host routes."""
import argparse
from contextlib import contextmanager, ExitStack
import json
from pathlib import Path
import queue
import shutil
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time

from verify_macos_per_app import verified_engine

REQUEST = b'perroute-process-proof\n'
RESPONSE = b'perroute-proof-ok\n'


def receive(connection, size):
    result = b''
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise EOFError('Incomplete fixture transaction')
        result += chunk
    return result


class FixtureServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, handler):
        self.events = queue.Queue()
        self.failures = queue.Queue()
        self.available = True
        super().__init__(('127.0.0.1', 0), handler)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.server_address[1]

    def __exit__(self, *args):
        self.shutdown()
        self.server_close()
        self.thread.join(timeout=3)


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(3)
        try:
            # Readiness probes open then close; they are not successful test traffic.
            first = self.request.recv(1)
            if not first:
                return
            if first + receive(self.request, len(REQUEST) - 1) != REQUEST:
                raise ValueError('Unexpected synthetic payload')
            self.server.events.put('echo')
            self.request.sendall(RESPONSE)
        except (OSError, EOFError, ValueError) as error:
            self.server.failures.put(type(error).__name__)


class SocksRelay(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(3)
        try:
            if not self.server.available:
                return
            if receive(self.request, 3) != b'\x05\x01\x00':
                raise ValueError('Expected SOCKS5 without authentication')
            self.request.sendall(b'\x05\x00')
            if receive(self.request, 4) != b'\x05\x01\x00\x01':
                raise ValueError('Only a literal IPv4 CONNECT is permitted')
            address = socket.inet_ntoa(receive(self.request, 4))
            port = struct.unpack('!H', receive(self.request, 2))[0]
            if address != '127.0.0.1' or port != self.server.destination:
                raise ValueError('Refuse any destination outside the owned echo fixture')
            with socket.create_connection((address, port), timeout=3) as upstream:
                self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                payload = receive(self.request, len(REQUEST))
                if payload != REQUEST:
                    raise ValueError('Unexpected relay payload')
                upstream.sendall(payload)
                reply = receive(upstream, len(RESPONSE))
                if reply != RESPONSE:
                    raise ValueError('Unexpected destination response')
                self.server.events.put('relay')
                self.request.sendall(reply)
        except (OSError, EOFError, ValueError) as error:
            self.server.failures.put(type(error).__name__)


@contextmanager
def running_engine(engine, config, port):
    subprocess.run([str(engine), 'check', '-c', str(config)], check=True, timeout=15)
    process = subprocess.Popen([str(engine), 'run', '-c', str(config)],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'Loopback engine exited before readiness: {process.returncode}')
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.05)
        else:
            raise TimeoutError('Loopback engine did not become ready')
        yield
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def assert_loopback_fixture(config, inbound_port, destination_port, relay_ports):
    """Refuse a generator regression before invoking the native engine's run command."""
    expected = {'type': 'direct', 'tag': 'perroute-tun-spike', 'listen': '127.0.0.1',
                'listen_port': inbound_port, 'network': 'tcp',
                'override_address': '127.0.0.1', 'override_port': destination_port}
    if config.get('inbounds') != [expected] or set(config) != {'log', 'inbounds', 'outbounds', 'route'}:
        raise ValueError('Only the fixed loopback direct inbound may run in this proof')
    if config['log'] != {'disabled': True}:
        raise ValueError('Process-path logs must remain disabled')
    expected_outbounds = [{'type': 'direct', 'tag': 'unmatched-direct'}] + [
        {'type': 'socks', 'tag': f'xray-loopback-{port}', 'server': '127.0.0.1',
         'server_port': port, 'version': '5'} for port in sorted(relay_ports)]
    if config['outbounds'] != expected_outbounds:
        raise ValueError('Expected only the two owned SOCKS fixtures and direct fallback')
    # No DNS, API, download, route activation or other top-level feature can enter this config.
    if (set(config['route']) != {'auto_detect_interface', 'rules', 'final'} or
            config['route']['auto_detect_interface'] is not True or config['route']['final'] != 'unmatched-direct'):
        raise ValueError('Unexpected route settings')
    rules = config['route']['rules']
    if not isinstance(rules, list) or not 1 <= len(rules) <= 257:
        raise ValueError('Expected bounded exact process routing rules')
    for index, rule in enumerate(rules):
        keys = {'process_path', 'action', 'outbound'} | ({'inbound', 'network'} if index else set())
        if set(rule) != keys or rule['action'] != 'route':
            raise ValueError('Only generated exact process route actions are allowed')
        if not isinstance(rule['process_path'], list) or not 1 <= len(rule['process_path']) <= 256:
            raise ValueError('Expected bounded explicit executable paths')
        if any(not isinstance(path, str) or not path.startswith('/') for path in rule['process_path']):
            raise ValueError('Expected absolute executable paths')
        if not index:
            if rule['outbound'] != 'unmatched-direct':
                raise ValueError('Engine exclusion must be direct')
        elif (rule['inbound'] != ['perroute-tun-spike'] or rule['network'] != 'tcp' or
              rule['outbound'] not in {f'xray-loopback-{port}' for port in relay_ports}):
            raise ValueError('Expected selected TCP loopback endpoint rules')


def prove_owned_client(client, probe):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        listener.settimeout(5)
        process = subprocess.Popen([str(client), str(listener.getsockname()[1])],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            connection, source = listener.accept()
            with connection:
                connection.settimeout(3)
                if receive(connection, len(REQUEST)) != REQUEST:
                    raise AssertionError('Unexpected native client payload')
                arguments = [str(probe), str(process.pid), str(source[1]), str(listener.getsockname()[1]), str(client)]
                subprocess.run(arguments, check=True, timeout=3)
                wrong_path = subprocess.run(arguments[:-1] + [str(client) + '-unselected'], capture_output=True, timeout=3)
                wrong_tuple = subprocess.run(arguments[:3] + ['1'] + arguments[4:], capture_output=True, timeout=3)
                if wrong_path.returncode != 4 or wrong_tuple.returncode != 4:
                    raise AssertionError('Native probe accepted an unrelated tuple or executable')
                connection.sendall(RESPONSE)
            if process.wait(timeout=3) != 0:
                raise AssertionError('Owned native client did not complete')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
    print('Native attribution: exact owned tuple/path accepted; wrong tuple and path refused', flush=True)


def prove(java, classpath, client, probe, native_only=False, privileged=False, prepare_privileged=False):
    prove_owned_client(client, probe)
    if native_only:
        return
    with verified_engine() as (engine, architecture), ExitStack() as resources:
        staging = Path(resources.enter_context(tempfile.TemporaryDirectory(prefix='process-proof-'))).resolve()
        paths = {
            'selected-a': 'Selected.app/Contents/MacOS/Client',
            'selected-b': 'Other.app/Contents/MacOS/Client',
            'unselected': 'Unselected.app/Contents/MacOS/Client',
            'helper': 'Selected.app/Contents/Frameworks/Worker.app/Contents/MacOS/Client',
            'excluded': 'engine/Client',
        }
        for path in paths.values():
            destination = staging / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(client, destination)
            destination.chmod(0o500)
        echo = resources.enter_context(FixtureServer(Echo))
        relays = [resources.enter_context(FixtureServer(SocksRelay)) for _ in range(2)]
        for relay in relays:
            relay.destination = echo.port
        checks = 0
        for phase in (('main',) if privileged or prepare_privileged else ('main', 'helper')):
            # Reserve an ephemeral port until the engine is about to bind. A racing bind causes
            # an explicit startup failure, never reuse of the installed app's fixed ports.
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
                config_path = staging / f'{phase}.json'
                subprocess.run([java, '-cp', classpath, 'app.perroute.MacosProcessRoutingFixture',
                                str(staging), str(engine), str(relays[0].port), str(relays[1].port),
                                str(port), str(echo.port), 'loopback-only', phase, str(config_path)],
                               check=True, timeout=20)
                assert_loopback_fixture(json.loads(config_path.read_text()), port, echo.port,
                                        [relay.port for relay in relays])
            if privileged or prepare_privileged:
                from macos_privileged_routing_proof import privileged_engine
                runtime = privileged_engine(engine, config_path, port, prepare_privileged)
            else:
                runtime = running_engine(engine, config_path, port)
            with runtime:
                if prepare_privileged:
                    return
                def request(label, expected_relay, succeeds=True):
                    nonlocal checks
                    before = [relay.events.qsize() for relay in relays]
                    echoes = echo.events.qsize()
                    result = subprocess.run([str(staging / paths[label]), str(port)],
                                            capture_output=True, timeout=8)
                    if (result.returncode == 0) != succeeds:
                        raise AssertionError(f'{phase}/{label}: native forwarding result was {result.returncode}')
                    expected = list(before)
                    if succeeds and expected_relay is not None:
                        expected[expected_relay] += 1
                    if [relay.events.qsize() for relay in relays] != expected:
                        raise AssertionError(f'{phase}/{label}: attribution gate failed; '
                                             f'expected SOCKS counts {expected}, observed '
                                             f'{[relay.events.qsize() for relay in relays]}; '
                                             f'destination request delta {echo.events.qsize() - echoes}. '
                                             'Do not enable per-app mode from parser acceptance alone.')
                    if echo.events.qsize() != echoes + int(succeeds):
                        raise AssertionError(f'{phase}/{label}: destination did not receive exactly the expected request')
                    if any(server.failures.qsize() for server in [echo, *relays]):
                        raise AssertionError('Controlled server reported a protocol failure')
                    checks += 1
                    print(f'PASS {phase}/{label}: ' + ('refused' if not succeeds else
                          ('direct' if expected_relay is None else f'SOCKS endpoint {expected_relay + 1}')))
                for _ in range(3):
                    request('selected-a', 0)
                    request('selected-b', 1)
                    request('unselected', None)
                    request('helper', 1 if phase == 'helper' else None)
                    request('excluded', None)
                relays[0].available = False
                request('selected-a', None, succeeds=False)
                request('unselected', None)
                relays[0].available = True
                request('selected-a', 0)
        print(f'Native macOS {architecture} process routing: {checks} controlled requests passed; '
              f'privileged={privileged}; phases=' + ('main only' if privileged else 'main and helper'))
        print('No TUN, host routes, DNS, system proxy, helper service or installed app was changed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--client', type=Path, required=True)
    parser.add_argument('--probe', type=Path, required=True)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--native-only', action='store_true')
    modes.add_argument('--privileged', action='store_true')
    modes.add_argument('--prepare-privileged', action='store_true')
    arguments = parser.parse_args()
    prove(arguments.java, arguments.classpath, arguments.client.resolve(strict=True),
          arguments.probe.resolve(strict=True), arguments.native_only, arguments.privileged, arguments.prepare_privileged)
