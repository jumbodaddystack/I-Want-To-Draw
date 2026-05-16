# Phase 2 — Detailed Sub-Phase Breakdown

> Companion to `STYLUS_NOTES_PLAN.md` and `STYLUS_NOTES_PHASE_1.md`. Phase 2 puts AI on the canvas: OCR runs on save, two entry points (lasso "Ask" and toolbar "Ask about this note") open a side sheet that streams a model reply, and replies can be inserted as a text box or sent to chat. Same single-session sizing as Phase 1.
>
> **Pre-requisites:** all of Phase 1 (1.1–1.10) is shipped and the manual verification matrix passed. Phase 2 builds on the data model, `DrawingSurface`, lasso selection, text-box items, and undo/redo from Phase 1.
>
> **Golden rules for every sub-phase** (same as Phase 1):
> 1. App still launches; existing chats and notes still load.
> 2. Green build (`./gradlew assembleDebug`) + any tests in the touched area.
> 3. Don't pull in scope from later sub-phases. Stubs with a TODO referencing the next sub-phase are encouraged.
> 4. One sub-phase, one PR. No merging "because they're small".

---

## Sub-phase 2.1 — Vision-capability registry + dependencies + manifest

**Goal:** the app can answer "does this model support image inputs?" reliably from a single source of truth. Add the two third-party libs Phase 2 will lean on. Nothing visible to the user yet.

### Scope
- Add ML Kit Digital Ink dependency.
- Add a small `ModelCapabilities` registry (model id → `{ supportsVision: Boolean, contextWindow: Int? }`) with a sane default fallback by name pattern.
- Wire it through `ChatViewModel` / `NoteEditorViewModel` so anything that has a model id can ask `model.supportsVision()`.
- Manifest tweaks for the ML Kit downloader.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/model/ModelCapabilities.kt`
- `app/src/test/java/com/aichat/sandbox/data/model/ModelCapabilitiesTest.kt`

### Files to modify
- `app/build.gradle.kts`
  - `implementation("com.google.mlkit:digital-ink-recognition:18.1.0")` (pin to latest stable at the time of implementation).
- `app/src/main/AndroidManifest.xml`
  - Confirm `INTERNET` permission already present (it is — chat needs it).
  - Add the ML Kit module metadata tag inside `<application>`:
    `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="ica" />` — actually for digital-ink the dependency is auto-downloaded; verify against current ML Kit docs and only add if required by the version pinned.
- `data/remote/ApiClient.kt` — no behavioral change; only add a `Chat.supportsVision()` extension reading `ModelCapabilities`. (Kept minimal — actual vision-aware routing is 2.5.)

### Step-by-step
1. Decide the registry shape:
   ```kotlin
   object ModelCapabilities {
       data class Caps(val supportsVision: Boolean, val notes: String = "")
       private val table: Map<String, Caps> = mapOf(
           "gpt-4o" to Caps(true),
           "gpt-4o-mini" to Caps(true),
           "gpt-4-turbo" to Caps(true),
           "gpt-4" to Caps(false),
           "gpt-3.5-turbo" to Caps(false),
           // Anthropic
           "claude-opus-4-7" to Caps(true),
           "claude-sonnet-4-6" to Caps(true),
           "claude-haiku-4-5" to Caps(true),
           // …
       )
       fun of(modelId: String): Caps = table[modelId] ?: inferFromName(modelId)
       private fun inferFromName(id: String): Caps {
           val l = id.lowercase()
           val vision = l.contains("vision") || l.contains("4o") ||
                        l.contains("claude") || l.contains("gemini") || l.contains("multimodal")
           return Caps(vision, notes = "inferred")
       }
   }
   ```
2. Add `fun String.supportsVision(): Boolean = ModelCapabilities.of(this).supportsVision`.
3. Tests cover: known ids hit the table, unknown ids hit the inference path, blank string returns `false`.
4. Sync gradle and confirm ML Kit's transitive deps don't conflict with anything in the existing OkHttp/Retrofit stack.

### Definition of done
- `./gradlew :app:assembleDebug` green.
- `ModelCapabilitiesTest` passes.
- Selecting any model in chat continues to behave exactly as before (no behavior change yet).
- No new Internet permission requests on first launch.

### Explicit non-goals
- No NoteAiService yet.
- No OCR yet — just the dep is added.
- No UI change.

### Risks
- The vision registry will go out of date as new models ship. The inference fallback covers most cases, and the registry is centralized so updates are one-PR.
- ML Kit pulls a sizeable native library. Check apk size delta and note it in the PR description; if it's > ~6 MB compressed, decide whether to split into a dynamic feature module (out of scope for now — note as follow-up).

---

## Sub-phase 2.2 — Note rasterizer (selection + whole note → PNG bytes)

**Goal:** any caller can ask "give me a PNG of this set of items at this resolution". Used by the AI service (2.5) and by Export (Phase 4); also unifies the thumbnail rendering from 1.10.

### Scope
- `NoteRasterizer.render(items, bounds, maxEdgePx, background)` → `Bitmap`.
- `NoteRasterizer.toPng(bitmap, quality = 100)` → `ByteArray`.
- Convenience `renderSelection(items)` and `renderNote(note, items)`.
- Refactor 1.10's thumbnail logic to call this — no duplication.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteRasterizer.kt`
- `app/src/test/java/com/aichat/sandbox/data/notes/NoteRasterizerTest.kt` (renders into a small in-memory bitmap, asserts pixel-color presence in expected regions).

### Files to modify
- `data/repository/NoteRepository.kt` — `renderThumbnail` now delegates to `NoteRasterizer`.

### Step-by-step
1. `bounds`: union of item bounding boxes; if empty, return `null` and let the caller decide.
2. `scale = maxEdgePx / max(bounds.width, bounds.height)`, clamp to `[0.1, 4.0]`.
3. Allocate `Bitmap` at `(bounds.width * scale, bounds.height * scale)`, `ARGB_8888`.
4. Translate canvas to `-bounds.left`, scale by `scale`, draw background (matching the note's `backgroundStyle`), then draw each item via the same `StrokeRenderer` helpers used by `DrawingSurface` so output matches what the user sees.
5. PNG encode via `Bitmap.compress(PNG, 100, outputStream)`.
6. Refactor `renderThumbnail` to be a 5-line call to `NoteRasterizer`.

### Definition of done
- Existing thumbnails still render identically (or close — diff visually on three sample notes).
- `NoteRasterizerTest` passes.
- Calling `renderSelection(items)` with a synthetic single-stroke item produces a bitmap whose non-transparent pixel count > 0.

### Explicit non-goals
- No PDF support — Phase 4.
- No streaming for huge notes — all-at-once render only.
- No vector / SVG export.

### Risks
- For massive notes (>10k strokes), rasterizing the whole canvas at 1024px is fine; at 4096px it can OOM. Cap `maxEdgePx` at 2048 in the AI path (2.5) and document.
- Background rendering must respect `backgroundStyle`; otherwise the AI sees a transparent void and may hallucinate paper context.

---

## Sub-phase 2.3 — Handwriting OCR (ML Kit Digital Ink)

**Goal:** given a list of stroke `NoteItem`s, produce a best-effort text transcription. Self-contained — no UI yet, no auto-trigger on save. Includes the model-download lifecycle so the first call is observable, not silent.

### Scope
- `HandwritingOcr` class with `suspend fun recognize(strokes: List<NoteItem>, locale: String = "en-US"): OcrResult`.
- Model download lifecycle exposed as a `StateFlow<OcrModelState>` (`NotDownloaded`, `Downloading(progress)`, `Ready`, `Failed(reason)`).
- Suspend `ensureModelReady()` that downloads on first call.
- Stroke timestamps: ML Kit Digital Ink wants per-point timestamps. Our codec drops them (per Phase 1 plan). Synthesize evenly-spaced timestamps starting at `t=0` with `1000/240` ms per sample — accuracy is unaffected for printed handwriting; cursive can be slightly worse with synthetic timing. Document the tradeoff.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/notes/HandwritingOcr.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/OcrResult.kt` — `data class OcrResult(val text: String, val confidence: Float, val perWord: List<WordCandidate>)`.
- `app/src/androidTest/java/com/aichat/sandbox/data/notes/HandwritingOcrTest.kt` — instrumented (needs Play Services). Single happy-path with a synthetic "hello" stroke list. May need to be `@Ignore`'d on devices without Play Services; document.

### Files to modify
- `di/AppModule.kt` — `@Provides @Singleton fun provideHandwritingOcr(...): HandwritingOcr`.

### Step-by-step
1. Lazy-init the `DigitalInkRecognitionModelIdentifier` per locale; cache the recognizer once the model is ready.
2. `ensureModelReady()`:
   - Query `RemoteModelManager.getInstance().isModelDownloaded(model)`.
   - If false: `state.value = Downloading(0f)`, call `download(model, DownloadConditions.Builder().build())`.
   - On success: `state.value = Ready`. On failure: `state.value = Failed(reason)`.
   - The ML Kit API doesn't expose download progress granularly; emit `Downloading(0f)` then `Downloading(1f)` around the awaited task. Don't fake a progress curve.
3. `recognize(strokes)`:
   - Build `Ink.Builder()`; for each stroke item, decode payload; create a `Stroke.Builder()`; append points as `Ink.Point.create(x, y, syntheticTimestamp)`.
   - Call `recognizer.recognize(ink).await()`.
   - Map the `RecognitionResult.candidates` to `OcrResult`.
4. Locale selection: default `"en-US"`. Expose `recognize(strokes, locale)` overload but don't surface in UI until the user asks.
5. Error handling: every exception is caught and surfaced as `OcrResult(text = "", confidence = 0f, perWord = emptyList())` plus a log; never throw out of `recognize`.

### Definition of done
- Instrumented test passes on an emulator with Play Services.
- Triggering `ensureModelReady()` on a fresh install downloads the model once; subsequent calls are instant.
- Recognizer accepts decoded Phase-1 strokes without modification.

### Explicit non-goals
- No auto-trigger on save — that's 2.4.
- No multi-locale UI selection.
- No math / shape recognition (separate ML Kit model; deliberately out of scope per the parent plan).

### Risks
- Devices without Google Play Services (some MIUI builds, Huawei) can't use ML Kit. Treat OCR failure as non-fatal everywhere; non-vision models simply won't have an OCR hint to fall back on.
- Synthetic timestamps subtly hurt cursive accuracy. If users report bad cursive OCR, Phase 1's codec can be bumped to schemaVersion 2 with real timestamps — explicit follow-up, not in scope here.

---

## Sub-phase 2.4 — OCR on save (lazy pipeline)

**Goal:** every time a note is saved, OCR runs in the background and updates `Note.ocrText`. Cheap and observable; if OCR fails, the note still saves.

### Scope
- `NoteRepository.runOcr(noteId)` — loads items, calls `HandwritingOcr.recognize`, writes `ocrText` back.
- `NoteEditorViewModel.saveNote()` triggers `runOcr` on `Dispatchers.Default` after the DB write.
- Debounce: if the user re-saves within 2 seconds, skip duplicate OCR runs.
- A small "OCR pending" / "OCR ready" indicator in the editor TopAppBar (optional; can be a single icon that's gray vs filled).

### Files to modify
- `data/repository/NoteRepository.kt`
- `ui/screens/notes/NoteEditorViewModel.kt`
- `ui/screens/notes/NoteEditorScreen.kt` — small TopAppBar indicator.

### Step-by-step
1. `runOcr`:
   ```kotlin
   suspend fun runOcr(noteId: String) = withContext(Dispatchers.Default) {
       ocr.ensureModelReady()  // may suspend on first call
       val items = noteDao.getItems(noteId).filter { it.kind == "stroke" }
       if (items.isEmpty()) return@withContext
       val result = ocr.recognize(items)
       noteDao.updateOcrText(noteId, result.text, System.currentTimeMillis())
   }
   ```
2. Add `NoteDao.updateOcrText(id, text, updatedAt)` (partial update — don't touch other fields).
3. Debounce in the VM with a `Mutex` or by tracking a `lastOcrRequest: Long` and skipping if delta < 2s.
4. Indicator: small icon next to the AI button (renders only if the model is `Downloading` or OCR is running). Tooltip on long-press: "Transcribing handwriting…"

### Definition of done
- Save a note with 5 strokes → check `Note.ocrText` is non-empty within a few seconds (visible via a debug "show OCR text" menu in dev builds, or via an instrumented test).
- Save a 200-stroke note → OCR runs without blocking the UI (no jank).
- Toggling between two notes rapidly doesn't pile up OCR jobs (debounce caught it).
- OCR failure leaves `ocrText` unchanged; no crash.

### Explicit non-goals
- Search using OCR text — separate work; can land independently or as a follow-up.
- Showing OCR text in the AI side sheet — that's 2.5.
- Re-running OCR after a single-stroke add — full re-run only; incremental OCR is out of scope.

### Risks
- Running OCR after every micro-save is wasteful. Debounce + only-on-explicit-save (back press) covers it.
- The download-on-first-use can take 5–30s on slow networks. The indicator covers this UX.

---

## Sub-phase 2.5 — `NoteAiService` (core; no UI)

**Goal:** a fully working AI request pipeline callable from a test. Branches on vision capability; emits a `Flow<AiChunk>`. Doesn't know about Compose, side sheets, or canned prompts.

### Scope
- `NoteAiService.ask(request: AskRequest): Flow<AiChunk>`.
- `AskRequest`: note, optional selection (null = whole note), free-form prompt string, model id.
- Vision branch: rasterize → base64 → build a synthetic `Chat` + `Message` list → call `ApiClient.sendMessageStream`.
- Non-vision branch: ensure `ocrText` is populated (run OCR if missing on a slice), construct a text-only prompt, call the same stream API.
- `AiChunk` sealed: `Delta(text)`, `Complete(usage)`, `Error(message)`.
- Cancellation honored: cancelling the collection cancels the upstream stream.

### Files to create
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteAiService.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/AskRequest.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/AiChunk.kt`
- `app/src/test/java/com/aichat/sandbox/data/notes/NoteAiServiceTest.kt` — uses a fake `ApiClient` interface (extract one if not already).

### Files to modify
- `data/remote/ApiClient.kt` — extract a small interface `ChatStreamer` over `sendMessageStream` so the service can be tested without HTTP. Keep the concrete class unchanged for chat callers.
- `di/AppModule.kt` — provide `NoteAiService`.

### Step-by-step
1. Define the request:
   ```kotlin
   data class AskRequest(
       val note: Note,
       val selection: List<NoteItem>?,
       val userPrompt: String,
       val modelId: String,
       val baseUrl: String,
       val apiKey: String,
   )
   ```
2. Build the user-visible system instruction once:
   `"You are helping the user with a handwritten note. Be concise. If the user pasted an image of the note, treat the handwriting as their input; transcribe relevant parts when answering."`
3. Vision branch (`ModelCapabilities.of(modelId).supportsVision == true`):
   - Compute bounds for `selection ?: items`.
   - `NoteRasterizer.render(items, bounds, maxEdgePx = 1536, background = note.style)` → PNG bytes.
   - Base64-encode; build a synthetic `Message(role="user", contentType="multimodal", metadata = ImageMetadata([ImageData("data:image/png;base64,…")]), content = userPrompt)`.
   - Build a synthetic `Chat(model=modelId, systemMessage=systemInstruction, baseUrl=baseUrl, apiKey=apiKey, …default sampling…)`.
   - Call `chatStreamer.sendMessageStream(...)`.
4. Non-vision branch:
   - If `selection != null`, run OCR on just the selection (synchronously, in this flow).
   - Else use `note.ocrText` if present, else trigger and await `runOcr`.
   - Build a text-only `Message`: `"Transcribed note (may have OCR errors):\n${ocrText}\n\nUser question:\n${userPrompt}"`.
   - Same stream call.
5. Map `StreamEvent` → `AiChunk`:
   ```
   Delta(content)  → AiChunk.Delta(content)
   Complete(usage) → AiChunk.Complete(usage)
   Error(msg)      → AiChunk.Error(msg)
   ToolCallDelta   → ignore (we never request tools in this path)
   ```
6. `NoteAiServiceTest`:
   - Vision: fake `ChatStreamer` emits two deltas + complete; assert chunks pass through unchanged.
   - Non-vision: same, plus a mocked `HandwritingOcr` that returns `"hello world"`; assert the prompt includes that text.
   - Error: fake emits error; assert chunk is `AiChunk.Error`.
   - Cancellation: collector cancels; underlying flow's `onCompletion` fires.

### Definition of done
- All four tests pass.
- A debug menu in dev builds can fire a one-shot `ask(...)` and log chunks to logcat — verify on real device end-to-end with a vision model and a non-vision model.

### Explicit non-goals
- No canned prompts — 2.7.
- No UI — 2.6.
- No reply-action handling — 2.8.
- No conversation history (each ask is a one-shot; multi-turn within the side sheet lands in 2.6 as a list of one-shots, not a chat history packed into the request — see 2.6 risks).

### Risks
- Synthetic `Chat` objects must satisfy whatever invariants `ApiClient.buildApiMessages` expects. Read that method carefully (lines ~290–351 in `ApiClient.kt`) before constructing the synthetic.
- Encoding 1.5 MB of base64 PNG inline in JSON is fine for OpenAI/Anthropic but borderline for some self-hosted endpoints with strict body limits. If we hit issues, lower `maxEdgePx` to 1024.

---

## Sub-phase 2.6 — `AiSideSheet` UI shell + streaming render

**Goal:** the user can open a side sheet, type a prompt, fire it, and watch the response stream in. No canned prompts yet, no reply actions yet — just the wiring. The sheet pulls in the current chat's model selection by default.

### Scope
- `AiSideSheet` composable: right-edge sheet, ~70% screen width on phones, full overlay on small screens.
- `AiSideSheetViewModel` (or state hoisted into `NoteEditorViewModel`) holding the conversation as `List<AskTurn>` plus a streaming buffer.
- Manual open/close via TopAppBar toggle (the actual lasso entry point lands in 2.7).
- Each `AskTurn`: prompt, optional selection summary ("3 strokes selected"), reply text (growing as the stream emits), state (`Streaming`, `Done`, `Error`).
- Cancel button while streaming.
- Conversation persists for the editor's lifetime only (in-memory).

### Files to create
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/AiSideSheet.kt`
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/AiSideSheetState.kt`

### Files to modify
- `ui/screens/notes/NoteEditorScreen.kt` — host the sheet behind a Compose `Scaffold` overlay; toggle via TopAppBar icon (always visible from 2.6 on).
- `ui/screens/notes/NoteEditorViewModel.kt` — own `aiSheetState: StateFlow<AiSideSheetState>` and methods `openSheet(selection)` / `submitPrompt(text)` / `cancelStreaming()` / `closeSheet()`.

### Step-by-step
1. State shape:
   ```kotlin
   data class AskTurn(
       val id: String,
       val prompt: String,
       val selectionSummary: String?,
       val replyBuffer: String,
       val state: TurnState,
   )
   sealed interface TurnState {
       data object Streaming : TurnState
       data object Done : TurnState
       data class Error(val message: String) : TurnState
   }
   data class AiSideSheetState(
       val isOpen: Boolean = false,
       val pendingSelection: List<NoteItem>? = null,
       val turns: List<AskTurn> = emptyList(),
       val inputText: String = "",
       val activeModelId: String,
   )
   ```
2. `submitPrompt`:
   - Capture inputText + pendingSelection.
   - Append a new `AskTurn(state = Streaming, replyBuffer = "")` to `turns`.
   - Launch a coroutine; collect `NoteAiService.ask(...)`; on each Delta, append to that turn's buffer; on Complete, flip to `Done`; on Error, to `Error`.
   - Clear `inputText`.
   - Store the coroutine `Job` so cancel works.
3. UI:
   - Header: title, model name (read-only for now — picker is 2.8 polish), close button.
   - Conversation list: previous turns rendered as alternating `Prompt` / `Reply` bubbles.
   - The active streaming turn shows a typing indicator if the buffer is empty.
   - Footer: `OutlinedTextField` + Send icon button (disabled if input is blank or a stream is running) + Cancel button (visible if streaming).
4. Model selection comes from the latest chat the user touched. Read it from `PreferencesManager` or a `SettingsViewModel`. Fall back to the first item in the chat model list if none.
5. Conversation context: **each turn is independent for now.** The current request does NOT include prior turns. Document this. Multi-turn chat behavior in the side sheet (carrying prior Q&A as conversation history) is a follow-up; 2.6 ships the one-shot loop because it composes cleanly with 2.5 and avoids a token-budget design discussion mid-phase.

### Definition of done
- TopAppBar "Ask" icon opens the sheet.
- Typing a prompt and pressing send streams the reply.
- Cancel during streaming stops the stream within ~200ms.
- Closing the sheet preserves the conversation; reopening shows it again.
- Sheet survives configuration changes (rotation).

### Explicit non-goals
- Canned prompts — 2.7.
- Reply actions (Copy, Insert, Send to chat) — 2.8.
- Multi-turn context packing — follow-up.
- Streaming markdown / rich rendering — render plain text first; markdown can come later.

### Risks
- A long-running stream not tied to `viewModelScope` can leak coroutines on editor exit. Always launch inside `viewModelScope`.
- Compose recomposing on every Delta append at 30+ chunks/sec can jank. Throttle UI updates: buffer deltas to a `StateFlow` and use `sample(50.ms)` for the rendered text, OR mutate a `mutableStateOf` that holds a `Long` "version" + a `StringBuilder` for the actual buffer and read it via `remember(version)`. Measure first; only optimize if jank is visible.

---

## Sub-phase 2.7 — Entry points: toolbar **Ask** + lasso **Ask** + canned prompts + Convert-to-text fast path

**Goal:** the two user-facing entry points fire correctly with the right scope (whole note vs selection), and the canned-prompt buttons are wired. Convert-to-text is special-cased to bypass the chat round-trip.

### Scope
- TopAppBar "Ask about this note" → opens sheet with `selection = null`.
- Lasso floating menu's `Ask` button (was disabled in Phase 1.8) → opens sheet with `selection = currentSelection`.
- Canned-prompt chips inside the sheet: **Explain**, **Expand**, **Convert to text**, **Summarize**, **Continue this**.
  - Each is a one-tap prompt template, e.g. `"Explain this in plain English."`.
  - Tapping fills the input field with the canned prompt and fires immediately (no double-tap).
- **Convert to text** is special: skip `NoteAiService`, call `HandwritingOcr.recognize(selection)` directly, drop the text into the input as a finished `Done` turn (no API spend). Available only when there is a selection.
- Lasso menu's `Convert to text` button (also disabled in 1.8) now invokes the same fast path and **also** offers "Insert as text box" in the result (preview of 2.8 behavior — but only for this fast path; the general reply-action UI is 2.8).

### Files to modify
- `ui/screens/notes/NoteEditorScreen.kt` — wire TopAppBar Ask icon to `viewModel.openSheet(null)`.
- `ui/screens/notes/LassoController.kt` (or wherever the lasso floating menu lives from 1.8) — wire Ask + Convert to text.
- `ui/screens/notes/AiSideSheet.kt` — add canned-prompt chip row above the input field.
- `ui/screens/notes/NoteEditorViewModel.kt` — `convertSelectionToText(): String` calls HandwritingOcr directly; `submitCannedPrompt(label)` resolves the template and calls `submitPrompt`.

### Step-by-step
1. Canned-prompt templates as a `Map<String, String>`:
   ```
   "Explain"        → "Explain this in plain English."
   "Expand"         → "Expand on the ideas in this note. Suggest additional points."
   "Convert to text"→ <sentinel — handled separately>
   "Summarize"      → "Summarize this note in 3–5 bullet points."
   "Continue this"  → "Continue the thought naturally from where it leaves off."
   ```
2. UI: a horizontal `LazyRow` of `AssistChip`s above the text input.
3. Convert-to-text: when invoked from either entry point, bypass `NoteAiService`:
   - Render a fresh `AskTurn(prompt="Convert to text", state=Streaming)` immediately.
   - Launch `HandwritingOcr.recognize(selectionOrAllStrokes)`; on result, mutate the turn to `Done` with the recognized text in `replyBuffer`.
   - On error or empty result, `Error("Couldn't recognize handwriting.")`.
4. Lasso menu now uses the same `openSheet(selection)` path. Phase 1.8 leaves the menu rendering but disabled — flip those two buttons to enabled here.
5. Add a one-line indicator above the chip row showing scope: `"Whole note"` or `"3 strokes selected"`. Tap to toggle clears the selection scope and reopens with `selection = null`.

### Definition of done
- Tapping the TopAppBar Ask icon opens the sheet with "Whole note" scope.
- Lassoing items → Ask → opens sheet with "N items selected" scope.
- Each canned prompt fires a real request and streams a reply (except Convert to text).
- Convert to text returns OCR result without an API call (verify by airplane-mode test).
- Switching between Ask (whole note) and Ask (selection) flows back-to-back works; conversation history shows both.

### Explicit non-goals
- Reply actions still come in 2.8 — Copy / Insert / Send to chat are not in this sub-phase, except the special-case Insert in the Convert-to-text result.
- No prompt editing once a canned prompt is fired (user must cancel and retry).

### Risks
- The "scope chip" can get out of sync with the actual selection if the user lasso-clears in the background. On each sheet open, snapshot the selection and freeze the scope display until the sheet closes.
- Convert-to-text on a vision-only note (e.g. doodles only) returns empty string; show a friendly empty state, not "Error".

---

## Sub-phase 2.8 — Reply actions: Copy, Insert as text box, Send to chat + polish + verification

**Goal:** every completed reply has actionable buttons. Replies can become text boxes on the canvas or jump into chat. Then the Phase 2 verification matrix is run on the S25 Ultra.

### Scope
- Per-reply action row: **Copy**, **Insert as text box**, **Send to chat**.
- Copy: copies plain text to `ClipboardManager`.
- Insert as text box: creates a `NoteItem(kind="text")` at the current viewport center (or near the original selection if it was selection-scoped); goes through `EditorAction.AddItems` so undo works.
- Send to chat (simple, scoped): creates a brand-new chat with the reply as the first user (or assistant?) message and navigates to it.
  - **Decision:** prefill the new chat's draft composer with the reply text, do NOT auto-send. User confirms what they're sending.
  - The Phase 4 "Send to chat" picker (pick an existing chat) replaces this with a sheet later.
- Model picker inside the side sheet header (`ModelSelector` reused).
- Phase 2 verification pass on the S25 Ultra.

### Files to modify
- `ui/screens/notes/AiSideSheet.kt` — action row.
- `ui/screens/notes/NoteEditorViewModel.kt` — `insertReplyAsText(turn)`, `sendReplyToChat(turn)`.
- `ui/screens/chat/ChatScreen.kt` — accept a `draftText` navigation argument and prefill the composer.
- `ui/navigation/Navigation.kt` — route `chat/{chatId}?draftText={text}` (URL-encode).

### Step-by-step
1. Action row layout: three small `TextButton`s under the reply bubble. Only visible when `turn.state == Done`.
2. **Copy**: standard Clipboard write + small Snackbar `"Copied"`.
3. **Insert as text box**:
   - Compute world coords: `viewportController.screenToWorld(center)` (or selection bounds center if scope = selection).
   - Encode a new `NoteItem(kind="text", payload = textCodec.encode(reply, fontSize=18f))`.
   - `viewModel.apply(EditorAction.AddItems(listOf(item)))`.
   - Close the sheet, scroll the viewport so the new text box is in view.
4. **Send to chat**:
   - Create a new `Chat` via `ChatRepository.createChat(...)` mirroring how the chat list FAB does it (default settings, current model).
   - `navController.navigate("chat/${newChatId}?draftText=${URLEncoder.encode(reply, "UTF-8")}")`.
5. `ChatScreen` accepts the draft arg; on first composition, if non-empty, sets the composer text field. Strip the arg from the back stack after read to avoid re-prefilling on rotation.
6. Model picker: add `ModelSelector` to the sheet header, sourced from the same model list chat uses. Switching mid-conversation affects subsequent turns only (existing turns are immutable).
7. **Phase 2 verification matrix** (run on real S25 Ultra; paste into PR):
   1. Existing chats + notes both still work.
   2. Open a note, tap Ask, type a question → reply streams in.
   3. Open a note, lasso 3 strokes, tap Ask → sheet shows "3 strokes selected"; reply references them sensibly.
   4. Switch to a non-vision model in the sheet → ask again → reply is coherent (OCR fallback worked).
   5. Tap each canned prompt → all fire correctly.
   6. Convert to text → text appears with no network call (airplane mode confirms).
   7. Tap Copy → paste elsewhere → text matches.
   8. Tap Insert as text box → new text appears on canvas; undo removes it.
   9. Tap Send to chat → lands in a new chat with the reply as the draft.
   10. Cancel during a long stream → stops within ~200ms; turn marked Error.
   11. Rotate device mid-stream → stream continues; UI follows.
   12. Force-quit and relaunch → conversation is gone (in-memory only — expected); note + ocrText persist.

### Definition of done
- All three reply actions work end-to-end on the S25 Ultra.
- All 12 verification items pass.
- No regressions in the Phase 1 verification matrix.

### Explicit non-goals
- Picker UI to send the reply to an *existing* chat — Phase 4.
- Streaming markdown rendering — follow-up.
- Multi-turn context packing — follow-up.
- Per-message cost / token display in the sheet — follow-up.

### Risks
- Inserting a text box mid-stream is tempting but would require freezing the buffer. Disable the action until `state == Done`.
- The new-chat creation must respect the user's currently selected model and base URL — re-read these from preferences right before creating.

---

## Sequencing notes

- 2.1 → 2.2 → 2.3 → 2.4 are strictly sequential (each builds on the prior).
- 2.5 depends on 2.1 (capability) and 2.2 (rasterizer) but not necessarily 2.4 (OCR-on-save) — `NoteAiService` can synchronously trigger OCR if `ocrText` is missing. Land 2.4 first anyway so the common path (vision + cached OCR hint) is exercised end-to-end.
- 2.6 depends on 2.5.
- 2.7 depends on 2.6 and on Phase 1.8 (lasso menu).
- 2.8 is the closer — same role as 1.10. Don't begin 2.8 if any of 2.5–2.7 are flagged "good enough for now".

## Sub-phase quick reference

| # | Title | Net new files | Touch existing | Risk |
| --- | --- | --- | --- | --- |
| 2.1 | Capability registry + deps | 2 | 3 | Low |
| 2.2 | Note rasterizer | 2 | 1 | Low |
| 2.3 | HandwritingOcr | 3 | 1 | **High** |
| 2.4 | OCR on save | 0 | 3 | Medium |
| 2.5 | NoteAiService core | 4 | 2 | Medium |
| 2.6 | AiSideSheet UI + streaming | 2 | 2 | Medium |
| 2.7 | Entry points + canned prompts + Convert-to-text | 0 | 4 | Medium |
| 2.8 | Reply actions + model picker + verification | 0 | 4 | Medium |
