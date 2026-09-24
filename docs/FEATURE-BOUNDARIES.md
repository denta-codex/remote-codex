# Android feature boundaries

The Android client keeps one server-owned task model while letting parity features evolve in
separate worktrees.

- `AppShell` owns navigation, top-level connection/error presentation, and screen selection.
- `HomeScreen` owns task discovery presentation and depends only on `HomeActions`.
- `SettingsScreen` owns connection setup presentation and depends only on `SettingsActions`.
- `ConversationScreen` owns timeline, decisions, and delivery recovery presentation.
- `ConversationComposer` owns draft and send controls. Future composer features extend
  `NewTaskOptions` and `ConversationActions` instead of reaching into the activity.
- `ClientModel` remains the lifecycle orchestrator. It owns reconciliation and uncertain-send
  rules while implementing the screen action contracts.
- `RemoteSession` is the stock Codex RPC boundary. `ClientStore` is the device persistence
  boundary. Feature code should depend on these contracts rather than concrete transport or
  Room/DataStore implementations.

`NewTaskOptions` represents project, workspace target, model/reasoning, permissions, mode, and
attachments. Its defaults preserve the existing behavior: projectless execution and server
defaults with no attachments. Adding a selector should update this value; RPC wiring belongs to
the feature that introduces the selector and must retain the operation journal guarantees.

`HostIdentity` carries the host-specific endpoint and expected Codex home. Grace remains the only
configured host, but task and UI state no longer rely on scattered endpoint or display-name
constants.
