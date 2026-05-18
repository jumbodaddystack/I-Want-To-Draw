# Phase 9 — Handwriting notebook mode

> Optional sibling to the freeform infinite canvas. A "notebook" is a `Note` whose frames (Phase 8) are uniform-sized and rendered as a vertical stack of pages, Goodnotes/Notability-style. Adds cross-notebook OCR search and audio-synced ink playback for students and professionals who like paginated workflows. **No AI study features** (no flashcards, no cross-notebook AI summaries, no quizzes) — user direction.

Parent plan: [`ARTIST_CANVAS_PLAN.md`](./ARTIST_CANVAS_PLAN.md).

## Implementation notes (post-landing)

Sub-phases 9.1–9.4 landed on `claude/implement-phase-9-EkGgi`. The pieces that match the spec exactly:

- `Notebook` model + `notebooks` table + `Note.notebookId` FK (MIGRATION_11_12)
- `NotebookRepository.createNotebook` provisions a paired `Note` + first `NoteFrame` in one transaction; `delete()` cascades through Room's FK AND scrubs on-disk thumbnails, image deps, and audio files.
- `note_audio` table + stroke payload v2 (one-byte `0x02` version header followed by `(x,y,p,tilt,t)` per sample). v1 strokes round-trip unchanged through `StrokeCodec.decode`; `decodeWithT` synthesises `t = 0` for v1 so the replayer's "ignore non-v2" path is implicit.
- `notes_ocr_fts` is a **standalone** FTS4 virtual table (no `content=notes` link) with explicit `AI/AU/AD` triggers in the migration. This avoids trigger-name collisions with Room's `@Fts4(contentEntity = …)` auto-generation — the existing `MessageFts` precedent relies on Room's mechanism but our `NoteDao.updateOcrText` uses a raw `@Query("UPDATE …")` path which Room's generated triggers don't cover, so the explicit triggers were the simpler choice.

Two pieces diverged from the spec for v1:

- **Paginated page view (9.2)** ships as a navigator rail (`PageThumbnailRail`) over a single `DrawingSurface` instead of a `LazyColumn` of fixed-size surfaces. The spec's "scroll vertically through pages" UX is approximated by tap-to-fly: the page rail flies the existing viewport to fit each page. The infinite canvas underneath stays a single world; pages are just frames the viewport snaps between. Multi-surface rendering, page-flip animation, and per-page background overrides remain deferred.
- **Audio-synced playback rendering (9.4)** lands the data model + `StrokeReplayer` clip math, but does NOT yet wire the replayer into `DrawingSurface`'s scene bitmap. Today playback advances the position chip + scrubber and the recorded clip plays back; the strokes themselves render statically. Wiring `StrokeReplayer.clipStroke` into the rasterizer's render loop and invalidating on the 30 Hz position tick is the remaining bit. The encoder side (v2 stroke commits during a recording) IS live — `DrawingSurface.recordingStartedAt` toggles between v1 and v2 encoding cleanly.

### Files added on `claude/implement-phase-9-EkGgi`

```
data/model/Notebook.kt          data/model/NoteAudio.kt
data/local/NotebookDao.kt       data/local/NoteAudioDao.kt
data/local/NoteSearchDao.kt
data/repository/NotebookRepository.kt    data/repository/NoteAudioRepository.kt
data/repository/NoteSearchRepository.kt
data/notes/AudioRecorder.kt     data/notes/AudioPlayer.kt
ui/components/notes/StrokeReplayer.kt
ui/components/notes/AudioRecordingBar.kt
ui/components/notes/AudioPlaybackBar.kt
ui/screens/notebooks/NotebooksListScreen.kt
ui/screens/notebooks/NotebooksListViewModel.kt
ui/screens/notebooks/NewNotebookSheet.kt
ui/screens/notebooks/PageThumbnailRail.kt
ui/screens/notes/NoteSearchScreen.kt
```

### Files modified

```
data/model/Note.kt                          (+ notebookId)
data/local/AppDatabase.kt                   (+ entities, version 11 → 13)
data/local/Migrations.kt                    (+ MIGRATION_11_12, MIGRATION_12_13)
data/local/NoteDao.kt                       (observeNotes filters notebookId IS NULL; + getNotesForNotebook)
ui/components/notes/StrokeCodec.kt          (+ v2 encode / decode / decodeWithT)
ui/components/notes/DrawingSurface.kt       (+ recordingStartedAt, v2 commit branch)
ui/navigation/Navigation.kt                 (+ Notebooks tab, notes_search route)
ui/screens/notes/NotesListScreen.kt         (+ search button + onOpenSearch param)
ui/screens/notes/NoteEditorScreen.kt        (notebook / audio wiring)
ui/screens/notes/NoteEditorViewModel.kt     (notebook header, audio state, addNotebookPage)
di/AppModule.kt                             (+ DAOs, migrations 11_12 + 12_13)
AndroidManifest.xml                         (+ RECORD_AUDIO)
```

Build status: `./gradlew :app:compileDebugKotlin` was not run on this branch — the remote-execution environment has no Android SDK. Compilation has been validated manually against existing imports and call sites.

## Design summary

A notebook is a note + a notebook header. The notebook header pins:

- Page size (A4 portrait, Letter portrait, Half-letter landscape, custom).
- Page style (plain / dot / line / graph).
- Optional default brush preset.

Frames inside the note are auto-arranged into a vertical column, each frame exactly one page. New page = add a frame below the last one. Pages are reorderable via the frame navigator.

This means **the engine doesn't change**. We use the same `DrawingSurface`, same items, same frames. We add a new shell screen (`NotebookPageScroller`) that constrains pan/zoom to page-aligned scrolling.

## Sub-phase 9.1 — Notebook entity + list screen

### Scope

Add the `Notebook` table, a `NotebooksListScreen`, and the option to "create as notebook" alongside "create as note." Notebooks have their own list view; the Notes list continues to show standalone (non-notebook) notes only.

### Schema

Bump to version 11 (or roll into Phase 8's bump):

```sql
CREATE TABLE notebooks (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  pageStyle TEXT NOT NULL,         -- 'plain' | 'dot' | 'line' | 'graph'
  pageWidth REAL NOT NULL,         -- world units
  pageHeight REAL NOT NULL,        -- world units
  defaultBrushPresetId TEXT,
  coverColorArgb INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);

ALTER TABLE notes ADD COLUMN notebookId TEXT REFERENCES notebooks(id) ON DELETE CASCADE;
CREATE INDEX idx_notes_notebookId ON notes(notebookId);
```

A notebook owns exactly **one** underlying note (where its pages live as frames). The 1:1 looks redundant but keeps the existing item / frame / rendering machinery untouched.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/Notebook.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/NotebookDao.kt`.
- `app/src/main/java/com/aichat/sandbox/data/repository/NotebookRepository.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notebooks/NotebooksListScreen.kt`.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notebooks/NewNotebookSheet.kt` — picks page size / style / cover color.
- `MIGRATION_10_11`.

Modified:
- `Navigation.kt` — add `notebooks` route to bottom nav; opening a notebook routes to `NoteEditorScreen` in notebook view mode.
- `NotesListScreen.kt` — filter to `notes WHERE notebookId IS NULL`.
- `NoteEditorScreen.kt` — branch on `note.notebookId`: notebook-mode wraps the canvas with `NotebookPageScroller` (Phase 9.2).

### Step-by-step

1. Notebook creation: create the row, create a sibling `Note` with `notebookId` set, create the first `NoteFrame` at `(0, 0)` with the configured page size.
2. List screen: 2-column grid of notebook covers (rendered card with the cover color + title + page count + last-edited time). Long-press → rename / delete.
3. Delete: cascade through `notebooks` → `notes` → `note_items` / `note_frames`.

### Definition of done

- Create / open / rename / delete notebooks.
- Notes list excludes notebook pages.
- Opening a notebook routes into editor and recognizes notebook mode.

### Non-goals

- Notebook templates / starter packs.
- Multi-note notebooks (one note per notebook is the contract).

### Risks

- **Cascade correctness.** Notebook deletion must remove the underlying note and its assets (thumbnails, image dir, frames). Cover with an instrumented test.

---

## Sub-phase 9.2 — Paginated page view

### Scope

Replace the free pan/zoom of standard notes with a vertical scroll-by-page experience when the note has a `notebookId`. Pan is horizontal-locked-to-page-bounds, zoom snaps to "fit width."

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notebooks/NotebookPageScroller.kt` — wraps `DrawingSurface` with a `LazyColumn` of pages. The same `DrawingSurface` is reused; we just constrain its viewport via `ViewportController` overrides exposed in 5.4.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notebooks/PageThumbnailRail.kt` — reuses the Phase 8.2 navigator but with page numbers instead of frame names.

Modified:
- `ViewportController.kt` — add `lockedToPageColumn: Boolean` plus `pageBounds: List<RectF>`. When true, pan is clamped horizontally to the page's X-extent and vertical pan crosses page boundaries seamlessly.
- `NoteEditorViewModel.kt` — when notebook mode, "Add page" appends a frame at `lastFrame.maxY + GUTTER`.

### Step-by-step

1. Compute page rows: each frame is one row, in `ordinal` order.
2. `LazyColumn` items render a fixed-size `DrawingSurface` viewport positioned at the frame's bounds. Strokes that span pages still render correctly because the underlying note items are unchanged — the page is just a viewport into world space.
3. "Add page" button at the bottom of the scroller and in the overflow menu.
4. Zoom: pinch zooms but clamps to `[fitWidthScale, 4 * fitWidthScale]`. Double-tap toggles fit-width / 100%.
5. Horizontal swipe inside a page is drawing; horizontal swipe **at the page edge** does nothing (no page-flip animation in v1).

### Definition of done

- Open a notebook → pages scroll vertically.
- Pinch zoom respects clamps.
- Add / reorder pages updates the scroll list.
- Stroke drawn at the bottom of one page that strays into the gutter is preserved (and visible in the next page's viewport if it overlaps).
- Standard notes still pan/zoom freely (no regression).

### Non-goals

- Page-turn animation.
- Two-page spread view.
- Per-page background style (notebook header style applies to all pages).

### Risks

- **Performance with many pages.** A 100-page notebook can't render all `DrawingSurface` instances at once. Mitigation: `LazyColumn` only composes visible pages ± 1 page of overscan. A scene bitmap per visible page; offscreen pages render only thumbnails.
- **Two `DrawingSurface` instances drawing the same strokes.** They share the same world; rendering is read-only of the item list. Writes go through the ViewModel which routes to the active page (the one under the active gesture).

---

## Sub-phase 9.3 — Cross-notebook OCR search

### Scope

Wire the OCR text we already record on save (`Note.ocrText`, `STYLUS_NOTES_PHASE_2.md` sub-phase 2.4) into a global search that walks notes + notebooks.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteSearchScreen.kt` — search box + result list grouped by notebook.
- `app/src/main/java/com/aichat/sandbox/data/repository/NoteSearchRepository.kt` — FTS query against a new FTS4 virtual table.
- `MIGRATION_11_12` (rolled into 9.4's bump if 9.4 lands at the same time, otherwise separate).

### Schema

```sql
CREATE VIRTUAL TABLE note_ocr_fts USING fts4(content=notes, ocrText);
-- triggers to keep in sync with notes.ocrText
CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
    INSERT INTO note_ocr_fts(docid, ocrText) VALUES (new.rowid, new.ocrText);
END;
-- analogous au, ad triggers
```

### Step-by-step

1. Migration creates FTS table + triggers; one-shot `INSERT INTO note_ocr_fts SELECT rowid, ocrText FROM notes WHERE ocrText IS NOT NULL` seeds existing rows.
2. Search bar at the top of `NotesListScreen` + `NotebooksListScreen`.
3. Results show snippet (`fts4 snippet()` function) with the matched term highlighted, plus notebook + page (frame ordinal) when applicable.
4. Tap a result → navigates to the note and (notebook mode) scrolls to the matching page.

### Definition of done

- Search updates within 200 ms of typing on a 200-note dataset.
- Notebook page numbers are correct in result rows.
- Existing OCR pipeline (Phase 2.4) is unchanged.

### Non-goals

- AI semantic search ("notes about photosynthesis" → ranked semantic results). Explicit user direction: no AI study features.
- Search inside text-box items separately (current OCR captures handwriting only; text items aren't OCR'd because they're already text — separate work to fold them in).

### Risks

- **FTS table on existing DB.** Index rebuild on first launch could be slow with a large dataset. Mitigate by running in a one-shot `WorkManager` job, not in the migration.

---

## Sub-phase 9.4 — Audio-synced ink

### Scope

Notability's signature feature. Record audio while drawing; play it back and watch strokes animate onto the page in sync.

### Schema

Bump to version 12. New table:

```sql
CREATE TABLE note_audio (
  id TEXT PRIMARY KEY,
  noteId TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  filePath TEXT NOT NULL,
  durationMs INTEGER NOT NULL,
  recordedAt INTEGER NOT NULL,
  recordingStartedAt INTEGER NOT NULL   -- monotonic anchor for stroke timestamps
);
```

**Stroke payload v2.** Existing format `(x, y, p, tilt)` → new format `(x, y, p, tilt, t)`. `Note.schemaVersion` gates the decoder. `MIGRATION_11_12` does **not** rewrite existing payloads; v1 strokes are read with `t` synthesized as `(i / count) * durationEstimate` at decode time.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/model/NoteAudio.kt`.
- `app/src/main/java/com/aichat/sandbox/data/local/NoteAudioDao.kt`.
- `app/src/main/java/com/aichat/sandbox/data/notes/AudioRecorder.kt` — wraps `MediaRecorder`. AAC in MP4 container at 64 kbps.
- `app/src/main/java/com/aichat/sandbox/data/notes/AudioPlayer.kt` — wraps `MediaPlayer`, exposes `currentPositionMs` as a flow.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/AudioRecordingBar.kt` — record button + timer + waveform.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/AudioPlaybackBar.kt` — play/pause + scrubber + position.
- `app/src/main/java/com/aichat/sandbox/ui/components/notes/StrokeReplayer.kt` — given a playback position and ordered (item, t) pairs, computes which segments to render.
- Updated `StrokeCodec` with v2 read/write.

### Step-by-step

1. **Recording**:
   - Request `RECORD_AUDIO` at first tap on the record button.
   - Start `MediaRecorder` and capture `recordingStartedAt = SystemClock.elapsedRealtime()`.
   - Every sample written by `DrawingSurface` between record start and record stop gets `t = sampleTime - recordingStartedAt`. Re-encode the stroke at `ACTION_UP` (no live re-encoding pressure).
   - Stop: finalize the file, write the `note_audio` row, embed `recordingStartedAt`.
2. **Playback**:
   - Tap an audio row → play. `AudioPlayer` emits `currentPositionMs`.
   - `StrokeReplayer` filters items recorded during this audio's window (by `t` falling between `0` and `durationMs`) and clips each stroke's rendered point range to `points[where t <= currentPositionMs]`.
   - The page rendering during playback ignores the regular scene bitmap for that audio's strokes and renders them progressively.
3. **Scrubbing**:
   - Dragging the scrubber updates `currentPositionMs` directly. Replay state recomputes instantly because filtering is O(N) over points.
4. Multiple audio recordings per note are supported; each draws its own stroke subset. Strokes drawn outside any recording are static.

### Definition of done

- Record → draw 5 strokes → stop → play back → strokes animate in sync.
- Scrubbing jumps stroke replay to the right position.
- Drift between audio and stroke replay < 100 ms over a 5-minute recording.
- v1 strokes (no `t`) render normally — they don't get replayed (synthesized `t` makes them all appear at `t = 0` of the "no-audio" window, which is fine).
- App kill during a recording cleans up (corrupted file deleted on next launch).

### Non-goals

- Multi-track audio.
- Audio editing (trim, split).
- Background recording.
- Cross-notebook audio.

### Risks

- **`MediaRecorder` lifecycle.** Easy to leak. Bind to the editor's lifecycle, force-release in `onCleared`. Crash recovery clears stale files on app start.
- **Stroke format bump is the highest-risk change in the entire plan.** Mitigate:
  - Add `schemaVersion` per `Note` (already there) AND per-item in the payload's first byte for forward safety.
  - Encode 10 fixture notes in v1, decode, re-encode in v2, decode again, assert geometry matches within float tolerance. Land this test before any production code.

---

## Sub-phase 9.5 — Phase 9 device verification

### Verification matrix (Samsung S25 Ultra)

1. Create a notebook → first page appears, page style applied.
2. Scroll between pages smoothly at 60 fps with 50 pages of dense strokes.
3. Add page at end / reorder pages via navigator.
4. Pinch zoom respects clamps (fit-width to 4×).
5. OCR search finds a term in handwriting across both notes and notebooks → tapping a notebook result scrolls to the right page.
6. Search responsive on 200 notes.
7. Record audio while drawing → play back → strokes animate.
8. Scrub mid-recording → replay position jumps correctly.
9. Open a v1 (pre-9.4) note → strokes render and edit normally.
10. Delete a notebook → cascade removes pages, frames, items, audio files.

### Definition of done

- All 10 items pass.
- Update **Status** in `ARTIST_CANVAS_PLAN.md`.
