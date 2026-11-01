#!/usr/bin/env python3
"""Validate the native capture fixture with the pinned parser. Never start a TUN."""
import argparse
import json
from pathlib import Path
import subprocess
from verify_macos_per_app import VERSION, verified_engine


def check(config):
    if config.is_symlink() or not config.is_file() or config.stat().st_size > 65536:
        raise ValueError('Expected bounded native capture fixture')
    candidate = json.loads(config.read_text())
    inbound = candidate.get('inbounds')
    expected = {'type': 'tun', 'tag': 'perroute-tun-spike', 'address': ['172.31.255.1/30'],
                'auto_route': True, 'interface_name': 'utun877'}
    if set(candidate) != {'log', 'inbounds', 'outbounds', 'route'} or inbound != [expected]:
        raise ValueError('Unexpected native capture fixture')
    route = candidate['route']
    if route.get('final') != 'unmatched-direct' or route.get('auto_detect_interface') is not True:
        raise ValueError('Missing native loop-prevention/direct fallback policy')
    rules = route.get('rules', [])
    if len(rules) != 3 or any(rule.get('user_id') != [501] for rule in rules[1:]):
        raise ValueError('Expected the fixture user scope on both selected rules')
    outbounds = candidate.get('outbounds', [])
    if len(outbounds) != 3 or outbounds[0] != {'type': 'direct', 'tag': 'unmatched-direct'}:
        raise ValueError('Unexpected fixture outbounds')
    for outbound in outbounds[1:]:
        if (set(outbound) != {'type', 'tag', 'server', 'server_port', 'version'} or
                outbound['type'] != 'socks' or outbound['server'] != '127.0.0.1' or outbound['version'] != '5'):
            raise ValueError('Expected only local SOCKS outbounds')
    with verified_engine() as (engine, architecture):
        subprocess.run([str(engine), 'version'], check=True, timeout=15)
        subprocess.run([str(engine), 'check', '-c', str(config.resolve())], check=True, timeout=30)
    print(f'Pinned sing-box {VERSION} {architecture}: native capture fixture accepted; no activation or host changes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    check(parser.parse_args().config)
