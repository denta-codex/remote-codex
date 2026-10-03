# Remote Codex

> **Personal project:** This is my personal setup. I'm sharing the source in the
> hope that it benefits others. I am not accepting outside pull requests at this time.

Codex on a remote host. Native Android text client for Grace's existing stock
Codex app server, over WSS and Tailscale.

Sending while Codex is working adds a follow-up to its server queue. Queued
messages appear above the composer; tap **Steer now** to use one in the active
turn, or **Remove** to cancel it. An idle, interrupted queue offers **Send now**.
Queue actions preserve anything you are currently drafting.

Take a screenshot while the app is open and tap **Report or request** in the snackbar
(Android 14+), or use **⋮ → Report or request**, to save a
report with a screenshot, recent app logs, available process-exit diagnostics,
and frozen conversation/task context. Choose **Investigate**, **Research**, **Plan**,
or **Implement**, describe your request, and preview or remove evidence on the same
form. **Review request** shows the destination, editable title, and complete message
before you explicitly start the task. Only Implement authorizes code changes;
the other intents use the server's Plan mode. Missing mode support keeps the draft
saved instead of falling back to implementation. Submitting creates an isolated
remote-codex worktree, runs its environment setup, and starts the selected task on
Grace. **Last report task** opens it. Reports do not publish, deploy, or install updates.
Settings includes an **Offer a report after screenshots** toggle
(on by default) and an opt-in **Shake to report or request** toggle, which is off by
default because shaking can trigger Motorola's flashlight gesture.

Reports remain on the phone while offline and across restarts. Reopening a saved
report preserves its intent, text, review, and original evidence; reconnecting never
submits automatically. Screenshots are omitted on the Settings
screen, which contains connection credentials. App logs cover this application's
UID only; optional full Android bug-report archives can be added as files within
the existing 20 MiB per-file / 50 MiB total limits. Capture failures are listed
in the report and do not prevent submission.

Interrupted preparation offers **Check and continue**. It inspects the existing
worktree, setup receipt, uploaded bytes, task identity, and submitted message;
uncertain mutations are never replayed. The original chat draft stays intact.

## Project

- `android/app`: Compose interface, assistant entry point, local storage.
- `android/core`: stock RPC, event dispatch and conversation reconciliation.
- `forwarder`: authenticated Rust loopback WebSocket proxy to the existing Unix socket.
- `deploy`: project-owned Ansible, systemd and persistent Tailscale Serve configuration.
- `protocol`: schemas captured from stock 0.154.0; no generation required on startup.
- `docs`: architecture, verification and physical-device acceptance.

## Build

Use the existing Mise JDK 17, Rust 1.95 and Android SDK 36 toolchains.

```sh
scripts/check
scripts/init-signing   # once for a new identity; refuses to overwrite
scripts/deploy -e remote_codex_action=build
```

The build action requires a clean checkout, chooses the next patch version and
Android build number, prepares notes from commit subjects, and calls the internal
`scripts/release` builder once. That builder already runs `scripts/check`; do not
run checks separately for a routine release. On success Ansible records the source
revision, checksums and validation outcome in a local commit. It does not push.

For an update available through the app's install button, use one command:

```sh
scripts/deploy -e remote_codex_action=release
```

Release runs the same build, publishes the private stable update, and verifies
the authenticated HTTPS manifest and complete APK. It does not deploy the
forwarder or install on the phone. Optional variables are
`remote_codex_version=X.Y.Z` and `remote_codex_notes_file=/absolute/path/notes.md`.
Console output contains stages and a compact result; detailed logs are saved in
`artifacts/releases/`. See [release operation and recovery](docs/RELEASING.md).

## Parallel worktree development

Codex worktrees run `scripts/setup-worktree` automatically. The setup validates
the existing managed JDK, Android SDK, Rust and Codex toolchains, writes the ignored
Android SDK location, and creates a shared clean Android 16 base AVD when needed.
It does not install or replace toolchains.

Automated instrumentation uses AndroidX Test Orchestrator on a Gradle-managed
Android 16 virtual device. Select the smallest relevant test set; the full suite
is explicit and reserved for cross-cutting or test-infrastructure changes:

```sh
scripts/emulator-test --tests AppTest#textChatStreamsAndCanReopen
scripts/emulator-test --cover   # Razr Ultra 2025 cover-size regression suite
scripts/emulator-test --full
```

The cover mode reproduces the 1080 × 1272, 420 dpi outer-display viewport and
resets it after every test. See [cover-screen layout and testing](docs/COVER-SCREEN.md)
for the device rationale, responsive behavior, and coverage.

Interactive inspection and fixture recording retain a worktree-owned disposable
emulator from the shared base AVD:

```sh
scripts/emulator-start             # build, install and open; expires after one hour
scripts/emulator-start --ttl 2h    # override the lifetime
scripts/emulator-record textChatStreamsAndCanReopen
scripts/emulator-record coverScreenDestinationsRemainReachable --cover
scripts/emulator-stop              # stop it early
```

The emulator is read-only with respect to the shared base AVD. App and device data
last for the running emulator process and are discarded when it stops. Runtime
state is kept outside the repository. `emulator-start` stays attached to its
terminal until the emulator stops; use another terminal for recording. Re-running it
from another terminal reuses this worktree's live emulator and installs the current
debug build. It opens a window when a desktop display is available and runs
headlessly on a remote host. Fresh emulators must be paired again for interactive live testing;
`scripts/check-live-android` retains its separate temporary credential flow.
`emulator-record` captures one named `AppTest` method with fixture data and writes
the ignored MP4 under `artifacts/demos/`. Pass `--output /absolute/path/demo.mp4`
to place a requested clip elsewhere. Its optional demo pauses do not slow normal tests.

Signing defaults to `/home/agent/.local/share/remote-codex/signing` outside the repo.
Keep the PKCS12 file and password secure and backed up. The independently recorded
public certificate digest is `docs/signing-certificate.sha256`. Releases must match
it. Increase versionCode for upgrades; never uninstall to force an update.

`dist/` contains the signed APK, forwarder and SHA256SUMS. The build action never publishes or deploys.
Tests use an isolated Codex home and fake model, not live account credentials.

## Deployment (explicit steps)

```sh
scripts/deploy
scripts/deploy -e remote_codex_action=deploy --check --diff
scripts/deploy -e remote_codex_action=deploy
scripts/deploy -e remote_codex_action=publish --check --diff
scripts/deploy -e remote_codex_action=publish
scripts/deploy -e remote_codex_action=deliver
```

Default action is preview. Deploy checks identity, release hashes and existing
Serve routes, then installs the user service and configures private Serve with
`--bg`. It does not change or restart Codex. Delivery targets the single phone in
inventory. The user opens the APK and confirms installation.

Publish is the explicit prepared-artifact publication action; release includes
it after building. It requires a running host
that exposes the authenticated update extension, but Android-only releases do not
require an identical forwarder build. It copies the signed APK to an immutable
private release path and atomically advances the stable manifest. In app Settings,
**Check for updates** contacts only the authenticated
`/remote-codex/v1/updates/` extension routes. **Download and install** verifies
the manifest, APK hash, package, version and signing certificate before asking
Android to confirm installation. There is no automatic or background check.
Taildrop delivery remains the bootstrap and recovery path.

Deployment creates a single user-scoped systemd encrypted credential at
`/home/agent/.local/share/remote-codex/connection-token.cred` (0600). The user
service receives its plaintext runtime copy through `LoadCredentialEncrypted`;
the token is never embedded in the APK or stored as a plaintext host file.
From a private interactive terminal, run `scripts/show-connection-qr`, then use
**Scan setup QR** in app Settings. Manual entry remains available. The helper
uses the terminal's alternate screen and creates no plaintext file; do not run it
through captured command output or share the screen while pairing. The QR holds
the bearer token, so anyone who copies it can control this account's Codex tasks.
If it is exposed, replace the encrypted credential, restart only the forwarder,
and pair the phone again. If the host encryption key is lost, generate a new
credential and re-pair.

The phone needs the existing Tailscale app and no SSH client or SSH server. The
transport bearer credential is removed before any stock Codex RPC reaches the
control socket.

## Recovery

Inspect `systemctl --user status remote-codex-forwarder` and `tailscale serve status`.
Stop only this service if needed. Remove only its root route with
`tailscale serve --https=443 --set-path=/ off`, after verifying it still points to
127.0.0.1:8787. Do not reset all Serve configuration. Codex tasks remain on the
existing server. Correct APK issues with a higher version and the same signing key.

See [architecture](docs/ARCHITECTURE.md) and [physical acceptance](docs/DEVICE-ACCEPTANCE.md).

To rerun selected instrumentation on the managed device:

```sh
scripts/emulator-test --tests AppTest#textChatStreamsAndCanReopen
```

Read [validation results and remaining acceptance](docs/VALIDATION.md).

For explicit read-only acceptance against **live Grace**, start a disposable
Android 16 emulator and run `scripts/check-live-android emulator-SERIAL`.
This uses the actual Android client over WSS to initialize and load tasks. It
passes the host credential through an app-private FIFO, stores it using the
Android Keystore, and clears it after the test. No prompt or task mutation is
submitted. Shut down and remove the disposable emulator after acceptance.

## Connection diagnosis

Run `uv run --no-project scripts/connection-check.py` for a read-only check of the
same authenticated WSS route used by the phone. It checks initialization and
project/task listing, including an empty account, and prints only result metadata.
Inspect `journalctl --user -u remote-codex-forwarder.service` for bounded upstream
failure reason codes. On hosts with system-only journals, use
`sudo journalctl _UID=$(id -u) _SYSTEMD_USER_UNIT=remote-codex-forwarder.service`.

The deployed checks are under `~/.local/libexec/remote-codex-checks/`. Runtime
upgrades require an isolated candidate check with the installed forwarder and its
systemd protections before staging, plus a live WSS check before acceptance.
