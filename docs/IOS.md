# Native iPhone client

The first Apple client is an independent Swift/SwiftUI app targeting iPhone on
iOS 18 and later. `ios/` contains the app, its Foundation-based `RemoteCodexCore`
package, core tests, fixture CLI, and simulator UI tests. Android remains the
behavioral reference. Stock Codex owns tasks, execution, and history; the app
connects to the existing Grace WSS endpoint through Tailscale.

## Scaffold scope (device acceptance remains pending)

| Capability | Required behavior |
| --- | --- |
| Setup | Token-only setup QR/manual entry; Keychain; expected Codex home verification; connection errors. |
| Chats | Paged active chats, projectless creation, reopen, Markdown/code, streaming and tool activity, stop. |
| History | Recent full-turn resume, older pages, live-event reconciliation; explicit smaller-history recovery. |
| Queue | Server queue on busy send; list, remove, and idle Send now. Steer now comes later. |
| Decisions | Command/file/permission approvals and questions; advertised choices and context; generation guards. |
| Recovery | Durable drafts/journals; acknowledged IDs; uncertain mutations reconciled through reads, never replayed. |
| Usability | Keyboard, scrolling, Dynamic Type, VoiceOver, dark mode, lifecycle, empty/loading/error states. |
| Delivery | Separate iOS builds, fixture simulator checks, development IPA, USB installation, upgrade preservation. |

Attachments, project/worktree selection, model controls, search, archive/rename,
Todo, costs, reports, credential workflows, visualizations, and incoming shares
are later milestones. iPad layouts and a native Mac UI are later work; the core
package is separate from UIKit to permit reuse.

## Mutation outcomes and recovery

RPC IDs correlate responses; they do not make repeated mutations safe. A durable
record precedes each dispatch and retains immutable input, client-message ID,
stage, working directory, acknowledged thread ID, and outcome.

- Prepared: no attempt has been recorded; dispatch may proceed.
- Attempted: saved before calling transport; a restart treats this as uncertain.
- Acknowledged: server reply saved before advancing to the next stage.
- Uncertain: reconnect reads server state and never repeats the operation.

Unresolved journals block conflicting writes. The UI retains the draft and offers
read-only checking. Matching client-message identity or an acknowledged thread
can establish acceptance. Absence on a partial history page cannot establish
failure. If acceptance cannot be established, the record remains uncertain until
the user inspects the task and explicitly discards the local record. Discarding
never sends anything. Lost new-chat creation acknowledgements require inspecting
existing chats and the deterministic workspace, not creating a second thread.

Approval replies are attempted once per request/generation. Desktop resolution
disables them. Historical snapshots cannot create actionable requests. File
grants need matching thread/turn/item context. Permissions remain a subset of the
request. Persistent grants display consequences and require confirmation.

## Validation

`ios-check.yml` runs on the standard GitHub-hosted Apple Silicon `macos-15` runner.
Xcode 16.4 (16F6) and the preinstalled iOS 18.6 runtime are pinned explicitly;
the job rejects another compiler or architecture and creates its own iPhone 16
simulator. The app still supports iOS 18.0+. Core tests, an unsigned
`build-for-testing`, and the three fixture UI tests run in measured stages.

Push and PR checks receive no signing or live-server secrets. Only compact JSON,
Markdown, durations, selected synthetic screenshots, and bounded failure logs
are uploaded, with one-day retention. DerivedData and complete success xcresults
are excluded. Fixture mode uses synthetic content, isolated
`RemoteCodexFixture/client.sqlite` storage, and in-memory credentials; it never
opens the production transport or Keychain. Launch with `--fixture`.

Required scenarios include dropped mutation replies, request-ID collisions,
stale generations, restart journals, queue races, history/live overlap,
desktop-resolved approvals, and websocket size/fragmentation boundaries. Limits
are 32 MiB inbound and 100 MiB outbound. Size rejection preserves loaded content
and drafts and stops automatic reconnect until explicit recovery. A websocket
failure cannot identify the offending RPC. Operational output is result metadata
only: no credentials, RPC payloads, or transcripts.

Physical iPhone acceptance covers Tailscale, Keychain, camera QR setup, keyboard,
suspension, accessibility, and upgrade retention. Live mutations and phone
installation remain separate explicit actions.

## Development distribution

GitHub development IPA signing will use a separate manually triggered workflow
and the `ios-development` environment. Bundle identifier: `dev.codexops.client.ios`.
Device tooling belongs to `scripts/ios-device` in its separate workstream.
No personal-Mac build or TestFlight upload path is supported.

## Runner evidence

Selection reviewed October 10, 2026 against primary sources:

- [GitHub standard runner labels](https://docs.github.com/en/actions/reference/runners/github-hosted-runners): `macos-15` is Apple Silicon for public repositories.
- [macOS 15 arm64 image manifest](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md): Xcode 16.4 build 16F6 and iOS 18.6 installed.
- [Manual workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow): workflow dispatch requires default-branch registration.

GitHub image revisions move; tool/runtime pins fail visibly if removed. The
completed run records the actual image version, revision, run attempt, and durations.

The reference is the committed Android revision at worktree creation. New Android
capabilities enter the checklist after integration. Checked-in stock schemas
predate some Android contracts; verify consumed methods against the installed
schema without modifying the host/server.
