# Phase 1 Implementation Plan — Editable Bézier Scene Graph + Pen Tool

## Context

This is the keystone phase from the approved roadmap
(`image-what-an-app-stateful-gosling.md`). Today the app has a rich vector model
(`data/vector/VectorDocument`) that is **read-only** — it's parsed from imported
XML/SVG, optimized, AI-edited, and rendered as a safe preview, but a user can
never grab an anchor point and move it. Phase 1 adds the missing interactive
editing layer on top of the existing model, turning the Vector Tune-Up workspace
from "view + optimize" into a real vector editor. It deliberately reuses the
existing parser, normalizer, preview renderer, viewport controller, snapping, and
reducer/ViewModel patterns rather than building a parallel stack.

**Scope of this phase (in):** an editable node model derived from
`VectorDocument`; a pen tool (place anchors, drag handles); direct-selection
(move anchors/handles); add/delete anchor; convert corner↔smooth; close/open
subpath; move/translate selection; grid/angle snapping; undo/redo; lossless
re-serialization back to `VectorDocument` → XML/SVG via the existing writers.

**Out of scope (later phases):** boolean ops (Phase 2), keyline grids /
multi-size artboards (Phase 3), folding the notes canvas in / freehand
vectorization (Phase 4), gradients & stroke styling (Phase 5). Quad/arc commands
are **elevated to cubics** on entering edit mode (see "Round-trip contract").

---

## The editable model (new — `data/vector/edit/`)

The `VectorDocument`/`PathCommand` types stay the immutable source of truth.
We add a normalized, **all-cubic, absolute-coordinate** editable view that is
trivial to manipulate and hit-test, plus pure converters to/from it.

```
data/vector/edit/EditablePath.kt
    enum AnchorType { CORNER, SMOOTH, SYMMETRIC }

    // Handles stored as ABSOLUTE control points (not deltas) to match the
    // normalizer's output and simplify hit-testing; null = no handle (corner).
    data class EditAnchor(
        val id: String,            // stable id, survives edits (for selection)
        val x: Float, val y: Float,
        val inX: Float?, val inY: Float?,    // incoming control point
        val outX: Float?, val outY: Float?,  // outgoing control point
        val type: AnchorType = AnchorType.CORNER,
    )

    data class EditSubpath(val id: String, val anchors: List<EditAnchor>, val closed: Boolean)

    // One editable path = the geometry of a single VectorPath, keeping its
    // id + style so we can write it straight back into the document tree.
    data class EditablePath(
        val pathId: String,
        val subpaths: List<EditSubpath>,
        val style: VectorStyle,
    )
```

### Conversion in: `VectorDocument` → editable (reuse the normalizer)
`data/vector/edit/EditablePathFactory.kt`
- For each `VectorPath`, call the existing
  `VectorPreviewPathNormalizer.normalize(path.commands)` → `List<PreviewSubpath>`
  of absolute `PreviewSegment.{Line,Quad,Cubic}`. This already resolves relative
  commands and the `S`/`T` smooth-reflection logic, so we never re-touch path math.
- Walk each subpath's segments and build anchors:
  - **Line** → segment with no handles (`out`/`in` null on the bounding anchors).
  - **Quad** → elevate to cubic (`c1 = p0 + 2/3·(ctrl−p0)`, `c2 = p1 + 2/3·(ctrl−p1)`),
    then treat as Cubic.
  - **Cubic** → `out` handle of the start anchor = `c1`; `in` handle of the end
    anchor = `c2`.
  - Classify `AnchorType`: SMOOTH/SYMMETRIC when in/out handles are (near-)colinear
    through the anchor; CORNER otherwise. (Purely cosmetic — drives handle drag
    behavior, not geometry.)
- Assign deterministic ids (`"${pathId}.s${i}.a${j}"`) so selection survives
  edits and undo.

### Conversion out: editable → `VectorPath.commands`
`data/vector/edit/EditablePathSerializer.kt`
- For each subpath emit `MoveTo(firstAnchor)`, then per adjacent pair:
  - both relevant handles absent → `LineTo(end)`
  - otherwise → `CubicTo(out(prev) ?: prev, in(next) ?: next, end)`
- Append `Close` when `closed`. Set `pathData` to the re-serialized string via the
  **existing** `AndroidVectorDrawableWriter` path-data emitter (extract/reuse its
  command→string function; do not write a second one).
- Replace the matching `VectorPath` in the `VectorDocument` tree by id (add a
  pure `VectorDocument.replacePath(id, newPath)` helper next to `allPaths()` in
  `VectorDocument.kt`).

### Round-trip contract (Phase 0 work, validated by tests)
- A path that is **only viewed** (never edited) is written back byte-identical by
  preferring its original `pathData` verbatim (the writer already falls back to
  `pathData` when `commands` is null — we extend: editor marks untouched paths
  "pristine" and skips re-serialization).
- A path that **is edited** round-trips through M/L/C/Z and is visually identical
  within tolerance; original H/V/Q/A/S/T tokens are intentionally normalized to
  C. This is the documented, accepted lossiness of entering edit mode.

---

## Editor state + reducer (new — mirrors Tune-Up's pure pattern)

```
ui/screens/vector/edit/VectorEditState.kt      // immutable UI state
ui/screens/vector/edit/VectorEditReducer.kt    // pure (State, Action) -> State
ui/screens/vector/edit/VectorEditAction.kt     // sealed action set
```

`VectorEditState` (immutable, JVM-testable like `VectorTuneupUiState`):
- `document: VectorDocument` (current geometry)
- `editing: EditablePath?` (the path currently in node-edit, lazily derived)
- `activeTool: EditTool` (`PEN`, `DIRECT_SELECT`)
- `selection: Selection` (set of anchor ids + handle refs)
- `pendingPen: PenDraft?` (subpath being drawn but not committed)
- `snapMask: Int` (reuse `Snap.MASK_*`)
- `undoStack`, `redoStack` (bounded ~200, mirroring notes `EditorAction` cap)

`VectorEditAction` (sealed): `StartPath`, `PlaceAnchor(world)`,
`DragHandle(world)`, `CommitPath`, `SelectAnchor(id, additive)`,
`MoveSelection(dx,dy)`, `InsertAnchorOnSegment(subpathId, t)`,
`DeleteSelected`, `SetAnchorType(id, type)`, `ToggleSubpathClosed(subpathId)`,
`Undo`, `Redo`.

`VectorEditReducer.reduce(state, action): VectorEditState` — **pure**, no Compose
/ Android imports, so the whole edit algebra is unit-tested without a device,
exactly like `VectorTuneupReducer`. Each mutating action pushes an inverse onto
`undoStack`.

`VectorEditViewModel` holds `StateFlow<VectorEditState>` and dispatches actions
into the reducer (same shape as `VectorTuneupViewModel`).

---

## Rendering + interaction (extend, don't replace)

`ui/screens/vector/edit/VectorEditCanvas.kt`
- Render the document via the existing `VectorPreviewCanvas` internals
  (`preparePreviewPaths` / `buildComposePath` / `drawPreparedPath`) so fills,
  strokes, and the fit transform are pixel-consistent with preview.
- **Reuse `ViewportController`** (from `ui/components/notes/`) for pan/zoom and
  `screenToWorld*`/`worldToScreen*`. Its bounded-artboard clamp is already what an
  icon canvas wants; the notes/icon distinction transfers directly.
- Overlay layer (drawn in screen space): anchor dots (filled=corner, hollow=smooth),
  handle lines + handle knobs for selected anchors, the in-progress pen rubber-band.
- Input via Compose `pointerInput { detectDragGestures / detectTapGestures }`:
  - **Pen tool:** tap places an anchor (`PlaceAnchor`); tap-drag pulls symmetric
    handles (`DragHandle`); tap on the first anchor closes the subpath.
  - **Direct-select:** hit-test anchors/handles (screen-space radius), drag to
    move (`MoveSelection` / `DragHandle`); tap empty deselects.
  - Snap every placed/dragged anchor through `Snap.snapToGrid` /
    `Snap.snapAngleTo` / `Snap.snapToEndpoints` (anchor termini as candidates),
    gated by `snapMask`.

Hit-testing math (anchor/handle/segment-`t`) lives in a pure
`ui/screens/vector/edit/EditHitTest.kt` (JVM-testable; takes world coords +
a screen-space tolerance already divided by `viewport.scale`).

---

## Entry point / wiring

- Add an **"Edit" affordance** in the existing Vector Tune-Up workspace
  (`VectorTuneupScreen.kt`) that opens the node editor for the current version's
  `VectorDocument`. On exit, the edited document flows back as a new version
  through the existing version-history machinery (`VectorTuneupReducer` already
  creates versions), so undo/branch/compare/export all keep working for free.
- No new Room schema: the edited result is just another `VectorDocument` serialized
  by the existing writers/persistence. (If a dedicated editor screen is preferred
  over an in-Tune-Up mode, that's a one-line nav choice — confirm during build.)

---

## File-by-file work list

**New (`data/vector/edit/`)**
- `EditablePath.kt` — model (above)
- `EditablePathFactory.kt` — `VectorDocument` → `EditablePath` (reuses `VectorPreviewPathNormalizer`)
- `EditablePathSerializer.kt` — `EditablePath` → `VectorPath.commands` + `pathData`

**New (`ui/screens/vector/edit/`)**
- `VectorEditState.kt`, `VectorEditAction.kt`, `VectorEditReducer.kt` — pure core
- `VectorEditViewModel.kt` — StateFlow host (Hilt, mirrors `VectorTuneupViewModel`)
- `VectorEditCanvas.kt` — Compose canvas + overlay + gestures
- `EditHitTest.kt` — pure hit-testing
- `VectorEditScreen.kt` (or an in-Tune-Up mode) — toolbar (Pen / Direct-select /
  add / delete / corner-smooth / close), undo/redo buttons

**Modified**
- `data/vector/VectorDocument.kt` — add `replacePath(id, newPath)` helper
- `data/vector/AndroidVectorDrawableWriter.kt` — expose its command→pathData
  string function for reuse by the serializer (extract internal → internal fun)
- `ui/screens/vector/VectorTuneupScreen.kt` — "Edit" entry + return-as-new-version

**Reused unchanged:** `VectorPreviewPathNormalizer`, `VectorPreviewCanvas`
internals, `ViewportController`, `Snap`, `AndroidVectorDrawableWriter` /
`VectorSvgWriter` (export), version-history reducer paths.

---

## Test plan (pure JVM — the bulk of the value lands here)

`app/src/test/java/com/aichat/sandbox/data/vector/edit/`
- `EditablePathRoundTripTest` — `VectorDocument → EditablePath → VectorDocument`
  is geometry-stable within tolerance over representative icons (reuse fixtures
  from `VectorPreviewPathNormalizerTest` / parser tests); pristine (unedited)
  paths come back byte-identical.
- `EditablePathFactoryTest` — quad-elevation correctness; relative-command
  resolution (delegated to normalizer) preserved; anchor-type classification.
- `EditablePathSerializerTest` — line vs. cubic emission; close handling; output
  re-parses via `PathDataParser` to identical commands.

`app/src/test/java/com/aichat/sandbox/ui/screens/vector/edit/`
- `VectorEditReducerTest` — each action: place/commit a triangle and a curve;
  insert anchor splits a segment without changing the curve (de Casteljau split);
  delete anchor; corner↔smooth; close/open; **undo/redo inverts every action**
  and returns to the exact prior state.
- `EditHitTestTest` — anchor/handle/segment hit selection at varied zoom levels.

Run: `./gradlew :app:testDebugUnitTest --tests "com.aichat.sandbox.*.edit.*"
--console=plain`. (Note the known-preexisting Android-`Color`/`Log` "not mocked"
failures in `NoteSvg/VectorDrawable/AiService` tests — unrelated; suite is green
if those are the only failures.)

**On-device manual verification:** open an imported icon → Edit → draw a closed
4-anchor shape with one smooth corner → move an anchor onto the grid (snap
engages) → undo/redo → exit → confirm it returns as a new version and exports to
VectorDrawable + SVG that re-import identically.

---

## Sequencing within the phase
1. Phase 0 model + converters + round-trip tests (no UI) — de-risks everything.
2. Reducer + actions + reducer tests (no UI) — the edit algebra, fully testable.
3. Canvas rendering + direct-select drag (reuse viewport/preview).
4. Pen tool + snapping + close-subpath.
5. Insert/delete anchor, corner↔smooth, undo/redo UI.
6. Tune-Up entry wiring + return-as-version + export verification.

## Risks
- **Round-trip fidelity** is the main correctness risk → mitigated by the
  pristine-path passthrough + tolerance round-trip tests landing first (step 1).
- **Curve-preserving anchor insert** needs a de Casteljau split (standard, pure
  math) — covered by a dedicated reducer test.
- **Hit-test ergonomics at zoom** → tolerance is expressed in screen px and
  divided by `viewport.scale` (same approach `VectorPreviewCanvas` uses for
  constant-width highlights).
