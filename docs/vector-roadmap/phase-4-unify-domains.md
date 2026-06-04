# Phase 4 Implementation Plan — Unify the Two Vector Domains

## Context

The app currently carries **two disconnected vector worlds** that never share a
model:

- **(A) The notes drawing canvas** (`ui/components/notes/`, `data/model/`,
  `data/notes/`) — freehand stylus ink. A `NoteItem`
  (`data/model/NoteItem.kt`, `kind = stroke|shape|text|image`) stores strokes as
  pressure/tilt sample polylines packed by
  `ui/components/notes/StrokeCodec.kt` and shapes via
  `ui/components/notes/ShapeCodec.kt`. Rendering is pixel-centric to a bitmap
  (`ui/components/notes/DrawingSurface.kt`). The AI edit pipeline lives in
  `data/notes/` (`EditProtocol.kt` `sealed EditOp`, `EditOpsParser.kt`) and is
  simulated by `ui/screens/notes/EditPreviewController.kt`, which already
  contains a local **RDP simplify**, **Chaikin smooth**, and **auto-shape
  replacement** path. Export is **lossy**: `data/notes/NoteSvgExporter.kt` and
  `data/notes/NoteVectorDrawableExporter.kt` flatten pressure-varying strokes to
  a single mean width.

- **(B) The Vector Tune-Up workspace** (`data/vector/`, `ui/screens/vector/`) —
  an **import-only** pipeline over the immutable
  `data/vector/VectorDocument.kt` (`VectorDocument(viewport, root, …)` /
  `VectorPath(commands: List<PathCommand>?)` / `VectorStyle`). It parses
  (`AndroidVectorDrawableParser`, `VectorSvgParser`), writes
  (`AndroidVectorDrawableWriter`, `VectorSvgWriter`), optimizes
  (`VectorDrawableOptimizer`, `VectorPathSimplifier`, `VectorPathSampler`), and
  AI-edits (`VectorTuneupAiService` → `VectorEditPlanApplier`;
  `VectorRedrawAiService` → `VectorSceneCompiler`) under a pure reducer
  (`VectorTuneupReducer` + `VectorTuneupState` + `VectorTuneupViewModel`) with
  version history and a safe preview (`VectorPreviewModel` +
  `ui/screens/vector/VectorPreviewCanvas.kt` + `VectorPreviewPathNormalizer`).

**Phase 1** (in flight) builds the missing bridge: `data/vector/edit/`
(`EditablePath`/`EditAnchor`/`EditSubpath`, `EditablePathFactory`,
`EditablePathSerializer`) plus a node editor in `ui/screens/vector/edit/`. The
editable model is **all-cubic, absolute**, derived through
`VectorPreviewPathNormalizer`. Phases 2 (boolean ops) and 3 (grids / artboards /
lossless export) also operate on this editable model.

**Phase 4 finishes the unification.** It makes the notes canvas and the Tune-Up
workspace **two views of one editable `VectorDocument`**, vectorizes committed
freehand ink into `EditablePath`s on demand, routes every AI feature at the
shared model, and replaces the average-width stroke flatten with true lossless
vector export. The canonical model going forward is the **editable
`VectorDocument`**; notes strokes/shapes become an *input method* that
vectorizes into it.

---

## Scope

**In:**
- A pure, JVM-testable **`NoteItem` ↔ `EditablePath`/`VectorDocument` bridge**
  (`data/vector/notesbridge/`).
- **Vectorize freehand on demand**: committed `NoteItem` strokes → polyline →
  cubic `EditablePath`; `NoteItem` shapes → exact `PathCommand`s; color/width →
  `VectorStyle`. Reuse the RDP/curve-fit/auto-shape logic that already exists in
  `ui/screens/notes/EditPreviewController.kt` and the document-side simplifier
  `data/vector/VectorPathSimplifier.kt`.
- Import (SVG / VectorDrawable via the existing parsers) **drops straight onto
  the editable node canvas** — i.e. a Tune-Up `VectorDocument` is editable in
  both surfaces.
- **Route AI to the unified model**: notes `EditOp` ops and Tune-Up
  edit-plans / semantic redraw all resolve to edits against the shared editable
  `VectorDocument`.
- **Lossless export everywhere**: notes export goes through the document writers
  (`AndroidVectorDrawableWriter` / `VectorSvgWriter`) instead of the lossy
  flatten.
- A written decision record naming the **canonical model** and the chosen
  **variable-width approximation**.

**Out:**
- Live two-way binding / real-time collaboration between the two surfaces (the
  bridge converts at well-defined commit points, not continuously).
- Perfect preservation of pressure-varying stroke width as a single filled
  outline. We document and implement the chosen approximation (below) and treat
  full outline-fill conversion as a later enhancement.
- New persisted Room schema for the unified model (we reuse the existing
  `VectorDocument` serialization + the notes `NoteItem` table; the bridge is a
  pure transform, not a migration).

### Decision record (documented here, enforced in code)

1. **Canonical model = editable `VectorDocument`.** The notes `NoteItem` table
   stays the on-disk representation of *ink as captured*, but any structured /
   AI / export operation runs on the `VectorDocument` produced by the bridge.
   The bridge is **one-way at the boundary by default** (ink → document); going
   back (document → ink) is supported only for the round-trip tests and is
   explicitly lossy (cubic anchors → resampled polyline), never used to mutate
   persisted strokes.
2. **Variable-width approximation = centerline cubic + uniform stroke.** A
   freehand stroke vectorizes to a single cubic `EditablePath` along its
   centerline, with `VectorStyle.strokeWidth` set to the stroke's
   width-weighted mean (`NoteItem.baseWidthPx` scaled by mean sample pressure)
   and `strokeLineCap = "round"`, `strokeLineJoin = "round"`. This is simpler,
   round-trips cleanly, and matches how `NoteSvgExporter` already collapses
   width today — we just move the collapse into the shared model so it is
   *uniform and lossless from the document's point of view*. (Outline-fill
   conversion — tracing both stroke edges into a filled path — is deliberately
   deferred; the bridge exposes a `WidthMode` enum so it can be added later
   without touching callers.)

---

## The bridge model (new — `data/vector/notesbridge/`)

The bridge is pure Kotlin (no Android/Compose imports) so the entire
vectorization algebra is unit-tested without a device, exactly like
`VectorPreviewPathNormalizer` and `VectorPathSimplifier`. It produces the Phase-1
`EditablePath` directly, so node-editing / boolean ops / export all come for
free.

```
data/vector/notesbridge/NoteVectorBridge.kt
    enum class WidthMode { CENTERLINE_UNIFORM, OUTLINE_FILL /* deferred */ }

    data class NoteVectorResult(
        val document: VectorDocument,
        val itemToPathId: Map<String, String>,   // NoteItem.id -> VectorPath.id
        val skipped: List<String>,                // text/image kinds, empty strokes
    )

    object NoteVectorBridge {
        // Vectorize a whole note's committed items into ONE document, preserving
        // z-order as document tree order (root children, painter's order).
        fun toDocument(
            items: List<NoteItem>,
            viewport: VectorViewport,             // from note canvas size
            widthMode: WidthMode = WidthMode.CENTERLINE_UNIFORM,
        ): NoteVectorResult

        // Single-item entry points (reused by AI routing + tests).
        fun strokeToEditablePath(item: NoteItem, widthMode: WidthMode): EditablePath?
        fun shapeToEditablePath(item: NoteItem): EditablePath?
    }
```

### Strokes → cubic `EditablePath`

`data/vector/notesbridge/StrokeVectorizer.kt`

1. `StrokeCodec.decode(item.payload)` → `FloatArray` of `[x,y,p,tilt, …]` (stride
   `StrokeCodec.FLOATS_PER_SAMPLE`). v2 payloads decode to the same v1 shape, so
   timestamps are ignored here.
2. **Simplify** the centerline. Reuse the RDP already proven in
   `EditPreviewController.simplifyStroke` by extracting its pure inner `rdp` /
   `perpDistance` into a shared `internal` helper that operates on
   `List<VectorPoint>` (the type `VectorPathSimplifier.simplify` already takes),
   so notes and Tune-Up share *one* RDP. Drop p/tilt into the centerline
   collapse; keep mean pressure for width.
3. **Auto-shape fit** (optional, reusing the notes auto-shape intent): when the
   simplified polyline closely matches a line / rect / circle, emit the exact
   shape path instead of fitting curves. The fitting thresholds live in
   `EditPreviewController` today only implicitly (via `EditOp.ReplaceWithShape`
   built by the model); Phase 4 lifts the *geometric* line/rect/circle test into
   a pure `AutoShapeFitter.kt` so both the bridge and a future "clean up" button
   call the same code.
4. **Curve-fit** the remaining polyline to cubic anchors. Each kept polyline
   vertex becomes an `EditAnchor`; tangent handles are derived from neighboring
   vertices (Catmull-Rom → Bézier, `out = p + (next - prev)/6`, mirror for
   `in`), giving `AnchorType.SMOOTH` interior anchors and `CORNER` endpoints.
   This yields a single open `EditSubpath`.
5. Build `EditablePath(pathId = "note_${item.id}", subpaths = [sub], style)`
   where `style` carries `strokeColor = #AARRGGBB(item.colorArgb)`,
   `strokeWidth = effectiveWidth(item)`, round cap/join.

### Shapes → exact `PathCommand`s

`data/vector/notesbridge/ShapeVectorizer.kt`

- `ShapeCodec.decode(item.payload)` → `Shape` + `fillArgb`.
- Map each `Shape` variant to **exact** path geometry (no sampling):
  - `Shape.Line` → `MoveTo` + `LineTo`.
  - `Shape.Rect` → 4 `LineTo` + `Close` (corner radius → 4 `CubicTo` quarter-arc
    approximations using the standard `0.5523` kappa constant when
    `cornerRadius > 0`).
  - `Shape.Ellipse` → 4 `CubicTo` quarter-ellipse arcs about `(cx,cy)` with radii
    `(rx,ry)`; bake `rotationRad` into the control points.
  - `Shape.Arrow` → shaft `LineTo` + two head `LineTo`s.
  - `Shape.Polygon` → `MoveTo` + `LineTo*` (+ `Close` when `shape.closed`).
- Wrap commands in a `VectorPath`, run them through `EditablePathFactory`
  (Phase 1) so the result is the same all-cubic `EditablePath` shape as strokes
  — keeping a single editable type across the canvas. `fillArgb != 0` → set
  `VectorStyle.fillColor`; the `NoteItem` color/width → stroke style.

### Document assembly

`NoteVectorBridge.toDocument` builds a `VectorDocument` whose `root:
VectorGroup` children are the per-item `VectorPath`s in `zIndex` order, each
serialized via `EditablePathSerializer` (Phase 1) so `pathData` + `commands` are
both populated. `text`/`image` kinds are recorded in `skipped` (Phase 4 does not
vectorize glyphs/rasters). Viewport comes from the note's canvas extents.

---

## Routing AI to the unified model

Both AI worlds already produce **structured, validated edit operations against a
list of paths/items** — Phase 4 makes both resolve through the bridge so they
hit one document.

- **Notes `EditOp`** (`data/notes/EditProtocol.kt`, simulated by
  `EditPreviewController.simulate`): no behavior change for users, but the
  *transform / recolor / restyle / simplify / smooth / replace_with_shape* ops
  become expressible on the bridged document too. Add a thin
  `data/vector/notesbridge/EditOpToManualEdit.kt` that maps the geometry-free ops
  (`Recolor`, `Restyle`, `Delete`, `Simplify`) onto the existing
  `VectorManualEdit` + `VectorManualEditApplier` (`data/vector/`) so the same
  op can run on a `VectorDocument`. Geometry ops (`Transform`,
  `ReplaceWithShape`, `Smooth`) stay on the ink path *or* re-vectorize then apply
  — covered by tests, not by duplicating math.
- **Tune-Up edit-plans** (`VectorTuneupAiService` → `VectorEditPlanParser` →
  `VectorEditPlanApplier.apply(document, plan)`): already operate on
  `VectorDocument` — they now also apply to a document that *originated from
  ink*, so "tidy up my sketch" works end-to-end.
- **Semantic redraw** (`VectorRedrawAiService` → `VectorSceneParser` →
  `VectorSceneCompiler.compile(scene)` → `VectorDocument`): unchanged; its output
  document is already canonical and editable.

No new AI prompt formats. The win is that `VectorCanvasJson.serialize` (the
notes-side model input) and the Tune-Up summary both describe the *same*
underlying document once the bridge runs.

---

## Lossless export

Replace the average-width flatten in `NoteSvgExporter` /
`NoteVectorDrawableExporter` with a single code path:

1. `NoteVectorBridge.toDocument(items, viewport)` → `VectorDocument`.
2. `AndroidVectorDrawableWriter.write(document)` / `VectorSvgWriter.write(document)`
   (the Tune-Up writers) → bytes.

The strokes now export as real cubic paths with a uniform stroke width
(`CENTERLINE_UNIFORM`) — lossless *with respect to the canonical document*,
and visually equivalent to today's mean-width output but driven by one writer.
`text`/`image` items keep their existing dedicated emitters (the writers gain a
small "passthrough extras" hook, or the exporter composes document output with
the existing `<text>`/`<image>` emitters — confirm during build; prefer
composing to avoid touching the Tune-Up writers).

---

## Reuse, don't rebuild

| Need | Existing code reused |
| --- | --- |
| Decode stroke samples | `ui/components/notes/StrokeCodec.kt` (`decode`, `FLOATS_PER_SAMPLE`) |
| Decode shapes | `ui/components/notes/ShapeCodec.kt` (`decode` → `Shape` + `fillArgb`) |
| Shape geometry types | `ui/components/notes/Shape.kt` (`Line/Rect/Ellipse/Arrow/Polygon`) |
| RDP simplify | `ui/screens/notes/EditPreviewController.kt` (`rdp`/`perpDistance`) + `data/vector/VectorPathSimplifier.kt` (`simplify`) — unified into one helper |
| Polyline→pathData helper | `data/vector/VectorPathSimplifier.kt` `SimplifiedPathBuilder.buildPolylinePath` |
| Commands → editable cubics | Phase 1 `data/vector/edit/EditablePathFactory.kt` (via `VectorPreviewPathNormalizer`) |
| Editable → `pathData`/`commands` | Phase 1 `data/vector/edit/EditablePathSerializer.kt` (via `data/vector/PathDataFormatter.kt`) |
| Document tree helpers | `data/vector/VectorDocument.kt` (`allPaths`, Phase-1 `replacePath`) |
| AI ops on a document | `data/vector/VectorManualEditApplier.kt`, `data/vector/VectorEditPlanApplier.kt`, `data/vector/VectorSceneCompiler.kt` |
| Lossless writers | `data/vector/AndroidVectorDrawableWriter.kt`, `data/vector/VectorSvgWriter.kt` |
| Node editing / boolean / grids | Phase 1–3 editor in `ui/screens/vector/edit/` |
| Notes model input for AI | `data/notes/VectorCanvasJson.kt` |

---

## File-by-file work list

**New (`data/vector/notesbridge/`) — pure, JVM-testable**
- `NoteVectorBridge.kt` — `toDocument` + single-item entry points; `WidthMode`,
  `NoteVectorResult`.
- `StrokeVectorizer.kt` — samples → simplified centerline → cubic `EditablePath`;
  width/color → `VectorStyle`.
- `ShapeVectorizer.kt` — `Shape` → exact `PathCommand`s (line/rect/ellipse/
  arrow/polygon, kappa arcs).
- `AutoShapeFitter.kt` — pure line/rect/circle detection over a polyline (lifts
  the geometric intent currently implicit behind `EditOp.ReplaceWithShape`).
- `EditOpToManualEdit.kt` — maps geometry-free notes `EditOp`s onto
  `VectorManualEdit`.

**Modified**
- `ui/screens/notes/EditPreviewController.kt` — extract `rdp`/`perpDistance` into
  a shared `internal` helper (e.g. `data/vector/notesbridge/PolylineSimplify.kt`)
  so notes + bridge share one RDP; behavior identical.
- `data/notes/NoteSvgExporter.kt` — route through `NoteVectorBridge` +
  `VectorSvgWriter`; drop the mean-width flatten comment/path.
- `data/notes/NoteVectorDrawableExporter.kt` — route through `NoteVectorBridge` +
  `AndroidVectorDrawableWriter`.
- `ui/screens/vector/VectorTuneupScreen.kt` — accept a bridged document as an
  import source so a note opens directly in the node editor (one nav/entry hook;
  confirm placement during build).

**Reused unchanged:** `StrokeCodec`, `ShapeCodec`, `Shape`,
`VectorPreviewPathNormalizer`, `PathDataFormatter`, `VectorManualEditApplier`,
`VectorEditPlanApplier`, `VectorSceneCompiler`, the writers, and the Phase-1
`EditablePath*` types.

---

## Test plan (pure JVM — the bulk of the value lands here)

`app/src/test/java/com/aichat/sandbox/data/vector/notesbridge/`

- `StrokeVectorizerTest`
  - `strokeSamplesRoundTripToCubicWithinTolerance` — a known sampled polyline
    (e.g. a sine arc) → cubic `EditablePath` → re-sample (`VectorPathSampler`)
    deviates from the source centerline by ≤ tolerance at every sample.
  - `straightStrokeStaysStraight` — collinear samples collapse to a single
    `LineTo`-style anchor pair (no spurious curvature).
  - `widthMapsToUniformStroke` — `VectorStyle.strokeWidth` equals the
    width-weighted mean; cap/join are round; color is `#AARRGGBB(colorArgb)`.
- `ShapeVectorizerTest`
  - `rectEmitsExactPathAndReparses` — `Shape.Rect` → commands → `PathDataParser`
    re-parses to the same 4-line closed path; corners exact.
  - `ellipseEmitsFourCubicArcsReparses` — `Shape.Ellipse` → 4 `CubicTo` → re-parse
    → re-render bounds match `(cx±rx, cy±ry)` within tolerance.
  - `polygonClosedFlagPreserved`, `lineAndArrowExact`.
- `AutoShapeFitterTest`
  - `wobblyCircleDetectedAsCircle`, `nearAxisRectDetectedAsRect`,
    `nearStraightDetectedAsLine`, `genericSquiggleNotFitted` — reuses the
    auto-shape intent so a hand-drawn circle becomes an exact ellipse.
- `NoteVectorBridgeTest`
  - `zOrderPreservedAsTreeOrder` — items map to root children in `zIndex` order;
    `itemToPathId` is complete; `text`/`image` land in `skipped`.
  - `emptyAndDegenerateStrokesSkippedNotCrash`.

`app/src/test/java/com/aichat/sandbox/data/vector/` (integration-style, pure)

- `UnifiedModelIntegrationTest`
  - `iconImportedEditedBooleanCombinedExportedLosslessly` — parse a fixture
    VectorDrawable (`AndroidVectorDrawableParser`) → `EditablePathFactory` →
    apply a Phase-1 node edit + a Phase-2 boolean union → `EditablePathSerializer`
    → `AndroidVectorDrawableWriter` → re-parse → geometry stable within
    tolerance. (Skips/elevates gracefully if Phase 2 boolean op isn't merged
    yet — assert the node-edit + lossless export portion unconditionally.)
  - `aiEditPlanAppliedToBridgedDocumentProducesValidDocument` — vectorize a
    sketch via `NoteVectorBridge`, apply a `VectorEditPlan` through
    `VectorEditPlanApplier.apply`, assert the result passes
    `VectorDocumentValidator` with no error-level warnings.
  - `noteEditOpRecolorViaBridgeMatchesManualEdit` — `EditOp.Recolor` →
    `EditOpToManualEdit` → `VectorManualEditApplier` recolors the right path.

`app/src/test/java/com/aichat/sandbox/data/notes/`

- Extend `NoteSvgExporterTest` / `NoteVectorDrawableExporterTest` with a pure
  `bridgeProducesLosslessPathsForStrokeAndShape` assertion on the *document*
  (not the bitmap), so it runs without the Android `Color`/`Log` framework
  stubs that make the existing on-device export tests fail.

Run:
`./gradlew :app:testDebugUnitTest --tests "com.aichat.sandbox.data.vector.notesbridge.*" --tests "com.aichat.sandbox.data.vector.UnifiedModelIntegrationTest" --console=plain`.
(The known-preexisting Android-`Color`/`Log` "not mocked" failures in
`NoteSvg/VectorDrawable/AiService` tests are unrelated; the suite is green if
those are the only failures.)

**On-device manual verification:** draw a wobbly circle + a freehand squiggle in
a note → "Open in vector editor" → confirm the circle snapped to an exact
ellipse and the squiggle became an editable cubic path → grab an anchor and move
it → export to VectorDrawable + SVG and re-import both → geometry identical;
stroke width is uniform and round-capped.

---

## Sequencing within the phase

1. **Bridge core, no UI** (`StrokeVectorizer`, `ShapeVectorizer`,
   `AutoShapeFitter`, shared `PolylineSimplify`) + their unit tests — de-risks
   the vectorization math first.
2. **`NoteVectorBridge.toDocument` assembly** + `NoteVectorBridgeTest`
   (z-order, skip handling).
3. **Lossless export** rewire (`NoteSvgExporter`, `NoteVectorDrawableExporter`)
   + document-level export assertions.
4. **AI routing** (`EditOpToManualEdit`, integration tests against
   `VectorEditPlanApplier` / `VectorManualEditApplier`).
5. **Entry wiring**: a note opens onto the Phase-1 node canvas via the bridged
   document; integration round-trip test (import → edit → boolean → export).

Steps 1–4 are fully testable on the JVM and carry the bulk of the value; step 5
depends on the Phase-1 editor (and, for the boolean assertion, Phase 2) being
present.

## Risks

- **Vectorization fidelity** (samples → cubic within tolerance) is the main
  correctness risk → mitigated by the round-trip + resample tolerance tests
  landing first (step 1).
- **Auto-shape over-fitting** (snapping a deliberate squiggle to a circle) →
  conservative thresholds plus the `genericSquiggleNotFitted` negative test;
  fitting is opt-out via `WidthMode`/a flag so the raw curve path is always
  available.
- **Width approximation surprises** — collapsing pressure to one width is
  documented and matches today's exporter behavior; the `WidthMode.OUTLINE_FILL`
  seam keeps the door open without reworking callers.
- **Double-RDP drift** — extracting one shared simplify helper risks subtly
  changing notes' existing simplify output; pin it with a characterization test
  over `EditPreviewController.simplifyStroke` before the refactor.
- **Export composition for text/image** — keep those on their existing emitters
  (compose, don't fold into the Tune-Up writers) to avoid regressing
  glyph/raster export while unifying the vector path.
