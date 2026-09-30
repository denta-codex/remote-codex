"""Offline connection-check regressions. Run with uv run --no-project tests/connection_check_test.py."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import struct
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('connection_check', ROOT / 'scripts/connection-check.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
TOKEN = 'fixture-token-that-must-never-be-logged-000000000000000'
HOME = Path('/fixture/codex')


def exact(stream, count):
    result = b''
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise EOFError()
        result += chunk
    return result


def message(stream):
    first, second = exact(stream, 2)
    length = second & 127
    if length == 126:
        length = struct.unpack('!H', exact(stream, 2))[0]
    mask = exact(stream, 4)
    payload = exact(stream, length)
    return first & 15, json.loads(bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))


def frame(stream, payload, opcode=1, final=True):
    if len(payload) >= 126:
        length = bytes([126]) + struct.pack('!H', len(payload))
    else:
        length = bytes([len(payload)])
    stream.sendall(bytes([(128 if final else 0) | opcode]) + length + payload)


@contextlib.contextmanager
def fixture(status=101, rpc_error=None, wrong_home=False, stall=False, invalid_accept=False):
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen()
    listener.settimeout(5)
    observed = []
    errors = []

    def serve():
        try:
            stream, _ = listener.accept()
            with stream:
                stream.settimeout(3)
                head = b''
                while b'\r\n\r\n' not in head:
                    head += stream.recv(2048)
                key = next(line.split(b':', 1)[1].strip() for line in head.split(b'\r\n')
                           if line.lower().startswith(b'sec-websocket-key:'))
                accept = base64.b64encode(hashlib.sha1(key + b'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest())
                if invalid_accept:
                    accept = b'invalid'
                stream.sendall(b'HTTP/1.1 ' + str(status).encode() + b' Fixture\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + b'\r\n\r\n')
                if status != 101 or invalid_accept:
                    return
                while True:
                    try:
                        _, request = message(stream)
                    except EOFError:
                        return
                    method = request['method']
                    observed.append(method)
                    if method == 'initialized':
                        continue
                    if stall:
                        try:
                            stream.recv(1)
                        except TimeoutError:
                            pass
                        return
                    if method == rpc_error:
                        response = {'id': request['id'], 'error': {'code': -32000, 'message': TOKEN}}
                    else:
                        result = {'codexHome': '/wrong' if wrong_home else str(HOME)} if method == 'initialize' else {'data': []}
                        response = {'id': request['id'], 'result': result}
                    payload = json.dumps(response).encode()
                    # Ensure the checker handles WebSocket continuation frames.
                    split = len(payload) // 2
                    frame(stream, payload[:split], final=False)
                    frame(stream, payload[split:], opcode=0)
                    if rpc_error == method or wrong_home or method == 'thread/list':
                        return
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            errors.append(error)

    thread = threading.Thread(target=serve)
    thread.start()
    try:
        yield f'ws://127.0.0.1:{listener.getsockname()[1]}/codex/rpc', observed
    finally:
        thread.join(5)
        listener.close()
        if thread.is_alive() or errors:
            raise AssertionError('fixture failed')


class ConnectionCheckTests(unittest.TestCase):
    def test_empty_account_and_fragmented_results_pass_without_mutations(self):
        with fixture() as (url, observed):
            self.assertTrue(checker.check(url, TOKEN, HOME)['ok'])
        self.assertEqual(observed, ['initialize', 'initialized', 'project/list', 'thread/list'])

    def test_rejects_authentication_and_upstream_failures(self):
        for status in (401, 502):
            with self.subTest(status=status), fixture(status=status) as (url, _):
                with self.assertRaises(checker.CheckFailure) as caught:
                    checker.check(url, TOKEN, HOME)
                self.assertEqual((caught.exception.stage, caught.exception.code), ('upgrade', status))

    def test_rejects_bad_upgrade_and_wrong_account(self):
        for options, stage in [({'invalid_accept': True}, 'upgrade'), ({'wrong_home': True}, 'account_identity')]:
            with self.subTest(stage=stage), fixture(**options) as (url, _):
                with self.assertRaises(checker.CheckFailure) as caught:
                    checker.check(url, TOKEN, HOME)
                self.assertEqual(caught.exception.stage, stage)

    def test_rpc_failures_print_only_codes(self):
        for method in ('initialize', 'project/list', 'thread/list'):
            with self.subTest(method=method), fixture(rpc_error=method) as (url, _):
                output = io.StringIO()
                with patch('sys.argv', ['connection-check.py', '--url', url, '--expected-home', str(HOME)]), patch.object(checker, 'credential', return_value=TOKEN), contextlib.redirect_stdout(output):
                    self.assertEqual(checker.main(), 1)
                self.assertNotIn(TOKEN, output.getvalue())
                self.assertEqual(json.loads(output.getvalue()), {'ok': False, 'stage': method, 'code': -32000})

    def test_timeout_and_insecure_endpoints_fail(self):
        with fixture(stall=True) as (url, _):
            with self.assertRaises((checker.CheckFailure, TimeoutError)):
                checker.check(url, TOKEN, HOME, timeout=0.2)
        for url in ('ws://example.invalid/codex/rpc', 'wss://user:secret@example.invalid/codex/rpc',
                    'wss://example.invalid/codex/rpc?token=secret'):
            with self.subTest(url=url), self.assertRaises(checker.CheckFailure):
                checker.WebSocket(url, TOKEN)


if __name__ == '__main__':
    unittest.main()
