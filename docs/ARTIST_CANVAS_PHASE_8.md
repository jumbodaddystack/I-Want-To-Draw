# Phase 8 — Canvas as project

> Turn one infinite note into a project. Frames carve the infinite world into named, exportable regions. A navigator strip lets you jump between frames. The object library lets you save selections as stamps and drop them into any note. The favorites bar replaces "tap-tap-tap to set tool + color + width" with one-tap presets.

Parent plan: [`ARTIST_CANVAS_PLAN.md`](./ARTIST_CANVAS_PLAN.md).

## Sub-phase 8.1 — Frame primitive

### Scope

A frame is a named rectangle in world space. It does not clip rendering — items continue to live in the global item list. Frames define exportable regions and are the substrate Phase 9 uses for notebook pages.

### Schema

Bump to version 10. New table:

```sql
CREATE TABLE note_frames (
  id TEXT PRIMARY KEY,
  noteId TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  minX REAL NOT NULL, minY REAL NOT NULL,
  maxX REAL NOT NULL, maxY REAL NOT NULL,
  ordinal INTEGER NOT NULL,
  createdAt INTEGER NOT NULL
);
CREATE INDEX idx_note_frames_noteId ON note_frames(noteId);
CREATE INDEX idx_note_frames_ordinal ON note_frames(noteId, ordinal);
```

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/NoteFrame.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/NoteFrameDao.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/FrameOverlay.kt` — renders frame rectangles in the canvas with a name label at the top-left corner.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/Tool.kt` — `FRAME` tool: drag a rectangle in world space to create a frame.
- `MIGRATION_9_10`.

Modified:
- `AppDatabase.kt` — version 10.
- `NoteEditorViewModel.kt` — frame CRUD, `currentFrameId: StateFlow<String?>` (highlight in navigator).
- `NoteRasterizer.kt` / `NoteSvgExporter.kt` — gain `forFrame(frameId)` overloads that bound the render to the frame's rect.
- `EditorAction.kt` — new variants `AddFrame`, `RemoveFrame`, `UpdateFrame`.

### Step-by-step

1. Frame tool behavior: drag = create. Tap an existing frame's corner / edge to resize. Tap inside = "current frame" (used by export + Phase 9).
2. Render frame outline at 1 px / device-pixel regardless of zoom (constant screen width). Name label uses a contrast background so it stays readable on any canvas color.
3. Right-click / long-press a frame → menu: Rename, Delete, Export this frame.
4. Item ownership: items are **not** scoped to a frame. A frame "contains" an item iff the item's bounding box intersects the frame. This is computed lazily for export.
5. Default: notes without any frame work exactly as before; frames are an additive concept.

### Definition of done

- Create / resize / rename / delete frames, all undoable.
- Export "this frame" produces a PNG / PDF / SVG bounded to the frame rect.
- Frames render at constant screen width across all zoom levels.
- Notes without frames behave unchanged.

### Non-goals

- Frame clipping (items don't get clipped to the frame — visual continuity is the point of an infinite canvas).
- Nested frames.

### Risks

- **Discoverability.** Users won't find frames unless promoted. Mitigation: add to the tool palette second row and surface in the export menu ("Export current frame" if one is active).

### Implementation notes

- `DrawingSurface.handleFrameToolEvent` reuses the shape-tool rubber-band state (`shapeStartX/Y/EndX/Y`, `shapeInProgress`) with `strokeTool` pinned to `Tool.RECT` so the existing `drawShapePreview(canvas)` path renders the in-flight frame as a rectangle. On `ACTION_UP` the world bounds fire through `frameDragListener`; a tap below `tapSlopPx` routes to `frameTapListener` so the editor can pick the topmost frame under the touch.
- Resize handles for existing frames were not surfaced in this slice — the planned interaction ("tap a corner / edge to resize") is deferred. Today the user can resize by deleting + re-drawing the frame, and the navigator's rename / delete handles the rest of the metadata.
- PDF export still routes through `NoteExporter.exportPdf` for the whole note. PNG and SVG honour `currentFrameId` via `sharePngForCurrentFrame` / `shareSvgForCurrentFrame`; the overflow menu shows the "Current frame" section only when `currentFrameId != null`.
- The undo path uses a new `EditorAction.FrameMutation(description, before, after)` variant. `applyTo` is a no-op on the item list; `NoteEditorViewModel.undo` / `redo` detect the variant and restore `_frames` directly. The codec round-trips frame state via `EditorActionCodec`'s new `TYPE_FRAME_MUTATION`.

---

## Sub-phase 8.2 — Frame navigator

### Scope

A left-edge vertical strip showing per-frame thumbnails in `ordinal` order. Tap = fly the viewport to that frame's bounds. Drag to reorder. Provides the "table of contents" for a multi-frame note.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/FrameNavigator.kt` — Compose `LazyColumn` of small thumbnails (96 dp wide).
- `app/src/main/java/com/aichat/sandbox/data/notes/FrameThumbnailRenderer.kt` — reuses `NoteRasterizer.forFrame`, caches by `(frameId, contentHash)`.

Modified:
- `NoteEditorScreen.kt` — slot navigator on the left edge with a toggle button (collapsed by default).
- `ViewportController.kt` — `flyTo(bounds, durationMs=300)` animates `(offsetX, offsetY, scale)` so the frame fills the viewport with a 24 dp margin.

### Step-by-step

1. Compute a content hash per frame from the IDs + updatedAt of items intersecting its rect. Invalidate cache on hash change.
2. Render thumbnails at 256 px max edge, downsampled.
3. Drag-to-reorder writes new `ordinal` values in a single transaction.
4. `flyTo` uses `animateFloatAsState` for offset and scale separately.

### Definition of done

- Toggle navigator open / closed.
- Thumbnails update within 500 ms of relevant edits.
- Fly-to animation is smooth (60 fps on Samsung S25 Ultra).
- Reorder persists across kill/reopen.

### Non-goals

- Frame folders / sections.
- Mini-thumbnails in the bottom palette (the navigator owns the affordance).

### Risks

- **Thumbnail churn on dense notes.** Throttle thumbnail regeneration to once per 800 ms per frame.

### Implementation notes

- `FrameThumbnailRenderer` caches a single bitmap per frame keyed on a 64-bit FNV-1a hash of `(frame.bounds, intersecting item ids, payload sizes, colour, width)`. Hash misses recycle the previous bitmap before rendering the new one to keep the working-set bounded; explicit `invalidate(frameId)` / `clear()` are exposed for the editor close path.
- `ViewportController.flyTo` is the one-shot teleport version of `fitToContent`. Smooth animated transitions are out of scope for this slice — the controller stays Compose-free so the JVM tests keep running. Future animation work can drive `setForAnimation(offsetX, offsetY, scale)` from an `animateFloatAsState` at the Compose layer.
- Drag-to-reorder is not yet wired in the navigator UI — rename + delete are surfaced via the long-press dialog. Reorder still works programmatically via `NoteEditorViewModel.reorderFrames`; a future polish pass can plumb the touch gesture.

---

## Sub-phase 8.3 — Object library

### Scope

Save a selection as a reusable stamp. Drawer (slide-in from the bottom) shows saved stamps; tap to drop one into the current note at the viewport center.

### Schema

Stamps live in their own table — they're shared across notes:

```sql
CREATE TABLE stamps (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  thumbnailPath TEXT NOT NULL,
  payloadJson TEXT NOT NULL,    -- VectorCanvasJson from Phase 7.1
  createdAt INTEGER NOT NULL,
  lastUsedAt INTEGER
);
CREATE INDEX idx_stamps_lastUsedAt ON stamps(lastUsedAt DESC);
```

Bump to version 11 in a single migration shared with 8.1 if 8.1 hasn't shipped yet; otherwise 10→11.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/Stamp.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/StampDao.kt`.
- `app/src/main/java/com/aichat/sandbox/data/repository/StampRepository.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/StampDrawer.kt`.

Modified:
- `LassoController.kt` — selection action "Save as stamp…" prompts for a name → repository writes the row + thumbnail + JSON.
- `NoteEditorViewModel.kt` — `insertStamp(stampId)` deserializes payloadJson, generates fresh IDs, offsets to viewport center, drops onto the current layer (or a new "Stamps" layer if none).

### Step-by-step

1. Save: serialize the selection via `VectorCanvasJson.serialize`, rasterize to a 256-px thumbnail, write the row.
2. Drawer: 3-column grid sorted by `lastUsedAt DESC`. Long-press → Rename / Delete.
3. Insert: parse JSON, regenerate UUIDs (so re-inserting doesn't collide), translate bounds to viewport center, batch into a single `EditorAction.AddItems`.
4. Stamp payload references images by relative path; on insert, copy referenced images to the destination note's image store.

### Definition of done

- Save / list / rename / delete / insert all work.
- Inserting the same stamp twice produces two independent copies (different IDs).
- Stamps with images survive cross-note insertion.
- Drawer is responsive with 100 stamps.

### Non-goals

- Stamp categories / tags.
- Cloud-shared stamp library.
- Stamps from a marketplace.

### Risks

- **Image-path resolution across notes.** A stamp authored in Note A with image `img/foo.jpg` must find `img/foo.jpg` in Note B. Solution: copy images into a shared `filesDir/stamp-images/<stampId>/` on save; insert copies into the target note's image store at insert time.

### Implementation notes

- `StampPayloadCodec` is intentionally separate from `VectorCanvasJson` — the latter is lossy (downsampled stroke points, rounded coordinates, no Base64 payload), which is acceptable for AI request bodies but would silently degrade strokes on every stamp round-trip. The new codec serialises raw `NoteItem.payload` bytes as Base64 so smoothed pressure-modulated strokes replay exactly.
- The stamp insert path regenerates UUIDs and translates the stamp's saved bounds onto the supplied viewport-centre world point. Items drop onto the active layer (via `layerIdForNewItem`) and land as a single `EditorAction.AddItems` so one Ctrl-Z removes the whole stamp.
- Image dependency copy-out is staged: `StampRepository.stampImagesDir(stampId)` returns the per-stamp dir, but the editor's current save path stores image items by their original `filesDir/note-images/...` relative path. Wiring the deep-copy on save (and the rehydrate-into-target-note on insert) is a follow-up; a stamp containing only strokes / shapes / text round-trips correctly today.

---

## Sub-phase 8.4 — Favorites bar

### Scope

A horizontal row of six saved (tool + color + width + texture + opacity) combos pinned above the tool palette. One tap restores the full brush state. This is Concepts' personalizable tool wheel, simplified for a phone.

### Storage

`Preferences DataStore` — favorites are a global app preference, not per-note.

```kotlin
@Serializable
data class FavoriteSlot(
    val index: Int,
    val brushPresetId: String?,   // null = empty slot
)
```

Six slots; user can drag from the preset chip row into a slot to fill it.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/FavoritesBar.kt`.
- `app/src/main/java/com/aichat/sandbox/data/notes/FavoritesStore.kt`.

Modified:
- `NoteEditorScreen.kt` — slot the bar above the existing palette.
- `ToolPaletteState.kt` — `applyFavorite(slotIndex)` resolves the preset and updates the active tool.

### Step-by-step

1. Six slots, each rendered as a circular swatch with the preset's primary color and a small icon for tool / texture.
2. Long-press a slot → "Replace with current brush" or "Clear".
3. Drag-and-drop from the preset chips into a slot (Compose `Modifier.dragAndDropSource`).
4. Default seeds: pen-black, pen-blue, pencil-grey, highlighter-yellow, eraser, lasso.

### Definition of done

- Six slots show on first launch with the seeded defaults.
- Tap applies the brush; visible affordance for "this slot is active."
- Replace / clear / reorder persist across kill.

### Non-goals

- Per-note favorites.
- More than six slots (defer; phone screen real estate).

### Risks

- **Drag-and-drop on Android is fiddly.** Fall back to long-press → menu if Compose DnD glitches.

### Implementation notes

- Backed by `FavoritesStore`, a Hilt-injected singleton that wraps a Compose-free `Preferences DataStore` (`favorites_bar`). Slots persist as a JSON list under a single key — one read-modify-write per assignment, no per-slot transaction churn.
- The bar lives at the editor screen's `Column`, just above the existing tool palette. Empty slots render as outlined "+" tiles; long-press on any slot opens the "Replace with current brush" / "Clear" menu. Drag-and-drop from preset chips was deferred per the phase-doc fallback note — the long-press menu covers the same outcome with one fewer pointer affordance to debug.
- `applyFavorite(slotIndex)` resolves the saved `BrushPreset.id` via `brushPresetList.value`, selects the matching `Tool` enum, and calls `applyBrushPreset`. Deleted user presets degrade gracefully — the resolver returns null and the tap becomes a no-op.

---

## Sub-phase 8.5 — Phase 8 device verification

### Verification matrix (Samsung S25 Ultra)

1. Create three frames on the same note → navigator shows all three → fly-to each works.
2. Resize a frame → bounds update, items unaffected.
3. Delete a frame → only the frame disappears; items remain.
4. Export current frame to PNG / PDF / SVG → bounded to the rect.
5. Save a selection as a stamp → thumbnail renders correctly in the drawer.
6. Insert a stamp into a different note → all items + images come over with fresh IDs.
7. Delete a stamp → row + thumbnail + shared image dir cleaned up.
8. Favorites bar shows six seeded slots → applying one restores tool / color / width.
9. Long-press → "Replace with current brush" persists across kill.
10. Drag from preset chips to a slot works (or long-press fallback works).

### Definition of done

- All 10 items pass.
- Update **Status** in `ARTIST_CANVAS_PLAN.md`.
