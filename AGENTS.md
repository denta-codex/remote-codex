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

For automated Android instrumentation, use `scripts/emulator-test --tests` with
the smallest relevant set. Use `--full` only for concrete cross-cutting or
test-infrastructure risk; release preparation alone is not a reason. Tests run
through AndroidX Test Orchestrator on a Gradle-managed device and have bounded
timeouts; never retry a failed or timed-out run automatically.

Use `scripts/emulator-start`, `scripts/emulator-record`, and
`scripts/emulator-stop` only for interactive inspection and fixture-backed
recording. Do not select an arbitrary device from `adb devices`; each interactive
worktree owns the emulator serial recorded by these helpers. Do not improvise
recording flows that can expose live account content. Emulator data is disposable,
so never place durable credentials or test state in it.
