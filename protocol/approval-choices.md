# Stock approval choices

Verified against the installed `codex-cli 0.159.2` schemas generated with
`codex app-server generate-json-schema --experimental --out <temporary-directory>`.
The older stock schema snapshots remain historical references.

- Command responses wrap the exact selected advertised string or object in
  `decision`. An explicit `availableDecisions` list is authoritative, including
  an empty list. Unknown variants are unavailable. Missing/null command lists
  conservatively retain only accept/decline; proposed amendments alone do not
  authorize additional choices.
- `acceptForSession` reuses the session approval cache. Command policy decisions
  contain `acceptWithExecpolicyAmendment.execpolicy_amendment`; network decisions
  contain `applyNetworkPolicyAmendment.network_policy_amendment` with `host` and
  `action` (`allow` or `deny`). They require explicit consequence confirmation.
- File-change responses permit `accept`, `acceptForSession` (same files),
  `decline`, and `cancel`. Their request schema has no advertised decisions list.
- `cancel` denies and interrupts the turn; `decline` lets the agent continue.
  `kind: writeStdin` reviews input to an existing terminal, using the same
  command decision response and existing request-ID/generation guard.
- Permission responses contain `permissions` and `scope` (`turn`, the default,
  or `session`). There is no request-side scope restriction field. Selected
  grants copy requested network permissions and filesystem entries/legacy
  read/write paths exactly. Filesystem deny entries and `globScanMaxDepth` stay
  with any filesystem subset. Unknown filesystem restrictions disable that
  filesystem selection. Empty denial is turn-scoped.
- `strictAutoReview` is optional and intentionally not authored here.

ClientModel validates approval responses against the pending request before
reserving a send through the existing stock RPC guard. No uncertain response is
retried. Approval context and reconnect behavior remain separate integration
work; positive command/file choices still require the current context gate.
