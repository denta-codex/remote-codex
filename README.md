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

The connection credential is generated once on deployment at
`/home/agent/.local/share/remote-codex/connection-token` (0600). Enter it in Settings;
it is never embedded in the APK. Do not print it in logs or commit it. Its compromise
allows control of the account's Codex tasks; rotate the file and restart only the
forwarder, then replace the credential in the app.

The phone needs the existing Tailscale app and no SSH client or SSH server.

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
