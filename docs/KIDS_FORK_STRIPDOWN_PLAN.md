# Kids Fork — Mechanical Strip-Down Plan

A verified, step-by-step plan for turning **AI Chat Sandbox** into a safe, fully
offline drawing app for kids (~ages 5–9). This is the **mechanics-only** companion
to [`KIDS_FORK_GUIDE.md`](./KIDS_FORK_GUIDE.md): it covers the rebrand, tab removal,
offline lockdown, and canvas-first boot — and stops at a clean, compiling, offline
base. **It does not design the kid-facing UI** (crayon buttons, colors, stickers);
that is meant to be built hands-on with the kids.

> Goal: keep the drawing canvas, remove everything that costs money, needs the
> internet, or exposes grown-up settings, then boot straight into a drawing canvas.

---

## What was verified against the current code

| Claim in the guide | Reality in code | Consequence |
|---|---|---|
| Remove 3 tabs in `Navigation.kt` | `Screen.ChatList`, `Screen.Settings`, `Screen.VectorTuneup` exist in the `sealed class Screen` + `bottomNavItems` (Navigation.kt lines 53–67) with their own `composable{}` destinations | Straightforward |
| Drawing engine needs no internet | **Mostly true, but** `NoteEditorViewModel` (`ui/screens/notes/`) injects `aiService: NoteAiService` (line 143) **and** `chatRepository: ChatRepository` (line 146), used at lines 1271 & 3581 plus the in-canvas AI side-sheet | The canvas runs offline, but the networking code **cannot be deleted** without editing the canvas |
| Delete `data/remote/`, AI services | Reachable from the **kept** canvas via `NoteEditorViewModel → NoteAiService → ChatStreamer → ApiClient` | Deletion is a **second phase** requiring canvas surgery — out of scope for a mechanical strip-down |
| `HandwritingOcr` is offline | Uses ML Kit `DigitalInkRecognition` with a `RemoteModelManager` that **downloads models over the net** on first use | Compiles fine; OCR silently won't work offline. Not needed for drawing. |
| Icons tab is independent | `ui/screens/icons/` imports only studio/theme/notes helpers — **no** chat/remote/AI imports | Safe to keep as-is |
| `data/vector/` is "the AI tune-up" | **Notes + Icons + icon export use `data/vector/`** for offline geometry (`PathConversions`, `ShapeRecognizer`, `AndroidVectorDrawableWriter`, …) | **Do NOT delete the `data/vector/` directory** — only the 4–5 AI-specific files inside it |

**Bottom line:** the work splits cleanly into two phases. **Phase 1** (below) yields a
compiling, offline, canvas-first base and is where this plan stops. **Phase 2**
(deleting the now-inert networking/AI code) is optional and deferred because it edits
the canvas you want to design with the kids.

---

## Phase 1 — Mechanical, offline, canvas-first base (do this now)

Ordered so the app keeps compiling at each step.

### Step 1 — Rebrand (independent; safe to do first)
- `app/build.gradle.kts` (line 14): `applicationId = "com.aichat.sandbox"` → `"com.doodlepad.kids"` (your choice).
  **Leave `namespace = "com.aichat.sandbox"` (line 10) unchanged** — only `applicationId`
  decides install identity; changing `namespace` forces a package-wide source move for no benefit.
- `app/src/main/res/values/strings.xml` (line 3): `app_name` → your kid-facing name.
- The `FileProvider` authority is `${applicationId}.fileprovider` in the manifest, so it
  auto-tracks the new id — **no manifest edit needed** for that. Grep to confirm no source
  hardcodes `com.aichat.sandbox.fileprovider`.

### Step 2 — Drop permissions (manifest only; safe)
In `app/src/main/AndroidManifest.xml`, delete:
- `<uses-permission android:name="android.permission.INTERNET" />`
- `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

Runtime (not compile) consequences:
- The in-canvas **audio-memo / audio-synced-ink** feature (`AudioRecorder` injected into
  `NoteEditorViewModel`) can't record; the button stays until removed as kid-UI later. Compiles fine.
- Any AI call hard-fails — the intended seatbelt. Compiles fine.
- **Keep** `READ_MEDIA_IMAGES` (image insert / photo picker) and the stylus `uses-feature` —
  both offline and useful for drawing.

### Step 3 — Strip navigation (the only file with real edits)
In `app/src/main/java/com/aichat/sandbox/ui/navigation/Navigation.kt`:
1. Remove `ChatList`, `Settings`, `VectorTuneup` from the `sealed class Screen` (lines 54, 57, 58)
   and from `bottomNavItems` (lines 61–67). Keep `Notes` + `IconsTab`.
2. Delete the `composable(...)` destinations for `Screen.ChatList.route` (147–156),
   `chat/{chatId}?...` (157–182), `Screen.Settings.route` (269–271),
   and `Screen.VectorTuneup.route` (275–279).
3. Remove now-unused imports (lines 41, 42, 49, 50, 51; and the `Chat`/`Settings`/`Tune`
   icon imports 17, 21, …).
4. **Neutralize the canvas→chat callbacks** so the kept `NoteEditorScreen` composables still
   compile: in the `note/new` and `note/{noteId}` destinations, change
   `onNavigateToChat = { chatId -> navController.navigate("chat/$chatId") }` to
   `onNavigateToChat = {}` (the in-canvas "send to chat" button becomes an inert no-op until
   removed as kid-UI).
5. **Change the start destination** (line 144): `startDestination = Screen.ChatList.route` →
   boot straight into a blank canvas with `startDestination = "note/new"` (the existing
   `note/new?...` route matches a bare `note/new` since all args are optional).
   - Trade-off: on the `note/new` editor route, `showBottomBar` is false (it isn't in
     `bottomNavItems`), so you boot into a chrome-free canvas with **no tab bar** and Back
     exits the app — arguably ideal for a 5-year-old. To keep the tab bar visible on launch,
     use `startDestination = Screen.Notes.route` (the drawings gallery) instead.
     **Recommended: `note/new`** for true canvas-first; trivial to switch later.

### Step 4 — Delete the dead screen packages (after Step 3 compiles)
Delete these directories (UI + their ViewModels); none are referenced once Step 3 is done:
- `ui/screens/chatlist/` (ChatListScreen, ChatListViewModel, SearchViewModel)
- `ui/screens/chat/` (ChatScreen, ChatViewModel)
- `ui/screens/settings/` (SettingsScreen, SettingsViewModel)
- `ui/screens/vector/` (the whole VectorTuneup workspace **and** `vector/edit/`)

Before deleting `ui/screens/vector/`, grep for `VectorEditScreen` / `ROUTE_VECTOR_TUNEUP`
references outside that package — it's only reached from within VectorTuneup (not registered in
`Navigation.kt`), but confirm. Note `SearchViewModel` lives in `chatlist/` and is chat-search —
confirm it is not note search (note search is `NoteSearchScreen` / `NoteSearchRepository`, kept).

### Step 5 — Compile, fix stragglers
Build (see `CLAUDE.md` for the one-time Android SDK install in a fresh container) and fix any
remaining import/reference errors. Result: boots to a canvas, no internet, no money tabs, no
grown-up settings. **Stop here.**

---

## Phase 2 — Delete the inert networking/AI code (optional; later — NOT now)

What becomes *physically deletable* but is **blocked by the canvas**, hence flagged rather than done:

**Would be dead, but `NoteEditorViewModel` still injects/uses it:**
- `data/remote/` (entire dir: `ApiClient`, `ChatStreamer`, `*Adapter`, `*Api`, `ProviderAdapter`, `RetryPolicy`, `dto/`)
- `data/repository/ChatRepository.kt`
- `data/notes/NoteAiService.kt` (+ the AI helpers it anchors: `AskRequest`, `AiChunk`,
  `EditOpsParser`, `aiRecolorPrompt`, the Tutor* flow, `AiSideSheetState`)
- AI-only files inside `data/vector/`: `VectorTuneupAiService.kt`, `VectorRedrawAiService.kt`,
  `VectorRedrawPrompts.kt`, `VectorTuneupPrompts.kt`, `trace/AiBitmapTracer.kt`
- `AppModule` providers `provideChatStreamer` and `provideNoteAiService` (lines 198–211)

**To unblock deletion**, first edit the kept canvas (`NoteEditorViewModel` + `AiSideSheetState`
+ the AI side-sheet UI): drop the `aiService` and `chatRepository` constructor params and remove
their usages (the AI "Ask" side-sheet, AI recolor, the Tutor feature, the `chats` flow at line
1271, and `createChat()` at line 3581). That is real surgery on the screen you'll redesign with
the kids — hence it is deferred.

---

## Compile-time risk register

| Risk | Detail | Handling |
|---|---|---|
| **Room schema** | `AppDatabase` declares `Chat::class` entity + `chatDao()`, `vectorTuneupDao()`, `vectorSymbolDao()` | **Do NOT remove DB entities/DAOs** — removing a Room entity forces a destructive migration (data loss / crash). Leave the schema and the `provideChatDao`/`provideVectorTuneupDao` providers; they are offline and inert. |
| **Hilt orphan providers** | After deleting VectorTuneup, `provideVectorTuneupRepository` (AppModule 165–172) is unused | Harmless — an unused `@Provides` is fine. Optionally delete the provider **and** `VectorTuneupRepository.kt` (only consumer was VectorTuneupViewModel), but **keep `vectorTuneupDao`** for Room. |
| **Hilt over-deletion** | Deleting the `NoteAiService`/`ChatRepository` providers in Phase 1 breaks the Hilt graph because `NoteEditorViewModel` still requests them | Don't touch those providers until Phase 2's canvas surgery. |
| **Dangling callbacks** | `NoteEditorScreen` exposes `onNavigateToChat`; `ChatScreen` referenced `Screen.Settings.route`; `onOpenSettings` | Neutralize at the call site (`onNavigateToChat = {}`, Step 3.4); `ChatScreen` is deleted so its Settings reference goes with it. |
| **`PreferencesManager`** | Holds API keys **and** legit tool/palette prefs; injected by the kept `NoteEditorViewModel` + `MainActivity` | **Keep it.** Only SettingsScreen *exposed* keys; the class is shared offline state. |
| **Shortcuts / aliases** | Manifest `CreateNoteAlias` (`ACTION_CREATE_NOTE`), `NotesQuickTileService`, `aichat://notes` deep link, `@xml/shortcuts` | All point at the **notes/canvas** path — keep them; they reinforce canvas-first. (Optional: rename the `aichat` URI scheme later for brand consistency — not required.) |

---

## Open questions
1. **Boot target:** blank canvas (`note/new`, no tab bar, Back exits) vs. drawings gallery
   (`Screen.Notes.route`, tab bar visible)? Recommended: `note/new`.
2. **Scope:** stop at Phase 1 (offline base, dead AI code left inert)? Phase 2 (actually deleting
   `data/remote/` etc.) waits until after the kid UI is designed, since it edits the canvas.
3. **OCR / handwriting-to-text:** keep it compiled-but-dormant (non-functional offline), or strip
   it in Phase 2? Irrelevant to drawing either way.
4. **Audio memos:** dropping `RECORD_AUDIO` now makes the button a runtime no-op — confirm it
   should be removed later as kid-UI, not now.
5. **`vector/edit` (`VectorEditScreen`):** treated as VectorTuneup-only and deletable — confirm
   with a reference grep before deleting.

---

*Scope guard: this plan performs no code changes and does not design the kid-facing UI. It stops
at a clean, offline, canvas-first base.*
