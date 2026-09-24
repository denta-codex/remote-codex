# Remote Codex

Codex on a remote host. Native Android text client for Grace's existing stock
Codex app server, over WSS and Tailscale.

## Project

- `android/app`: Compose interface, assistant entry point, local storage.
- `android/core`: stock RPC, event dispatch and conversation reconciliation.
- `forwarder`: authenticated Go loopback WebSocket proxy to the existing Unix socket.
- `deploy`: project-owned Ansible, systemd and persistent Tailscale Serve configuration.
- `protocol`: schemas captured from stock 0.154.0; no generation required on startup.
- `docs`: architecture, verification and physical-device acceptance.

## Build

Use the existing Mise JDK 17 and Android SDK 36; no toolchain installer is needed.

```sh
scripts/check
scripts/init-signing   # once for a new identity; refuses to overwrite
scripts/release
```

## Parallel worktree development

Codex worktrees run `scripts/setup-worktree` automatically. The setup validates
the existing managed JDK, Android SDK, Go and Codex toolchains, writes the ignored
Android SDK location, and creates a shared clean Android 16 base AVD when needed.
It does not install or replace toolchains.

Each worktree can run an isolated, disposable emulator from that base AVD:

```sh
scripts/emulator-start             # build, install and open; expires after one hour
scripts/emulator-start --ttl 2h    # override the lifetime
scripts/emulator-test              # run instrumentation on this worktree's emulator
scripts/emulator-stop              # stop it early
```

The emulator is read-only with respect to the shared base AVD. App and device data
last for the running emulator process and are discarded when it stops. Runtime
state is kept outside the repository. `emulator-start` stays attached to its
terminal until the emulator stops; use another terminal for tests. Re-running it
from another terminal reuses this worktree's live emulator and installs the current
debug build. It opens a window when a desktop display is available and runs
headlessly on a remote host. Fresh emulators must be paired again for interactive live testing;
`scripts/check-live-android` retains its separate temporary credential flow.

Signing defaults to `/home/agent/.local/share/remote-codex/signing` outside the repo.
Keep the PKCS12 file and password secure and backed up. The independently recorded
public certificate digest is `docs/signing-certificate.sha256`. Releases must match
it. Increase versionCode for upgrades; never uninstall to force an update.

`dist/` contains the signed APK, forwarder and SHA256SUMS. Build scripts never deploy.
Tests use an isolated Codex home and fake model, not live account credentials.

## Deployment (explicit steps)

```sh
scripts/deploy
scripts/deploy -e remote_codex_action=deploy --check --diff
scripts/deploy -e remote_codex_action=deploy
scripts/deploy -e remote_codex_action=deliver
```

Default action is preview. Deploy checks identity, release hashes and existing
Serve routes, then installs the user service and configures private Serve with
`--bg`. It does not change or restart Codex. Delivery targets the single phone in
inventory. The user opens the APK and confirms installation.

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

To rerun instrumentation on a disposable emulator:

```sh
ANDROID_SERIAL=EMULATOR_SERIAL JAVA_HOME="$(mise where java@temurin-17.0.20+8)" \
ANDROID_HOME=/home/agent/Android/Sdk \
android/gradlew -p android --no-daemon :app:connectedDebugAndroidTest
```

Read [validation results and remaining acceptance](docs/VALIDATION.md).

For explicit read-only acceptance against **live Grace**, start a disposable
Android 16 emulator and run `scripts/check-live-android emulator-SERIAL`.
This uses the actual Android client over WSS to initialize and load tasks. It
passes the host credential through an app-private FIFO, stores it using the
Android Keystore, and clears it after the test. No prompt or task mutation is
submitted. Shut down and remove the disposable emulator after acceptance.
