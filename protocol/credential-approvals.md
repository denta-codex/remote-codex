# Credential approval transport v1

The authenticated WSS endpoint `/remote-codex/v1/credentials` routes only to the
on-demand op-bridge approval Unix socket. It is unrelated to stock Codex RPC.
There is no initialize handshake. Missing session returns HTTP 503; auth failure
returns 401. A GET of this route never starts a session. The forwarder strips
external headers and relays upgraded bytes; op-bridge owns all state.

One JSON text message requests an operation:

```json
{"version":1,"id":"client-sequence","method":"list"}
{"version":1,"id":"client-sequence","method":"get","request_id":"opaque-id"}
{"version":1,"id":"client-sequence","method":"release","request_id":"opaque-id","value":"fixture-only"}
{"version":1,"id":"client-sequence","method":"deny","request_id":"opaque-id"}
```

Responses echo version/id and contain `requests` (absent means empty) and/or a
fixed `error` code. Request metadata contains `id`, `host`, `caller`, `account`,
`vault`, `item`, `field`, `deadline` (Unix milliseconds), and `state`. No response
contains values. Unknown IDs return `unknown_request`. `list` includes only live
pending requests; `get` can inspect bounded terminal records. Request IDs are
random UUIDs and never reused. Methods are not forwarded into Codex.

States: pending → releasing → completed or delivery_uncertain; pending may also
end denied, expired, or cancelled. Caller receipt is acknowledged on its existing
Unix connection. Concurrent releases atomically consume pending state; further
release/deny commands return request_not_pending. Error responses use fixed codes
without request values. Release values must be nonempty UTF-8, at most 64 KiB;
wire requests are bounded to 512 KiB including JSON escaping. Secrets exist only
in transient field/transport buffers, never in persistent storage.

The phone never automatically retries a mutation. Reconnect/Refresh performs
list/get only. If the session disappears, a submitted operation is unknown,
not proven failed. Opening the screen and polling do not extend session lifetime.
The current single-user host trust boundary includes same-UID processes and the
host administrator; this is not end-to-end encryption against the execution host.
