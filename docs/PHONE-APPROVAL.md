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

The old Autofill playground remains available for item-type experiments. It does
not submit values. The real approval screen clears inputs on selection changes,
exit, recreation, and submission; temporary picker handoff preserves the field.
Submitted operations never replay on reconnect. Read-only status checks can
report completion, cancellation, or uncertainty while the original session lives.
See `protocol/credential-approvals.md` for the versioned transport contract.
