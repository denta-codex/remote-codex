# Remote Codex
Keep Codex stock; attach to the existing control socket. Android uses WSS only.
Never automatically retry an uncertain mutation. Server state is authoritative.
Do not log credentials, RPC payloads, or transcripts in operational logs.
Use the existing managed JDK/Android SDK.

## Routine builds and releases

- Build/sign: `scripts/deploy -e remote_codex_action=build`.
- Publish an in-app update: `scripts/deploy -e remote_codex_action=release`.
- Ansible chooses the next patch/build number, prepares notes, invokes
  `scripts/release` once (which runs `scripts/check`), verifies artifacts, and
  commits the completed build/release record locally. Do not run checks separately.
- Override the version with `-e remote_codex_version=X.Y.Z` or notes with
  `-e remote_codex_notes_file=/absolute/path/to/notes.md` when requested.
- Account for source changes before invoking the workflow: build/release requires
  a clean checkout. It uses the current branch, or creates a release branch when
  detached. It never pushes.
- Use the command's stages and final result. Wait in 30–60-second intervals;
  do not repeatedly read unchanged logs, re-explore the repository, research the
  release process, or write bespoke verification commands for a routine build.
- Investigate only failures. Detailed logs and failed build artifacts are retained
  under `artifacts/releases/` and `dist/`. Inspect the reported publication state
  before an explicit recovery; never automatically replay an uncertain mutation.
- Build/release does not deploy the forwarder or install on the phone. Those
  remain separate explicit actions. Do not rerun emulator tests merely to release.

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
timeouts.

Run relevant emulator validation as part of implementation without asking.
After a failure or timeout, inspect the results, address the cause, and rerun
the affected tests. Avoid repeatedly rerunning an unchanged failure. If the
cause cannot be resolved, report the remaining validation limitation. This
permission covers fixture-backed tests on disposable emulators; it does not
authorize replaying uncertain live mutations, publishing releases, or installing
on the user's phone.

Use `scripts/emulator-start`, `scripts/emulator-record`, and
`scripts/emulator-stop` only for interactive inspection and fixture-backed
recording. Do not select an arbitrary device from `adb devices`; each interactive
worktree owns the emulator serial recorded by these helpers. Do not improvise
recording flows that can expose live account content. Emulator data is disposable,
so never place durable credentials or test state in it.
