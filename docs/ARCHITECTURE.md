# Remote Codex 0.1.8

Android uses Java-WebSocket WSS over the existing Tailscale app. Persistent Tailscale Serve
(`--bg`) terminates TLS and proxies the root route to 127.0.0.1:8787. The Rust service
accepts only authenticated `/codex/rpc` upgrades and connects one Unix stream per
client to the existing stock Codex socket. It strips its bearer credential before
forwarding. There is no RPC rewriting, backend task store, or new Codex process.
The forwarder also removes WebSocket extension offers: the stock control socket
closes handshakes offering `permessage-deflate`. Android does not offer extensions,
and the forwarder strips them defensively before the stock handshake. After the
upgrade, Rust copies bytes without decoding WebSocket messages, preserving client
fragment boundaries and backpressure.

Android owns presentation, encrypted connection credentials, drafts, and submission
records. Stock Codex owns execution, configuration, task IDs and durable history.
The host bearer token is a systemd encrypted user credential loaded at service
start. Android scans its versioned setup QR and stores the token with an Android
Keystore key. Authentication applies only to the forwarder upgrade; stock RPC
remains unchanged.
Android has no SSH transport. SSH is for deployment/recovery. Attachment transfer uses
stock `fs/createDirectory`, `fs/writeFile`, `fs/readFile`, and `fs/getMetadata` RPC over the same WSS
connection; there is no additional HTTP upload or preview endpoint.

The project-owned private updater is deliberately outside the stock RPC surface.
Authenticated `GET /remote-codex/v1/updates/stable/latest.json` and immutable
`GET /remote-codex/v1/updates/releases/<versionCode>/remote-codex.apk` routes
serve files from the forwarder's read-only update root. Their manifest schema is
`dev.codexops.remote-codex.update/v1`; `/codex/rpc` remains the only stock Codex
bridge. Update discovery is user initiated in Settings. Android downloads into
app-private storage, verifies the expected package, higher version, SHA-256 and
pinned signing certificate, then commits a `PackageInstaller` session that always
requires user confirmation. Publishing is explicit and manifest-last. It checks
that the installed host supports the authenticated update extension, but it does
not couple an Android-only release to the exact forwarder binary produced by the
release build. Taildrop remains available for bootstrap and recovery.

The protocol module separates responses, notifications, and server requests even
when IDs overlap. Events carry a local connection generation; old-generation
requests cannot be answered. History uses 20-turn pages. Bounded read retries
cover the observed initial persistence delay; mutations are never replayed.
Android serializes each stock JSON-RPC message once and emits 256 KiB RFC 6455
continuation frames, with a 100 MiB message ceiling. This removes OkHttp's 16 MiB
outgoing queue limit while leaving the stock RPC document unchanged. Reconnect
creates a new stock session and reloads server-owned state; there is no sequence,
acknowledgement, replay, or custom chunk envelope between Android and the host.

The client pages the stock `project/list` catalog and keeps project identity and
thread assignment server-owned. The task browser can show all tasks, projectless
Chats, or one existing project. New tasks default to projectless execution: their
directories are fixed under `/home/agent/Documents/RemoteCodex`, using a client
UUID, and preparation uses an explicit workspace-write sandbox rooted there without
network access. Selecting an existing project supplies its first stock project root
to `NewTaskOptions`; Android can use that checkout
or create a detached worktree from its local `origin/HEAD`. Worktrees use a
deterministic path under `CODEX_HOME/worktrees/remote-codex-<operation>/workspace`.
Project identity remains the selected stock `projectId`; it is not inferred from
or replaced by the worktree path.

Worktree orchestration is a narrow client adapter over stock `project/read` and
`command/exec`; there is no invented worktree RPC and no second project browser.
The command shape and `dangerFullAccess` policy match the host-verified
`codex-tasks` behavior required to update Git's common worktree metadata. The
adapter only resolves `origin/HEAD`, creates the destination directory, adds a
detached worktree, and reads `git worktree list --porcelain` for reconciliation.
It never fetches, creates a branch, removes a worktree, or manages general Git state.

Each mutating setup/send stage is journaled before dispatch: destination creation,
worktree addition, task creation, attachment directory/file writes, and input
submission. Known paths, revisions, uploaded host paths, and
task IDs are saved before the next stage. Reconnect recovery inspects deterministic
directories, Git's authoritative worktree list, and filtered stock task pages. It
continues only after confirming the prior stage or when the next mutation was never
attempted; it does not replay an uncertain mutation. An unacknowledged operation
blocks further submission until the user inspects it. A reviewed record is retained
locally; the composer can be explicitly unlocked. A successful acknowledgement
removes the draft. Offline sending is not queued.

During a running turn, normal send uses stock `thread/queue/add`. If queued
submissions remain after an interruption, new messages also join that queue.
An idle task with an empty queue still uses `turn/start`. The server owns queue
ordering, persistence, and automatic consumption; Android does not drain a local
outbox or start turns in response to completion notifications. The composer shows
the server queue above the draft, with Remove and Steer now (Send now while idle).
Queued submissions inherit task settings because the queue contract has no model,
effort, or collaboration-mode override fields.
On the compact cover screen, disabled model/mode controls are omitted while work
is queued or active. With the keyboard open, a Queued shortcut replaces the queue
panel; tapping it dismisses the keyboard and reveals the queued-message actions
without changing the draft.

Queue reads use paginated `thread/queue/list` on task hydration, reconnect, and
`thread/queue/changed`. Selection, connection generation, and read revision guards
prevent older responses from replacing the current task's queue. Failed queue reads
disable queue mutations and offer Refresh; they never switch normal send to steer.

Steer now follows the Android reference's delete-before-steer sequence: journal
the saved input, delete the queue entry, then call `turn/steer` with the original
`clientUserMessageId` and the captured `expectedTurnId`. A false delete result means
the entry was already consumed or removed, so its cached input is never sent.
Each mutation is journaled independently. A rejected steer retains the original
input for explicit retry; an uncertain delete, start, or steer requires review and
is never retried on reconnect. Promotion does not overwrite the composer's draft
or attachments. Idle Send now uses `thread/queue/start` with the selected entry ID.

Selected images, camera images, and documents are imported immediately into app-private draft
storage, and their descriptors are persisted with the text draft. Originals are preserved.
Before the turn mutation, attachments are written sequentially beneath
`$CODEX_HOME/attachments/remote-android/<thread>/<operation>`. Every uploaded path is included
in the Android Codex `# Files mentioned by the user` context; images are additionally referenced
with stock `localImage` items. An uncertain attachment write or turn submission remains journaled
and is never retried automatically. Limits match the ChatGPT Android remote client: 20 MiB per
attachment and 50 MiB combined. Images support JPEG, PNG, WebP, and non-animated GIF; other
regular file types are transferred as generic files.

Timeline entries retain stock `image`, `localImage`, `imageView`, and
`imageGeneration` media. Host paths are fetched lazily with `fs/readFile`; data URLs
and generation results are decoded locally. Raw host images use a bounded app cache
and sampled rendering. External HTTP image URLs require an explicit tap and are
never fetched automatically.

User attachment history is reconstructed from the server-owned file context. Generic file
references and file-change paths are validated with stock `fs/getMetadata`, then fetched only
after an explicit tap. Android previews bounded UTF-8 text and images, exposes other types through
`FileProvider`, and offers Open, Share, and `CreateDocument` save actions. Remote files remain
limited to 20 MiB; the private preview cache is bounded and expires unretained files after seven
days. Ordinary HTML file links remain text previews.

Completed assistant messages recognize standalone `visualize{"path":"/absolute/file.html"}`
references, with optional `title` and `mode: "wide"`. Code examples and malformed references
remain Markdown. The viewer reads the fragment using stock `fs/getMetadata` and `fs/readFile`
over WSS, checks both reported and actual sizes against 1 MB, and requires valid UTF-8.
It supports responsive inline rendering and a full-screen view on either phone display.

Each viewer uses a WebView shell and an opaque-origin `sandbox="allow-scripts"` iframe.
The skill's versioned runtime assets supply styles, tabs, tooltips, calendars, carousels,
and optional mockup helpers. The native message port belongs only to the trusted shell;
there is no JavaScript Android interface. File/content access, API connections, nested frames,
forms, popups, permissions, and downloads are disabled. Only HTTPS resources from the skill's
seven CDN hosts can load; the bounded resource loader checks every redirect and supplies no
application credentials or WebView cookies. Operational logs contain no HTML or messages.

Widget state is limited to 16 KiB and stored locally under a hash of host, chat, message,
reference position, and path. It survives view recreation and reopening the chat; it is not
synced to desktop or injected into model context. `sendFollowUpMessage` requests require a
touch gesture and native confirmation, then append to the existing composer without sending.
External HTTPS links also require confirmation. Desktop annotation/Tweak controls are not
advertised; guarded mockups retain their normal rendering and local interactions. CDN-backed
charts/icons require connectivity. Missing files offer an explicit read-only Retry action.

Plan mode is exposed only when the stock `collaborationMode/list` capability
advertises it. The selected stock collaboration setting is sent with `turn/start`;
the mode selector remains available during a running turn. Selecting an explicit
mode keeps the draft unsent until both the active turn and server queue are clear,
because queue submissions cannot carry that setting. The composer explains the
wait; sending remains an explicit user action. Selecting Server default restores
normal queueing. Attachments share an Add menu, and model controls are hidden while
queueing because queued submissions use task settings. On cover screens they also
hide while typing to keep the mode and send actions reachable above the keyboard.
Completed plans render in a dedicated card and full-screen viewer. Implementing a
completed plan starts a new turn in the advertised default mode and is never
simulated when the server capability is absent.

Stock 0.154.0 can briefly return `list_turns is not supported yet` or `no rollout
found` just after creation. This is retried only on history/resume reads, for a
bounded interval. The first live turn already has a subscription and is rendered
from its events, without an immediate resume call.

Approvals are connection-scoped. Resolved requests disappear even if answered by
another client. A missing file-change body disables approval; the user is directed
to desktop. Unsupported dynamic/MCP requests remain visible as desktop-required.
No auto-approval is performed. Permission grants are limited to the current turn.

The project browser supplies `projectId` plus the chosen absolute
`workingDirectory`; the workspace adapter validates both with `project/read`.
Existing projects can be selected but not created, deleted, reordered, or edited,
and only the first project root is offered. New worktrees require a locally
resolvable `origin/HEAD` and
do not include uncommitted checkout changes. Worktrees are deliberately retained;
cleanup, branch/ref selection, and general Git management are outside this feature.
Environment execution is a shared workspace-adapter operation with an explicit
execution deadline and a durable success receipt. Bug reports currently invoke
the repository's setup script; normal new-task UI does not yet expose environment
selection. Long commands use operation-specific RPC deadlines rather than the
ordinary request default.

## User-authored bug reports

Android owns the report UI, collectors, draft persistence, and orchestration; the
forwarder and stock app-server protocol are unchanged. The menu and a foreground
Seismic shake detector invoke the same capture flow. The copied Apache-2.0
detector's license ships in `assets/licenses/seismic.txt`. Registration uses
`SENSOR_DELAY_GAME`, stops when the activity pauses, and has a three-second
invocation cooldown.

The phone stores one pending report and its artifacts under app-private
`files/bug-reports/<UUID>`, with an atomically replaced draft index. The frozen
context is an explicit field selection, not a raw RPC/state dump. It includes
loaded visible conversation content and marks unloaded history. Recent action
metadata is bounded to 100 entries / five minutes. Known pairing credentials are
redacted from text evidence; the Settings screen is excluded from screenshots.
PixelCopy captures the focused app window on Android 14+, and the activity window
on earlier releases (unsupported dialog capture is recorded as unavailable).
Collectors record their own timestamps and failures; app logcat is bounded to
five minutes, 2,000 lines, and 512 KiB. Android 11+ supplies abnormal process-exit
metadata within 24 hours and an available trace up to 2 MiB. Diagnostics are
report artifacts, never operational log output.

Report submission has its own persisted journal, independent of the original
conversation journal. It resolves the configured remote-codex project, verifies
the repository origin, uses the existing detached-worktree adapter, and executes
`scripts/setup-worktree` with a two-minute process deadline plus transport grace.
The setup command atomically writes an operation/revision receipt after success.
Evidence resides beside the worktree checkout. The first turn uses server-default
model settings, a human-authored description, artifact references, and explicit
implementation instructions. Every mutation is journaled before dispatch;
recovery compares authoritative receipts, uploaded bytes, and task/message IDs.
Missing or conflicting evidence leaves the operation pending instead of replaying
it. Accepted reports retain the task reference and remove local artifacts.

Limits: no push notifications, directory attachments, terminal emulator, or interactive
command previews. Activity text is bounded for phone rendering; full output
remains on Grace. End-to-end physical-device behavior is a release acceptance step,
not inferred from successful builds.

## Control socket and upgrade acceptance

The forwarder resolves the configured control-socket alias for each authenticated
connection. The alias and resolved Unix socket must belong to the agent account,
their containing directories must be private, and the socket must not be writable
by other accounts. The connected peer's kernel-reported UID is checked before any
HTTP upgrade bytes are sent. Missing or unsafe targets fail closed with 502.

Stock Codex 0.159.2 publishes an alias into `/tmp/codex-daemon-<uid>/`. The service
shares host `/tmp` so that target is visible; `ProtectSystem=strict` still makes
the filesystem read-only. Upstream failures emit fixed reason codes and HTTP
status, at most once per 30 seconds, without paths, credentials or RPC content.

`uv run --no-project scripts/connection-check.py` checks the live authenticated
WSS route, stock initialization, account home, and read-only project/task listing.
Deployment installs this check and requires it before reporting success.
`candidate-check.py --codex-binary /absolute/bin/codex` uses the installed
forwarder and its actual unit/drop-in protections with isolated systemd units,
a synthetic credential and an empty Codex home. It checks a connection and a
candidate restart, then removes its scoped units, files and daemon locks. It
never sends inference requests or restarts the live server.

Grace's LiteLLM runtime utility requires candidate acceptance before staging a
version, and live WSS acceptance during verification and immediately before
finishing a transaction. A failed gate preserves runtime selection or pending
recovery state; rollback and Desktop Restart remain explicit. Forwarder deployment
keeps narrow Ansible backups of the old binary and unit until live acceptance
succeeds. If deployment fails, retain the reported backup paths for explicit
recovery; remove them once recovery and verification are complete.
