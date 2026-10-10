# Hello World iOS testing fixture

This stage establishes reliable iOS testing. `ios/` is a standalone SwiftUI
Hello World app with no Remote Codex client, server connection, credential entry,
camera access, or Keychain use. The previous client scaffold stays on
`codex/ios-github-builds`; it is not included in this testing branch.

The fixture shows **Hello, world!**, a **Tap me** counter, an editable synthetic
note with **Save note**, and the installed version/build. Both saved fields use
an atomic JSON file at `Application Support/HelloWorldFixture/state.json`.
Ordinary launches preserve the file for restart and in-place upgrade checks.
Storage errors are visible and disable saving instead of replacing unreadable data.

## Simulator testing on GitHub Macs

The existing iOS harness is reused: standard `macos-15` Apple Silicon runner,
Xcode 16.4 (16F6), iOS 18.6, and a disposable iPhone 16 simulator. The workflow
rejects an unavailable pin. No personal Mac or signing credentials are needed.

Push this branch to run `.github/workflows/ios-check.yml`. On Grace:

```sh
scripts/ios check
scripts/ios watch RUN_ID
```

`scripts/ios-ci check` runs the artifact/dispatch/report/profile helper checks,
six Foundation persistence tests, the unsigned app build, and three XCTest UI
tests. The UI tests verify:

1. Launch, greeting, zero initial counter, and visible version/build.
2. Two taps, text entry, explicit save, and displayed saved text.
3. One tap and a saved note surviving termination and relaunch.

Each independent UI test starts with
`--fixture --ui-testing --reset-fixture`. Reset removes only the fixture state
file. All three flags are required. The persistence test relaunches with
`--fixture` alone, retaining its data. Physical acceptance must use `--fixture`
alone; never reset or uninstall between restart/upgrade checks.

Tests run serially with 60-second normal and 90-second maximum per-test allowances;
the GitHub job has a 25-minute limit. The owned simulator is deleted on exit.
Each run records its source SHA, attempt, runner image, test counts, stage timings,
and three selected synthetic screenshots: `fixture-launch.png`,
`fixture-interaction.png`, and `fixture-persistence.png`.

The compact `ios-check-RUN_ID-RUN_ATTEMPT` artifact lasts one day. A successful
report requires every stage to finish, nonempty passing core tests, all three UI
tests passing with no skips, and all three screenshots. Missing evidence fails
the job rather than producing a false success. Inspect the screenshots before
accepting the exact revision. One passing run proves that run; repeatability and
physical automation remain separate acceptance checks.

## Signed physical testing: next gate

The existing manual development workflow and artifact contract are retained for
the Hello World app. The product/scheme remain `RemoteCodex` and the bundle ID
remains `dev.codexops.client.ios`; its home-screen display name is **Hello World**.
This keeps the agreed signing/device-tooling interface. Do not install it over
a client containing data you need: this is a test vehicle using that identifier.

Before signing, review and register `.github/workflows/ios-development.yml` on
the default branch and prove unsigned success on the exact intended source SHA.
Actual membership, signing inputs, and selected-device readiness must be validated.
The `ios-development` environment requires three secrets:
`IOS_DEVELOPMENT_CERTIFICATE_P12_BASE64`,
`IOS_DEVELOPMENT_CERTIFICATE_PASSWORD`, `IOS_DEVELOPMENT_PROFILE_BASE64`, and
variable `IOS_DEVELOPMENT_TEAM_ID`. No secrets are used by unsigned checks.

The profile must cover `dev.codexops.client.ios` and the explicitly selected phone.
Signing, USB device tooling, and physical acceptance remain separate workstreams.
The existing `scripts/ios-device` helper is on `codex/ios-device-tooling`.

From a clean checkout, request one explicitly reviewed development build:

```sh
scripts/ios build codex/ios-hello-world EXPECTED_FULL_SHA 0.1.0
scripts/ios watch RUN_ID
scripts/ios download RUN_ID EXPECTED_FULL_SHA
```

No signed build is dispatched as part of preparing this fixture. A lost dispatch
response requires authoritative run inspection before any new attempt.
The exact-run artifact `ios-development-RUN_ID-RUN_ATTEMPT` contains only
`RemoteCodex.ipa`, `metadata.json`, and `SHA256SUMS`. Manifest keys remain exactly
`schema_version`, `repository`, `revision`, `run_id`, `run_attempt`, `artifact`,
`sha256`, `bundle_id`, `version`, `build_number`, `signing`, `minimum_ios`, and
`fixture_launch_argument`. The launch argument is `--fixture`; build numbers use
the GitHub run number/attempt, so a later build can test in-place upgrade retention.

Physical acceptance is: verify and install one exact artifact on the selected
phone, launch with `--fixture`, tap, save a synthetic note, terminate/relaunch,
then install a verified higher build without uninstalling and confirm both
fields persist. Record installed build, artifact SHA, timings, and screenshots.
Simulator results do not prove these physical steps.

## Stable physical automation selectors

| Identifier | Element and expected behavior |
| --- | --- |
| `hello-world` | Static greeting: `Hello, world!` |
| `fixture-status` | Static label: `Offline testing fixture` |
| `tap-count` | Static label: `Tap count: N` |
| `increment-button` | Button: `Tap me`; increments and saves the counter |
| `note-input` | Multiline text input for synthetic text |
| `save-note` | Button: `Save note`; saves text and dismisses the keyboard |
| `saved-note` | Static label: `Saved note: TEXT` |
| `build-info` | Static installed version and build |
| `storage-error` | Present only when persistence failed |

These selectors are the minimal target for proving the proposed Appium/WDA
physical testing path. That path is not yet validated on the actual phone.
Remote Codex feature development and TestFlight delivery remain deferred.
