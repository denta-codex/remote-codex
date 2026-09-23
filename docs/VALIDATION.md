# Validation — September 22, 2026

## Completed locally

- Go forwarder authentication, wrong-route/origin rejection, socket ownership/type,
  and unchanged bidirectional upgraded traffic: passed under `go test -race`.
- Isolated stock Codex 0.154.0 + mock Responses model over TLS/WSS: task creation,
  two clients, completion after creator disconnect, search, full history, reconnect,
  and a real command-approval/decline round trip passed.
- Kotlin core tests: overlapping request IDs, stale-connection responses,
  canonical completion replacing deltas, history/stream overlap, and turn-scoped
  permission grants passed.
- Four Android 16 emulator instrumentation tests passed: streamed text and reopen,
  uncertain send not replayed, desktop-resolved approval removal, and draft
  restoration in a new ViewModel using persistent storage.
- Android debug build and lint passed. Lint warnings concern newer dependency/SDK
  versions; no lint errors remain. Existing managed SDK 36/JDK 17 were used.
- Ansible syntax and default preview passed without deployment.

## Findings incorporated

A newly allocated stock task may have no persisted rollout before its first input.
Just after input acceptance, stock history/resume can briefly reject reads with
`list_turns is not supported yet`. The app retries those reads within a bounded
window and never resends the accepted mutation. Initial streaming uses the live
subscription. Search returns nested `thread` objects, unlike `thread/list`.

The first emulator run used software acceleration because sandbox inspection hid
KVM. Host inspection confirmed usable KVM; the disposable emulator was restarted
with hardware acceleration and completed its tests. No existing emulator was used.

## Remaining acceptance

The physical Razr has not been changed. Live Tailscale Serve and the production
forwarder are not deployed. See DEVICE-ACCEPTANCE.md for physical gesture,
connectivity, approval-recovery, screen lock/folding and upgrade acceptance.

The signing identity is durable on Grace at
`/home/agent/.local/share/remote-codex/signing` with restrictive permissions. The
configured op-bridge route to XPS failed both metadata access and its diagnostic;
no authentication was changed. Off-host signing backup remains pending.

File/permission/question UI uses the saved stock contracts. A real command approval
has been exercised against isolated stock; actual late file-approval recovery still
requires device/server acceptance. Unsupported server requests direct the user to
desktop. Missing approval context is never silently accepted.

## Release artifact

The final signed `dist/remote-codex-0.1.0.apk` (versionCode 1) passed release lint,
certificate/package/non-debuggable verification and installed successfully on the
Android 16 disposable emulator. Cold launcher startup and warm ACTION_ASSIST
handoff both reached MainActivity successfully. The rendered New chat screen was
visually inspected and is saved as `release-new-chat.png`.

The concrete Ansible deployment check (`remote_codex_action=deploy --check --diff`)
passed: 14 tasks OK, four simulated changes, zero failures. No service or Serve
route was installed. APK SHA-256:
`7add1e7f8562f0490a56143628af2ce7ebd6b2ed17f0aa4ca1edc8b3f0c19ce9`.

Physical-device installation, exact-host WSS connectivity, and actual assistant
gesture are still pending user-authorized live deployment and delivery.
