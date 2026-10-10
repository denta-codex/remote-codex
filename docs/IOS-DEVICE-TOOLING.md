# Grace USB iPhone tooling

`scripts/ios-device` owns Linux physical-device operations. `scripts/ios` owns
GitHub builds and exact-run artifact downloads. This helper never contacts the
Remote Codex server. Existing Android connectivity and stock Codex/WSS remain
unchanged.

## Verified host gate, October 10, 2026

Host-level inspection, after installing only the missing `usbmuxd` package:

| Component | Observed result |
| --- | --- |
| libimobiledevice | Arch package 1.4.0-2; existing idevice_id/idevicepair/ideviceinfo |
| usbmuxd | Installed 1.1.1-4; packaged static service running |
| USB access | /run/usbmuxd socket 0666, accessible as agent; packaged udev rules installed |
| libusbmuxd / libplist / libimobiledevice-glue | Existing 2.1.1-2 / 2.7.0-3 / 1.3.2-1 |
| uv / Python | Existing managed uv 0.12.11 and Python 3.14.7 |
| pymobiledevice3 | 11.26.0, isolated uv environment; transitive dependencies hash-locked |
| Discovery | idevice_id and helper both succeed with **zero USB devices** |
| Phone model, OS, Trust, Developer Mode | **Not established** |
| Signed install, launch, screenshot, diagnostics, draft preservation | **Not run on hardware** |

No full system upgrade, toolchain replacement, system Python install, USB group
change, or ideviceinstaller installation was needed. pymobiledevice3 supplies
installation and developer APIs. The packaged udev rules start the static service
when an Apple USB device arrives; there is no separate usbmuxd socket unit.

## Runtime and private state

Run through `scripts/ios-device`; its wrapper uses managed uv, the checked-in
script lock, and `--no-python-downloads`. An existing Python >=3.12 is required.
First use may download the locked packages into uv's isolated cache. No packages
are installed into system Python. OpenSSL and the packaged usbmuxd service are
also required.

Default state: `${XDG_STATE_HOME:-$HOME/.local/state}/remote-codex/ios-device`.
Directories are 0700; metadata, pairing cache, receipts and screenshots are 0600.
`--state-dir /absolute/private/path` goes **before** the subcommand and must be
outside this repository. Use a separate directory for a different phone.

`discovered-devices.json` holds candidate USB UDIDs. `selected-device.json` holds
the actual selected UDID, product type, model, OS version and OS build. This is
the private local metadata path for the signing owner to validate and register
the selected phone in Apple's portal. Do not paste its contents into chat,
tracked files, public issues, CI variables or build logs. Share the local path
and a redacted readiness result. No record exists until actual selection.

All device commands require `--udid`, including reads. Saved selection never
silently supplies it. The helper rejects missing, duplicated or mismatched
USB identities. Discovery lists no device names, serial numbers or UDIDs on
stdout; it reports the count and private file path. Shell tracing must be off
when using private UDIDs. Operational output excludes library exception text,
RPCs, credentials, transcripts and application stdout/stderr.

## Connect, trust and select

Connect the intended **iPhone 13 mini with iOS 18 or newer** directly to Grace
using a USB data cable. Unlock it and keep it connected. The helper accepts
other iPhones on iOS >=18, reports their actual product type, and never infers
that an attached phone is the expected model.

```sh
scripts/ios-device doctor
scripts/ios-device discover
```

Privately read `discovered-devices.json`, identify the intended physical phone,
and supply that exact UDID. The following examples assume a locally set
`PHONE_UDID`; do not include its value in a shared command transcript.

```sh
scripts/ios-device pair --udid "$PHONE_UDID"
scripts/ios-device select --udid "$PHONE_UDID"
scripts/ios-device readiness --udid "$PHONE_UDID"
```

`pair` sends at most one Pair request. If the phone asks to Trust, the owner must
confirm on the phone. A pending response is reported; the helper does not poll
Pair requests. Inspect readiness/selection after confirmation before considering
another explicitly requested pair. `select` stores actual identity only after
Trust is established and the model/OS checks pass. A different saved selection
requires a separate private state directory.

Enable **Settings > Privacy & Security > Developer Mode**, restart, and confirm
Developer Mode on the phone. If the menu is absent, this explicit command only
reveals the toggle:

```sh
scripts/ios-device developer-mode-menu --udid "$PHONE_UDID"
```

It never enables Developer Mode, restarts the phone, answers post-restart prompts,
or removes a passcode. After physical confirmation:

```sh
scripts/ios-device readiness --udid "$PHONE_UDID"
scripts/ios-device prepare --udid "$PHONE_UDID"
scripts/ios-device developer-check --udid "$PHONE_UDID"
```

`prepare` checks whether the developer image is mounted and mounts it once if
needed, using pymobiledevice3's OS-appropriate personalized image support. This
can download/cache a developer image and contact Apple's personalization service.
Preparation is explicit, never an install/launch fallback. Unsupported device/image
combinations fail; do not rerun an unchanged incompatible setup.

Developer commands create a temporary userspace CoreDevice USB tunnel from the
already verified USB connection, validate the RSD UDID, and close the tunnel on
completion. There is no privileged tunnel daemon, Bonjour/network fallback,
external RSD endpoint, or legacy pre-iOS-18 workflow. `developer-check` opens
the DVT process service; success establishes developer access, not installation.

## Exact artifact and one installation

Signing and workflow setup are described in `IOS-DEVELOPMENT-SIGNING.md` on its
owner's branch. Actual installation waits for successful unsigned CI, default
branch workflow registration, validated development signing material, and the
selected phone's readiness. The profile must include that phone and exactly
match `dev.codexops.client.ios` with `get-task-allow=true`.

The build chat supplies `ios-development-<run_id>-<run_attempt>` with exactly
`RemoteCodex.ipa`, `metadata.json`, and `SHA256SUMS`. Schema 1 keys are:

```text
schema_version, repository, revision, run_id, run_attempt, artifact, sha256,
bundle_id, version, build_number, signing, minimum_ios, fixture_launch_argument
```

Values include repository `denta-codex/remote-codex`, full source SHA, artifact
`RemoteCodex.ipa`, bundle `dev.codexops.client.ios`, signing `development`, minimum
iOS `18.0`, and fixture argument `--fixture`. Old aliases are rejected.

Acceptance independently supplies the expected full source revision, GitHub run,
attempt, IPA SHA256 and bundle. Obtain these from the reviewed exact successful
run, not merely by copying an untrusted local manifest. The build helper retrieves
an exact run, never "latest":

```sh
scripts/ios download "$RUN_ID" "$EXPECTED_REVISION" "$ARTIFACT_DIR"
scripts/ios-device artifact-check --udid "$PHONE_UDID" \
  --artifact-dir "$ARTIFACT_DIR" --sha256 "$EXPECTED_IPA_SHA256" \
  --revision "$EXPECTED_REVISION" --run-id "$RUN_ID" --run-attempt "$RUN_ATTEMPT" \
  --bundle-id dev.codexops.client.ios
scripts/ios-device install --udid "$PHONE_UDID" \
  --artifact-dir "$ARTIFACT_DIR" --sha256 "$EXPECTED_IPA_SHA256" \
  --revision "$EXPECTED_REVISION" --run-id "$RUN_ID" --run-attempt "$RUN_ATTEMPT" \
  --bundle-id dev.codexops.client.ios
scripts/ios-device reconcile --udid "$PHONE_UDID"
```

Preflight checks metadata, checksum, embedded bundle/build/platform/minimum OS,
signature resources, CMS profile integrity, exact team/bundle, development
entitlements, expiry, certificate presence, and selected UDID coverage. The
verified bytes are the bytes sent to installation proxy. OpenSSL's CMS check
does not establish Apple chain trust or validate the executable's code signature;
iOS enforces signing during installation. Installed-build readback establishes
version/build presence, not the hash of installed executable bytes.

The helper installs once and reads back only this bundle's version/build. Existing
apps receive `Upgrade`, preserving their container and Keychain. There is no
uninstall, data reset or automatic reinstall. An already matching version/build
produces an observation without mutation.

## Synthetic fixture proof and preservation

```sh
scripts/ios-device launch --udid "$PHONE_UDID"
scripts/ios-device diagnostics --udid "$PHONE_UDID"
scripts/ios-device screenshot --udid "$PHONE_UDID" --fixture-screen-confirmed
```

Launch requires a validated installation receipt and always supplies `--fixture`,
with no arbitrary arguments, production environment or automatic server pairing.
The fixture uses `RemoteCodexFixture/client.sqlite`, in-memory credentials, and
`chat-fixture-chat`. Existing production drafts and Keychain are preserved.
An already running app must be closed on the phone before fixture launch; the
helper does not silently kill it or claim its existing arguments are synthetic.

Before screenshot, the owner confirms the synthetic app is visible and dismisses
notifications. The explicit confirmation flag acknowledges this physical check;
it is not automated foreground verification. Capture requires a confirmed fixture
launch within five minutes and the same app PID. It stores the screenshot privately
and prints only its path/hash. Diagnostics report only the selected app's
version/build, running PID and debuggability; no device-wide syslog, crash dump,
filesystem export or application output is collected.

Acceptance should type a synthetic draft, close/relaunch the app in fixture mode,
confirm preservation, then install a reviewed higher-build signed IPA **in place**
and verify the same draft again. Physical UI interactions and this preservation
proof have not been performed. Appium/WDA belong to a later task.

## Interruption and recovery

Private receipts are flushed before install, launch, image mount and menu reveal.
Timeouts are bounded (240 seconds for install/image preparation; 45 seconds for
other commands). Any interrupted mutation remains uncertain, including process
termination. Another uncertain mutation of the same kind is rejected.

After an interrupted install, reconnect/unlock and run `reconcile`. Matching
installed-build readback is recorded as `observed-matching`; this does not prove
which request installed the bytes. Nonmatching/absent state remains uncertain.
Only after reviewing that observation may the owner explicitly allow recovery:

```sh
scripts/ios-device recover --udid "$PHONE_UDID" --operation install
```

This changes only the private receipt and never replays installation. A subsequent
explicit install must still pass all artifact checks. For uncertain image mounting,
use `reconcile --operation prepare`, review the observed mounted state, and only
if needed use `recover --operation prepare` before a new explicit preparation.
For uncertain launch, inspect diagnostics, close the app physically, then use
`recover --operation launch --app-closed-on-phone`; recovery verifies the app is
no longer running. Never infer fixture arguments from a PID after uncertainty.
If menu reveal is interrupted, inspect Settings physically; do not automatically
repeat it. No recovery unpairs the device or clears application data.

## Validation and sources

Offline tests: `uv run --no-project tests/ios_device_test.py`. They create a
disposable synthetic CMS certificate/profile and mock device services; they do
not validate Apple signing or physical iOS behavior. Test staging is removed.

Implementation follows the pinned upstream APIs in
[pymobiledevice3 11.26.0](https://pypi.org/project/pymobiledevice3/11.26.0/),
particularly `lockdown`, `remote/tunnel_service`, `remote/userspace_tunnel`,
`services/mobile_image_mounter`, `installation_proxy` and DVT instruments.
Upstream source: [pymobiledevice3](https://github.com/doronz88/pymobiledevice3).
Host service/USB behavior was inspected directly from Arch's packaged unit and
`/usr/lib/udev/rules.d/39-usbmuxd.rules`.
