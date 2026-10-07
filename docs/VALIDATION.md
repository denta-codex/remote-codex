# Validation

## 2026-10-07 — Android 0.4.11 (43)

- Source revision: `f69892373e483f80f29bf99ff2cd463cbf950080`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `1e2353a1def350befad3a5f3f6d292a949c8c776276c031e39739a63d18b46e3`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/.codex/worktrees/0508/remote-codex/artifacts/releases/run-20261007T145847Z-DIEIkh.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-06 — Android 0.4.9 (41)

- Source revision: `87c10d4c5aba711abb652ea0fdd54f8d8b852fc6`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `6028bcc3c7dbc961c8845bcb0d869e6086f6f8a42f9b53ae1b0992abe259f25d`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261006T232928Z-ej3A7B.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-06 — Conversation opening memory and latency

- Report `3386afd3-ab29-4f92-b964-4375eb12d2db` records 9,924 ms from
  opening the task to clearing busy, a failed 179,509,778-byte allocation,
  and 6,298 ms blocked on garbage collection.
- Reuse resume's newest full turn and cursor instead of requesting another
  20 full turns before rendering. Older history and metadata-only resume
  recovery request one turn per page. The inbound RPC UTF-8 size check now
  counts bytes without allocating an encoded copy of the entire message.
- `RpcSizeTest` passed, including Unicode and malformed-surrogate boundaries.
- Managed Android 16 tests passed:
  `AppTest#conversationOpensFromResumePageWithoutWaitingForOlderHistory`
  and `AppTest#textChatStreamsAndCanReopen`. The regression confirms rendering
  succeeds while older-history responses are withheld and preserves the cursor.
- No live-device latency measurement, publication, deployment, or phone
  installation was performed. A single exceptionally large turn can still
  require substantial memory; pagination bounds turns rather than bytes.

## 2026-10-06 — Android 0.4.8 (40)

- Source revision: `bb6e267fcc8aa3387f93b5805a8e170acc910a05`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `7962101c4523b282215897cbc32a1bb19ab9742e813f0490d3a26aea628a003b`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261006T213808Z-thDD97.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-06 — Consolidate shared-checkout Android changes

- Integrated the swipe action tray, one-hour/custom snoozing, conversation
  renaming, weekly usage remaining, and complete shake-reporting removal on
  `main`. The duplicate dirty checkout has the same code; the older reporting
  and visualization scratch work is already incorporated in current history.
- Validated an isolated copy of the staged source: all 148 app unit tests,
  Android lint, debug app assembly, and instrumentation APK assembly passed.
- Five focused managed Android 16 scenarios passed together:
  `AppTest#settingsShowsWeeklyRemainingRefreshesAndHandlesSparseUpdates`,
  `AppTest#snoozeTapDefaultsToHourAndChangeTimeCanReturnEarly`,
  `AppTest#conversationMenuRenamesTaskAndUpdatesList`,
  `AppTest#taskSwipeMenuRevealsWithoutMutatingAndClosesSafely`, and
  `AppTest#systemScreenshotOffersReportWithTheCapturedWindow`.
- XML results are retained under `artifacts/integration-validation/`. The
  temporary validation checkout was removed after verification. This local
  integration does not publish a release or install on the phone.

## 2026-10-06 — Remove shake-to-report

- Removed the Settings toggle, report-state field, saved-preference handling,
  lifecycle sensor registration, invocation gate, vendored Seismic detector,
  detector tests, and bundled license assets. Screenshot and menu reporting
  remain available; earlier shake-related entries below describe retired code.
- All 10 `BugReportTest` unit tests passed, and the Android test APK compiled.
- Selected managed Android 16 instrumentation passed:
  `AppTest#bugReportSettingsExcludesScreenshotAndPersistsScreenshotPreference`
  and `AppTest#systemScreenshotOffersReportWithTheCapturedWindow`.
- Validation used an isolated source snapshot to avoid concurrent checkout
  builds. XML results are retained in `artifacts/reporting-validation/`;
  the temporary source copy was removed after successful verification.

## 2026-10-06 — Todo cutover and Android 0.4.7 publication

- Built Android 0.4.7 (39) from `main` at `a14e6a4`, after fetching and confirming
  that all current `origin/main` commits were included. Build record: `5eeb4c5`.
- Live SQLite backup migration verified integrity and logical equality before
  deploying the repo-owned Todo CLI and forwarder. Stock and Todo authenticated
  WSS checks and shared CLI database access passed.
- Published the same signed APK to the private stable channel. Authenticated HTTPS
  manifest and full APK verification passed. Publication log:
  `artifacts/releases/run-20261006T193505Z-TR3zDv.log`.
- User confirmed Android installation and a successful Todo write. Read-only
  authenticated stock/Todo WSS checks, default CLI access, SQLite integrity, and
  foreign-key checks passed after acceptance.
- Removed the old database, its empty data directory, and the temporary CLI
  recovery binary. Cutover is complete; the canonical database is authoritative.
  Any future reverse cutover must transfer that current database, preserving
  writes accepted since migration.

## 2026-10-06 — Android 0.4.7 (39)

- Source revision: `a14e6a42f3591510ab25f0432367ccc661c02c89`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `4bd4d8eef524ab5853e83fdd2b484788491b427f21425d517388c0c2eb8eb6bc`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261006T192957Z-9auyi1.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-06 — Rust-owned Todo service

- Added the authenticated Todo WSS route, repo-owned shared Rust storage/CLI,
  independent Android Todo connection, and explicit database cutover tooling.
- `scripts/check` passed, including the real stock control-socket fragmented
  20 MiB round trip, Rust format/clippy/tests, Kotlin unit tests, Android debug
  builds, instrumentation APK compilation, and lint.
- Storage/CLI fixtures cover concurrency, rollback, revision conflicts, archived
  records, ordered notes, migration refusal, retained source, and reverse cutover
  preserving writes made after migration. Transfer uses SQLite backup and verifies
  schema, integrity, foreign keys, records, and allocation sequences.
- Focused managed Android 16 tests passed: `AppTest#nativeTodoOpensWithoutPreviewAndReturnsToChats`,
  `TodoUiTest#addsEditsAndMovesATaskThroughNativeControls`,
  `TodoUiTest#offlineAndUncertainSaveDoNotOfferSilentRetry`,
  `TodoUiTest#swipesMoveBothDirectionsAndLongPressReorders`, and
  `TodoUiTest#coverKeepsTabsEditingAndDiscardReachableWithLargeText`.
  The first navigation run failed only in fixture shutdown; socket teardown and
  the mock close handshake were corrected, and that affected test passed on rerun.
- The isolated `scripts/candidate-check.py --todo-cli` fixture passed using the
  new systemd sandbox: authenticated stock/Todo connections, selected-status
  creation, SQLite writes, CLI notes visible through WSS, stock socket replacement,
  and cleanup. Synthetic credentials and disposable databases only.
- All 16 release-workflow fixtures passed; updated Ansible syntax and shell
  syntax checks passed. Todo is included in the verified build artifact set.
- No live Todo data migration, forwarder deployment, publication, or phone
  installation was performed. Cutover and recovery are documented in `docs/TODO.md`.

## 2026-10-05 — Android 0.4.6 (38)

- Source revision: `e495815de604644bbee96a0277e2eafdf8e895c0`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `48d73662c181886a5833a79ac9b0b51ba14ff162440237af4061cbf9e7871490`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261005T185022Z-wE31JG.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-05 — Android 0.4.4 (36)

- Source revision: `b862a768bda39639e5b682d757b0acd6c0080671`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `8fc2c87267a8956b7886c2dee35031877231ca2dfd5da3a9155d3083c677d1b2`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261005T155147Z-WdUjYD.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-05 — Approval context and reconnect recovery

- Version-matched stock 0.159.2 source confirms that file-change items precede
  approval, unfinished item events are not persisted, and running-thread resume
  overlays the live turn before reissuing outstanding requests. Android requests
  one full initial turn, retains matching details independently of timeline
  pages, and keeps the existing connection-scoped dispatcher and response guard.
- Four `FileApprovalContextsTest` cases and four `ServerRequestDispatchTest`
  cases passed. App unit tests and `:app:lintDebug` passed with the existing
  managed JDK/Android SDK. `git diff --check` passed.
- Six focused fixture-backed managed-device tests passed: new-chat live details
  without resume; snapshot recovery before history finishes; missing details,
  retry, and patch updates; explicit unsupported-field fallback; reconnect with
  server reissue and no response replay; and resolution during recovery with
  late-snapshot rejection. The new-chat fixture's initial wait was corrected to
  await the chat page before typing, then the affected fixture passed on rerun.
- Integration with local main `8f5b202` passed the focused context/dispatcher
  tests, app unit tests, instrumentation compilation, and the snapshot-before-
  history and reconnect-ownership emulator fixtures. The only merge conflict was
  documentation; both approval recovery and MCP behavior were preserved.
- The subsequent approval-choice update on main `1c6b29c` was also integrated.
  Context, dispatcher, approval-choice, and app unit tests passed, as did three
  focused emulator fixtures for recovery before history, reconnect ownership,
  and missing-details retry. The retry fixture also verifies that once/session
  grants cannot bypass missing context and that recovered session grants still
  require confirmation.
- These are stock-contract fixtures, not a live-account or physical-phone
  acceptance run. No release, publication, deployment, or phone installation
  was performed.

## 2026-10-05 — Android 0.4.3 (35)

- Source revision: `7d5d301598d487c6e1bff5c18d2b812735adcceb`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `f6b3b3f75a9e79bc1010c43b53e664033b08dddaf86b1ba8f6f8f0068e2dab89`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261005T140856Z-xmOxLl.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-05 — Android 0.4.2 (34)

- Source revision: `16fb5189ad3e49e3af8926f7033d51adbfffabc8`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `a15638526b2a38fa9d9c19aa00bfdc741b6c42b7340dfcd2f009631e0577a734`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261005T024924Z-bz5dlw.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-04 — Android 0.4.1 (33)

- Source revision: `e806ce0280b1058fb9bd44caffa1a0ea3db40492`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `8fb48cf547412d85e6657517f9ac9b2c439e475befd771b4b764c468f9830c88`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/.codex/worktrees/remote-codex-native-todo/artifacts/releases/run-20261004T175305Z-QB12gk.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-04 — Backlog replacement step 1 audit

- The requested base `f815d49` already has no Todo integration. The integration
  commit `dbdf40b` is not its ancestor; their merge base is `769f46a`.
  Kept the requested base and all unrelated source changes intact.
- Inspected the original integration and viewport fix against the current tree:
  the Todo entry point, board WebView, controller, state/actions, preview commands,
  operation journal handling, fixtures, dedicated tests, and feature documentation
  are absent. No production removal was necessary at this revision.
- Added `AppTest#homeNavigationHasNoTodoDestination` to assert Todo is absent
  while Home, Settings, New chat, and return navigation remain functional.
- Passed `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, and
  `:app:assembleDebugAndroidTest` using the existing managed JDK and Android SDK.
- Focused Android 16 emulator tests passed:
  `compactArchivesRestoreAndNavigation`,
  `visualizationLoadsFromHistoryExpandsAndShowsMissingFileRecovery`, and
  `remoteTextFileUsesMetadataAndOpensAReadablePreview`.
  A second focused run passed `homeNavigationHasNoTodoDestination` and
  `coverScreenDestinationsRemainReachable`, both in cover-screen mode:
  `env 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.coverScreen=true' scripts/emulator-test --tests AppTest#homeNavigationHasNoTodoDestination AppTest#coverScreenDestinationsRemainReachable`.
- Remaining validation limitation: the existing
  `coverChatToolbarAvoidsCutoutWithHiddenStatusBar` failed on the unchanged base
  instrumentation APK at its first chat-toolbar assertion. The title bounds began
  at y=90 px, above the simulated 96 px cutout boundary. The broader destination
  layout test passed; the separate cutout issue remains outside this change.
- Left the host Todo workspace/data, installed Backlog CLI, shared preview
  tooling, and live previews untouched. No replacement board, CLI, database,
  import, release, deployment, phone installation, push, or shared-main merge.

## 2026-10-04 — Android 0.4.0 (32)

- Source revision: `030e67043c4a617f508b331dd686e6ca1e16ecfd`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `58ea4b65ed4df584e09bc0e64e6c840bf7cc8d930e02a35b5d675c3f27f41af7`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261004T170035Z-VT3VBY.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-03 — Add existing host projects from Android

- Added a folder-only browser and pasted-path entry to the new-task project
  picker. Canonical roots reuse existing host registrations; ambiguous matches
  require a choice. New registrations use stock project/create with a durable
  host/account-scoped operation record. Desktop records and workspace files are
  not changed.
- All 106 app unit tests passed, including 17 new project-addition tests covering
  pagination, canonical aliases, unavailable roots, duplicate taps, explicit
  rejection, lost replies, persistence failures, restart recovery, and stale
  navigation/connection results. Draft text and attachments are preserved.
- Android debug lint and instrumentation APK assembly passed.
- All five selected Android 16 emulator tests passed in bounded batches:
  AppTest#addProjectBrowsesAndPersistsWithoutStartingTask,
  AppTest#addProjectPasteReusesHostProjectAndReportsInvalidPath,
  AppTest#addProjectLostReplyRecoversAfterRestartWithoutReplay,
  AppTest#addProjectCoverPickerKeepsActionsReachable, and
  AppTest#selectedProjectSurvivesDraftRecreationAndStartsInItsRoot.
- An earlier emulator run was interrupted externally. A subsequent five-test
  batch hit the existing three-minute limit after a Kotlin incremental compiler
  fallback consumed startup time; the final runs used smaller batches and
  completed successfully without changing test timeouts.
- No live projects were registered, no release was published, and no phone
  installation or forwarder deployment was performed.

## 2026-10-03 — Android 0.3.2 (29)

- Source revision: `b07340d7dd07b2faa7ac1efbd04a483f7937b5b0`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `1841711d76705b28b76098ac0932b2ec28ab2324ac9d15c4e2722fe5aa57bae1`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261003T152510Z-7LKgwp.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-03 — Retired Autofill playground cleanup

- Based on local main `c3ba403` (Android 0.3.1, build 28). Removed the experimental
  Autofill activity, Settings entry, manifest registration, dedicated tests, and
  experiment guide. Removed the obsolete playground availability statement.
- Production Credential requests, approval transport, keyboard suppression,
  protected-window screenshot checks, and prerelease version support remain intact.
- Core/app unit tests, debug APK assembly, debug lint, and instrumentation APK
  assembly passed using the existing managed JDK 17 and Android SDK.
- All three selected Android 16 emulator tests passed with no skips or failures:
  `CredentialRequestsTest#releaseUsesSeparateRouteClearsValueAndNeverReplays`,
  `CredentialRequestsTest#pickerHandoffRecreationAndProtectedWindow`, and
  `AppTest#bugReportSettingsExcludesScreenshotAndPersistsShakePreference`.
- The merged debug manifest omits the experimental activity and retains the
  non-exported production approval activity. Source/current-documentation searches
  found no remaining playground references; historical validation and release
  version fixtures are retained. `git diff --check` passed.
- No live credentials were requested. No publication, deployment, or physical
  phone installation was performed; new manual 1Password acceptance was not run.

## 2026-10-03 — Android 0.3.1 (28)

- Source revision: `f03ab1a47b4294658a09af732af2ea4e7a4005fc`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `8fd30df067902da46d6f12bf247a88eef9cac1d0a0aab0d19f43fd9e2e59e17a`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261003T145349Z-Z3DMhv.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-03 — Phone approval manual acceptance

- On preview `0.3.1-phone.1 (27)`, the disposable Login fixture passed normal
  and repeat reads, `read --no-newline`, and single-field `item get`. Caller-side
  comparisons verified the expected fake value without displaying it.
- Denial returned `denied` with no value. An unattended request ended with
  `phone_session_ended_or_timed_out` and no value.
- The user confirmed Back to requests cleared the field; selecting and filling
  the request again delivered the expected value.
- Vault/item listing, complete-item JSON, generated OTP, and item-create dry run
  were rejected as `unsupported_operation` on the phone destination.
- Manual post-approval screen reopen/reconnect testing was skipped at the user's
  request. Automated no-replay coverage remains separate from this manual run.
- Follow-up source change disables the credential field's automatic soft keyboard
  while retaining focus, the password Autofill hint, and explicit Autofill requests.
- Both focused `CredentialRequestsTest` emulator cases passed after integration
  with main, covering focused Autofill delivery with soft-input-on-focus disabled,
  picker handoff, recreation, capture protection, clearing, and lost-ack no replay.

## 2026-10-03 — Android 0.3.1-phone.1 (27)

- Source revision: `6113e5c6e9c1d67138c3f44aa69480e0c3a27234`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `78ffe28935b8fa8c3ee74bae91a1b9d27d8ea505411d26c978b03b7aca1be574`.
- Outcome: Subsequently published to the private stable channel; authenticated
  manifest and full APK verified by the publish workflow.
- Build log: `/home/agent/.codex/worktrees/740e/remote-codex/artifacts/releases/run-20261003T141847Z-6Nzqep.log.build.log`.

## 2026-10-03 — Android 0.3.0 (26)

- Source revision: `770c8eeac43bd9c8a615ff1cee36e54b353933b2`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `b865e531198a8cb0cc60b45c43ee7c0fa29a31163dad36b62a465941f16a63bb`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/tmp/remote-codex-release-0.3.0/artifacts/releases/run-20261003T140323Z-eiOl6i.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-03 — Intent-aware reports (unreleased)

- Based on local main `6417fcb`. The report form now requires Investigate,
  Research, Plan, or Implement, with no default. Description and evidence stay
  together; a separate review shows the title, destination, attachments, and
  exact first message before any host mutation. The first three intents use
  advertised Plan mode; Implement uses Default, with no capability fallback.
- All 20 focused `BugReportTest` and `BugReportSubmissionTest` unit tests passed.
  Coverage includes intent/title/mode agreement, verbatim user restrictions,
  exact reviewed input, preparation without mutations, stale-review rejection,
  migration of unsent v1 drafts, legacy in-flight submissions, and reconstruction
  after lost acknowledgements at every mutation stage without replay.
- Android instrumentation compilation and `:app:lintDebug` passed.
- A separate selected run passed all three previously unattempted cases:
  `AppTest#systemScreenshotOffersReportWithTheCapturedWindow`,
  `AppTest#bugReportScreenshotSurvivesOfflineRecreationAndCanBeRemoved`, and
  `AppTest#bugReportSettingsExcludesScreenshotAndPersistsShakePreference`.
  This verifies the renamed screenshot action on the compact display, offline
  intent/text/evidence retention, evidence removal, and credential-screen capture
  exclusion. Seven emulator cases have confirmed passing results in total,
  including the separately approved cover-display check below.
- The initial four-case managed-device run hit the wrapper's three-minute
  deadline. Its instrumentation results explicitly confirmed these three passes:
  `AppTest#reportUnavailableModeKeepsDraftWithoutCreatingTask`,
  `AppTest#bugReportCapturesScreenAndStartsIsolatedFixTask`, and
  `AppTest#researchReportReviewsEditsAndFreezesTheRequestAcrossRecreation`.
  The research case covers edit/review, removing evidence, changing intent,
  custom titles, recreation, reconnect without submission, and double submission
  calls producing only one task and turn.
- After explicit user approval, the isolated
  `AppTest#bugReportCaptureFailureStillAllowsSubmissionOnCoverDisplay` run passed
  (one test, zero failures; successful completion in 2m 51s). This confirms that
  intent selection, review, and Plan-mode submission remain reachable on the
  compact display even when screenshot capture fails. The earlier batch's result
  was inconclusive because its final acknowledgement was cut off by the timeout;
  no automatic retry was performed. Temporary diagnostic copies from that
  interrupted run were removed after the isolated check passed.
- All instrumentation uses fixture data. No release, publication, forwarder
  deployment, or physical-phone installation was performed.

## Task-row gestures — unreleased

- Integrated with main `9a622a6`, retaining the compact inbox, server recency
  ordering, independent archive/inbox filters, and automatic reply indicators.
  Manual unread extends the existing host-scoped reply marker; it does not add
  a second unread store. Opening clears the manual reminder while unseen new
  replies remain unread until viewed. Archive snapshots refresh without losing
  each tab's search/sort choices.
- Integration validation: all six gesture tests below passed in two selected
  runs, plus `AppTest#compactArchivesRestoreAndNavigation` and
  `AppTest#readingEarlierParagraphKeepsNewReplyUnread`. All six
  `ChatActivityTest` unit tests passed, including manual/automatic unread
  interaction, persistence, and host isolation. Android lint passed.
- Reproduced the missing right-swipe behavior with `AppTest#taskSwipeMarksUnread`:
  the baseline failed because no unread indicator appeared after swiping.
- Implemented left-swipe Archive/Unarchive, right-swipe Mark unread, long-press
  deep-link copying, threshold haptics, cancellation, TalkBack actions, and
  acknowledgement-only Undo. Unread reminders persist locally on the phone;
  they do not synchronize with desktop unread state.
- Passed six selected Android 16 managed-device tests using
  `scripts/emulator-test --tests`:
  `AppTest#taskSwipeMarksUnread`,
  `AppTest#taskSwipesArchiveUndoAndUnarchive`,
  `AppTest#taskLongPressCopiesLinkAndAccessibleActionsWork`,
  `AppTest#uncertainArchiveDoesNotReplayOnReconnect`,
  `AppTest#rejectedArchiveKeepsTaskAndOffersNoUndo`, and
  `AppTest#taskGesturesOnCompactScreenRespectCancellationAndPhysicalDirection`.
  Coverage includes app recreation, clearing unread after opening, repeat
  swipes, short/cancelled gestures, a compact RTL layout, explicit rejection,
  and a committed archive whose response is lost before reconnect.
- `:app:lintDebug` and `git diff --check` passed. Tests use the local WebSocket
  fixture; no live account mutations or physical-phone verification occurred.
- No release, publication, forwarder deployment, or phone installation.

## 2026-10-03 — Android 0.2.13 (25)

- Source revision: `a85c2a6bed72c9116353a5bc520ff7c9ff2e3d78`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `b09d7c10efa2a5b4a3e6189318d1f9a693823f2cee71ee105337b95d82823790`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261003T030034Z-B0WUR6.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## Report 624bbb7a — inactive chat promoted in recents

- The frozen report identifies Android 0.2.11 (23), while the supplied checkout
  `0ce33a5` predates its compact inbox and relative-age labels. The common list
  request explicitly sorted by `updated_at`. The frozen snapshot does not include
  task-list timestamps, so it cannot establish the exact update that triggered
  the captured ordering.
- A read-only comparison of the current stock state database reproduced the
  inversion for the reported lampshades task and source chat: descending
  `updated_at_ms` puts lampshades first; descending `recency_at_ms` puts the source
  chat first. This is current diagnostic evidence, not a reconstruction of the
  capture. Both list and search support `recency_at` in the checked-in protocol.
- Changed the browser's list/search requests to explicit descending `recency_at`,
  preserving server ordering and opaque cursors across pages. No client-side
  timestamp cache or reordering was introduced.
- `scripts/emulator-test --tests
  AppTest#recentChatsIgnoreMetadataUpdatesAcrossPagesAndSearch` passed: one test,
  Android 16 managed device. The fixture gives an old chat a newer metadata
  timestamp and checks list/search order, second pages, sort parameters and
  cursor propagation. Debug and instrumentation compilation passed as part of
  that run. Initial sandbox startup was blocked by the read-only Gradle cache
  before tests ran; the authorized host-level invocation completed successfully.
- No release, publication, forwarder deployment, or physical-phone installation
  was performed. The newer inbox's relative-age rendering is absent from this
  revision and was not changed or validated.

### Integration with current main

- Integrated the fix with the compact inbox's `ChatSort.Recent` contract and
  changed its displayed age from `updatedAt` to `recencyAt`. Creation sorts keep
  using `createdAt`. Missing recency remains undated rather than showing a
  misleading metadata age.
- Two focused Android 16 emulator cases passed after conflict resolution:
  `AppTest#recentChatsIgnoreMetadataUpdatesAcrossPagesAndSearch` and
  `HomeScreenTest#recentAgeUsesRecencyInsteadOfMetadataUpdate`. The former now
  isolates explicit page boundaries from the newer automatic scroll loading;
  the latter verifies recent and creation ages independently.
- The user subsequently authorized merging to main and publishing an in-app
  update. The release workflow records that outcome separately below.

## 2026-10-03 — Android 0.2.12 (24)

- Source revision: `04b28aa52ca268705f73108c0e76f05475e8898f`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `b089b61e4218f10ada39449ac0b5cfc986f4ec135c00e4c50973a0e5b66143ba`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/.codex/worktrees/remote-codex-b9f642a3-8051-435a-b989-e97600b853f4/workspace/artifacts/releases/run-20261003T023734Z-BcYA7q.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-03 — Android 0.2.11 (23)

- Source revision: `b803471c1a23f3d9165815b9c484977ef9460295`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `163fba96dfb9d93743763d39cd47850b695c8b3371df8ae72507840f90a2f6ab`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/.codex/worktrees/remote-codex-b9f642a3-8051-435a-b989-e97600b853f4/workspace/artifacts/releases/run-20261003T021438Z-7Gy98I.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-02 — Plan mode composer report a9ba2621

- The frozen report shows a connected task with an active turn. Reproduced the
  disabled mode selector with `AppTest#planModeCanBeSelectedWhileWorkingAndSentWhenIdle`:
  the original composer failed `assertIsEnabled` on `mode-selector`.
- The composer now exposes mode selection while working, groups attachments in
  Add, and hides unusable model controls while queueing. Explicit mode drafts wait
  for an idle task and empty queue before the user sends; the client also rejects
  attempts to queue them, since queue requests cannot carry collaboration settings.
- Four focused Android 16 fixture tests passed through `scripts/emulator-test
  --tests`: selecting Plan during a turn and sending it once idle; waiting behind
  a paused queue and returning to task settings; normal queue/steer behavior; and
  the existing Plan-to-implementation flow. The first also checks the Add menu.
  Results: `artifacts/validation/plan-composer/phone-tests.xml`.
- Two focused cover-viewport cases initially timed out trying to send with the
  keyboard open. Hiding model controls during compact typing fixed the layout;
  both Plan selection/submission and normal queue/steer then passed with zero
  failures or skips. These used the same `--tests` entry point with
  `ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.coverScreen=true`.
  Results: `artifacts/validation/plan-composer/cover-initial-tests.xml` and
  `artifacts/validation/plan-composer/cover-tests.xml`.
- Initial validation startup required access to the managed Gradle cache outside
  the sandbox. A test-only Espresso reference then failed compilation and was
  replaced with the existing instrumentation API before the passing test run.
- Validation uses synthetic fixture content, not the live source task or physical
  Razr. No release, publication, deployment, or phone installation was performed.
- Integration with main preserved the scrollable action row, pinned Stop/Send,
  and landscape Options dialog. Four phone cases, cover Plan selection, and the
  landscape layout case passed. The cover queue case initially raced the IME
  transition; explicitly enabling the fixture's soft keyboard and waiting for
  the Queued shortcut fixed the test, which then passed. Integration results:
  `artifacts/validation/plan-composer/merge-phone-tests.xml`,
  `merge-compact-initial-tests.xml`, and `merge-cover-queue-tests.xml` in the same
  directory.

## Phone approval preview — Android 0.2.11-phone.1 (22)

- Published the previously built artifact; authenticated manifest and full APK verification passed.
- Publication log: `artifacts/releases/run-20261003T021149Z-w8y2HN.log`.
- Forwarder deployed through the existing workflow; stock Codex acceptance checks passed.
- op-bridge phone.1 installed on Grace from integration commit `e8a80a2`; default route remains mac.
- Fake-value end-to-end transport and natural idle expiry verified. Manual phone approval remains to be tested.
- See `docs/PHONE-APPROVAL.md` for integration validation and rollout details.


## 2026-10-03 — Android 0.2.11-phone.1 (22)

- Source revision: `56c15d2535ad52e15468be28c865ee54a88e2abf`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `0f062304c2e9c024ca6bf33d7d557f601464faa4df8f48fe3de2719626476e48`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/.codex/worktrees/740e/remote-codex/artifacts/releases/run-20261003T020628Z-B9GgVQ.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-02 — Android 0.2.11-autofill.1 (21)

- Source revision: `b1163c21ba557527ec6c46e1c9d88660126a8462`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `29968797286ceb65dd20a11599b5c25d3b9a95adf0ad9fa34f2560af3d668527`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/.codex/worktrees/740e/remote-codex/artifacts/releases/run-20261002T191037Z-ktGkOk.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-01 — Android 0.2.10 (20)

- Source revision: `b9caccb017935c43023c1077e8da0f25ff393c69`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `1f00b1b7a9b1ee9151da4a3833b6b75fce8993c7a2d7666e73fdaa9f1ea07041`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261001T232232Z-KLnBfB.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-01 — Android 0.2.9 (19)

- Source revision: `c23d2eb6f75c6a0fcc0a1a83f448c0ecd1ab9f71`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `12c1806c860646aceb95d93b255b3e641f22c18f7c9ed4e6643c429b18db5513`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261001T231240Z-XWDCjW.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-01 — Android 0.2.8 (18)

- Source revision: `332c158378ffc231fe6174aa981a715c90289ab6`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `6efecd86111ea6432a678955a4feb5c5b548c726a6d488ec53ae85f1023e1aac`.
- Outcome: Published to the private stable channel; authenticated HTTPS manifest and full APK verified.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261001T195016Z-0fpcC1.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-01 — Android 0.2.7 (17)

- Source revision: `946ad4ddde110953687d061cb9c18bb9c8b80d8a`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `19eb13912e5098877d696a5ddb7cd4db6b7b7fcec2d0a757e94c37a36adfe3fe`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/workspaces/remote-codex/artifacts/releases/run-20261001T194602Z-EN6SJI.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-10-01 — Screenshot-to-report prompt

- On Android 14+, the app registers an `Activity.ScreenCaptureCallback` while it
  is visible (`DETECT_SCREEN_CAPTURE`, a normal permission). Each screenshot
  copies only the app window right away and shows a Material 3 snackbar,
  "Screenshot taken · Report bug". Tapping it opens the existing report flow
  with that copy. A newer screenshot replaces a pending prompt. The app never
  reads the saved system screenshot.
- Shake-to-report is now opt-in, and the existing `bug-report/shake` key is
  honored only when it is `true`. The new screenshot preference is stored as
  `bug-report/screenshot`.
- App unit tests (including the new screenshot-prompt gate), debug lint (no new
  warnings), and androidTest assembly passed. Three focused managed Android 16
  tests passed: a real `KEYCODE_SYSRQ` system screenshot that offers the
  snackbar and captures the window, Settings exclusion with the shake default
  off, and menu capture with fix-task submission.

## 2026-10-01 — Shake-to-report with Android diagnostics

- Android debug and instrumentation assembly, all 37 app unit tests, and debug
  lint passed (zero lint errors). Fifteen unit tests cover report snapshots,
  credential redaction, bounded logs/action history, persistent artifacts,
  Seismic sampling and invocation gates, blocked native collectors, destination
  validation, and journal recovery.
- Lost acknowledgements were simulated after directory creation, worktree
  creation, environment setup, evidence-directory creation, upload, task
  creation, naming, and initial submission. Recovery confirmed each authoritative
  result without repeating the mutation; missing/conflicting evidence stayed
  pending.
- Four focused managed Android 16 report tests passed on the final implementation:
  actual screenshot capture and isolated fix-task submission, offline report and
  screenshot persistence across model recreation, Settings screenshot exclusion,
  and successful cover-layout submission despite capture failure.
- A separate focused cover-viewport run passed real screenshot/task submission
  and navigation reachability. The earlier menu-capture failure exposed a Compose
  frame-clock assumption in a model coroutine; the corrected Android frame
  callback passed subsequent runs.
- Fixture results are retained under `artifacts/validation/bug-report/`:
  `final-report-tests.xml`, `cover-tests.xml`, and the earlier diagnostic runs.
- No live fix task was created, and no forwarder deployment, update publication,
  or phone installation was performed. Physical Razr gesture sensitivity,
  interaction with its other gestures, real-device diagnostics, and live Grace
  task acceptance remain unchecked in `docs/DEVICE-ACCEPTANCE.md`.

## 2026-09-30 — Android 0.2.6 (16)

- Source revision: `e03e256c89b77d64b6a6b73738ff0a19b86abfe4`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `515ef926c453dcc9e3f23848651b40df26051bebc21894d1c868722d39858b47`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/.codex/worktrees/41f3/remote-codex/artifacts/releases/run-20260930T221133Z-LYDUWn.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

## 2026-09-29 — Android 0.2.4 (14)

- Source revision: `30e8947d1959b0e8e799d3a7a0b2b5a302360784`.
- Required checks, signed build, lint, package and signing verification passed.
- APK SHA-256: `ac08d1b2c78adb68f54d23f8cb14ced9ed1a2eaaf6f24ed0f918712a926b0e9a`.
- Outcome: Built and signed locally; not published.
- Build log: `/home/agent/.codex/worktrees/3896/remote-codex/artifacts/releases/run-20260929T152952Z-4CuzOP.log.build.log`.
- Installation remains user initiated. No forwarder deployment or phone installation was performed.

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

## October 3, 2026 — direct merge into main (report 889fad11)

- Implemented on the restored local-main baseline `6417fcb`. The conversation
  overflow menu now previews and directly merges committed work through stock
  `command/exec`. The earlier agent-prompt shortcut was discarded. No Rust,
  forwarder, or Codex server changes were made.
- Requires an idle synchronized task, clean checkouts, an existing local `main`
  checkout, related histories, and no submodules or unfinished Git operations.
  Preflight uses `merge-tree` without touching either checkout. Source and target
  commit IDs and checkout identity are revalidated after confirmation and after
  preparation. All source commits absent from main are included; the UI shows
  up to 100 commit subjects and 200 changed paths, with total counts.
- Divergent merges are prepared in a disposable detached worktree, then main
  only receives a fast-forward. Normal merge hooks/signing configuration apply;
  squash/autostash preferences cannot override the explicit operation. Ignored
  local files are protected from overwrite. No push or release commands run.
- Operations use a repository lock, a separate phone journal, and minimal host
  receipts under `<common-git-dir>/remote-codex-merges/`. Lost replies or restarts
  expose a state check, never an automatic mutation retry. Missing or unusable
  recovery evidence remains uncertain. Completed temporary worktrees are removed;
  unresolved recovery retains its owned worktree and reports its location.
  The lock serializes this app's operations; independent Git clients still
  require pre/post state checks, and detected external changes stop the flow or
  produce an explicit needs-review result without automatic rollback.
- Passed 14 focused `GitMergeTest` cases with the managed JDK using
  `:app:testDebugUnitTest --tests dev.codexops.client.GitMergeTest`. Tests run the
  exact bundled shell logic against disposable repositories: fast-forward and
  divergent merges, detached and named source branches, conflicts with byte-for-byte
  index preservation, dirty checkouts, stale snapshots, absent main/checkout,
  unfinished Git state and locks, unrelated histories, submodules, failing hooks,
  concurrent commits, ignored files, and recovery before/after advancement.
  Controller tests cover eligibility, persisted uncertainty, restart, and an
  unavailable recovery response that must not clear the saved mutation.
- Passed three selected managed-emulator tests:
  `AppTest#directMergePreviewsCancelsAndMergesWithoutAnAgentTurn`,
  `AppTest#directMergeConflictsDisableConfirmation`, and
  `AppTest#directMergeLostReplySurvivesRestartAndReconcilesWithoutReplay`.
  They verify draft/attachment preservation, cancellation, blocked conflicts,
  one merge request, recovery after model restart, and zero agent-turn requests.
- The first unit iterations exposed a submodule blocker-priority issue and a
  concurrent-writer fixture inheriting hook-local Git environment variables;
  corrected both before final validation. Shell syntax and `git diff --check`
  pass. No full emulator suite or release workflow was run.
- The final cover-screen fixture recording also passed using
  `scripts/emulator-record directMergePreviewsCancelsAndMergesWithoutAnAgentTurn --cover`.
  Demo artifacts are `artifacts/demos/direct-merge-main.mp4` and `.gif`; the
  recording uses mock RPC results. The owned emulator was stopped afterward.
  Only disposable test repositories were merged. Nothing was pushed, published,
  deployed, or installed on the physical phone.

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

## September 30, 2026 — stock socket repair and runtime upgrade gates

- Reproduced authenticated HTTP 502 through loopback and Tailscale WSS after
  Codex 0.159.2 activation. The owned control-socket alias was rejected, and the
  forwarder's private `/tmp` hid its target. Direct stock initialization and
  read-only project/task listing succeeded.
- The forwarder now validates the alias, private containing directories, target
  ownership/type/permissions and kernel peer UID, resolving anew on each
  connection. The service shares host `/tmp` while retaining `ProtectSystem=strict`.
  Fixed upstream failure reason codes are rate-limited without logging credentials,
  headers, RPC documents or transcripts.
- Five offline checker regressions and twenty LiteLLM runtime regressions passed.
  An isolated candidate connected and reconnected after a controlled restart with
  the real systemd protections. Restoring `PrivateTmp=true` reproduced 502.
  Candidate tests use an isolated home and synthetic credential, stop their scoped
  units, remove their socket/lock files, and have a 120-second service backstop.
- The first build stopped on two Clippy style errors before any publication or
  deployment. Its log and reserved version were retained. Corrected source passed
  the complete build workflow against exact Codex 0.159.2 and produced local
  Android 0.2.6 (16); its APK was not published or installed.
- Forwarder deployment succeeded, including authenticated WSS initialization and
  project/task listing. Narrow binary/unit recovery copies were removed after
  acceptance. The deployed candidate/restart check and runtime verification passed.
  Codex's live service start time remained September 30 at 16:39:32 EDT; only the
  forwarder restarted, at 18:15:48 EDT.
- Upgrade gates are committed in LiteLLM branch `codex/remote-connection-gates`
  (`94ff3bb`) and its utilities are installed. Automatic approval review initially
  rejected the merge; the user explicitly approved integration on October 1.
  The branch is now merged into LiteLLM `main`, and its temporary review worktree
  was removed after integration.
- At deployment, the physical phone was on 0.2.3 (13). Wireless ADB trust was
  verified and the existing app was opened, but the keyguard prevented initial
  UI confirmation. On October 1 the user confirmed the app works on the phone,
  completing physical-device acceptance. No account content was saved or displayed.

## October 1, 2026 — inline visualizations

- Added the visualize marker parser, bounded stock-RPC HTML reader, bundled runtime,
  isolated WebView renderer, full-screen expansion, local widget state, and confirmed
  follow-up drafting. No forwarder or stock Codex changes are required.
- Five parser/policy/size/state unit tests cover code examples, malformed and streaming
  markers, multiple visuals, invalid UTF-8, oversized files, and CDN URL validation.
  All 27 app unit tests and Android lint passed after the final source changes.
- The focused managed Android 16 tests passed:
  `VisualizationTest#sandboxRendersInteractsRestoresStateAndBlocksEscapes` and
  `AppTest#visualizationLoadsFromHistoryExpandsAndShowsMissingFileRecovery`.
  They exercise real WebView JavaScript and touch input, restored interaction state,
  follow-up messages, opaque-origin isolation, blocked file/network access, history
  rendering, full-screen expansion, and explicit missing-file recovery.
- Testing exposed and fixed interception of the viewer's own document, native message
  initialization, and compositor synchronization in the touch fixture. The renderer
  serves its shell locally at a dedicated HTTPS origin; the test waits for WebView's
  visual-state callback before tapping a recreated view.
- State remains Android-local, and desktop annotation controls are not advertised.
  CDN-backed content still requires connectivity. This change has not been published,
  installed on the phone, or validated against live account content.

## Compact inbox activity indicators

- Inspected static Codex desktop 26.928.40906 assets and ChatGPT Android
  1.2026.258 (2625815) reference sources. Desktop separates runtime attention
  from its unread store; Android's remote-thread trailing content includes a
  small unread marker and distinguishes working/approval/input/error states.
  These were static reference inspections, not live UI interaction.
- Implemented the requested spinner, unread blue dot, slowly pulsing blue
  approval/input marker, red warning, and blank quiet state. Screen-reader
  descriptions distinguish each state; disabled system animations are respected.
- Focused emulator runs passed four unique cases:
  `HomeScreenTest#chatIndicatorsAndScreenshots`,
  `AppTest#inboxRuntimeAndUnreadFollowServerAndVisibleReply`,
  `AppTest#readingEarlierParagraphKeepsNewReplyUnread`, and
  `AppTest#compactArchivesRestoreAndNavigation`.
  The viewport refinement was rechecked in the second three-test run.
- Fixture screenshots were captured from Compose and visually inspected:
  `artifacts/chat-status/chat-status-light.png` and
  `artifacts/chat-status/chat-status-cover-dark.png` (360 dp, 150% text).
- Pure tests cover runtime precedence, historical baselines, digest persistence,
  host separation, old responses, approval reconciliation, and first-observed
  running tasks completing unread. The release workflow runs these with the
  existing unit/lint/build checks.
- Read-only smoke checks against Grace verified initialization identity,
  metadata-only `thread/read`, and descending full `thread/turns/list` with
  limit 1. No mutation, background resume, server change, or phone installation
  was performed. Publication stays on `codex/compact-inbox`; merging remains
  pending phone testing.

## Screenshot report action placement (report 088d56ab)

- The report checkout (`0ce33a5`) predates the bug-report implementation.
  This fix uses local main at `9a622a6`, where the screenshot prompt uses
  Material's default trailing snackbar action.
- The focused regression uses the reported 1080x1272 display and 360 dpi,
  asserts that "Report bug" is left of "Screenshot taken", and verifies that
  tapping it preserves the captured draft and screenshot. Display overrides
  are applied before attaching the mock-backed activity and reset in cleanup.
- The original layout failed the placement assertion. An earlier fixture run
  failed before reaching the prompt because resizing after mock attachment
  recreated the activity; moving resizing to setup corrected that fixture.
- Diff-counter feasibility was checked against TypeScript bindings generated
  from installed stock `codex-cli 0.159.2` with
  `codex app-server generate-ts --experimental`. `TurnDiffUpdatedNotification`
  contains `threadId`, `turnId`, and the latest aggregate unified `diff` for
  that turn. `FileUpdateChange` contains `path`, `kind`, and `diff`;
  `GitDiffToRemoteResponse` contains `sha` and `diff`. These types do not expose
  ready-made added/removed line counters. A count-only UI can derive counts
  from diffs, but summing turn counts would not represent net worktree changes
  when later turns edit or undo earlier changes. No counter UI was added.
- With the layout fix applied,
  `scripts/emulator-test --tests AppTest#systemScreenshotOffersReportWithTheCapturedWindow`
  passed (one test, managed Android 16). No release workflow, deployment, or
  phone installation was performed.

## Completed-turn recorded changes and fullscreen diffs — 2026-10-04

- Started from local main `bcce5d0` (Android 0.4.1), which contains the reported
  Git banner and grouped activity. Removed the persistent banner and its automatic
  Git commands. Successful recorded file edits now produce one feed row only
  after their turn reports `completed`; discussion, failed, interrupted, and
  unfinished turns do not produce that row.
- Unit coverage verifies distinct paths, ordered repeated patches, failed and
  declined edits, missing patch text, chronological placement, and consistent
  live/history projection. `:core:test` and `:app:testDebugUnitTest` passed.
- Four new managed Android 16 tests passed in two bounded batches:
  `recordedChangesAppearAfterFinishedReply`, `discussionHasNoChangesChrome`,
  `recordedChangesSurviveHistoryReload`, and
  `recordedChangesSupportCompactLargeText`. Coverage includes fullscreen file
  selection, stored diff content, hardware Back returning to the file list,
  absent binary text, reload without duplication, and compact large-text access.
- `groupedToolActivityStreamsAndPreservesExpandedDetails` passed in the initial
  five-test batch. That batch exhausted its three-minute bound during the final
  test after first-build/startup overhead. The discussion assertion was corrected
  to await rendered Markdown and layout, rather than only model hydration; the
  affected tests passed in the subsequent smaller batches.
- Final `:app:lintDebug` passed with the existing managed JDK and a command-local
  `-Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8 -XX:+UseSerialGC"` override.
  The preceding lint attempt crashed in the JDK's
  `G1ParScanThreadState::trim_queue_to_threshold`; no shared JVM configuration or
  toolchain was changed. `git diff --check` passed.
- Fixture screenshots of the fullscreen file list and highlighted diff were
  inspected and retained under ignored `artifacts/completed-turn-changes/`.
  No release, deployment, or phone installation was performed.

## Native read-only task tools — 2026-10-05

- Integrated local main `597d7bc` into the feature worktree before final checks,
  preserving main's approval-context, approval-choice, and chat-cost changes.
- Both ordinary chats and report tasks advertise exactly `codex_app.list_threads`
  and `codex_app.read_thread` via `thread/start.dynamicTools`. The installed stock
  0.159.2 experimental schema confirms the namespace/function specification;
  `thread/resume` does not accept tool-registration overrides.
- A disposable stock app-server probe used the compiled Kotlin definitions, an
  isolated temporary Codex home, and a loopback fixture Responses provider. It
  verified both tool definitions in the model request, supported argument types,
  required fields and descriptions, and a namespaced `item/tool/call` callback.
  Stock removes numeric bounds/defaults during schema normalization; descriptions
  repeat them, and handler validation remains authoritative. No credentials,
  live account tasks, or real model inference were used.
- `:core:test` and `:app:testDebugUnitTest` passed after integration and the
  registration changes. Coverage includes limits, cursors, bounded outputs,
  invalid/unsupported requests, timeout/size failures, resolution, cancellation,
  duplicate IDs, and stale connection generations. Report-submission tests also
  check registration at their task-creation boundary.
- Four focused managed Android 16 scenarios passed:
  `nativeTaskToolsReadDuringHistoryLoadingWithoutOpeningTasks`,
  `unsupportedToolsFailOnHomeAndDuringHistoryLoading`,
  `fileApprovalRecoversLiveSnapshotBeforeHistoryFinishes`, and
  `extendedApprovalChoicesRequireConfirmationAndPreservePayload`.
  The native fixture now creates a task, checks eager tool advertisement, then
  lists and reads through those advertised names while another history load is
  held. Its initial navigation assertion raced turn completion; waiting for the
  fixture turn to finish fixed it, and the affected scenario passed on rerun.
- Existing tasks retain their existing tool definitions; this change does not
  retrofit registration, add mutation tools, or add a production server. No
  release, deployment, authentication change, or phone installation was performed.

## Task swipe action tray — 2026-10-06

- Right swipe now reveals a persistent left-side Read/Unread button without
  changing read state. Tapping the button performs the existing phone-local
  operation and closes the tray. Left swipe retains Archive/Unarchive and Undo;
  reversing an open tray closes it without archiving. Snooze remains deferred.
- The list permits one open tray and dismisses it on outside touch, scrolling,
  and list/connection changes. TalkBack custom actions and long-press deep-link
  copying remain available. Action cells use an 88dp width and existing row
  height; physical left/right placement also holds in RTL.
- Six focused managed Android 16 scenarios passed across two bounded runs:
  `taskSwipeMenuRevealsWithoutMutatingAndClosesSafely`,
  `taskSwipeTogglesUnread`, `taskSwipesArchiveUndoAndUnarchive`,
  `taskLongPressCopiesLinkAndAccessibleActionsWork`,
  `taskGesturesOnCompactScreenRespectCancellationAndPhysicalDirection`, and
  `hapticPreferencePersistsAndControlsSendQueueAndSwipeFeedback`.
  Coverage includes short/cancelled drags, cancelled closure restoring an open
  tray, reverse swipe, row/outside dismissal, physical action-button tapping,
  persistence, archive undo, accessibility, compact RTL, and haptic preference.
- The initial run passed four scenarios. The short-drag test initially moved
  below touch slop and opened the chat as a tap; the corrected gesture clears
  touch slop while staying below the reveal threshold. The haptic test was
  updated to close the tray before opening the chat. Both passed on rerun,
  along with the affected RTL scenario after fixing Archive hint placement.
- `:app:lintDebug` and `git diff --check` passed. Lint used the existing managed
  JDK with a command-local SerialGC setting; no toolchain configuration changed.
  No release, deployment, snooze mutation, or phone installation was performed.

## Conversation task rename — 2026-10-06

- Added Rename to the conversation overflow menu using stock `thread/name/set`,
  followed by a metadata-only `thread/read` to display the authoritative name.
- Fixture-backed emulator validation passed across selected runs:
  `AppTest#conversationMenuRenamesTaskAndUpdatesList`,
  `AppTest#rejectedRenamePreservesTitleAndAllowsCorrection`, and
  `AppTest#lostRenameReplyDoesNotAutomaticallyRetry`.
  Coverage includes emoji names, whitespace trimming, blank-name validation,
  list refresh, explicit rejection, duplicate submissions, lost acknowledgement,
  and read-only reconciliation after reconnect.
- Initial runs encountered an incremental Kotlin cache failure, an undeclared
  Espresso dependency in another shared-checkout test, and the three-minute
  limit during compilation/emulator startup. Declared the already cached
  Espresso 3.5.0 test dependency and used command-local in-process compilation
  with incremental compilation disabled. The lost-response case passed before
  a run timed out; the two remaining cases then passed in a successful run.
- `git diff --check` passed. The UI change has not been released or installed on
  the phone.

## One-hour task snooze and custom return time — 2026-10-06

- The right-swipe tray now exposes Read/Unread and Snooze in two 88dp cells.
  Snooze immediately requests the fixed one-hour duration. Its confirmation
  offers Change time; native date/time pickers, Save time, and Return now also
  remain accessible through Settings → Snoozed chats. Running chats wait for
  idle before hiding; restoring visibility does not start a model turn.
- The app uses the installed public `codex-tasks` protocol over stock
  `command/exec`, pins the host/account/Codex home and connection generation,
  and persists a delivery guard before mutations. Reconnects inspect inventory
  without replaying uncertain requests. Old inventory responses cannot overwrite
  newer mutations or clear their delivery guards. Unarchiving a managed snooze
  cancels its timer; archiving a chat waiting to snooze requires Return now first.
- The full app unit suite passed (150 tests, including 11 snooze tests), along
  with `:app:lintDebug`, debug app assembly, and instrumentation APK assembly.
  Unit coverage includes helper identity/arguments, one-hour/custom deadlines,
  persistent uncertainty, restart recovery, duplicate-send prevention, retained
  failed schedules, clock gaps/overlaps, wake-up membership, and stale reads.
- Five focused managed Android 16 scenarios passed:
  `snoozeTapDefaultsToHourAndChangeTimeCanReturnEarly`,
  `snoozeCustomDatePersistsInSnoozedChatsAndCanReturnNow`,
  `snoozePendingAndUncertainRequestsAreInspectedWithoutReplay`,
  `snoozeCompactTrayAndEditorRemainReachable`, and
  `snoozeUnarchiveCancelsTimerInsteadOfLeavingAHiddenSchedule`.
  These exercise the physical tray buttons, one-hour argv, native date/time
  selection, retained controls, pending visibility, explicit return, reconnect
  without replay, compact RTL with enlarged text, and unarchive cancellation.
- All six swipe regression scenarios passed with the two-action tray:
  `taskSwipeMenuRevealsWithoutMutatingAndClosesSafely`,
  `taskSwipeTogglesUnread`, `taskSwipesArchiveUndoAndUnarchive`,
  `taskLongPressCopiesLinkAndAccessibleActionsWork`,
  `taskGesturesOnCompactScreenRespectCancellationAndPhysicalDirection`, and
  `hapticPreferencePersistsAndControlsSendQueueAndSwipeFeedback`.
- Initial shared-output emulator provisioning failed before tests; validation
  moved to a disposable isolated checkout. The first isolated UI run exposed
  that the replacement fixture model was not marked foregrounded. Correcting
  the fixture enabled its inventory polling, and both affected scenarios passed.
  Validation used the existing managed toolchain with command-local incremental
  compilation disabled; no shared toolchain settings changed.
- These are fixture-based checks. No live snooze was sent, release published,
  service deployed, or app installed on the user's phone. Small validation
  reports are retained under ignored `artifacts/snooze-validation/`.

### Multi-project chat scopes

- Added project checkboxes that select any subset of projects and optionally
  projectless chats. Apply retains atomic project/sort changes; Reset selects all.
- Multi-project browsing filters stock pages, continuing past excluded results;
  full-text search uses the same scope without sending unsupported project fields.
- `scripts/emulator-test --tests
  HomeScreenTest#multipleProjectsAndProjectlessApplyTogether
  HomeScreenTest#sheetAppliesAtomicallyAndDismissesWithoutChanges
  AppTest#multipleProjectsPageBeforeAndDuringSearch
  AppTest#compactBrowserQueriesAndPagination`: the three existing/model checks
  passed. The new UI test initially had an ambiguous project-name selector;
  constrained it to selectable rows and reran that test successfully. All four
  targeted checks passed across these runs.
- Added the stock 0.154.0 facet inventory in `SEARCH-CAPABILITIES.md`, including
  unavailable aggregation/count fields and the cost of client-side scope scans.

### Foreground credential request alerts — 2026-10-07

- Local metadata publication, permission/path rejection, stale socket recovery,
  fanout to two chat clients, and unchanged fragmented stock payloads pass.
- The official build caught oversized upstream frames after message reassembly.
  Regenerating 256 KiB fragments fixed it; all 18 forwarder tests, including the
  isolated stock Codex 20 MiB attachment round trip, pass.
- Alert controller fixtures cover coalescing, lifetime deduplication, grouped
  expiration, cancelled/obsolete reads, connection changes, and discovery errors.
- Focused managed-emulator validation passed Review navigation, visible Dismiss,
  chat streaming/reopen, protected picker handoff/recreation, and credential
  release without replay. Initial alert fixtures needed explicit foreground
  activation and their test-specific chat route; corrected and rerun successfully.
- Op-bridge change `1b20930` passed Go tests, race tests, vet, package checks and
  a Darwin cross-build. All secret interactions used disposable fixtures.

### Credential alert deployment — Android 0.4.11 (43), 2026-10-07

- Signed build completed with all required Rust, Kotlin, Android unit, compile,
  lint, signing and artifact checks through the repository workflow.
- Rust forwarder and `remote-codex notify credential-requests-changed` deployed
  successfully on Grace. Authenticated WSS initialization/listing, shared Todo
  access, and local metadata publication passed the deployment acceptance checks.
- Grace op-bridge `dev-20261007-1b20930` deployed with the phone event socket at
  `/run/user/1001/remote-codex/events.sock`. Existing default route remains `mac`.
  Installed version/route verified; staging and recovery files removed. No other
  op-bridge host was deployed.
- Android 0.4.11 (43) published to the private stable channel. Authenticated HTTPS
  manifest and full APK verification passed. Installation remains user initiated.
- Build: `artifacts/releases/run-20261007T145847Z-DIEIkh.log`.
  Forwarder: `artifacts/releases/run-20261007T150344Z-Zq7C4A.log`.
  Publication: `artifacts/releases/run-20261007T150549Z-o70jWR.log`.
