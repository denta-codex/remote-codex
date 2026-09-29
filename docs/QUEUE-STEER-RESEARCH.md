# Queue and steer inspection — September 29, 2026

Inspected the installed desktop bundle and the repository's decompiled Android
reference. These are implementation observations for the versions below, not a
claim that every OpenAI client or channel has identical behavior. Hosted official
documentation lookup returned no usable content during this inspection.

## Desktop

Read `/Applications/ChatGPT.app/Contents/Resources/app.asar` on the configured Mac.
Its package version is **26.924.22138**; SHA-256:
`d0ba973179d2f717affd39e012b64a095464a54a51c6bccb7bc6b3d2a1cfba80`.

- `webview/assets/app-shared-36eae88777f2.js` contains the server queue adapter:
  add/update, paginated reads, delete, reorder, interrupted-queue resume, and
  send-now. It subscribes to `thread/queue/changed`.
- Its send-now adapter steers during streaming using the queued submission's
  client message ID, then deletes the queue entry. While idle it calls
  `thread/queue/start` with the selected queued submission ID.
- The shared turn coordinator distinguishes queue intent from a send-now
  override and refuses to resend submissions marked sending or outcome-unknown.
- `webview/assets/app-primary-cca0c1a58f0f.js` contains the queued-message send-now
  UI handler. It preserves the message context and reports attempt/result state.

The downloaded bundle and temporary extracted files are inspection-only staging
and are removed after recording these findings.

## Android

Reference: `artifacts/chatgpt-android/METADATA.txt`, version **1.2026.258 (2625815)**.
The existing artifact was inspected without refreshing it. Source paths below are
relative to `artifacts/chatgpt-android/simple/sources/defpackage/`.

- `g6g.java`: distinct `Queue` and `Steer` intents, with queue used as the default
  by the composer submission path (`xe6.java`, `xsf.java`).
- `o5f.java`: concrete calls to `thread/queue/add`, `list`, `update`, `delete`,
  `reorder`, `start`, and `turn/steer`. `qih.java` serializes queue add with
  `threadId`, `input`, and `clientUserMessageId`.
- `fuh.java` method `N`: promote the selected queued entry to steer intent for
  the current task and turn.
- `u1a.java` branch starting at label `L235`: for a server-backed queued entry,
  await removal, require successful deletion, then submit steer; definite failure
  has a restoration path. `m6g.java` defines the queue interface.

Remote Codex uses this delete-before-steer ordering so a lost steer acknowledgement
cannot leave the same entry waiting to be consumed from the server queue. It saves
the input and journals both mutations instead of automatically restoring or
replaying an uncertain result.

## Protocol and chosen UX

The checked-in `protocol/stock-0.154.0-ClientRequest.json` and
`protocol/stock-0.154.0-ServerNotification.json` already define these requests and
the queue-change notification. There is no atomic queue-to-steer operation.

Normal send queues while working or behind existing queued messages. The queue is
visible above the composer, with an explicit Steer now action and Remove action.
An interrupted, idle queue offers Send now. The draft stays independent of queue
actions. Images and files retain their uploaded server paths when promoted.
Queue add accepts no model, effort, or collaboration override, so queued messages
use task settings and the composer communicates that distinction.
