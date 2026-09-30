"""Check a candidate stock Codex with the installed forwarder's actual systemd sandbox.

Only isolated units, an isolated Codex home and a synthetic credential are used.
Run with uv run --no-project scripts/candidate-check.py --codex-binary /absolute/bin/codex.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import uuid

spec = importlib.util.spec_from_file_location('connection_check', Path(__file__).with_name('connection-check.py'))
connection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connection)


class CandidateFailure(Exception):
    pass


def command(args, environment, timeout=20, check=True):
    result = subprocess.run(args, env=environment, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise CandidateFailure('isolated_service_command')
    return result


def wait_for(predicate, stage):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise CandidateFailure(stage)


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.2):
            return True
    except OSError:
        return False


def socket_identity(alias):
    try:
        info = alias.stat()
        return info.st_ino, info.st_mtime_ns, info.st_ctime_ns
    except FileNotFoundError:
        return None


def unit_text(original, binary, fixture, port, private_tmp=None):
    # Append a Service section to override only test identity/inputs and restart policy.
    # The original unit and its drop-ins retain all sandbox directives.
    override = f'''
[Service]
ExecStart=
ExecStart={binary}
Environment=CODEX_SOCKET={fixture}/codex/app-server-control/app-server-control.sock
Environment=REMOTE_CODEX_LISTEN=127.0.0.1:{port}
Environment=REMOTE_CODEX_UPDATE_ROOT={fixture}/updates
LoadCredentialEncrypted=
LoadCredential=
LoadCredential=connection-token:{fixture}/connection-token
Restart=no
TimeoutStopSec=10
'''
    if private_tmp is not None:
        override += f'PrivateTmp={private_tmp}\n'
    return original + override


def candidate_check(binary, forwarder, service_unit=None, private_tmp=None):
    binary, forwarder = binary.resolve(strict=True), forwarder.resolve(strict=True)
    if not os.access(binary, os.X_OK) or not os.access(forwarder, os.X_OK):
        raise CandidateFailure('executables')
    runtime = Path(f'/run/user/{os.getuid()}')
    environment = dict(os.environ, XDG_RUNTIME_DIR=str(runtime),
                       DBUS_SESSION_BUS_ADDRESS=f'unix:path={runtime}/bus')
    original = (service_unit.read_text() if service_unit else
                command(['systemctl', '--user', 'cat', 'remote-codex-forwarder.service'], environment).stdout)
    if not original.strip():
        raise CandidateFailure('service_template')
    fixture = Path(tempfile.mkdtemp(prefix='remote-codex-candidate-', dir=runtime))
    units = [f'remote-codex-candidate-{uuid.uuid4().hex}.service',
             f'remote-codex-probe-{uuid.uuid4().hex}.service']
    unit_directory = runtime / 'systemd/user'
    unit_directory.mkdir(parents=True, exist_ok=True)
    unit_file = unit_directory / units[1]
    attempted = []
    socket_targets = set()
    failure = None
    try:
        home = fixture / 'codex'
        home.mkdir(mode=0o700)
        (fixture / 'updates').mkdir(mode=0o700)
        token = secrets.token_hex(32)
        credential = fixture / 'connection-token'
        credential.write_text(token)
        credential.chmod(0o600)
        (home / 'config.toml').write_text('''model = "fixture"
model_provider = "fixture"
cli_auth_credentials_store = "file"
[model_providers.fixture]
name = "Fixture"
base_url = "http://127.0.0.1:9/v1"
env_key = "REMOTE_CODEX_FIXTURE_KEY"
wire_api = "responses"
requires_openai_auth = false
request_max_retries = 0
stream_max_retries = 0
''')
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1', 0))
            port = reserved.getsockname()[1]
        unit_file.write_text(unit_text(original, forwarder, fixture, port, private_tmp))
        unit_file.chmod(0o600)
        command(['systemctl', '--user', 'daemon-reload'], environment)
        attempted.append(units[0])
        command(['systemd-run', '--user', '--quiet', '--collect', f'--unit={units[0]}',
                 '--property=Type=exec', '--property=KillMode=control-group', '--property=TimeoutStopSec=10',
                 '--property=UMask=0077', '--property=StandardOutput=null', '--property=StandardError=null',
                 '--property=UnsetEnvironment=LITELLM_PROXY_KEY OPENAI_API_KEY OPENAI_AUTH_TOKEN',
                 f'--setenv=HOME={home}', f'--setenv=CODEX_HOME={home}',
                 '--setenv=REMOTE_CODEX_FIXTURE_KEY=fake',
                 str(binary), 'app-server', '--listen', 'unix://'], environment)
        alias = home / 'app-server-control/app-server-control.sock'
        wait_for(alias.exists, 'candidate_socket')
        socket_targets.add(alias.resolve())
        attempted.append(units[1])
        command(['systemctl', '--user', 'start', units[1]], environment)
        wait_for(lambda: listening(port), 'forwarder_listener')
        connection.check(f'ws://127.0.0.1:{port}/codex/rpc', token, home)
        before = socket_identity(alias)
        # This is an explicit restart of the disposable candidate, never the live server.
        command(['systemctl', '--user', 'restart', units[0]], environment)
        wait_for(lambda: socket_identity(alias) not in (None, before), 'candidate_restart')
        socket_targets.add(alias.resolve())
        connection.check(f'ws://127.0.0.1:{port}/codex/rpc', token, home)
    except (CandidateFailure, connection.CheckFailure, OSError, ValueError, subprocess.SubprocessError) as error:
        failure = {'ok': False, 'stage': error.stage if isinstance(error, connection.CheckFailure)
                   else str(error) if isinstance(error, CandidateFailure) else 'candidate_transport'}
        if isinstance(error, connection.CheckFailure):
            failure['code'] = error.code
    finally:
        cleanup_ok = True
        for unit in reversed(attempted):
            try:
                command(['systemctl', '--user', 'stop', unit], environment, check=False)
                state = command(['systemctl', '--user', 'show', unit, '-p', 'ActiveState', '--value'],
                                environment, check=False).stdout.strip()
                if state not in ('inactive', 'failed', ''):
                    cleanup_ok = False
            except (OSError, subprocess.SubprocessError):
                cleanup_ok = False
        if cleanup_ok:
            # Codex leaves an empty daemon lock beside its socket. Remove only locks
            # belonging to this isolated home after its entire unit has stopped.
            for target in socket_targets:
                if target.parent == Path(f'/tmp/codex-daemon-{os.getuid()}'):
                    for path in [target, target.with_name(target.name + '.lock')]:
                        try:
                            if path.lstat().st_uid == os.getuid():
                                path.unlink()
                        except FileNotFoundError:
                            pass
            unit_file.unlink(missing_ok=True)
            shutil.rmtree(fixture)
            command(['systemctl', '--user', 'daemon-reload'], environment)
        else:
            failure = {'ok': False, 'stage': 'candidate_cleanup', 'recovery_directory': str(fixture),
                       'recovery_unit': str(unit_file), 'units': units}
    return failure or {'ok': True, 'candidate_connection': True, 'candidate_restart': True, 'cleanup': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--codex-binary', type=Path, required=True)
    parser.add_argument('--forwarder', type=Path, default=Path('/home/agent/.local/libexec/remote-codex-forwarder'))
    parser.add_argument('--service-unit', type=Path)
    parser.add_argument('--private-tmp', choices=['true', 'false'], help='Sandbox regression fixture only')
    args = parser.parse_args()
    try:
        result = candidate_check(args.codex_binary, args.forwarder, args.service_unit, args.private_tmp)
    except (CandidateFailure, OSError, ValueError, subprocess.SubprocessError):
        result = {'ok': False, 'stage': 'candidate_setup'}
    print(json.dumps(result))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
