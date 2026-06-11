# Phase 10 — Shared vector foundations

> Multi-object editing table stakes that both the whiteboard lane (Phase 11) and the vector lane (Phases 12–13) sit on: surfaced fills, dashed/dotted shape outlines, flat grouping, alignment/distribution, and z-order controls. Everything mutates through `EditorAction.CompositeEdit`, so undo/redo and the persisted undo log work without codec schema changes.

## Sub-phase 10.1 — `ItemTransformer` + alignment/z-order math

### Scope

Extract the per-kind payload transform out of `EditorAction.TransformItems` into a shared pure object, and add the pure math that 10.5 builds on. No behaviour change.

### Files

- `ui/components/notes/ItemTransformer.kt` — **new**: `transform(item, matrix3x2): NoteItem` for stroke/text/shape/image; unknown kinds pass through. `TransformItems` delegates to it.
- `ui/components/notes/AlignmentMath.kt` — **new**: `align(entries, AlignEdge): Map<id, matrix>` (LEFT/CENTER_H/RIGHT/TOP/CENTER_V/BOTTOM) and `distribute(entries, Axis)` (equal gaps, outermost items fixed, ≥3 entries). Entries are `(id, [minX,minY,maxX,maxY])`; already-in-place items are omitted from the result.
- `ui/components/notes/ZOrderMath.kt` — **new**: `reorder(entries, selected, Op)` where `Op` ∈ front/forward/backward/back. Works per z band and **reuses the band's existing zIndex values as the slot set**, so highlighters can never climb above the ink tier and the VM's z counters stay valid.

### Definition of done

- `EditorActionCodecTest` and `UndoRedoTest` stay green (transform refactor is invisible).
- `ItemTransformerTest`, `AlignmentMathTest`, `ZOrderMathTest` cover round-trips, every edge/axis/op, and the no-op cases (single-item align, <3 distribute, forward-at-top).

## Sub-phase 10.2 — Fill surfacing

### Scope

`ShapeCodec` has carried a trailing `fillArgb:i32` since Phase 6.2 and `ShapeRenderer`/`NoteSvgExporter` already honour it — but nothing ever set it. Surface it: palette state for new shapes, restyle for existing ones.

### Files

- `ui/components/notes/ToolPaletteState.kt` — `shapeFillEnabled` / `shapeFillColor` / `shapeStrokeStyle` (+ `setFillEnabled` / `setFillColor` / `setStrokeStyle`; property-setter JVM names are taken, hence the short names), `activeShapeFillArgb()`, `restore(...)` extended with three nullable params.
- `data/notes/ToolPalettePrefsStore.kt` — three new keys; nulls mean "never saved" so stale prefs degrade to defaults.
- `ui/components/notes/DrawingSurface.kt` — `setToolConfig` gains `shapeFillArgb` + `shapeStrokeStyle`; `commitShape` encodes them; the rubber-band preview matches. Both are **gated on `paletteTool.isShape`** because the frame tool reuses the rect rubber-band state and must stay unfilled.
- `ui/components/notes/ToolPalette.kt` — `ShapeStyleRow` under the shape config: "Fill" chip, fill swatch (opens the picker targeting the fill slot), Solid/Dashed/Dotted chips.
- `ui/screens/notes/NoteEditorViewModel.kt` — `openShapeFillColorPicker()` + `colorPickerTargetsShapeFill` routing inside `confirmColorPick`; `setSelectionFill(Int?)` / `setSelectionStrokeStyle(Int)` restyle selected shapes via one `CompositeEdit` (non-shape items untouched, byte-identical re-encodes skipped).
- `ui/screens/notes/SelectionOverlay.kt` — "Style" popover (no-fill, six translucent fill swatches, line styles) shown when the selection contains shapes.

### Definition of done

- Draw a filled dashed rect → survives save/reload, transform, duplicate, undo/redo.
- Restyling an existing shape is one undo entry; undo restores the payload byte-identical.

## Sub-phase 10.3 — `ShapeCodec` stroke-style byte

### Schema

```
[type:u8] <type-specific fields…> [fillArgb:i32] [strokeStyle:u8]?
strokeStyle: 0=solid (default), 1=dashed, 2=dotted
```

Decode reads the byte only `if (buf.hasRemaining())` — every pre-Phase-10 payload decodes as solid. **No DB migration, no `Note.schemaVersion` bump.** This trailing-optional-append is the convention for all future payload fields (PathCodec in 12.1 must follow it).

### Files

- `ui/components/notes/ShapeCodec.kt` — encode/decode + `DecodedShape.strokeStyle`, `STROKE_STYLE_*` constants.
- `ui/components/notes/ShapeRenderer.kt` — `configurePaint` maps the style to a width-scaled `DashPathEffect` (dash = 3w/2w, dot = 0.1w/2w + round cap). Dashed/dotted straight lines render through a `Path` (`drawLine` ignores pathEffect on some HW pipelines). Fill passes and arrowheads null the effect so dash never slices a fill.
- Pass-through at every shape re-encode site: `ItemTransformer`, `NoteEditorViewModel.rebuildStampItem` / `duplicate`, `EditPreviewController.transformItem`.
- `data/notes/NoteSvgExporter.kt` — `stroke-dasharray` mirroring the renderer factors (exposed as `ShapeRenderer.DASH_*`/`DOT_*` constants).

### Non-goals

- VectorDrawable dash — the format has no dash support; that export renders solid (documented limitation; SVG is the faithful vector export).
- Dash on ink strokes — stroke items keep their pressure-based variable-width rendering.

## Sub-phase 10.4 — Grouping

### Schema

- `note_items.groupId TEXT NULL` — `MIGRATION_18_19`, DB **v19** (`app/schemas/.../19.json` committed). No index; group lookups run against the open note's in-memory items.
- `EditorActionCodec` items gain optional `groupId` JSON — **no `SCHEMA_VERSION` bump** (Gson ignores unknown fields in both directions; an old build reading a new log silently drops grouping, accepted downgrade).

### Semantics (locked)

- Flat, single-level groups; **no nesting**.
- A group may span layers; members on a **locked layer stay inert** (excluded from selection expansion, same rule as the lasso).
- Expansion happens **at selection time** (`expandSelectionToGroups` at the lasso commit in `onLassoCompleted`), so transform/delete/duplicate/copy stay group-agnostic.
- Duplicate/paste re-keys group membership (`remapGroupIds`): one fresh UUID per source group per batch.

### Files

- `data/model/NoteItem.kt` (+ equals/hashCode), `data/local/Migrations.kt`, `data/local/AppDatabase.kt`, `di/AppModule.kt`.
- `ui/screens/notes/GroupOps.kt` — **new**, pure: `expandSelectionToGroups`, `remapGroupIds`.
- `ui/screens/notes/NoteEditorViewModel.kt` — `groupSelection()` / `ungroupSelection()` (CompositeEdits), expansion at the lasso commit, `remapGroupIds` in `duplicateSelection` / `pasteFromClipboard`.
- `ui/screens/notes/SelectionOverlay.kt` — Group (≥2 selected) / Ungroup (any member grouped) buttons.

### Definition of done

- Group 3 items → lasso one member selects all → move moves all → undo restores `groupId = null` byte-identical.
- Group spanning a locked layer: locked member never joins the selection.
- Duplicate of a group moves as one unit, independent of the original.

## Sub-phase 10.5 — Align / distribute / z-order

### Files

- `ui/screens/notes/NoteEditorViewModel.kt` — `alignSelection(edge)` / `distributeSelection(axis)`: per-item bounds (`itemBounds`, shared with `recomputeSelectionBounds`) → `AlignmentMath` → `ItemTransformer` → one CompositeEdit; selection bounds recomputed after. `reorderSelection(op)`: items → `ZOrderMath.Entry` (band = highlighter vs ink by `tool`) → `copy(zIndex = …)` pairs → one CompositeEdit.
- `ui/screens/notes/SelectionOverlay.kt` — "Arrange" submenu: 6 align entries, 2 distribute (enabled at ≥3 selected), 4 z-order entries.

### Definition of done

- Align-left of 3 mixed items (stroke + shape + text) is **one** undo entry; distribute disabled below 3.
- Bring-to-front of a highlighter never lifts it above any pen stroke (band invariant, pinned by `ZOrderMathTest.highlighterStaysInItsBand`).

## Sub-phase 10.6 — Tests + docs

JVM-pure tests (no `android.graphics` / `android.util` — raw ARGB Int literals):

| Test | Pins |
| --- | --- |
| `ShapeCodecStrokeStyleTest` | round-trip 5 shapes × 3 styles; hand-built legacy bytes decode solid; transform preserves fill+style |
| `AlignmentMathTest` | every edge, distribute h/v with uneven sizes + overlap (negative gap), no-op guards |
| `ItemTransformerTest` | per-kind transform parity incl. inverse round-trip; unknown kind passes through |
| `ZOrderMathTest` | all 4 ops, band isolation, sparse-slot reuse, top/bottom no-ops |
| `GroupOpsTest` | expansion incl. locked filter + multi-group; remap freshness |
| `EditorActionCodecGroupTest` | groupId round-trip through CompositeEdit/AddItems; legacy JSON decodes null |

Plus this doc + the master plan.

## Sub-phase 10.7 — Phase 10 device verification

### Verification matrix (real hardware)

| # | Step | Expect |
| --- | --- | --- |
| 1 | Shape tool → Fill on, pick colour, Dashed → draw rect → close + reopen note | Fill + dash survive save/reload |
| 2 | Lasso an old (pre-10) shape → Style → fill swatch, then Dotted | Restyles apply; each is one undo step; undo twice restores original |
| 3 | Draw 3 items → Group → lasso-tap one member → drag | All three move together |
| 4 | Move one group member to a second layer, lock that layer → lasso the group | Locked member stays unselected and unmoved |
| 5 | Select 4 mixed items → Align left, then Distribute horizontally | Single undo entry each; geometry correct |
| 6 | Highlighter stroke + pen stroke overlapping → select highlighter → Bring to front | Highlighter still renders under the pen ink |
| 7 | Export SVG with a filled dashed ellipse → open in an external viewer | Fill colour + dash pattern visible |
| 8 | Export PNG + PDF of the same note | Raster output matches the on-canvas rendering |
| 9 | Upgrade install over a v18 database | Notes open; grouping works on new edits |

### Definition of done

- All rows pass on hardware; failures filed as fixes inside Phase 10 before Phase 11 starts.
