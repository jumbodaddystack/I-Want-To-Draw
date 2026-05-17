# Stylus Notes for S25 Ultra — Master Plan

> This is the working document. Open it at the start of every implementation session, find the next unchecked sub-phase in the tracker, open the linked phase doc, and execute it.

## Status

- **Current phase:** Phase 2 — 2.1–2.5 landed; Phase 1 device verification still pending
- **Next sub-phase:** [2.6 — `AiSideSheet` UI shell + streaming](./STYLUS_NOTES_PHASE_2.md#sub-phase-26--aisidesheet-ui-shell--streaming-render)
- **Last verified device pass:** none yet (Phase 1 matrix to be run on hardware)

## Phase index

| Phase | Focus | Breakdown doc | Sub-phases |
| --- | --- | --- | --- |
| 1 | Canvas feel (shippable as v1) | [`STYLUS_NOTES_PHASE_1.md`](./STYLUS_NOTES_PHASE_1.md) | 10 |
| 2 | AI on canvas | [`STYLUS_NOTES_PHASE_2.md`](./STYLUS_NOTES_PHASE_2.md) | 8 |
| 3 | Quick capture | [`STYLUS_NOTES_PHASE_3.md`](./STYLUS_NOTES_PHASE_3.md) | 4 |
| 4 | Export & chat integration | [`STYLUS_NOTES_PHASE_4.md`](./STYLUS_NOTES_PHASE_4.md) | 4 |

---

## Master implementation tracker

Update the check state in this section as work lands. One sub-phase per PR. Mark a sub-phase complete only after its "Definition of done" is met **and** the build is green on `main` (or this feature branch).

Legend: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked (note reason inline)

### Phase 1 — Canvas feel · [`details`](./STYLUS_NOTES_PHASE_1.md)

- [x] **1.1** Data layer foundation — entities, DAO, `MIGRATION_3_4`, DI, repository ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-11--data-layer-foundation))
- [x] **1.2** Navigation + empty Notes screens ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-12--navigation--empty-notes-screens))
- [x] **1.3** Minimum-viable `DrawingSurface` (single tool, no front buffer) ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-13--minimum-viable-drawingsurface-single-tool-no-front-buffer))
- [x] **1.4** Front-buffer rendering, motion prediction, pressure & tilt ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-14--front-buffer-rendering-motion-prediction-pressure--tilt))
- [x] **1.5** Infinite viewport + background layer ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-15--infinite-viewport-pan-pinch-zoom--background-layer))
- [x] **1.6** Tool palette + highlighter + pencil + erasers + side-button mapping ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-16--tool-palette--highlighter--pencil--erasers--side-button-mapping))
- [x] **1.7** Undo / redo (event log) ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-17--undo--redo-event-log))
- [x] **1.8** Lasso + selection + transforms + cross-note clipboard ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-18--lasso-selection-transforms-cross-note-clipboard))
- [x] **1.9** Text-box tool ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-19--text-box-tool))
- [~] **1.10** Thumbnails + list polish + S25 verification ([details](./STYLUS_NOTES_PHASE_1.md#sub-phase-110--thumbnails-list-polish-delete-confirmation-manual-verification-pass)) — code landed (thumbnails, long-press delete, stylus manifest feature); 12-item device matrix still pending on real S25 Ultra hardware

### Phase 2 — AI on canvas · [`details`](./STYLUS_NOTES_PHASE_2.md)

- [x] **2.1** Vision-capability registry + ML Kit dep + manifest ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-21--vision-capability-registry--dependencies--manifest))
- [x] **2.2** `NoteRasterizer` (selection + whole note → PNG bytes) ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-22--note-rasterizer-selection--whole-note--png-bytes))
- [x] **2.3** `HandwritingOcr` (ML Kit Digital Ink) ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-23--handwriting-ocr-ml-kit-digital-ink))
- [x] **2.4** OCR on save (lazy pipeline) ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-24--ocr-on-save-lazy-pipeline))
- [x] **2.5** `NoteAiService` core (no UI) ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-25--noteaiservice-core-no-ui))
- [ ] **2.6** `AiSideSheet` UI shell + streaming ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-26--aisidesheet-ui-shell--streaming-render))
- [ ] **2.7** Entry points: toolbar Ask + lasso Ask + canned prompts + Convert-to-text ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-27--entry-points-toolbar-ask--lasso-ask--canned-prompts--convert-to-text-fast-path))
- [ ] **2.8** Reply actions: Copy / Insert as text box / Send to chat + verification ([details](./STYLUS_NOTES_PHASE_2.md#sub-phase-28--reply-actions-copy-insert-as-text-box-send-to-chat--polish--verification))

### Phase 3 — Quick capture · [`details`](./STYLUS_NOTES_PHASE_3.md)

- [ ] **3.1** Deep-link plumbing + home-screen static shortcut ([details](./STYLUS_NOTES_PHASE_3.md#sub-phase-31--deep-link-plumbing--home-screen-static-shortcut))
- [ ] **3.2** Quick Settings tile ([details](./STYLUS_NOTES_PHASE_3.md#sub-phase-32--quick-settings-tile))
- [ ] **3.3** `ACTION_CREATE_NOTE` activity-alias (Android 14 default note-taking app) ([details](./STYLUS_NOTES_PHASE_3.md#sub-phase-33--action_create_note-activity-alias-android-14-default-note-taking-app))
- [ ] **3.4** Sketch composer attachment sheet + verification ([details](./STYLUS_NOTES_PHASE_3.md#sub-phase-34--sketch-composer-attachment-sheet--phase-3-verification))

### Phase 4 — Export & chat integration · [`details`](./STYLUS_NOTES_PHASE_4.md)

- [ ] **4.1** `NoteExporter` + PNG share + `FileProvider` bootstrap ([details](./STYLUS_NOTES_PHASE_4.md#sub-phase-41--noteexporter-core--png-share-target))
- [ ] **4.2** PDF export with fit-page / tile dialog ([details](./STYLUS_NOTES_PHASE_4.md#sub-phase-42--pdf-export-with-fit-page--tile-to-pages-dialog))
- [ ] **4.3** "Send to chat" picker ([details](./STYLUS_NOTES_PHASE_4.md#sub-phase-43--send-to-chat-picker))
- [ ] **4.4** Pin note as context + verification ([details](./STYLUS_NOTES_PHASE_4.md#sub-phase-44--chat-side-pin-note-as-context-affordance--phase-4-verification))

---

## Context

The app is a native Android Kotlin + Jetpack Compose chat client (minSdk 26, compileSdk 34). There is no notes feature today. The user is on a Samsung S25 Ultra with S-Pen and wants a first-class stylus surface — writing, brainstorming, doodling, planning — that lives alongside chat so they can switch between modes fluidly. The notes feature is **standalone** in v1 (no chat panel inside the editor), but AI hooks and quick-capture entry points are baked in from day one.

Android's standard `MotionEvent` already reports `TOOL_TYPE_STYLUS`, pressure, tilt, and orientation from the S-Pen, and exposes the side button via `BUTTON_STYLUS_PRIMARY`. No Samsung-specific SDK is required.

## Decisions (from the brainstorm)

| Axis | Decision |
| --- | --- |
| Editor / chat coupling | Editor is standalone. AI hooks live in the editor; chat-side gets a "pin note as context" affordance in Phase 4. |
| Canvas shape | Truly infinite single surface, pan/zoom anywhere. |
| Canvas polish | Go big: front-buffered low-latency rendering, motion prediction, hover cursor, side-button eraser, pressure + tilt. |
| Background style | User-selectable per note: plain / dot-grid / lines / graph. |
| Pen tools | Pen, highlighter, pencil (tilt shading), dual eraser (stroke + area), lasso, text box. |
| AI surface | Lasso context menu **and** a whole-note "Ask" toolbar button. Both feed a side sheet. |
| AI model | Whichever model the user has selected for chat. Non-vision models fall back to OCR text only. |
| Lasso actions | Move / scale / rotate, duplicate / delete, cross-note clipboard, export selection. |
| Export | Share as PNG, share as PDF, send to an existing in-app chat. |
| Quick capture | Stylus icon in chat input (sketch attachment), Android 14 `ACTION_CREATE_NOTE` default-app handler, home-screen shortcut + Quick Settings tile. |
| Build order | **Canvas feel first.** AI integration and quick-capture are later phases. |

## Architecture overview

```
NotesListScreen   (new bottom-nav tab, thumbnails of bounding-box render)
    └── NoteEditorScreen (full-screen)
            ├── DrawingSurface  (custom View, owns input + front-buffer render)
            │       └── pen / highlighter / pencil / eraser / lasso / text-box
            ├── BackgroundLayer (plain / dot / line / graph, rendered to scroll/zoom)
            ├── ToolPalette     (bottom bar) + TopAppBar (title, undo/redo, Ask-AI)
            └── AiSideSheet     (slides in from the right; uses chat model)
```

## Data layer

`app/src/main/java/com/aichat/sandbox/data/model/Note.kt`

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val backgroundStyle: String,   // "plain" | "dot" | "line" | "graph"
    val schemaVersion: Int,        // bump when stroke binary format changes
    // Stroke geometry bounds, used for thumbnails & initial viewport.
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    val thumbnailPath: String?,    // cached PNG in app files dir
    val ocrText: String?,          // most-recent OCR pass for search / non-vision AI fallback
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

Strokes live in a separate table — keeps notes light, allows partial loading, and avoids the JSON-blob anti-pattern from the v0 plan:

`app/src/main/java/com/aichat/sandbox/data/model/NoteItem.kt`

```kotlin
@Entity(
    tableName = "note_items",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"],
        childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("noteId", "zIndex")],
)
data class NoteItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val zIndex: Int,
    val kind: String,        // "stroke" | "text"
    val tool: String?,       // for strokes: "pen" | "highlighter" | "pencil" | "eraser"
    val colorArgb: Int,
    val baseWidthPx: Float,
    // Strokes: packed binary points (x,y,p,tilt). Text: UTF-8 body, font/size encoded.
    val payload: ByteArray,
)
```

Point packing: `FloatArray` flattened to bytes via `ByteBuffer` — `(x, y, pressure, tilt)` per sample, no per-point timestamps. ~25% smaller than the v0 design and far smaller than JSON.

**DAO** — `data/local/NoteDao.kt`, mirrors `ChatDao`:
- `observeNotes(): Flow<List<Note>>` ordered by `updatedAt DESC`
- `getNote(id)`, `getItems(noteId): List<NoteItem>`
- `upsertNote`, `upsertItems`, `deleteItems(ids)`, `deleteNote(id)`
- Transactional `saveNote(note, items)` that replaces the item set atomically.

**Database** — `data/local/AppDatabase.kt`:
- Add `Note::class` and `NoteItem::class` to `@Database(entities = ...)`
- Bump version `3 → 4` (Phase 1); `4 → 5` later for the chat-side pinned-note column (Phase 4.4)
- Add `MIGRATION_3_4` in `data/local/Migrations.kt` that creates both tables + indices (follow the `MIGRATION_2_3` pattern)
- `abstract fun noteDao(): NoteDao`

**Repository** — `data/repository/NoteRepository.kt`:
- Thin pass-through, plus `renderThumbnail(noteId)` that rasterizes the bounding box to a 512px PNG in `filesDir/note-thumbs/` and updates `thumbnailPath`. Runs on a background dispatcher after save.
- `runOcr(noteId)` (Phase 2.4) — wraps ML Kit Digital Ink, writes `ocrText`.

**DI** — `di/AppModule.kt`: add `provideNoteDao` and the repository.

## Rendering pipeline (the high-stakes piece)

S-Pen on the S25 Ultra reports ~240 Hz; the Android frame pipeline alone introduces ~1–2 frame latency on top of touch sampling. To get a "pen on paper" feel we need to render the *in-progress* stroke on a front buffer rather than going through Compose recomposition.

**Component**: `app/src/main/java/com/aichat/sandbox/ui/components/notes/DrawingSurface.kt` — a custom `SurfaceView` (or `View` backed by `CanvasBufferedRenderer` from `androidx.graphics:graphics-core:1.0+`) wrapped in an `AndroidView` for Compose embedding.

Responsibilities:

1. **Input**
   - Accept only `TOOL_TYPE_STYLUS` pointers for ink (palm rejection).
   - Touch pointers route to a `ViewportController` for pan (one-finger) and pinch-zoom (two-finger).
   - Read `MotionEvent.getButtonState() and BUTTON_STYLUS_PRIMARY` — while held, force the active tool to `eraser` and restore on release.
   - Iterate `historySize` in `ACTION_MOVE` (S-Pen samples faster than the frame rate; skipping history gives jagged lines).
   - Feed positions through `androidx.input:input-motionprediction` (`MotionEventPredictor`) to predict 1 frame ahead; render predicted tail on the front buffer, replace with real samples next frame.
   - `ACTION_HOVER_*` with `TOOL_TYPE_STYLUS` drives a small hover cursor preview at the nib position.

2. **Rendering**
   - Two layers: a **scene layer** (Bitmap-backed canvas of committed strokes, redrawn on viewport change) and a **front buffer** (current in-progress stroke + hover cursor).
   - In-progress stroke uses `Path` with quadratic Bézier smoothing between samples (`path.quadTo(p1, midpoint(p1,p2))`).
   - Variable width: `baseWidthPx * pressureCurve(pressure) * tiltFactor(tool, tilt)` sampled per segment, drawn as a stroked sub-path per segment so width can vary mid-stroke.
   - Pencil tool adds a noise-textured `BitmapShader` and modulates alpha by tilt.
   - Highlighter: `BlendMode.Multiply` (or simple 30% alpha), constant width, drawn under ink (lower `zIndex`).

3. **Commit**
   - On `ACTION_UP/CANCEL`, freeze the live stroke into a `NoteItem`, blit onto the scene layer, append to the in-memory list, clear the front buffer.

4. **Erase**
   - Stroke eraser: hit-test the stroke's bounding box first, then segment-by-segment distance check, remove matched items.
   - Area eraser: same approach with a configurable radius; partial overlap removes the whole stroke in v1 (true segment splitting is v2).

## UI layer

**Navigation** — `ui/navigation/Navigation.kt`:
- Add `data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)` and append to `bottomNavItems`.
- `composable("notes") { NotesListScreen(...) }`
- `composable("note/{noteId}") { NoteEditorScreen(...) }`, sentinel `"note/new"` for fresh notes.

**NotesListScreen** — `ui/screens/notes/NotesListScreen.kt`:
- LazyColumn of cards, mirrors `ChatListScreen`.
- Each card: cached thumbnail PNG, title, relative time, long-press → delete confirmation.
- "New note" FAB.

**NoteEditorScreen** — `ui/screens/notes/NoteEditorScreen.kt`:
- TopAppBar: back (saves on exit), inline editable title, undo, redo, **Ask about this note** button.
- Bottom palette: tool tabs (pen / highlighter / pencil / eraser / lasso / text), color row, width slider, page-style menu.
- Center: `DrawingSurface`.
- Right edge: pull-out `AiSideSheet` (hidden by default).

**Undo/redo** — event log, not snapshot:
- `EditorAction = AddItems | RemoveItems | TransformItems | UpdateText`
- ViewModel keeps `pastActions` and `futureActions` deques; applying/reverting mutates the `SnapshotStateList<NoteItem>`. Persisted across editor lifetime (in-memory only in v1).

**LassoController** — `ui/screens/notes/LassoController.kt`:
- Closed-loop path → polygon hit-test against item bounding boxes (cheap), then exact stroke containment.
- Selection handles for translate / scale / rotate (Compose `pointerInput` over the selection bounds).
- Floating menu: **Ask**, **Convert to text**, **Duplicate**, **Delete**, **Cut**, **Copy**, **Export as image**.
- Clipboard: a process-singleton `NoteClipboard` object holds the last copied items; paste re-IDs them and offsets position. Survives navigating between notes; lost on app death (v1).

**Text-box items** — tap with the text tool drops a `NoteItem(kind="text")` you can move/scale; double-tap to edit. AI side-sheet's "Insert on canvas" action creates the same item.

## AI integration

Lives entirely behind two entry points:

1. **Lasso → Ask** (selection in context)
2. **Toolbar "Ask about this note"** (whole note in context)

Both call into `NoteAiService` (`data/notes/NoteAiService.kt`):

```kotlin
suspend fun ask(
    note: Note,
    selection: List<NoteItem>?,        // null = whole note
    prompt: String,                    // free-form or canned (Explain / Expand / Convert to text)
    model: ModelChoice,                // taken from existing chat ModelSelector
): Flow<AiChunk>
```

Implementation:
- Render the selection (or whole note's bounding box) to a PNG in memory.
- If `model.supportsVision`: send `[image, ocrTextHint, prompt]` to the existing `ApiClient`.
- Else: run ML Kit Digital Ink over the selection's strokes, send `[ocrText, prompt]`.
- Stream the response into `AiSideSheet`; each reply offers **Copy**, **Insert as text box**, **Send to chat**.

ML Kit Digital Ink (`com.google.mlkit:digital-ink-recognition`) is on-device and free; model downloads on first use. OCR runs lazily in the repository on save so search and non-vision fallback are always ready.

**Canned prompt buttons** in the lasso menu: *Explain*, *Expand*, *Convert to text*, *Summarize*, *Continue this*. Plus a free-form input.

## Quick-capture entry points (Phase 3)

- **Stylus icon in chat input** — `ChatScreen`'s composer gets a pen button that opens a bottom-sheet sketch surface (a stripped-down `DrawingSurface`, fixed-size). On confirm, the sketch is rasterized and attached to the outgoing message as an image (reuses existing image-attachment flow).
- **Android 14 default note-taking app** — declare an activity-alias for `Intent.ACTION_CREATE_NOTE` in `AndroidManifest.xml`. Launches `NoteEditorScreen` with `note/new`. Honours `EXTRA_USE_STYLUS_MODE`. Gated by `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`.
- **Home-screen shortcut** — static shortcut in `app/src/main/res/xml/shortcuts.xml` pointing at `note/new`.
- **Quick Settings tile** — `TileService` subclass that launches the same deep link.

All four entry points share a single deep link (`aichat://notes/new?source=…&stylus=…`) introduced in Phase 3.1.

## Export (Phase 4)

- **PNG**: rasterize the stroke bounding box (with margin), expose via `FileProvider` + `Intent.ACTION_SEND`. (4.1 introduces the `FileProvider`, which the rest of Phase 4 reuses.)
- **PDF**: `PdfDocument` API. Infinite canvas → ask the user to pick "Fit to one page" or "Tile across pages" at export time.
- **Send to chat**: bottom-sheet picker over existing chats; opens the target chat with the rendered PNG + OCR text attached to the draft message. Image handover uses an in-memory `PendingDraftStore`, not a navigation argument.
- **Pin note as context** (chat-side): per-chat `pinnedNoteId` column. On every send, the pinned note is re-rendered and attached transparently to the API call only (not persisted on the user `Message` row).

## Cross-cutting design decisions

These are the non-obvious decisions captured in the phase docs that future sessions should not re-litigate without good cause.

- **Stroke payload format is fixed at Phase 1.3.** `(x, y, pressure, tilt)` per sample, packed `FloatArray`, no per-point timestamps. Bumping this requires `schemaVersion` change + migration.
- **OCR timestamps are synthetic.** ML Kit Digital Ink (Phase 2.3) wants per-point timestamps that the codec doesn't store. We synthesize evenly-spaced ones at the recognizer call site. Acceptable accuracy hit for printed handwriting; documented in the Phase 2.3 doc.
- **AI side sheet is one-shot, not multi-turn.** Each `AskTurn` is independent; prior turns are not packed into the request. Multi-turn is a deliberate follow-up.
- **"Send to chat" image handover uses an in-memory store, not a query-string blob.** See Phase 4.3 for the reasoning (URL length, log leakage).
- **Pinned-note image attaches only to the API call.** The DB `Message` row stays clean — pinning is additive, removable, and doesn't bloat persistence. See Phase 4.4.
- **High-risk sub-phases to front-load:** 1.4 (front buffer), 2.3 (ML Kit lifecycle), 4.4 (pinning + migration + ApiClient changes).

## Manifest

`app/src/main/AndroidManifest.xml`:
- `<uses-feature android:name="android.hardware.type.stylus" android:required="false" />` (Phase 1.10)
- `aichat://` deep-link intent filter on `MainActivity` (Phase 3.1)
- Static shortcuts metadata (Phase 3.1)
- Quick Settings `<service>` (Phase 3.2)
- `<activity-alias>` for `ACTION_CREATE_NOTE` (Phase 3.3)
- `<provider>` for `FileProvider` (Phase 4.1)

## Verification matrices

Each phase's closing sub-phase contains its full verification matrix:

- [Phase 1 matrix (12 items)](./STYLUS_NOTES_PHASE_1.md#sub-phase-110--thumbnails-list-polish-delete-confirmation-manual-verification-pass) — run on real S25 Ultra at end of Phase 1.
- [Phase 2 matrix (12 items)](./STYLUS_NOTES_PHASE_2.md#sub-phase-28--reply-actions-copy-insert-as-text-box-send-to-chat--polish--verification) — at end of Phase 2.
- [Phase 3 matrix (10 items)](./STYLUS_NOTES_PHASE_3.md#sub-phase-34--sketch-composer-attachment-sheet--phase-3-verification) — at end of Phase 3.
- [Phase 4 matrix (12 items)](./STYLUS_NOTES_PHASE_4.md#sub-phase-44--chat-side-pin-note-as-context-affordance--phase-4-verification) — at end of Phase 4.

Update the **Status** block at the top of this doc each time a matrix passes.

## Out of scope (deliberately, even after all four phases)

- Cloud sync.
- Full pencil-style segment-splitting eraser (v1 erases whole strokes for partial overlap).
- Multi-user collaboration on a note.
- S Pen Bluetooth-button air gestures (would need the Samsung S Pen Remote SDK).
- Handwriting → math LaTeX (separate ML Kit model; revisit if requested).
- Multi-turn conversation history packed into AI side-sheet requests (each turn is one-shot).
- Multiple pinned notes per chat.
- Returning a note URI from `ACTION_CREATE_NOTE` to the caller.
- Vector PDF export (we rasterize per page).
- Selectable text layer in exported PDFs.

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
