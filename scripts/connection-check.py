"""Read-only Remote Codex acceptance; credentials and RPC content never reach output.

Run with the existing managed toolchain: uv run --no-project scripts/connection-check.py.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import struct
import subprocess
import time
from urllib.parse import urlsplit, urlunsplit

LIMIT = 16 * 1024 * 1024


class CheckFailure(Exception):
    def __init__(self, stage, code=None):
        self.stage, self.code = stage, code
        super().__init__(stage)


class WebSocket:
    def __init__(self, url, token, timeout=30):
        self.path = urlsplit(url).path
        self.stage = 'connect'
        self.stream = None
        self.buffer = bytearray()
        self.deadline = time.monotonic() + timeout
        endpoint = urlsplit(url)
        if (endpoint.scheme not in ('ws', 'wss') or not endpoint.hostname
                or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
                or endpoint.path not in ('/codex/rpc', '/remote-codex/v1/todo')
                or endpoint.scheme == 'ws' and endpoint.hostname != '127.0.0.1'):
            raise CheckFailure('endpoint')
        try:
            self.stream = socket.create_connection(
                (endpoint.hostname, endpoint.port or (443 if endpoint.scheme == 'wss' else 80)), timeout)
            if endpoint.scheme == 'wss':
                self.stream = ssl.create_default_context().wrap_socket(
                    self.stream, server_hostname=endpoint.hostname)
            self.stage = 'upgrade'
            key = base64.b64encode(os.urandom(16)).decode()
            request = (f'GET {endpoint.path} HTTP/1.1\r\nHost: {endpoint.netloc}\r\n'
                       f'Authorization: Bearer {token}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                       f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
            self.stream.sendall(request.encode())
            while b'\r\n\r\n' not in self.buffer:
                self.buffer.extend(self.read(2048))
                if len(self.buffer) > 16 * 1024:
                    raise CheckFailure(self.stage)
            head, tail = bytes(self.buffer).split(b'\r\n\r\n', 1)
            self.buffer = bytearray(tail)
            lines = head.decode('ascii').split('\r\n')
            status = int(lines[0].split()[1])
            if status != 101:
                raise CheckFailure(self.stage, status)
            headers = {}
            for line in lines[1:]:
                name, value = line.split(':', 1)
                name = name.lower()
                if name in headers:
                    raise CheckFailure(self.stage)
                headers[name] = value.strip()
            expected = base64.b64encode(hashlib.sha1(
                (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            if (headers.get('sec-websocket-accept') != expected
                    or headers.get('upgrade', '').lower() != 'websocket'
                    or 'upgrade' not in [value.strip().lower() for value in headers.get('connection', '').split(',')]):
                raise CheckFailure(self.stage)
        except BaseException:
            self.close()
            raise

    def read(self, count):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise CheckFailure(self.stage)
        self.stream.settimeout(remaining)
        data = self.stream.recv(count)
        if not data:
            raise CheckFailure(self.stage)
        return data

    def exact(self, count):
        while len(self.buffer) < count:
            self.buffer.extend(self.read(min(65536, count - len(self.buffer))))
        result = bytes(self.buffer[:count])
        del self.buffer[:count]
        return result

    def send(self, payload, opcode=1):
        if len(payload) >= 126:
            length = bytes([0x80 | 126]) + struct.pack('!H', len(payload))
        else:
            length = bytes([0x80 | len(payload)])
        mask = os.urandom(4)
        self.stream.sendall(bytes([0x80 | opcode]) + length + mask
                            + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))

    def receive(self):
        message = bytearray()
        started = False
        while True:
            first, second = self.exact(2)
            opcode = first & 15
            if first & 0x70 or second & 0x80:
                raise CheckFailure(self.stage)
            length = second & 127
            if length == 126:
                length = struct.unpack('!H', self.exact(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', self.exact(8))[0]
            if length + len(message) > LIMIT:
                raise CheckFailure(self.stage)
            payload = self.exact(length)
            if opcode in (8, 9, 10):
                if not first & 0x80 or length > 125:
                    raise CheckFailure(self.stage)
                if opcode == 8:
                    raise CheckFailure(self.stage)
                if opcode == 9:
                    self.send(payload, 10)
                continue
            if opcode == 1 and not started:
                started = True
            elif opcode != 0 or not started:
                raise CheckFailure(self.stage)
            message.extend(payload)
            if first & 0x80:
                return json.loads(message)

    def call(self, identifier, method, params):
        self.stage = method
        request = {'id': identifier, 'method': method, 'params': params}
        if self.path == '/remote-codex/v1/todo':
            request['jsonrpc'] = '2.0'
        self.send(json.dumps(request).encode())
        while True:
            response = self.receive()
            if not isinstance(response, dict):
                raise CheckFailure(method)
            if response.get('id') == identifier:
                if 'error' in response:
                    error = response['error']
                    code = error.get('code') if isinstance(error, dict) else None
                    raise CheckFailure(method, code if isinstance(code, int) else None)
                result = response.get('result')
                if not isinstance(result, dict):
                    raise CheckFailure(method)
                return result

    def close(self):
        if self.stream:
            self.stream.close()


def check(url, token, expected_home, timeout=30):
    ws = WebSocket(url, token, timeout)
    try:
        initialized = ws.call(1, 'initialize', {
            'clientInfo': {'name': 'remote_codex_connection_check', 'version': '1'},
            'capabilities': {'experimentalApi': True},
        })
        if initialized.get('codexHome') != str(expected_home):
            raise CheckFailure('account_identity')
        ws.send(b'{"method":"initialized"}')
        for identifier, method in [(2, 'project/list'), (3, 'thread/list')]:
            result = ws.call(identifier, method, {'limit': 1})
            if not isinstance(result.get('data'), list):
                raise CheckFailure(method)
        return {'ok': True, 'upgrade': 101, 'initialize': True, 'projects': True, 'tasks': True}
    finally:
        ws.close()


def check_todo(url, token, timeout=30):
    endpoint = urlsplit(url)
    todo_url = urlunsplit(endpoint._replace(path='/remote-codex/v1/todo'))
    ws = WebSocket(todo_url, token, timeout)
    try:
        result = ws.call('todo-check', 'todo/list', {})
        if not isinstance(result.get('tasks'), list):
            raise CheckFailure('todo/list')
        return {'todo': True}
    finally:
        ws.close()


def credential(path):

    result = subprocess.run(['systemd-creds', 'decrypt', '--user', '--name=connection-token',
                             str(path), '-'], capture_output=True, timeout=10)
    token = result.stdout.decode().strip()
    if result.returncode or len(token) < 43 or any(value.isspace() for value in token):
        raise CheckFailure('credential')
    return token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--todo', action='store_true', help='Also verify the application Todo endpoint')
    parser.add_argument('--url', default='wss://grace.taila198f.ts.net/codex/rpc')
    parser.add_argument('--credential', type=Path,
                        default=Path('/home/agent/.local/share/remote-codex/connection-token.cred'))
    parser.add_argument('--expected-home', type=Path, default=Path('/home/agent/.codex'))
    args = parser.parse_args()
    try:
        token = credential(args.credential)
        result = check(args.url, token, args.expected_home)
        if args.todo:
            result.update(check_todo(args.url, token))
    except CheckFailure as error:
        print(json.dumps({'ok': False, 'stage': error.stage, 'code': error.code}))
        return 1
    except (OSError, ValueError, subprocess.SubprocessError):
        # Raw exception messages can contain URLs, headers or server data.
        print(json.dumps({'ok': False, 'stage': 'transport_or_protocol'}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
