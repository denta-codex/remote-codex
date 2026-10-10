# /// script
# requires-python = ">=3.12"
# dependencies = ["pymobiledevice3==11.26.0"]
# ///
"""Explicit USB iPhone operations. No device payloads or application output in logs."""
import argparse
import asyncio
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.metadata
import importlib
import io
import json
import logging
import os
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = "dev.codexops.client.ios"
REPOSITORY = "denta-codex/remote-codex"
PIN = "11.26.0"
MAX_IPA = 512 * 1024 * 1024


class DeviceFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DeviceFailure(message)


def emit(**fields):
    print(json.dumps(fields, sort_keys=True))


def os_version(value):
    require(isinstance(value, str) and re.fullmatch(r"\d+(\.\d+){0,2}", value), "invalid iOS version")
    return tuple((list(map(int, value.split("."))) + [0, 0])[:3])


def valid_udid(value):
    require(bool(re.fullmatch(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40}", value)), "invalid explicit UDID")
    return value


def state_directory(path):
    path = path.expanduser().resolve()
    require(not path.is_relative_to(ROOT), "device state must be outside the repository")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(path.stat().st_uid == os.getuid(), "private state must belong to the current user")
    path.chmod(0o700)
    return path


def save(path, data):
    # Atomic, private and durable: a killed install must leave its pending receipt.
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(data, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
            temporary.chmod(0o600)
            os.replace(temporary, path)
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            temporary.unlink(missing_ok=True)


def load(path):
    require(path.is_file() and not path.is_symlink(), "private device record missing")
    require(path.stat().st_uid == os.getuid() and path.stat().st_mode & 0o077 == 0, "device record permissions must be 0600")
    return json.loads(path.read_text())


@contextmanager
def device_lock(state):
    descriptor = os.open(state / "device.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DeviceFailure("another selected-device operation is active") from None
        yield
    finally:
        os.close(descriptor)


async def discover():
    from pymobiledevice3.usbmux import list_devices
    try:
        devices = await asyncio.wait_for(list_devices(), timeout=10)
    except Exception:
        raise DeviceFailure("USB discovery unavailable; check usbmuxd service/socket and host access") from None
    return [device.serial for device in devices if device.is_usb]


async def require_attached(udid):
    devices = await discover()
    require(devices.count(udid) == 1, "selected UDID is absent or ambiguous on USB; connect and unlock that phone")


@asynccontextmanager
async def lockdown(udid, state, paired=True):
    from pymobiledevice3.lockdown import create_using_usbmux
    await require_attached(udid)
    client = await create_using_usbmux(serial=udid, connection_type="USB", autopair=False,
                                     pairing_records_cache_folder=state)
    try:
        require(client.udid == udid, "USB device identity mismatch")
        if paired:
            require(client.paired, "Trust required; unlock phone, run pair with its explicit UDID and confirm Trust on phone")
        yield client
    finally:
        await client.close()


def identity(client):
    values = client.all_values
    require(values.get("DeviceClass") == "iPhone", "selected USB device is not an iPhone")
    version = values.get("ProductVersion")
    require(os_version(version) >= (18, 0, 0), "iOS 18 or newer is required")
    product = values.get("ProductType")
    require(isinstance(product, str) and product.startswith("iPhone"), "iPhone model unavailable")
    return {"udid": client.udid, "product_type": product,
            "model": "iPhone 13 mini" if product == "iPhone14,4" else product,
            "os_version": version, "os_build": values.get("BuildVersion")}


def selected(state, udid):
    record = load(state / "selected-device.json")
    require(record.get("udid") == udid, "UDID does not match private selected-device.json; register the intended phone first")
    return record


async def ready(client, developer=False):
    result = identity(client)
    result.pop("udid")
    result["paired"] = client.paired
    result["developer_mode"] = await client.get_developer_mode_status()
    if developer:
        require(result["developer_mode"], "enable Settings > Privacy & Security > Developer Mode, restart, and confirm on phone")
    return result


@asynccontextmanager
async def developer(client, udid):
    from pymobiledevice3.remote import tunnel_service
    from pymobiledevice3.remote.userspace_tunnel import UserspaceDialPlane
    from pymobiledevice3.remote.remote_service_discovery import RemoteServiceDiscoveryService
    await ready(client, developer=True)
    # Reuse the proven USB lockdown connection. The generic tunnel helper can
    # select a Network connection after a cable disconnect, even with a serial.
    proxy = await tunnel_service.CoreDeviceTunnelProxy.create(client)
    previous = tunnel_service.USE_USERSPACE_TUNNEL
    tunnel_service.USE_USERSPACE_TUNNEL = True
    try:
        async with proxy.start_tcp_tunnel() as tunnel:
            tun = tunnel.client.tun
            tun.set_peer(tunnel.address)
            async with UserspaceDialPlane(tun, tunnel.address) as plane:
                async with RemoteServiceDiscoveryService((tunnel.address, tunnel.port), open_connection=plane.dial) as rsd:
                    require(rsd.udid == udid, "developer tunnel identity mismatch")
                    yield rsd
    finally:
        tunnel_service.USE_USERSPACE_TUNNEL = previous
        await proxy.close()


async def installed(client):
    from pymobiledevice3.services.installation_proxy import InstallationProxyService
    async with InstallationProxyService(client) as service:
        apps = await service.get_apps(bundle_identifiers=[BUNDLE])
    app = apps.get(BUNDLE)
    if app is None:
        return {"installed": False}
    return {"installed": True, "bundle_id": BUNDLE,
            "version": app.get("CFBundleShortVersionString"), "build_number": app.get("CFBundleVersion")}


def verify_artifact(args, udid, ios=None):
    require(args.bundle_id == BUNDLE, "unexpected bundle identifier")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", args.revision)), "expected revision must be a full lowercase SHA")
    require(bool(re.fullmatch(r"[0-9a-f]{64}", args.sha256)), "expected SHA256 must be independently supplied")
    directory = args.artifact_dir.resolve()
    metadata_path, ipa_path = directory / "metadata.json", directory / "RemoteCodex.ipa"
    require(metadata_path.is_file() and metadata_path.stat().st_size < 65536, "metadata.json missing or too large")
    meta = json.loads(metadata_path.read_text())
    expected = {"schema_version": 1, "repository": REPOSITORY, "revision": args.revision,
                "artifact": "RemoteCodex.ipa", "sha256": args.sha256, "bundle_id": BUNDLE,
                "signing": "development", "fixture_launch_argument": "--fixture"}
    require(all(meta.get(key) == value for key, value in expected.items()), "artifact metadata does not match expected source/hash/bundle/development contract")
    require(set(meta) == set(expected) | {"run_id", "run_attempt", "version", "build_number", "minimum_ios"}, "metadata schema must use exactly the agreed keys, without aliases")
    require(args.run_id > 0 and args.run_attempt > 0, "run and attempt must be positive")
    require(str(meta.get("run_id")) == str(args.run_id) and str(meta.get("run_attempt")) == str(args.run_attempt), "artifact GitHub run/attempt mismatch")
    require(ipa_path.is_file() and ipa_path.stat().st_size <= MAX_IPA, "IPA missing or exceeds 512 MiB")
    data = ipa_path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == args.sha256, "IPA SHA256 mismatch")
    sums = (directory / "SHA256SUMS").read_text().splitlines()
    require(sums == [f"{args.sha256}  RemoteCodex.ipa"], "SHA256SUMS must identify only the exact IPA")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate IPA members")
        require(all(not name.startswith("/") and ".." not in Path(name).parts for name in names), "unsafe IPA member path")
        infos = [name for name in names if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        require(len(infos) == 1, "IPA must contain exactly one main application")
        base = infos[0].removesuffix("Info.plist")
        def member(name):
            require(archive.getinfo(name).file_size <= 1024 * 1024, "IPA metadata member too large")
            return archive.read(name)
        info = plistlib.loads(member(infos[0]))
        require(info.get("CFBundleIdentifier") == BUNDLE, "IPA bundle identifier mismatch")
        require(info.get("CFBundleShortVersionString") == meta.get("version") and str(info.get("CFBundleVersion")) == str(meta.get("build_number")), "IPA version/build mismatch")
        minimum = info.get("MinimumOSVersion")
        require(os_version(minimum) >= (18, 0, 0) and os_version(minimum) == os_version(meta.get("minimum_ios")), "IPA minimum iOS mismatch")
        if ios:
            require(os_version(ios) >= os_version(minimum), "phone OS is older than IPA minimum iOS")
        require(info.get("UIDeviceFamily") == [1] and "iPhoneOS" in info.get("CFBundleSupportedPlatforms", []), "IPA must target a physical iPhone")
        require(base + "_CodeSignature/CodeResources" in names and base + info.get("CFBundleExecutable", "") in names, "IPA is missing executable or signature resources")
        profile_data = member(base + "embedded.mobileprovision")
    decoded = subprocess.run(["openssl", "cms", "-verify", "-inform", "DER", "-noverify"], input=profile_data,
                             capture_output=True, timeout=10)
    require(decoded.returncode == 0, "embedded profile CMS verification failed")
    profile = plistlib.loads(decoded.stdout)
    entitlements = profile.get("Entitlements", {})
    teams = profile.get("TeamIdentifier", [])
    require(len(teams) == 1 and entitlements.get("application-identifier") == teams[0] + "." + BUNDLE,
            "development profile must match exact team/bundle; wildcards are rejected")
    require(entitlements.get("get-task-allow") is True and not profile.get("ProvisionsAllDevices"), "IPA requires development provisioning")
    require(udid in profile.get("ProvisionedDevices", []), "development profile does not contain selected phone")
    require(bool(profile.get("DeveloperCertificates")), "development profile has no signing certificate")
    expiration = profile.get("ExpirationDate")
    require(isinstance(expiration, datetime) and expiration.replace(tzinfo=timezone.utc) > datetime.now(timezone.utc), "development profile expired")
    return meta, data


def receipt_path(state, operation):
    return state / f"{operation}.json"


def begin(state, udid, operation, **details):
    path = receipt_path(state, operation)
    previous = load(path) if path.exists() else {}
    require(previous.get("outcome") != "unknown", f"uncertain {operation}; run reconcile with explicit UDID before considering recovery")
    record = {"udid": udid, "operation": operation, "outcome": "unknown", "started_at": time.time(), **details}
    save(path, record)
    return record


async def install_once(client, state, udid, meta, data):
    from pymobiledevice3.services.installation_proxy import InstallationProxyService
    before = await installed(client)
    if before.get("installed") and str(before.get("build_number")) == str(meta["build_number"]) and before.get("version") == meta["version"]:
        emit(outcome="already-present", observation=before, artifact_bytes_on_device_verified=False)
        return
    record = begin(state, udid, "install", sha256=meta["sha256"], revision=meta["revision"],
                   run_id=meta["run_id"], run_attempt=meta["run_attempt"], version=meta["version"], build_number=meta["build_number"])
    try:
        async with InstallationProxyService(client) as service:
            # Exactly the bytes that passed hash checks. Upgrade preserves application data.
            await service.install_from_bytes(data, cmd="Upgrade" if before.get("installed") else "Install")
        after = await installed(client)
        require(after.get("installed") and after.get("version") == meta["version"] and str(after.get("build_number")) == str(meta["build_number"]), "install response received but matching app state not observed")
        record.update(outcome="confirmed", observation=after)
        save(receipt_path(state, "install"), record)
        # An upgrade invalidates any earlier fixture screenshot proof.
        receipt_path(state, "launch").unlink(missing_ok=True)
        emit(outcome="confirmed", observation=after)
    except BaseException:
        raise DeviceFailure("install outcome uncertain; receipt retained, reconnect/unlock and run reconcile; do not repeat install") from None


async def reconcile(client, state, udid, operation="install"):
    if operation == "prepare":
        from pymobiledevice3.services.mobile_image_mounter import MobileImageMounterService, image_type_for_device
        path = receipt_path(state, operation)
        record = load(path)
        require(record.get("udid") == udid, "image preparation receipt device mismatch")
        async with developer(client, udid) as rsd, MobileImageMounterService(rsd) as mounter:
            mounted = await mounter.is_image_mounted(image_type_for_device(rsd))
        record.update(observed_at=time.time(), image_mounted=mounted)
        if mounted:
            record["outcome"] = "observed-mounted"
        save(path, record)
        emit(image_mounted=mounted, prepare_outcome=record["outcome"], replayed=False)
        return
    observation = await installed(client)
    path = receipt_path(state, "install")
    record = load(path) if path.exists() else None
    if record:
        require(record.get("udid") == udid, "install receipt device mismatch")
        record.update(observation=observation, observed_at=time.time())
        if record.get("outcome") == "unknown" and observation.get("installed") and observation.get("version") == record.get("version") and str(observation.get("build_number")) == str(record.get("build_number")):
            record["outcome"] = "observed-matching"
        save(path, record)
    emit(observation=observation, install_outcome=record.get("outcome") if record else "no-receipt",
         artifact_bytes_on_device_verified=False, replayed=False)


async def dvt_operation(args, client, state):
    from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
    from pymobiledevice3.services.dvt.instruments.process_control import ProcessControl
    from pymobiledevice3.services.dvt.instruments.screenshot import Screenshot
    async with developer(client, args.udid) as rsd, DvtProvider(rsd) as provider, ProcessControl(provider) as process:
        pid = await process.process_identifier_for_bundle_identifier(BUNDLE)
        if args.command == "developer-check":
            emit(developer_access=True, tunnel="temporary-userspace-usb", app_running=bool(pid))
        elif args.command == "diagnostics":
            emit(observation=await installed(client), app_running=bool(pid), pid=pid,
                 debuggable=await process.is_debuggable(pid) if pid else None)
        elif args.command == "launch":
            require(not pid, "app already running; close it on phone before explicit fixture launch")
            install_record = load(receipt_path(state, "install"))
            require(install_record.get("udid") == args.udid and install_record.get("outcome") in ("confirmed", "observed-matching"), "fixture launch requires a verified installation receipt")
            observation = await installed(client)
            require(observation.get("version") == install_record.get("version") and str(observation.get("build_number")) == str(install_record.get("build_number")), "installed app no longer matches verified artifact")
            record = begin(state, args.udid, "launch", fixture=True, sha256=install_record["sha256"])
            try:
                launched = await process.launch(BUNDLE, arguments=["--fixture"], kill_existing=False)
                observed = await process.process_identifier_for_bundle_identifier(BUNDLE)
                require(launched == observed and bool(observed), "fixture process not observed after launch")
                record.update(outcome="confirmed", pid=observed)
                save(receipt_path(state, "launch"), record)
                emit(outcome="confirmed", fixture=True, pid=observed)
            except BaseException:
                raise DeviceFailure("fixture launch outcome uncertain; inspect app-specific diagnostics; do not automatically repeat launch") from None
        elif args.command == "screenshot":
            require(args.fixture_screen_confirmed, "confirm synthetic fixture app is visible with notifications dismissed using --fixture-screen-confirmed")
            record = load(receipt_path(state, "launch"))
            require(record.get("udid") == args.udid and record.get("outcome") == "confirmed" and record.get("fixture") and record.get("pid") == pid and pid and time.time() - record["started_at"] < 300,
                    "screenshot needs a confirmed fixture launch on this device within five minutes")
            output = state / f"fixture-{int(time.time())}.png"
            async with Screenshot(provider) as screenshot:
                data = await screenshot.get_screenshot()
            require(data.startswith(b"\x89PNG\r\n\x1a\n"), "screenshot service returned an unsupported image format")
            descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
            emit(outcome="captured", private_file=str(output), sha256=hashlib.sha256(data).hexdigest())


async def dispatch(args):
    require(importlib.metadata.version("pymobiledevice3") == PIN, "pymobiledevice3 version does not match helper pin")
    if args.command == "doctor":
        for module in ("pymobiledevice3.lockdown", "pymobiledevice3.services.installation_proxy",
                       "pymobiledevice3.remote.userspace_tunnel", "pymobiledevice3.remote.remote_service_discovery",
                       "pymobiledevice3.services.mobile_image_mounter", "pymobiledevice3.services.amfi",
                       "pymobiledevice3.services.dvt.instruments.process_control",
                       "pymobiledevice3.services.dvt.instruments.screenshot"):
            importlib.import_module(module)
        service = subprocess.run(["systemctl", "is-active", "usbmuxd.service"], capture_output=True, timeout=10)
        devices = await discover()
        emit(pymobiledevice3=PIN, developer_api_imports=True, usbmuxd_service=service.stdout.decode().strip(),
             socket_access=os.access("/run/usbmuxd", os.R_OK | os.W_OK), usb_device_count=len(devices))
        return
    state = state_directory(args.state_dir)
    if args.command == "discover":
        devices = await discover()
        save(state / "discovered-devices.json", {"usb_devices": devices})
        emit(usb_device_count=len(devices), private_file=str(state / "discovered-devices.json"),
             next_step="connect and unlock iPhone using a USB data cable" if not devices else "privately read candidate UDIDs; select the intended phone explicitly")
        return
    valid_udid(args.udid)
    with device_lock(state):
        if args.command not in ("pair", "select", "artifact-check"):
            selected(state, args.udid)
        if args.command == "artifact-check":
            meta, _ = verify_artifact(args, args.udid)
            emit(artifact_checked=True, sha256=meta["sha256"], revision=meta["revision"], phone_install_tested=False)
            return
        async with lockdown(args.udid, state, paired=args.command != "pair") as client:
            if args.command == "pair":
                if not client.paired:
                    # timeout=0 sends exactly one Pair request; no hidden Trust polling.
                    await client.pair(timeout=0)
                emit(pair_request_accepted=True, next_step="confirm Trust physically, then run select/readiness; no pairing retries were sent")
            elif args.command == "select":
                record = identity(client)
                prior = state / "selected-device.json"
                require(not prior.exists() or load(prior).get("udid") == args.udid,
                        "another phone is registered; use a separate private --state-dir")
                save(prior, record)
                emit(selected=True, model=record["model"], os_version=record["os_version"], private_file=str(prior))
            elif args.command == "readiness":
                emit(**await ready(client))
            elif args.command == "developer-mode-menu":
                from pymobiledevice3.services.amfi import AmfiService
                identity(client)
                record = begin(state, args.udid, "developer-mode-menu")
                await AmfiService(client).reveal_developer_mode_option_in_ui()
                record["outcome"] = "confirmed"
                save(receipt_path(state, "developer-mode-menu"), record)
                emit(menu_revealed=True, next_step="enable Developer Mode in Settings, restart and confirm physically; then run readiness")
            elif args.command == "install":
                await ready(client, developer=True)
                meta, data = verify_artifact(args, args.udid, client.product_version)
                await install_once(client, state, args.udid, meta, data)
            elif args.command == "reconcile":
                await reconcile(client, state, args.udid, args.operation)
            elif args.command == "recover":
                path = receipt_path(state, args.operation)
                record = load(path)
                require(record.get("udid") == args.udid and record.get("outcome") == "unknown", "no uncertain operation for selected phone")
                if args.operation in ("install", "prepare"):
                    require(record.get("observed_at", 0) > record["started_at"], "run reconcile before explicitly allowing another install")
                else:
                    require(args.app_closed_on_phone, "close app on phone and acknowledge with --app-closed-on-phone before launch recovery")
                    from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
                    from pymobiledevice3.services.dvt.instruments.process_control import ProcessControl
                    async with developer(client, args.udid) as rsd, DvtProvider(rsd) as provider, ProcessControl(provider) as process:
                        require(not await process.process_identifier_for_bundle_identifier(BUNDLE), "app is still running; launch recovery rejected")
                record.update(outcome="recovery-authorized", acknowledged_at=time.time())
                save(path, record)
                emit(outcome="recovery-authorized", operation=args.operation, replayed=False)
            elif args.command == "prepare":
                from pymobiledevice3.services.mobile_image_mounter import auto_mount, MobileImageMounterService, image_type_for_device
                await ready(client, developer=True)
                async with developer(client, args.udid) as rsd:
                    async with MobileImageMounterService(rsd) as mounter:
                        mounted = await mounter.is_image_mounted(image_type_for_device(rsd))
                    if not mounted:
                        record = begin(state, args.udid, "prepare")
                        await auto_mount(rsd)
                        record["outcome"] = "confirmed"
                        save(receipt_path(state, "prepare"), record)
                    elif receipt_path(state, "prepare").exists():
                        record = load(receipt_path(state, "prepare"))
                        require(record.get("udid") == args.udid, "image preparation receipt device mismatch")
                        record["outcome"] = "observed-mounted"
                        save(receipt_path(state, "prepare"), record)
                    emit(image_mounted=True, already_mounted=mounted, tunnel="temporary-userspace-usb")
            else:
                await dvt_operation(args, client, state)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--state-dir", type=Path, default=Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "remote-codex/ios-device")
    commands = result.add_subparsers(dest="command", required=True)
    for command in ("doctor", "discover", "pair", "select", "readiness", "developer-mode-menu", "prepare", "developer-check", "artifact-check", "install", "reconcile", "recover", "launch", "screenshot", "diagnostics"):
        item = commands.add_parser(command)
        if command not in ("doctor", "discover"):
            item.add_argument("--udid", required=True)
        if command in ("artifact-check", "install"):
            item.add_argument("--artifact-dir", type=Path, required=True)
            item.add_argument("--sha256", required=True)
            item.add_argument("--revision", required=True)
            item.add_argument("--run-id", type=int, required=True)
            item.add_argument("--run-attempt", type=int, required=True)
            item.add_argument("--bundle-id", required=True)
        if command == "screenshot":
            item.add_argument("--fixture-screen-confirmed", action="store_true")
        if command == "recover":
            item.add_argument("--operation", choices=("install", "launch", "prepare"), required=True)
            item.add_argument("--app-closed-on-phone", action="store_true")
        if command == "reconcile":
            item.add_argument("--operation", choices=("install", "prepare"), default="install")
    return result


def main():
    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    args = parser().parse_args()
    try:
        # Bounded even if a USB unplug leaves a developer API waiting indefinitely.
        asyncio.run(asyncio.wait_for(dispatch(args), timeout=240 if args.command in ("install", "prepare") else 45))
        return 0
    except DeviceFailure as error:
        emit(ok=False, reason=str(error))
    except (KeyboardInterrupt, asyncio.CancelledError, TimeoutError):
        emit(ok=False, reason="interrupted/timed out; observe device state and retained receipts before explicit recovery")
    except Exception as error:
        # Library exception strings can include UDIDs, paths, RPCs or application data.
        guidance = {
            "PairingDialogResponsePendingError": "confirm Trust on the unlocked phone; inspect readiness before an explicit new pair request",
            "UserDeniedPairingError": "Trust was denied on phone; the owner must authorize Trust physically",
            "PasswordRequiredError": "unlock the selected phone with its passcode",
            "PasscodeRequiredError": "unlock the selected phone with its passcode",
            "NotTrustedError": "Trust this host on the selected phone",
            "DeveloperModeIsNotEnabledError": "enable Developer Mode, restart and confirm physically",
            "DeviceVersionNotSupportedError": "selected phone OS is incompatible with the pinned developer tooling",
            "NoSuchBuildIdentityError": "developer image does not support the selected phone/OS; do not repeat mounting unchanged",
            "NotMountedError": "run explicit prepare for the selected phone before developer access",
        }
        emit(ok=False, reason=guidance.get(type(error).__name__, "operation failed; check USB/Trust/Developer Mode and app-specific diagnostics; pending receipts remain uncertain"))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
