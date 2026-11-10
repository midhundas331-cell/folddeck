# Shortcut bar beside the monitor (portrait), 0.17.0

## Intent
Unfolded portrait ("portrait console", `DeckLayout.solve` → `portraitConsole`): the shortcut
bar is docked in the keyboard's shelf next to the trackpad. When the user shrinks the monitor
with the monitor/controls divider, the 16:9 desktop letterboxes and leaves empty bands left
and right of it. The user wants to drag the bar there in the Layout Studio and have it fit.

## Behaviour
- New per-posture flag `barBeside` (`Prefs.RESIZE_BAR_BESIDE + posture`, default false).
- `MainActivity.applyPosture`: `barCanDock = portraitConsole && controlsShown && showShortcuts`;
  `dockShortcuts = barCanDock && !barBeside`. Undocked, the bar's parent is `videoPane` and the
  existing `fitVideo` places it: a column beside the desktop when the spare width is at least
  `ShortcutBar.COLUMN_MIN_DP` (48dp), otherwise a row band above/below. `barFlip` picks the side
  (column: true = right; row: true = top).
- Undocked, `InputDeckLayout` gets `portraitShelf=false`, so keys + trackpad use the existing
  stacked layout (pad strip above/below keys, `padTop`), or Split.
- Studio drag rules, `WorkspaceGeometry.barMove(from, to, docked, canDock)`:
  - docked bar dragged onto MONITOR → `BAR_UNDOCK`: `barBeside = true`, side = half of the
    monitor the finger is on.
  - undocked bar onto MONITOR → `BAR_SIDE`: side = finger's half when it is a column; toggles
    when it is a row.
  - undocked bar onto KEYBOARD or TRACKPAD → `BAR_REDOCK`: `barBeside = false`.
  - anything else, and every posture that cannot dock (landscape, cover) → unchanged old rules.
- Saved with the other arrangement flags in `finishResize(true)`; Reset clears it; Cancel
  reloads it via `loadResize`.

## Non-goals
No change to landscape or cover-screen layouts. No new pane positions for keyboard/trackpad.

## Files
`android/src/io/folddeck/spike/MainActivity.java` (applyPosture ~l.880-960, fitVideo ~l.465-565,
studio `swap` ~l.1195-1245, loadResize, finishResize), `WorkspaceGeometry.java` (`barMove`),
`InputDeckLayout.java` (`computeBoxes`, `resizeHeight`), `DeckLayout.java`, `Prefs.java`,
`ResizeOverlay.java` (live swap ~l.580-600), test `android/test/InputDeckLayoutCheck.java`.

## Known risks to check
1. Undocked + Split in portrait makes `raisedPad` true (previously only reachable in landscape).
2. `padHeight` saved while docked meant the shelf pad's height; undocked it is the strip's height.
3. Live swap fires when the finger enters a pane; after UNDOCK the layout changes under the finger.
4. `resizedLid(..., shelf)` minimum base differs docked vs undocked.
