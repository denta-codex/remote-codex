# Compact cover-screen layout and test

The Motorola Razr Ultra 2025 outer display is 4.0 inches at 1272 × 1080 pixels.
The supplied device capture is 1080 × 1272 in portrait. At the device's roughly
420 dpi logical density, that is approximately 411 × 485 dp before system insets.

This sits just above Android's 480 dp compact-height breakpoint, so size classes
alone do not identify it reliably. Remote Codex enters its cover presentation when
the window is compact width and either compact height or both shorter than 600 dp
and nearly square (height / width below 1.35). The decision uses the current app
window, not a device model or fold state, so it also handles equivalent split-screen
and freeform windows.

The app uses the current window's `WindowInsets.safeDrawing` for camera cutouts,
system bars, and keyboard clearance. Scaffold reserves these insets once and the
conversation consumes that padding before applying keyboard padding. The compact
composer action row stays at a 48 dp touch-target height; it does not add a fixed
camera-height spacer on top of Android's reserved area. A system-excluded camera
band therefore does not cause a second reservation inside the usable app window.

For cover-screen UX, keep the conversation as the primary pane and the composer
as its consistent action anchor. Reveal model, mode, attachments, and other
secondary controls through the conversation sheet. During typing, reduce the
resting controls to give the keyboard and message field priority. Keep these
decisions responsive to available width, height, and font scale, including when
the user changes the external display mode. Avoid scaling down the entire phone
UI or forcing a device-specific full-screen mode.

Full-screen image, file, plan, visualization, and recorded-change dialogs own their window
insets independently of the app shell. Their backgrounds fill the window while
their content uses safe-drawing padding; full-screen dialog windows explicitly
disable decor fitting so clearance is applied once. The project/sort and
conversation settings sheets use their parent's available height instead of a
percentage of the device configuration height, with scrollable options and
persistent actions. The separate credential-request screen also includes display
cutouts when combining system-bar and keyboard padding.

The implementation follows these Android recommendations:

- [Adapt layouts](https://developer.android.com/design/ui/mobile/guides/layout-and-content/adapt-layout)
  says to consider width first, then height; it explicitly calls out small foldable
  cover screens and recommends reflow, reveal, and presentation changes.
- [Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)
  defines compact width below 600 dp and compact height below 480 dp, treats the two
  axes independently, and requires testing across representative window sizes.
- [Foldable postures and orientation](https://developer.android.com/design/ui/mobile/guides/layout-and-content/postures-and-orientation)
  notes that flip-phone cover displays can be square and that primary content should
  take priority when vertical room is constrained.
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets)
  documents system bars, keyboard, display-cutout, and safe-drawing insets; these
  provide the platform geometry for keeping content visible.
- [Android virtual devices](https://developer.android.com/studio/run/managing-avds)
  supports custom hardware profiles with explicit resolution and screen size.
- [Motorola's published specification](https://motorola-global-en-uk--tst5.custhelp.com/app/answers/detail/a_id/192853)
  lists the cover display resolution as 1272 × 1080.

## Automated emulator test

Run:

```sh
scripts/emulator-test --cover
```

This uses the existing Gradle-managed Android 16 device, applies `wm size
1080x1272` and `wm density 420`, recreates the activity, and resets both overrides
after each test. The focused suite covers:

- task browser, settings, new chat, and an existing conversation;
- compact composer controls and attachment menu;
- image expansion and text-file preview;
- plan card and full-screen plan;
- blocking question presentation and submission;
- queueing during an active turn with Stop and Queue fully visible, then
  steering a queued message.

The normal phone suite remains:

```sh
scripts/emulator-test --full
```

Do not copy the pixel dimensions into production layout checks. The app's responsive
branch is based on dp window dimensions and aspect ratio, so font density, system
insets, folding, and window resizing continue to behave as Android expects.
