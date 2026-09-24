# UI cleanup — September 24, 2026

This pass changes presentation and conversation scrolling. It adds no product capabilities and leaves the transport, mutation handling, credentials, and server state model unchanged.

## Reference inspection

Inspected the existing extracted desktop bundle at `/tmp/chatgpt-app-asar/webview/assets`, especially `thread-scroll-layout-e4643b89edd2.js`, its CSS, and the sidebar action assets. Its conversation layout uses a bottom scroll origin, distinguishes deliberate user scrolling from content growth, and uses compact icon actions. These informed the Android implementation; no extracted source or assets were copied.

The earlier Android JADX output referenced by the local research notes (`/tmp/codex-android-audit`) was no longer present. This pass does not claim a fresh inspection of that APK.

## Changes

- Consistent outlined vector icons for the existing navigation, search, compose, archive, send, stop, setup, and disclosure controls. Icon-only actions have accessibility labels and 48 dp targets.
- Neutral light/dark surfaces, quieter metadata, shorter headers, clearer task rows, and a rounded composer with more space for the conversation.
- Right-aligned user messages, full-width assistant text, and bordered expandable command/file details.
- A reverse-layout conversation starts at the latest content, including the bottom of an oversized reply. Stable message keys preserve reading position. Streaming follows the latest content until the reader scrolls away; reopening a task returns to the latest reply.

## Reproduce

Run `scripts/check`, then `scripts/emulator-start`, then `scripts/emulator-test`.

Record the fixture demo with:

```sh
scripts/emulator-record polishedConversationOpensAtLatestAndKeepsReadingPosition \
  --output artifacts/demos/ui-cleanup.mp4
```

The demo opens a 20-turn history, expands a command, scrolls back while a reply grows, reopens at the latest reply, sends a message, and shows the existing settings/new-chat screens and dark theme. A separate instrumentation case covers a latest reply taller than the viewport and continued streaming at its end. All content is synthetic.
