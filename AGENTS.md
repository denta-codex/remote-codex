# Remote Codex
Keep Codex stock; attach to the existing control socket. Android uses WSS only.
Never automatically retry an uncertain mutation. Server state is authoritative.
Do not log credentials, RPC payloads, or transcripts in operational logs.
Use the existing managed JDK/Android SDK. Run scripts/check before preparing release.
Live deployment and phone delivery are separate, explicit steps after local checks.

For Android worktree testing, use `scripts/emulator-start`,
`scripts/emulator-test`, and `scripts/emulator-stop`. Do not select an arbitrary
device from `adb devices`; each worktree owns the emulator serial recorded by
these helpers. Emulator data is disposable, so never place durable credentials
or test state in it.
