#!/usr/bin/env bash
# Shipped with the Android client and executed via stock command/exec. No daemon.
set -Eeuo pipefail
export LC_ALL=C GIT_TERMINAL_PROMPT=0 GIT_MERGE_AUTOEDIT=no GIT_EDITOR=true
export GIT_OPTIONAL_LOCKS=0
umask 077
action=${1:?}; cwd=${2:?}; operation=${3:-}; approved=${4:-'{}'}
receipt=''; temporary=''; analysis=''; result=''; source=''; destination=''; common=''
status=blocked; reason=''; conflicts='[]'; commits='[]'; files='[]'; count=0; file_count=0
source_head=''; target_head=''; source_ref=''; merge_tree=''

reply() {
    jq -cn --arg status "$status" --arg reason "$reason" --arg source "$source" \
        --arg destination "$destination" --arg common "$common" --arg sourceHead "$source_head" \
        --arg targetHead "$target_head" --arg sourceRef "$source_ref" --arg result "$result" \
        --arg operation "$operation" --arg receipt "$receipt" --arg temporary "$temporary" \
        --argjson conflicts "$conflicts" --argjson commits "$commits" --argjson files "$files" \
        --argjson count "$count" --argjson fileCount "$file_count" \
        '{status:$status,reason:$reason,source:$source,destination:$destination,common:$common,
          sourceHead:$sourceHead,targetHead:$targetHead,sourceRef:$sourceRef,result:$result,
          operation:$operation,receipt:$receipt,temporary:$temporary,conflicts:$conflicts,
          commits:$commits,files:$files,count:$count,fileCount:$fileCount}'
}
finish() { status=$1; reason=$2; reply; exit 0; }
cleanup_analysis() { if [[ -n "$analysis" ]]; then rm -f -- "$analysis"; fi; }
trap cleanup_analysis EXIT
trap 'trap - ERR; status=needsReview; reason="Git operation did not finish normally. Check its state before continuing."; reply; exit 0' ERR
command -v jq >/dev/null && command -v flock >/dev/null && command -v git >/dev/null
[[ "$action" == inspect || "$action" == merge || "$action" == reconcile ]] || finish blocked 'Unknown merge action.'
unavailable_status=blocked
if [[ "$action" == reconcile ]]; then unavailable_status=needsReview; fi
[[ "$cwd" == /* ]] || finish "$unavailable_status" 'The task directory is unavailable.'
source=$(git -C "$cwd" rev-parse --show-toplevel 2>/dev/null) || finish "$unavailable_status" 'This task is not in a Git repository. Inspect the recorded checkout on the host.'
source=$(cd -- "$source" && pwd -P)
common=$(git -C "$source" rev-parse --path-format=absolute --git-common-dir)
common=$(cd -- "$common" && pwd -P)

clean() {
    local changes
    changes=$(git -C "$1" status --porcelain=v1 --untracked-files=all 2>/dev/null) || return 1
    [[ -z "$changes" ]]
}
idle_git() {
    local marker path
    for marker in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply sequencer BISECT_LOG index.lock HEAD.lock refs/heads/main.lock; do
        path=$(git -C "$1" rev-parse --git-path "$marker") || return 1
        [[ ! -e "$path" ]] || return 1
    done
}
target_is_expected() {
    [[ "$(git -C "$destination" symbolic-ref -q HEAD)" == refs/heads/main ]] &&
    [[ "$(git -C "$destination" rev-parse HEAD)" == "$target_head" ]] &&
    [[ "$(git -C "$source" rev-parse refs/heads/main)" == "$target_head" ]]
}
remove_temporary() {
    # Only this operation's own disposable worktree may be removed, never a task checkout.
    if [[ -n "$temporary" && -d "$temporary" ]]; then
        git -C "$source" worktree remove --force -- "$temporary" >/dev/null 2>&1 || return 1
    fi
    temporary=''
}
save_receipt() {
    local stage=$1
    reply | jq --arg stage "$stage" '{operation,source,destination,common,sourceHead,targetHead,
        sourceRef,result,temporary,status,reason,stage:$stage}' > "$receipt.part"
    mv -- "$receipt.part" "$receipt"
}

if [[ "$action" != inspect ]]; then
    [[ "$operation" =~ ^[0-9a-f-]{36}$ ]] || finish blocked 'Invalid merge operation.'
    # The approved canonical Git directory prevents a replaced path from selecting a different repo.
    [[ "$(jq -r '.common' <<< "$approved")" == "$common" &&
       "$(jq -r '.source' <<< "$approved")" == "$source" ]] || finish needsReview 'Repository identity changed. Review the task on the host.'
    receipt="$common/remote-codex-merges/$operation.json"
fi

if [[ "$action" == reconcile ]]; then
    [[ -f "$receipt" ]] || finish needsReview 'No host receipt is available. The request will not be replayed.'
    exec 9>"$common/remote-codex-merge.lock"
    flock -n 9 || finish needsReview 'A merge operation is still running. Check again after it finishes.'
    saved=$(cat -- "$receipt")
    [[ "$(jq -r '.operation' <<< "$saved")" == "$operation" &&
       "$(jq -r '.sourceHead' <<< "$saved")" == "$(jq -r '.sourceHead' <<< "$approved")" &&
       "$(jq -r '.targetHead' <<< "$saved")" == "$(jq -r '.targetHead' <<< "$approved")" &&
       "$(jq -r '.destination' <<< "$saved")" == "$(jq -r '.destination' <<< "$approved")" ]] || finish needsReview 'The merge receipt does not match this request.'
    destination=$(jq -r '.destination' <<< "$saved")
    source_head=$(jq -r '.sourceHead' <<< "$saved"); target_head=$(jq -r '.targetHead' <<< "$saved")
    source_ref=$(jq -r '.sourceRef' <<< "$saved"); result=$(jq -r '.result' <<< "$saved")
    temporary=$(jq -r '.temporary' <<< "$saved"); stage=$(jq -r '.stage' <<< "$saved")
    [[ -z "$temporary" || "$temporary" == "$common/remote-codex-merges/worktree-$operation" ]] || finish needsReview 'The recovery worktree does not match this operation.'
    if [[ "$stage" == succeeded || "$stage" == advancing ]] && [[ -n "$result" ]] &&
        [[ "$(git -C "$destination" symbolic-ref -q HEAD)" == refs/heads/main ]] &&
        git -C "$destination" merge-base --is-ancestor "$result" HEAD && clean "$destination" && idle_git "$destination"; then
        remove_temporary || finish needsReview 'Merged, but the temporary worktree needs cleanup. Check again.'
        status=succeeded; reason='Merged into local main.'; save_receipt succeeded; reply; exit 0
    fi
    if [[ "$stage" != succeeded ]] && target_is_expected && clean "$destination" && idle_git "$destination"; then
        remove_temporary || finish needsReview 'Main is unchanged, but the temporary worktree needs cleanup. Check again.'
        status=failed; reason='Main is unchanged. Refresh the preview before a new merge.'
        save_receipt failed; reply; exit 0
    fi
    finish needsReview 'Git state changed or the outcome is uncertain. Inspect the recorded checkouts on the host; this request will not be replayed.'
fi

inspect() {
    local worktree='' token candidates=() path
    local previous stage
    for previous in "$common"/remote-codex-merges/*.json; do
        [[ -e "$previous" ]] || continue
        stage=$(jq -r '.stage' "$previous")
        [[ "$stage" == succeeded || "$stage" == failed ]] || finish blocked 'An earlier merge in this repository needs review. Check its state from the original task first.'
    done
    source_head=$(git -C "$source" rev-parse --verify HEAD 2>/dev/null) || finish blocked 'The source has no committed changes.'
    source_ref=$(git -C "$source" symbolic-ref -q --short HEAD || true)
    target_head=$(git -C "$source" rev-parse --verify refs/heads/main 2>/dev/null) || finish blocked 'This repository has no local main branch.'
    while IFS= read -r -d '' token; do
        case "$token" in
            'worktree '*) worktree=${token#worktree } ;;
            'branch refs/heads/main') candidates+=("$worktree") ;;
        esac
    done < <(git -C "$source" worktree list --porcelain -z)
    [[ ${#candidates[@]} == 1 && -d "${candidates[0]}" ]] || finish blocked 'Main must be checked out in one existing local worktree.'
    destination=$(cd -- "${candidates[0]}" && pwd -P)
    target_is_expected || finish blocked 'The main checkout changed. Refresh the preview.'
    # v1 does not recursively merge repositories or populate submodules.
    # Check each tree independently (ls-tree takes one tree, then pathspecs).
    for tree in "$source_head" "$target_head"; do
        if [[ -n "$(git -C "$source" ls-tree -r "$tree" | awk '$1 == "160000" {print "submodule"}')" ]]; then
            finish blocked 'Submodule repositories are not supported by this merge control yet.'
        fi
    done
    for path in "$source" "$destination"; do
        idle_git "$path" || finish blocked 'A checkout has an unfinished Git operation. Finish it first.'
        clean "$path" || finish blocked 'Commit or remove uncommitted changes, including untracked files, in both checkouts first.'
    done
    git -C "$source" merge-base "$target_head" "$source_head" >/dev/null || finish blocked 'Source and main have unrelated histories.'
    git -C "$source" merge-base --is-ancestor "$source_head" "$target_head" && finish blocked 'These commits are already in main.'
    count=$(git -C "$source" rev-list --count "$target_head..$source_head")
    commits=$(git -C "$source" log -100 --format='%h %s' "$target_head..$source_head" | jq -Rs 'split("\n") | map(select(length > 0))')
    files=$(git -C "$source" diff --no-ext-diff --name-only -z "$target_head...$source_head" | jq -Rs 'split("\u0000") | map(select(length > 0))')
    file_count=$(jq 'length' <<< "$files"); files=$(jq '.[0:200]' <<< "$files")
    analysis=$(mktemp)
    local exit_code=0
    git -C "$source" merge-tree --write-tree --name-only -z "$target_head" "$source_head" > "$analysis" 2>/dev/null || exit_code=$?
    if [[ $exit_code == 1 ]]; then
        conflicts=$(jq -Rs 'split("\u0000") | .[1:] | reduce .[] as $p ({paths:[],done:false}; if .done or $p == "" then .done=true else .paths += [$p] end) | .paths' < "$analysis")
        finish blocked 'Merge conflicts must be resolved before this button can be used.'
    fi
    [[ $exit_code == 0 ]] || finish blocked 'Git could not check this merge. Inspect the repository on the host.'
    IFS= read -r -d '' merge_tree < "$analysis" || true
    cleanup_analysis; analysis=''
}

if [[ "$action" == merge ]]; then
    exec 9>"$common/remote-codex-merge.lock"
    flock -n 9 || finish blocked 'Another merge is running in this repository.'
    [[ ! -e "$receipt" ]] || finish needsReview 'This operation already has a receipt. Check its state instead of replaying it.'
fi
inspect
if [[ "$action" == inspect ]]; then finish ready 'Ready to merge committed changes into local main.'; fi
for field in source destination common sourceHead targetHead sourceRef; do
    case "$field" in
        source) value=$source ;; destination) value=$destination ;; common) value=$common ;;
        sourceHead) value=$source_head ;; targetHead) value=$target_head ;; sourceRef) value=$source_ref ;;
    esac
    [[ "$(jq -r --arg field "$field" '.[$field]' <<< "$approved")" == "$value" ]] || finish blocked 'The approved Git state changed. Refresh the preview and confirm again.'
done
mkdir -p -- "$common/remote-codex-merges"
status=needsReview; reason='Merge preparation started.'; save_receipt preparing
if git -C "$source" merge-base --is-ancestor "$target_head" "$source_head"; then
    result=$source_head
else
    temporary="$common/remote-codex-merges/worktree-$operation"
    save_receipt preparing
    git -C "$source" worktree add --detach -- "$temporary" "$target_head" >/dev/null 2>&1
    if ! git -C "$temporary" -c merge.autoStash=false merge --no-ff --no-edit --no-squash --no-autostash --no-overwrite-ignore \
        -m "Merge committed task changes into main" "$source_head" >/dev/null 2>&1; then
        remove_temporary || finish needsReview 'Merge preparation failed; its temporary worktree needs review.'
        status=failed; reason='Merge preparation failed (conflict, identity, hook, or signing configuration). Main is unchanged.'
        save_receipt failed; reply; exit 0
    fi
    result=$(git -C "$temporary" rev-parse HEAD)
    [[ "$(git -C "$temporary" rev-parse 'HEAD^{tree}')" == "$merge_tree" ]] && clean "$temporary" || finish needsReview 'Prepared merge differs from the reviewed merge. Main was not advanced.'
fi
save_receipt prepared
# Revalidate after preparation/hooks. A changed preview is never silently accepted.
if ! target_is_expected || [[ "$(git -C "$source" rev-parse HEAD)" != "$source_head" ]] ||
    [[ "$(git -C "$source" symbolic-ref -q --short HEAD || true)" != "$source_ref" ]] ||
    ! clean "$source" || ! clean "$destination" || ! idle_git "$source" || ! idle_git "$destination"; then
    remove_temporary || finish needsReview 'Git state changed; the temporary worktree needs review.'
    status=failed; reason='Git state changed before merging. Refresh the preview and confirm again.'
    save_receipt failed; reply; exit 0
fi
save_receipt advancing
# No autostash, squash, rebase, or conflict-producing merge is permitted in main.
if ! git -C "$destination" -c merge.autoStash=false -c branch.main.mergeOptions= merge --ff-only --no-edit --no-squash --no-autostash --no-overwrite-ignore "$result" >/dev/null 2>&1; then
    finish needsReview 'Git could not complete the final fast-forward. Check the operation state.'
fi
[[ "$(git -C "$destination" symbolic-ref -q HEAD)" == refs/heads/main &&
   "$(git -C "$destination" rev-parse HEAD)" == "$result" &&
   "$(git -C "$source" rev-parse refs/heads/main)" == "$result" ]] &&
    clean "$destination" && idle_git "$destination" || finish needsReview 'The destination changed during the merge. Inspect it before continuing.'
status=succeeded; reason='Merged into local main.'; save_receipt succeeded
remove_temporary || finish needsReview 'Merged, but the temporary worktree needs cleanup. Check again.'
save_receipt succeeded
reply
