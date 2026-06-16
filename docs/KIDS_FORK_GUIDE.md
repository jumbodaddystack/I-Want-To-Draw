# Kids Fork Guide — "Doodle Pad"

A playbook for forking **AI Chat Sandbox** into a safe, offline drawing app for
kids (~ages 5–9). The goal: keep the great drawing canvas, strip everything that
costs money, needs the internet, or exposes grown-up settings.

This doc is intentionally **mechanics only** — the kid-facing UI (big crayon
buttons, bright colors, stickers, mascot) is meant to be designed hands-on with
the kids, not prescribed here.

## Why a true fork

A separate repo lets the kids app diverge freely while still pulling brush/canvas
improvements from the parent via an `upstream` remote.

```bash
# After forking on GitHub (e.g. kids-doodle-pad):
git clone git@github.com:<you>/kids-doodle-pad.git
cd kids-doodle-pad
git remote add upstream git@github.com:jumbodaddystack/ai-chat-sandbox.git

# Pull parent improvements whenever you want:
git fetch upstream
git merge upstream/main
```

## Rebrand so it installs side-by-side

| Change | File | Note |
|--------|------|------|
| `applicationId` | `app/build.gradle.kts` | `com.aichat.sandbox` → `com.doodlepad.kids`. Only `applicationId` decides install identity; `namespace` can stay. |
| App name | `app/src/main/res/values/strings.xml` (`app_name`) | Shown under the launcher icon via `android:label` in the manifest. |
| Launcher icon | `app/src/main/res/mipmap-*` | Great first project to do *with* the kids. |

## What to remove (money + internet + safety)

All deletions — easy to reason about and reversible.

| Remove | Where | Why |
|--------|-------|-----|
| Chat tab | `Screen.ChatList` in `ui/navigation/Navigation.kt`; remove from `bottomNavItems` | AI chat spends API money |
| Settings tab | `Screen.Settings` in `Navigation.kt` + its `composable` | Hides API-key entry entirely |
| Vector AI tune-up | `Screen.VectorTuneup` in `Navigation.kt` | Also calls paid AI |
| INTERNET permission | `<uses-permission android:name="android.permission.INTERNET"/>` in `AndroidManifest.xml` | The real seatbelt: with no INTERNET permission the app physically cannot call an API or upload a drawing. The canvas is fully offline. |
| RECORD_AUDIO (optional) | `AndroidManifest.xml` | Notes can attach audio memos; drop unless wanted. |

The drawing engine (`ui/screens/notes/NoteEditorScreen.kt`, brushes, stamps,
layers, undo/redo) needs **no** internet and keeps working once the above is gone.

## Make it launch straight into drawing

In `Navigation.kt`, change the start destination from the chat list to the
canvas:

```kotlin
startDestination = Screen.Notes.route   // was Screen.ChatList.route
```

Even better: deep-link to a fresh canvas (`note/new`) so it opens ready to draw.

## Two safety tips for the device

1. **Android Screen Pinning** (Settings → Security → App pinning) locks kids into
   the app — no swiping out into your real apps. No code required.
2. **Dropping INTERNET** (above) guarantees no purchases, uploads, or data leaving
   the device.

## Suggested first milestone (build with the kids)

1. Fork + rebrand (name + icon) — let them pick the name and draw the icon.
2. Remove chat / settings / vector tabs and the INTERNET permission.
3. Launch straight into the canvas.
4. *Then* iterate on the fun stuff together: bigger brush buttons, a fixed bright
   palette, a one-tap "clear page", sticker stamps.
