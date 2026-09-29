# Physical-device acceptance

Target: Motorola Razr Ultra 2025, explicit Taildrop DNS name from inventory.
Record Android version, APK version/code, signer fingerprint, Codex version and date.

- [x] Install signed 0.1.3 APK, scan the setup QR in Settings; Tailscale enabled.
- [ ] Upgrade to signed 0.2.2 (versionCode 12) without losing credentials or drafts.
- [x] Authenticated WSS connection to Grace reports Connected on the Razr (2026-09-23).
- [ ] Invalid QR and canceled scan leave an existing credential intact; manual entry works.
- [ ] Select Remote Codex as default assistant; cold/warm gesture opens New chat.
- [ ] Send text, observe streaming, queue follow-ups, promote one with Steer now, and stop.
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
- [ ] Desktop answers first: phone controls disappear.
- [ ] Late pending file approval is either actionable with context or explicitly unsupported.
- [ ] Lock/unlock, rotation/folding and process recreation preserve drafts/history.
- [ ] Disconnect around send acknowledgement; no automatic duplicate submission.
- [ ] Disconnect during an attachment write; confirm no file write or turn is replayed.
- [ ] Restore Tailscale; reconnect and inspect uncertain operation before unlocking composer.
- [ ] Higher-version same-signer APK preserves credential/drafts/assistant selection.
- [ ] Scheduled host restart: user service and persistent Serve route return.

Phone installation, assistant selection and physical gesture require the user.
A successful Taildrop transfer is not proof of installation. Do not check these
boxes from emulator evidence alone.
