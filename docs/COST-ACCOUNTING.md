# Cost snapshots

The cost feature starts from Android 0.4.14 (`d6ce9ee`) and replaces automatic
whole-rollout reads with local host accounting. It uses the existing stock
`command/exec` API over the existing WSS connection; Codex and the forwarder's
byte-copy `/codex/rpc` route remain unchanged.

## Invocation and contract

Android invokes `/home/agent/.local/libexec/remote-codex-forwarder accounting
<json>` as an argv vector, without a shell. The subcommand runs before credential
loading or service startup, with no network, writes, cache files, or daemon.
Stock execution uses a read-only sandbox, a 2-second deadline and a 64 KiB output
cap. Android allows 2.5 seconds for each RPC and 30 seconds for the complete scan.

Request version 1 has `version`, `thread`, absolute server-returned `path`, and
optional `continuation`. Each success returns `version`, `thread`, fixed
`boundary`, committed `offset`, `status` (`more` or `caughtUp`), `buckets`, and
`continuation` (null when caught up). Failure returns only
`{"version":1,"status":"unavailable"}`. Failure output includes no error details,
file contents, or paths. The subcommand emits no operational logs.

Buckets contain `provider`, `model`, recorded `tier`, `context` (`short` or
`long`), `requests`, and `tokens`. Token fields are `input_tokens`,
`cached_input_tokens`, `cache_write_input_tokens`, `output_tokens`, and
`total_tokens`. Cached reads and writes are subsets of input. Reasoning tokens
are already included in output. The context band is determined per request,
before aggregation, using the existing 272,000-input-token pricing threshold.

Continuation state is transient and private to this version: thread/file
identity, fixed boundary and timestamps, hashed header/checkpoint anchors,
byte offset, oversized-message skip state, current accounting context and
previous cumulative counters. It contains no transcript or partial line.
Android accepts advancing checkpoints with an unchanged boundary, merges each
batch once, and keeps at most 128 buckets. It does not persist continuations.
File replacement, truncation, and detected rewrites invalidate the scan.

## Bounded scanning and pricing

Each batch reads at most 8 MiB and checks a 250 ms work budget between chunks.
A record buffer is limited to 1 MiB. Identifiable oversized `response_item`
records are skipped in bounded chunks; oversized accounting or unclassifiable
records make the estimate unavailable. File reads require an owned regular
file and reject final-component symlinks. The initial boundary is fixed even
when the file grows. Only newline-committed records inside that boundary count.

Only `session_meta`, `turn_context`, `thread_settings_applied`, and `token_count`
contribute to accounting. Session identity, counters and delta continuity are
validated. The current model/tier never prices earlier requests. Forks or other
histories with unaccounted cumulative tokens are unavailable rather than partial.
Android prices using its existing host catalog and explicit aliases. Missing
attribution, unavailable prices or stale-only prices produce no dollar amount.
A verified session with no recorded requests can produce an estimated zero.

## Lifecycle and UI

A thread-page opening, return from settings, or reconnect that reloads the thread
starts one snapshot calculation. Successive batches belong to that one snapshot.
Token, completion and settings notifications do not start accounting commands.
Foregrounding the same page does not refresh cost. Catalog refresh may reprice
retained buckets without rereading history.

The UI shows `~$…` on success and hides cost while calculating or unavailable.
Its accessibility label still identifies the estimate as an opening snapshot.
The summary pill shows Git statistics only when changed files are present, uses
a divider only when both statistics and cost are visible, and disappears when
neither is visible. There is no warning icon, asterisk or cost-details dialog.
The pill keeps a 48 dp minimum height and the same surrounding margins when
only cost is visible, so removing Git statistics does not collapse its padding.
Navigation and disconnect cancel local work and invalidate
late responses; already dispatched read-only work ends at its stock timeout.
Cost failure neither disconnects chat nor starts a reconnect or automatic retry.

## Rollout and evidence limits

The helper-capable forwarder binary must be deployed before distributing this
Android feature. An older/missing helper shows unavailable cost; it never falls
back to a whole-rollout download. Forwarder deployment, Android release and
phone installation are separate explicit actions. No unit/config migration is
required because the existing deployment installs the same executable path.

The measured Blastoise rollout was 54,944,707 bytes (73,259,612 base64 bytes),
which exceeds Android's 32 MiB inbound cap. Ed's current rollout was 114,965,362
bytes; its original size is unknown. The exact rejected phone response was not
captured, so attributing that observed failure to this path remains a hypothesis.
Stock 0.159.2 metadata supplies no file size and stock readFile has no range/limit.
These constraints explain removing the unsafe read regardless of attribution.
