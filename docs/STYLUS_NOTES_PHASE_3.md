# Phase 3 — Detailed Sub-Phase Breakdown

> Companion to `STYLUS_NOTES_PLAN.md`, `STYLUS_NOTES_PHASE_1.md`, and `STYLUS_NOTES_PHASE_2.md`. Phase 3 adds quick-capture entry points: a stylus button in the chat composer that produces a sketch attachment, system-level entry points (`ACTION_CREATE_NOTE`, home-screen shortcut, Quick Settings tile), and the deep-link plumbing that ties them all to `NoteEditorScreen`.
>
> **Pre-requisites:** Phases 1 and 2 are shipped. The editor, drawing surface, and AI side sheet all work.
>
> **Golden rules** (same as Phases 1–2):
> 1. App still launches; existing chats + notes still work.
> 2. Green build + tests in the touched area.
> 3. Don't pull in scope from later sub-phases.
> 4. One sub-phase, one PR.

---

## Sub-phase 3.1 — Deep-link plumbing + home-screen static shortcut

**Goal:** the app has a single, well-defined deep link for "create a fresh note (optionally in stylus mode)" that every Phase 3 entry point will fire. The simplest entry point — a static home-screen shortcut — ships alongside it as the first consumer.

### Scope
- Formal deep-link URI: `aichat://notes/new` plus optional query params (`source=tile|shortcut|intent|composer`, `stylus=true`).
- `NavHost` route `note/new` accepts these params; `NoteEditorViewModel` reads them.
- Static home-screen shortcut targeting the same deep link.
- `MainActivity` handles `Intent.ACTION_VIEW` for the URI scheme.

### Files to create
- `app/src/main/res/xml/shortcuts.xml`
- `app/src/main/res/drawable/ic_shortcut_new_note.xml` (vector drawable — single-color quill / pen icon)

### Files to modify
- `app/src/main/AndroidManifest.xml`
  - On the existing `MainActivity`, add `android:launchMode="singleTask"` so deep links don't pile up multiple instances.
  - Add `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />` inside `<activity>`.
  - Add an `<intent-filter>` block for the deep link:
    ```
    <intent-filter android:autoVerify="false">
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:scheme="aichat" android:host="notes" />
    </intent-filter>
    ```
- `app/src/main/java/com/aichat/sandbox/MainActivity.kt`
  - In `onCreate`/`onNewIntent`, parse the URI, extract `source`/`stylus`, navigate to `note/new?source=...&stylus=...`.
- `ui/navigation/Navigation.kt` — extend the `note/new` route to accept `source` and `stylus` query args.
- `ui/screens/notes/NoteEditorViewModel.kt` — accept and log the source; if `stylus=true`, default the active tool to PEN (it already is, but make this explicit and forward-compatible).

### Step-by-step
1. Write `shortcuts.xml`:
   ```xml
   <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
     <shortcut
       android:shortcutId="new_note"
       android:enabled="true"
       android:icon="@drawable/ic_shortcut_new_note"
       android:shortcutShortLabel="@string/shortcut_new_note_short"
       android:shortcutLongLabel="@string/shortcut_new_note_long">
       <intent
         android:action="android.intent.action.VIEW"
         android:data="aichat://notes/new?source=shortcut&amp;stylus=true"
         android:targetPackage="com.aichat.sandbox"
         android:targetClass="com.aichat.sandbox.MainActivity" />
       <categories android:name="android.shortcut.conversation" />
     </shortcut>
   </shortcuts>
   ```
2. Add the two strings to `strings.xml`: `"New note"` (short) / `"Start a new handwritten note"` (long).
3. `MainActivity` deep-link handling:
   - On `onCreate`, capture `intent`; on `onNewIntent`, replace it via `setIntent(intent)`.
   - In a `LaunchedEffect(intent)` inside the Compose tree, if `intent.action == ACTION_VIEW && data?.host == "notes" && data?.pathSegments?.firstOrNull() == "new"`, navigate. Make sure the navigation is idempotent — guard with a `consumed` flag on the intent.
4. Navigation route:
   ```
   composable(
     route = "note/new?source={source}&stylus={stylus}",
     arguments = listOf(
       navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
       navArgument("stylus") { type = NavType.BoolType; defaultValue = false }
     )
   ) { ... NoteEditorScreen(...) }
   ```
   Keep the existing bare `note/new` route working — back-compat for in-app FAB.
5. Log the source to logcat (`Log.d("NotesEntry", "source=$source stylus=$stylus")`). No analytics yet.

### Definition of done
- `adb shell am start -W -a android.intent.action.VIEW -d "aichat://notes/new?source=adb&stylus=true"` opens the app on a fresh note.
- Long-pressing the launcher icon shows a "New note" shortcut; tapping it lands on a fresh note.
- Existing in-app FAB → new note still works (no regression).
- Re-firing the deep link while the app is foregrounded reuses the same activity instance (no duplicate task).

### Explicit non-goals
- No Quick Settings tile — 3.2.
- No `ACTION_CREATE_NOTE` — 3.3.
- No chat-composer sketch button — 3.4.
- No analytics on `source`.

### Risks
- `singleTask` launch mode changes back-stack behavior; verify pressing Back from the deep-linked note still returns to the previous screen if the app was already running.
- URI parsing is easy to get wrong on hot-launch (`onNewIntent`) vs cold-launch (`onCreate`). Test both paths.

---

## Sub-phase 3.2 — Quick Settings tile

**Goal:** the user can drag a "New Note" tile into their Quick Settings panel; tapping it fires the same deep link as the home-screen shortcut and lands on a fresh note (unlocked path; we'll handle the locked-screen case explicitly).

### Scope
- `NotesQuickTileService` extending `TileService`.
- Manifest `<service>` entry with the required permission and metadata.
- Tile icon + label.
- Behavior:
  - Unlocked → start the deep-link activity directly.
  - Locked → use `startActivityAndCollapse` after unlock (`requestListeningState` / show toast asking to unlock — keep it minimal in v1).

### Files to create
- `app/src/main/java/com/aichat/sandbox/service/NotesQuickTileService.kt`
- `app/src/main/res/drawable/ic_qs_tile_note.xml`

### Files to modify
- `app/src/main/AndroidManifest.xml` — add the service block.

### Step-by-step
1. Service skeleton:
   ```kotlin
   class NotesQuickTileService : TileService() {
       override fun onClick() {
           super.onClick()
           val intent = Intent(Intent.ACTION_VIEW,
               Uri.parse("aichat://notes/new?source=tile&stylus=true"))
               .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
           // API 34+ recommends startActivityAndCollapse(PendingIntent)
           if (Build.VERSION.SDK_INT >= 34) {
               val pi = PendingIntent.getActivity(
                   this, 0, intent,
                   PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
               startActivityAndCollapse(pi)
           } else {
               @Suppress("DEPRECATION")
               startActivityAndCollapse(intent)
           }
       }
   }
   ```
2. Manifest:
   ```xml
   <service
     android:name=".service.NotesQuickTileService"
     android:exported="true"
     android:icon="@drawable/ic_qs_tile_note"
     android:label="@string/tile_new_note_label"
     android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
     <intent-filter>
       <action android:name="android.service.quicksettings.action.QS_TILE" />
     </intent-filter>
   </service>
   ```
3. Add string `tile_new_note_label = "New Note"`.
4. Locked-screen behavior: do nothing special in v1 — the system shows an unlock prompt and resumes our intent after unlock. Test it; document if it falls short.

### Definition of done
- After install, the tile appears in the Quick Settings tile picker.
- Dragged into the active set and tapped → app opens on a fresh note.
- From a locked screen, tapping prompts unlock, then opens the same note.
- Existing 3.1 shortcut still works.

### Explicit non-goals
- Dynamic tile state ("note in progress" indicator) — out of scope.
- A "resume last note" variant of the tile — follow-up.

### Risks
- `startActivityAndCollapse(Intent)` is deprecated on API 34+. The branching above covers both. Confirm the deprecation hasn't graduated to a removal between when this doc was written and implementation time.
- Some OEMs (older Samsung One UI versions) don't surface user-installable tiles cleanly. Don't block on this; document the limitation.

---

## Sub-phase 3.3 — `ACTION_CREATE_NOTE` activity-alias (Android 14 default note-taking app)

**Goal:** the user can set this app as Android 14's default note-taking app. Long-pressing the S-Pen on the lock screen (or invoking any `ACTION_CREATE_NOTE` source) opens directly into a fresh note. Locked-screen handling matters here — `EXTRA_USE_STYLUS_MODE` should default the editor to stylus immediately and `EXTRA_LOCK_SCREEN_SHOW` is honored.

### Scope
- `<activity-alias>` declaring an `ACTION_CREATE_NOTE` intent filter, gated by `targetSdk >= 34`.
- Locked-screen capable activity attributes: `android:showWhenLocked="true"`, `android:turnScreenOn="true"`.
- Extras handled in `MainActivity`: `EXTRA_USE_STYLUS_MODE`, `EXTRA_LOCK_SCREEN_SHOW`.
- A small banner inside the editor when launched from a locked-screen handoff: "Sign in to save to your library" — without it, the note still saves locally to the same DB (we don't gate persistence on auth; we don't have auth).

### Files to modify
- `app/src/main/AndroidManifest.xml`
  - Add the activity-alias targeting `MainActivity`.
  - The alias requires `android:exported="true"` and the intent filter.
- `app/src/main/java/com/aichat/sandbox/MainActivity.kt`
  - Read `EXTRA_USE_STYLUS_MODE` (boolean) and forward into the deep link as `stylus=true`.
  - Build.VERSION.SDK_INT gate (`>= UPSIDE_DOWN_CAKE`) before reading these extras (defensive — they don't exist on lower APIs).

### Step-by-step
1. Manifest activity-alias:
   ```xml
   <activity-alias
     android:name=".CreateNoteAlias"
     android:targetActivity=".MainActivity"
     android:exported="true"
     android:showWhenLocked="true"
     android:turnScreenOn="true">
     <intent-filter>
       <action android:name="android.intent.action.CREATE_NOTE" />
       <category android:name="android.intent.category.DEFAULT" />
     </intent-filter>
   </activity-alias>
   ```
2. `MainActivity.onNewIntent`/`onCreate` (after the existing deep-link handler):
   ```kotlin
   if (intent.action == "android.intent.action.CREATE_NOTE") {
       val useStylus = if (Build.VERSION.SDK_INT >= 34) {
           intent.getBooleanExtra("android.intent.extra.USE_STYLUS_MODE", true)
       } else true
       navigateToNewNote(source = "create_note_intent", stylus = useStylus)
       intent.action = null   // consume
   }
   ```
3. Compose: detect the locked-screen path (`KeyguardManager.isKeyguardLocked` at launch) and show a transient Snackbar banner: `"Note saved locally — sign in to sync later."` This is a no-op affordance for v1 (we don't have auth); it's a placeholder for sync work. Skip the banner if you'd rather not lie; the parent plan doesn't call for it. **Decision: skip the banner in v1**; the note simply saves.
4. Test the locked-screen path on the S25 Ultra: settings → Apps → Default apps → Note-taking app → AI Chat Sandbox. Then with the device locked, click the S-Pen button (or tap "Screen off memo" on Samsung); a fresh note should open.

### Definition of done
- The app appears as a candidate in **Settings → Apps → Default apps → Note-taking app** on Android 14.
- Selecting it and triggering note-creation from a lock screen opens a fresh note over the keyguard.
- The deep link / tile / shortcut entry points (3.1, 3.2) still all work.
- On API ≤ 33, the alias is harmless dead weight (Android ignores the intent filter); the rest of the app behaves identically to before.

### Explicit non-goals
- Returning the note's URI to the caller (some implementations expect this) — out of scope.
- Specific Samsung S-Pen Bluetooth-button air-gesture integration — out of scope per the parent plan.
- Auth / sync hooks.

### Risks
- `showWhenLocked` + `turnScreenOn` apply to the **alias**, not implicitly to `MainActivity`. They live on the alias element. Verify on a locked device.
- Some Samsung firmware revisions briefly re-routed `ACTION_CREATE_NOTE` through Samsung Notes regardless of default-app setting. If we observe this on the S25 Ultra, document it; not fixable from our side.

---

## Sub-phase 3.4 — Sketch composer attachment sheet + Phase 3 verification

**Goal:** the chat composer gets a pen-button entry point that opens a bottom-sheet sketch surface (stripped-down `DrawingSurface`, fixed size, single tool palette). Confirming the sketch rasterizes it to PNG and feeds it through the existing image-attachment flow. Then the Phase 3 verification matrix is run.

### Scope
- `SketchAttachmentSheet` composable: modal bottom sheet ~60% screen height, fixed-size internal canvas (no pan/zoom).
- Tool palette: pen, eraser, undo, clear — that's it. No tilt/pressure config UI; the rendering still respects them.
- Confirm action: rasterize via `NoteRasterizer.renderSelection(items, bounds=allItems)`, base64-encode, attach to the message draft using the **same code path** the photo picker uses (the composer already has `imageList` state).
- Cancel action: discard sketch.
- Pen button in the composer next to the image-attachment button; only visible if the device has stylus capability (`PackageManager.hasSystemFeature(PackageManager.FEATURE_STYLUS)`)? Actually, expose unconditionally — finger-drawn sketches are a valid use case. Just call it "Sketch", not "Stylus sketch".

### Files to create
- `app/src/main/java/com/aichat/sandbox/ui/components/chat/SketchAttachmentSheet.kt`
- `app/src/main/java/com/aichat/sandbox/ui/components/chat/SketchAttachmentState.kt`

### Files to modify
- `ui/screens/chat/ChatScreen.kt` — add the pen `IconButton` next to the image attachment one (~line 806–823); wire to open the sheet.
- `ui/screens/chat/ChatViewModel.kt` — `attachSketch(pngBytes: ByteArray)` builds an `ImageData` entry (data URI: `data:image/png;base64,...`) and appends to the same `imageList` the photo picker uses. Reuse whatever helper already does base64 for picked images; if there isn't one, factor it out here (`ImageAttachmentEncoder`).

### Step-by-step
1. State:
   ```kotlin
   data class SketchAttachmentState(
       val isOpen: Boolean = false,
       val items: List<NoteItem> = emptyList(),
       val activeTool: Tool = Tool.PEN,
       val undoStack: List<EditorAction> = emptyList(),
   )
   ```
   Yes — we reuse Phase 1's `EditorAction` / undo plumbing inside the sheet. No second implementation.
2. Internal canvas size: fixed pixel dimensions matching the sheet bounds (e.g. `screenWidthPx × 0.5 * screenHeightPx`). World coords = screen coords here; no `ViewportController`.
3. Reuse `DrawingSurface` with `viewportController = null` (or an `IdentityViewport`). If the existing Phase 1 implementation hard-assumes a viewport, factor that out behind an interface — that's part of this sub-phase's scope.
4. Confirm flow:
   - Compute bounds of all items (default to the canvas bounds if empty? Empty sketches should be blocked — Confirm button disabled).
   - `NoteRasterizer.render(items, bounds, maxEdgePx = 1024, background = "plain")` → PNG bytes.
   - Convert to base64 data URI.
   - `chatViewModel.attachSketch(pngBytes)`.
   - Close sheet.
5. Composer button: `IconButton` with `Icons.Default.Draw` (or `Edit`) next to the image-attach button; clicking sets `SketchAttachmentState.isOpen = true`.
6. **Phase 3 verification matrix** (run on real S25 Ultra; paste into PR):
   1. Existing chats, notes, AI side sheet all still work.
   2. Composer pen button is visible; tapping it opens the sheet.
   3. Drawing with S-Pen produces ink; finger draws too in this surface (no palm rejection scope here — sheet is small).
   4. Undo / Clear behave; Confirm with an empty canvas is disabled.
   5. Confirming attaches the sketch to the draft as an image; sending it includes the image in the API call (verify via logcat or response).
   6. The sketch image renders correctly in the chat transcript after send.
   7. Home-screen shortcut still works.
   8. Quick Settings tile still works.
   9. Setting the app as the default note-taking app + invoking from lock screen still works.
   10. Deep link `aichat://notes/new?source=adb` still opens a fresh note.

### Definition of done
- Sketch attach flow works end-to-end with a vision model and the sketch is part of the message context.
- All 10 verification items pass.
- No regression in Phase 1 / Phase 2 matrices (sanity check: open a Phase 1 note, draw, AI-ask, save).

### Explicit non-goals
- Editing a sketch after attaching it (it becomes a regular image attachment — that's the point of reusing the existing flow).
- Multi-page sketch attachments.
- Sketch reply from the AI ("here's a fixed version of your diagram") — far out of scope.

### Risks
- Reusing `DrawingSurface` may require small refactors to break its viewport assumption. Budget for this; if it's too invasive, fall back to a slim copy (`SketchSurface`) — but document the duplication and file a follow-up to unify.
- Sheet keyboard interactions: ensure the IME doesn't push the sketch surface around (set `WindowInsetsCompat` appropriately on the sheet).

---

## Sequencing notes

- 3.1 is the foundation — every other Phase 3 sub-phase rides on the deep link.
- 3.2 and 3.3 are independent of each other and can swap order; both depend on 3.1.
- 3.4 has the most code and is least dependent on 3.1–3.3 internally (it uses the existing chat attachment flow), but **ship it last** so the Phase 3 verification can cover all entry points together.

## Sub-phase quick reference

| # | Title | Net new files | Touch existing | Risk |
| --- | --- | --- | --- | --- |
| 3.1 | Deep link + shortcut | 2 | 3 | Low |
| 3.2 | Quick Settings tile | 2 | 1 | Low |
| 3.3 | `ACTION_CREATE_NOTE` alias | 0 | 2 | Medium |
| 3.4 | Sketch attachment sheet + verification | 2 | 2 | Medium |
