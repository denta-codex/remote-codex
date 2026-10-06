# Todo service and local CLI

Todo is application-owned planning data. Stock Codex remains responsible for chats,
execution, approvals, and history. Promotion of ideas into Codex tasks is outside
this change. The board retains To Do, In Progress, Done, descriptions, notes, and
phone-local ordering.

## Ownership and interface

The persistent Rust forwarder handles `/remote-codex/v1/todo` itself. Tailscale
terminates WSS on the same host as `/codex/rpc`. Both use the existing bearer
credential; browser-origin connections are rejected. Stock forwarding, credential
approvals, and update downloads retain their existing routes.

Android opens a separate Todo connection without stock initialization. There is
no fallback to `command/exec` and no temporary process or server per request.

Requests use JSON-RPC 2.0 with a string or integer `id` and object `params`:

| Method | Parameters | Result |
| --- | --- | --- |
| `todo/list` | `{}` | `{tasks: [summary, ...]}` for active records |
| `todo/show` | `id` | `{task: detail}` |
| `todo/create` | `title`, `description`, `status` | `{task: detail}`; revision 1 |
| `todo/edit` | `id`, `revision`, `title`, `description` | `{task: detail}` |
| `todo/move` | `id`, `revision`, `status` | `{task: detail}` |

The request envelope's ID is distinct from the database-local task ID inside
params. IDs correlate replies only. Notifications cannot execute writes. Status
values are exactly `To Do`, `In Progress`, and `Done`. Summaries include ID, title,
status, archived, revision, created_at, and updated_at. Detail adds description
and ordered notes with stable IDs, text, and timestamps. Edits replace both supplied
text fields. Reads of archived details remain available to the CLI; Android's
adapter rejects archived records.

Protocol errors use standard JSON-RPC codes. Domain errors use `-32001` and
`error.data.code`: `invalid_input`, `not_found`, `revision_conflict`, `database_error`,
`io_error`, or `busy`. Error messages are generic and never include task content.
`busy` proves dispatch did not occur. `io_error`, internal errors, malformed
responses, timeouts, and lost connections leave mutation outcomes uncertain.
A confirmed SQLite transaction may outlive its connection. Never resubmit an
uncertain request automatically, including after a restart. Android preserves its
existing pending-intent keys and explicit inspect/acknowledge recovery.

Frames and complete messages are bounded to 1 MiB. Oversized results close the
connection rather than misrepresent a potentially committed mutation as rejected.
At most two blocking database workers execute concurrently. Saturation rejects
new work before dispatch; SQLite waits up to five seconds for locks. Neither
mechanism replays a write.

## Storage and CLI

Canonical host database: `/home/agent/.local/share/remote-codex/todo.sqlite3`.
The service accepts `REMOTE_CODEX_TODO_DB` as an absolute-path override. SQLite
schema version 1 is preserved, including IDs, revisions, timestamps, archived
records, descriptions, notes, and allocation sequences.

The service and the `todo` binary built in `forwarder/` use the same storage
library. No external checkout or installed CLI is used for Android requests.
The CLI preserves `add`, `list`, `show`, `edit`, `move`, `note`, `archive`, `restore`,
JSON schema 1, exit codes, file/stdin input, search, filters, and revision checks.
Its default follows `$XDG_DATA_HOME/remote-codex/todo.sqlite3`, or
`$HOME/.local/share/remote-codex/todo.sqlite3`. `--db` overrides `TODO_DB`, which
overrides the default. When overriding the service path, explicitly point the
CLI at that same database. Test only with disposable paths.

The old `/home/agent/.local/share/todo/tasks.sqlite3` path is rejected by normal
CLI/storage operations, including relative or symlink aliases resolving to it.
The migration command is the explicit exception. If old data exists and the
canonical destination is absent, normal operations refuse to create an empty
replacement database.

The service remains filesystem-read-only except its application data directory,
where SQLite must create/remove journals. Sibling signing assets, build records,
updates, and the encrypted connection credential are mounted read-only. New
Todo databases use 0600; new directories use 0700.

## Explicit cutover

Implementation and fixture validation do not migrate the live database, deploy
the service, publish an update, or install on the phone. Run the following sequence
when performing the live cutover:

1. Account for source changes and use the normal clean-checkout build workflow:
   `scripts/deploy -e remote_codex_action=build`. This prepares the service and
   CLI in `dist/`, signs Android, and runs required checks once. It does not deploy
   or install anything.
2. Stop local Todo writers and Android Todo use. Migration acquires a SQLite
   write lock, but an operator must also prevent future writers during cutover.
3. Run `scripts/deploy -e remote_codex_action=migrate_todo -e remote_codex_todo_writers_quiesced=true`.
   This stops the forwarder and invokes `dist/todo migrate --from /home/agent/.local/share/todo/tasks.sqlite3 --to /home/agent/.local/share/remote-codex/todo.sqlite3 --writers-quiesced --json` once.
4. Migration uses SQLite backup, verifies schema/integrity/foreign keys and exact
   table contents, then publishes the destination without overwriting anything.
   Both preexisting destination and interrupted `.sqlite3.migrating` staging cause
   refusal. The source remains only for cutover recovery.
5. Keep writers stopped and run `scripts/deploy -e remote_codex_action=deploy`.
   This installs the new CLI and service, verifies stock and Todo WSS connections,
   and starts the service. Old Android clients now receive a clear retired-path
   error rather than creating a second database. Resume local CLI use afterward.
6. Update Android through a separately authorized publication/installation step.
   Verify existing cards, descriptions, notes, an intentional edit, CLI visibility,
   board ordering, and any existing pending-save review. Once acceptance succeeds,
   remove the retained old database and the reported old-CLI recovery binary.

No migration, deployment, or write is automatically replayed after failure.
Inspect the reported paths and current state before choosing recovery.

## Recovery

Before any writes reach the new database, recovery may discard the verified
new copy and restore the old CLI/service using deployment recovery files or
previously built artifacts. Keep writers stopped throughout. Retained source:
`/home/agent/.local/share/todo/tasks.sqlite3`; remove it only after acceptance.
The old CLI backup is reported by deployment and is retained until Android
acceptance; remove that exact file when the cutover is complete.

After new writes, never restore the stale source. Stop writers and the service,
remove the now-obsolete retained source, and run `todo migrate` with the current
canonical database as `--from` and the original path as `--to`, plus
`--writers-quiesced`. This copies current records back and retains the canonical
copy for reverse-cutover recovery. Restore the previous CLI/service only after
verification, then remove the reverse-cutover recovery copy after acceptance.

On an interrupted transfer, the source remains authoritative until the destination
has passed verification. Inspect `.sqlite3.migrating` staging and any published
destination before removing staging or attempting another transfer. If cleanup
fails, preserve only that operation's staging and report the path. No extra home,
repository, or configuration archives are created.
