# Phone approval integration

An op-bridge `remote-codex` destination runs a transient phone session on the
execution host, started by the first caller and stopped after inactivity. The
existing Rust forwarder authenticates and relays a dedicated WSS route to that
session's approval socket. It does not own pending requests or launch sessions.
The app connects only while Settings → Credential requests is open. There are no
notifications and no enabled phone-session daemon.

The phone destination initially supports single-field `read` and `item get`.
Desktop destinations continue to provide vault/item listing, complete-item JSON,
write operations, and generated OTP. Unsupported phone commands fail immediately
as `unsupported_operation`. The requested account/item is displayed for the user
to match; Autofill cannot independently prove item identity.

## Deploy and validate

1. Install the matching op-bridge release on Grace using its existing Ansible
   workflow. Add the `phone` destination owned by `agent`, preserving the current
   default destination while validating.
2. Build the signed Android/forwarder artifacts through `scripts/deploy` with
   `remote_codex_action=build` and an explicit prerelease version. This runs the
   required checks once.
3. Deploy the prepared forwarder with `remote_codex_action=deploy`. Its service
   template selects `/run/user/UID/op-bridge-phone/approval.sock` for approvals.
4. Publish the prepared APK with `remote_codex_action=publish`. Installation stays
   user initiated. No phone remote control is required.
5. Request a harmless value using
   `op-bridge --desktop phone read 'op://Test/Fixture/password'`, then manually
   open Settings → Credential requests and release the matching fixture value.
6. Change the configured default to `phone` only after the manual phone test.
   Caller commands then stay unchanged. Restore the old desktop default for
   recovery; there is never automatic fallback.

The approval screen clears inputs on selection changes,
exit, recreation, and submission; temporary picker handoff preserves the field.
Submitted operations never replay on reconnect. Read-only status checks can
report completion, cancellation, or uncertainty while the original session lives.
See `protocol/credential-approvals.md` for the versioned transport contract.

## Published preview: 0.2.11-phone.1 (22)

The preview APK was published through the existing private update channel and
verified by the publication workflow. Grace has op-bridge `phone.1` installed
from integration commit `e8a80a2`, plus the updated Rust forwarder. The default
op-bridge route remains `mac`; use `--desktop phone` until manual phone validation.

Validation completed:

- Go tests, race detector, vet, package checks, and a macOS arm64 cross-build.
- Focused Android approval tests, including lost acknowledgment/recreation,
  no automatic replay, picker handoff, field clearing, and capture protection.
- Required Remote Codex build checks, Rust route tests, core transport tests,
  Android unit tests, lint, APK identity, and signing verification.
- Post-install fake-value caller → fixture Rust forwarder → on-demand session
  check: absent session returned 503, read started the session, output/newline
  matched, caller receipt was acknowledged, duplicate release was rejected.
- The live temporary session subsequently expired naturally: systemd reported
  inactive and `op-bridge --desktop phone session status` reported stopped.
- Forwarder deployment verified authenticated stock Codex initialization and
  project/task listing. APK publication verified the manifest and full download.

The first op-bridge deployment stopped before activation because a noninteractive
shell lacked user-bus environment variables. It made no installation changes;
explicit bus addressing was added, revalidated, and the corrected deployment
succeeded. Its staging and recovery data were cleaned by the deployment workflow.
Actual 1Password selection and approval on the phone remains a manual user test.
