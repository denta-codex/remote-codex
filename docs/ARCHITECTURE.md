# Remote Codex 0.1.6

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
Android has no SSH transport. SSH is for deployment/recovery. Interactive preview
endpoints remain deferred.

The project-owned private updater is deliberately outside the stock RPC surface.
Authenticated `GET /remote-codex/v1/updates/stable/latest.json` and immutable
`GET /remote-codex/v1/updates/releases/<versionCode>/remote-codex.apk` routes
serve files from the forwarder's read-only update root. Their manifest schema is
`dev.codexops.remote-codex.update/v1`; `/codex/rpc` remains the only stock Codex
bridge. Update discovery is user initiated in Settings. Android downloads into
app-private storage, verifies the expected package, higher version, SHA-256 and
pinned signing certificate, then commits a `PackageInstaller` session that always
requires user confirmation. Publishing is explicit, host-first and manifest-last;
Taildrop remains available for bootstrap and recovery.

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
worktree addition, task creation, and input submission. Known paths, revisions, and
task IDs are saved before the next stage. Reconnect recovery inspects deterministic
directories, Git's authoritative worktree list, and filtered stock task pages. It
continues only after confirming the prior stage or when the next mutation was never
attempted; it does not replay an uncertain mutation. An unacknowledged operation
blocks further submission until the user inspects it. A reviewed record is retained
locally; the composer can be explicitly unlocked. A successful acknowledgement
removes the draft. Offline sending is not queued.

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
cleanup, branch/ref selection, setup environments, and general Git management are
outside this feature.

Limits: no push notifications, media, terminal emulator, model/mode selectors, or
interactive previews. Activity text is bounded for phone rendering; full output
remains on Grace. End-to-end physical-device behavior is a release acceptance step,
not inferred from successful builds.
