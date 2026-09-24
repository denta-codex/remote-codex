# Remote Codex 0.1.3

Android uses OkHttp WSS over the existing Tailscale app. Persistent Tailscale Serve
(`--bg`) terminates TLS and proxies the root route to 127.0.0.1:8787. The Go service
accepts only authenticated `/codex/rpc` upgrades and connects one Unix stream per
client to the existing stock Codex socket. It strips its bearer credential before
forwarding. There is no RPC rewriting, backend task store, or new Codex process.
The forwarder also removes WebSocket extension offers: the stock control socket
closes handshakes offering `permessage-deflate`, which OkHttp offers by default.
Without extension negotiation both endpoints exchange ordinary WebSocket frames.

Android owns presentation, encrypted connection credentials, drafts, and submission
records. Stock Codex owns execution, configuration, task IDs and durable history.
The host bearer token is a systemd encrypted user credential loaded at service
start. Android scans its versioned setup QR and stores the token with an Android
Keystore key. Authentication applies only to the forwarder upgrade; stock RPC
remains unchanged.
Android has no SSH transport. SSH is for deployment/recovery. Additional HTTP
transfer/preview endpoints are deferred.

The protocol module separates responses, notifications, and server requests even
when IDs overlap. Events carry a local connection generation; old-generation
requests cannot be answered. History uses 20-turn pages. Bounded read retries
cover the observed initial persistence delay; mutations are never replayed.

The client pages the stock `project/list` catalog and keeps project identity and
thread assignment server-owned. The task browser can show all tasks, projectless
Chats, or one existing project. New tasks default to projectless execution: their
directories are fixed under `/home/agent/Documents/RemoteCodex`, using a client
UUID, and preparation uses an explicit workspace-write sandbox rooted there without
network access. Selecting an existing project instead uses its first stock project
root as the current workspace and passes its ID to `thread/start`; it does not create
a directory or worktree.

Each send is journaled before dispatch, including a new task's project and workspace.
Known IDs are saved before the next step.
An unacknowledged operation blocks further submission until the user inspects it.
A reviewed record is retained locally; the composer can be explicitly unlocked.
A successful acknowledgement removes the draft. Offline sending is not queued.

Stock 0.154.0 can briefly return `list_turns is not supported yet` or `no rollout
found` just after creation. This is retried only on history/resume reads, for a
bounded interval. The first live turn already has a subscription and is rendered
from its events, without an immediate resume call.

Approvals are connection-scoped. Resolved requests disappear even if answered by
another client. A missing file-change body disables approval; the user is directed
to desktop. Unsupported dynamic/MCP requests remain visible as desktop-required.
No auto-approval is performed. Permission grants are limited to the current turn.

Limits: existing projects can be selected but not created, deleted, reordered, or
edited; only the first project root is offered and worktrees are not created. There
are no push notifications, media, terminal emulator, model/mode selectors, or
interactive previews. Activity text is bounded for phone rendering; full output
remains on Grace. End-to-end physical-device behavior is a release acceptance step,
not inferred from successful builds.
