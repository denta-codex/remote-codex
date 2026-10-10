"""Offline USB safety regressions: uv run --no-project tests/ios_device_test.py.

CMS profiles are synthetic and signed by a disposable test certificate. No
physical device, Apple credentials or network access is used by these tests.
"""
import argparse
import asyncio
from contextlib import asynccontextmanager, redirect_stdout
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ios_device", ROOT / "scripts/ios-device.py")
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)
UDID = "00008110-0011223344556677"
OTHER = "00008110-8899AABBCCDDEEFF"
REVISION = "a" * 40


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.crypto = tempfile.TemporaryDirectory()
        cls.cert = Path(cls.crypto.name) / "certificate.pem"
        cls.key = Path(cls.crypto.name) / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=Disposable offline fixture", "-keyout", str(cls.key), "-out", str(cls.cert)],
                       check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.crypto.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name)
        self.state = device.state_directory(self.path / "state")
        self.artifacts = self.path / "artifact"
        self.artifacts.mkdir()
        self.info = {"CFBundleIdentifier": device.BUNDLE, "CFBundleShortVersionString": "0.1.0",
                     "CFBundleVersion": "101", "MinimumOSVersion": "18.0", "UIDeviceFamily": [1],
                     "CFBundleSupportedPlatforms": ["iPhoneOS"], "CFBundleExecutable": "RemoteCodex"}
        self.profile = {"Entitlements": {"application-identifier": "FIXTURE123." + device.BUNDLE, "get-task-allow": True},
                        "TeamIdentifier": ["FIXTURE123"], "ProvisionedDevices": [UDID],
                        "DeveloperCertificates": [b"synthetic certificate"],
                        "ExpirationDate": datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(days=1)}
        self.args = argparse.Namespace(artifact_dir=self.artifacts, bundle_id=device.BUNDLE,
                                      revision=REVISION, run_id=900, run_attempt=2)
        self.package()

    def package(self, extra_members=(), metadata_changes=None):
        cms = subprocess.run(["openssl", "cms", "-sign", "-binary", "-nodetach", "-outform", "DER",
                              "-signer", str(self.cert), "-inkey", str(self.key)], input=plistlib.dumps(self.profile),
                             capture_output=True, check=True).stdout
        with zipfile.ZipFile(self.artifacts / "RemoteCodex.ipa", "w") as archive:
            base = "Payload/RemoteCodex.app/"
            archive.writestr(base + "Info.plist", plistlib.dumps(self.info))
            archive.writestr(base + "embedded.mobileprovision", cms)
            archive.writestr(base + "_CodeSignature/CodeResources", b"synthetic signature resource")
            archive.writestr(base + "RemoteCodex", b"synthetic executable; never install on a phone")
            for name, data in extra_members:
                archive.writestr(name, data)
        self.args.sha256 = hashlib.sha256((self.artifacts / "RemoteCodex.ipa").read_bytes()).hexdigest()
        self.meta = {"schema_version": 1, "repository": device.REPOSITORY, "revision": REVISION,
                     "run_id": 900, "run_attempt": 2, "artifact": "RemoteCodex.ipa", "sha256": self.args.sha256,
                     "bundle_id": device.BUNDLE, "version": "0.1.0", "build_number": "101",
                     "signing": "development", "minimum_ios": "18.0", "fixture_launch_argument": "--fixture"}
        self.meta.update(metadata_changes or {})
        (self.artifacts / "metadata.json").write_text(json.dumps(self.meta))
        (self.artifacts / "SHA256SUMS").write_text(self.args.sha256 + "  RemoteCodex.ipa\n")

    def rejects(self):
        with self.assertRaises(device.DeviceFailure):
            device.verify_artifact(self.args, UDID, "18.0")

    def test_exact_artifact_preflight(self):
        meta, data = device.verify_artifact(self.args, UDID, "18.6.2")
        self.assertEqual(meta, self.meta)
        self.assertEqual(hashlib.sha256(data).hexdigest(), self.args.sha256)

    def test_independent_source_hash_run_bundle(self):
        for key, value in (("revision", "b" * 40), ("sha256", "0" * 64), ("run_id", 901),
                           ("run_attempt", 1), ("repository", "other/repository"), ("bundle_id", "other.app"),
                           ("artifact", "other.ipa"), ("signing", "app-store"), ("fixture_launch_argument", "--live")):
            with self.subTest(key=key):
                self.package(metadata_changes={key: value})
                self.rejects()

    def test_old_manifest_aliases_rejected(self):
        self.package(metadata_changes={"source_revision": REVISION})
        self.rejects()

    def test_mutated_ipa_rejected(self):
        with (self.artifacts / "RemoteCodex.ipa").open("ab") as stream:
            stream.write(b"changed")
        self.rejects()

    def test_mismatched_embedded_bundle_build_and_platform(self):
        for key, value in (("CFBundleIdentifier", "other.app"), ("CFBundleVersion", "102"),
                           ("CFBundleShortVersionString", "0.2.0"), ("MinimumOSVersion", "19.0"),
                           ("UIDeviceFamily", [1, 2]), ("CFBundleSupportedPlatforms", ["iPhoneSimulator"])):
            with self.subTest(key=key):
                old = self.info[key]
                self.info[key] = value
                self.package()
                self.rejects()
                self.info[key] = old

    def test_unsigned_profile_wrong_device_expired_and_wildcard(self):
        for key, value in (("ProvisionedDevices", [OTHER]), ("ExpirationDate", datetime(2020, 1, 1)),
                           ("ProvisionsAllDevices", True), ("DeveloperCertificates", [])):
            with self.subTest(key=key):
                original = self.profile.copy()
                self.profile[key] = value
                self.package()
                self.rejects()
                self.profile = original
        self.profile["Entitlements"]["get-task-allow"] = False
        self.package()
        self.rejects()
        self.profile["Entitlements"] = {"get-task-allow": True, "application-identifier": "FIXTURE123.*"}
        self.package()
        self.rejects()

    def test_untrusted_cms_payload_rejected(self):
        with zipfile.ZipFile(self.artifacts / "RemoteCodex.ipa") as archive:
            members = {name: archive.read(name) for name in archive.namelist()}
        members["Payload/RemoteCodex.app/embedded.mobileprovision"] = plistlib.dumps(self.profile)
        with zipfile.ZipFile(self.artifacts / "RemoteCodex.ipa", "w") as archive:
            for name, data in members.items():
                archive.writestr(name, data)
        sha = hashlib.sha256((self.artifacts / "RemoteCodex.ipa").read_bytes()).hexdigest()
        self.args.sha256 = self.meta["sha256"] = sha
        (self.artifacts / "metadata.json").write_text(json.dumps(self.meta))
        (self.artifacts / "SHA256SUMS").write_text(sha + "  RemoteCodex.ipa\n")
        self.rejects()

    def test_ambiguous_app_and_path_traversal(self):
        for members in ((('Payload/Other.app/Info.plist', plistlib.dumps(self.info)),), (('../private', b'data'),)):
            with self.subTest(members=members):
                self.package(extra_members=members)
                self.rejects()

    def test_private_state_and_registration(self):
        record = self.state / "selected-device.json"
        device.save(record, {"udid": UDID})
        self.assertEqual(record.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o700)
        self.assertEqual(device.selected(self.state, UDID)["udid"], UDID)
        with self.assertRaises(device.DeviceFailure):
            device.selected(self.state, OTHER)
        record.chmod(0o644)
        with self.assertRaises(device.DeviceFailure):
            device.selected(self.state, UDID)
        with self.assertRaises(device.DeviceFailure):
            device.state_directory(ROOT / "artifacts/device-state")

    def test_mutations_require_explicit_udid(self):
        for command in ("pair", "select", "prepare", "install", "launch", "screenshot", "recover"):
            with self.subTest(command=command), redirect_stdout(io.StringIO()), patch("sys.stderr", io.StringIO()):
                with self.assertRaises(SystemExit):
                    device.parser().parse_args([command])
        for value in ("", "auto", "first", "--udid", "../../device"):
            with self.assertRaises(device.DeviceFailure):
                device.valid_udid(value)

    def test_discovery_filters_network_and_requires_exact_presence(self):
        module = SimpleNamespace(list_devices=AsyncMock(return_value=[SimpleNamespace(serial=UDID, is_usb=True), SimpleNamespace(serial=OTHER, is_usb=False)]))
        with patch.dict(sys.modules, {"pymobiledevice3.usbmux": module}):
            self.assertEqual(asyncio.run(device.discover()), [UDID])
        with patch.object(device, "discover", AsyncMock(return_value=[UDID, OTHER])):
            asyncio.run(device.require_attached(UDID))
        for devices in ([], [OTHER], [UDID, UDID]):
            with patch.object(device, "discover", AsyncMock(return_value=devices)):
                with self.assertRaises(device.DeviceFailure):
                    asyncio.run(device.require_attached(UDID))

    def test_lockdown_disables_autopair_and_forces_usb(self):
        client = SimpleNamespace(udid=UDID, paired=True, close=AsyncMock())
        factory = AsyncMock(return_value=client)
        async def check():
            async with device.lockdown(UDID, self.state) as observed:
                self.assertIs(observed, client)
        with patch.object(device, "discover", AsyncMock(return_value=[UDID])), patch.dict(sys.modules, {"pymobiledevice3.lockdown": SimpleNamespace(create_using_usbmux=factory)}):
            asyncio.run(check())
        factory.assert_awaited_once_with(serial=UDID, connection_type="USB", autopair=False, pairing_records_cache_folder=self.state)
        client.close.assert_awaited_once()

    def test_wrong_phone_or_old_ios_rejected(self):
        client = SimpleNamespace(udid=UDID, all_values={"DeviceClass": "iPhone", "ProductVersion": "18.6.2", "ProductType": "iPhone14,4"})
        self.assertEqual(device.identity(client)["model"], "iPhone 13 mini")
        for key, value in (("DeviceClass", "iPad"), ("ProductVersion", "17.7")):
            client.all_values[key] = value
            with self.assertRaises(device.DeviceFailure):
                device.identity(client)
            client.all_values[key] = "iPhone" if key == "DeviceClass" else "18.6.2"

    def test_receipt_blocks_replay_and_concurrency(self):
        device.begin(self.state, UDID, "install", sha256="a" * 64)
        with self.assertRaises(device.DeviceFailure):
            device.begin(self.state, UDID, "install", sha256="b" * 64)
        with device.device_lock(self.state):
            with self.assertRaises(device.DeviceFailure):
                with device.device_lock(self.state):
                    self.fail("second lock should never succeed")

    def installation(self, before=None, interruption=None):
        calls = []
        current = dict(before or {})
        class Service:
            def __init__(self, client): pass
            async def __aenter__(self): return self
            async def __aexit__(self, *args): pass
            async def get_apps(self, **kwargs):
                self_test.assertEqual(kwargs, {"bundle_identifiers": [device.BUNDLE]})
                return current
            async def install_from_bytes(self, data, cmd):
                calls.append((data, cmd))
                if interruption:
                    raise interruption
                current[device.BUNDLE] = {"CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "101"}
        self_test = self
        return Service, calls, current

    def test_install_exact_bytes_and_upgrade_in_place(self):
        for before, command in ((None, "Install"), ({device.BUNDLE: {"CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "100"}}, "Upgrade")):
            with self.subTest(command=command):
                receipt = device.receipt_path(self.state, "install")
                receipt.unlink(missing_ok=True)
                service, calls, _ = self.installation(before)
                with patch.dict(sys.modules, {"pymobiledevice3.services.installation_proxy": SimpleNamespace(InstallationProxyService=service)}), redirect_stdout(io.StringIO()):
                    asyncio.run(device.install_once(None, self.state, UDID, self.meta, b"the exact validated bytes"))
                self.assertEqual(calls, [(b"the exact validated bytes", command)])
                self.assertEqual(device.load(receipt)["outcome"], "confirmed")

    def test_interrupted_install_is_observed_without_replay(self):
        service, calls, current = self.installation(interruption=InterruptedError("SENSITIVE DEVICE CONTENT"))
        output = io.StringIO()
        with patch.dict(sys.modules, {"pymobiledevice3.services.installation_proxy": SimpleNamespace(InstallationProxyService=service)}), redirect_stdout(output):
            with self.assertRaisesRegex(device.DeviceFailure, "uncertain"):
                asyncio.run(device.install_once(None, self.state, UDID, self.meta, b"validated"))
            with self.assertRaises(device.DeviceFailure):
                asyncio.run(device.install_once(None, self.state, UDID, self.meta, b"validated"))
            asyncio.run(device.reconcile(None, self.state, UDID))
            record = device.load(device.receipt_path(self.state, "install"))
            self.assertEqual(record["outcome"], "unknown")
            self.assertIn("observed_at", record)
            current[device.BUNDLE] = {"CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "101"}
            asyncio.run(device.reconcile(None, self.state, UDID))
        self.assertEqual(len(calls), 1)
        self.assertEqual(device.load(device.receipt_path(self.state, "install"))["outcome"], "observed-matching")
        self.assertNotIn("SENSITIVE", output.getvalue())
        self.assertNotIn(UDID, output.getvalue())

    def test_already_present_does_not_install(self):
        service, calls, _ = self.installation({device.BUNDLE: {"CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "101"}})
        with patch.dict(sys.modules, {"pymobiledevice3.services.installation_proxy": SimpleNamespace(InstallationProxyService=service)}), redirect_stdout(io.StringIO()):
            asyncio.run(device.install_once(None, self.state, UDID, self.meta, b"validated"))
        self.assertEqual(calls, [])

    def test_failures_hide_device_data(self):
        with patch.object(sys, "argv", ["ios-device", "doctor"]), patch.object(device, "dispatch", AsyncMock(side_effect=RuntimeError("credential RPC transcript " + UDID))), redirect_stdout(io.StringIO()) as output:
            self.assertEqual(device.main(), 1)
        self.assertNotIn(UDID, output.getvalue())
        self.assertNotIn("credential", output.getvalue())

    def dvt_fixture(self, pid=0, launch_error=None):
        observed = {"pid": pid, "launches": [], "screenshots": 0}
        class Context:
            def __init__(self, *args, **kwargs): pass
            async def __aenter__(self): return self
            async def __aexit__(self, *args): pass
        class Process(Context):
            async def process_identifier_for_bundle_identifier(self, bundle):
                self_test.assertEqual(bundle, device.BUNDLE)
                return observed["pid"]
            async def launch(self, bundle, **kwargs):
                observed["launches"].append((bundle, kwargs))
                if launch_error:
                    raise launch_error
                observed["pid"] = 777
                return 777
            async def is_debuggable(self, pid): return pid == 777
        class Screenshot(Context):
            async def get_screenshot(self):
                observed["screenshots"] += 1
                return b"\x89PNG\r\n\x1a\nfixture-only-image"
        @asynccontextmanager
        async def tunnel(*args): yield object()
        self_test = self
        modules = {
            "pymobiledevice3.services.dvt.instruments.dvt_provider": SimpleNamespace(DvtProvider=Context),
            "pymobiledevice3.services.dvt.instruments.process_control": SimpleNamespace(ProcessControl=Process),
            "pymobiledevice3.services.dvt.instruments.screenshot": SimpleNamespace(Screenshot=Screenshot),
        }
        return modules, observed, tunnel

    def confirmed_install(self):
        device.save(device.receipt_path(self.state, "install"), {"udid": UDID, "outcome": "confirmed",
                    "version": "0.1.0", "build_number": "101", "sha256": "a" * 64})

    def test_fixture_launch_uses_only_fixture_argument(self):
        self.confirmed_install()
        modules, observed, tunnel = self.dvt_fixture()
        args = argparse.Namespace(command="launch", udid=UDID)
        with patch.dict(sys.modules, modules), patch.object(device, "developer", tunnel), patch.object(device, "installed", AsyncMock(return_value={"version": "0.1.0", "build_number": "101"})), redirect_stdout(io.StringIO()):
            asyncio.run(device.dvt_operation(args, None, self.state))
        self.assertEqual(observed["launches"], [(device.BUNDLE, {"arguments": ["--fixture"], "kill_existing": False})])
        record = device.load(device.receipt_path(self.state, "launch"))
        self.assertTrue(record["fixture"])
        self.assertEqual(record["outcome"], "confirmed")

    def test_launch_blocks_existing_process_and_unknown_replay(self):
        self.confirmed_install()
        args = argparse.Namespace(command="launch", udid=UDID)
        modules, observed, tunnel = self.dvt_fixture(pid=777)
        with patch.dict(sys.modules, modules), patch.object(device, "developer", tunnel):
            with self.assertRaises(device.DeviceFailure):
                asyncio.run(device.dvt_operation(args, None, self.state))
        self.assertEqual(observed["launches"], [])
        modules, observed, tunnel = self.dvt_fixture(launch_error=ConnectionError("sensitive launch output"))
        with patch.dict(sys.modules, modules), patch.object(device, "developer", tunnel), patch.object(device, "installed", AsyncMock(return_value={"version": "0.1.0", "build_number": "101"})):
            for _ in range(2):
                with self.assertRaises(device.DeviceFailure):
                    asyncio.run(device.dvt_operation(args, None, self.state))
        self.assertEqual(len(observed["launches"]), 1)
        self.assertEqual(device.load(device.receipt_path(self.state, "launch"))["outcome"], "unknown")

    def test_screenshot_requires_current_confirmed_fixture_and_physical_check(self):
        record = {"udid": UDID, "outcome": "confirmed", "fixture": True, "pid": 777, "started_at": device.time.time()}
        modules, observed, tunnel = self.dvt_fixture(pid=777)
        args = argparse.Namespace(command="screenshot", udid=UDID, fixture_screen_confirmed=False)
        device.save(device.receipt_path(self.state, "launch"), record)
        with patch.dict(sys.modules, modules), patch.object(device, "developer", tunnel), redirect_stdout(io.StringIO()):
            with self.assertRaises(device.DeviceFailure):
                asyncio.run(device.dvt_operation(args, None, self.state))
            args.fixture_screen_confirmed = True
            for key, value in (("fixture", False), ("pid", 888), ("udid", OTHER), ("outcome", "unknown"), ("started_at", 0)):
                with self.subTest(key=key):
                    device.save(device.receipt_path(self.state, "launch"), {**record, key: value})
                    with self.assertRaises(device.DeviceFailure):
                        asyncio.run(device.dvt_operation(args, None, self.state))
            self.assertEqual(observed["screenshots"], 0)
            device.save(device.receipt_path(self.state, "launch"), record)
            asyncio.run(device.dvt_operation(args, None, self.state))
        self.assertEqual(observed["screenshots"], 1)
        screenshots = list(self.state.glob("fixture-*.png"))
        self.assertEqual(len(screenshots), 1)
        self.assertEqual(screenshots[0].stat().st_mode & 0o777, 0o600)

    def test_app_scoped_diagnostics_without_output_or_exports(self):
        modules, observed, tunnel = self.dvt_fixture(pid=777)
        args = argparse.Namespace(command="diagnostics", udid=UDID)
        with patch.dict(sys.modules, modules), patch.object(device, "developer", tunnel), patch.object(device, "installed", AsyncMock(return_value={"installed": True, "version": "0.1.0", "build_number": "101"})), redirect_stdout(io.StringIO()) as output:
            asyncio.run(device.dvt_operation(args, None, self.state))
        self.assertEqual(observed["launches"], [])
        self.assertEqual(observed["screenshots"], 0)
        report = json.loads(output.getvalue())
        self.assertEqual(set(report), {"observation", "app_running", "pid", "debuggable"})
        self.assertNotIn(UDID, output.getvalue())

    def test_developer_tunnel_reuses_usb_client_and_checks_identity(self):
        events = []
        class Tun:
            def set_peer(self, address): events.append("peer")
        class Proxy:
            @asynccontextmanager
            async def start_tcp_tunnel(self):
                try:
                    yield SimpleNamespace(client=SimpleNamespace(tun=Tun()), address="::1", port=12345)
                finally:
                    events.append("tunnel-closed")
            async def close(self): events.append("proxy-closed")
        class Plane:
            def __init__(self, *args): pass
            async def __aenter__(self): return self
            async def __aexit__(self, *args): events.append("plane-closed")
            async def dial(self, *args): pass
        class RSD(Plane):
            def __init__(self, *args, **kwargs): self.udid = OTHER
        factory = AsyncMock(return_value=Proxy())
        service = SimpleNamespace(CoreDeviceTunnelProxy=SimpleNamespace(create=factory), USE_USERSPACE_TUNNEL=False)
        modules = {
            "pymobiledevice3.remote": SimpleNamespace(tunnel_service=service),
            "pymobiledevice3.remote.userspace_tunnel": SimpleNamespace(UserspaceDialPlane=Plane),
            "pymobiledevice3.remote.remote_service_discovery": SimpleNamespace(RemoteServiceDiscoveryService=RSD),
        }
        client = object()
        async def check():
            async with device.developer(client, UDID): self.fail("wrong RSD identity accepted")
        with patch.dict(sys.modules, modules), patch.object(device, "ready", AsyncMock(return_value={})), self.assertRaises(device.DeviceFailure):
            asyncio.run(check())
        factory.assert_awaited_once_with(client)
        self.assertFalse(service.USE_USERSPACE_TUNNEL)
        self.assertIn("tunnel-closed", events)
        self.assertIn("proxy-closed", events)

    def test_developer_mode_remains_a_physical_confirmation(self):
        client = SimpleNamespace(udid=UDID, paired=True,
                 all_values={"DeviceClass": "iPhone", "ProductVersion": "18.6.2", "ProductType": "iPhone14,4"},
                 get_developer_mode_status=AsyncMock(return_value=False))
        with self.assertRaisesRegex(device.DeviceFailure, "confirm on phone"):
            asyncio.run(device.ready(client, developer=True))
        result = asyncio.run(device.ready(client))
        self.assertFalse(result["developer_mode"])
        self.assertNotIn("udid", result)


if __name__ == "__main__":
    unittest.main()
