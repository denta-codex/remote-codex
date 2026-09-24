# Remote Codex
Keep Codex stock; attach to the existing control socket. Android uses WSS only.
Never automatically retry an uncertain mutation. Server state is authoritative.
Do not log credentials, RPC payloads, or transcripts in operational logs.
Use the existing managed JDK/Android SDK. Run scripts/check before preparing release.
Live deployment and phone delivery are separate, explicit steps after local checks.

The ignored ChatGPT Android reference is in `artifacts/chatgpt-android`: use
`simple/sources` for decompiled code, `com.openai.chatgpt.apk` for the base APK,
and `METADATA.txt` for its version and hashes. Run
`scripts/refresh-chatgpt-android-reference` to replace it with the latest public
APK and JADX output. Related research is in
`/home/agent/workspaces/android-app-research/CHATGPT-ANDROID-INTERNALS.md` and
`/home/agent/workspaces/codex-ops/android/PROJECTLESS-RESEARCH.md`.

For Android worktree testing, use `scripts/emulator-start`,
`scripts/emulator-test`, `scripts/emulator-record`, and `scripts/emulator-stop`.
Do not select an arbitrary device from `adb devices`; each worktree owns the
emulator serial recorded by these helpers. Record fixture-backed instrumentation
tests by default; do not improvise recording flows that can expose live account
content. Emulator data is disposable, so never place durable credentials or test
state in it.
