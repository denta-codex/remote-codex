# Validation

## September 29, 2026 — Ansible build and release workflow

- Added build-only and complete release actions to the existing Ansible entry
  point, with shared task groups, automatic version allocation, one-time checks,
  signed artifact verification, HTTPS publication verification, and local commits.
- Ansible syntax validation and the non-mutating preview passed. Twelve initial
  fixture regression tests passed; three focused follow-up tests passed after
  adding cross-worktree version reservations and matching Android's rejection of
  APK redirects. Together these cover thirteen distinct test cases, including
  dirty checkouts, explicit versions/notes, host locking, immutable conflicts,
  failed builds, authentication/checksum failures, and failure after publication.
- Fixture tests use disposable repositories, a fake build primitive, and a local
  TLS server. They verify checks run once, build-only does not publish, credentials
  are excluded from logs, and publication/verification state is reported honestly
  if local Git recording fails. No live publication or phone operation occurred.

## September 29, 2026 — Android 0.2.3 publication

- Advanced Android to 0.2.3 / code 13 with queued follow-ups, explicit steering,
  and compact-screen queue controls from commit `40eb468`.
- `scripts/check` and `scripts/release` passed using the existing managed Codex
  executable for isolated-stock tests. Rust formatting, Clippy, all seven Rust
  tests, Kotlin and Android unit tests, debug and instrumentation assembly,
  debug/release lint, release signing and package verification passed.
- The Ansible publication dry run and private stable-channel publication passed.
  The signed APK SHA-256 is
  `0b9ad051ef45cd8f92d446eed81372bb5665f3d05ca9274fedcc5cef174415b8`.
- Verified the private HTTPS endpoint rejects unauthenticated requests with 401.
  The authenticated manifest matches the prepared 0.2.3 manifest byte for byte;
  the complete APK download matches its declared size and SHA-256.
- Installation remains user initiated through Settings → Check for updates →
  Download and install. No forwarder deployment, Taildrop delivery, or phone
  installation was performed.

## September 29, 2026 — queued sending and explicit steering

- Normal send during an active turn (or behind existing queued entries) now uses
  stock `thread/queue/add`. The composer lists the server-owned queue and offers
  Steer now, idle Send now, and Remove. Queue actions preserve the current draft.
- Inspected desktop 26.924.22138 and Android reference 1.2026.258; see
  [queue/steer research](QUEUE-STEER-RESEARCH.md) for source locations and protocol
  findings. No app-server or forwarder changes were necessary.
- `scripts/check` passed with `REMOTE_CODEX_TEST_CODEX` set to the existing managed
  Codex 0.155.1 executable. The initial stock-socket check timed out because its
  isolated home could not resolve the Mise shim from `command -v codex`; resolving
  the executable fixed the check without changing toolchains or shared settings.
  Rust formatting, Clippy, all seven Rust tests, Kotlin unit tests, Android debug
  and instrumentation assembly, and lint passed (zero lint errors).
- Eight selected Android 16 emulator cases passed with no failures or skips:
  multiple queued messages and steering while preserving a draft; process
  recreation, task switching, and other-client queue notifications; idle queue
  start; lost add and steer acknowledgements without replay; rejected steer with
  explicit retry; an already-consumed entry; and original image-input preservation.
  The normal-phone result is retained at
  `artifacts/validation/queue-steer/phone-tests.xml` (ignored fixture output).
- The focused cover-viewport run passed image steering and unavailable-queue
  handling. Its text follow-up case timed out because the compact keyboard layout
  could push sending controls out of reach. The correction hides disabled
  model/mode controls and replaces the queue panel with a Queued shortcut while
  typing. The failed result is retained at
  `artifacts/validation/queue-steer/cover-initial-tests.xml`. After the correction,
  Android build, unit tests, lint, and instrumentation assembly passed. The
  explicitly approved single-case cover-screen rerun also passed with no failures
  or skips, verifying multiple queued messages, the keyboard's Queued shortcut,
  steering, removal, and draft preservation. Its result is retained at
  `artifacts/validation/queue-steer/cover-retest.xml`.
- No signed release, live deployment, phone installation, or live-model prompt
  was performed. Android behavior was verified through the stock-shaped fixture.

## September 25, 2026 — Android 0.2.2 release preparation

- Integrated compact cover-screen layouts, thread-deeplink copying, and reliable
  project-workspace validation with safe draft and attachment retention.
- Advanced Android to 0.2.2 / code 12. `scripts/check` passed, including Rust,
  Kotlin, and Android unit tests, debug and instrumentation APK assembly, and lint.
- The five-test cover-screen suite passed. In the full normal-phone suite, every
  existing test passed or skipped as designed; the new rejected-validation test
  exposed a fixture response-dispatch problem. A focused JVM RPC rejection test
  passed, the fixture was corrected, and the explicitly approved targeted emulator
  rerun passed.
- `scripts/release` repeated the checks and passed signed release assembly, release
  lint, package and signer verification, manifest generation, and checksum
  generation. The signed APK SHA-256 is
  `0f9ce71874d5c9eae3897ba74a0eb14feca12461b64c1dc662c856d1ff11dd36`.
- The Ansible publication dry run passed, then 0.2.2 was published to the private
  stable channel. The authenticated endpoint returned versionCode 12. No Taildrop
  delivery or physical-phone installation was performed.

## September 25, 2026 — project-task workspace validation fix

- Root cause: Remote Codex checked a selected project root with
  `command/exec(["test", "-d", "--", path])`. The host's `test` implementation
  rejects `--`, so an existing directory produced exit code 2 and the Android
  client reported that the server-advertised workspace was inaccessible.
- Replaced that shell probe with the existing stock `fs/getMetadata` RPC. A
  selected root is accepted only when metadata identifies a directory, after
  `project/read` has confirmed that the project still exists and continues to
  advertise the exact root. Git and directory mutations remain on the journaled
  `command/exec` path; trust configuration is not inspected.
- Workspace-validation failures now remove the pre-mutation journal while
  retaining the draft and attachments, allowing a safe retry. Failures after a
  directory, worktree, task, attachment, or message mutation retain the existing
  uncertain-operation behavior.
- Focused JVM workspace tests passed, covering directory, file, missing path,
  rejected metadata, changed project root, RPC order, absence of a `test`
  command, and absence of trust reads or mutations. `scripts/check` passed,
  including Rust format/clippy/tests, Kotlin tests, Android unit tests, debug and
  instrumentation APK assembly, and lint.
- One targeted managed-emulator run covered four scenarios. Current workspace,
  new worktree, and projectless creation passed. The new rejected-validation
  test exposed a fixture response-dispatch problem in the integrated full suite;
  after that fixture was corrected, its explicitly approved targeted rerun passed.
- No release, deployment, phone delivery, or physical-device acceptance was
  performed. Current- and new-worktree creation against Grace on the Razr remain
  required before declaring the user-visible bug fixed.

## September 25, 2026 — compact cover-screen adaptation

- Added window-based compact-height and nearly-square classification, then adapted
  the task browser, settings, empty and active conversations, composer, attachment
  controls, plan and question flows, and file/image overlays for the Razr Ultra
  2025 cover display's approximately 411 × 485 dp usable window.
- Added a documented `scripts/emulator-test --cover` mode. Its five focused tests
  passed on the managed Android 16 emulator at 1080 × 1272 pixels and 420 dpi,
  covering every destination plus attachments, file preview, plans, and decisions.
- The normal-phone full run completed 26 tests: 23 passed, two expected tests
  skipped, and the long-history test exposed a synchronous lazy-list remeasure race
  while Markdown was laying out. After replacing that forced scroll with a deferred
  list-anchor request, the affected normal-phone test passed independently.
- `scripts/check` passed against the final code, including Rust and Kotlin tests,
  Android unit tests, debug and instrumentation APK assembly, and lint.
- No release build, publication, live deployment, Taildrop delivery, or physical-
  phone installation was performed.

## September 24, 2026 — Android file support release

- Advanced Android to 0.2.1 / code 11 for generic file attachments and file
  result previews, opening, sharing, and saving.
- `scripts/check` passed, including Rust tests, Kotlin and Android unit tests,
  debug assembly, instrumentation APK assembly, and lint. `scripts/release`
  repeated those checks successfully, then passed signed release assembly and
  lint, package/signer verification, manifest generation, and checksum generation.
- The signed APK SHA-256 is
  `4d133af1b874e366b0ba64b38b69dfdf5b56242988447d57ac2422958e108339`.
- No deployment, update publication, Taildrop delivery, or physical-phone
  installation was performed.

## September 24, 2026 — Android file support

- Generalized image drafts into image-or-file attachments while retaining the stock
  `fs/createDirectory` and base64 `fs/writeFile` transfer path. Generic files are
  represented in the exact Android file-context prompt; only images add a
  `localImage` turn item.
- Added stock `fs/getMetadata` plus `fs/readFile` result handling, bounded local
  caching, UTF-8 text and image previews, generic open/share/save actions, and
  clickable file references from user messages, file changes, and Markdown links.
- Preserved the decompiled Android limits: 20 MiB per attachment and 50 MiB total.
  Existing image drafts and uncertain-write journals remain readable.
- `scripts/check` passed after merging the current `main`, including Rust tests,
  Kotlin tests, Android unit tests, debug and instrumentation APK assembly, and
  lint. No production Rust code changed.
- The Android instrumentation run finished 24 tests on the worktree-owned
  Android 16 emulator: every fixture-backed test passed and the credential-gated
  live test skipped as designed. The disposable emulator was stopped afterward.
- No release build, publication, Taildrop delivery, or physical-phone installation
  was performed.

## September 24, 2026 — composer layout release

- Advanced Android to 0.2.0 / code 10 for the composer layout fixes.
- Kept the active-turn composer footer compact and prevented long workspace paths
  from expanding the footer or displacing its controls.
- `scripts/check` passed, including Rust tests, Kotlin and Android unit tests,
  debug assembly, instrumentation APK assembly, and lint. `scripts/release`
  repeated those checks successfully, then passed signed release assembly and
  lint, package/signer verification, manifest generation, and checksum generation.
- The signed APK SHA-256 is
  `a235b0af6b424810727ec0286d951786ff118dafbeac3661b0ebd46603905a5a`.
- The Ansible publication dry run passed, then 0.2.0 was published to the private
  stable channel. The authenticated endpoint returned versionCode 10. No
  Taildrop delivery or physical-phone installation was performed.

## September 24, 2026 — standard Android test orchestration

- Replaced custom automated-emulator lifecycle orchestration with AndroidX Test
  Orchestrator on a Gradle-managed Android 16 device. The worktree-owned emulator
  remains only for interactive inspection, recording, and live acceptance.
- Added explicit targeted and full instrumentation modes with bounded execution;
  routine work selects the smallest relevant tests and cannot accidentally start
  the full suite.
- `scripts/check` passed. A three-test targeted run passed in 1 minute 31 seconds,
  and the full managed-device run passed in 4 minutes 35 seconds: 21 tests, zero
  failures, and one intentionally skipped credential-gated live test.
- Process isolation exposed and fixed a fixture-only Compose teardown issue and
  replaced implicit one-second UI waits with explicit bounded waits.
- Machine profiles are shelved and are not part of the release roadmap.

## September 24, 2026 — integrated image and plan release

- Merged image attachments and stock-advertised plan mode onto the current `main`
  line. Machine profiles remained separate and were subsequently shelved.
- Advanced Android to 0.1.8 / code 9 because the private stable channel already
  contains the immutable 0.1.7 / code 8 update.
- `scripts/check` passed after integration, including Rust tests, Kotlin tests,
  Android unit tests, debug assembly, and lint.
- All 20 fixture-backed Android instrumentation tests passed on the worktree-owned
  Android 16 emulator; the credential-gated live test skipped as designed. The
  disposable emulator was stopped afterward.
- The fixture suite now disposes the production activity model before installing
  its mock-backed model and clears its temporary credential between tests. This
  prevents background endpoint reconnects from starving instrumentation work.
- `scripts/release` passed its repeated checks, signed release assembly and lint,
  package/signer verification, manifest generation, and checksum generation. The
  signed APK SHA-256 is
  `84f663519c2e220d1b2d3bd06f5acfff263cae0ca70f69543bb38c8054768bef`.
- The Ansible publication dry run passed, then 0.1.8 was published to the private
  stable channel. The authenticated endpoint returned versionCode 9. No Taildrop
  delivery or physical-phone installation was performed.

## September 24, 2026 — decoupled Android update publication

- Advanced the Android release to 0.1.7 / code 8 for the first in-app update test.
- Publication now checks the running authenticated update capability instead of
  requiring the installed forwarder to match the release build byte-for-byte.
- Monotonic stable versions, immutable version contents, APK/manifest validation,
  and manifest-last publication remain enforced.
- Rust tests, Android unit/build/lint, Ansible syntax validation, `scripts/check`,
  the signed release build, and generated 0.1.7 manifest/checksums passed.
- Published 0.1.7 through the private stable channel after the explicit 0.1.6
  bootstrap deployment. The user confirmed that the physical Razr discovered,
  downloaded, and installed the update through the in-app flow.

## September 24, 2026 — settings-triggered private updater

- Added authenticated, project-owned update routes under
  `/remote-codex/v1/updates/`; the stock `/codex/rpc` bridge remains unchanged.
- Added manual Settings discovery, app-private download progress/cancellation,
  manifest/hash/package/version/signer verification, and user-confirmed Android
  `PackageInstaller` handling. There is no background or silent installation.
- Rust route/authentication/path tests, Android updater unit tests, `scripts/check`,
  release lint, Ansible syntax validation, and generated manifest/checksum
  verification passed. The signed bootstrap release is 0.1.6 / code 7 because
  0.1.5 / code 6 had already been delivered before this feature.
- On-device updater acceptance remains outstanding.
- No live deployment, update publication, Taildrop delivery, or physical-phone
  installation was performed.

## September 24, 2026 — image support reconciled with Rust transport

- Merged the current `main` line into the image-support branch, retaining the Rust
  byte tunnel, Java-WebSocket continuation framing, projects/worktrees, model
  controls, and the refactored Compose navigation.
- Added persisted multiple-image drafts, Photos and camera capture, image-only and
  mixed turn input, stock filesystem upload/read helpers, structured timeline media,
  sampled inline rendering, and tap-to-expand viewing. Originals remain limited to
  20 MiB each and 50 MiB combined.
- Attachment directory creation, individual file writes, and turn submission are
  journaled. An uncertain image write is retained for review and is not replayed
  automatically.
- `scripts/check` passed, including the Rust checks/tests, exact 20 MiB fragmented
  stock-server round trip, Kotlin tests, Android build, unit tests, lint, and
  instrumentation APK assembly.
- All 17 fixture-backed instrumentation tests passed on the worktree-owned Android
  16 emulator; the credential-gated live test skipped as designed. The new coverage
  exercises image-only upload and inline/full-screen rendering, uncertain image-write
  no-replay, and attachment restoration after ViewModel recreation. The disposable
  emulator was stopped afterward.
- Prepared Android version 0.1.6 / code 7 so it remains newer than the delivered
  Rust-transport 0.1.5 build. No deployment or phone delivery was performed.

## September 24, 2026 — Rust tunnel and fragmented Android transport

- Preserved the detached image and machine-profile work on separate named branches;
  neither was merged into this transport branch.
- Replaced the Go reverse proxy with a Rust 1.95 service that keeps the same binary,
  environment, endpoint, credential and systemd contracts. The Rust service parses
  only the two HTTP handshakes and copies upgraded bytes unchanged.
- Replaced Android's OkHttp WebSocket with Java-WebSocket 1.6.0. Outbound stock RPC
  uses 256 KiB continuation frames, requests no extensions and retains connection
  generations, typed failures, bounded events and mutation-safe reconnect behavior.
- Rust unit tests cover authentication, route/origin rejection, credential and Unix
  socket checks, header sanitization, exact upgraded bytes and the eight-client cap.
  The isolated stock gate writes and reads an exact 20 MiB file through the Rust
  tunnel, accepts the roughly 28 MiB response and reads a server-owned task from a
  second connection.
- Kotlin tests cover the previous HTTP 401 diagnostic, a base64-expanded 20 MiB
  request, UTF-8 across a fragment boundary, absence of compression negotiation and
  a single 28 MiB incoming text frame. No live deployment or phone delivery occurred.
- `scripts/check` passed. After reconciliation with main, all 14 fixture-backed
  Android tests also passed on the worktree-owned Android 16 emulator. The
  credential-gated live test skipped as designed.
- Repeated instrumentation exposed the obsolete asynchronous OkHttp cleanup path;
  Java-WebSocket disposal now closes synchronously during ViewModel cleanup. The
  uninstrumented 14-test fixture suite then passed and the emulator was stopped.
- `scripts/release` passed for the corrected version 0.1.5 / code 6 app and Rust
  forwarder. This distinct Android version prevents confusion with the earlier
  0.1.4 release already installed on the phone. The signed APK SHA-256 is
  `7df5f8d834bd5fbf8f55c22e8a6cc4e6fe31d69ca4630a1be34f35b9d2941b5d`;
  the Rust forwarder SHA-256 is
  `742ab98ba95018cb0fc2c334b9a2f4a500b8bef200632c22ed1069a7f22d0fc7`.
- The Ansible deployment dry run and live deployment passed. Grace is running the
  exact verified Rust binary on loopback with an enabled user service and zero
  restarts; the existing private Tailscale Serve route was preserved. The public
  route returned 401 without credentials, and a credential-protected, read-only
  WSS `initialize` reached the stock app server. The corrected 0.1.5 APK was then
  checksum-verified and delivered to the configured Razr through Taildrop; Android
  installation awaits user confirmation.

## September 24, 2026 — integrated 0.1.4 Android release

- Merged the Projects, workspace/worktree execution, and server-backed model
  controls feature branches into `main`, resolving their shared composer, client
  state machine, recovery journal, and fixture-server changes as one architecture.
- `scripts/check` passed after integration. The worktree-owned Android 16 emulator
  then ran all 14 fixture-backed tests without failures; the credential-gated live
  connection test was skipped as designed. The disposable emulator was stopped.
- `scripts/release` passed its repeated checks, release lint, package identity,
  non-debuggable, and signing-certificate verification. The signed APK is version
  0.1.4 / code 5. SHA-256:
  `f68029bf91861702f76c0f9d0ebbe2e6059d9187ccf8306fdd7ffb618fea6e98`.
- Taildrop delivery completed to the active
  `motorola-razr-ultra-2025-2` Tailscale node. Installation remains an explicit
  user step; no ADB installation or live deployment was performed.

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

## September 24, 2026 — server-backed Android model controls

- The Android composer now loads the stock `model/list` catalog and presents a
  server-default choice plus the models and reasoning efforts advertised by that
  response. It uses the current thread model from `thread/resume` to constrain
  effort choices for existing tasks.
- Null model and effort choices remain absent from RPC payloads. Explicit models
  are sent on `thread/start` and `turn/start`; explicit effort is sent as
  `turn/start.effort`. Active-turn `turn/steer` remains unchanged because the
  checked-in stock schema exposes neither override there.
- Catalog loading, manual refresh, reconnect refresh, and removal of stale model
  or effort choices are covered by the fixture server. No fallback model names,
  effort lists, or compatibility catalog are stored locally.
- `scripts/check` passed, including Go race/vet, Kotlin tests, Android debug
  build, lint, and instrumentation assembly. The disposable Android 16 emulator
  ran seven fixture-backed UI/integration tests with zero failures; the live
  credential-gated smoke test was skipped as expected. Coverage includes catalog
  rendering, model-specific effort filtering, exact request overrides, omission
  of defaults, refresh invalidation, reconnect reload, and uncertain-send
  no-replay behavior.
- No live deployment, signed release, physical-phone installation, or live model
  mutation was performed. Runtime verification used the stock-schema-shaped
  fixture transport only.

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
