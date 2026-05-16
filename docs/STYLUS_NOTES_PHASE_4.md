# Phase 4 — Detailed Sub-Phase Breakdown

> Companion to `STYLUS_NOTES_PLAN.md`, `STYLUS_NOTES_PHASE_1.md`, `STYLUS_NOTES_PHASE_2.md`, and `STYLUS_NOTES_PHASE_3.md`. Phase 4 turns the notes into output: PNG share, PDF export with multi-page tiling, an in-app "Send to chat" picker that prefills a chat draft with a rendered note, and a chat-side "pin note as context" affordance that re-renders the pinned note on every turn.
>
> **Pre-requisites:** Phases 1–3 are shipped. Notes save reliably, OCR populates `ocrText`, the AI side sheet works, and the chat composer supports image attachments. `NoteRasterizer` from Phase 2.2 is in place.
>
> **Golden rules** (same as Phases 1–3):
> 1. App still launches; existing chats + notes still work.
> 2. Green build + tests in the touched area.
> 3. Don't pull in scope from later sub-phases.
> 4. One sub-phase, one PR.

---

## Sub-phase 4.1 — `NoteExporter` core + PNG share target

**Goal:** "Share as PNG" works end-to-end. A `NoteExporter` class owns rendering + writing to a shareable URI via `FileProvider`. This sub-phase introduces the `FileProvider` (we don't have one today), so it's the entry point for all future file-export work.

### Scope
- `NoteExporter.exportPng(note, items, margin = 64f): Uri` — renders to a PNG in `cacheDir/exports/`, returns a content URI via `FileProvider`.
- `FileProvider` declaration + `res/xml/file_provider_paths.xml`.
- TopAppBar overflow menu: **Share → PNG**.
- Standard `Intent.ACTION_SEND` with `EXTRA_STREAM` + `image/png` MIME.
- Atomic write (`.tmp` → rename) to avoid sharing a half-written file.
- Cleanup: prune `cacheDir/exports/` to last N files (e.g. 20) on each export.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteExporter.kt`
- `app/src/main/res/xml/file_provider_paths.xml`

### Files to modify
- `app/src/main/AndroidManifest.xml` — add `<provider>` block:
  ```xml
  <provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
      android:name="android.support.FILE_PROVIDER_PATHS"
      android:resource="@xml/file_provider_paths" />
  </provider>
  ```
- `ui/screens/notes/NoteEditorScreen.kt` — overflow menu Share → PNG.
- `ui/screens/notes/NoteEditorViewModel.kt` — `sharePng(): Uri` (delegates to exporter).
- `di/AppModule.kt` — provide `NoteExporter`.

### Step-by-step
1. `file_provider_paths.xml`:
   ```xml
   <paths xmlns:android="http://schemas.android.com/apk/res/android">
     <cache-path name="exports" path="exports/" />
     <files-path name="note-thumbs" path="note-thumbs/" />
   </paths>
   ```
   (The `note-thumbs` path is added preemptively in case we later need to share thumbnails directly. Costs nothing.)
2. `NoteExporter.exportPng`:
   ```kotlin
   suspend fun exportPng(note: Note, items: List<NoteItem>, margin: Float = 64f): Uri =
       withContext(Dispatchers.IO) {
           val bounds = boundsOf(items).inflate(margin) ?: defaultPaperBounds()
           val bitmap = NoteRasterizer.render(items, bounds, maxEdgePx = 2048, background = note.backgroundStyle)
           val dir = File(context.cacheDir, "exports").apply { mkdirs() }
           val out = File(dir, "${note.id}-${System.currentTimeMillis()}.png")
           val tmp = File(out.parentFile, out.name + ".tmp")
           tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
           tmp.renameTo(out)
           pruneOld(dir, keep = 20)
           FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
       }
   ```
3. Share intent assembly:
   ```kotlin
   val send = Intent(Intent.ACTION_SEND).apply {
       type = "image/png"
       putExtra(Intent.EXTRA_STREAM, uri)
       addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }
   context.startActivity(Intent.createChooser(send, "Share note as PNG"))
   ```
4. Overflow menu: add `DropdownMenu` to TopAppBar with **Share → PNG** (placeholder PDF entry disabled until 4.2; greyed with tooltip "Coming next").
5. Unit test (where possible): pure helpers (`boundsOf`, `inflate`, `pruneOld`) are unit-testable. The `Bitmap.compress` and `FileProvider.getUriForFile` paths need instrumented coverage; one happy-path instrumented test is enough.

### Definition of done
- Sharing a note → system chooser appears → picking Gmail / Drive / etc. attaches the PNG and it opens correctly in the receiving app.
- Re-sharing the same note 25 times → only the most recent 20 files remain in `cacheDir/exports/`.
- Existing chats + notes still work.

### Explicit non-goals
- PDF export — 4.2.
- "Send to chat" picker — 4.3.
- Auto-saving the PNG to the user's gallery — out of scope; the share sheet handles "Save to Photos".

### Risks
- `FileProvider` authority must exactly match the `<provider>` declaration. Mismatches surface as obscure `IllegalArgumentException` at share time — verify on a clean install.
- Cache pruning is best-effort; on devices with very tight cache eviction, files may disappear before the receiving app reads them. The share intent flags grant access for the lifetime of the receiving activity; this is fine in practice.

---

## Sub-phase 4.2 — PDF export with fit-page / tile-to-pages dialog

**Goal:** "Share as PDF" produces a single-file PDF. Because the canvas is infinite, the user picks a layout strategy at export time: **Fit to one page** (scale-to-fit) or **Tile across multiple pages** (multi-page A4/Letter grid). Default page size follows the device locale.

### Scope
- `NoteExporter.exportPdf(note, items, layout, pageSize, margin): Uri` using `android.graphics.pdf.PdfDocument`.
- Layout enum: `FIT_ONE_PAGE`, `TILE`. Tile direction is reading-order: left→right, top→bottom.
- Page size: `A4` or `LETTER`, defaulted by locale (`Locale.getDefault().country in {"US","LR","MM"} → LETTER else A4`).
- Confirmation dialog before export: `[ Fit to one page | Tile across pages ]`, page-size dropdown, page-count preview ("This will produce 6 pages").
- Share via the same `FileProvider` (cached in `cacheDir/exports/`).

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/notes/PdfLayout.kt` — pure layout math + tests.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/ExportPdfDialog.kt`
- `app/src/test/java/com/aichat/sandbox/data/notes/PdfLayoutTest.kt`

### Files to modify
- `data/notes/NoteExporter.kt` — `exportPdf` method.
- `ui/screens/notes/NoteEditorScreen.kt` — overflow menu Share → PDF (enabled now); shows dialog before exporting.
- `ui/screens/notes/NoteEditorViewModel.kt` — `sharePdf(layout, pageSize): Uri`.

### Step-by-step
1. Page sizes in points (1pt = 1/72 inch):
   - A4: `595 × 842`
   - Letter: `612 × 792`
2. `PdfLayout`:
   - `fitOnePage(bounds, pageSize, margin) → Matrix` — scale-to-fit + center.
   - `tile(bounds, pageSize, margin) → List<TileSpec>` where each `TileSpec` has page index and a sub-bounds slice in world coords.
   - Tests:
     - Fit math is correct for portrait and landscape bounds.
     - Tiling produces ceil(width / usableWidth) × ceil(height / usableHeight) pages.
     - Tile slices are exactly contiguous and cover the full bounds.
3. `exportPdf` flow:
   ```kotlin
   val doc = PdfDocument()
   when (layout) {
       FIT_ONE_PAGE -> {
           val page = doc.startPage(PdfDocument.PageInfo.Builder(pageSize.w, pageSize.h, 1).create())
           val matrix = PdfLayout.fitOnePage(bounds, pageSize, margin)
           page.canvas.setMatrix(matrix)
           drawBackground(page.canvas, note.backgroundStyle, bounds)
           items.forEach { drawItem(page.canvas, it) }
           doc.finishPage(page)
       }
       TILE -> {
           val tiles = PdfLayout.tile(bounds, pageSize, margin)
           tiles.forEachIndexed { i, t ->
               val page = doc.startPage(PdfDocument.PageInfo.Builder(pageSize.w, pageSize.h, i + 1).create())
               val matrix = t.toCanvasMatrix(pageSize, margin)
               page.canvas.setMatrix(matrix)
               drawBackground(page.canvas, note.backgroundStyle, t.worldBounds)
               items.filter { it.boundingBox().intersects(t.worldBounds) }
                    .forEach { drawItem(page.canvas, it) }
               doc.finishPage(page)
           }
       }
   }
   doc.writeTo(tmp.outputStream())
   doc.close()
   tmp.renameTo(out)
   ```
4. Dialog (`ExportPdfDialog`): two radio buttons (Fit / Tile), page-size dropdown, live page-count preview by calling `PdfLayout.tile(...).size` (only relevant for Tile mode), Cancel / Export buttons.
5. After export, fire the same share intent as 4.1 but with `type = "application/pdf"`.

### Definition of done
- A note that fits in one A4 page exports as a 1-page PDF, scaled correctly.
- A wide note (e.g. 3 × A4 width) with Tile mode exports as a multi-page PDF; pages tile in reading order with no gaps and no overlap; opening the PDF on the device renders ink at the right positions.
- `PdfLayoutTest` covers the math.
- Switching layout in the dialog updates the live page-count preview.

### Explicit non-goals
- Vector PDF (drawing strokes as PDF path objects) — we rasterize per page via the same `Canvas` path. True vector PDF requires `PdfRenderer`-equivalent vector emission, far out of scope.
- Custom page sizes / orientation choice — A4 / Letter only; orientation matches the page's natural portrait.
- OCR'd text layer in the PDF — selectable text in the exported PDF is a follow-up.

### Risks
- `PdfDocument` page count + memory: tiling 4×4 = 16 pages each rendered into the same `Document` then written to disk uses moderate memory. Render one page at a time and never hold all pages in memory.
- `Canvas.setMatrix` semantics differ between hardware and software canvases. `PdfDocument`'s canvas is software — matrices behave predictably; strokes still anti-alias if `Paint.isAntiAlias = true`. Test against a real PDF viewer.

---

## Sub-phase 4.3 — "Send to chat" picker

**Goal:** in the note editor's share menu and in the AI side sheet's reply actions, "Send to chat" opens a bottom-sheet picker over existing chats. Picking one navigates to that chat with a pre-filled draft containing the rendered note image + OCR text snippet. Creating a new chat from the picker is also possible.

### Scope
- `SendToChatSheet`: modal bottom sheet listing existing chats (mirrors `ChatListScreen` row style — title + last-message preview + relative time).
- "+ New chat" row at the top.
- Selecting a chat:
  1. Renders the note (or reuses a fresh PNG export).
  2. Encodes as base64 data URI.
  3. Navigates to `chat/{chatId}?draftText={ocrSnippet}&draftImage={base64Uri}`.
- `ChatScreen` accepts the new `draftImage` arg, decodes it, prepends to the composer's `imageList` (alongside existing draftText prefill from Phase 2.8).
- Replaces the "create new chat" stub from Phase 2.8 (the new-chat row in the sheet routes through the same picker logic).

### Files to create
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/SendToChatSheet.kt`

### Files to modify
- `ui/screens/notes/NoteEditorScreen.kt` — share menu adds "Send to chat" entry that opens the sheet (uses a fresh PNG render).
- `ui/screens/notes/AiSideSheet.kt` — wires the existing "Send to chat" reply action through the picker.
- `ui/screens/chat/ChatScreen.kt` — handle `draftImage` arg the same way the photo picker results land in `imageList`; strip after read.
- `ui/navigation/Navigation.kt` — route `chat/{chatId}?draftText={text}&draftImage={uri}` (URL-encoded).
- `ui/screens/notes/NoteEditorViewModel.kt` — `prepareSendToChat(): SendToChatPayload` (image bytes + OCR snippet).

### Step-by-step
1. Payload:
   ```kotlin
   data class SendToChatPayload(val pngUri: Uri, val ocrSnippet: String)
   ```
   `ocrSnippet` = first 200 chars of `note.ocrText` (or empty if not yet OCR'd).
2. The picker observes `chatRepository.observeChats()` for the list. Sorting matches the chat list screen.
3. New-chat path: tap the "+ New chat" row → create via `chatRepository.createChat()` → navigate using the new chat's id.
4. Draft image handover. **Decision point:** how do we pass the image through navigation safely?
   - Option A: encode the PNG as a base64 data URI in the query string (cap at ~250 KB; abort with an error toast above that).
   - Option B: persist a `pending_draft` row keyed by chatId, read+delete on the chat screen.
   - **Pick B.** Query strings carrying large blobs are fragile (URL length limits, encoding issues, logs leaking the body). Add a small `PendingDraftStore` (in-memory `ConcurrentHashMap<chatId, Pair<imageUri, draftText>>` scoped to the process); the chat screen reads it once on entry. If the process dies, the draft is lost — acceptable.
5. Wire AiSideSheet's "Send to chat" reply action through the same sheet/picker (now replacing the 2.8 simple new-chat behavior, which becomes "tap + New chat in this picker").

### Definition of done
- From the note editor share menu, "Send to chat" opens a picker → picking a chat lands you in that chat with a thumbnail of the note already in the attachment strip and a text snippet in the composer.
- Sending the message includes both the image and the text to the API (verify logs).
- From the AI side sheet, "Send to chat" on a reply does the same — image is the rendered note (or selection if selection-scoped), text is the AI reply.
- "+ New chat" path creates a chat and lands in it with the attachment.
- Process-kill mid-flow loses the draft without crash.

### Explicit non-goals
- Multi-select (send to multiple chats at once).
- Sending in the background without opening the chat.
- Persisting the draft across process death — `PendingDraftStore` is in-memory only.

### Risks
- Forgetting to strip the draft args / `PendingDraftStore` entry after first read will re-prefill on every rotation. Read-and-delete on `LaunchedEffect(chatId)`.
- The attachment-strip UI in `ChatScreen` (Phase 1-era code, around line 738) already supports multiple images. Inserting our PNG at index 0 should compose cleanly; verify visually.

---

## Sub-phase 4.4 — Chat-side "pin note as context" affordance + Phase 4 verification

**Goal:** the user can pin a note to a chat. Every subsequent turn re-renders the note's current state and includes it as a context image (and OCR text hint, for non-vision models). Pinning is per-chat, persisted, and visible in the chat UI. Then the Phase 4 verification matrix is run.

### Scope
- `Chat.pinnedNoteId: String?` column (Room migration `4 → 5`).
- Pin UI: an icon in the chat TopAppBar overflow menu, **Pin a note** → opens a note picker; if already pinned, the menu shows **Unpin "{title}"**.
- Visible affordance: a chip near the composer reading "📎 {title}" with an `×` to unpin.
- On every send, `ChatViewModel.sendMessage` checks `chat.pinnedNoteId`; if non-null, renders the note via `NoteRasterizer` (background dispatcher), attaches as the *first* image of the outgoing user message, and prepends an OCR hint to the system message (or the user message — see step 3 decision).
- Render caching: if the note hasn't changed since the last pin render (compare `note.updatedAt`), reuse the cached PNG.
- Phase 4 verification pass.

### Files to create
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/NotePickerSheet.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/PinnedNoteCache.kt`
- `app/src/androidTest/java/com/aichat/sandbox/data/local/Migration_4_5_Test.kt`

### Files to modify
- `data/model/Chat.kt` — add `pinnedNoteId: String? = null`.
- `data/local/AppDatabase.kt` — bump version `4 → 5`.
- `data/local/Migrations.kt` — `MIGRATION_4_5` adding the nullable column.
- `data/repository/ChatRepository.kt` — `setPinnedNote(chatId, noteId?)`.
- `ui/screens/chat/ChatScreen.kt` — overflow Pin entry + composer chip.
- `ui/screens/chat/ChatViewModel.kt` — pinned-note rendering on send.
- `data/remote/ApiClient.kt` — no API change; reuse multimodal path. Possibly accept an extra "context image" parameter so we don't need to mutate the outgoing `Message` row in the DB — see step 4.

### Step-by-step
1. Migration:
   ```kotlin
   val MIGRATION_4_5 = object : Migration(4, 5) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE chats ADD COLUMN pinnedNoteId TEXT")
       }
   }
   ```
   Add to `.addMigrations(...)`. Migration test asserts existing chat rows preserve all data and have `pinnedNoteId = null` after.
2. `PinnedNoteCache`: in-memory cache keyed by `noteId`, storing `(updatedAt, File)`. Invalidates when the note's `updatedAt` changes; lazy renders on first send after invalidation.
3. **Decision: where does the pinned image attach?**
   - Option A: mutate the user's outgoing `Message.metadata` to include the pinned PNG in addition to user-chosen attachments. Persists in DB → re-sent on every regen.
   - Option B: keep the user's row clean; attach the pinned image **only in the API call**, not in the DB. The chat transcript shows the user's actual message; the model sees the image transparently.
   - **Pick B.** Persisting the same image in every user row bloats the DB. Modify `ApiClient.sendMessageStream` (or just `buildApiMessages`) to accept an optional `extraImageOnLastUserTurn: ByteArray?` and append it to the final user message's content parts. Vision-only path; for non-vision models, prepend `ocrText` to the system message instead.
4. `ChatViewModel.sendMessage` logic:
   ```kotlin
   val pinnedNote = chat.pinnedNoteId?.let { noteRepository.getNote(it) }
   val (extraImage, extraSystemText) = pinnedNote?.let { note ->
       if (chat.model.supportsVision()) {
           pinnedNoteCache.getOrRender(note) to null
       } else {
           null to "Pinned note (OCR):\n${note.ocrText.orEmpty().take(2000)}"
       }
   } ?: (null to null)
   apiClient.sendMessageStream(..., extraImage = extraImage, extraSystemSuffix = extraSystemText)
   ```
5. UI:
   - Overflow menu: "Pin a note" → opens `NotePickerSheet` (list of notes from `noteRepository.observeNotes()`, same row style as `NotesListScreen`).
   - When `pinnedNoteId != null`, replace the menu entry with "Unpin {title}".
   - Composer chip: small `AssistChip` above the composer with note title and a close icon (unpins).
6. **Phase 4 verification matrix** (run on real S25 Ultra; paste into PR):
   1. All Phase 1–3 verification items still pass (sanity sample, not full re-run).
   2. Note editor share → PNG → image opens correctly in Gallery.
   3. Note editor share → PDF → Fit to one page → opens as 1-page PDF in a viewer.
   4. Note editor share → PDF → Tile across pages → opens as N pages, no gaps/overlap.
   5. Note editor "Send to chat" → picker → existing chat → composer shows PNG + OCR snippet.
   6. Note editor "Send to chat" → picker → + New chat → fresh chat opens with attachment.
   7. AI side-sheet reply "Send to chat" → picker → existing chat → attachment is the rendered selection / note.
   8. Pin a note to a chat → chip appears → sending a message includes the rendered note in the API call (verify via logcat or response).
   9. Modify the pinned note's strokes, send another message → API sees the updated rendering, not the cached one.
   10. Unpin → chip disappears; subsequent messages do not include the note.
   11. Pin a note while on a non-vision model → OCR text appears in the system message instead of an image (verify via logcat).
   12. Force-quit and relaunch → `pinnedNoteId` persists; chip is restored.

### Definition of done
- Pinning works for both vision and non-vision models.
- Cache invalidates when the pinned note changes.
- All 12 verification items pass.
- `Migration_4_5_Test` passes.
- No regressions in Phase 1 / 2 / 3 verification sampling.

### Explicit non-goals
- Multiple pinned notes per chat (single-pin only).
- Pinning a *selection* of a note — whole-note only.
- A separate "context preview" sheet so the user can see exactly what's being sent — follow-up.
- Re-running OCR on the pinned note when its strokes change (the OCR-on-save pipeline from 2.4 handles this naturally — pinning relies on whatever `ocrText` is current).

### Risks
- Sending the pinned image on every turn balloons API costs. Make the chip and the affordance obvious so the user knows what they're paying for. (Optional: a small `~{n} kB context attached` label on the chip — nice-to-have.)
- Cache invalidation by `updatedAt` is correct only if every stroke mutation goes through `saveNote()` which bumps `updatedAt`. Verify there's no path that mutates items without bumping the parent note (audit `NoteRepository.saveNote` from Phase 1.1).
- Modifying `ApiClient` to accept extra image parameters means churn in a file that other features touch. Keep the change additive — the new params default to `null` and existing call sites pass nothing.

---

## Sequencing notes

- 4.1 introduces `FileProvider`, which 4.2 and 4.3 depend on.
- 4.2 (PDF) and 4.3 (Send to chat picker) are independent of each other and can swap order.
- 4.4 depends on 4.3 (uses the same `NotePickerSheet`) and on 4.1 (rasterization pipeline). Ship it last so the Phase 4 verification sweeps everything.
- Migration `4 → 5` lands in 4.4. Don't bundle the migration into an earlier sub-phase — chat-side persistence is a 4.4-only concern.

## Sub-phase quick reference

| # | Title | Net new files | Touch existing | Risk |
| --- | --- | --- | --- | --- |
| 4.1 | NoteExporter + PNG share | 2 | 4 | Low |
| 4.2 | PDF export + layout dialog | 3 | 3 | Medium |
| 4.3 | Send to chat picker | 1 | 5 | Medium |
| 4.4 | Pin note as context + verification | 3 | 6 | **High** |
