#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C GIT_OPTIONAL_LOCKS=0
cd -- "$1"
root=$(git rev-parse --show-toplevel 2>/dev/null) || { printf '{"status":"notRepository"}\n'; exit 0; }
cd -- "$root"
baseline=HEAD
base=$(git rev-parse --verify HEAD 2>/dev/null) || base=$(git hash-object -t tree /dev/null)
if main=$(git rev-parse --verify refs/heads/main 2>/dev/null) && ancestor=$(git merge-base HEAD "$main" 2>/dev/null); then
    base=$ancestor
    baseline=main
fi
# Aggregate on the host: only counts leave the workspace. No index or worktree writes.
{
    git diff --no-ext-diff --no-textconv --no-renames --numstat "$base" --
    git ls-files --others --exclude-standard -z | while IFS= read -r -d '' path; do
        status=0
        git diff --no-ext-diff --no-textconv --no-renames --numstat --no-index -- /dev/null "$path" || status=$?
        (( status <= 1 )) || exit "$status"
    done
} | awk -v baseline="$baseline" '
    { files++; if ($1 == "-") binary++; else { added += $1; removed += $2 } }
    END { printf "{\"status\":\"ready\",\"baseline\":\"%s\",\"files\":%d,\"added\":%d,\"removed\":%d,\"binary\":%d}\n", baseline, files, added, removed, binary }
'
