# Phase 6 — Artist toolbox (Concepts lane)

> The visible upgrade from "note app with a pen" to a real vector drawing tool. Adds selection handles, shape tools, snapping, layers, brush customization with named presets, brush textures, image insert, and SVG export.

Parent plan: [`ARTIST_CANVAS_PLAN.md`](./ARTIST_CANVAS_PLAN.md).

Sequencing inside the phase is deliberate — 6.4 (layers) touches every render path, so we land 6.1–6.3 first to establish the selection + shape primitives the layer panel will reference. 6.5 / 6.6 ride on top of the existing tool palette. 6.7 / 6.8 are largely independent.

## Sub-phase 6.1 — Selection handles overlay

### Scope

Lasso selection exists (`LassoController`, `EditorAction.TransformItems`) but the user drags an invisible bounding box — no handles, no rotate gesture, no live preview. This sub-phase adds a Compose overlay over the selection bounds with eight scale handles, a rotate handle above the top edge, and live preview while dragging.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/SelectionHandlesOverlay.kt` — Composable that takes `selectionWorldBounds`, the current `Matrix`, and the `ViewportController`, and renders eight scale corners + one rotate handle in screen space. Emits gesture deltas back to the ViewModel.
- Unit tests for the corner-to-matrix math (no Android deps in the math).

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteEditorScreen.kt` — overlay slotted above `DrawingSurface` inside the canvas `Box`.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteEditorViewModel.kt` — gestures call into an existing `updateSelectionMatrix(...)` path (already used by `TransformItems`); add a `commitSelectionMatrix()` that bakes the matrix into a single `EditorAction.TransformItems` on gesture end.

### Step-by-step

1. Compute screen-space corners from `selectionWorldBounds` × current matrix × viewport transform every recomposition.
2. Render eight `Box` handles (8 dp circles) plus a rotate handle 24 dp above the top-center edge. Hit-target each at 32 dp.
3. `pointerInput` per handle:
   - Corner drag → uniform scale around opposite corner; Shift-equivalent (two-finger? long-press?) for free scale. On phones we default uniform; free-scale is an explicit toggle later.
   - Edge drag → 1-D scale.
   - Rotate handle → rotate around bounds center.
4. Update the live matrix during drag (Compose state, immediate visual feedback). On `ACTION_UP`, ViewModel bakes the final matrix into one `EditorAction.TransformItems` (existing).
5. Tap outside selection or press Esc / back → clear selection.

### Definition of done

- All eight scale handles + rotate handle visible and draggable.
- Live preview during drag matches the final committed transform pixel-for-pixel.
- One undo entry per gesture.
- Selection survives viewport pan/zoom (handles re-anchor).
- Hit-targets work with both finger and S-Pen.

### Non-goals

- Skew handles (deferred).
- Per-item handles (the overlay always operates on the whole selection).
- Snapping during transform — that's 6.3.

### Risks

- **Coordinate space confusion.** Handles render in screen space, bounds live in world space, the transform matrix multiplies world geometry. Pin the conventions in a header comment in `SelectionHandlesOverlay.kt` and add a regression test that pans + zooms + transforms and asserts the handle screen positions.

---

## Sub-phase 6.2 — Shape tools

### Scope

Add line, rectangle, ellipse, arrow, and polygon tools. Shapes are a new `NoteItem.kind = "shape"` with their own compact codec. They participate in selection / transform / layers / export like strokes.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/Shape.kt` — sealed class `Shape { Line, Rect, Ellipse, Arrow, Polygon(points) }`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ShapeCodec.kt` — `encode(Shape)` / `decode(bytes)`. Little-endian, tagged: `[type:u8][fields...]`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ShapeRenderer.kt` — `drawShape(canvas, shape, paint)`.

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/Tool.kt` — add `LINE`, `RECT`, `ELLIPSE`, `ARROW`, `POLYGON`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ToolPalette.kt` — second-row drawer for shape tools (icons + same color/width controls).
- `DrawingSurface.kt` — on a shape tool, `ACTION_DOWN` sets `startPoint`, `ACTION_MOVE` updates the rubber band, `ACTION_UP` commits a `NoteItem(kind="shape")`.
- `StrokeRenderer.kt` (or the dispatcher above it) — route `kind="shape"` to `ShapeRenderer`.
- `NoteRasterizer.kt`, AI rasterizer, thumbnail path, SVG export (6.8) — all consume the new dispatcher.
- `HitTest.kt` — exact hit-test per shape type (line: distance-to-segment; rect: edge distance + interior; ellipse: parametric; polygon: ray cast).

### Step-by-step

1. Define the codec wire format up front so renderer / hit-test / export agree:
   ```
   type 0x01 Line:    x0 y0 x1 y1
   type 0x02 Rect:    x0 y0 x1 y1 cornerRadius
   type 0x03 Ellipse: cx cy rx ry rotation
   type 0x04 Arrow:   x0 y0 x1 y1 headSize
   type 0x05 Polygon: count, [x y]*count, closed:u8
   ```
2. `ShapeRenderer` reads `NoteItem.colorArgb`, `baseWidthPx` (stroke width), and an optional `fillArgb` (new field — added as alpha channel in a second color word at end of payload; 0 = no fill).
3. Polygon tool: tap to drop vertices, double-tap to finish, long-press on first vertex to close.
4. Hit-test: `HitTest.shapeContainsPoint(shape, p)` + `HitTest.shapeIntersectsPolygon(shape, lasso)`.
5. Wire shapes through every place that walks `NoteItem` — search for `kind == "stroke"` and switch to a dispatch.

### Definition of done

- All five tools draw with live rubber-band preview.
- Shapes are selectable, transformable, erasable.
- Shapes export correctly in PNG (existing), PDF (existing), and SVG (Phase 6.8).
- A polygon hit-tests correctly inside / outside / on-edge.
- Existing notes (strokes only) unchanged.

### Non-goals

- Bezier curves with control points (Concepts has this; we're deferring).
- Fill patterns / gradients — solid fill only.
- Constrained drawing (square via Shift, perfect circle) — that's snapping, 6.3.

### Risks

- **`kind` dispatch grows everywhere.** Every render-walker and exporter needs the branch. Mitigation: introduce `NoteItemRenderer` interface with one impl per kind and a registry in DI; all walkers go through the registry.

---

## Sub-phase 6.3 — Snapping system

### Scope

Angle snap (drawing a line at 0° / 15° / 30° / 45° / 90°), grid snap (snap endpoints to the background grid when one is set), and stroke-to-stroke snap (snap an endpoint to an existing stroke's endpoint within 12 px).

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/Snap.kt` — pure functions: `snapAngle(start, end, step)`, `snapToGrid(point, spacing)`, `snapToEndpoints(point, candidates, radiusPx)`.
- Unit tests covering each.

Modified:
- `DrawingSurface.kt` — shape tools and selection drag run their candidate end-point through `Snap` before drawing/committing.
- `SelectionHandlesOverlay.kt` — corner / edge drags run through snap too.
- New top-bar toggle (or palette toggle) `Snap on/off` — persisted per note in `Note.snapMask` (TINYINT bitmask): bit 0 = angle, bit 1 = grid, bit 2 = endpoint.

### Step-by-step

1. Implement the three pure functions. Each returns either the snapped point + a "snapped: true" flag, or the original.
2. Visual feedback: when a snap engages, draw a small magenta dot at the snap target on the front buffer, decay over 200 ms.
3. Add `Note.snapMask` (default all-on) with a `MIGRATION_X_Y`-style additive column bump only if it doesn't fit elsewhere — prefer storing the toggle in `Preferences DataStore` if it's a global pref. **Decision: global pref**, not per-note, to avoid another schema bump in this phase.
4. Toggle UI: small chip in the palette that shows on for the active snap modes (e.g. `⟂ 15° ▦`).

### Definition of done

- Drawing a near-horizontal line snaps to 0° when within 5° of horizontal.
- With a dot-grid background, endpoint snaps land on grid intersections within 8 px.
- Endpoint-to-endpoint snap visibly latches with the magenta dot.
- Toggling snap off restores raw input.

### Non-goals

- Snap to midpoints / centers (defer).
- Smart-guide alignment lines (defer; Concepts doesn't really do these).

### Risks

- **Snap thresholds are subjective.** Pin the constants at the top of `Snap.kt` and expose them in a debug menu for tuning rather than guessing.

---

## Sub-phase 6.4 — Layers panel

### Scope

Replace the implicit z-band hack (`HIGHLIGHTER_Z_BASE` negative, others positive) with a real layers system. Each note has 1..N layers. Items belong to a layer. The right-edge panel shows layers with visibility / lock / opacity / drag-to-reorder.

### Schema

- New table `note_layers(id, noteId, name, opacityPercent, visible, locked, ordinal)`.
- `NoteItem` adds `layerId` (FK, nullable; null = default layer).

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/NoteLayer.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/NoteLayerDao.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/LayersPanel.kt` — slide-in from the right (shares the right-edge sheet with the AI side sheet via a small tab switcher).
- `MIGRATION_6_7` in `data/local/Migrations.kt`.

Modified:
- `AppDatabase.kt` — bump version to 7, register migration.
- `NoteEditorViewModel.kt` — layer CRUD, "current layer" selection that new strokes / shapes inherit.
- `DrawingSurface.kt` — render walks items in `(layer.ordinal, layer.zIndex)` order. Items on hidden / locked layers are skipped (locked: rendered but not hit-tested).
- `Migrations` — `MIGRATION_6_7` creates `note_layers`, adds `layerId` column to `note_items`, and inserts a "Highlights" + "Ink" layer per existing note, assigning each existing item to whichever layer matches its tool.

### Step-by-step

1. Write the migration first (the riskiest part). For each note:
   - Insert `(noteId, "Ink", 100, visible=1, locked=0, ordinal=0)`.
   - Insert `(noteId, "Highlights", 100, visible=1, locked=0, ordinal=-1)` only if any items have `tool="highlighter"`.
   - Update `note_items.layerId` based on tool.
2. ViewModel exposes `layers: StateFlow<List<NoteLayer>>` and `currentLayerId: StateFlow<String>`.
3. `LayersPanel` shows layers top-to-bottom in render order (top of list = renders on top). Each row: visibility toggle, lock toggle, drag-handle, name (double-tap to rename), opacity slider, overflow (delete, duplicate).
4. Reorder writes new `ordinal` values transactionally.
5. New `EditorAction` variants: `AddLayer`, `RemoveLayer(layer, items)`, `RenameLayer`, `SetLayerProps`, `MoveItemsBetweenLayers`. All undoable.
6. The legacy `HIGHLIGHTER_Z_BASE` constant is retired — z-index inside a layer is purely the order strokes were drawn.

### Definition of done

- Existing notes open with auto-generated layers and visually unchanged rendering.
- New strokes land on the currently-selected layer.
- Hiding a layer hides its items immediately; locking blocks selection + erase on its items.
- Reordering layers reorders rendering.
- Layer opacity multiplies into every render path (canvas, thumbnail, AI rasterizer, exports).
- Single migration run on a 100-note fixture completes < 1 s.

### Non-goals

- Layer masks / clipping.
- Per-layer blend modes beyond opacity (defer).
- Layer folders / groups (defer).

### Risks

- **Migration touches every existing note.** Run it inside the migration callback in a single transaction; assert row counts before/after in an instrumented test.
- **Render order ties.** Same layer + same `zIndex` happens on bulk paste. Tie-break on `noteItem.id` lexicographically to keep ordering deterministic.

---

## Sub-phase 6.5 — Brush customization + presets

### Scope

Replace the single per-tool width slider with a Brush sheet (collapsible) exposing width, opacity, taper start, taper end, jitter, pressure-curve preview. Saved combinations land in a `brush_presets` table; the palette shows preset chips above the tool tabs.

### Schema

New table `brush_presets(id, ownerScope, name, tool, colorArgb, baseWidthPx, opacity, taperStart, taperEnd, jitter, pressureCurveId, textureId)`.

`ownerScope` ∈ `"app" | "user"`. App-scope rows are seeded at install / migration and not user-editable; cloning produces a user-scope copy.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/BrushPreset.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/BrushPresetDao.kt`.
- `app/src/main/java/com/aichat/sandbox/data/repository/BrushPresetRepository.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/BrushSheet.kt`.
- `MIGRATION_7_8` — creates `brush_presets`, seeds 18 app-scope presets (6 colors × 3 tools).

Modified:
- `AppDatabase.kt` — version 8.
- `ToolDynamics.kt` (from 5.1) — accepts a `BrushPreset` instead of a raw `baseWidthPx`. Taper / jitter feed into the segment style.
- `ToolPaletteState.kt` — current tool now resolves to a `BrushPreset`; the legacy color + width fields fall out (preserved in `BrushPreset`).
- `NoteEditorViewModel.kt` — exposes presets, current preset, "Save as preset…" action.

### Step-by-step

1. Define the preset schema. Pressure curve = enum (`LINEAR`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`); `pressureCurveId` is the enum's string name.
2. Taper start/end are 0..1; multiply the per-segment width down for the first/last N% of the stroke.
3. Jitter is 0..1 standard deviation in px added to the per-segment width (deterministic from sample index so the same stroke renders the same).
4. `BrushSheet`: sliders + a small live preview canvas showing how a sample stroke looks with current settings.
5. Long-press a tool tab → "Save as preset…" dialog with name input → writes a user-scope row.
6. Preset chips above the tool tabs scroll horizontally; tap = apply.

### Definition of done

- 18 seeded presets show on first launch.
- "Save as preset…" round-trips name + all sliders.
- Switching presets is instant (no re-render of past strokes).
- Pressure curve preview updates live as you adjust the slider.
- Existing notes render identically (default preset matches old `baseWidthPx` behavior).

### Non-goals

- Import / export `.brushset` files (defer).
- Pressure curve arbitrary point editing (defer).

### Risks

- **Preset matrix explodes the test surface.** Snapshot-test each app-scope preset against a golden bitmap; if any drifts, fail CI.

---

## Sub-phase 6.6 — Brush textures

### Scope

Ship four textures (smooth, charcoal, watercolor, marker). Strokes can reference a `textureId` via the preset; renderer applies a `BitmapShader` with the appropriate tile mode.

### Files

New:
- `app/src/main/res/drawable-nodpi/tex_charcoal.webp`, `tex_watercolor.webp`, `tex_marker.webp` (smooth = no shader).
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/TextureRegistry.kt` — `BitmapShader` cache keyed by `textureId`, single-decode lazy.

Modified:
- `StrokeRenderer.kt` — pulls shader from `TextureRegistry` and applies it on the paint when `textureId != "smooth"`.
- App-scope presets are updated to reference textures where appropriate (charcoal pencil preset, watercolor highlighter preset, marker preset, etc.).

### Step-by-step

1. Source / commission small (~256×256) seamless texture tiles. Greyscale alpha-channel data — color comes from the paint.
2. `TextureRegistry.get(textureId): BitmapShader?` — decodes once into a `Bitmap`, wraps in `BitmapShader(TileMode.REPEAT, TileMode.REPEAT)`, caches.
3. Stroke renderer combines: `paint.shader = textureShader; paint.colorFilter = PorterDuffColorFilter(itemColor, MULTIPLY)`.
4. Test: draw the same stroke with each texture and snapshot.

### Definition of done

- Each texture renders distinctly.
- Switching texture on a preset doesn't require re-saving existing items (display only).
- No measurable latency regression vs. smooth path (frame time within 1 ms on 50-stroke canvas).

### Non-goals

- User-imported textures.
- Texture rotation / scale controls.

### Risks

- **Shader memory.** Each `BitmapShader` ties up its tile bitmap. Four textures × one device == fine. Bigger sets need an LRU.

---

## Sub-phase 6.7 — Image insert

### Scope

Insert images (gallery, paste, drag-drop from a chat) as a new `NoteItem.kind="image"`. Images participate in selection / transform / layers / export. Originals stored under `filesDir/note-images/`.

### Schema

Bump to version 9. `NoteItem.kind` gains `"image"`. No new column — payload bytes are an `ImageItemCodec`-formatted blob:
```
[version:u8][pathLength:u16][path:utf8][naturalWidth:f][naturalHeight:f][cropMinX:f][cropMinY:f][cropMaxX:f][cropMaxY:f][rotation:f]
```

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ImageItemCodec.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/ImageRenderer.kt`.
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteImageStore.kt` — copy-from-Uri into `filesDir/note-images/<uuid>.<ext>`, return the relative path.

Modified:
- `NoteEditorScreen.kt` — `PhotoPicker` launcher + paste handler (`ClipboardManager.primaryClip` listener) + insertion at viewport center.
- `DrawingSurface.kt` / dispatcher — render images via `ImageRenderer`.
- Hit-test, transform, export — all walk through the dispatcher.

### Step-by-step

1. Wire `ActivityResultContracts.PickVisualMedia` to a "+ image" button in the palette.
2. On pick: copy bytes to `filesDir/note-images/`, decode to get natural dimensions, create a new `NoteItem` sized to fit viewport.
3. Paste handler reads `ClipDescription` for `MIMETYPE_TEXT_URILIST` / image and reuses the same code path.
4. Renderer: decode bitmap lazily into an LRU keyed by path (cap at 64 MB total). On viewport / scale change, only re-blit, don't re-decode.
5. Delete a note → delete its image files (cascade in the repository, not in DB).

### Definition of done

- Insert from gallery, paste from clipboard, both work.
- Image is selectable / transformable like any item.
- Notes with images export to PNG, PDF, SVG (PNG-embedded `<image>` in SVG).
- Deleting a note removes its image files; orphan sweep on app start cleans up anything left.
- LRU cap respected on a 30-image note.

### Non-goals

- Per-image filters (brightness / contrast).
- Vector tracing of an image (defer; obvious AI extension).
- Live resize from external sources (e.g. drag from another app).

### Risks

- **Storage bloat.** Users insert massive photos. Mitigation: on insert, if longest edge > 4096 px, resize to 4096 before persisting; keep aspect.

---

## Sub-phase 6.8 — SVG export + Phase 6 verification

### Scope

Add SVG to the existing PNG / PDF export menu. SVG path strings are generated from `StrokeCodec` samples; shapes map to native SVG primitives; images embed as base64 `<image href>`.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteSvgExporter.kt`.
- Unit tests with golden SVG strings for a fixture note.

Modified:
- `NoteEditorScreen.kt` — overflow menu adds "Export as SVG".
- The existing `FileProvider` from `STYLUS_NOTES_PHASE_4.md` ships the file.

### Step-by-step

1. Iterate items in `(layer.ordinal, zIndex)` order, emit `<g layer="…" opacity="…">` per layer.
2. Strokes → `<path d="M x y Q x y x y ...">` rebuilding the quadratic-Bezier smoothing that the renderer uses. Stroke width: use the **mean** of per-segment widths derived from `ToolDynamics` (SVG `path` is single-width). Document this lossy step.
3. Shapes → `<line>`, `<rect rx>`, `<ellipse>`, `<polyline>`, `<polygon>`.
4. Images → `<image href="data:image/png;base64,…" x y width height>`.
5. Add a regression test: round-trip a fixture note → SVG → re-render via Compose `androidx.compose.ui.graphics` or a JVM SVG lib, hash the resulting bitmap.

### Definition of done

- Export of a 50-item note opens in Inkscape and Figma with paths, shapes, images intact and visually close to the in-app render.
- Layers are SVG groups with the right ordering.
- Images embed inline (no broken `href`).

### Verification matrix (Samsung S25 Ultra, S-Pen)

1. Selection handles: scale / rotate / translate produces one undo entry each.
2. Lasso select two strokes across two layers → transform behaves correctly.
3. Shape tools: line / rect / ellipse / arrow / polygon draw, select, transform, erase.
4. Snap: angle, grid, endpoint each engage and disengage at the documented thresholds.
5. Create new layer → draw on it → hide → strokes disappear; lock → eraser cannot remove.
6. Reorder layers → render order changes immediately.
7. Save / apply brush preset round-trips all sliders.
8. Each of the 4 textures looks distinct and renders without lag on a 100-stroke canvas.
9. Insert image from gallery → selectable, transformable.
10. Paste image from clipboard.
11. Note with 5 images survives kill/reopen.
12. Export 30-item, 3-layer, 2-image note to SVG → opens in Inkscape with structure preserved.
13. Existing pre-Phase-6 notes still render and edit correctly.
14. PNG / PDF exports unaffected.

### Definition of done

- All 14 items pass on real hardware.
- Update **Status** in `ARTIST_CANVAS_PLAN.md`.
