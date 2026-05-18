# Artist Canvas — Master Plan

> Follow-on to `STYLUS_NOTES_PLAN.md`. That plan delivered the stylus surface, AI side sheet, quick capture, and export. This plan turns the notes surface into a Concepts-style vector artist toolbox, adds a model-driven vector edit pipeline, and bolts on an optional Goodnotes-style paginated notebook mode for handwriting workflows.
>
> Open this doc at the start of every implementation session, find the next unchecked sub-phase in the tracker, open the linked phase doc, and execute it.

## Status

- **Current phase:** Phase 6 — foundation slice complete (6.1, 6.2, 6.3, 6.8); 6.4 / 6.5 / 6.6 / 6.7 still open
- **Next sub-phase:** 6.4 Layers panel (highest blocker for remaining sub-phases — touches every render path)
- **Last verified device pass:** n/a (5.5 + Phase 6 verification matrix pending)

## Phase index

| Phase | Focus | Breakdown doc | Sub-phases |
| --- | --- | --- | --- |
| 5 | Foundation polish (surface what we already have) | [`ARTIST_CANVAS_PHASE_5.md`](./ARTIST_CANVAS_PHASE_5.md) | 5 |
| 6 | Artist toolbox (Concepts lane) | [`ARTIST_CANVAS_PHASE_6.md`](./ARTIST_CANVAS_PHASE_6.md) | 8 |
| 7 | AI vector edit pipeline (vector JSON + PNG, structured edits) | [`ARTIST_CANVAS_PHASE_7.md`](./ARTIST_CANVAS_PHASE_7.md) | 6 |
| 8 | Canvas as project (frames, object library, favorites bar) | [`ARTIST_CANVAS_PHASE_8.md`](./ARTIST_CANVAS_PHASE_8.md) | 5 |
| 9 | Handwriting notebook mode (paginated, no AI study) | [`ARTIST_CANVAS_PHASE_9.md`](./ARTIST_CANVAS_PHASE_9.md) | 5 |

---

## Master implementation tracker

One sub-phase per PR. Mark a sub-phase complete only after its "Definition of done" is met **and** the build is green on this feature branch.

Legend: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked (note reason inline)

### Phase 5 — Foundation polish · [`details`](./ARTIST_CANVAS_PHASE_5.md)

- [x] **5.1** Pressure & tilt → width/opacity modulation in `DrawingSurface` segment renderer ([details](./ARTIST_CANVAS_PHASE_5.md#sub-phase-51--pressure--tilt--widthopacity-modulation))
- [x] **5.2** Persisted undo/redo across sessions (action log on `Note`) ([details](./ARTIST_CANVAS_PHASE_5.md#sub-phase-52--persisted-undoredo-across-sessions))
- [x] **5.3** Full color picker (HSL wheel + hex + recent colors, swatches stay as presets) ([details](./ARTIST_CANVAS_PHASE_5.md#sub-phase-53--full-color-picker))
- [x] **5.4** Zoom/pan UI affordances (zoom %, Fit, 100%, Center, optional minimap) ([details](./ARTIST_CANVAS_PHASE_5.md#sub-phase-54--zoompan-ui-affordances))
- [ ] **5.5** Phase 5 device verification ([details](./ARTIST_CANVAS_PHASE_5.md#sub-phase-55--phase-5-device-verification))

### Phase 6 — Artist toolbox · [`details`](./ARTIST_CANVAS_PHASE_6.md)

- [x] **6.1** Selection handles overlay (translate / scale / rotate with live preview) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-61--selection-handles-overlay))
- [x] **6.2** Shape tools — line, rectangle, ellipse, arrow, polygon — new `shape` item kind ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-62--shape-tools))
- [x] **6.3** Snapping system (angle, grid, to-other-strokes) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-63--snapping-system))
- [ ] **6.4** Layers panel UI (visibility, lock, reorder, opacity per layer) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-64--layers-panel))
- [ ] **6.5** Brush customization (per-tool sliders + named presets) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-65--brush-customization--presets))
- [ ] **6.6** Brush textures (smooth, charcoal, watercolor, marker) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-66--brush-textures))
- [ ] **6.7** Image insert (drag / paste / pick into canvas as `image` item) ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-67--image-insert))
- [x] **6.8** SVG export ([details](./ARTIST_CANVAS_PHASE_6.md#sub-phase-68--svg-export--phase-6-verification)) — Phase 6 verification matrix still pending real-hardware run

### Phase 7 — AI vector edit pipeline · [`details`](./ARTIST_CANVAS_PHASE_7.md)

- [ ] **7.1** Vector JSON serializer (`NoteItem[] → compact JSON the model can edit`) ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-71--vector-json-serializer))
- [ ] **7.2** Edit protocol — structured response format the model returns ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-72--edit-protocol))
- [ ] **7.3** Combined image + JSON request path in `NoteAiService` ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-73--combined-image--json-request))
- [ ] **7.4** Edit applier — parse response, dry-run diff preview, single undo entry ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-74--edit-applier))
- [ ] **7.5** Canned edit actions in lasso / Ask sheet (Clean up, Straighten, Auto-shape, Recolor, Continue) ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-75--canned-edit-actions))
- [ ] **7.6** AI edit safety + Phase 7 verification ([details](./ARTIST_CANVAS_PHASE_7.md#sub-phase-76--ai-edit-safety--phase-7-verification))

### Phase 8 — Canvas as project · [`details`](./ARTIST_CANVAS_PHASE_8.md)

- [ ] **8.1** Frame primitive — named rectangle within the infinite canvas ([details](./ARTIST_CANVAS_PHASE_8.md#sub-phase-81--frame-primitive))
- [ ] **8.2** Frame navigator (jump to / rename / reorder / per-frame thumbnail) ([details](./ARTIST_CANVAS_PHASE_8.md#sub-phase-82--frame-navigator))
- [ ] **8.3** Object library (long-press selection → save as stamp; drawer to drop back in) ([details](./ARTIST_CANVAS_PHASE_8.md#sub-phase-83--object-library))
- [ ] **8.4** Customizable favorites bar (saved tool + color + width combos) ([details](./ARTIST_CANVAS_PHASE_8.md#sub-phase-84--favorites-bar))
- [ ] **8.5** Phase 8 device verification ([details](./ARTIST_CANVAS_PHASE_8.md#sub-phase-85--phase-8-device-verification))

### Phase 9 — Handwriting notebook mode · [`details`](./ARTIST_CANVAS_PHASE_9.md)

- [ ] **9.1** Notebook entity + Notebooks list screen (separate from Notes list) ([details](./ARTIST_CANVAS_PHASE_9.md#sub-phase-91--notebook-entity--list-screen))
- [ ] **9.2** Paginated page view (fixed-size pages stacked vertically; reuses `DrawingSurface`) ([details](./ARTIST_CANVAS_PHASE_9.md#sub-phase-92--paginated-page-view))
- [ ] **9.3** Cross-note / cross-notebook OCR search ([details](./ARTIST_CANVAS_PHASE_9.md#sub-phase-93--cross-notebook-ocr-search))
- [ ] **9.4** Audio recording with synced ink playback (per-sample timestamps, stroke format bump) ([details](./ARTIST_CANVAS_PHASE_9.md#sub-phase-94--audio-synced-ink))
- [ ] **9.5** Phase 9 device verification ([details](./ARTIST_CANVAS_PHASE_9.md#sub-phase-95--phase-9-device-verification))

---

## Context

`STYLUS_NOTES_PLAN.md` shipped:

- A working `DrawingSurface` with front-buffered rendering, motion prediction, palm rejection, side-button eraser.
- Pen / highlighter / pencil / eraser (stroke + area) / lasso / text tools.
- Infinite pan-zoom canvas (`ViewportController`, 0.25× – 8×).
- Lasso selection with translate / scale / rotate via `EditorAction.TransformItems`.
- Session-only undo/redo (200-action cap).
- OCR (ML Kit Digital Ink) on save.
- AI side sheet that rasterizes the selection / whole note to PNG and sends it to vision-capable models; falls back to OCR text on non-vision models.
- PNG / PDF export, send-to-chat, pin-note-as-context.

What the audit surfaced as **gaps** for a real artist-tool experience:

1. **The infinite canvas is invisible.** No zoom indicator, no "Fit" / "100%", no minimap. Users hit pinch-zoom by accident and don't know it exists.
2. **Pressure and tilt are captured but discarded** at render time. Every stroke renders at `baseWidthPx`. The biggest "feels like a real pen" lever sits unused.
3. **Six fixed colors, one width slider, no opacity, no texture, no presets.** Concepts ships custom brushes; we ship none.
4. **No selection handles** — lasso exists but users drag a bounding box without visible affordances.
5. **No shape tools, no snapping, no layers, no image insert, no SVG export.**
6. **AI can read the canvas (as PNG) but can't edit it.** The model sees pixels with no way to address strokes back. The vector JSON is on disk but never leaves the device.
7. **Single page, no multi-page, no project structure, no object library.**
8. **Undo doesn't survive process death.**

This plan does **not** revisit the engine — `DrawingSurface`, `ViewportController`, `NoteItem`, `StrokeCodec`, `NoteRasterizer`, `NoteAiService` stay. The work is mostly:

- Lighting up data we already record (Phase 5).
- New tools and overlays on top of the existing item model (Phase 6).
- A new AI request shape that adds JSON alongside the existing PNG, and a typed response format (Phase 7).
- A new "frame" overlay on the same canvas, plus a sibling "notebook" view mode for paginated workflows (Phases 8, 9).

## Decisions (from the audit / brainstorm)

| Axis | Decision |
| --- | --- |
| Primary lane | Concepts-style **vector-native artist toolbox**, every mark editable forever. |
| Secondary lane | **Handwriting notebook mode** (Goodnotes-style paginated view) as an opt-in *alternative view* on the same data — students/professionals who want page-shaped notes. **No AI study features** (cross-notebook chat, flashcards, summaries) in scope. |
| Canvas shape | Stays a single infinite vector surface. Notebooks are a *paginated view* layered on top of frames (Phase 8 → Phase 9), not a separate engine. |
| AI direction | **Not agent-drawable.** The model is given the rendered PNG **and** the vector JSON of the current selection, and may return a typed `edit-ops` document that *modifies existing items only* (transform / recolor / replace / delete). No `add_stroke` from scratch. |
| Edit protocol | Structured JSON response, not function/tool calls. Lets us reuse the existing one-shot streaming pipeline through `NoteAiService`. |
| Edit safety | Every AI edit lands as a **dry-run preview** the user accepts or rejects; on accept it becomes a single `EditorAction` for undo. |
| Brushes | Per-tool sliders (width, opacity, taper, jitter, pressure curve, texture) + named presets stored in a new `brush_presets` table. |
| Layers | Real layer entity per note; existing z-band hack (highlighters negative, pen positive) maps to default "Highlights" + "Ink" layers on migration. |
| Frames vs. pages | Frames live in the infinite canvas (any rect, any aspect). Notebooks (Phase 9) impose a fixed page size and vertical scroll on top of frames. A notebook *is* a note whose frames are uniform and sequential. |
| Audio sync | Stroke payload bumps from `(x, y, p, tilt)` to `(x, y, p, tilt, t)` with a schema-version-gated migration. Existing strokes synthesize timestamps on read. |
| Build order | Polish first (5), then toolbox (6), then AI edits (7), then project structure (8), then notebook mode (9). Each phase ships independently. |

## Architecture overview

```
NotesListScreen   (existing)              NotebooksListScreen   (Phase 9)
    └── NoteEditorScreen ────────────────────────────┐
            │                                        │
            ├── DrawingSurface (existing)            └── NotebookPageScroller (Phase 9)
            │       ├── pen / highlighter / pencil          (vertical scroll of Frames
            │       ├── eraser / lasso / text                rendered as fixed pages,
            │       ├── shape (Phase 6.2)                    each hosting its own
            │       └── image (Phase 6.7)                    DrawingSurface viewport)
            │
            ├── BackgroundLayer (existing)
            ├── ToolPalette + brush sliders (Phase 6.5)
            ├── SelectionHandlesOverlay (Phase 6.1)
            ├── LayersPanel (Phase 6.4, slide-in from right)
            ├── FrameOverlay + FrameNavigator (Phase 8.1, 8.2)
            ├── FavoritesBar (Phase 8.4)
            ├── ZoomChrome (Phase 5.4)
            └── AiSideSheet (existing)
                    └── DryRunEditPreview (Phase 7.4)
```

## Data layer changes

This plan adds entities incrementally. Schema-version bumps are spelled out per phase to keep migrations small.

| Phase | Schema version | New / changed |
| --- | --- | --- |
| 5.2 | 5 → 6 | `Note.undoLogJson` (TEXT, nullable) — serialized `EditorAction` stack. |
| 6.4 | 6 → 7 | New `note_layers` table (id, noteId, name, opacity, visible, locked, ordinal). `NoteItem.layerId` (FK, nullable; null = default layer). |
| 6.5 | 7 → 8 | New `brush_presets` table (id, ownerScope ["app"\|"user"], name, tool, colorArgb, baseWidthPx, minPressure, maxPressure, taperStart, taperEnd, jitter, textureId, opacity). Seeded with the six current swatches × three tools. |
| 6.6 | (no schema bump) | Texture assets ship as raw resources; referenced by `textureId` (string). |
| 6.7 | 8 → 9 | `NoteItem.kind` gains `"image"`. Payload format documented in `ImageItemCodec`. Original bytes stored under `filesDir/note-images/` and referenced by relative path inside the payload. |
| 7.x | (no schema bump) | All AI edit state lives in-memory until the user accepts the preview, at which point it lands as standard `EditorAction`s. |
| 8.1 | 9 → 10 | New `note_frames` table (id, noteId, name, minX, minY, maxX, maxY, ordinal). |
| 9.1 | 10 → 11 | New `notebooks` table (id, title, pageStyle, pageWidth, pageHeight, createdAt, updatedAt). `Note.notebookId` (FK, nullable; null = standalone note). |
| 9.4 | 11 → 12 | New `note_audio` table (id, noteId, filePath, durationMs, recordedAt). **Stroke payload format bump:** v2 = `(x, y, p, tilt, t)`. `Note.schemaVersion` is the per-row gate; v1 strokes are read with synthesized timestamps. `MIGRATION_11_12` does **not** rewrite existing payloads. |

All migrations follow the additive pattern from `MIGRATION_3_4`: create new tables / columns; never `DROP` or rewrite existing rows.

## Rendering pipeline changes

`DrawingSurface.kt` is the hot path; we modify, not replace.

- **Phase 5.1**: per-segment width now reads `pressure` and `tilt` from the sample (`FloatArray[2]`, `[3]`) through a tool-specific curve. Pen uses pressure for width. Pencil uses pressure for opacity *and* tilt for width broadening (shading). Highlighter ignores both (constant). Curves are pure functions in a new `ToolDynamics` object so unit tests can pin the math without Android dependencies.
- **Phase 6.2**: shape items render via a new `ShapeRenderer` analogous to `StrokeRenderer`. Shapes serialize as `(kind, x0, y0, x1, y1, cornerRadius, fillArgb, strokeArgb, strokeWidth)`.
- **Phase 6.6**: stroke renderer optionally walks a `BitmapShader` (texture) and per-tool blend mode. Cached per-`textureId` to avoid re-decoding.
- **Phase 6.7**: image items render through a new `ImageRenderer` that decodes once into the scene-bitmap path; viewport transforms apply on top.
- **Phase 7.4**: dry-run edits are rendered into a separate translucent overlay on the front buffer (not committed to the scene bitmap) until the user accepts.

## UI layer changes

- **Top bar** keeps title / undo / redo / Ask / overflow. Phase 5.4 adds a zoom chip between Ask and the overflow menu.
- **Bottom palette** grows in 6.5 from a single width slider into a collapsible "Brush" sheet (width, opacity, taper start/end, jitter, pressure-curve preview). The fast-access row (current tool + 6 color swatches + width slider) stays for one-tap use.
- **Right edge**: AI side sheet (existing). Layers panel (Phase 6.4) docks to the same right edge with a small tab switcher.
- **Floating selection menu** (existing, currently shows Ask / Convert-to-text / Duplicate / Delete) grows in Phase 7.5 with `Clean up`, `Straighten`, `Auto-shape`, `Recolor`, `Continue`.
- **Frame navigator** (Phase 8.2) is a vertical strip on the left edge showing per-frame thumbnails; tap to fly the viewport to that frame.

## AI integration changes

`NoteAiService.kt` currently sends `[image, prompt]` (vision) or `[ocrText, prompt]` (non-vision). Phase 7 keeps both branches and adds:

- A new `AskRequest.mode = ASK | EDIT`. `ASK` is what we have today.
- `EDIT` mode rasterizes the selection **and** serializes the same items to vector JSON via `VectorCanvasJson` (Phase 7.1), and packs both into the multimodal message: the image as a `data:image/png` URI, the JSON inline in the prompt body inside a fenced block.
- The system message instructs the model to respond with **only** a fenced ` ```edit-ops ` JSON block (Phase 7.2 schema).
- `NoteAiService` parses the fenced block via `EditOpsParser` and emits `AiChunk.EditPreview(ops)` instead of `Delta` text.
- The side sheet renders the diff (Phase 7.4) with Accept / Reject buttons.

Non-vision models can still do `EDIT` — they get OCR text + vector JSON, no PNG. Auto-shape and Straighten work fine on JSON alone.

## Cross-cutting design decisions

Captured here so future sessions don't relitigate.

- **AI cannot create strokes from scratch.** The edit protocol (Phase 7.2) supports `transform`, `recolor`, `replace`, `delete`, `group`, `regroup`. It does **not** support `add` of arbitrary new strokes. "Auto-shape" works by *replacing* the user's freehand stroke with a clean shape item — the user drew the input. This is the agreed boundary for "not agent-drawable yet."
- **Every AI edit is one undo entry.** The applier (Phase 7.4) batches the entire ops list into a single `EditorAction.CompositeEdit` so one Ctrl-Z reverts the whole thing.
- **Frames are world-space rectangles, not viewports.** Two frames can overlap. They don't clip rendering; they only define exportable regions and notebook pages.
- **Notebook pages are frames with a fixed size.** A notebook just enforces uniform-size frames and renders them sequentially in a scrollable list. No separate engine; one source of truth.
- **Stroke payload v1 stays readable forever.** Phase 9.4 introduces v2 (with `t`). The decoder branches on `Note.schemaVersion`; v1 rows synthesize `t = i * uniformDt` at read time. We never bulk-rewrite v1 strokes.
- **Brush presets are tool-scoped.** A pen preset can't be applied as a highlighter. This is a deliberate constraint that keeps the brush dynamics matrix bounded.
- **Layer migration is automatic.** On first open after `MIGRATION_6_7`, existing notes get a "Highlights" layer (collects all `highlighter` strokes) and an "Ink" layer (everything else). Users can rename / split later.
- **High-risk sub-phases to front-load** in each phase doc:
  - 6.4 (layers + migration touches every render path)
  - 7.2 / 7.4 (edit protocol + applier — the bug surface)
  - 9.4 (stroke format bump — schema-version gating)

## Manifest changes

`app/src/main/AndroidManifest.xml`:

- **Phase 6.7**: declare `READ_MEDIA_IMAGES` (API 33+) for image insert; fall back to the photo picker (`ActivityResultContracts.PickVisualMedia`) which needs no permission on API 33+. On API 26–32, request `READ_EXTERNAL_STORAGE` only when the user actually picks from gallery (and prefer SAF where possible).
- **Phase 9.4**: declare `RECORD_AUDIO`. Request at recording start, not at app launch.
- No new intent filters, deep links, or activities beyond what `STYLUS_NOTES_PLAN.md` already shipped.

## Verification matrices

Each phase's closing sub-phase carries its full matrix:

- Phase 5 (8 items) — pressure feel, persisted undo across kills, color picker round-trip, zoom chrome.
- Phase 6 (14 items) — selection handles, shape geometry, snap correctness, layer visibility / lock, brush preset round-trip, image insert lifecycle, SVG export round-trip.
- Phase 7 (10 items) — vision-with-JSON request shape, non-vision JSON-only path, edit-ops parser fuzz cases, dry-run preview accept/reject, single undo entry per AI edit, malformed-response fallback.
- Phase 8 (10 items) — frame create/move/rename/delete, navigator jump, object library save / drop / delete, favorites bar slot persistence.
- Phase 9 (10 items) — notebook list, paginated scroll perf, cross-notebook OCR search recall, audio record / playback sync drift, stroke-format v1 read-back unchanged.

Update the **Status** block at the top of this doc each time a matrix passes.

## Out of scope (deliberately, even after all five phases)

- **Cloud sync.**
- **Real-time collaboration / live cursors** (tldraw's lane; needs a sync engine).
- **AI agent that draws from scratch.** Edit-only is the contract.
- **AI study features over notebooks** — no flashcards, no cross-notebook summaries, no quizzing. Explicitly excluded by user direction.
- **True segment-splitting eraser** (still erases whole strokes for partial overlap; vector path-cutting is its own project).
- **Vector PDF export** — PDF stays rasterized per page (SVG covers the vector case).
- **Vector path editing** (Bézier control point manipulation per stroke). Strokes are atomic; you can transform / recolor / replace them, but not push individual points around. Concepts has this; we're explicitly deferring.
- **3D / isometric guides.**
- **Animation / Procreate Dreams-style timeline.**
- **Audio-synced playback scrubbing UI** beyond a simple play/pause + position slider.
- **Custom font import for text items.**
- **Pencil air gestures (S Pen Bluetooth)** — same as `STYLUS_NOTES_PLAN.md`.

## How to use this document in a session

1. Open this file. Read the **Status** block.
2. Find the next unchecked sub-phase in the **Master implementation tracker**.
3. Open the linked phase doc; read that sub-phase's full spec (scope, files, step-by-step, definition of done, non-goals, risks).
4. Implement. One sub-phase, one PR.
5. When the definition of done is met and the build is green:
   - Flip the checkbox in the tracker from `[ ]` to `[x]`.
   - Update the **Status** block (next sub-phase, plus device-pass date if a verification matrix was run).
   - Commit this doc with the implementation PR or in a small follow-up.
6. If blocked, flip to `[!]` and note the reason inline in the tracker.
