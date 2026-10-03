# Autofill experiment

Open Settings → Autofill test · experimental. Use harmless test credentials.
This release keeps the normal Remote Codex package and signing identity so app
associations are tested against the intended app. It uses the existing private
update channel; the prerelease name does not isolate distribution.

Try each form (Secret only, Login, Control) with a normal Login, a token in a
Login password, an API Credential item, and a custom field. Focus a field and
choose Request Autofill. Select the test category and observation afterward.
Repeat after choosing Always Allow in 1Password, and after closing/reopening the
activity. Support for particular item types is an experimental result, not an
assumption. The Control form omits explicit hints, but Android may add a heuristic
`passwordAuto` hint and a provider may infer its purpose. Diagnostics report the
presence of known username/password/automatic-password hints as booleans.

Copy diagnostic report exports only version information, form/category,
predefined observations, framework events, and empty/nonempty state. Suggestion
callbacks do not reveal which items the provider displayed. Delivery is recorded
when Android calls the field's autofill method, separately from ordinary edits.
Events are bounded to the last 100 observations and reset with the form.

The screen performs no network requests, initializes no chat model, and stores no
values outside its fields. Screenshots and app bug-report capture are blocked.
Reset, form changes, closing, and recreation clear inputs; temporarily leaving
for the password-manager picker does not. Autofill sessions are canceled without
an explicit save/commit action on reset and exit. No phone automation is needed.

## Release and exit

The initial target is `0.2.11-autofill.1`. Further experiments require explicit
versions, e.g. `0.2.11-autofill.2`. If a later normal version has already been
allocated, start at its next patch with `-autofill.1`.

The prerelease parser and its tests must also be on main before publication.
The playground remains on `codex/autofill-spike`. A subsequent normal release
from main promotes the highest prerelease to its plain version and allocates a
higher Android build number, removing the playground without uninstalling or
losing app data. Never revert the parser while prerelease manifests remain.

Focused instrumentation uses `scripts/emulator-test --tests` with the three
`AutofillTest` methods. It uses fake values and simulates framework delivery and
picker lifecycle transitions. Actual 1Password compatibility requires the manual
phone experiment described above.

## Validation — October 2, 2026

All 16 release-workflow tests passed, including prerelease publication and normal
promotion. The three targeted Android instrumentation scenarios passed: field
hints/delivery/report sanitization, picker lifecycle/recreation/exit, and private
activity/capture/state-restoration protections. Initial form-test failures exposed
Android's extra heuristic hint and initialization text-change events; the
assertion and event filtering were corrected and the affected test then passed.
Real 1Password item compatibility remains for the manual phone experiment.
