# Notes & Icon System — Roadmap

Working branch: `claude/notes-icon-system-improvements-2pc5hb`. This document
records what has shipped on that branch and the detailed plan for what
remains, so a fresh session can pick up without re-deriving the research.
Read `CLAUDE.md` first for build/SDK setup and the known test baseline.

## Status — shipped on this branch

| Phase | Feature | Key files |
|-------|---------|-----------|
| 15.1 | Pressure-preserving vector export: `StrokeOutliner` converts pressure-modulated strokes to closed filled outlines (perfect-freehand style, follows `ToolDynamics` exactly); opt-in `preservePressure` flag on both exporters + export dialogs. Also removed `android.graphics.Color` from the exporters (fixed 3 "not mocked" JVM test failures). | `ui/components/notes/StrokeOutliner.kt`, `data/notes/NoteSvgExporter.kt`, `NoteVectorDrawableExporter.kt`, `ui/screens/notes/ExportSvgDialog.kt` |
| 15.2 | "Outline ink" selection action: strokes → compact node-editable filled paths via `PathConversions.fromStrokeOutline` (outline → RDP → Schneider cubic fit). | `ui/components/notes/PathConversions.kt`, `NoteEditorViewModel.outlineSelectionStrokes`, `SelectionOverlay.kt` |
| 15.3 | Pixel-grid icon mode: snap-to-pixel for shape endpoints + pen anchors (`EditSnap.quantizeInBounds`, one 32-world graph cell per icon pixel), Material keyline overlay, live 24/48 dp preview chip, persisted toggle (on by default). | `ui/components/notes/DrawingSurface.kt`, `ui/screens/notes/IconLivePreview.kt`, `data/notes/ToolPalettePrefsStore.kt` |
| 15.4 | One-tap icon-set export: zip of VD XML at 24/48/108 dp + SVG + 512 px PNG. | `data/notes/NoteIconSetExporter.kt` |
| 16.1 | Multi-subpath path codec (v2 wire format + nonZero/evenOdd fill rule). Boolean ops return ONE payload with holes as subpaths — a subtracted donut punches through. Single-subpath/nonZero payloads still encode byte-identical v1 (hard compatibility rule). Node editing gated to single-subpath payloads. | `ui/components/notes/PathCodec.kt`, `PathBooleanBridge.kt`, `PathRenderer.kt`, `HitTest.kt`, both exporters, `data/notes/VectorCanvasJson.kt` |
| 16.2 | Vector import: `.xml`/`.svg` → editable icon note. `DocumentToNoteItems` composes group transforms, normalizes commands to absolute cubics (quad lift, spec arc→cubic), preserves holes/fill rules. Import chip in the Icons gallery. | `data/vector/notesbridge/DocumentToNoteItems.kt`, `ui/screens/icons/IconsListViewModel.kt`, `IconsListScreen.kt` |
| 16.3 | Icons gallery search over the existing `notes_ocr_fts` FTS4 index (title + OCR), `isIcon = 1` filter, no migration. | `data/local/NoteSearchDao.searchIcons`, `data/repository/NoteSearchRepository.kt` |
| 17.1 | Icon tags & variants: `note_tags` junction table (DB v20→21, cascade-deleted, indexed on `tag`), gallery tag-chip filter combined with FTS search (client-side id intersection), long-press action sheet (Edit tags / Duplicate as variant / Delete), `IconTags` JVM-pure tag normalization, `NoteRepository.duplicateNote` deep copy (items/layers/frames/tags, ids remapped). | `data/model/NoteTag.kt`, `data/local/NoteTagDao.kt`, `Migrations.MIGRATION_20_21`, `data/notes/IconTags.kt`, `ui/screens/icons/IconsList{ViewModel,Screen}.kt` |
| 17.2 | Multi-subpath node editing: lifted the 16.1 single-subpath gate. `PathNodeMath` ops take a trailing `subpath` index (default 0 → existing call sites/tests byte-identical) and rebuild one subpath via `PathPayload.withSubpath`; `nearestOnPath` scans every contour; `deleteAnchor` drops a sub-2-anchor contour and returns null only when the last empties. `PathNodeEditor` renders all subpaths, `(subpath, anchor)` selection, deletes the item when the final anchors go. | `ui/components/notes/PathNodeMath.kt`, `PathCodec.PathPayload.withSubpath`, `ui/screens/notes/PathNodeEditor.kt`, `NoteEditorViewModel.{selectionIsSinglePath,enterNodeEdit,deleteNodeEditItem}` |
| 17.3 | Structure-preserving export: optional `layers` list on `renderSvg` / VD `render`. SVG emits one `<g id="layer-N">` per layer (ordinal order, `opacity` baked, name as comment, hidden dropped, null-layer items in a `layer-default` group); VD emits `<group android:name>` baking layer opacity into path alpha (VD groups have no opacity). Empty list = byte-identical flat output. | `data/notes/NoteSvgExporter.kt`, `NoteVectorDrawableExporter.kt`, `NoteEditorViewModel` export calls |
| 17.5#3 | Local `merge_paths`: fold style-compatible paths into one multi-subpath payload (no clipping; holes kept). `PathMerge` (pure), `EditOp.MergePaths` + parser + `EditPreviewController` applier, `NoteEditorViewModel.mergeSelectionPaths` + selection-overlay "Merge" action. | `ui/components/notes/PathMerge.kt`, `EditProtocol.kt`, `EditOpsParser.kt`, `EditPreviewController.kt`, `SelectionOverlay.kt` |
| 17.5#1 | Style-matched icon generation: authoring edit-ops `add_path` / `add_shape` (the model creates new geometry, mirroring `VectorCanvasJson`'s anchor format) land via the existing edit-ops → `EditPreviewController` (`newItemNoteId`) → `CompositeEdit` plumbing. `NoteAiService` gains a generation branch (no raster, empty id space) whose system prompt embeds 2–3 gallery icons as style reference (`EditOpsParser.buildIconGenerateSystemMessage`); `NoteEditorViewModel.generateIcon` loads the references and an EDIT instruction on an empty icon artboard routes to generation. | `EditProtocol.kt`, `EditOpsParser.kt`, `EditPreviewController.kt`, `data/vector/notesbridge/EditOpToManualEdit.kt`, `AskRequest.kt`, `NoteAiService.kt`, `NoteEditorViewModel.kt` |
| 17.5#2 | Annotate-and-iterate "Make real" refine loop: select a sketch → vision raster + `ICON_REFINE_SYSTEM_MESSAGE` ask the model to redraw it as clean vector (`add_*` ops) → `EditPreviewController.simulate(authoredOffset=…)` places the result one sketch-width beside the original → user marks it up / re-prompts (footer text) and refines again. `IconQuickAction.MAKE_REAL` + `NoteEditorViewModel.refineSketch`; falls back to text-only authoring on non-vision models. | `EditOpsParser.kt`, `NoteAiService.kt`, `EditPreviewController.kt`, `AskRequest.kt`, `NoteEditorViewModel.kt`, `AiSideSheetState.kt` |

Pre-existing hold-to-snap shape recognition (sub-phase 11.3) and the stamp
library (10.x) already cover two items from the original brainstorm — do not
rebuild them.

## House conventions (follow these)

- **Phase numbering** in KDoc and commit subjects (next free: 17.x).
- **JVM-pure geometry**: codecs/exporters/converters must avoid
  `android.graphics.*`/`android.util.Log` in code paths unit tests exercise
  (use pure int math for colors: `(argb ushr 24) and 0xFF`).
- **Wire-format stability**: any codec change must keep existing payloads
  byte-identical on re-encode (see `PathCodec.encode`'s v1/v2 rule) — undo
  logs, stamps, and `contentEquals` no-op detection depend on it.
- **Test baseline**: `./gradlew :app:testDebugUnitTest` has exactly 2 known
  failures (`NoteVectorDrawableExporterTest.textIsSkippedAndCounted`,
  `NoteAiServiceTest.editModeMalformedReplyEmitsError` — Android "not
  mocked"). Anything else is a regression.
- One commit per feature, build + full test suite before each, push to the
  working branch.

## Remaining work, in recommended order

**Status (updated):** 17.1, 17.2, 17.3 and all of 17.5 (#1, #2, #3) have shipped
(see the table above). Still open: **17.4** (androidx.ink — *deferred*, see note
below) and the smaller follow-ons. The detailed plans below are kept for the
shipped items as a record.

> **17.4 deferral note:** the androidx.ink experiment's only meaningful
> verification is stroke *feel* + screenshot diffs on a real device/emulator,
> which a headless CI/cloud session can't produce. It also rewrites the wet-
> stroke path of the ~2400-line `DrawingSurface`. It should be picked up in an
> interactive session with a device in the loop, not shipped blind behind a
> flag. Prefer doing 17.5 #1/#2 and the smaller follow-ons first.

### 17.1 Icon tags & variants (Tier 2 remainder) — ✅ shipped

**Goal:** IconJar-style organization: tag icons, filter the gallery by tag,
and "duplicate as variant" (filled/outlined siblings).

**Schema** (DB version 20 → 21, pattern in `data/local/Migrations.kt` —
mirror `MIGRATION_19_20`):
- New table `note_tags(noteId TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(noteId, tag))`
  with an index on `tag`. A junction table (not a CSV column) keeps tag
  rename/delete and per-tag counts cheap and avoids touching the `notes`
  entity + its FTS triggers.
- New `@Entity NoteTag` + DAO (`observeTagsFor(noteId)`, `observeAllTags()`
  with counts, `setTags(noteId, tags)` as delete+insert in a transaction,
  `observeIconsWithTag(tag)`).

**UI:**
- Gallery: a horizontally scrollable row of tag chips (`StudioToolChip`,
  selected = filter active) under the search field; combines with FTS search
  (filter the search results client-side by tagged ids — result sets are
  ≤ 100).
- Tag editing: long-press dialog on a tile (extend the existing delete
  dialog into a small action sheet: Delete / Edit tags / Duplicate as
  variant) or a row in the editor's Icon menu section.
- Variant: `duplicateAsVariant(note)` in `IconsListViewModel` — copy note +
  items + frames with new ids (reuse `NoteDao.saveNoteWithLayers` +
  `saveFrames`), title suffix " — outlined", shared tag.

**Tests:** DAO migration test if the project has one (check
`app/src/androidTest`); otherwise JVM tests for tag-set logic + a manual
migration sanity pass. Effort: ~1 session.

### 17.2 Multi-subpath node editing (lift the 16.1 gate) — ✅ shipped

**Goal:** "Edit nodes" on boolean results and imported icons.

**Approach:** generalize the selection model in
`ui/screens/notes/PathNodeEditor.kt` and `ui/components/notes/PathNodeMath.kt`
from flat anchor indices to `(subpathIndex, anchorIndex)`:
- `PathNodeMath` functions gain a `subpath: Int` parameter; the five
  `payload.withSingleSubpath(...)` sites become "rebuild subpath N"
  (`payload.copy(subpaths = subpaths.toMutableList().also { it[n] = ... })`).
- `PathNodeEditor` renders anchors/handles for every subpath (iterate
  `payload.subpaths`, offset hit-testing by subpath), spine via
  `PathCodec.flattenAll`.
- Remove the `isSingleSubpath` gate in `NoteEditorViewModel`
  (`selectionIsSinglePath` / `enterNodeEdit`).

**Gotchas:** insert/delete on the *closing* segment per subpath; deleting a
subpath below 2 anchors should remove that subpath (and the item when none
remain); undo flows are already payload-snapshot-based so they need no
changes. Effort: ~1 session; pure-JVM `PathNodeMathTest` extensions cover
most of it.

### 17.3 Structure-preserving export (Tier 3 #9) — ✅ shipped

**Goal:** stop flattening layers/groups on export (documented Phase 6.4
deferral, noted in `NoteSvgExporter`'s KDoc).

- SVG: `renderSvg` takes the note's layers (already threaded everywhere
  else); emit one `<g id="layer-N" opacity="...">` per layer in ordinal
  order, items inside by zIndex; optionally nested `<g>` per `groupId`.
  Keep the current single-`<g>` output when the note has no layers, so
  existing wire-format tests stay green (add the layer list as an optional
  parameter defaulting to empty).
- VectorDrawable: `<group>` per note group (VD groups don't support
  opacity — document the approximation: bake layer opacity into colors or
  skip with a warning count).

Effort: ~half session. Tests: extend `NoteSvgExporterTest` with a 2-layer
fixture asserting two `<g>` elements + opacity attr.

### 17.4 androidx.ink adoption experiment (Tier 1 #2) — ⏸ deferred (needs device verification; see note at top of this section)

**Goal:** the single biggest stroke-feel upgrade — low-latency wet-stroke
rendering with brush dynamics. androidx.ink hit **1.0.0 stable Dec 17,
2025** (`ink-authoring-compose`, `ink-brush`, `ink-geometry`,
`ink-strokes`, `ink-storage`); pairs with
`androidx.input:input-motionprediction`.

**Scope it as an experiment behind a flag** (follow the `fingerDrawing`
pref pattern): `DrawingSurface` is load-bearing (~2400 lines).
- Phase A: replace only the *in-progress* (wet) stroke path — feed motion
  events to an ink `InProgressStrokesView`/authoring composable layered over
  the existing surface; on finish, convert the ink stroke's points back to
  `StrokeCodec` samples and run the existing commit path. Committed
  rendering, hit-testing, export all stay unchanged.
- Phase B (later): `ink-brush` `BrushFamily` mapped from `ToolDynamics`
  curves; `ink-geometry` for eraser/lasso hit-testing.

**Gotchas:** the existing one-frame `MotionEventPredictor` overlaps with
ink's prediction (disable one); pressure→width parity with `ToolDynamics`
must match or committed strokes will "pop" on pen-up; check minSdk 26
compatibility and the dependency footprint. Effort: 1–2 sessions for
Phase A. Verification is feel + screenshot tests, not JVM.

### 17.5 AI differentiators (Tier 4)

Foundations are in place (icons are editable paths; `VectorCanvasJson`
serializes subpaths; `EditOpsParser` is lenient).

1. ✅ **shipped** — Style-matched icon generation (Recraft pattern): see the
   17.5#1 row above. Authoring ops `add_path` / `add_shape` let the model
   create geometry from scratch; the generation system prompt embeds 2–3
   gallery icons as style reference, and the reply lands as items via the
   existing edit-ops → `CompositeEdit` plumbing.
2. ✅ **shipped** — Annotate-and-iterate "Make real" refine loop (tldraw
   pattern): see the 17.5#2 row above. Vision raster of the selected sketch +
   `add_*` authoring + a placement offset (`authoredOffset`) drop the cleaned
   vector beside the original; the footer text re-prompts for the iterate step.
3. ✅ **shipped** — Local `merge_paths` edit-op (see 17.5#3 row above). The
   broader "tidy" pass (one-tap simplify + snap-to-grid bundled with merge)
   is still open; `simplify` already exists as an op, so a tidy action would
   just compose simplify + `mergeSelectionPaths` + grid-snap over a selection.

All three 17.5 items are done. Remaining 17.5 follow-on: the bundled "tidy"
pass (#3 note above). Otherwise see 17.4 (deferred) and the smaller
follow-ons.

### Smaller follow-ons

- **Wire `WidthMode.OUTLINE_FILL`** in
  `data/vector/notesbridge/NoteVectorBridge.kt` (documented deferral; the
  geometry — `StrokeOutliner` / `VariableWidthOutliner` — already exists).
- **Nested groups** (Phase 10.4 limitation: flat groups only).
- **Import clip-path support** (parser warns today; either rasterize-clip or
  boolean-intersect the clip path against children using `PathBoolean`).
- **Stamp library tagging/search** (stamps exist; mirror 17.1's tag model).
