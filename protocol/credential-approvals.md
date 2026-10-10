# Credential approval transport v1

The authenticated WSS endpoint `/remote-codex/v1/credentials` routes only to the
on-demand op-bridge approval Unix socket. It is unrelated to stock Codex RPC.
There is no initialize handshake. Missing session returns HTTP 503; auth failure
returns 401. A GET of this route never starts a session. The forwarder strips
external headers and relays upgraded bytes; op-bridge owns all state.

## Messages and batch capability

One JSON text message requests an operation:

```json
{"version":1,"id":"client-sequence","method":"list","capabilities":["inject_batch_v1"]}
{"version":1,"id":"client-sequence","method":"get","request_id":"opaque-id"}
{"version":1,"id":"client-sequence","method":"release","request_id":"opaque-id","value":"fixture-only"}
{"version":1,"id":"client-sequence","method":"release_batch","request_id":"opaque-id","values":[{"id":"f1","value":"fixture-only"},{"id":"f2","value":""}]}
{"version":1,"id":"client-sequence","method":"deny","request_id":"opaque-id"}
```

Responses echo version/id and contain `requests` (absent means empty), a fixed
`error` code when needed, and `capabilities: ["inject_batch_v1"]`. An older backend
may omit capabilities and continues supporting single-field requests. Batch
requests are listed only when the caller advertises `inject_batch_v1`; their
list entries are summaries. This narrow capability gate keeps older Android
clients from presenting a batch as one field. It does not add a handshake or
change the caller's separate version-2 protocol.

Single-field request metadata retains `id`, `host`, `caller`, `account`, `vault`,
`item`, `field`, `deadline` (Unix milliseconds), and `state`. Batch metadata uses
`kind: "inject"`, the same identity/account/deadline/state fields, `unique_count`,
and `occurrence_count`. `get` and mutation replies also include ordered `fields`:

```json
{"id":"opaque-id","kind":"inject","host":"fixture","caller":"agent","account":"pinned-account","deadline":1900000000000,"state":"pending","unique_count":2,"occurrence_count":3,"fields":[{"id":"f1","vault":"Test","item":"Example","field":"password","occurrences":2},{"id":"f2","vault":"Test","item":"Example","field":"section/username","occurrences":1}]}
```

Field IDs are opaque and scoped to that request. No response contains selected
values or the template. Unknown request IDs return `unknown_request`. `list`
includes only live pending requests; `get` can inspect bounded terminal records.
Request IDs are random UUIDs and never reused. Methods never enter stock Codex.

## Supported phone templates

The bridge caller still sends version 2, action `read`, validated `inject` args
with the account pin, and existing stdin bytes. Templates enter only through
stdin; file flags, arbitrary native options, and account changes are rejected.
Desktop routes retain one native CLI invocation. Phone routes use manual values,
not verified native CLI lookups.

Phone templates are valid UTF-8 literal text plus `{{ op://Vault/Item/field }}` or
`{{ op://Vault/Item/section/field }}`. ASCII whitespace is allowed immediately
inside the braces. Components are nonempty, without leading/trailing whitespace;
internal spaces and Unicode names are supported. Components reject query or
fragment/attribute syntax (`?`, `&`, `#`), percent encoding (`%`), backslash, `$`,
braces, Unicode control characters, and Unicode format characters.

Reject invalid UTF-8, NUL, malformed/unmatched double braces, nested expressions,
empty or extra path components, and environment expressions (`$NAME`, `${NAME}`;
NAME starts with an ASCII letter or underscore). Ordinary dollar signs and
single braces remain literal. Unsupported syntax anywhere returns
`unsupported_template` before approval, with no stdout. Use an explicit desktop
route when native syntax beyond this subset is required; no automatic fallback.

The parser produces literal spans and reference slots. Identical paths share
one slot regardless of delimiter whitespace; spelling/case and name/ID aliases
are not normalized. Metadata preserves first-occurrence order. Android groups
fields by vault/item and requests one selection per slot. Rendering accepts only
a parsed template and a complete batch, substitutes literally, adds no newline,
performs no escaping, and never evaluates references inside selected values.
A supported template without references, including empty stdin, returns unchanged
without phone approval or starting an approval session.

## Release, limits, and receipt

`release_batch` must provide every field ID exactly once. Missing/null values,
unknown IDs, duplicate IDs, wrong types, and incomplete batches return
`invalid_batch`, retaining no values and leaving the request pending. Explicit
empty strings are supported; Android requires a visible **Use empty value**
action to distinguish them from unfilled fields. `release` cannot approve a batch
and `release_batch` cannot approve a single-field request (`invalid_request`).
Repeated JSON keys, case aliases of schema keys, unknown keys, invalid UTF-8 or
unpaired surrogate escapes, binary messages, ambiguous payloads, and oversized
frames close the approval connection without applying a mutation.

Each selected value is bounded to 64 KiB UTF-8. Existing single-field release
values remain nonempty. Approval messages are bounded to 512 KiB including JSON
escaping; responses are bounded to 1 MiB on Android. The version-2 encoded caller
request remains bounded to 64 KiB and each output stream to 16 MiB. One shared
caller timeout covers session startup, selection, rendering, and acknowledgment.
Polling does not extend it.

Under the request lock, the backend constructs a complete batch, accounts for
repeated substitutions, checks the deadline/cancellation, and accepts the release
at most once. It renders only after all values arrive, buffers the whole result,
and sends one caller response. No per-field staging or partial stdout is allowed.
A complete batch exceeding the output bound ends `failed` with `output_limit`.
Malformed/incomplete submissions do not consume the request.

States: pending → releasing → completed or delivery_uncertain; pending may also
end denied, expired, cancelled, or failed. Denial consumes the whole batch.
Concurrent release/deny commands have one winner; subsequent mutations return
`request_not_pending`. Before delivery begins, failure, expiry, cancellation,
or an output limit returns no stdout. After delivery begins, interruption can
leave delivery uncertain; transmitted bytes cannot be recalled.

The waiting op-bridge caller validates the complete response and acknowledges its
matching request ID on the existing Unix connection. Acknowledgments before
response transmission or with a wrong ID do not confirm receipt. `completed`
means the caller received the response, not that a downstream program consumed
stdout. Missing receipt, session loss, or transmission failure after delivery
starts becomes `delivery_uncertain`. Failure to send acknowledgment clears the
caller's local output and returns uncertainty. No secret result is retrievable
from a later `get`.

## Android lifecycle and privacy

The phone never automatically retries a mutation. It prepares/bounds the whole
payload, marks the request submitted, clears every field, and sends once.
Reconnect/Refresh performs list/get only. A rejected submission also remains
locally blocked; cancel the caller and start a fresh request if needed. If the
session disappears, a submitted operation is unknown, not proven failed.

Secrets exist only in transient field/transport/rendering buffers, never in
persistent storage. The activity retains FLAG_SECURE, disables view-state saving,
and preserves only opaque submitted-request IDs across recreation. Unsubmitted
values and explicit-empty selections are lost on recreation. Live fields may
survive a temporary password-manager handoff; observed disconnection, metadata
changes, expiry, terminal state, request changes, close, or destruction clears
them. Cancel Autofill on clearing. Immutable runtime strings cannot guarantee
physical memory erasure; avoid extra copies and drop references promptly.

Templates, references, selected values, and rendered output never enter bridge
logs or inject access history. History contains only operation, opaque request
ID, and fixed outcome/reason. Terminal records hold metadata only. Operational
errors contain fixed codes, never payloads. The existing same-UID/host-admin trust
boundary remains; this is not end-to-end encryption against the execution host.

## Foreground credential alerts

Op-bridge can publish a metadata invalidation to the forwarder's private Unix
socket, configured with `desktops.phone.event_socket`. On Grace this is
`/run/user/UID/remote-codex/events.sock`. Each publication uses a fresh local
connection; no request, callback endpoint, reference, or selected value is sent:

```json
{"version":1,"event":"credential_requests_changed"}
```

The NDJSON exchange is bounded to 1 KiB and one second. The response is
`{"accepted":true}` followed by newline. Acceptance means the forwarder received
the hint, not that Android displayed an alert. Both ends verify the peer UID;
the directory is owner-only and the socket is mode 0600. The listener refuses
unsafe paths and recovers a verified stale socket. The publisher does not retry.
The optional setting leaves op-bridge usable independently of Remote Codex.

Rust coalesces hints and sends this server-generated notification to each active
`/codex/rpc` connection using its existing WebSocket:

```json
{"method":"remoteCodex/credentialRequestsChanged","params":{}}
```

Stock JSON messages keep their payloads and Android uses its normal dispatcher.
The forwarder reserves `remoteCodex/` for server events and rejects client
attempts to send this namespace to stock Codex. Writes are serialized with a
bounded deadline; text/binary messages are limited to 100 MiB. WebSocket frames
and masks are regenerated for each hop. Credential approval traffic retains its
separate byte tunnel.

Android performs read-only list discovery on this hint and foreground/reconnect,
then shows a grouped snackbar with **Review**. A single request opens directly;
multiple requests open the inbox. Expiry triggers one read at the deadline.
Repeated hints coalesce; dismissed request IDs remain silent for their lifetime.
No periodic polling, background push, durable queue, or automatic mutation replay
is added. Hints are best effort: foreground reconnect or manual refresh recovers
missed discovery. Secrets, release/deny commands, caller delivery and receipts
remain exclusively on the existing credential path.
