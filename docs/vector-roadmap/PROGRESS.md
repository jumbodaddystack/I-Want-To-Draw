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

### Phase 1 — node editor (the rest of Phase 1) — 🟢 NEARLY COMPLETE (1a–1f done → **only on-device manual verify left**)
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
- [x] **1e. Screen + toolbar:** `ui/screens/vector/edit/VectorEditScreen.kt` — a
  `Scaffold` that collects `vm.state` (`collectAsState`), hosts `VectorEditCanvas` in
  the body (passing `vm.onTap(x,y)` non-additive + the drag/pan/zoom pass-throughs),
  a `TopAppBar` with back / **Undo** (gated on `canUndo`) / **Redo** (`canRedo`) /
  **Done** (`applyToDocument` + `onDone`), and a two-row bottom toolbar of M3 chips:
  **Pen** (`setTool(PEN)` + `startPath()` when no draft) / **Select** (`setTool(DIRECT_SELECT)`) /
  **Finish** (`commitPath`, shown in pen mode, enabled at ≥2 draft anchors) /
  **Delete** (`deleteSelected`, enabled when selection non-empty) /
  **Corner·Smooth·Symmetric** (`setAnchorType` — enabled only when **exactly one**
  anchor is selected, so each retype is one undo step; chip reads the anchor's current
  type) / **Close** (`toggleClosed` on the active subpath = the one holding the
  selection, else the last subpath) / **Snap** Grid·Angle·Endpoint (toggle the
  `Snap.MASK_*` bits via `setSnapMask`). Added VM pass-through `setAnchorType(id, type)`.
  Pure-UI (no reducer logic); compiles clean, `*.edit.*` stays **59**.
- [x] **1f. Wire into Tune-Up:** the node editor opens as a **full-screen mode hosted
  inside `VectorTuneupScreen`** (not a NavHost route — no result-passing to invent, and the
  canvas gets the whole surface instead of fighting the scrolling EDIT tab). The EDIT tab's
  new **"Node editor"** section has **Edit nodes** (enabled when exactly one path is selected →
  opens on that path) and **Draw new path** (opens the pen on an empty canvas). On **Done** the
  edited `VectorDocument` is persisted as a new `MANUAL_EDIT` version through the *existing*
  `persistManualEdit` pipeline (`VectorTuneupViewModel.persistNodeEdit`), so history/diff/export
  all keep working. New plumbing: `VectorDocument.upsertPath` (replace-or-append, so a
  drawn-from-scratch path isn't dropped), reducer `applyToDocument` now upserts + new pen paths
  get a default `#000000` fill, `VectorEditViewModel.openForNewPath`. **Tests:** +2 `upsertPath`
  + 1 reducer new-path-apply → `*.edit.*` now **62, green**. Decision resolved with user:
  embedded mode + new-path drawing (see §5).
- [x] **Open choices (resolved):** (1) **embedded in-Tune-Up full-screen mode** (not a dedicated
  route); (2) handles stored as absolute `ControlPoint`s (locked in Phase 0).
- [ ] On-device manual verify (draw closed shape, snap, undo/redo, export round-trips) — the only
  remaining Phase 1 item; needs a device/emulator (the build env is headless).

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

**Last updated:** 2026-06-04 · **Last completed:** Phase 1 steps 1e + 1f (screen/toolbar + Tune-Up wiring)

**State of the branch:** Phase 0 + 1a–1d are merged to main (PRs #91–#94). Phase **1e + 1f**
are on `claude/vector-roadmap-next-phase-T6EcZ`. `:app:assembleDebug` builds clean and edit
tests are green (**62** in `*.edit.*`: 14 Phase-0 round-trip/replace+upsert + 26 reducer +
14 hit-test + 8 ViewModel). No PR open. **The node editor is now fully wired into Tune-Up
end-to-end:** open it from the EDIT tab → edit/draw on a full-screen canvas → Done saves a new
version. The **only** remaining Phase 1 item is on-device manual verify (needs an emulator/device;
this env is headless). After that, Phase 1 is complete and Phase 2 can start.

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
- *(Phase 1e, NEW)* `ui/screens/vector/edit/VectorEditScreen.kt`:
  - `@Composable fun VectorEditScreen(onNavigateBack, onDone, viewModel = hiltViewModel())` —
    wraps content in `StudioTheme`, collects `vm.state`, hosts `VectorEditCanvas` in a
    `Scaffold` body and a chip toolbar in `bottomBar`. `const val ROUTE_VECTOR_EDIT = "vector-edit"`.
  - Top bar: back / Undo (`canUndo`) / Redo (`canRedo`) / Done (`applyToDocument()` then `onDone()`,
    enabled when `editing != null`). The host reads back the edited doc from `vm.state.document`
    after Done (no document is passed through `onDone` yet — 1f decides that contract).
  - Toolbar (two scrollable chip rows): Pen / Select / Finish / Delete / Corner·Smooth·Symmetric /
    Close / Snap Grid·Angle·Endpoint — see the 1e checklist for exact wiring + enablement.
  - Private helpers `singleSelectedAnchor(state)` / `activeSubpath(state)` derive the targets for
    the type/close chips (pure reads of state — no logic).
  - VM gained pass-through **`setAnchorType(id, type)`** (+ `AnchorType` import).
- *(Phase 1f, NEW)* node editor ↔ Tune-Up wiring:
  - `VectorTuneupScreen.kt` — the EDIT tab gained a **"Node editor"** section (**Edit nodes**,
    enabled when exactly one path is selected; **Draw new path**). A private `NodeEditTarget`
    (`ExistingPath(pathId)` / `NewPath`) hoisted in `VectorTuneupScreenContent` drives a
    full-screen takeover: when set, `NodeEditorHost` renders `VectorEditScreen` (its own
    `hiltViewModel`) and the normal `Scaffold` early-returns. `NodeEditorHost` parses
    `state.sourceVersion.xml → VectorDocument` and calls `editVm.open(doc, pathId)` /
    `editVm.openForNewPath(doc)`; on **Done** it persists `editVm.state.value.document` via
    `tuneupVm.persistNodeEdit(...)` (only if `canUndo`, so a no-edit glance saves nothing),
    on back it discards.
  - `VectorTuneupViewModel.persistNodeEdit(document, label="Edit nodes")` — serializes the
    edited doc (`AndroidVectorDrawableWriter.write`), re-analyzes metrics, and reuses the shared
    `persistManualEdit` pipeline → a new `MANUAL_EDIT` version branched from the source.
  - `VectorEditViewModel.openForNewPath(document)` — opens for drawing (pen tool + fresh draft).
  - `VectorDocument.upsertPath(pathId, newPath)` — replace if the id exists, else **append to
    root**. Reducer `applyToDocument` now upserts (so a drawn-from-scratch path isn't dropped),
    and `commitPath` gives a brand-new path a default `#000000` fill (`NEW_PATH_FILL_COLOR`) so
    it's visible after write-back/export.
- Reusable, confirmed APIs (still): `VectorPreviewPathNormalizer.normalize`,
  `PathDataFormatter.format`, `PathDataParser.parse`, `ViewportController` (world↔screen,
  bounded clamp), `Snap` (`MASK_GRID/ANGLE/ENDPOINT`, all pure — reducer imports it and
  stays JVM-clean), `VectorPreviewCanvas` internals.

**→ NEXT ACTION:** Phase 1 is code-complete. Two options for the next session:
1. **On-device manual verify** (the last Phase 1 checkbox) — needs an emulator/device (this build
   env is headless). Flow to exercise: Tune-Up → parse/import a vector → EDIT tab → select one
   path → **Edit nodes** → drag anchors / insert on a segment / corner↔smooth↔symmetric / close /
   undo·redo → **Done** → confirm a new version appears and **Export** round-trips. Then **Draw new
   path**: pen-place ≥3 points → **Finish** → **Done** → confirm the new (black-filled) path is in
   the exported XML.
2. **Start Phase 2** (boolean ops + outline-stroke + offset) per `phase-2-boolean-path-ops.md` —
   it builds on the Phase 1 editable model, which is now fully in place and wired.

**Watch-outs for next session:**
- **1f decision (resolved):** node editor is an **embedded full-screen mode** in `VectorTuneupScreen`,
  not a NavHost route. The `VectorVersion ⇄ VectorDocument` bridge is the existing
  `AndroidVectorDrawableParser.parse` (enter) / `AndroidVectorDrawableWriter.write` (exit), same as
  every other edit op. `ROUTE_VECTOR_EDIT` in `VectorEditScreen` is currently **unused** (the editor
  is hosted inline, never navigated to) — leave it or wire a route only if a standalone entry is wanted.
- New-path write-back depends on `VectorDocument.upsertPath` (append when id absent) — the older
  `replacePath` would silently drop a from-scratch path. New pen paths get a default `#000000` fill;
  revisit if a different default (stroke-only? last-used colour?) is wanted. The path id is
  `VectorEditReducer.NEW_PATH_ID = "edit-path"` until re-parsed.
- `persistNodeEdit` skips saving when `editVm.state.value.canUndo` is false (no edits), so opening
  and immediately pressing Done won't spawn a no-op version. Entering edit normalizes the path
  (H/V/Q/A/S/T → C/L, documented Phase-0 lossiness); other paths are preserved verbatim by upsert+writer.
- Reducer + hit-test stay pure (no Android imports); `VectorEditViewModel`/`VectorEditCanvas`/
  `VectorEditScreen` + the `NodeEditorHost`/`persistNodeEdit` glue are the Android-coupled files.
  Keep new geometry logic in the reducer.
- The canvas **frames the VM's viewport itself** (fit + `setPanBounds` on document/size change).
  Don't also fit from the host. For a "reset view" call `vm.viewport.fitToContent(bounds, size)`.
- Type chips (`setAnchorType`) are **enabled only when exactly one anchor is selected** (each retype =
  one undo step); `Close` targets the **active subpath**. For multi-anchor retype, add a reducer/VM
  batch action (don't loop dispatches in the screen — that fragments undo).
- Drags are **coalesced to one undo step** by the VM in `onDragEnd`; the canvas brackets honestly
  (incl. cancel). Handle knobs draw/grab only for **selected** anchors (canvas + `EditHitTest` agree).
- Tolerance is world-space and inverse-scales (DEFAULT 22px ÷ scale); zoomed out, anchors dominate
  segment hits — zoom in to insert points on a segment.
- Re-run `*.edit.*` after each step; keep them green (currently **62**). Composables add no JVM tests —
  the bar is "compiles/assembles clean + 62 stays green."

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

### Phase 1 — node editor (2026-06-04)
- **Shipped (1a–1f):** pure reducer core (`VectorEditState`/`Action`/`Reducer`), pure
  `EditHitTest`, Hilt `VectorEditViewModel` (gesture→action, drag-coalesced undo), Compose
  `VectorEditCanvas` (reuses the preview pipeline) + `VectorEditScreen` (chip toolbar), and the
  Tune-Up integration: an EDIT-tab "Node editor" section that opens a **full-screen embedded mode**
  to edit one path or draw a new one, saving the result as a new `MANUAL_EDIT` version. **62 `*.edit.*`
  tests green; `:app:assembleDebug` clean.**
- **Key decisions:** (1) **embedded full-screen mode**, not a NavHost route — maximal reuse of the
  existing `persistManualEdit` version pipeline, no nav-result plumbing, and the canvas owns the whole
  surface (a pan/zoom/drag editor can't share the scrolling EDIT tab). Confirmed with the user, who
  also asked to include **new-path drawing** now. (2) Write-back uses `upsertPath` (replace-or-append)
  so from-scratch paths persist; new pen paths default to `#000000` fill so they're visible. (3) Save
  is skipped when nothing was edited (`canUndo == false`).
- **Verified:** compile + assemble clean; 62 edit tests (incl. new `upsertPath` append + reducer
  new-path-apply). **Not yet verified:** on-device manual interaction (headless build env) — the one
  open Phase 1 checkbox.
- **Deviation from plan:** the plan doc floated "VectorEditScreen.kt (or an in-Tune-Up mode)"; we did
  the in-Tune-Up mode and `VectorEditScreen` is hosted inline (its `ROUTE_VECTOR_EDIT` is currently
  unused). Added `VectorDocument.upsertPath` + `VectorEditViewModel.openForNewPath` +
  `VectorTuneupViewModel.persistNodeEdit`, none of which the plan doc anticipated but all minimal.
