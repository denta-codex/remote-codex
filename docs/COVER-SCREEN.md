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
- blocking question presentation and submission.

The normal phone suite remains:

```sh
scripts/emulator-test --full
```

Do not copy the pixel dimensions into production layout checks. The app's responsive
branch is based on dp window dimensions and aspect ratio, so font density, system
insets, folding, and window resizing continue to behave as Android expects.
