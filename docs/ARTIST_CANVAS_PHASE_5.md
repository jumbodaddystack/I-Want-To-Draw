# Phase 5 — Foundation polish

> Surface what `STYLUS_NOTES_PLAN.md` already shipped. No new engine, no schema-version bump beyond the persisted undo log. Each sub-phase is shippable independently and noticeably changes how the canvas feels.

Parent plan: [`ARTIST_CANVAS_PLAN.md`](./ARTIST_CANVAS_PLAN.md).

## Status

| Sub-phase | State | Notes |
| --- | --- | --- |
| 5.1 | ✅ Implemented | `ToolDynamics` extracted; `StrokeRenderer` routes width/alpha through it; JVM tests in `ToolDynamicsTest`. |
| 5.2 | ✅ Implemented | `Note.undoLogJson` + `MIGRATION_5_6`; `EditorActionCodec` (JSON, base64 payloads, 256 KB cap, FIFO eviction); rehydrated in `NoteEditorViewModel.init`, persisted in `save()`. Tests in `EditorActionCodecTest`. |
| 5.3 | ✅ Implemented | `RecentColorsStore` (DataStore, 12-entry FIFO, app-wide); `ColorPickerSheet` (hue ring + SL square + alpha slider + hex round-trip + recents); `ToolPalette` long-press + `+` tile open the sheet. |
| 5.4 | ✅ Implemented | `ViewportController.fitToContent` / `centerOnContent` / `resetToOneHundred`; `ZoomChrome` chip + popover slotted into the editor TopAppBar. JVM tests in `ViewportControllerTest`. Minimap remains optional (not shipped). |
| 5.5 | ⏳ Pending hardware | Cannot run on real S-Pen hardware in CI; verify on a Samsung S25 Ultra per the matrix below. |

### Notes / deviations

- **Highlighter base alpha** lifted from `~0.30` (76/255) to `0.35` (89/255) to match the spec — a single pass now reads slightly heavier on white paper. Old highlighter strokes re-render with the new alpha (data unchanged).
- **`StateFlow<Float>` on ViewportController** — left as Compose `mutableStateOf` because the editor consumes it from a `@Composable` (`ZoomChrome`) that recomposes on the underlying snapshot read. A `StateFlow` wrapper would have been redundant.
- **Minimap** — not shipped (the spec marks it optional behind a feature flag); the chip popover covers the navigation gap.
- **Phase 5.1 `DrawingSurface` edits** — the spec's "≈ lines 329–351 / 849–870" pointer ended up being entirely inside `StrokeRenderer.drawStrokePath`, which is the shared path both `DrawingSurface` (live + replay) and `NoteRasterizer` (export) call. Centralising there means `DrawingSurface` did not need its own modification.

## Sub-phase 5.1 — Pressure & tilt → width/opacity modulation

### Scope

`DrawingSurface` already records `pressure` and `tilt` per sample (`FloatArray[2]` and `[3]` inside `StrokeCodec`'s 4-floats-per-sample format), but at render time every stroke gets `baseWidthPx`. This sub-phase wires the recorded data into per-segment width and opacity through a tool-specific dynamics function.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ToolDynamics.kt` — pure functions, no Android deps so they can be JVM-tested.
- `app/src/test/java/com/aichat/sandbox/ui/components/notes/ToolDynamicsTest.kt`.

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/DrawingSurface.kt` — segment renderer (≈ lines 329–351 live-stroke path, plus the committed-stroke blit path around 849–870) reads from `ToolDynamics` instead of a hard-coded `baseWidthPx`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/StrokeRenderer.kt` — same change for the committed render used by `NoteRasterizer`.

### Step-by-step

1. Define `ToolDynamics`:
   ```kotlin
   object ToolDynamics {
       data class SegmentStyle(val widthPx: Float, val alpha: Float)
       fun pen(baseWidthPx: Float, pressure: Float, tilt: Float): SegmentStyle
       fun pencil(baseWidthPx: Float, pressure: Float, tilt: Float): SegmentStyle
       fun highlighter(baseWidthPx: Float, pressure: Float, tilt: Float): SegmentStyle
       fun marker(baseWidthPx: Float, pressure: Float, tilt: Float): SegmentStyle // Phase 6.6 future
   }
   ```
   - **Pen**: width = `baseWidth * lerp(0.35, 1.15, pressure^0.7)`. Tilt ignored. Alpha = 1.
   - **Pencil**: width = `baseWidth * lerp(0.7, 1.6, sin(tilt))` (tilt broadens). Alpha = `lerp(0.35, 1.0, pressure^0.5)`.
   - **Highlighter**: width = `baseWidth` (constant). Alpha = 0.35 (constant). Pressure / tilt ignored to keep the marker look consistent.
2. Find the live-stroke renderer in `DrawingSurface` (the per-segment path that does `path.quadTo(...)` and `canvas.drawPath`). Replace the static `paint.strokeWidth = baseWidthPx` with a per-segment style read from `ToolDynamics.<tool>` for the sample at the segment's midpoint.
3. Mirror the change in `StrokeRenderer` so committed strokes, thumbnails, exports, and the AI rasterizer all match.
4. Sanity-cap output: `widthPx ∈ [0.5f, 64f]`, `alpha ∈ [0.05f, 1f]`. Documented as defense against bad input from older payloads (where pressure was clamped to 1.0).
5. Unit test the curves with hand-picked points (pressure = 0, 0.5, 1.0; tilt = 0, π/4, π/2) and one regression test per tool.

### Definition of done

- A slow, light pen stroke tapers visibly thinner than a fast, hard one.
- Pencil strokes shade darker / wider when the S-Pen is tilted.
- Highlighter looks identical to today (visual regression baseline).
- `./gradlew :app:testDebugUnitTest` passes; new `ToolDynamicsTest` covers each tool at three pressure / three tilt points.
- Old notes re-render with the new dynamics with no migration needed (pressure / tilt were always recorded).

### Non-goals

- Custom pressure curves per preset — that's 6.5.
- Texture / blend mode changes — that's 6.6.
- Eraser dynamics — eraser stays binary.

### Risks

- **Existing notes look different.** Strokes drawn before this change re-render with the new curves. We accept that — the data is unchanged, only display differs. Note in the PR description.
- **Pencil tilt math is degree vs. radian-sensitive.** `MotionEvent.AXIS_TILT` returns radians. Document at the top of `ToolDynamics`.

---

## Sub-phase 5.2 — Persisted undo/redo across sessions

### Scope

`NoteEditorViewModel` keeps a 200-action `ArrayDeque` in memory only. App-kill kills the history. This sub-phase serializes the undo log onto the `Note` row so reopening the editor restores the stack.

### Files

Modified:
- `app/src/main/java/com/aichat/sandbox/data/model/Note.kt` — new field `undoLogJson: String? = null`.
- `app/src/main/java/com/aichat/sandbox/data/local/Migrations.kt` — new `MIGRATION_5_6` adds the column.
- `app/src/main/java/com/aichat/sandbox/data/local/AppDatabase.kt` — bump `version = 6`, register the migration.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteEditorViewModel.kt` — restore stack on init, persist stack on save.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/EditorAction.kt` — add `@SerializedName` annotations or a sealed-type adapter (Gson default polymorphic serialization isn't enough).

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/EditorActionCodec.kt` — Gson `TypeAdapter` for the `EditorAction` sealed class.
- Unit tests for round-trip serialization of every action variant including `TransformItems` (matrix as 9-float array), `UpdateText`, `RemoveItems` (full `NoteItem` payload bytes base64-encoded).

### Step-by-step

1. Schema bump `AppDatabase.version = 6`. Write `MIGRATION_5_6` that runs `ALTER TABLE notes ADD COLUMN undoLogJson TEXT`.
2. Define wire format:
   ```json
   {
     "schema": 1,
     "past":   [ { "type": "AddItems",       "items": [...] }, ... ],
     "future": [ ... ]
   }
   ```
   Item payloads serialize bytes as base64. Matrices serialize as 9-float arrays.
3. In `NoteEditorViewModel.init`, after loading the note, decode `undoLogJson` and rehydrate `past` / `future`. If decode fails, drop the log and log a warning — never crash the editor.
4. In `save()`, encode the current stacks and persist alongside the note. Cap the encoded size at 256 KB; if exceeded, evict oldest `past` entries until it fits.
5. Defensive cap: 200 actions per stack (unchanged from in-memory) — eviction is FIFO from `past`, then drop `future` if still oversize.

### Definition of done

- Draw → undo once → kill app → reopen → redo works and restores the stroke.
- Draw 5 → undo 3 → kill → reopen → undo / redo state matches.
- A note with no `undoLogJson` (existing rows) opens cleanly with empty stacks.
- A note with malformed JSON opens cleanly with empty stacks plus a logcat warning.
- `EditorActionCodec` round-trips every action variant.
- Encoded size cap is exercised by a unit test that hammers `AddItems` 500 times.

### Non-goals

- Cross-device sync of the undo log.
- Branching history.
- Per-frame / per-layer undo scoping (today undo is global to the note; that stays).

### Risks

- **Stroke payloads inside `RemoveItems` are bulky.** Base64-encoded byte arrays in JSON triple the size for the action that needs them most. Mitigation: the 256 KB cap; FIFO eviction. Worst case the user loses old undo, not the note.
- **Sealed-class deserialization is order-sensitive.** Lock the `"type"` strings in `EditorActionCodec` (use a `when` not `Class.forName`) so renaming a class doesn't silently break existing logs.

---

## Sub-phase 5.3 — Full color picker

### Scope

Six fixed swatches and one tool-shared color is fine for note-taking, not for drawing. This sub-phase adds a proper HSL wheel + hex input + recent-colors row, while keeping the existing swatches as one-tap presets.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ColorPickerSheet.kt` — bottom sheet hosting the wheel + hex + recents.
- `app/src/main/java/com/aichat/sandbox/data/notes/RecentColorsStore.kt` — DataStore-backed FIFO of last 12 colors, app-wide (not per-note).
- Optional: pull in `com.godaddy.android.colorpicker:compose-color-picker` or roll an HSL wheel from scratch. Prefer rolling our own to avoid a dep for a single screen.

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ToolPalette.kt` — long-press on a swatch (or a "+" tile at the end of the swatch row) opens `ColorPickerSheet`. Confirmed color updates the active tool's color and prepends to recents.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ToolPaletteState.kt` — colors stay per-tool (pen vs. highlighter etc.). The new sheet edits whichever tool is active.

### Step-by-step

1. Build a Compose HSL wheel: hue ring + saturation/lightness square, plus an alpha slider. Output in ARGB int form (`Color.toArgb()`).
2. Add a hex text input below the wheel; round-trip with the wheel state.
3. Render a 12-cell recents grid above the wheel, sourced from `RecentColorsStore`. Tap = apply.
4. Wire the "+" tile in `ToolPalette` to open the sheet. On confirm: write to active tool's color, push to recents.
5. Persist recents across sessions via `Preferences DataStore` keyed `recent_colors:argb_list`.

### Definition of done

- Long-press on any existing swatch opens the picker pre-loaded with that color.
- Confirming a custom color applies it immediately to the active tool.
- Hex field accepts `#RRGGBB` and `#AARRGGBB`; rejects malformed input with a tiny inline error.
- The 12 recents persist across app kill.
- The six default swatches are still a one-tap path.

### Non-goals

- COPIC palette (deferred; nice to have, not blocking).
- Per-layer color overrides — color lives on the item.
- Eyedropper from canvas — defer to Phase 6 polish if requested.

### Risks

- **Compose wheel performance.** Recomposition cost when dragging on the SL square. Mitigate with `Modifier.pointerInput` + `mutableFloatStateOf` for the local pointer position, push the final color through `derivedStateOf` so the slider track redraws but the parent doesn't.

---

## Sub-phase 5.4 — Zoom/pan UI affordances

### Scope

`ViewportController` already supports pan / pinch-zoom 0.25× – 8×. Users have no way to tell. This sub-phase adds a zoom chip in the top bar, three viewport actions (Fit, 100%, Center), and an optional thumbnail minimap.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ZoomChrome.kt` — Composable that renders the chip, popup with the three actions, and reads/writes `ViewportController`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/Minimap.kt` (optional, behind a feature flag) — small bottom-right thumbnail of the scene bitmap with a viewport rectangle.

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteEditorScreen.kt` — slot `ZoomChrome` into the top bar between the AI button and the overflow menu; hand it the `ViewportController` ref tracked at `NoteEditorScreen.kt:69`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ViewportController.kt` — expose `scale: StateFlow<Float>`, `fitToContent(bounds, canvasSize)`, `centerOnContent(bounds, canvasSize)`, `resetToOneHundred(canvasSize)`. The first two already exist conceptually; surface them publicly.

### Step-by-step

1. Add `scale: StateFlow<Float>` on `ViewportController`. Wire it from the existing internal mutable state.
2. Implement `fitToContent(bounds, canvasSize)` — choose scale so `bounds` fits within `canvasSize` minus a 24px margin, then center.
3. `ZoomChrome`: a small `AssistChip` with the current scale formatted as `%d%%`. Tap opens a popover with three rows: **Fit content**, **100%**, **Center**.
4. Pinch-zoom updates the chip in real time via the new StateFlow.
5. Minimap (optional, off by default): 160 × 100 dp thumbnail bottom-right, semi-transparent, shows a viewport rectangle. Tap-drag the rectangle to pan; tap outside to fly the viewport there. Re-renders at most every 250 ms.

### Definition of done

- Zoom chip always shows the current scale, updates while pinching.
- Each of Fit / 100% / Center produces visually correct viewport state.
- Existing pinch/pan gestures unchanged.
- Minimap (if shipped) does not regress live-stroke latency on a 100-stroke note.

### Non-goals

- Free-aspect crop / export region selection (that's Phase 8 with frames).
- Mouse-wheel zoom (Android device focus; defer).

### Risks

- **Minimap regenerates on every commit.** If we re-render the whole scene bitmap into the minimap each commit, performance dies on dense notes. Mitigation: throttle to 250 ms via `Flow.sample`, downscale the existing scene bitmap rather than redrawing strokes.

---

## Sub-phase 5.5 — Phase 5 device verification

### Verification matrix (Samsung S25 Ultra, S-Pen)

1. Pen: slow vs. fast stroke shows visible width taper.
2. Pencil: tilting the S-Pen broadens and lightens the line; vertical pencil gives a thin dark line.
3. Highlighter: stroke is constant width and translucent regardless of pressure / tilt.
4. Draw 10 strokes → undo 5 → force-stop app → reopen → redo all 5 → state matches.
5. Open a note created before 5.2 → no crash, empty undo stack.
6. Long-press a swatch → custom color via wheel → applies; reopen note → color persists per stroke.
7. Recent colors persist across app kill.
8. Zoom chip updates live during pinch; Fit / 100% / Center all behave correctly on a 5000×5000 world bounding box.

### Definition of done

- All eight items pass on real hardware.
- Update **Status** in `ARTIST_CANVAS_PLAN.md` with the pass date.
