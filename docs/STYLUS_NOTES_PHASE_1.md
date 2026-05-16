# Phase 1 — Detailed Sub-Phase Breakdown

> Companion to `STYLUS_NOTES_PLAN.md`. Each sub-phase below is sized to fit a single implementation session: small enough that the change set is reviewable, large enough to leave the app in a working, shippable state. Sub-phases stack — every one ends with the app installable on the S25 Ultra and existing chat features untouched.
>
> **Golden rules for every sub-phase:**
> 1. App must still launch and existing chats must still load (migration safety).
> 2. Every sub-phase ends with a green build (`./gradlew assembleDebug`) and any tests that exist for the touched area.
> 3. Don't import what later sub-phases will introduce. Stubs are fine — TODO comments referencing the next sub-phase are encouraged.
> 4. Don't merge two sub-phases "because they're small". Smallness is the feature.

---

## Sub-phase 1.1 — Data layer foundation

**Goal:** persistence is ready end-to-end before any UI work. After this sub-phase the DB has notes/items tables, the DAO is callable from a repository, DI wires the repository, and the migration is proven on an existing install.

### Scope
- Room entities for `Note` and `NoteItem`.
- `NoteDao` with the surface listed in the parent plan.
- `MIGRATION_3_4` adding both tables + indices.
- `NoteRepository` (pass-through only — no thumbnail/OCR yet).
- DI wiring in `di/AppModule.kt`.
- Unit/instrumented tests on the DAO.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/model/Note.kt`
- `app/src/main/java/com/aichat/sandbox/data/model/NoteItem.kt`
- `app/src/main/java/com/aichat/sandbox/data/local/NoteDao.kt`
- `app/src/main/java/com/aichat/sandbox/data/repository/NoteRepository.kt`
- `app/src/androidTest/java/com/aichat/sandbox/data/local/NoteDaoTest.kt`

### Files to modify
- `data/local/AppDatabase.kt`
  - Add `Note::class, NoteItem::class` to `@Database(entities = …)`.
  - Bump `version = 3` → `version = 4`.
  - Add `abstract fun noteDao(): NoteDao`.
- `data/local/Migrations.kt`
  - Add `MIGRATION_3_4` following the `MIGRATION_2_3` pattern.
  - Mirror the index spec on the entity exactly (`Index("noteId")`, `Index("noteId", "zIndex")`).
- `di/AppModule.kt`
  - Add `MIGRATION_3_4` to the database builder's `.addMigrations(...)` call.
  - `@Provides fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()`
  - `@Provides @Singleton fun provideNoteRepository(noteDao: NoteDao): NoteRepository`.

### Step-by-step
1. Add the two entity classes verbatim from the parent plan. The `payload: ByteArray` field needs hand-written `equals`/`hashCode` overrides — Room won't complain but lint will; document the override with a one-line comment.
2. Add `NoteDao`. Annotate the transactional save method with `@Transaction` and `@Insert(onConflict = REPLACE)` for upsert.
3. Write `MIGRATION_3_4`. The two `CREATE TABLE` statements must match the Room-generated schema exactly — run a debug build, inspect `app/schemas/com.aichat.sandbox.data.local.AppDatabase/4.json` after first compile, and copy column order/types from there.
4. Update DI. Verify `provideAppDatabase` adds the new migration to the existing `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` chain.
5. Write `NoteRepository` with: `observeNotes`, `getNote`, `getItems`, `saveNote(note, items)`, `deleteNote(id)`. Inject `@IoDispatcher` if the project has one; otherwise use `Dispatchers.IO` directly.
6. Write `NoteDaoTest` (in-memory Room): insert note + 3 items, read back, delete note → assert items cascade-delete.

### Definition of done
- `./gradlew :app:assembleDebug` succeeds.
- `./gradlew :app:connectedDebugAndroidTest --tests NoteDaoTest` passes on an emulator or device.
- Existing app installs cleanly over an apk from before this change (migration runs without data loss in chats).
- Generated schema JSON `4.json` is checked into `app/schemas/`.

### Explicit non-goals
- No UI changes (no nav tab, no screens).
- No thumbnail rendering.
- No OCR.
- No background dispatcher heroics — just a plain repository.

### Risks / gotchas
- Forgetting to call `super` in the migration's `migrate(db)` is fine — `Migration.migrate` has no super body. But forgetting to add the new migration object to `.addMigrations(...)` will silently destructive-migrate on dev builds. Double-check.
- `ByteArray` equality: if any test compares two `NoteItem` instances by `==`, it'll fail. Test by id.

---

## Sub-phase 1.2 — Navigation + empty Notes screens

**Goal:** a Notes tab appears in the bottom nav. Tapping it shows an empty list with a FAB. Tapping the FAB or a list row navigates to a placeholder editor that can set a title and persist an empty note (zero items) back to the DB. No drawing yet.

### Scope
- New `Screen.Notes` entry + `bottomNavItems` update.
- `NotesListScreen` rendering `observeNotes()` as cards (no thumbnail yet — just title + relative time).
- `NoteEditorScreen` with TopAppBar (back, title field) and an empty centered placeholder ("Canvas coming next sub-phase").
- `NoteEditorViewModel` with `loadNote(id)` / `saveNote()` plumbing.
- Route `"note/new"` creates a fresh `Note` row on first save.

### Files to create
- `ui/screens/notes/NotesListScreen.kt`
- `ui/screens/notes/NoteEditorScreen.kt`
- `ui/screens/notes/NoteEditorViewModel.kt`

### Files to modify
- `ui/navigation/Navigation.kt`
  - Add `data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)`.
  - Append to `bottomNavItems`.
  - `composable("notes") { NotesListScreen(navController) }`
  - `composable("note/{noteId}", arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { NoteEditorScreen(...) }`
  - Use sentinel `"note/new"` for the create flow.

### Step-by-step
1. Mirror the structure of `ChatListScreen` for `NotesListScreen`: `LazyColumn`, `Card` rows, "New note" FAB → `navController.navigate("note/new")`.
2. `NoteEditorViewModel` (Hilt'd):
   - `private val noteId: String = savedStateHandle["noteId"] ?: "new"`
   - State: `note: MutableStateFlow<Note?>`, `items: SnapshotStateList<NoteItem>` (empty in this sub-phase).
   - `init { if (noteId != "new") load() }`
   - `save(): String` — returns the (possibly newly generated) note id. On "new" path, generate id once and stick with it.
3. `NoteEditorScreen` UI: `TopAppBar` with a back button (calls `viewModel.save()` then `navController.popBackStack()`), an inline `OutlinedTextField` for title (default "Untitled"), and a `Box { Text("Canvas coming next sub-phase") }` placeholder filling the rest.
4. Confirm that an empty title still saves (use "Untitled" if blank).

### Definition of done
- Bottom nav shows Notes tab with the `EditNote` icon.
- Creating a note, typing a title, and pressing back lands you on a list that shows the new note at the top.
- Re-entering the note shows the saved title.
- Existing chat flows are unchanged (smoke-test sending a message in any chat).

### Explicit non-goals
- No `DrawingSurface`, no canvas, no ink.
- No long-press delete (deferred to 1.10).
- No thumbnail rendering.
- No background style picker UI.

### Risks
- Using `savedStateHandle` incorrectly with the "new" sentinel will cause two notes to be created on rapid back-presses. Generate the id once in the VM and reuse it.

---

## Sub-phase 1.3 — Minimum-viable DrawingSurface (single tool, no front buffer)

**Goal:** the user can draw a black, constant-width line with the S-Pen on the editor canvas, the stroke commits to the DB on lift, and reopening the note redraws it identically. No pressure/tilt yet, no motion prediction, no viewport panning, no other tools. This is the smallest end-to-end ink loop.

### Scope
- `DrawingSurface` as a custom `View` (NOT yet `SurfaceView`/front-buffer) wrapped in `AndroidView`.
- Stylus-only input filter (`TOOL_TYPE_STYLUS`); finger touches ignored entirely in this sub-phase.
- `historySize` iteration inside `ACTION_MOVE`.
- Single in-memory `Path` for the live stroke, drawn over a `Bitmap`-backed scene canvas.
- On `ACTION_UP/CANCEL`: pack samples to `ByteArray`, create a `NoteItem(kind="stroke", tool="pen", colorArgb=BLACK, baseWidthPx=4f)`, push to VM, blit to scene bitmap.
- Editor loads existing items on entry, rasterizes them to the scene bitmap once.

### Files to create
- `ui/components/notes/DrawingSurface.kt`
- `ui/components/notes/StrokeCodec.kt` — pure pack/unpack helpers + a test.
- `app/src/test/java/com/aichat/sandbox/ui/components/notes/StrokeCodecTest.kt`

### Files to modify
- `ui/screens/notes/NoteEditorScreen.kt` — replace placeholder with `DrawingSurface`.
- `ui/screens/notes/NoteEditorViewModel.kt` — add `addItem(item)`, expose `items` as `SnapshotStateList`.

### Step-by-step
1. Define the on-wire stroke format in `StrokeCodec`:
   ```
   FloatArray[ x0,y0,p0,t0,  x1,y1,p1,t1,  … ]
   → ByteBuffer.allocate(4 * floats.size).order(LITTLE_ENDIAN).asFloatBuffer().put(floats).array()
   ```
   In this sub-phase always write `p=1.0f, t=0.0f`. The format is fixed now to avoid a schemaVersion bump later.
2. Write a round-trip test: random `FloatArray` of length `% 4 == 0`, encode → decode, assert `contentEquals` within float tolerance.
3. `DrawingSurface`:
   - Extend `View`, override `onTouchEvent` and `onDraw`.
   - Maintain `private val sceneBitmap: Bitmap` created in `onSizeChanged` (or lazily); `sceneCanvas = Canvas(sceneBitmap)`.
   - Reject any pointer whose `getToolType(actionIndex) != TOOL_TYPE_STYLUS`.
   - On `ACTION_DOWN`: start a `Path`, record first sample.
   - On `ACTION_MOVE`: for `h in 0 until historySize` and then the current sample, append to `Path` with `lineTo` (quadratic smoothing is a 1.4 task).
   - `onDraw`: `canvas.drawBitmap(sceneBitmap, 0f, 0f, null)`; then `canvas.drawPath(livePath, livePaint)`.
   - On `ACTION_UP`: commit — draw live path to `sceneCanvas`, build `NoteItem`, invoke a callback `(NoteItem) -> Unit` passed from Compose, clear `livePath`, `invalidate()`.
4. Wire the callback to `viewModel.addItem(...)`, and on Compose dispose / nav back, the existing `saveNote()` writes the items batch.
5. Load path: in `LaunchedEffect(items)` (one-shot when initially populated), call `surface.replayItems(items)` which decodes each stroke and draws it to the scene bitmap.

### Definition of done
- Draw on the canvas with the S-Pen → black line appears.
- Tap with a finger → nothing happens.
- Press back → reopen → exact same strokes are visible.
- `StrokeCodecTest` passes.

### Explicit non-goals
- No front buffer (we'll see lag — that's acceptable for this sub-phase).
- No pressure, no tilt, no color, no width, no eraser.
- No pan/zoom — canvas is fixed to view bounds.
- No hover cursor.
- No undo/redo.

### Risks
- `Bitmap` allocation on rotation: handle in `onSizeChanged` by allocating only when size actually changes; copy old contents into the new bitmap so strokes already drawn don't vanish.
- Compose `AndroidView` recomposition can recreate the view — pass a stable `viewModel` reference and use `factory { … }` / `update { … }` correctly so the scene bitmap isn't lost.

---

## Sub-phase 1.4 — Front-buffer rendering, motion prediction, pressure & tilt

**Goal:** the line "tracks the nib" — perceptibly no lag — and varies with pressure and tilt. Adds the rendering machinery that all later tools rely on. This is the highest-risk sub-phase; budget extra time.

### Scope
- Convert `DrawingSurface` from `View` to `SurfaceView` backed by `CanvasBufferedRenderer` (or stay on `View` if `androidx.graphics:graphics-core` proves unstable — fallback noted below).
- Front-buffered rendering of the live stroke.
- `MotionEventPredictor` integrated for 1-frame look-ahead.
- Variable-width strokes (`baseWidthPx * pressureCurve(pressure)`).
- Tilt factored in for pencil prep (pencil tool itself lands in 1.6 — but the pipeline must accept tilt now so the codec doesn't change).
- Hover cursor: small filled circle that follows `ACTION_HOVER_*` with `TOOL_TYPE_STYLUS`.
- Quadratic-Bézier smoothing on the live path (`path.quadTo(prev, midpoint(prev, cur))`).

### Files to create
- `ui/components/notes/StrokeRenderer.kt` — pure helpers: `pressureCurve`, `tiltFactor`, `widthAt(t, base, pressure, tilt, tool)`. Unit-testable.

### Files to modify
- `ui/components/notes/DrawingSurface.kt` — rewrite of the render path.
- `app/build.gradle.kts` — add:
  - `implementation("androidx.graphics:graphics-core:1.0.0-rc01")` (verify latest before pinning)
  - `implementation("androidx.input:input-motionprediction:1.0.0-beta05")`

### Step-by-step
1. Add the two dependencies. Sync. Confirm the build picks them up before refactoring anything else.
2. Replace the `View` base class with `SurfaceView`, implement `SurfaceHolder.Callback`.
3. Wire `CanvasBufferedRenderer` on `surfaceCreated`. Maintain two render targets:
   - **Scene** — committed strokes; redrawn only when an item commits or viewport changes (later sub-phase).
   - **Front** — current in-progress stroke + hover cursor; redrawn every frame while drawing.
4. Create `MotionEventPredictor.newInstance(this)` (`androidx.input.motionprediction.MotionEventPredictor`) at attach, release at detach.
5. In `ACTION_MOVE`: feed real events to the predictor (`predictor.record(event)`), then `predictor.predict()` returns a synthesized event — append predicted sample to the live path with a flag that says "discard on next real sample". When the next real sample arrives, trim the predicted tail before appending.
6. Use `quadTo` smoothing: render each new segment as `path.quadTo(prev.x, prev.y, (prev.x+cur.x)/2, (prev.y+cur.y)/2)`.
7. Variable width: break the live path into short sub-paths per segment, each `Paint.strokeWidth = widthAt(...)`. This is more expensive than one path with a uniform width — measure on device. If draw time exceeds frame budget, fall back to drawing every-other-segment width changes (visually identical for ink).
8. `pressureCurve`: a sane default is `0.4 + 0.6 * pressure` clamped — exposes itself to per-tool tuning later. `tiltFactor` for non-pencil tools returns `1.0f`.
9. Hover cursor: track latest hover x,y in a `Pair<Float,Float>?`, draw on the front buffer when present, clear on `ACTION_HOVER_EXIT`.
10. **Fallback plan:** if `CanvasBufferedRenderer` API surface causes integration trouble (it's a young library), keep a `View` base class and use `setRenderEffect`/double-buffered `Bitmap` swapping. Document the decision in a header comment in `DrawingSurface.kt` and add a TODO to revisit.

### Definition of done
- On the S25 Ultra, drawing with the S-Pen feels meaningfully tighter than 1.3 (subjective — record a short video at 60fps and compare against the 1.3 build).
- Pressing harder visibly thickens the line.
- Pencil tilt placeholder behaves: setting `tool="pencil"` in code and tilting the pen thins/widens the line (pencil isn't selectable from UI yet — confirm by hardcoding).
- Hovering the pen above the screen shows a small circle that follows the nib.
- `StrokeRendererTest` passes.

### Explicit non-goals
- No new tools in the palette.
- No viewport / pan / zoom.
- No erase, no lasso.
- Not yet drawing the live stroke through the same renderer as committed strokes — that consolidation is for a polish pass later.

### Risks
- `CanvasBufferedRenderer` is RC at time of writing; semantics around frame submission may shift. Pin a specific version.
- Motion prediction can overshoot at direction changes — draw predicted samples with reduced alpha (e.g. `0.4f`) so they "fade in" when overwritten by real samples.
- SurfaceView z-ordering inside Compose `AndroidView` is fiddly; the Compose top app bar may render below the SurfaceView unless we call `setZOrderOnTop(false)` and ensure the TopAppBar sits above via Compose ordering.

---

## Sub-phase 1.5 — Infinite viewport (pan, pinch-zoom) + Background layer

**Goal:** the canvas is "truly infinite". One-finger drag pans, two-finger pinch zooms, S-Pen continues to ink during gestures (independent input streams). Background renders dot/line/graph patterns at the current zoom level. Per-note background style is persisted.

### Scope
- `ViewportController`: encapsulates `offsetX, offsetY, scale` plus `screenToWorld` / `worldToScreen` helpers.
- Touch pointers (non-stylus) route exclusively to the viewport.
- Stylus pointers continue to flow into the ink pipeline.
- Stroke render: world coords are stored; render time applies `viewportMatrix`.
- `BackgroundLayer` composable (or canvas pass inside `DrawingSurface`) that tiles the chosen pattern, clipped to view bounds, accounting for viewport.
- Note's `backgroundStyle` column is read on load and written on title-bar menu change.

### Files to create
- `ui/components/notes/ViewportController.kt`
- `ui/components/notes/BackgroundLayer.kt`
- `app/src/test/java/com/aichat/sandbox/ui/components/notes/ViewportControllerTest.kt`

### Files to modify
- `ui/components/notes/DrawingSurface.kt` — read `viewportController` for input routing and rendering transforms.
- `ui/screens/notes/NoteEditorScreen.kt` — top-app-bar overflow menu with "Background → plain / dot / line / graph".
- `ui/screens/notes/NoteEditorViewModel.kt` — `setBackgroundStyle(style: String)` updates the in-memory note; save persists.

### Step-by-step
1. `ViewportController`:
   - `var offset: Offset by mutableStateOf(Offset.Zero)` (or plain fields if not in Compose).
   - `var scale: Float by mutableStateOf(1f)`, clamped to `0.25f..8f`.
   - `applyPan(dx, dy)`, `applyZoom(focus, factor)` — zoom around a focal point (pinch midpoint).
   - `screenToWorld(p: Offset): Offset = (p - offset) / scale`.
2. Stroke encoding stores **world coords**. The codec doesn't change — `Float` is `Float`. On input, convert screen → world before appending to the live point list. On render, apply `canvas.withMatrix(viewportMatrix) { drawSceneBitmap... }`. Concretely: the scene bitmap is large enough to cover the *current view of world*; on viewport change, redraw the scene bitmap from the in-memory items list using the new matrix. Don't try to scroll the bitmap.
3. Input routing in `onTouchEvent`:
   - If any active pointer is stylus → ink branch.
   - Else if exactly 1 finger → pan.
   - Else if 2 fingers → pinch.
   - Stylus + finger simultaneously → ink wins; ignore finger for viewport so the user doesn't accidentally pan mid-stroke.
4. `BackgroundLayer`: function `drawBackground(canvas, viewport, style, viewSizePx)`:
   - Plain → fill with paper color.
   - Dot → compute first dot world coords inside view, step by `dotSpacingWorld`, draw N×M dots.
   - Line → horizontal lines every `lineSpacingWorld`.
   - Graph → both axes.
   - Spacing values: 32f world units; line stroke 1px at scale 1.0, scaled by `max(0.25, scale)` to avoid hairlines at deep zoom-out.
5. Persist `backgroundStyle`. Plain is the default for newly created notes.
6. `ViewportControllerTest`: assertions on `screenToWorld` ↔ `worldToScreen` round-trip; assertions that zoom around a focal point keeps the focal point's world coord invariant.

### Definition of done
- One-finger drag moves the canvas; strokes move with it.
- Two-finger pinch zooms in/out around the pinch center.
- S-Pen ink during a one-finger drag still inks (palm rejection logic still holds).
- Background dropdown changes pattern live; the choice persists across reopen.
- A dense note with ~200 strokes still pans smoothly (measure frame time in Android Studio profiler; budget < 16ms).

### Explicit non-goals
- No fling / momentum on pan (snap-to-rest is enough for v1).
- No "snap to grid" mode.
- No min-map / overview UI.

### Risks
- Redrawing the scene bitmap on every pan frame will tank perf. Strategy: keep the scene bitmap at the current `viewSize` and re-rasterize *only* on `ACTION_UP` of a pan gesture; during the gesture, draw items on the fly via `canvas.withMatrix { for (item in items) drawItem(canvas, item) }`. Switch strategy based on item count if needed.
- Pinch focal-point math is easy to get wrong. The test must catch that the world point under the user's fingers stays under their fingers throughout the gesture.

---

## Sub-phase 1.6 — Tool palette + highlighter + pencil + erasers + side-button mapping

**Goal:** the full tool set (minus lasso and text-box) is selectable from a bottom palette. Color and width are configurable per tool. The S-Pen side button temporarily switches to eraser.

### Scope
- `ToolPalette` composable along the bottom: tool tabs, color row, width slider.
- Tools: pen, highlighter, pencil, stroke-eraser, area-eraser.
- Side-button mapping (`BUTTON_STYLUS_PRIMARY`) forces eraser while held.
- Per-tool default color/width; user changes persist for the editor lifetime (not across sessions in this sub-phase — saving tool presets is out of scope).

### Files to create
- `ui/components/notes/ToolPalette.kt`
- `ui/components/notes/ToolPaletteState.kt` (sealed class for `Tool`, plus current selection / color / width).
- `app/src/test/java/com/aichat/sandbox/ui/components/notes/EraserHitTest.kt`

### Files to modify
- `ui/components/notes/DrawingSurface.kt` — branch render & commit logic per tool; implement erasers.
- `ui/screens/notes/NoteEditorScreen.kt` — host `ToolPalette` at the bottom.
- `ui/screens/notes/NoteEditorViewModel.kt` — hold `ToolPaletteState`.
- `ui/components/notes/StrokeRenderer.kt` — per-tool paint configuration.

### Step-by-step
1. `Tool` enum: `PEN, HIGHLIGHTER, PENCIL, ERASER_STROKE, ERASER_AREA`. Plus `LASSO`, `TEXT` are added as reserved values but disabled in the palette UI (their sub-phases handle wiring).
2. `ToolPalette`: row of `IconToggleButton`s; below it a color row (6 swatches + custom picker), and a width slider (`0.5f..10f`).
3. Render branches in `DrawingSurface`:
   - Pen: existing logic.
   - Highlighter: `Paint().apply { alpha = 76; strokeCap = ROUND; xfermode = null }` — drawn under ink. Achieved by giving highlighter items lower `zIndex` than pen items when committing. Constant width.
   - Pencil: load `R.drawable.pencil_noise` (a small tileable noise PNG; commit a 64×64 PNG to `res/drawable-nodpi/`) into a `BitmapShader`, set on the paint; alpha modulated by `(1f - tilt/MAX_TILT) * basePencilAlpha`.
   - Eraser-stroke: on each touch sample, hit-test against item bounding boxes; for hits, do segment distance check; mark matched items for deletion, commit on `ACTION_UP`.
   - Eraser-area: same but with a configurable radius (default 24px screen-space).
4. Side button: in `onTouchEvent`, check `(event.buttonState and BUTTON_STYLUS_PRIMARY) != 0`. While true, switch the active tool to `ERASER_STROKE` and remember the previous selection. On release (button state clears), restore.
5. Hit-test helpers (in `StrokeRenderer.kt` or a new `HitTest.kt`):
   - `boundingBoxOf(item: NoteItem): RectF` — decode payload once, cache on the item's lifetime in memory.
   - `pointWithinStroke(item, world, radius): Boolean` — segment distance with early-out.
6. `EraserHitTest`: known strokes (synthetic), assert which the eraser at point P with radius R removes.

### Definition of done
- All five tools draw / erase correctly.
- Holding the S-Pen side button erases regardless of which tool is selected; releasing restores the previous tool.
- Highlighter strokes always render *under* pen strokes drawn on top, even when drawn later.
- Color and width pickers update the active tool live.

### Explicit non-goals
- No partial-stroke segment-splitting eraser (whole-stroke removal only).
- No per-tool saved presets across sessions.
- No texture variety for pencil (single noise).

### Risks
- Z-order: putting highlighter under ink means the commit step must assign `zIndex` based on tool, not insertion order. Make this explicit and test it.
- Side-button state can race with `ACTION_DOWN`: capture button state at down time, not just on move events.

---

## Sub-phase 1.7 — Undo / redo (event log)

**Goal:** the user can undo and redo any canvas action (add stroke, erase, future: transform). Undo/redo is in-memory only — persists for the editor lifetime, lost on exit.

### Scope
- `EditorAction` sealed class: `AddItems`, `RemoveItems`. (`TransformItems`, `UpdateText` are added when lasso / text sub-phases land.)
- `pastActions: ArrayDeque<EditorAction>`, `futureActions: ArrayDeque<EditorAction>` in the VM.
- `apply(action)` mutates `items`, pushes onto past, clears future.
- `undo()` pops past, inverts the action, applies to items, pushes onto future.
- `redo()` mirror image.
- TopAppBar undo / redo buttons, disabled when their deques are empty.

### Files to create
- `ui/screens/notes/EditorAction.kt`
- `app/src/test/java/com/aichat/sandbox/ui/screens/notes/UndoRedoTest.kt`

### Files to modify
- `ui/screens/notes/NoteEditorViewModel.kt` — replace direct `items.add(...)` with `apply(AddItems(...))`.
- `ui/screens/notes/NoteEditorScreen.kt` — undo/redo IconButtons in the TopAppBar; observe `canUndo` / `canRedo` derived state.
- `ui/components/notes/DrawingSurface.kt` — on commit, hand the item to `viewModel.apply(AddItems(listOf(item)))`. On eraser commit, `apply(RemoveItems(matched))`.

### Step-by-step
1. Sealed class:
   ```
   sealed interface EditorAction {
       data class AddItems(val items: List<NoteItem>) : EditorAction
       data class RemoveItems(val items: List<NoteItem>) : EditorAction
   }
   ```
   Removal stores the full items (not ids) so inversion can re-add them.
2. VM: `private val past = ArrayDeque<EditorAction>()` (cap at 200 for memory safety; drop oldest when full). `private val future = ArrayDeque<EditorAction>()`.
3. `apply(action)`: switch on type, mutate `items`, `past.addLast(action)`, `future.clear()`, update `canUndo` / `canRedo` `StateFlow`s.
4. `undo()`: pop `past`, invert, mutate `items`, push to `future`. Don't recurse into `apply`.
5. `UndoRedoTest`: a sequence of `AddItems`, `RemoveItems`, `undo`, `redo`, asserting the items list state matches expectations.
6. UI: `Icon(Icons.Filled.Undo)` and `Icon(Icons.Filled.Redo)` in the TopAppBar action row.

### Definition of done
- Draw 3 strokes → undo three times → canvas is empty.
- Redo three times → all three strokes return in order.
- Erase a stroke → undo brings it back identically (round-tripped through codec — verify by re-rendering).
- `UndoRedoTest` passes.

### Explicit non-goals
- No serializing the undo stack to disk.
- No coalescing rapid actions (each stroke is its own undo step — acceptable for v1).

### Risks
- Mutating `SnapshotStateList` from a non-main thread will throw — funnel `apply` through `viewModelScope.launch(Dispatchers.Main.immediate)` or call from the main thread to begin with.

---

## Sub-phase 1.8 — Lasso, selection, transforms, cross-note clipboard

**Goal:** the user can lasso a region, see selection handles, translate / scale / rotate the selection, duplicate or delete it, and cut/copy/paste across notes via an in-memory clipboard.

### Scope
- `LassoController` polygon hit-test.
- Selection state in the VM: `selection: Set<String>` (item ids), `selectionTransform: Matrix` while a drag is active.
- Selection overlay: dashed rect around bounds + four corner handles + one rotate handle.
- Floating menu (Compose `Popup`): Duplicate, Delete, Cut, Copy, Paste, Export selection. **Ask** and **Convert to text** appear but are disabled with a tooltip "Available in phase 2".
- `NoteClipboard` process-singleton object.
- `EditorAction.TransformItems` added to the undo log.

### Files to create
- `ui/screens/notes/LassoController.kt`
- `ui/screens/notes/NoteClipboard.kt`
- `app/src/test/java/com/aichat/sandbox/ui/screens/notes/LassoHitTest.kt`

### Files to modify
- `ui/screens/notes/EditorAction.kt` — add `TransformItems(val ids: List<String>, val matrix: FloatArray)`.
- `ui/components/notes/DrawingSurface.kt` — when `tool == LASSO`, capture a closed-loop world path on stylus input; render it as a dashed line; commit selection on `ACTION_UP`.
- `ui/components/notes/ToolPaletteState.kt` — enable `LASSO`.
- `ui/screens/notes/NoteEditorScreen.kt` — render selection overlay & floating menu.
- `ui/screens/notes/NoteEditorViewModel.kt` — selection state + clipboard ops.

### Step-by-step
1. Polygon hit-test (`LassoController`):
   - `containsBounds(polygon, rect): Boolean` — fully-inside check via ray cast for each corner.
   - `containsAny(polygon, item.boundingBox): Boolean` — initial filter.
   - Final: any point of any stroke segment inside polygon ⇒ select.
2. Selection bounds = union of selected item bounding boxes, expanded by margin.
3. Transform gesture: Compose `pointerInput` over the selection overlay; differentiate from canvas pointers by hosting the overlay outside the `AndroidView`. Single-finger drag inside bounds → translate. On a handle → scale (with aspect-lock when shift-equivalent; for v1 just free scale from corner). Rotate handle drag → rotate around bounds center.
4. While transforming, render the selection by transforming the matrix when drawing; on release, bake the transform into each item by transforming its decoded points and re-encoding.
5. Commit transforms as a `TransformItems` action so undo works.
6. `NoteClipboard` (object, process-scoped):
   ```
   object NoteClipboard {
       private val items = mutableListOf<NoteItem>()
       fun put(items: List<NoteItem>) { … }
       fun peek(): List<NoteItem> = items.toList()
       fun isEmpty() = items.isEmpty()
   }
   ```
   Paste re-IDs every item with `UUID.randomUUID()`, applies an offset (e.g. +24,+24 world units), sets `noteId` to the current note, calls `apply(AddItems(...))`.
7. Cut = Copy + Delete (two actions, two undo entries — acceptable).

### Definition of done
- Lasso encloses 3 strokes → all 3 turn highlighted; handles appear.
- Drag inside the selection translates it; drag a corner scales; drag the rotate handle rotates.
- Duplicate places an identical set with offset.
- Copy in note A, navigate to note B, paste — same strokes appear.
- Undo reverses transforms / duplicates / deletes correctly.
- Disabled `Ask` button shows the "phase 2" tooltip.

### Explicit non-goals
- Multi-touch transform gestures (pinch-rotate on the selection) — single-handle interactions only.
- Snap-to-grid or rotation steps.
- Persisting the clipboard across app death.

### Risks
- Baking the transform back into stroke points loses some fidelity for highly nonlinear ops; for v1 only affine transforms are supported, so this is fine. Document it.
- The selection overlay must not eat stylus events meant for ink — when the user starts a new lasso while a selection is active, the overlay should clear first.

---

## Sub-phase 1.9 — Text-box tool

**Goal:** a text tool that drops a text item at the tapped position, opens an inline editor for the body, and supports the same translate/scale/rotate handles as strokes via the lasso machinery.

### Scope
- New `NoteItem(kind="text")`. Payload encodes UTF-8 body + font-size float + alignment byte. Width is derived from text layout.
- Tool palette gets the **Text** entry enabled.
- Tapping the canvas with the text tool drops a text item and opens a Compose `BasicTextField` positioned at that world point.
- Double-tap on an existing text item opens the editor.
- Text items participate in selection / transform / undo just like strokes.
- `EditorAction.UpdateText(id, oldBody, newBody)` added.

### Files to create / modify
- `ui/components/notes/TextItemRenderer.kt` — measures + draws text at a given matrix.
- `ui/components/notes/TextItemEditor.kt` — Compose overlay text field.
- `ui/components/notes/DrawingSurface.kt` — branch on `kind` during render; emit a callback on double-tap of a text item.
- `ui/screens/notes/EditorAction.kt` — add `UpdateText`.
- `ui/components/notes/StrokeCodec.kt` → rename or add `NoteItemCodec.kt` with `encodeText` / `decodeText`.

### Step-by-step
1. Text payload format:
   ```
   [fontSize: Float][alignment: Byte][bodyLen: Int][body: UTF-8 bytes]
   ```
   Endianness: little. Codec is tested round-trip.
2. Render text via `StaticLayout` (preferred — handles wrapping); cache the layout per item until the body changes.
3. Bounding box = layout width × height, transformed by the item's matrix.
4. Editing: `TextItemEditor` is a Compose overlay positioned in screen coords by `viewportController.worldToScreen(item.origin)`. While editing, the underlying text-item is hidden so we don't double-render.
5. Commit on focus loss or back press: if body changed, push `UpdateText`. If body is empty, remove the item via `RemoveItems`.

### Definition of done
- Selecting the text tool and tapping the canvas spawns a focused text field at that location.
- Typing, tapping away, and reopening shows the same text.
- Text items can be lasso-selected and transformed.
- Undo of "type a paragraph" reverts to the empty/previous state.

### Explicit non-goals
- Rich text (bold/italic/color spans).
- Multi-font support — single font (default system sans).
- Text in non-Latin scripts beyond what `StaticLayout` already handles.

### Risks
- IME interactions with `AndroidView`-hosted SurfaceView can be fiddly; keep the text editor entirely in Compose layer (above the `AndroidView`) and let it own focus.

---

## Sub-phase 1.10 — Thumbnails, list polish, delete confirmation, manual verification pass

**Goal:** the notes list shows thumbnails of the bounding box, long-press deletes a note (with confirmation), and the full verification matrix from `STYLUS_NOTES_PLAN.md` passes on a physical S25 Ultra. This sub-phase has no big new code — it's polish, perf, and the test pass.

### Scope
- `NoteRepository.renderThumbnail(noteId)` — rasterizes the items list's bounding box to a 512px PNG in `filesDir/note-thumbs/<id>.png` and updates `thumbnailPath`.
- Thumbnail render runs after `saveNote` on `Dispatchers.Default`.
- `NotesListScreen` cards render the thumbnail (AsyncImage or Coil; Coil is already in the project — verify).
- Long-press on a card → confirm dialog → cascade-delete.
- Manifest: `<uses-feature android:name="android.hardware.type.stylus" android:required="false" />` added.
- Run through the verification checklist on a real S25 Ultra; file issues for any regressions; address showstoppers before declaring Phase 1 done.

### Files to create
- (none essential; possibly `data/notes/ThumbnailRenderer.kt` if helper extraction is helpful)

### Files to modify
- `data/repository/NoteRepository.kt` — add `renderThumbnail`.
- `ui/screens/notes/NotesListScreen.kt` — thumbnails + long-press menu.
- `ui/screens/notes/NoteEditorViewModel.kt` — call `repo.renderThumbnail(id)` post-save.
- `app/src/main/AndroidManifest.xml` — add the stylus feature line.

### Step-by-step
1. Bounding-box computation: union of all item bounding boxes. If empty note, render a stub (light-gray paper with the note title centered).
2. Render: allocate a `Bitmap` such that the longer side is 512px, draw the background plus the items, compress to PNG (quality 100 — PNG ignores it but be explicit).
3. Write atomically: render to `<id>.png.tmp` then `renameTo(<id>.png)`.
4. List card: `AsyncImage(model = note.thumbnailPath?.let(::File))`; placeholder while null.
5. Long-press: `Modifier.combinedClickable(onLongClick = { showDeleteDialog = note })`; confirm dialog; on confirm `viewModel.delete(note)`. Items cascade via FK.
6. Manifest add the stylus feature.
7. **Verification matrix** from the parent plan — run each item, paste results into the PR description:
   1. Install over old apk: existing chats present.
   2. Notes tab present, new note opens canvas.
   3. Ink tracks the nib; pressure varies width; pencil tilt softens.
   4. Palm rejection holds.
   5. Side-button erases; release restores.
   6. Hover cursor tracks the nib.
   7. Pan + pinch behave; ink continues during pan.
   8. Tools, colors, undo, redo, lasso, transforms.
   9. Cross-note clipboard.
   10. Title saved; thumbnail appears.
   11. Force-quit, relaunch, note intact.
   12. Long-press delete cascade.

### Definition of done
- All 12 verification items pass on a physical S25 Ultra.
- Any item that doesn't pass either gets fixed in this sub-phase or is filed as a follow-up *before* declaring Phase 1 complete; no silent regressions.

### Explicit non-goals
- OCR / handwriting recognition (phase 2).
- Any AI hook.
- Quick-capture entry points (phase 3).

### Risks
- Thumbnail rendering on the main thread will jank the list. Stay on `Dispatchers.Default`, only mutate DB from there.
- Old notes that pre-date thumbnails won't have one until next save. Add a one-shot "render missing thumbnails" pass in `NotesListScreen`'s VM init if more than N notes are missing thumbnails.

---

## Sequencing notes

- 1.1 → 1.2 → 1.3 is strictly sequential.
- 1.4 (front buffer) and 1.5 (viewport) can swap if needed, but 1.4 is the harder one — front-loading risk early in the phase is healthy.
- 1.6 depends on 1.4 (per-tool paint). 1.7 (undo) is independent of 1.5/1.6 but pulls them all into the same data flow, so do it after both.
- 1.8 (lasso) depends on 1.7 (uses `TransformItems`). 1.9 (text) depends on 1.8 (selection reuse).
- 1.10 is the closer. Don't start 1.10 if any of 1.4–1.9 are flagged "good enough for now".

## Sub-phase quick reference

| # | Title | Net new files | Touch existing | Risk |
| --- | --- | --- | --- | --- |
| 1.1 | Data layer | 5 | 3 | Low |
| 1.2 | Nav + screen shells | 3 | 1 | Low |
| 1.3 | MVP DrawingSurface | 3 | 2 | Medium |
| 1.4 | Front buffer + prediction + pressure/tilt | 1 | 2 | **High** |
| 1.5 | Viewport + background layer | 3 | 3 | Medium |
| 1.6 | Palette + 5 tools + side button | 3 | 4 | Medium |
| 1.7 | Undo / redo | 2 | 3 | Low |
| 1.8 | Lasso + transforms + clipboard | 3 | 4 | Medium |
| 1.9 | Text-box tool | 3 | 3 | Medium |
| 1.10 | Thumbnails + verification | 0–1 | 4 | Low |
