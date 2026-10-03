# Release operation and recovery

The supported entry point is `scripts/deploy`. Ansible owns version preparation,
building, publication, verification, and the local release commit. There is no
separate release coordinator script or skill.

## Commands

```sh
scripts/deploy -e remote_codex_action=build
scripts/deploy -e remote_codex_action=release
scripts/deploy -e remote_codex_action=release -e remote_codex_version=0.2.4
scripts/deploy -e remote_codex_action=release -e remote_codex_notes_file=/absolute/path/notes.md
```

Build signs artifacts locally. Release additionally publishes and verifies the
in-app update. Both require a clean checkout and commit only the version, notes,
and validation record; neither pushes. A detached checkout is attached to
`codex/release-VERSION` before committing. No forwarder deployment, phone
installation, or emulator execution is part of these actions.

Versions accept `MAJOR.MINOR.PATCH` and SemVer prereleases such as
`0.2.11-autofill.1`; build metadata and leading-zero numeric identifiers are not
accepted. Experimental releases require an explicit `remote_codex_version`.
Prereleases sort below the corresponding normal release, with numeric suffixes
ordered numerically. If the greatest known version is a prerelease, automatic
selection promotes it to its normal version; otherwise it increments the patch.
The existing private stable channel also distributes explicitly requested test
versions: a prerelease label does not create a separate distribution channel.
All publishing branches must have this parser before publishing a prerelease.
Return from an experiment using a newer build without the experimental changes,
not by reinstalling an older APK.

The build number is greater than the repository number,
prepared/published manifest numbers, and numeric release directories in this
checkout and the private update store. An explicit version must be newer than all
existing manifest and source versions. The host-wide launcher lock serializes all
deployment invocations across worktrees. A shared reservation in
`/home/agent/.local/share/remote-codex/builds/latest.json` also prevents separate
worktrees from reusing versions allocated by unpublished or failed builds. Failed
reservations remain allocated; version gaps are intentional.

Default notes are commit subjects since the last Android version change; a rebuild
with no intervening commits identifies its source revision. Supply a notes file to
curate user-facing wording. Relative notes paths use Ansible lookup resolution;
prefer absolute paths.

`scripts/release` is an internal build primitive. It runs `scripts/check` once,
then signs and verifies the release and creates checksums. The checks resolve the
managed Codex executable rather than an isolated-home-incompatible Mise shim.
Existing managed toolchains and the existing signing identity are required.

## Output

The command prints its log location, stage changes, and one JSON result.
The full Ansible task results are saved in a private
`artifacts/releases/run-*.log`; builder output is in the adjacent `.log.build.log`.
Ansible `no_log` applies to credential handling and authenticated requests.
Temporary verification downloads are removed even when verification fails.

Build/release intentionally reject `--check`: their later stages depend on newly
built artifacts. Preview the existing publication tasks against a prepared build:

```sh
scripts/deploy -e remote_codex_action=publish --check --diff
```

The existing `preview`, `deploy`, `publish`, and `deliver` actions retain their
separate purposes. Default action is preview. The publish action now includes
HTTPS download verification.

## Failure handling

No mutation is automatically retried or rolled back. The failed result reports
the stage, whether stable publication was attempted/completed, whether verification
completed, and remaining checkout changes. Preserve the logs and artifacts until
the failure is understood.

- Before publication: fix the reported cause and account for version/notes changes
  before starting another build. A new build allocates a higher version.
- If `publication_attempted=true` but `published=false`: the stable-manifest write
  may be uncertain. Inspect the current stable manifest and immutable APK before
  deciding whether publication needs to be performed.
- If `published=true` but `verified=false`: the update may already be visible.
  Diagnose the failed endpoint or artifact verification first. Do not rebuild or
  advance stable automatically.
- If publication and verification succeeded but recording failed: repair only the
  local Git record using the preserved version, notes, validation entry and logs.
  Publishing again is unnecessary.
- If interrupted before a final result: use the last logged stage and inspect
  authoritative state. The operating system releases the invocation lock when
  the process and its children terminate.

Failed artifacts remain under `dist/`; logs remain under `artifacts/releases/`.
They may be removed once recovery is complete and the recorded artifact identity
is no longer needed. Successful logs are local diagnostic evidence, not additional
repository or credential backups.

## Testing the workflow

```sh
scripts/deploy --syntax-check
uv run --no-project tests/release_workflow_test.py
```

The tests run the real Ansible task groups against temporary Git repositories,
a fake build primitive, and a local HTTPS update fixture. They exercise version
selection, one-time checks, Git recording, lock contention, immutable publication,
authentication, complete downloads, and failures after publication. They do not
publish to Grace or modify its service or phone. Run these tests when changing the
workflow, not as an extra step on every routine release.
