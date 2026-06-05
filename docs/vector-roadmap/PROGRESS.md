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

### Phase 2 — boolean ops + outline-stroke + offset — 🟢 NEARLY COMPLETE (code-complete → only on-device manual verify left)
- [x] Pure `data/vector/edit/boolean/` module (no Android imports): `Polygon` (`Ring`/`PolyShape`/`FillRule`),
  `FillRuleResolver`, `PathFlattener` (cubics→polygons via the existing sampler/simplifier),
  `PolygonClipper` (UNION/INTERSECT/DIFFERENCE/XOR + `selfUnion`), `CurveRefit` (polygon→cubic Schneider fit),
  `StrokeOutliner`, `PathOffset`, and the `PathBoolean` façade (`combine`/`outlineStroke`/`offset`).
- [x] Clipper is an **arrangement + boundary-classification** clipper (not Martinez–Rueda — see §5 deviation):
  robust on shared/collinear/coincident edges and figure-eights (golden-geometry tests prove it).
- [x] Reducer actions `BooleanOp(kind)` / `OutlineStroke` / `OffsetPath(delta)` + `BoolOpKind`
  (each one undo entry), VM pass-throughs, and a new shape-ops toolbar row in `VectorEditScreen`.
- [x] Tests: `PolygonClipperTest` (7), `PathBooleanTest` (8), `StrokeOutlinerTest` (4), `PathOffsetTest` (4),
  `CurveRefitTest` (3), `FillRuleResolverTest` (2), `VectorBooleanReducerTest` (6) → **34 new, green**.
- [x] `:app:assembleDebug` clean; `*.edit.*` now **96, green** (62 Phase 1 + 34 Phase 2).
- [ ] On-device manual verify (select 2 subpaths → Union/Subtract/Intersect/Exclude; Outline a stroked path;
  Offset ±; undo/redo each; Done → new version exports + re-imports) — headless build env can't do this.

### Phase 3 — pixel-perfect pipeline — 🟢 NEARLY COMPLETE (code-complete → only on-device manual verify left)
- [x] Pure `data/vector/KeylineGrid.kt` — `KeylineShape`, `KeylineGrid` (`safeZone`/`keylineLines`/
  `shapeFigures` with a sealed `Figure` = `Line`/`Circle`/`RoundRect`), `KeylinePresets.MATERIAL_24`/
  `forViewport`. Every figure derives off `edge` by ratio (48-grid = 24-grid ×2).
- [x] Pure `data/vector/VectorQuantizer.kt` — `mapCoordinates(doc, fn)` (separable per-axis walker,
  re-emits `pathData`, passes unparsed paths verbatim) + `quantize(doc, step=1f)` (round-to-grid,
  clamp into viewport, idempotent, kind-preserving).
- [x] Pure `data/vector/IconSizeSet.kt` — `IconTarget(24/48/108)`, `OpticalAdjust(scale, paddingOverride)`,
  `IconSizeSet.derive`/`deriveAll` (uniform scale of viewport+coords + optical scale about centre +
  `strokeWidth` tracking).
- [x] Pure `data/vector/IconSetExporter.kt` — `Format(VD/SVG)`, `Spec`, `Artifact`, `exportSet(spec, baseName)`
  + `exportBatch` (derive→quantize→serialize through the existing writers, lossless).
- [x] Pure `ui/components/notes/EditSnap.kt` — `MASK_PIXEL=0x8` + `quantize`/`quantizeInBounds`
  (unconditional integer-grid snap, reuses `Snap.SnapResult`).
- [x] Reducer/state/action: `VectorEditState` gained `keyline`/`sizeSet`; actions `ToggleKeyline`/
  `SetPixelSnap`/`SetOpticalAdjust`; pixel-snap is the final step in `PlaceAnchor` (via `snap()`) and
  single-anchor `MoveSelection`. VM pass-throughs added.
- [x] UI: `KeylineOverlay.kt` (DrawScope ext, drawn under the anchor overlay in `VectorEditCanvas`),
  `MultiSizePreviewStrip.kt` (reuses `VectorPreviewCanvas`), `IconExportPanel.kt` (size/format/quantize
  → `IconSetExporter.Spec`). `VectorEditScreen` adds Pixel/Keyline snap chips, a size-preview toggle, and
  an export `ModalBottomSheet`; wired through Tune-Up `NodeEditorHost` → `VectorTuneupViewModel.exportIconSet`.
- [x] `data/vector/VectorTuneupExporter.exportIconSet(name, spec)` — zips every `Artifact` into one
  shareable `.zip` (mirrors `exportBundle`).
- [x] Tests: `KeylineGridTest` (5), `VectorQuantizerTest` (5), `IconSizeSetTest` (5), `IconSetExporterTest` (5),
  `EditSnapTest` (4), `VectorEditReducerPixelSnapTest` (7) → **31 new, green**. `:app:assembleDebug` clean;
  `*.edit.*` now **103** (96 + 7).
- [ ] On-device manual verify (toggle keyline overlay; pixel-snap a drawn anchor onto integers; size-preview
  strip updates live; export a 24/48/108 × VD/SVG `.zip` and re-import) — headless build env can't do this.

### Phase 4 — unify the two domains — 🟢 CORE COMPLETE (pure JVM bridge + AI routing done → export-rewire + UI-entry remain)
Per `phase-4-unify-domains.md`. Steps 1–4 + the pure integration tests are done (the plan's "bulk of the value");
the two Android-coupled, headless-unverifiable steps are deferred to an on-device session.
- [x] **Bridge core (pure, no Android imports)** `data/vector/notesbridge/`: `PolylineSimplify` (shared RDP),
  `AutoShapeFitter` (line/rect/circle), `StrokeVectorizer` (samples→centerline→Catmull-Rom cubic `EditablePath`),
  `ShapeVectorizer` (`Shape`→exact kappa-arc commands→`EditablePath`), `NoteVectorBridge`
  (`toDocument`/`strokeToEditablePath`/`shapeToEditablePath`, `WidthMode`, `NoteVectorResult`, `ColorHex`).
- [x] **One RDP:** `EditPreviewController.simplifyStroke` now delegates to `PolylineSimplify.keepMask`
  (numerically identical; `EditPreviewControllerTest` still green — characterization preserved).
- [x] **AI routing (geometry-free):** `EditOpToManualEdit` maps notes `EditOp` Recolor/Restyle/Delete/Simplify →
  `VectorManualEdit`; geometry/canvas ops skipped. Tune-Up edit-plans/redraw already operate on `VectorDocument`,
  so they now also apply to a bridged-from-ink document (proved by integration test).
- [x] **Tests (pure JVM):** `StrokeVectorizerTest` (5), `ShapeVectorizerTest` (5), `AutoShapeFitterTest` (4),
  `NoteVectorBridgeTest` (3), `EditOpToManualEditTest` (4), `UnifiedModelIntegrationTest` (4) → **25 new, green**.
  Lossless export is proved at the model level (bridge → `AndroidVectorDrawableWriter` → `AndroidVectorDrawableParser`
  byte-identical path data). Full suite green apart from the known ~21 `data/notes` Color/Log "not mocked" failures.
- [ ] **Lossless export rewire** (`NoteSvgExporter`/`NoteVectorDrawableExporter` → route strokes/shapes through
  the bridge + document writers, compose text/image via existing emitters). Android-coupled; their tests need the
  `Color`/`Log` framework stubs (the known-failing set) so this can't be verified headless — do it on-device.
- [ ] **UI entry**: a note opens directly onto the Phase-1 node canvas via the bridged document
  (`VectorTuneupScreen` import hook). Needs the running app to verify.

### Phase 5 — production polish — ⬜ NOT STARTED
- [ ] Per `phase-5-production-polish.md`: stroke styling, gradients, vector symbols,
  keyboard ergonomics, AI auto-trace. (Independent sub-features; pick by priority.)

---

## 3. Latest handoff (update this each session)

**Last updated:** 2026-06-05 · **Last completed:** Phase 4 **core** (notes↔vector bridge + AI routing; pure JVM)

**State of the branch:** Phases 0–3 merged to main (PRs #95/#96/#97). Phase **4 core** is on
`claude/vector-roadmap-next-phase-qlI0h`. `compileDebugUnitTestKotlin` is clean; the full JVM suite is green
apart from the **known** ~21 `data/notes` `Color`/`Log` "not mocked" failures
(`NoteRasterizerTest`/`NoteSvgExporterTest`/`NoteVectorDrawableExporterTest`/`NoteAiServiceTest` — present on a
clean checkout, unrelated to this change). **25 new pure-JVM tests** under `data/vector/notesbridge/` +
`data/vector/UnifiedModelIntegrationTest`, all green; `*.edit.*` and `EditPreviewControllerTest` unaffected.
No PR open.

**What Phase 4 core delivers:** the notes canvas and the Tune-Up workspace are now **two views of one editable
`VectorDocument`** at the model level. A pure `data/vector/notesbridge/` module vectorizes committed freehand
ink + shapes into the *same* Phase-1 `EditablePath`/`VectorDocument` the node editor / boolean ops / grids /
lossless writers already use, and routes geometry-free notes AI ops onto the shared `VectorManualEdit` vocabulary.
Two Android-coupled, headless-unverifiable tails remain (exporter rewire + UI entry — see checklist); they're the
right place for the next on-device session to finish Phase 4. Phase 5 can also start independently.

**Phase 4 — what exists now (for the next session to build on):**
- *(NEW, pure JVM — no Android/Compose imports)* `data/vector/notesbridge/`:
  - `PolylineSimplify.kt` — the **one** notes RDP. `keepMask(points, tol): BooleanArray` + `simplify(...)` +
    `perpDistance(...)`. Replicates `EditPreviewController`'s exact numerics (eps `1e-6`, `maxDist=0` init), and
    `EditPreviewController.simplifyStroke` now **delegates** here (its private `rdp`/`perpDistance` deleted), so
    notes + bridge share one implementation. `VectorPathSimplifier` (optimizer side) is left untouched.
  - `AutoShapeFitter.kt` — `fit(points): Shape?` over a simplified polyline. Conservative line / circle-ellipse /
    axis-rect detection (line = chord-relative deviation; circle = bbox-ellipse normalized-radius spread; rect =
    every point hugs a bbox edge **and** all four corners are visited). Strict thresholds → a deliberate squiggle
    returns null. Returns a `ui.components.notes.Shape` (pure value type).
  - `StrokeVectorizer.kt` — `toEditablePath(item, widthMode, autoShape=true)`. Decode `StrokeCodec` → centerline
    polyline (+ mean pressure) → dedupe → `PolylineSimplify` → optional `AutoShapeFitter` (snap to primitive) →
    else **Catmull-Rom → Bézier** cubic fit (`handle = p ± (next−prev)/6`; interior SMOOTH, endpoints CORNER) →
    one open `EditSubpath`. Style: `strokeColor=#AARRGGBB`, `strokeWidth=max(0.5, base×meanPressure)` (matches the
    legacy exporters' collapse), round cap/join, no fill. `SIMPLIFY_TOLERANCE=0.75`.
  - `ShapeVectorizer.kt` — `toCommands(shape)` (exact line/rect/ellipse/arrow/polygon; rounded-rect + ellipse use
    the `0.5523` kappa cubic arcs; ellipse bakes `rotationRad`), `fromShape(shape, style, id, name)` and
    `toEditablePath(item)` → run commands through `EditablePathFactory` so the result is the same all-cubic
    `EditablePath`. Fill from `ShapeCodec` `fillArgb`, stroke from the item.
  - `NoteVectorBridge.kt` — `toDocument(items, viewport, widthMode=CENTERLINE_UNIFORM): NoteVectorResult`
    (z-order → root children; `itemToPathId` map; text/image + degenerate items in `skipped`), single-item entries
    `strokeToEditablePath`/`shapeToEditablePath`, `pathId(item) = "note_${id}"`, `enum WidthMode`
    (`CENTERLINE_UNIFORM` wired; `OUTLINE_FILL` is a deferred seam), and `internal object ColorHex` (ARGB→hex,
    so the bridge avoids `android.graphics.Color`).
  - `EditOpToManualEdit.kt` — `convert(ops, idToPathId)` / `convertOne(...)`: notes `EditOp` Recolor→`RecolorPaths`
    (stroke), Restyle(width)→`RestylePaths`, Delete→`DeletePaths`, Simplify→`SimplifyPaths`. Geometry ops
    (Transform/ReplaceWithShape/Smooth) + canvas ops (SetLayer/Group) and unresolvable ids are skipped.
- *(MODIFIED)* `ui/screens/notes/EditPreviewController.kt` — `simplifyStroke` delegates to `PolylineSimplify`;
  its private `rdp`/`perpDistance` removed (behavior identical, `EditPreviewControllerTest` green).
- **Decision record (enforced):** canonical model = editable `VectorDocument`; bridge is **one-way** (ink →
  document) at the commit boundary; variable width = **centerline cubic + uniform stroke** (the `WidthMode` enum
  keeps `OUTLINE_FILL` open). Matches `phase-4-unify-domains.md` §"Decision record".

**Phase 3 — what exists now (for the next session to build on):**
- *(NEW, pure JVM — no Android imports)* `data/vector/`:
  - `KeylineGrid.kt` — `enum KeylineShape`, `data class KeylineGrid(edge=24, padding=1, shapes)` with a
    sealed `Figure` (`Line`/`Circle`/`RoundRect`). `safeZone()` = viewport inset by `padding`;
    `keylineLines()` = 4 safe-zone edges + centre cross; `shapeFigures()` = the selected keyline shapes.
    Standard Material sizes on `edge=24`: square live 18 (inset 3), circle Ø 20, vert-rect 16×20,
    horiz-rect 20×16, rounded-square corner 2. `object KeylinePresets { MATERIAL_24; forViewport(vp) }`
    (scales edge + padding by `viewportWidth/24`).
  - `VectorQuantizer.kt` — `object`. `mapCoordinates(doc, fn)` walks every path's `commands` through a
    **separable per-axis** `fn` (probes `H`/`V` on their single axis with a `0` placeholder), re-emits
    `pathData` from the mapped commands, and passes null-`commands` (unparsed) paths through verbatim.
    `quantize(doc, step=1f)` rounds every coord to the nearest multiple of `step` and clamps into the
    viewport box — idempotent, command-kind-preserving. `step = viewportWidth/dp` for a device-pixel grid.
  - `IconSizeSet.kt` — `enum IconTarget(dp)` (24/48/108), `data class OpticalAdjust(scale=1, paddingOverride?)`,
    `data class IconSizeSet(master, targets, adjust)`. `derive(target)`: new `T×T` viewport, every coord
    `c → center + (c·(T/E) − center)·s` (uniform scale + optical scale about centre), `strokeWidth ×= (T/E)·s`.
    `deriveAll()` → `Map<IconTarget, VectorDocument>`. Reuses `VectorQuantizer.mapCoordinates`.
  - `IconSetExporter.kt` — `object`. `enum Format(extension)` (`ANDROID_VECTOR_DRAWABLE`/`SVG`),
    `data class Spec(sizes, formats, quantize=true)`, `data class Artifact(target, format, filename, content)`.
    `exportSet(spec, baseName="icon")` derives each target → quantizes (if `Spec.quantize`) → serializes via
    the **existing** `AndroidVectorDrawableWriter`/`VectorSvgWriter` (lossless, deterministic order:
    targets then `Format.entries`). `exportBatch(name→spec)` flattens many masters. Filenames `${baseName}_${dp}.${ext}`.
- *(NEW, pure)* `ui/components/notes/EditSnap.kt` — `object`, `const MASK_PIXEL=0x8`, `quantize(x,y,step=1f)`
  (unconditional round, reuses `Snap.SnapResult`), `quantizeInBounds(x,y,bounds,step=1f)` (quantize then
  clamp into `[minX,minY,maxX,maxY]`). Sibling of `Snap`, not a modification of it.
- *(MODIFIED)* `ui/screens/vector/edit/`:
  - `VectorEditState.kt` — `+ keyline: KeylineGrid?`, `+ sizeSet: IconSizeSet?` (neither is part of the undo
    `snapshot()` — both are view/config, not undoable geometry).
  - `VectorEditAction.kt` — `+ object ToggleKeyline`, `+ data class SetPixelSnap(enabled)`,
    `+ data class SetOpticalAdjust(target, adjust)`.
  - `VectorEditReducer.kt` — handlers for the three; `snap()` (used by `PlaceAnchor`) runs
    `EditSnap.quantizeInBounds` as the **final** step when `MASK_PIXEL` is set (clamped to `viewportBounds`);
    single-anchor `MoveSelection` likewise grid- then pixel-snaps. `ToggleKeyline`/`SetOpticalAdjust` are
    non-undoable (no master geometry change); `SetOpticalAdjust` lazily creates `IconSizeSet(master=document)`.
  - `VectorEditViewModel.kt` — pass-throughs `toggleKeyline()`/`setPixelSnap(enabled)`/`setOpticalAdjust(target, adjust)`.
    **(VM ctor is still no-arg `@Inject` — do NOT inject the exporter here; it would break the Robolectric-free
    `VectorEditViewModelTest`. Export is hoisted to the Tune-Up VM instead.)**
  - `KeylineOverlay.kt` *(NEW)* — `DrawScope.drawKeylineOverlay(keyline, viewport, …)`; `VectorEditCanvas`
    draws it (world→screen via the same `ViewportController`) beneath the anchor overlay; canvas gained
    `keylineLineColor`/`keylineShapeColor`.
  - `MultiSizePreviewStrip.kt` *(NEW)* — renders `IconSizeSet.deriveAll()` thumbnails via `VectorPreviewCanvas`.
  - `IconExportPanel.kt` *(NEW)* — size/format/quantize chips → builds an `IconSetExporter.Spec` → `onExport`.
  - `VectorEditScreen.kt` — snap row gained **Pixel** + **Keyline** chips; top bar gained a size-preview
    toggle (shows `MultiSizePreviewStrip` above the toolbar) and an export button (opens a `ModalBottomSheet`
    hosting `IconExportPanel`). New `onExportIconSet: (Spec)->Unit` screen param; the preview/export master
    is the document with the live editing path folded in (`currentMaster`).
- *(MODIFIED)* `data/vector/VectorTuneupExporter.kt` — `suspend exportIconSet(name, spec)` runs
  `IconSetExporter.exportSet` and **zips** every artifact into one `.zip` (atomic `.tmp`→rename + prune,
  `"zip"` added to `MANAGED_EXTENSIONS`). `ui/screens/vector/VectorTuneupViewModel.kt` — `exportIconSet(spec)`
  (viewModelScope, emits `ExportReady(uri, "application/zip")`); `NodeEditorHost` passes
  `onExportIconSet = { tuneupViewModel.exportIconSet(it) }`.

**Phase 2 — what exists now (for the next session to build on):**
- *(NEW)* `data/vector/edit/boolean/` — **pure JVM, no Android imports** (unit-tested like the
  reducer):
  - `Polygon.kt` — internal `Ring` (shoelace `signedArea`/`oriented`), `PolyShape` (rings + `FillRule`,
    `area`), `FillRule`.
  - `FillRuleResolver.kt` — `VectorStyle.fillType` ⇄ `FillRule` (null/unknown ⇒ NONZERO; `"evenOdd"` token).
  - `PathFlattener.kt` — `EditablePath`→`PolyShape` and `flattenSubpath`/`flattenCenterline`; reuses
    `VectorPathSampler`/`VectorPathSimplifier`; world-tolerance→step-count heuristic.
  - `PolygonClipper.kt` — `clip(subject, clip, BoolOp)` for UNION/INTERSECT/DIFFERENCE/XOR **and**
    `selfUnion(shape)`. Approach = **arrangement + boundary-classification** (split all edges at
    intersections incl. collinear overlaps → classify each by sampling the operands' winding just off
    each side → orient kept-region-on-left → chain via DCEL "next = clockwise from twin"). All in
    `Double`. Output rings correctly oriented (outer CCW, holes CW), emitted as NONZERO.
  - `CurveRefit.kt` — `refit(ring, maxError, idPrefix, cornerAngleDeg)` → `EditSubpath`. Splits at
    corners (turn angle), Schneider least-squares cubic fit per smooth run (recursive subdivide +
    Newton reparam), straight runs → handle-less anchors (serialize as `LineTo`); closed smooth loops
    fit with a continuous seam tangent so the seam anchor stays SMOOTH.
  - `StrokeOutliner.kt` — `outline(centerline, closed, width, cap, join, miterLimit)` builds the outline
    as a **union of pieces** (segment quads + per-vertex join: round disk / miter wedge / bevel / + caps),
    fused by `selfUnion`. Closed centerline → annulus (outer + inner ring). `capOf`/`joinOf` map style strings.
  - `PathOffset.kt` — `offset(shape, delta, join)` via morphology: grow = `UNION(shape, band)`, shrink =
    `DIFFERENCE(shape, band)` where `band` = stroke outline of every contour at width `2·|delta|`
    (round joins = Minkowski-with-disk). Over-shrink → empty (caller declines).
  - `PathBoolean.kt` — **public façade** (`object PathBoolean`): `Op{UNION,SUBTRACT,INTERSECT,EXCLUDE}`,
    `Options(flattenTolerance=0.25, refitMaxError=0.5, cornerAngleDeg=30)`, `combine(paths, op, newPathId)`
    (≥2; folds pairwise; SUBTRACT = subject − union(rest)), `outlineStroke(path, newPathId)` (needs
    `strokeWidth>0`), `offset(path, delta, newPathId)`. Boolean/outline results are pure fills (stroke
    cleared, canonical `fillType`); offset keeps the input style. Returns null when the result is empty.
- *(MODIFIED)* `ui/screens/vector/edit/`:
  - `VectorEditAction.kt` — `BooleanOp(kind)` / `OutlineStroke` / `OffsetPath(delta)` + top-level
    `enum BoolOpKind`.
  - `VectorEditReducer.kt` — handles all three (each `pushingUndo()` = one undo step). **`BooleanOp`
    operates on the SUBPATHS of the editing path** that hold a selected anchor (≥2 required, else no-op);
    each selected subpath becomes a single-subpath operand, the result replaces them (kept subpaths
    preserved, ids reissued `${pathId}.bopN`), selection cleared. `OutlineStroke`/`OffsetPath` act on
    the whole editing path. Imports `PathBoolean`; reducer still otherwise pure.
  - `VectorEditViewModel.kt` — pass-throughs `booleanOp(kind)`/`outlineStroke()`/`offsetPath(delta)`.
  - `VectorEditScreen.kt` — new **shape-ops toolbar row**: Union/Subtract/Intersect/Exclude (enabled when
    ≥2 subpaths selected via `selectedSubpathCount`), Outline (enabled when `strokeWidth>0`), Offset +/−
    (`OFFSET_STEP = 1f`). Snap row moved to row 3.

**Phase 1 — what exists (still current, for context):**
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

**→ NEXT ACTION:** Phase 4 **core** (pure JVM bridge + AI routing) is in and green. Options for the next session:
1. **Finish Phase 4's two Android-coupled tails** (need a device/emulator to verify):
   a. **Lossless export rewire** — point `NoteSvgExporter`/`NoteVectorDrawableExporter` at
      `NoteVectorBridge.toDocument(...)` + `AndroidVectorDrawableWriter`/`VectorSvgWriter` for strokes/shapes,
      composing the existing `<text>`/`<image>` emitters (don't fold those into the Tune-Up writers). These
      exporters use `android.graphics.Color`, so their tests are in the **known-failing** set — verify on-device.
   b. **UI entry** — a notes "Open in vector editor" hook that runs the bridge and hands the document to the
      Phase-1 node canvas (`VectorTuneupScreen`/`NodeEditorHost`).
2. **On-device manual verify** of the Phase 1–4 flows (still the only open checkboxes for 1/2/3) and the new
   Phase-4 flow: draw a wobbly circle + a squiggle → open in vector editor → circle becomes an exact ellipse,
   squiggle an editable cubic → move an anchor → export VD+SVG → re-import → geometry identical, uniform round stroke.
3. **Start Phase 5** (production polish) — independent sub-features on the Phase-1 model.

**Watch-outs for next session (Phase 4):**
- **The bridge is pure and model-level; the export rewire is where Android creeps in.** Keep new vectorization
  math in `data/vector/notesbridge/` (JVM-testable). The exporters' `Color`/`Log` usage is exactly why their
  tests need framework stubs and fail headless — that's why losslessness is proved at the **document** level
  (`UnifiedModelIntegrationTest.bridgedStrokeAndShapeExportLosslesslyThroughDocumentWriter`), not through those
  exporters. When rewiring, fit the bridged note-pixel viewport into the chosen icon dp box (reuse the Phase-3
  `IconSizeSet`/`VectorQuantizer` rather than re-deriving a transform).
- **`AndroidVectorDrawableParser` reissues path ids** (VectorDrawable XML has no id attribute) — never assert
  round-trip equality by `path.id` across write→parse; compare by position + `pathData` (the integration tests do).
- **Auto-shape is conservative by design.** Thresholds (`LINE_REL_TOL` .06 / `CIRCLE_REL_TOL` .16 / `RECT_REL_TOL`
  .10 / `CLOSE_REL_TOL` .22) favor under-fitting; `genericSquiggleNotFitted` is the guard. It's opt-out via
  `StrokeVectorizer.toEditablePath(..., autoShape=false)` so the raw curve is always reachable.
- **One RDP now.** `EditPreviewController.simplifyStroke` delegates to `PolylineSimplify`; if you touch RDP, the
  numeric contract (eps `1e-6`, `maxDist=0` init) lives there and `EditPreviewControllerTest` pins notes' output.
  `VectorPathSimplifier` (optimizer, slightly different init) was intentionally **not** merged — don't.
- **Width is collapsed to a uniform mean** (`max(0.5, base×meanPressure)`), matching the legacy exporters. The
  `WidthMode.OUTLINE_FILL` seam exists for true variable-width outline-fill later without touching callers.

**Watch-outs carried over (Phase 3):**
- **`VectorEditViewModel` must stay no-arg-injectable.** Export was deliberately NOT plumbed through the edit
  VM (that would need an injected `VectorTuneupExporter` + coroutine and break `VectorEditViewModelTest`'s
  Robolectric-free construction). It is hoisted: screen `onExportIconSet` callback → `NodeEditorHost` →
  `VectorTuneupViewModel.exportIconSet`. Keep export plumbing on the Tune-Up VM side.
- **"Lossless" depends on absolute, already-cubic geometry** (Phase 1 guarantees it on edit-entry). The
  quantizer rounds *before* serialization and the `exported_vector_drawable_reimports_geometry_stable` test is
  the guardrail. `VectorQuantizer.mapCoordinates` assumes a **separable per-axis** transform (true for scale +
  grid-quantize); `H`/`V` recover their axis by probing `fn` with a `0` on the other axis. **ArcTo radii are
  left intact** (out of scope) — only the arc endpoint is mapped. Relative commands are mapped coord-by-coord,
  which is only correct for editor output (all-absolute); a doc full of relative deltas would mis-quantize.
- **Optical adjust is manual-only** (a per-size uniform `scale` about centre + `paddingOverride`). No shape-aware
  auto-trim — that's explicitly out of scope and would re-introduce non-determinism into a deterministic export.
  `paddingOverride` is carried in `OpticalAdjust` but only the keyline overlay consumes it; `derive` ignores it.
- **Keyline `padding` is a tunable** (Material 1dp keyline vs. live-area inset is easy to get visually wrong).
  `KeylinePresets.MATERIAL_24` pins `padding=1`; `KeylineGridTest` pins the exact derived rects so a designer
  tweaks one constant. `forViewport` scales padding too, so a 48 grid is a clean ×2.
- **Export emits a `.zip`** (one entry per `target×format`), surfaced as `ExportReady(uri, "application/zip")`.
  `IconSetExporter` is pure (no Android I/O) and re-parseable, so extend it with JVM tests; the zip/URI step is
  the only Android-coupled part. Filenames are `${baseName}_${dp}.${ext}`.
- Re-run `*.edit.*` + the new `data/vector/` Phase 3 tests after each step; keep them green (currently **103**
  edit + **24** pure). The known ~21 `data/notes` `NoteRasterizer` "not mocked" failures are unrelated.

**Watch-outs carried over (Phase 2):**
- **Clipper choice / deviation:** the plan recommended Martinez–Rueda; I shipped an **arrangement +
  boundary-classification** clipper instead (O(n²) all-pairs split, then per-edge winding sampling,
  then DCEL contour chaining). It is robust on the degenerate cases (shared/collinear/coincident edges,
  figure-eight — all tested) and far less subtle to get right than an in-place sweep. O(n²) is fine at
  icon flattening scale. `selfUnion` is the single-pass merge used by outline/offset. If a future need
  demands huge polygons, this is the spot to swap in a sweep-line.
- **Boolean operand model (deviation from plan):** the plan said "selected *paths*", but the Phase 1
  editor holds **one** `editing: EditablePath`. So `BooleanOp` combines the **selected subpaths** of that
  one path (subpaths containing a selected anchor). Outline/offset act on the whole editing path. If you
  later want cross-path booleans, the editor must hold/select multiple paths first.
- **Lossiness is bounded + intentional:** flatten→clip→refit. `PathBoolean.Options` carries the knobs
  (`flattenTolerance` 0.25, `refitMaxError` 0.5, `cornerAngleDeg` 30). Geometry tests assert *within
  tolerance* (area/winding invariants like `|A∪B|+|A∩B| = |A|+|B|`), never exact float equality.
- **Orientation matters for `selfUnion`:** it force-orients every input ring CCW (solids) before the
  non-zero pass — oppositely-wound overlaps would otherwise cancel. `clip()` operands don't need this
  (they're already single-orientation or prior clean output).
- **Result style:** boolean/outline results are pure fills (stroke cleared, `fillType` = canonical/null
  for non-zero, which is what correctly-oriented rings render as). Offset keeps the input style. A
  fill-less subject gets `#000000` so the result is visible. `OutlineStroke`/`OffsetPath` reducer
  handlers reuse the façade's fillOnly style but re-apply `editing.name`.
- **No-ops don't push undo:** `BooleanOp` (<2 selected subpaths), `OutlineStroke` (no stroke),
  `OffsetPath` (delta 0 or over-shrink→empty) all return state unchanged. Each successful op = exactly
  one undo entry (`VectorBooleanReducerTest` asserts this + exact undo/redo inversion).
- Re-run `*.edit.*` after each step; keep them green (currently **96**). The boolean module is pure JVM
  so it's the easy part to extend with tests; composables add no JVM tests.

**Watch-outs carried over (Phase 1, still true):**
- Node editor is an **embedded full-screen mode** in `VectorTuneupScreen`, not a NavHost route;
  `ROUTE_VECTOR_EDIT` is unused. The `VectorVersion ⇄ VectorDocument` bridge is `AndroidVectorDrawableParser`
  / `AndroidVectorDrawableWriter`. `persistNodeEdit` skips saving when nothing was edited (`canUndo==false`).
- New-path write-back uses `VectorDocument.upsertPath` (append when id absent); new pen paths default to
  `#000000` fill; new-path id is `VectorEditReducer.NEW_PATH_ID = "edit-path"`.
- Reducer + hit-test + the whole `boolean/` module stay pure (no Android imports); the VM/canvas/screen +
  `NodeEditorHost`/`persistNodeEdit` glue are the Android-coupled files. Keep new geometry in the
  reducer/boolean module.
- Drags coalesce to one undo step (`onDragEnd`); handle knobs draw/grab only for selected anchors.
  Tolerance is world-space, inverse-scales (22px ÷ scale).

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

### Phase 2 — boolean ops + outline-stroke + offset (2026-06-04)
- **Shipped:** a pure `data/vector/edit/boolean/` module (`Polygon`, `FillRuleResolver`, `PathFlattener`,
  `PolygonClipper` + `selfUnion`, `CurveRefit`, `StrokeOutliner`, `PathOffset`, `PathBoolean` façade) plus
  reducer actions `BooleanOp`/`OutlineStroke`/`OffsetPath` (+ `BoolOpKind`), VM pass-throughs, and a
  shape-ops toolbar row in `VectorEditScreen`. **34 new `*.edit.*` tests green (96 total); `:app:assembleDebug` clean.**
- **Key decisions:**
  1. **Clipper = arrangement + boundary-classification, NOT Martinez–Rueda.** Split every edge (both
     shapes + self) at all intersections incl. collinear overlaps → for each unique edge sample the
     operands' winding a hair off each side → keep edges where the op's predicate flips, oriented
     kept-region-on-left → chain into rings via the DCEL "next = clockwise-from-twin" rule. All in `Double`.
     This is robust on icon geometry's degeneracies (shared/collinear edges, figure-eight — all tested)
     and far easier to get provably right than an in-place sweep; O(n²) is fine at flattening scale.
  2. **Offset by morphology** reusing the clipper+outliner: grow = `UNION(shape, boundaryBand(2·δ))`,
     shrink = `DIFFERENCE(shape, boundaryBand(2·|δ|))`. Over-shrink → empty → reducer no-op.
  3. **Outline by union-of-pieces** (segment quads + per-vertex join + caps) fused by `selfUnion`, which
     force-orients pieces CCW so non-zero winding doesn't cancel overlaps. Closed centerline → annulus.
  4. **Boolean operands are the editing path's selected *subpaths*** (the editor holds one path), a
     necessary adaptation of the plan's "selected paths". Outline/offset act on the whole path.
  5. **Results are pure fills** (stroke cleared, canonical `fillType`); offset keeps style; flatten→refit
     lossiness is bounded by `PathBoolean.Options` and asserted within-tolerance in tests.
- **Verified:** 34 JVM tests — clipper golden geometry (union/subtract/intersect/xor area invariants,
  disjoint-empty, concentric, shared-collinear→one-ring, figure-eight valid rings), façade round-trip
  back through `EditablePathSerializer`→`PathDataParser`, outline rectangle/annulus/round-cap/miter-limit,
  offset grow/shrink/over-shrink/concave, refit circle/rectangle/straight, fill-rule mapping, and reducer
  single-undo / no-op / undo-redo-inversion. Compile + assemble clean.
- **Not yet verified:** on-device manual interaction (headless build env) — the one open Phase 2 checkbox.
- **Deviation from plan:** clipper algorithm (above) and boolean-operand model (subpaths, above). The plan
  named files `Polygon/PathFlattener/PolygonClipper/FillRuleResolver/CurveRefit/PathBoolean/StrokeOutliner/
  PathOffset` — all delivered; added `PolygonClipper.selfUnion` and `PathFlattener.flattenCenterline` (not
  named in the plan but minimal). `VectorEditState` needed no transient-message field (no-ops just return
  state unchanged), so it was left untouched.

### Phase 3 — pixel-perfect icon production pipeline (2026-06-05)
- **Shipped:** four pure `data/vector/` modules (`KeylineGrid` + `KeylinePresets`, `VectorQuantizer`,
  `IconSizeSet`, `IconSetExporter`) and a pure `ui/components/notes/EditSnap` (`MASK_PIXEL` + quantizers),
  wired into the Phase 1 editor: `VectorEditState`/`Action`/`Reducer` gained keyline/pixel-snap/optical-adjust;
  `KeylineOverlay` draws on the canvas; `MultiSizePreviewStrip` + `IconExportPanel` + new screen chips/toggles;
  and a `.zip` icon-set export through `VectorTuneupExporter.exportIconSet` ⇄ `VectorTuneupViewModel.exportIconSet`
  ⇄ `NodeEditorHost`. **31 new JVM tests green (24 pure `data/vector`+`EditSnap` + 7 reducer); `*.edit.*` now 103;
  `:app:assembleDebug` clean.**
- **Key decisions:**
  1. **Quantize-on-export, not resample.** Editor geometry is already vector, so the production pass is a pure
     round-to-grid in the model (`VectorQuantizer.quantize`) + serialization through the *existing* writers — the
     `exported_vector_drawable_reimports_geometry_stable` test pins losslessness against the real parser.
  2. **Multi-size = pure uniform scale + manual optical hook.** `IconSizeSet.derive` scales viewport+coords+stroke
     from the master edge to each target and applies a per-size `OpticalAdjust.scale` about the centre; no automatic,
     shape-aware trimming (keeps the export deterministic). `deriveAll` feeds both the preview strip and the exporter.
  3. **`EditSnap` is a sibling, not a `Snap` edit.** A new `MASK_PIXEL=0x8` bit + unconditional quantizers, applied
     by the reducer as the *final* snap step (after the magnetic `Snap.*` steps), clamped into the artboard.
  4. **Export hoisted off the edit VM.** To keep `VectorEditViewModel` no-arg-injectable (Robolectric-free test), the
     `.zip` export is a callback → Tune-Up VM, mirroring how Phase 1f hoisted persistence.
  5. **`KeylineGrid` is pure geometry shared by overlay + (future) snapper**, expressed on a `24`-normalized edge so
     every figure scales by ratio; `forViewport` produces the 48/108 grids as clean multiples.
- **Verified:** 31 JVM tests — keyline rect/circle/corner/cross derivations + `forViewport` ×2; quantize
  integer-landing / idempotence / viewport-clamp / kind-preservation / unparsed-passthrough / device-pixel step;
  size derivation 24-identity/48-double/108-ratio/optical-about-centre/`deriveAll`; exporter count/geometry-stable
  re-import/integer-only-coords/VD≡SVG/batch-well-formed; `EditSnap` round/bounds/idempotent/step; reducer
  pixel-snap place+move+clamp, snap-bit toggle, keyline toggle, optical-adjust-without-touching-master.
  Compile + assemble clean.
- **Not yet verified:** on-device manual interaction (headless build env) — the one open Phase 3 checkbox.
- **Deviation from plan:** dropped the optional `data/notes/IconSetExportParityTest` contrast fixture — the
  `IconSetExporterTest.exported_vector_drawable_reimports_geometry_stable` test already proves the lossless path,
  and a parity test would needlessly couple to the Android-`Context`-bound lossy `NoteVectorDrawableExporter`.
  `KeylineGrid.shapeFigures()` returns a sealed `List<Figure>` instead of the plan's sketched `List<Any>` (cleaner,
  test-inspectable). Added `exportSet`'s optional `baseName` param + `VectorTuneupExporter.exportIconSet`'s zip
  packaging (the plan said "zip/dir") and a `"zip"` managed-extension; all minimal, none anticipated verbatim.

### Phase 4 — unify the two domains, **core** (2026-06-05)
- **Shipped (plan steps 1–4 + pure integration tests — the "bulk of the value"):** a pure
  `data/vector/notesbridge/` module — `PolylineSimplify` (the single notes RDP), `AutoShapeFitter`
  (line/circle/rect detection), `StrokeVectorizer` (freehand → centerline → Catmull-Rom cubic `EditablePath`),
  `ShapeVectorizer` (`Shape` → exact kappa-arc commands → `EditablePath`), `NoteVectorBridge`
  (`toDocument` + single-item entries, `WidthMode`, `NoteVectorResult`, `ColorHex`), and `EditOpToManualEdit`
  (geometry-free notes `EditOp` → `VectorManualEdit`). `EditPreviewController.simplifyStroke` refactored to
  delegate to `PolylineSimplify`. **25 new pure-JVM tests green** (`StrokeVectorizerTest` 5, `ShapeVectorizerTest`
  5, `AutoShapeFitterTest` 4, `NoteVectorBridgeTest` 3, `EditOpToManualEditTest` 4, `UnifiedModelIntegrationTest`
  4); full suite green apart from the known ~21 `data/notes` Color/Log "not mocked" failures;
  `compileDebugUnitTestKotlin` clean.
- **Key decisions:**
  1. **Canonical model = editable `VectorDocument`; bridge is one-way (ink → document)** at the commit boundary
     (document → ink is left to round-trip tests, never mutates persisted strokes). Matches the plan's decision record.
  2. **Variable width = centerline cubic + uniform stroke** (`max(0.5, base×meanPressure)`, round cap/join), which
     is what the legacy exporters already did — moved into the canonical model so it's uniform/lossless *from the
     document's view*. `WidthMode.OUTLINE_FILL` is a deferred enum seam.
  3. **One RDP.** Extracted the notes RDP into `PolylineSimplify` (exact numerics preserved) and pointed
     `EditPreviewController` at it; left the optimizer's `VectorPathSimplifier` untouched (different init, widely used).
  4. **Auto-shape is conservative + opt-out** (`autoShape=false`) so a deliberate squiggle is never snapped.
  5. **Losslessness proved at the document level** (bridge → `AndroidVectorDrawableWriter` → re-parse, byte-identical
     `pathData`) rather than through the Android-coupled exporters — deliberately, since those can't run headless.
- **Verified:** 25 JVM tests — samples→cubic resample-within-tolerance, straight→single LineTo, width/color/cap
  mapping, interior-SMOOTH/endpoint-CORNER; rect/ellipse/polygon/line/arrow exact commands + reparse; auto-shape
  wobbly-circle/near-rect/near-line positives + squiggle negative; bridge z-order/skip/viewport; EditOp→ManualEdit
  recolor/restyle/delete/simplify + geometry-op skip; and the four end-to-end integrations (lossless write/parse,
  node-edit stability, AI edit-plan on a bridged doc validates clean, notes-recolor-via-bridge == manual edit).
- **Not yet done (deferred to an on-device session — Android-coupled, headless-unverifiable):** (a) rewiring the
  live `NoteSvgExporter`/`NoteVectorDrawableExporter` to route through the bridge + document writers; (b) the UI
  "open a note in the vector editor" entry hook. Both are tracked as open checkboxes in §2.
- **Deviation from plan:** the plan named `PolylineSimplify`/`AutoShapeFitter`/`StrokeVectorizer`/`ShapeVectorizer`/
  `NoteVectorBridge`/`EditOpToManualEdit` — all delivered. `AutoShapeFitter` returns a `ui.components.notes.Shape`
  (pure value type) directly rather than an internal type. The exporter rewire + entry wiring (plan steps 3 & 5's
  UI half) were intentionally scoped out of this headless session, leaving Phase 4 **core-complete**.
