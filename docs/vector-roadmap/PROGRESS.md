# Vector Icon Editor — Progress & Handoff

**This is the single source of truth for where the vector-icon-editor work stands.**
Point a fresh session at this file (`docs/vector-roadmap/PROGRESS.md`) and it has
everything needed to pick up the next phase.

---

## 0. Orientation (read first)

**Goal:** grow the app from "icon sketchpad + import-only tune-up" into a true
editable vector icon editor. Full vision + gap analysis: [`00-overview.md`](00-overview.md).

**The core architectural bet:** promote the immutable `data/vector/VectorDocument`
into the *live editable model* every surface edits, by layering an editable node
view (`data/vector/edit/EditablePath`) on top of it and reusing the existing
parser / normalizer / writer / snapping / viewport / reducer machinery rather
than building a parallel stack.

**Per-phase plans** (each: scope, concrete files, reuse list, test plan, risks):
- Phase 1 — [`phase-1-editable-bezier-scene-graph.md`](phase-1-editable-bezier-scene-graph.md)
- Phase 2 — [`phase-2-boolean-path-ops.md`](phase-2-boolean-path-ops.md)
- Phase 3 — [`phase-3-pixel-perfect-pipeline.md`](phase-3-pixel-perfect-pipeline.md)
- Phase 4 — [`phase-4-unify-domains.md`](phase-4-unify-domains.md)
- Phase 5 — [`phase-5-production-polish.md`](phase-5-production-polish.md)

**Dependency order:** Phase 0→1 is the keystone. 2, 3, 4 each depend on the Phase 1
editable model; they can then proceed with some parallelism. 5 is post-core polish.

---

## 1. Working agreement (how to run a session here)

- **Branch:** `claude/vector-roadmap-next-phase-ceGqz` (carries all the Phase 0 +
  Phase 1a work). Develop, commit, and push here. Do **not** open a PR unless
  explicitly asked.
- **Build/test env:** Android SDK is installed per `CLAUDE.md`. In a fresh web
  container run the one-time SDK setup from `CLAUDE.md` first if `./gradlew` fails
  with "SDK location not found".
- **Run the edit tests:**
  ```
  ./gradlew :app:testDebugUnitTest --console=plain --tests "com.aichat.sandbox.*.edit.*"
  ```
  Full suite: `./gradlew :app:testDebugUnitTest --console=plain`.
- **Known pre-existing failures (NOT regressions):** ~22 failures in
  `NoteSvgExporterTest`, `NoteVectorDrawableExporterTest`, `NoteAiServiceTest`
  (`android.graphics.Color` / `android.util.Log` "not mocked"). Suite is "green"
  if these are the only failures. Ignore them; focus on `*.edit.*` and the tests
  for your phase.
- **Style:** pure, JVM-testable cores (no Android/Compose imports) wherever
  possible — mirror the existing `VectorTuneupReducer` / `Snap` / `ViewportController`
  pattern. Match surrounding code's idiom and comment density.
- **End every session by updating this file** (see §4 Handoff protocol).

---

## 2. Status checklist

Legend: `[x]` done · `[~]` in progress · `[ ]` not started

### Phase 0 — editable model foundation (UI-free) — ✅ COMPLETE
- [x] `data/vector/edit/EditablePath.kt` — `EditAnchor`/`ControlPoint`/`EditSubpath`/`EditablePath`, `AnchorType`
- [x] `data/vector/edit/EditablePathFactory.kt` — `VectorPath → EditablePath` (reuses `VectorPreviewPathNormalizer`; quad→cubic, closed-curve fold, anchor classify)
- [x] `data/vector/edit/EditablePathSerializer.kt` — `EditablePath → PathCommand[]/VectorPath` (reuses `PathDataFormatter`)
- [x] `VectorDocument.replacePath(id, newPath)` helper
- [x] Tests: `EditablePathRoundTripTest`, `VectorDocumentReplacePathTest` (14 tests, green)
- [x] Committed + pushed

### Phase 1 — node editor (the rest of Phase 1) — 🟡 IN PROGRESS (1a+1b+1c+1d done → **NEXT: 1e**)
Build in this order (each step is shippable + testable on its own):
- [x] **1a. Pure reducer core (no UI):** `ui/screens/vector/edit/VectorEditState.kt`,
  `VectorEditAction.kt`, `VectorEditReducer.kt`. Actions: `BeginEdit`, `SetTool`,
  `SetSnapMask`, `StartPath`, `PlaceAnchor`, `DragHandle`, `CommitPath`,
  `SelectAnchor`, `ClearSelection`, `MoveSelection`, `InsertAnchorOnSegment`
  (de Casteljau split), `DeleteSelected`, `SetAnchorType`, `ToggleSubpathClosed`,
  `Undo`, `Redo`, `ApplyToDocument`. Snapshot-based undo (cap 200). **Tests:**
  `VectorEditReducerTest` (21 tests, green) — pen-draws a triangle; grid snap;
  insert splits a cubic without changing the curve (sampled) and a line at its
  midpoint; closing-segment insert wraps to start; delete; corner→smooth→symmetric→
  corner; close/open; write-back; undo/redo inverts every action exactly + cap.
- [x] **1b. Hit-testing:** `ui/screens/vector/edit/EditHitTest.kt` (pure; world coords
  + world-space tolerance = screen-px ÷ viewport scale). Returns a `Hit` sum type
  (`Anchor` / `Handle{side: IN/OUT}` / `Segment{segmentIndex, t, x, y}`); `hitTest(...)`
  combines them with paint-order priority (handle of selected anchors → anchor →
  segment). Line projection + de Casteljau sample-and-refine for cubics; closing-segment
  index wraps to the start anchor to match the reducer. **Tests:** `EditHitTestTest`
  (14, green) — nearest anchor; zoom-dependent pick/miss; handle only for candidates +
  IN/OUT sides; line midpoint `t`; closing-segment wrap; open subpath has no closing
  segment; cubic point via de Casteljau; combined-priority; a returned `Segment` feeds
  `InsertAnchorOnSegment` and lands the new node exactly on the reported curve point;
  `worldTolerance` inverse-scales.
- [x] **1c. ViewModel:** `VectorEditViewModel.kt` (StateFlow host, Hilt — mirror `VectorTuneupViewModel`).
  Holds `MutableStateFlow<VectorEditState>`, `dispatch(action)` funnels through the reducer,
  owns the `ViewportController`, and maps gestures → actions (`onTap`/`onDragStart`/`onDrag`/
  `onDragEnd`, `pan`/`zoom`). Continuous drags are coalesced to a single undo step. Also added
  the missing reducer action **`MoveHandle(id, side, x, y)`** (corner=independent, smooth=colinear
  keep-length, symmetric=mirror) so `Hit.Handle` drags have a home. **Tests:** `VectorEditViewModelTest`
  (8) + 4 new `MoveHandle` reducer tests → `*.edit.*` now **59, green**.
- [x] **1d. Canvas + gestures:** `ui/screens/vector/edit/VectorEditCanvas.kt` — Compose
  `Canvas` that renders via the existing `VectorPreviewCanvas` internals
  (`preparePreviewPaths`/`buildComposePath`/`drawPreparedPath`) under the VM's
  `ViewportController` mapping (`screen = world*scale + offset`). Draws static doc paths
  (minus the path under edit), the **live** editing path's geometry + a constant-width
  skeleton outline (so an unpainted edit path is still visible), an artboard border, and
  the overlay: anchor knobs (selected = filled accent, idle = hollow), control handles
  **only for selected anchors** (matches `EditHitTest` candidates), and the in-progress
  pen draft. Custom `awaitEachGesture` loop classifies **1-finger tap → `onTap`**,
  **1-finger drag → `onDragStart`/`onDrag`/`onDragEnd`** (honest bracket incl. cancel,
  so the VM's drag-coalesce trims correctly), **2-finger → pan/zoom** via
  `calculatePan`/`calculateZoom`/`calculateCentroid`. Fits + bounds-clamps the viewport to
  the artboard on document/size change (the one place that frames the VM's viewport).
  Pure-UI (no new reducer logic); `*.edit.*` stays **59**.
- [ ] **1e. Screen + toolbar:** `VectorEditScreen.kt` (Pen / Direct-select / add /
  delete / corner-smooth / close / undo / redo).
- [ ] **1f. Wire into Tune-Up:** add an "Edit" entry in `ui/screens/vector/VectorTuneupScreen.kt`;
  edited document returns as a new version via the existing version-history reducer. No Room changes.
- [ ] **Open choices to confirm with user before/at build:** (1) in-Tune-Up edit
  mode vs. dedicated editor screen; (2) handles are stored as absolute
  `ControlPoint`s (already chosen in Phase 0 — keep consistent).
- [ ] On-device manual verify (draw closed shape, snap, undo/redo, export round-trips).

### Phase 2 — boolean ops + outline-stroke + offset — ⬜ NOT STARTED
- [ ] Per `phase-2-boolean-path-ops.md`: new pure `data/vector/edit/boolean/`
  (clipper + outliner + offset + curve-refit), flatten→clip→refit, reducer actions, golden-geometry tests.

### Phase 3 — pixel-perfect pipeline — ⬜ NOT STARTED
- [ ] Per `phase-3-pixel-perfect-pipeline.md`: keyline grids, integer-grid snap
  (`EditSnap`), multi-size artboards, grid-quantized lossless + batch export, tests.

### Phase 4 — unify the two domains — ⬜ NOT STARTED
- [ ] Per `phase-4-unify-domains.md`: notes-bridge vectorization, single canonical
  editable `VectorDocument`, route AI (notes `EditOp` + tune-up plans/redraw), lossless export, tests.

### Phase 5 — production polish — ⬜ NOT STARTED
- [ ] Per `phase-5-production-polish.md`: stroke styling, gradients, vector symbols,
  keyboard ergonomics, AI auto-trace. (Independent sub-features; pick by priority.)

---

## 3. Latest handoff (update this each session)

**Last updated:** 2026-06-04 · **Last completed:** Phase 1 step 1d (Compose canvas + gestures)

**State of the branch:** Phase 0 + 1a + 1b + 1c are merged to main (PRs #91–#93). Phase 1d
is now on `claude/vector-roadmap-next-phase-0k3F5`. `:app:compileDebugKotlin` is clean and
edit tests stay green (**59** in `*.edit.*`: 12 Phase-0 round-trip/replace + 25 reducer +
14 hit-test + 8 ViewModel). 1d is a Composable (pure UI), so it adds no JVM tests — its bar
is "compiles + 59 stays green," both met. No PR open.

**What exists now (for the next session to build on):**
- *(Phase 0)* `data/vector/edit/` — `EditablePath` node model (absolute `ControlPoint`
  handles, null ⇒ straight side, all-cubic), `EditablePathFactory.fromPath(...)` (enter),
  `EditablePathSerializer.toCommands/​toVectorPath(...)` (exit), `VectorDocument.replacePath(...)`.
- *(Phase 1a, NEW)* `ui/screens/vector/edit/`:
  - `VectorEditState.kt` — immutable state: `document`, `editing: EditablePath?`,
    `activeTool` (`EditTool.PEN`/`DIRECT_SELECT`), `selection: Selection` (anchor-id set),
    `pendingPen: PenDraft?`, `snapMask: Int`, `undoStack`/`redoStack: List<EditSnapshot>`.
  - `VectorEditAction.kt` — sealed action set (see 1a checklist for the full list).
  - `VectorEditReducer.kt` — pure `reduce(state, action): VectorEditState`. Snapshot-based
    undo (every geometry action snapshots first; restore = exact inverse; cap 200,
    `NEW_PATH_ID = "edit-path"`). Reuses `Snap` for grid/angle/endpoint snapping in
    `PlaceAnchor` + single-anchor `MoveSelection`. De Casteljau split in `splitSegment`.
  - *(Phase 1b, NEW)* `ui/screens/vector/edit/EditHitTest.kt` — pure `object`. Public:
    `hitTest(path, wx, wy, tolerance, selection, handleCandidates=selection.anchorIds)`
    (priority: handle → anchor → segment), plus the individual `hitAnchor` / `hitHandle`
    / `hitSegment`, the `Hit` sealed interface (`Anchor` / `Handle{subpathId, anchorId,
    side: HandleSide.IN|OUT}` / `Segment{subpathId, segmentIndex, t, x, y}`), and
    `worldTolerance(screenPx, scale) = screenPx/scale` (`DEFAULT_TOLERANCE_PX = 22f`).
    Tolerance is **world-space** (caller divides screen px by `viewport.scale`).
    Segment iteration mirrors the reducer exactly — closed subpaths add a closing span
    `n-1` that wraps to anchor 0 — so a `Hit.Segment` feeds `InsertAnchorOnSegment`
    unchanged. Cubic picking = coarse sample (24) + ternary refine (24); handles are
    only hit for `candidates` (i.e. selected) anchors.
- *(Phase 1c, NEW)* `ui/screens/vector/edit/`:
  - `VectorEditReducer` gained **`MoveHandle(id, side, x, y)`** (the handle-drag gap the
    prior handoff flagged): places the dragged `IN`/`OUT` handle and reconciles the
    opposite per `AnchorType` — `CORNER` independent, `SMOOTH` colinear keeping its own
    length, `SYMMETRIC` mirrored. `side` reuses `EditHitTest.HandleSide`. Pure; pushes one
    undo snapshot.
  - `VectorEditViewModel.kt` — first Android-coupled file in the package. `@HiltViewModel`,
    no-arg `@Inject` ctor, **no coroutine work** (so it's JVM-testable without Robolectric).
    Holds `MutableStateFlow<VectorEditState>` (`state`), `open(document, pathId?)` starts a
    session, `dispatch(action)` = `_state.update { reducer.reduce(it, action) }`. Owns
    `val viewport = ViewportController()`. Gesture API: `onTap(screenX, screenY, additive)`
    (pen→`PlaceAnchor`; else hitTest → `Anchor`=`SelectAnchor` / `Segment`=`InsertAnchorOnSegment`
    / empty=`ClearSelection`; handles ignored on tap), `onDragStart/onDrag/onDragEnd`
    (resolves a `Handle` or `MoveSelection` target at press, applies world-space deltas live,
    and **coalesces the drag to one undo step** by trimming `undoStack` back to its pre-drag
    baseline), plus `pan`/`zoom` and thin `setTool`/`setSnapMask`/`startPath`/`commitPath`/
    `deleteSelected`/`toggleClosed`/`undo`/`redo`/`applyToDocument` pass-throughs.
    `EMPTY_DOCUMENT` (24×24, no paths) is the placeholder before `open`.
- *(Phase 1d, NEW)* `ui/screens/vector/edit/VectorEditCanvas.kt`:
  - `@Composable fun VectorEditCanvas(state, viewport, modifier, onTap, onDragStart, onDrag,
    onDragEnd, onPan, onZoom)` — the screen (1e) passes `vm.state.value` + `vm.viewport` +
    `vm::onTap`/`onDragStart`/`onDrag`/`onDragEnd`/`pan`/`zoom`. `onTap` is `(x, y) -> Unit`
    (additive multi-select is deferred to 1e; canvas taps are always non-additive).
  - Renders through the **existing** preview pipeline (no parallel renderer): static doc
    paths via `preparePreviewPaths(VectorPreviewBuilder.build(document))` minus
    `state.editing?.pathId`; the live edit path via a private `buildEditingRender(editing)`
    that runs `EditablePathSerializer.toCommands → VectorPreviewPathNormalizer.normalize →
    buildComposePath` and resolves paints with `parseVectorColor`/`toStrokeCap`/`toStrokeJoin`.
  - World→screen is the VM's `ViewportController` (`withTransform { translate(off); scale }`),
    **not** the preview's fit transform — same mapping the VM hit-tests against. Overlay
    (knobs/handles/pen-draft/border) is drawn in screen space at constant px.
  - Gesture classification lives in a custom `awaitEachGesture` (NOT `detectTransformGestures`,
    which fires for one finger too): pointer-count gates tap/drag (1) vs pan/zoom (2).
- Reusable, confirmed APIs (still): `VectorPreviewPathNormalizer.normalize`,
  `PathDataFormatter.format`, `PathDataParser.parse`, `ViewportController` (world↔screen,
  bounded clamp), `Snap` (`MASK_GRID/ANGLE/ENDPOINT`, all pure — reducer imports it and
  stays JVM-clean), `VectorPreviewCanvas` internals.

**→ NEXT ACTION:** **Phase 1 step 1e** — `ui/screens/vector/edit/VectorEditScreen.kt`, the
screen + toolbar that hosts `VectorEditCanvas`. Collect `vm.state` (`collectAsState`), put the
canvas in a `Box`/`Scaffold`, and add a toolbar wired to the VM pass-throughs:
Pen (`setTool(PEN)` + `startPath`/`commitPath`) / Direct-select (`setTool(DIRECT_SELECT)`) /
add (insert is a canvas tap on a segment — surface as a hint, no button needed) / delete
(`deleteSelected`) / corner↔smooth↔symmetric (`SetAnchorType` on the selected anchor — note the
VM has **no `setAnchorType` pass-through yet**; either add one or `dispatch` the action directly)
/ close (`toggleClosed` — needs the active subpath id; derive from selection or "last subpath")
/ undo (`undo`, gate on `state.canUndo`) / redo (`redo`, gate on `state.canRedo`). Snap toggles
(`setSnapMask` with `Snap.MASK_GRID/ANGLE/ENDPOINT`) are a nice-to-have. Then 1f (Tune-Up wiring).

**Watch-outs for next session:**
- Reducer + hit-test stay pure (no Android imports); `VectorEditViewModel` + `VectorEditCanvas`
  are the only Android-coupled edit files. Keep new logic in the reducer, not the screen/canvas.
- **Pass `vm.onTap(x, y)` for the canvas tap** (the canvas's `onTap` is `(Float, Float) -> Unit`;
  the VM signature is `onTap(x, y, additive=false)`). If 1e adds a multi-select toggle, thread
  `additive` through a new canvas param — today it's always non-additive.
- The canvas **frames the VM's viewport itself** (fit + `setPanBounds` on document/size change).
  Don't also fit from the screen, or you'll fight it. If 1e wants a "reset view" button, call
  `vm.viewport.fitToContent(bounds, canvasSize)` with the artboard rect.
- `SetAnchorType`/`ToggleSubpathClosed` need a target id. `SetAnchorType` acts on a single
  anchor id — for a multi-anchor selection decide a policy (apply to each, or disable the button
  unless exactly one is selected). `ToggleSubpathClosed` takes a **subpath** id, not an anchor id.
- Drags are **coalesced to one undo step** by the VM in `onDragEnd`; the canvas already brackets
  honestly (incl. cancel). Don't second-guess it from the screen.
- Handle knobs are only drawn/grabbable for **selected** anchors (canvas + `EditHitTest` agree).
- `MoveHandle`/`MoveSelection` apply **world** deltas; the VM divides screen px by `viewport.scale`,
  so the canvas passes raw screen deltas to `onDrag` (already wired).
- Tolerance is world-space and inverse-scales (DEFAULT 22px ÷ scale); zoomed out, anchors dominate
  segment hits — expected. Zoom in to insert points on a segment.
- **Confirm the two "open choices" with the user before wiring UI into Tune-Up (step 1f):**
  in-Tune-Up edit mode vs. dedicated screen (handles-as-absolute-`ControlPoint` already locked).
- Re-run `*.edit.*` after each step; keep them green (currently **59**). 1d/1e are Composables
  (no JVM tests) — the bar is "compiles clean + 59 stays green."

---

## 4. Handoff protocol (instructions to future me)

At the **end of every session**, before stopping:

1. **Update §2 checklist** — flip `[ ]`→`[~]`/`[x]` for what you touched.
2. **Rewrite §3 "Latest handoff"** — set date + last completed, summarize branch
   state, list any NEW reusable code/APIs you added, and write a crisp **→ NEXT
   ACTION** plus watch-outs. Assume the next session has zero memory of this one;
   give it exactly what it needs to start in one read.
3. **Append a per-phase handoff note** under §5 when you finish a phase (what
   shipped, key decisions, deviations from the plan doc, test status).
4. **Commit this file** with your code changes, and **push** the branch.
5. If a phase's plan doc turned out wrong/incomplete, fix the doc too — keep docs
   and reality in sync.

Keep this file the *only* thing a fresh session must read to be oriented.

---

## 5. Per-phase completion notes

### Phase 0 — editable model foundation (2026-06-04)
- **Shipped:** `EditablePath` model + `EditablePathFactory` + `EditablePathSerializer`
  + `VectorDocument.replacePath`; round-trip + replace tests (14, green).
- **Key decisions:** handles stored as **absolute** `ControlPoint`s (match
  normalizer output + on-screen hit-testing); node model normalized to **all-cubic
  absolute** coords; quads degree-elevated (exact), arcs flattened via the
  normalizer (documented token-level lossiness on edit); closed curves whose
  closing segment lands on the start are **folded** into the start anchor for clean
  node counts and exact curved-close round-trip.
- **Verified:** M/L/C/Z round-trips token-exact; relative resolves to absolute;
  quad stays curve-equivalent (sampled); `replacePath` preserves tree position.
- **Deviation from plan:** none material. The Phase 1 doc sketched handles as
  `inX/inY/outX/outY` floats; implemented as a cleaner nullable `ControlPoint` —
  equivalent, avoids half-null states.
