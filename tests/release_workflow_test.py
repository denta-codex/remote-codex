"""Run with: uv run --no-project tests/release_workflow_test.py

The real Ansible tasks run against disposable Git repositories, a fake builder,
and a local HTTPS fixture. No live update, service, or phone is modified.
"""
import contextlib
import fcntl
import getpass
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import types
import unittest

ROOT = Path(__file__).resolve().parents[1]


def command(args, cwd=None, env=None):
    result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True, timeout=180)
    if result.returncode:
        raise AssertionError(f"{args}: {result.stdout}\n{result.stderr}")
    return result.stdout.strip()


# Pure filter tests need no installed Ansible package in the test interpreter.
errors = types.ModuleType("ansible.errors")
errors.AnsibleFilterError = ValueError
sys.modules.setdefault("ansible", types.ModuleType("ansible"))
sys.modules.setdefault("ansible.errors", errors)
spec = importlib.util.spec_from_file_location("release_version", ROOT / "deploy/filter_plugins/release_version.py")
version_filter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version_filter)


class VersionTests(unittest.TestCase):
    gradle = '        versionCode = 13\n        versionName = "0.2.3"\n'

    def test_prerelease_precedence_and_roundtrip(self):
        names = ["0.2.10", "0.2.11-1", "0.2.11-alpha", "0.2.11-autofill.1",
                 "0.2.11-autofill.2", "0.2.11-autofill.10", "0.2.11-autofill.beta", "0.2.11"]
        parsed = [version_filter.parse_version(name) for name in names]
        self.assertEqual(sorted(parsed), parsed)
        self.assertEqual([version_filter.format_version(v) for v in parsed], names)
        for value in ("0.2.010", "0.2.11-autofill.01", "0.2.11-", "0.2.11-a..b",
                      "0.2.11+build", "0.2.11-a_b", "0.2.11-a\n", "0.2.11-" + "a" * 64):
            with self.subTest(value=value), self.assertRaises(ValueError):
                version_filter.parse_version(value)

    def test_automatic_promotes_prerelease_and_preserves_build_order(self):
        manifests = [{"versionName": "0.2.11-autofill.10", "versionCode": 21}]
        self.assertEqual(version_filter.select_version(self.gradle, manifests, ["30"]),
                         {"versionName": "0.2.11", "versionCode": 31})
        with self.assertRaises(ValueError):
            version_filter.select_version(self.gradle, manifests, [], "0.2.11-autofill.2")

    def test_automatic_uses_all_versions_and_occupied_codes(self):
        actual = version_filter.select_version(
            self.gradle, [{"versionName": "0.3.9", "versionCode": 20}], ["99", "18"]
        )
        self.assertEqual(actual, {"versionName": "0.3.10", "versionCode": 100})

    def test_override_and_invalid_versions(self):
        self.assertEqual(version_filter.select_version(self.gradle, [], [], "1.0.0")["versionName"], "1.0.0")
        for value in ("0.2.3", "0.1.0", "v1.0.0", "1.0", "01.0.0", "1.0.0\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                version_filter.select_version(self.gradle, [], [], value)


class ReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="remote-codex-release-tests-")
        cls.cert = Path(cls.shared.name) / "cert.pem"
        cls.key = Path(cls.shared.name) / "key.pem"
        command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                 "-keyout", str(cls.key), "-out", str(cls.cert), "-days", "1",
                 "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"])

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.shared.name)
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        for directory in ("deploy", "scripts"):
            shutil.copytree(ROOT / directory, self.repo / directory)
        for name in ("ansible.cfg", ".gitignore"):
            shutil.copy(ROOT / name, self.repo / name)
        (self.repo / "android/app").mkdir(parents=True)
        (self.repo / "android/app/build.gradle.kts").write_text(VersionTests.gradle)
        (self.repo / "docs").mkdir()
        (self.repo / "docs/VALIDATION.md").write_text("# Validation\n\nExisting record.\n")
        (self.repo / "docs/release-notes.md").write_text("previous notes\n")
        shutil.copy(ROOT / "docs/signing-certificate.sha256", self.repo / "docs")
        shutil.copy(ROOT / "tests/fixtures/release-builder", self.repo / "scripts/release")
        (self.repo / "scripts/release").chmod(0o755)
        check = self.repo / "scripts/check"
        check.write_text("#!/usr/bin/env bash\nset -eu\nmkdir -p artifacts\necho check >> artifacts/check-count\n")
        check.chmod(0o755)
        inventory = {"all": {"hosts": {"fixture": {
            "ansible_connection": "local", "expected_user": getpass.getuser(),
            "expected_hostname": socket.gethostname(), "remote_codex_phone": "unused"
        }}}}
        (self.repo / "deploy/inventory.yml").write_text(json.dumps(inventory))
        self.store = self.base / "store"
        self.store.mkdir()
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for name, content in (("systemctl", "echo active"), ("systemd-creds", "echo fixture-token")):
            target = self.bin / name
            target.write_text("#!/bin/sh\n" + content + "\n")
            target.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        XDG_RUNTIME_DIR=str(self.base / "runtime"), SSL_CERT_FILE=str(self.cert),
                        ANSIBLE_CONFIG=str(self.repo / "ansible.cfg"), NO_PROXY="127.0.0.1,localhost",
                        PYTHONDONTWRITEBYTECODE="1")
        command(["git", "init", "-b", "test-release"], self.repo)
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Release Fixture")
        self.git("add", ".")
        self.git("commit", "-m", "Previous version")
        (self.repo / "feature.txt").write_text("fixture change\n")
        self.git("add", "feature.txt")
        self.git("commit", "-m", "Add fixture feature")

    def git(self, *args):
        return command(["git", *args], self.repo, self.env)

    def run_action(self, action, expected=0, extra=None, check=False):
        values = {"remote_codex_action": action, "secret_dir": str(self.store)}
        if extra:
            values.update(extra)
        args = [str(self.repo / "scripts/deploy"), "-e", json.dumps(values)]
        if check:
            args.append("--check")
        result = subprocess.run(args, cwd=self.repo, env=self.env, text=True, capture_output=True, timeout=180)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, expected == 0, output)
        summaries = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
        summaries = [summary for summary in summaries if 'status' in summary]
        self.assertTrue(summaries, output)
        self.assertLess(len(output), 14000, output)
        self.last_output = output
        self.last_summary = summaries[-1]
        for log in (self.repo / "artifacts/releases").glob("*.log"):
            self.assertNotIn("fixture-token", log.read_text())
        return self.last_summary

    def prepared(self):
        return json.loads((self.repo / "dist/updates/stable/latest.json").read_text())

    def checks(self):
        file = self.repo / "artifacts/check-count"
        return len(file.read_text().splitlines()) if file.exists() else 0

    @contextlib.contextmanager
    def endpoint(self, mode="normal"):
        store = self.store

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer fixture-token":
                    self.send_error(401)
                    return
                if mode == "auth-failure":
                    self.send_error(403)
                    return
                prefix = "/remote-codex/v1/updates/"
                if not self.path.startswith(prefix):
                    self.send_error(404)
                    return
                path = store / "updates" / self.path[len(prefix):]
                if not path.is_file():
                    self.send_error(404)
                    return
                data = path.read_bytes()
                if mode == "redirect-apk" and path.suffix == ".apk":
                    self.send_response(302)
                    self.send_header("Location", self.path + "?redirected=1")
                    self.end_headers()
                    return
                if mode == "bad-apk" and path.suffix == ".apk":
                    data = b"corrupted APK"
                if mode == "bad-manifest" and path.suffix == ".json":
                    data += b" "
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert, self.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        endpoint = f"https://127.0.0.1:{server.server_port}"
        try:
            yield {"remote_codex_update_url": endpoint, "remote_codex_loopback_url": endpoint}
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_build_only_versions_notes_commit_and_checks_once(self):
        (self.store / "updates/releases/50").mkdir(parents=True)
        stable = self.store / "updates/stable"
        stable.mkdir()
        before = json.dumps({"versionName": "0.2.8", "versionCode": 40})
        (stable / "latest.json").write_text(before)
        self.git("checkout", "--detach")
        result = self.run_action("build")
        self.assertEqual((result["version"], result["versionCode"]), ("0.2.9", 51))
        self.assertFalse(result["published"])
        self.assertEqual(self.checks(), 1)
        self.assertEqual((stable / "latest.json").read_text(), before)
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertEqual(self.git("branch", "--show-current"), "codex/release-0.2.9")
        self.assertIn("Add fixture feature", self.prepared()["releaseNotes"])
        self.assertIn("Existing record.", (self.repo / "docs/VALIDATION.md").read_text())
        self.assertEqual((self.repo / "docs/VALIDATION.md").read_text().count('# Validation\n'), 1)

    def test_explicit_version_and_notes(self):
        notes = self.base / "notes.txt"
        notes.write_text("Curated release notes.\n")
        self.run_action("build", extra={"remote_codex_version": "1.0.0", "remote_codex_notes_file": str(notes)})
        self.assertEqual(self.prepared()["versionName"], "1.0.0")
        self.assertEqual(self.prepared()["releaseNotes"], "Curated release notes.\n")
        self.assertEqual(self.checks(), 1)

    def test_prerelease_publication_then_normal_release(self):
        with self.endpoint() as endpoints:
            self.run_action("release", extra={**endpoints, "remote_codex_version": "0.2.11-autofill.1"})
            self.assertEqual(self.prepared()["versionName"], "0.2.11-autofill.1")
            self.run_action("release", extra=endpoints)
            self.assertEqual(self.prepared()["versionName"], "0.2.11")
            self.assertEqual(self.prepared()["versionCode"], 15)

    def test_separate_checkouts_share_version_reservations(self):
        other = self.base / 'other-checkout'
        command(['git', 'clone', '--local', str(self.repo), str(other)])
        first = self.run_action('build')
        self.repo = other
        self.env['ANSIBLE_CONFIG'] = str(other / 'ansible.cfg')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Release Fixture')
        second = self.run_action('build')
        self.assertEqual((first['version'], first['versionCode']), ('0.2.4', 14))
        self.assertEqual((second['version'], second['versionCode']), ('0.2.5', 15))

    def test_dirty_checkout_rejected_before_build(self):
        (self.repo / "feature.txt").write_text("uncommitted\n")
        self.run_action("release", expected=1)
        self.assertEqual(self.checks(), 0)
        self.assertFalse((self.store / "updates").exists())

    def test_build_failure_prevents_publication_and_preserves_changes(self):
        self.env["FIXTURE_BUILD_FAIL"] = "1"
        result = self.run_action("release", expected=1)
        self.assertEqual(result["stage"], "build and sign")
        self.assertIn("fixture build failed", self.last_output)
        self.assertEqual(self.checks(), 1)
        self.assertFalse((self.store / "updates").exists())
        self.assertIn("android/app/build.gradle.kts", result["uncommitted_changes"])

    def test_release_success_and_no_credential_logging(self):
        with self.endpoint() as endpoint:
            result = self.run_action("release", extra=endpoint)
        self.assertTrue(result["published"])
        self.assertTrue(result["verified"])
        self.assertEqual(self.checks(), 1)
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertEqual((self.store / "updates/stable/latest.json").read_bytes(),
                         (self.repo / "dist/updates/stable/latest.json").read_bytes())

    def test_post_publication_failures_are_reported_honestly(self):
        # Build once, then test prepared-artifact publication without replaying a build.
        self.run_action("build")
        for mode in ("auth-failure", "bad-manifest", "bad-apk", "redirect-apk"):
            with self.subTest(mode=mode), self.endpoint(mode) as endpoint:
                result = self.run_action("publish", expected=1, extra=endpoint)
                self.assertTrue(result["published"])
                self.assertFalse(result["verified"])
                self.assertEqual(result["stage"], "verify published update")
        self.assertEqual(self.checks(), 1)

    def test_recording_failure_does_not_misreport_published_release(self):
        hook = self.repo / ".git/hooks/pre-commit"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o755)
        with self.endpoint() as endpoint:
            result = self.run_action("release", expected=1, extra=endpoint)
        self.assertTrue(result["published"])
        self.assertTrue(result["verified"])
        self.assertEqual(result["stage"], "record release")
        self.assertTrue(result["uncommitted_changes"])

    def test_immutable_conflict_and_publication_check_mode(self):
        self.run_action("build")
        manifest = self.prepared()
        with self.endpoint() as endpoint:
            self.run_action("publish", extra=endpoint, check=True)
            self.assertFalse((self.store / "updates").exists())
            occupied = self.store / "updates/releases" / str(manifest["versionCode"])
            occupied.mkdir(parents=True)
            (occupied / "remote-codex.apk").write_text("a different immutable artifact")
            result = self.run_action("publish", expected=1, extra=endpoint)
            self.assertFalse(result["publication_attempted"])
            self.assertFalse((self.store / "updates/stable/latest.json").exists())

    def test_host_lock_rejects_concurrent_invocation(self):
        lock = Path(self.env["XDG_RUNTIME_DIR"]) / "remote-codex/deploy.lock"
        lock.parent.mkdir(parents=True)
        with lock.open("w") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = subprocess.run([str(self.repo / "scripts/deploy")], cwd=self.repo,
                                    env=self.env, text=True, capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Another Remote Codex", result.stderr)
        self.assertEqual(self.checks(), 0)

    def test_check_mode_build_never_mutates_checkout(self):
        self.run_action("build", expected=1, check=True)
        self.assertEqual(self.checks(), 0)
        self.assertEqual(self.git("status", "--porcelain"), "")


if __name__ == "__main__":
    unittest.main(verbosity=2)
