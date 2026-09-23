# Remote Codex 0.1.0

Android uses OkHttp WSS over the existing Tailscale app. Persistent Tailscale Serve
(`--bg`) terminates TLS and proxies the root route to 127.0.0.1:8787. The Go service
accepts only authenticated `/codex/rpc` upgrades and connects one Unix stream per
client to the existing stock Codex socket. It strips its bearer credential before
forwarding. There is no RPC rewriting, backend task store, or new Codex process.

Android owns presentation, encrypted connection credentials, drafts, and submission
records. Stock Codex owns execution, configuration, task IDs and durable history.
Android has no SSH transport. SSH is for deployment/recovery. Additional HTTP
transfer/preview endpoints are deferred.

The protocol module separates responses, notifications, and server requests even
when IDs overlap. Events carry a local connection generation; old-generation
requests cannot be answered. History uses 20-turn pages. Bounded read retries
cover the observed initial persistence delay; mutations are never replayed.

New task directories are fixed under `/home/agent/Documents/RemoteCodex`, using a
client UUID. Directory preparation uses an explicit workspace-write sandbox rooted
there, without network access. Task execution then inherits Grace defaults.

Each send is journaled before dispatch. Known IDs are saved before the next step.
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

Limits: no push notifications, media, project/worktree controls, terminal emulator,
model/mode selectors, or interactive previews. Activity text is bounded for phone
rendering; full output remains on Grace. End-to-end physical-device behavior is a
release acceptance step, not inferred from successful builds.
