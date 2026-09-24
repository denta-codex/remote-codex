# Validation

## September 24, 2026 — projects and workspace execution parity

- Checked the stock 0.154.0 schemas for paginated `project/list`, nullable
  `thread/list.projectId`, and `thread/start.projectId`. The ignored ChatGPT Android
  1.2026.258 reference confirmed its named/projectless selection states, the
  `__codex_projectless_chats` Chats presentation, and `project/list` records with
  `id`, `name`, and `roots[].path`.
- The Android task browser presents All, projectless Chats, and every paged existing
  project, and filters task reads by the selected project. New chats default to
  projectless; selecting a project offers its current first root or a new isolated
  detached worktree from local `origin/HEAD` under the connected Codex home.
- Project choice is persisted with `NewTaskOptions` and copied into the operation
  journal before mutation dispatch. Reconnect recovery uses project plus cwd to
  find a server-owned thread and does not replay `thread/start` or `turn/start`.
- `scripts/check` passed: Go race tests/vet, Kotlin core tests, Android debug build,
  lint, and debug instrumentation APK assembly.
- All ten fixture-backed Android tests passed on the worktree-owned Android 16
  emulator. Coverage includes paginated project loading, project/Chats filtering,
  project selection across ViewModel recreation and reconnect, exact project-root
  `thread/start`, retained projectless directory preparation, and uncertain project
  journal recovery with zero mutation replay. The credential-gated live connection
  test was skipped; no live deployment or phone delivery was performed.
- Added pure JVM coverage for destination planning, invalid selections, selected
  `project/read` validation, detached Git argv, exact worktree reconciliation, and
  the narrower projectless sandbox policy.
- Ten fixture-backed Android tests passed on this worktree's disposable Android 16
  emulator. New cases cover current-checkout and worktree RPC wiring, plus
  deliberately lost `git worktree add` and `thread/start` responses. Reconnect
  observed the deterministic registered worktree/task, did not repeat either
  creation, and proceeded to one input submission. The credential-gated live
  connection test remained skipped as designed.
- `scripts/check` passed, including Go race/vet and isolated stock lifecycle tests,
  Kotlin/JVM tests, Android debug build, lint, and instrumentation APK assembly.
- No live deployment, physical-phone delivery, or worktree cleanup was performed.
  Project browsing populates `projectId` and the selected absolute root in
  `workingDirectory` for the workspace adapter.

## September 23, 2026 — 0.1.3 live Android connection fix

- Razr 0.1.2 remained disconnected after the shutdown fix. Credential-free HTTPS
  from the phone returned the expected 401 with successful TLS verification.
  A signed 0.1.3 update (versionCode 4) added transport status/type diagnostics
  without headers, exception messages, RPC payloads, or transcripts.
- The phone reported HTTP 502 during WebSocket upgrade. Direct control-socket
  probes established that stock Codex accepts an ordinary upgrade but closes
  the request when it contains OkHttp's default `permessage-deflate` offer.
- Reproduced the same 502 using the actual Android app on a disposable Android 16
  emulator against live Grace, and in the isolated-stock integration test.
  The forwarder now removes extension offers before proxying the handshake, so
  neither side negotiates compression; WebSocket frames and RPC remain unchanged.
- `scripts/release` passed, including Go race tests and the full isolated-stock
  lifecycle with the compression offer, Kotlin handshake-error regression,
  Android build/lint, release lint and signing checks. All four existing Android
  emulator tests passed. Ansible deployment dry run and live deployment passed;
  unauthenticated requests still receive 401.
- After deployment, `scripts/check-live-android emulator-5580` passed against
  the live WSS endpoint: the app initialized and loaded tasks. The credential
  traveled through an app-private FIFO into Keystore-backed storage and was
  cleared afterward. The test sent no prompt or task mutation.
- The signed 0.1.3 APK is installed on the physical Razr, preserving its existing
  scanned credential. Its UI now reports **Connected to Grace**. APK SHA-256:
  `2b6e26f30c9ea7eb81d9815f334a63769e52db6ad4b735cfd3879439f932b22f`.
- Reboot persistence and the remaining physical interaction checklist are still
  separate acceptance steps; this check establishes authenticated WSS and task loading.

## September 23, 2026 — 0.1.2 Razr crash fix

- The installed 0.1.1 app (versionCode 2) crashed on the Razr when its activity
  was destroyed: `ClientModel.onCleared` called `Rpc.dispose`, and OkHttp's
  connection-pool eviction raised `NetworkOnMainThreadException`.
- Reproduced the same exception with the existing `draftSurvivesNewModel`
  instrumentation test on a disposable Android 16 emulator. Moving RPC disposal
  to a named cleanup thread made all four instrumentation tests pass.
- `scripts/release` passed the Go race tests and vet, Kotlin tests, Android debug
  build and lint, release build and lint, package identity, signer and
  non-debuggable checks. Signed `dist/remote-codex-0.1.2.apk` is versionCode 3;
  SHA-256: `1b419b75839e4e7d0174b3865d6314b161ea842d020c5789f9101a6119c97160`.
- The APK installed successfully over 0.1.1 on the verified Razr via Tailscale
  ADB. Android reports versionCode 3 / versionName 0.1.2. The phone was locked at
  the next check, so the live app connection is still to be verified on screen.

## September 23, 2026 — pairing release and live transport

- Signed `dist/remote-codex-0.1.1.apk` (versionCode 2) passed `scripts/check`,
  release lint, certificate and package checks, and checksum verification.
  APK SHA-256: `470c2e7e27a209d2fba4c0dc1c97166c04c6a42a1243201da028620eb8676486`.
- The Android QR parser test, Go race tests, Ansible syntax check, and deployment
  dry run passed. A disposable systemd user unit loaded a user-scoped encrypted
  test credential successfully.
- Grace's `remote-codex-forwarder.service` is active and enabled. Its only listener
  is `127.0.0.1:8787`; persistent private Tailscale Serve maps Grace HTTPS 443 to
  it. An unauthenticated HTTPS request returns 401. A read-only authenticated WSS
  initialize reached the live stock Codex server and confirmed its account home.
- The host has only an encrypted `connection-token.cred` file and no plaintext
  connection-token file. The signed APK was delivered by Taildrop to the Razr;
  Android installation and the physical QR scan still require user action.
- Physical-device acceptance and the scheduled reboot persistence check remain
  open. Taildrop success does not establish that Android installed the APK.

## September 22, 2026 — initial release

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

At this initial checkpoint, the physical Razr had not been changed. Live Tailscale
Serve and the production forwarder were not deployed. See DEVICE-ACCEPTANCE.md for physical gesture,
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
