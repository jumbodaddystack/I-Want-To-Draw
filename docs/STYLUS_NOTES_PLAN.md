# Plan: Standalone Handwritten Notes (S-Pen, S25 Ultra)

## Context

The app is a native Android Kotlin + Jetpack Compose chat client. There is no notes feature today, and no stylus/canvas code anywhere. The user wants a **standalone handwriting notes section** (separate from chat), reachable as a new top-level destination, with a **full-screen drawing canvas**, **vector strokes stored in Room** so notes stay editable later. Target device is the **Samsung S25 Ultra** with S-Pen — so the canvas needs proper stylus pressure/tilt capture and palm rejection.

This is feasible without any Samsung-specific SDK: Android's standard `MotionEvent` already reports `TOOL_TYPE_STYLUS`, pressure (0.0–1.0), tilt, and orientation from the S-Pen. The optional **Samsung S Pen Remote SDK** is only needed for Bluetooth-button air gestures, which we are not doing.

## Architecture overview

```
NotesListScreen (new bottom-nav tab)
    └── NoteEditorScreen (full-screen canvas)
            └── DrawingCanvas (custom Composable, pointerInteropFilter -> MotionEvent)
                    └── Stroke[] (in-memory while editing)
                            └── serialized to JSON -> Room "notes" table on save
```

## Data layer

**New entity** — `app/src/main/java/com/aichat/sandbox/data/model/Note.kt`

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val strokesJson: String,       // serialized List<Stroke>
    val canvasWidth: Int,           // logical canvas size for replay
    val canvasHeight: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

**Stroke model** (in-memory, serialized with kotlinx.serialization or Moshi — pick whichever the app already uses; check existing data classes):

```kotlin
@Serializable data class Stroke(
    val points: List<StrokePoint>,
    val colorArgb: Int,
    val baseWidthPx: Float,
    val tool: String   // "pen" | "highlighter" | "eraser"
)
@Serializable data class StrokePoint(
    val x: Float, val y: Float,
    val pressure: Float,   // 0..1
    val tilt: Float,       // radians
    val tMs: Long          // time offset from stroke start, for replay/animation
)
```

**DAO** — `app/src/main/java/com/aichat/sandbox/data/local/NoteDao.kt` — mirror `ChatDao` exactly:
- `getAllNotes(): Flow<List<Note>>` ordered by `updatedAt DESC`
- `getNoteById(id: String): Note?`
- `insertNote(note: Note)`, `updateNote(note: Note)`, `deleteNote(note: Note)`

**Database** — edit `app/src/main/java/com/aichat/sandbox/data/local/AppDatabase.kt`:
- Add `Note::class` to `@Database(entities = ...)`
- Bump version `3 -> 4`
- Add `MIGRATION_3_4` that creates the `notes` table (follow the existing `MIGRATION_2_3` pattern)
- Add `abstract fun noteDao(): NoteDao`

**Repository** — `app/src/main/java/com/aichat/sandbox/data/repository/NoteRepository.kt` — thin pass-through over `NoteDao`, mirror `ChatRepository`.

**DI** — edit `app/src/main/java/com/aichat/sandbox/di/AppModule.kt`, add `@Provides fun provideNoteDao(db: AppDatabase) = db.noteDao()`.

## UI layer

**Navigation** — edit `app/src/main/java/com/aichat/sandbox/ui/navigation/Navigation.kt`:
- Add `data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)` to the `Screen` sealed class
- Add `Notes` to `bottomNavItems`
- Register two composables in the `NavHost`:
  - `composable("notes") { NotesListScreen(onOpenNote = { id -> navController.navigate("note/$id") }) }`
  - `composable("note/{noteId}", arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { NoteEditorScreen(...) }`
  - Use `"note/new"` as a sentinel id for a fresh note (mirrors how chat detail is opened)

**NotesListScreen** — `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NotesListScreen.kt`:
- Mirror `ChatListScreen` (LazyColumn of clickable Surface rows, empty-state, clickable "New note" header row)
- Each row: small thumbnail preview rendered by re-drawing strokes into a small `Canvas` at scaled-down size, plus title + relative time
- Long-press → delete confirmation

**NoteEditorScreen** — `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NoteEditorScreen.kt`:
- TopAppBar with: back arrow (saves on exit), editable title `TextField`, undo, redo, save
- Bottom toolbar: pen / highlighter / eraser, color swatches, stroke-width slider, clear-canvas
- Center: `DrawingCanvas` filling remaining space
- `NoteEditorViewModel` holds `strokes: SnapshotStateList<Stroke>`, an `undoStack` and `redoStack`

**DrawingCanvas** — `app/src/main/java/com/aichat/sandbox/ui/components/DrawingCanvas.kt` — the only genuinely new piece. Outline:

```kotlin
@Composable
fun DrawingCanvas(
    strokes: List<Stroke>,
    currentTool: Tool,
    onStrokeFinished: (Stroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    var liveStroke by remember { mutableStateOf<MutableList<StrokePoint>?>(null) }

    AndroidView(  // or pointerInteropFilter on a Box
        factory = { ctx ->
            View(ctx).apply {
                setOnTouchListener { _, ev ->
                    val isStylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                    // Palm rejection: ignore non-stylus pointers entirely once a stylus is active
                    if (!isStylus) return@setOnTouchListener false
                    when (ev.actionMasked) {
                        ACTION_DOWN -> liveStroke = mutableListOf(point(ev))
                        ACTION_MOVE -> {
                            // include historical samples for high-rate S-Pen
                            for (h in 0 until ev.historySize) liveStroke?.add(historyPoint(ev, h))
                            liveStroke?.add(point(ev))
                        }
                        ACTION_UP, ACTION_CANCEL -> {
                            liveStroke?.let { onStrokeFinished(Stroke(it, ...)) }
                            liveStroke = null
                        }
                    }
                    invalidate(); true
                }
            }
        },
        update = { /* trigger redraw when strokes change */ }
    )
    // Also overlay a Compose Canvas that draws strokes + liveStroke each frame
}
```

Key S-Pen specifics to bake in:
- `ev.getToolType(i) == TOOL_TYPE_STYLUS` — accept only stylus pointers; this is the palm-rejection mechanism
- `ev.pressure` and `ev.getAxisValue(AXIS_TILT)` per point → drive variable line width (`baseWidthPx * (0.4f + 0.6f * pressure)`)
- Iterate `historySize` in `ACTION_MOVE` — the S-Pen samples faster than the frame rate; skipping history gives a jagged line
- Render with `Path` + quadratic Bézier smoothing between consecutive points (`path.quadTo(p1, midpoint(p1, p2))`) — a classic two-point smoothing pass is enough
- Eraser tool = stroke that hit-tests existing strokes and removes them from the list (vector-aware erase, not a white stroke)

**Manifest** — `app/src/main/AndroidManifest.xml`: no permission changes needed. Optional: declare `<uses-feature android:name="android.hardware.type.stylus" android:required="false" />` for clarity.

## Files to create / modify

Create:
- `data/model/Note.kt`
- `data/model/Stroke.kt`
- `data/local/NoteDao.kt`
- `data/repository/NoteRepository.kt`
- `ui/screens/notes/NotesListScreen.kt`
- `ui/screens/notes/NoteEditorScreen.kt`
- `ui/screens/notes/NoteEditorViewModel.kt`
- `ui/components/DrawingCanvas.kt`

Modify:
- `data/local/AppDatabase.kt` (entity list, version bump, migration)
- `di/AppModule.kt` (NoteDao + NoteRepository providers)
- `ui/navigation/Navigation.kt` (Screen entry, bottomNavItems, NavHost routes)

## Verification

End-to-end test plan (manual, on the S25 Ultra):
1. Build and install. App still launches; existing chats still load (migration succeeded).
2. New "Notes" tab appears in the bottom nav.
3. Tap "New note" → editor opens full-screen with empty canvas.
4. Draw with S-Pen: line should follow nib smoothly with no lag and **variable width** that thickens when pressing harder.
5. Rest palm on screen while drawing → palm strokes do not appear (palm rejection works).
6. Try undo/redo, switch colors, switch to highlighter (semi-transparent overlay), eraser removes whole strokes.
7. Set a title, back out → returns to list, note is at top with thumbnail.
8. Reopen the note → all strokes redrawn identically (Room round-trip works).
9. Force-quit and relaunch → note still there.
10. Long-press → delete → note disappears from list.

Lightweight unit checks worth adding alongside:
- `NoteDao` insert/read/delete (in-memory Room) — mirror the pattern in any existing `ChatDaoTest` if one exists.
- Stroke JSON serialization round-trip preserves pressure/tilt within float tolerance.

## Out of scope (deliberately)

- Handwriting → text OCR (ML Kit Digital Ink). Easy follow-up: add a "Convert to text" button in the editor that ships strokes to `DigitalInkRecognizer` and either copies the result or sends it to a chat.
- Attaching a note to a chat message. Plumbing exists (image attachments) — could rasterize a note to PNG and reuse that flow later.
- Cloud sync / export to PDF.
- S Pen Bluetooth button shortcuts (would need the Samsung S Pen Remote SDK).
