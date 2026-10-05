# Physical-device acceptance

Target: Motorola Razr Ultra 2025, explicit Taildrop DNS name from inventory.
Record Android version, APK version/code, signer fingerprint, Codex version and date.

- [x] Install signed 0.1.3 APK, scan the setup QR in Settings; Tailscale enabled.
- [ ] Upgrade to signed 0.2.2 (versionCode 12) without losing credentials or drafts.
- [x] Authenticated WSS connection to Grace reports Connected on the Razr (2026-09-23).
- [ ] Invalid QR and canceled scan leave an existing credential intact; manual entry works.
- [ ] Select Remote Codex as default assistant; cold/warm gesture opens New chat.
- [ ] Send text, observe streaming, queue follow-ups, promote one with Steer now, and stop.
- [ ] On both displays, feel one tick on Send (including queueing) and soft ticks
      synchronized with new reply text. Confirm strength fades over eight seconds,
      stays silent afterward, and starts fresh for the next turn. Assess comfort
      and synchronization on the Razr; emulator results cannot establish the feel.
- [ ] Disable Haptic feedback in Settings, restart, and confirm send, streaming,
      and swipe effects remain off. Verify Android's touch-feedback setting is
      respected, and history/reconnect/background activity produces no extra ticks.
- [ ] Verify queued text and images survive reopening, preserve a new draft when steered,
      and match changes made on desktop. Resume an interrupted queue with Send now.
- [ ] Enter Plan mode, answer a blocking question, view the completed plan full screen, and implement it.
- [ ] Send one image-only turn and one text-plus-multiple-images turn from Photos.
- [ ] Capture and send a camera image without granting broad media permissions.
- [ ] Send one file-only turn and one mixed image/file turn from the system document picker.
- [ ] Reopen the task and confirm generic attachment chips reconstruct from server history.
- [ ] Open, share, and save text, image, and unsupported result files; reject directories and files over 20 MiB.
- [ ] Reopen the task and expand user, generated, and Codex-opened images.
- [ ] Reject an unsupported/animated image, a file over 20 MiB, and a selection over 50 MiB.
- [ ] Confirm identical task and messages on desktop, including projectless identity.
- [ ] Search, archive-filter, page tasks/history, and continue an existing coding task.
- [ ] Command/file/permission decisions and question forms work with real requests.
- [ ] MCP forms submit typed values; URL requests open only after consent and never infer completion from browser return.
- [ ] Desktop answers first: phone controls disappear.
- [ ] Late pending file approval is either actionable with context or explicitly unsupported.
- [ ] Lock/unlock, rotation/folding and process recreation preserve drafts/history.
- [ ] Disconnect around send acknowledgement; no automatic duplicate submission.
- [ ] Disconnect during an attachment write; confirm no file write or turn is replayed.
- [ ] Restore Tailscale; reconnect and inspect uncertain operation before unlocking composer.
- [ ] Higher-version same-signer APK preserves credential/drafts/assistant selection.
- [ ] Scheduled host restart: user service and persistent Serve route return.
- [ ] Take a system screenshot on both Razr displays: one "Screenshot taken ·
      Report or request" snackbar appears and does not hide the system preview's
      actions. Tapping it attaches the app window as it was when the screenshot
      was taken. Ignoring it opens nothing. Confirm the Settings toggle persists.
- [ ] With the bug-report build installed, deliberately shake on both Razr displays:
      one report opens, the screenshot precedes the sheet, and repeated shakes
      respect the cooldown. Ordinary handling and the phone's other gestures do
      not open unwanted reports. Confirm disabling the Settings toggle persists.
- [ ] Save a report offline, reopen after a process restart, and verify the
      intent, original screenshot, human description, and diagnostic timestamps remain.
- [ ] On both displays, choose Research, review its title/message/evidence, edit
      the request, and return to review. Nothing starts before the explicit final
      action; reconnecting preserves the draft without submitting it.
- [ ] Submit the report to Grace and confirm one remote-codex worktree, successful
      environment setup, attached evidence, and a task matching the selected intent
      and mode on desktop. Only Implement authorizes implementation.
- [ ] Confirm real-device app logcat and available historical crash/ANR evidence;
      unavailable diagnostics are identified without blocking the report.

Phone installation, assistant selection and physical gesture require the user.
A successful Taildrop transfer is not proof of installation. Do not check these
boxes from emulator evidence alone.
