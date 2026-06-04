# Phase 5 Implementation Plan — Production Polish

## Context

Phases 1–4 of the vector roadmap turn the read-only `VectorDocument`
(`data/vector/VectorDocument.kt`) into a real editor: an all-cubic editable model
(`data/vector/edit/`), a node editor (`ui/screens/vector/edit/`), boolean ops,
pixel-perfect grids/artboards/lossless export, and a unification that folds the
notes canvas and the Vector Tune-Up workspace onto the **same** editable
`VectorDocument`. Phase 5 is post-core polish on that unified editor. Every item
below is **independent** — each is a self-contained sub-feature with its own
files and pure-JVM tests, and none blocks another. Pick any subset.

The unifying constraint stays the same as the earlier phases: the parsed model
(`VectorDocument` / `PathCommand` / `VectorStyle`) is the immutable source of
truth, the writers (`AndroidVectorDrawableWriter`, `VectorSvgWriter`) are the
only serializers, and `VectorPreviewCanvas` is the only place typed geometry
becomes Compose draw calls. Phase 5 extends each of those three seams rather than
forking a parallel stack — and it deliberately keeps the *parse → model → format*
round-trip property the existing writer tests already assert.

**Scope of this phase (in):** five orthogonal sub-features —
1. **Stroke styling** (caps/joins UI, dash patterns, variable-width profiles);
2. **Gradients / advanced fills** (linear/radial/sweep fill model end-to-end);
3. **Reusable vector symbols** (master/instance, replacing raster stamps);
4. **Keyboard / ergonomics** (nudge, shortcuts, numeric transform entry);
5. **AI auto-trace** (bitmap → editable cubic paths).

**Out of scope (future):** real-time collaborative editing; full SVG
filter/mask/blend-mode/clip-mask support (we still drop+warn those — see
`SVG_STYLE_UNSUPPORTED` / `CLIP_PATH_NOT_SUPPORTED`). Those are called out where
relevant as "future".

A note on warning codes: gradients are currently **dropped and warned**
(`VectorWarning.Codes.GRADIENT_NOT_SUPPORTED` for Android XML,
`SVG_GRADIENT_UNSUPPORTED` for SVG — see `VectorDocument.kt:116,169`). Sub-feature
2 lifts that; the codes are retro-purposed to fire only when a gradient genuinely
can't be represented in the target format.

---

## Sub-feature 1 — Stroke styling (caps/joins, dashes, variable width)

### 1a. Expose caps/joins in the editor UI (no model change)
`strokeLineCap` / `strokeLineJoin` already live on `VectorStyle`
(`VectorDocument.kt:90-91`), are already written by both writers
(`AndroidVectorDrawableWriter.kt:80-81`, `VectorSvgWriter.kt:130-131`), and are
already mapped to Compose in `toStrokeCap` / `toStrokeJoin`
(`VectorPreviewCanvas.kt:228-239`). The only gap is editor UI — add cap/join
segmented controls to the existing per-path style panel
(`ui/screens/vector/VectorPathEditPanel.kt`), dispatching the existing manual-edit
path (`data/vector/VectorManualEdit.kt` / `VectorManualEditApplier.kt`). **No new
model, writer, or preview code.**

### 1b. Dash patterns
Extend the model with a dash array + offset; teach SVG to emit native
`stroke-dasharray`; for Android VectorDrawable (which has **no native dash
attribute**) bake the dash into path geometry on export.

```
// VectorDocument.kt — additive, nullable so every existing construction compiles
data class VectorStyle(
    /* …existing… */
    val strokeDashArray: List<Float>? = null,   // viewport-unit on/off lengths
    val strokeDashOffset: Float? = null,        // phase
)
```

New pure geometry baker (no Android imports):

```
data/vector/StrokeDashBaker.kt
object StrokeDashBaker {
    // Walks the flattened polyline (reuse VectorPathSampler) and cuts it into
    // the "on" sub-segments dictated by the dash pattern + offset, returning a
    // list of open subpaths to emit as M/L runs.
    fun bake(commands: List<PathCommand>, dash: List<Float>, offset: Float): List<List<PathCommand>>
}
```

- **SVG** (`VectorSvgWriter.writePath`): when `strokeDashArray != null`, emit
  `stroke-dasharray="a,b,…"` and `stroke-dashoffset` instead of baking — SVG is
  lossless here. Reuse `num()` (`VectorSvgWriter.kt:231`).
- **Android XML** (`AndroidVectorDrawableWriter`): VectorDrawable has no dash
  attribute, so when a stroked path has a dash array, replace the single stroked
  `<path>` with the **baked dash geometry** drawn as a *filled* outline isn't
  needed — the baked dash runs are still stroked, just chopped. Emit one `<path>`
  per "on" run sharing the original stroke style (width/cap/join/color), via
  `StrokeDashBaker.bake(...)` → `PathDataFormatter.format(...)`. Warn once with a
  new code `STROKE_DASH_BAKED` so the user knows the XML path count grew.
- **Preview** (`VectorPreviewCanvas`): map to a Compose `PathEffect.dashPathEffect`
  on the `Stroke` when `strokeDashArray` is present (extend `PreparedPreviewPath`
  + `drawPreparedPath`). This is preview-only and never touches the model.

### 1c. Variable-width stroke profiles (outline-to-fill baking)
A width-along-the-path profile can't be represented by any single stroke
attribute in either target format, so the model carries the profile and **export
bakes it to a filled outline**.

```
data/vector/VariableWidthProfile.kt
data class WidthStop(val t: Float, val width: Float)   // t in [0,1] along the path
data class VariableWidthProfile(val stops: List<WidthStop>)   // attached on VectorStyle (nullable)

data/vector/StrokeOutliner.kt
object StrokeOutliner {
    // Offsets the flattened centerline left/right by width(t)/2 (linear-interp
    // the profile), returning a single closed VectorPath whose fill = original
    // stroke color. Caps honored per strokeLineCap. Pure; reuses VectorPathSampler.
    fun outline(commands: List<PathCommand>, profile: VariableWidthProfile,
                baseWidth: Float, cap: String?): List<PathCommand>
}
```

- On export (**both** writers, and the preview build for fidelity), a path with a
  non-null `variableWidth` is replaced by its outlined fill path before
  serialization. A constant profile (all stops equal) must produce a shape
  visually equal (within tolerance) to a plain stroked path — that's the key test
  invariant.
- Preview can either draw the outlined fill (exact) or approximate; we draw the
  outlined fill so on-screen == exported.

**Reuses:** `VectorStyle` (additive fields), `VectorPathSampler`/`VectorPoint`
(flattening), `PathDataFormatter` (re-serialize baked runs), `num()` in both
writers, `toStrokeCap`/`toStrokeJoin` (preview), `VectorManualEdit` dispatch (UI).

---

## Sub-feature 2 — Gradients / advanced fills

Today gradients are parsed-but-dropped with a warning. Add a real fill model and
carry it all the way through writers + preview.

```
data/vector/VectorFill.kt
sealed interface VectorFill {
    data class Solid(val color: String, val alpha: Float? = null) : VectorFill
    data class Linear(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                      val stops: List<GradientStop>, val tileMode: String? = null) : VectorFill
    data class Radial(val cx: Float, val cy: Float, val radius: Float,
                      val stops: List<GradientStop>, val tileMode: String? = null) : VectorFill
    data class Sweep(val cx: Float, val cy: Float, val stops: List<GradientStop>) : VectorFill
}
data class GradientStop(val offset: Float, val color: String)   // offset 0..1, #AARRGGBB
```

Wiring: add `val fill: VectorFill? = null` to `VectorStyle` (additive). When
`fill` is null, the existing `fillColor`/`fillAlpha` scalar path is unchanged —
so every current document and every existing writer/preview test keeps passing.
A non-null `fill` overrides the scalar fill.

- **Android XML** (`AndroidVectorDrawableWriter.writePath`): VectorDrawable
  expresses gradients via `aapt:attr` — emit
  `<aapt:attr name="android:fillColor"><gradient android:type="linear|radial|sweep"
  …><item android:offset android:color/></gradient></aapt:attr>` as a nested child
  of `<path>` (so the writer must learn to emit a `<path>…</path>` block, not just
  a self-closing tag, when `fill != null`). Add the `xmlns:aapt` declaration to the
  `<vector>` header when any path uses a gradient. Only fire
  `GRADIENT_NOT_SUPPORTED` now for the genuinely-unrepresentable case (e.g. a
  per-stop alpha pattern Android can't model).
- **SVG** (`VectorSvgWriter`): collect every gradient into a `<defs>` block
  (`<linearGradient>`/`<radialGradient>`; sweep has no SVG primitive → warn
  `SVG_GRADIENT_UNSUPPORTED` and fall back to the first stop color), assign stable
  ids (`grad0`, `grad1`, …), and reference them with `fill="url(#grad0)"`. Reuse
  the deterministic-output discipline already in the writer (fixed attribute
  order). `resolveColor` (`VectorSvgWriter.kt:186`) handles each stop.
- **Preview** (`VectorPreviewCanvas`): this is where the "gradients intentionally
  absent" comment (`VectorPreviewModel.kt:41-42`,
  `VectorPreviewCanvas.kt` fill path) gets lifted. Map `VectorFill` to a Compose
  `Brush` (`Brush.linearGradient` / `radialGradient` / `sweepGradient`) and draw
  `drawPath(brush = …)` instead of `drawPath(color = …)`. Extend
  `PreparedPreviewPath` with an optional `fillBrush: Brush?` and resolve stops via
  the existing `parseVectorColor` (`VectorPreviewCanvas.kt:197`). `VectorPreviewStyle`
  (`VectorPreviewModel.kt:42`) gains a nullable `fill: VectorFill?` carried through
  `VectorPreviewBuilder`.
- **Parsers** (`AndroidVectorDrawableParser`, `VectorSvgParser`): populate
  `VectorFill` instead of dropping — the round-trip parse↔write tests are the
  acceptance gate.

**Reuses:** `VectorStyle` (additive), `parseVectorColor` (preview stop colors),
`resolveColor` + `num()` (SVG writer), `PathDataFormatter` unaffected, existing
warning codes (`GRADIENT_NOT_SUPPORTED`, `SVG_GRADIENT_UNSUPPORTED`) re-scoped.

---

## Sub-feature 3 — Reusable vector symbols (master/instance)

Replace today's **raster** stamps (`data/model/Stamp.kt`,
`data/notes/StampPayloadCodec.kt`, `ui/components/notes/StampDrawer.kt` — a baked
`VectorCanvasJson`/PNG payload) with true vector symbols: define a master once,
instance it many times, and edits to the master propagate to every instance.

```
data/vector/symbol/VectorSymbol.kt
data class VectorSymbol(
    val id: String,                 // stable library id
    val name: String,
    val viewport: VectorViewport,   // the symbol's own coordinate box
    val root: VectorGroup,          // reuse the existing tree — a symbol IS a mini-document
)

// An instance lives inside a host VectorDocument as a new node kind.
data/vector/symbol/SymbolInstance.kt
data class SymbolInstance(
    val id: String,
    val symbolId: String,
    val translateX: Float, val translateY: Float,
    val scaleX: Float = 1f, val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val styleOverride: VectorStyle? = null,   // optional per-instance recolor
)
```

`VectorNode` (`VectorDocument.kt:56`) gains a third variant
`data class InstanceNode(val instance: SymbolInstance) : VectorNode`. To keep
**every existing consumer working unchanged**, instances are *expanded* to plain
groups+paths by a pure resolver before metrics/optimize/preview/export:

```
data/vector/symbol/SymbolResolver.kt
object SymbolResolver {
    // Replaces each InstanceNode with a GroupNode whose transform = the
    // instance transform and whose children = the symbol's root children
    // (ids namespaced "${instance.id}/${childId}" so they stay unique).
    // styleOverride is folded onto each leaf path's style.
    fun expand(doc: VectorDocument, library: Map<String, VectorSymbol>): VectorDocument
}
```

- The editor/preview/writers operate on the **expanded** document, so symbols cost
  zero new writer/preview code. Persistence stores the unexpanded document + the
  library; export expands first.
- "Edits to master propagate" falls out for free: change the `VectorSymbol`, re-run
  `expand`, every instance updates.
- A small library store + Hilt-injected DAO mirrors the existing Stamp persistence
  shape (`Stamp` is a Room `@Entity`); symbols are app-scoped like stamps. The old
  raster Stamp pipeline stays for backward compatibility but the editor's "insert
  reusable object" affordance now points at the symbol library.

**Reuses:** `VectorGroup` transform semantics (already understood by both writers
and `transformOf` in `VectorSvgWriter.kt:142`), `VectorNode`/`VectorDocument` tree,
`allPaths()`/`allGroups()` (`VectorDocument.kt:231,246`), the stamp library's
app-scoped Room pattern (`data/model/Stamp.kt`).

---

## Sub-feature 4 — Keyboard / ergonomics for the node editor

Pure-logic ergonomics for the Phase 1 node editor (`ui/screens/vector/edit/`).
All the math is a pure reducer extension; only the key-event plumbing touches
Compose.

```
ui/screens/vector/edit/EditKeyBindings.kt
object EditKeyBindings {
    // Maps a (Key, modifiers) to a VectorEditAction or null. Pure + testable
    // without a device — feed it synthetic key descriptors.
    fun resolve(key: KeySpec, shift: Boolean, ctrl: Boolean): VectorEditAction?
}
// Examples: Arrow → MoveSelection(±1 grid unit); Shift+Arrow → ×10 nudge;
// Ctrl+Z/Ctrl+Shift+Z → Undo/Redo; Delete → DeleteSelected; P → pen tool; V → select.

ui/screens/vector/edit/NumericTransformEntry.kt
data class TransformEntry(val dx: Float?, val dy: Float?, val scale: Float?, val rotateDeg: Float?)
object NumericTransform {
    // Parses free-text numeric fields into a TransformEntry and turns it into the
    // existing MoveSelection / (Phase 3) transform actions. Pure.
    fun parse(dxText: String, dyText: String, scaleText: String, rotText: String): TransformEntry?
}
```

- Nudge reuses the **existing** `VectorEditAction.MoveSelection(dx,dy)` reducer
  case and `Snap` grid step (`ui/components/notes/Snap.kt`) for the unit size, so no
  new geometry. Numeric entry dispatches the same actions a drag would.
- The Compose layer adds a `Modifier.onKeyEvent { … EditKeyBindings.resolve(…) }`
  to the editor canvas and a small numeric-transform inspector row; the
  view-model just forwards the resolved action.

**Reuses:** `VectorEditAction`/`VectorEditReducer` (Phase 1), `Snap` grid step,
the existing inspector panel layout conventions in
`ui/screens/vector/VectorPathInspectorPanel.kt`.

---

## Sub-feature 5 — AI auto-trace (bitmap → editable cubic paths)

Turn a raster image (a pasted bitmap, or the PNG `NoteAiService` already produces
— `data/notes/NoteAiService.kt:30-46` rasterizes selections to PNG; there is **no
bitmap→vector trace today**) into editable cubic `VectorPath`s feeding the unified
editable model. Two interchangeable backends behind one interface, with the
deterministic local tracer as the always-available fallback.

```
data/vector/trace/BitmapTracer.kt
interface BitmapTracer {
    // width/height + ARGB int array keeps this Android-free and JVM-testable.
    suspend fun trace(pixels: IntArray, width: Int, height: Int, options: TraceOptions): TraceResult
}
data class TraceOptions(val mode: TraceMode, val threshold: Int = 128, val simplifyTolerance: Float = 1.5f)
enum class TraceMode { OUTLINE, CENTERLINE }
data class TraceResult(val paths: List<VectorPath>, val viewport: VectorViewport, val warnings: List<VectorWarning>)
```

### 5a. Deterministic local tracer (default, pure JVM)
```
data/vector/trace/LocalBitmapTracer.kt : BitmapTracer
```
Pipeline, all pure: threshold → binary mask → contour extraction (Moore boundary
tracing) → polyline → `VectorPathSimplifier.simplify` (RDP, already exists,
`VectorPathSimplifier.kt:25`) → cubic curve-fit (least-squares Bézier fit over each
simplified span) → closed `VectorPath` with `commands` as `MoveTo`/`CubicTo`/`Close`.
`OUTLINE` traces region boundaries; `CENTERLINE` thins the mask (Zhang–Suen) then
fits open paths.

```
data/vector/trace/CurveFitter.kt
object CurveFitter {
    // Fits a sequence of cubic Béziers to an ordered point list within maxError,
    // splitting where error exceeds tolerance. Returns PathCommand cubics.
    fun fit(points: List<VectorPoint>, maxError: Float): List<PathCommand>
}
```

### 5b. Semantic AI tracer (optional, calls the multi-provider client)
```
data/vector/trace/AiBitmapTracer.kt : BitmapTracer
```
Reuses the existing multi-provider client (`data/remote/ApiClient` /
`ChatStreamer`) exactly like `NoteAiService` does: base64 the PNG as a multimodal
message, ask the model to emit path data / a `VectorScene`-style JSON
(`data/vector/VectorScene.kt` + `VectorSceneParser`/`VectorSceneCompiler` already
parse a JSON scene into a `VectorDocument` — reuse that compiler for the AI's
output), and **always fall back to `LocalBitmapTracer`** on parse failure / no
network. Gate on `ModelCapabilities.of(modelId).supportsVision`
(`data/model/ModelCapabilities.kt`), the same gate `NoteAiService` uses.

The traced `List<VectorPath>` is wrapped into a `VectorDocument` and handed to the
Phase 1/4 editable model — from there it's normal editable geometry.

**Reuses:** `VectorPathSimplifier` (RDP + dedup, `VectorPathSimplifier.kt`),
`VectorPoint`/`VectorPathSampler` types, `VectorScene`/`VectorSceneParser`/
`VectorSceneCompiler` (AI JSON → document), `ApiClient`/`ChatStreamer` +
`ModelCapabilities` (semantic backend), `PathDataFormatter` (serialize fitted
cubics).

---

## File-by-file work list

**New — sub-feature 1 (`data/vector/`)**
- `StrokeDashBaker.kt` — dash → chopped on-run subpaths
- `VariableWidthProfile.kt` — width-stop model
- `StrokeOutliner.kt` — centerline → filled outline

**New — sub-feature 2 (`data/vector/`)**
- `VectorFill.kt` — sealed solid/linear/radial/sweep fill + `GradientStop`

**New — sub-feature 3 (`data/vector/symbol/`)**
- `VectorSymbol.kt`, `SymbolInstance.kt`, `SymbolResolver.kt` (+ a Room
  entity/DAO mirroring `Stamp`)

**New — sub-feature 4 (`ui/screens/vector/edit/`)**
- `EditKeyBindings.kt`, `NumericTransformEntry.kt`

**New — sub-feature 5 (`data/vector/trace/`)**
- `BitmapTracer.kt`, `LocalBitmapTracer.kt`, `CurveFitter.kt`, `AiBitmapTracer.kt`

**Modified**
- `data/vector/VectorDocument.kt` — additive `VectorStyle` fields
  (`strokeDashArray`, `strokeDashOffset`, `variableWidth`, `fill`); new
  `VectorNode.InstanceNode`; new warning code `STROKE_DASH_BAKED`
- `data/vector/AndroidVectorDrawableWriter.kt` — dash baking, gradient
  `<aapt:attr>` emission, outline-to-fill swap, `xmlns:aapt` header
- `data/vector/VectorSvgWriter.kt` — `stroke-dasharray`/offset, `<defs>` gradients,
  outline-to-fill swap
- `data/vector/AndroidVectorDrawableParser.kt` / `VectorSvgParser.kt` — populate
  `VectorFill` instead of dropping gradients
- `data/vector/VectorPreviewModel.kt` / `VectorPreviewBuilder.kt` — carry
  `fill` + dash through `VectorPreviewStyle`
- `ui/screens/vector/VectorPreviewCanvas.kt` — `Brush` gradients, dash
  `PathEffect`, outline-fill draw
- `ui/screens/vector/VectorPathEditPanel.kt` — cap/join/dash/fill controls
- `ui/screens/vector/edit/VectorEditCanvas.kt` — `onKeyEvent` plumbing

**Reused unchanged:** `VectorPathSimplifier`, `VectorPathSampler`,
`PathDataFormatter`, `parseVectorColor`/`toStrokeCap`/`toStrokeJoin`,
`VectorScene*`, `ApiClient`/`ChatStreamer`, `ModelCapabilities`, `Snap`,
`VectorManualEdit`/`VectorManualEditApplier`, `allPaths()`/`allGroups()`.

---

## Test plan (pure JVM — the bulk of the value lands here)

`app/src/test/java/com/aichat/sandbox/data/vector/`
- `StrokeDashBakerTest`
  - `bake_simpleLine_producesAlternatingOnRunsOfExpectedTotalLength`
  - `bake_respectsDashOffset_shiftsFirstRun`
  - `bake_zeroOffWidth_isContinuous`
- `StrokeOutlinerTest`
  - `outline_constantProfile_matchesPlainStrokeWithinTolerance`
  - `outline_taperedProfile_widthAtMidpointEqualsInterpolatedStop`
  - `outline_isClosed`
- `VectorFillRoundTripTest`
  - `linearGradient_writesAaptAttr_andReparsesToEqualStops` (Android)
  - `radialGradient_writesSvgDefs_andReparsesToEqualStops` (SVG)
  - `sweepGradient_svg_fallsBackToFirstStop_withWarning`
  - `solidFill_unchanged_existingDocumentsByteIdentical` (regression guard)
- `SymbolResolverTest`
  - `expand_singleInstance_producesGroupWithSymbolChildren`
  - `expand_namespacesChildIdsUniquely`
  - `expand_appliesInstanceTransformAndStyleOverride`
  - `expand_masterEdit_propagatesToAllInstances`
- `data/vector/trace/`
  - `LocalBitmapTracerTest`
    - `trace_solidSquare_yieldsSingleClosedPathWithin1pxOfCorners`
    - `trace_centerlineOfThinLine_yieldsOpenPath`
    - `trace_emptyBitmap_yieldsNoPathsWithWarning`
  - `CurveFitterTest`
    - `fit_collinearPoints_yieldsSingleLinearCubic`
    - `fit_circleSamples_reproducesCircleWithinTolerance`
  - `AiBitmapTracerTest`
    - `trace_validJsonScene_compilesViaSceneCompiler` (fake `ChatStreamer`)
    - `trace_aiFailure_fallsBackToLocalTracer`
    - `trace_nonVisionModel_usesLocalTracer`

`app/src/test/java/com/aichat/sandbox/ui/screens/vector/` (+ `/edit/` once Phase 1 lands)
- `EditKeyBindingsTest`
  - `arrow_mapsToOneGridUnitNudge`
  - `shiftArrow_mapsToTenUnitNudge`
  - `ctrlZ_mapsToUndo_ctrlShiftZ_mapsToRedo`
- `NumericTransformTest`
  - `parse_validFields_buildsTransformEntry`
  - `parse_blankOrGarbage_returnsNullField`

Run:
`./gradlew :app:testDebugUnitTest --tests "com.aichat.sandbox.data.vector.*" \
  --tests "com.aichat.sandbox.data.vector.trace.*" --console=plain`.
(The known-preexisting Android-`Color`/`Log` "not mocked" failures in
`NoteSvgExporterTest` / `NoteVectorDrawableExporterTest` / `NoteAiServiceTest` are
unrelated — the suite is green if those are the only failures.)

**On-device manual verification:** import an icon → set a dashed round-cap stroke
→ export to SVG (native dasharray) and VectorDrawable (baked runs) and re-import,
confirming visual identity; paint a linear gradient fill and confirm the preview,
the SVG `<defs>` reference, and the Android `<aapt:attr>` all render; define a
symbol, drop three instances, edit the master, confirm all three update; trace a
photographed sketch and confirm editable anchors appear.

---

## Sequencing within the phase

Each sub-feature is independent; within each, land model + writer/parser +
pure-JVM tests **before** any Compose/UI wiring (UI is the thin, untested top).

1. **1a caps/joins UI** — smallest; pure UI on existing model.
2. **1b/1c dash + variable-width** — geometry bakers + writer/preview, fully
   testable.
3. **2 gradients** — model + both writers/parsers + preview `Brush`; round-trip
   tests gate it.
4. **3 symbols** — `SymbolResolver` expansion + tests, then library store/UI.
5. **4 ergonomics** — pure key/numeric resolvers + tests, then `onKeyEvent`.
6. **5 auto-trace** — local tracer + curve-fit + tests first, AI backend +
   fallback last.

## Risks

- **Dash/outline baking fidelity** (1b/1c) — chopped runs or offset polygons can
  drift from the ideal stroke. Mitigated by the constant-profile-equals-plain-stroke
  invariant test and total-length assertions on dash runs.
- **Gradient round-trip lossiness** (2) — Android `<aapt:attr>` vs SVG `<defs>`
  have different coordinate conventions (objectBoundingBox vs userSpace) and sweep
  has no SVG primitive. Mitigated by explicit fallback+warning paths and per-format
  round-trip tests; userSpace coordinates are emitted to avoid bounding-box
  ambiguity.
- **Symbol id collisions on expansion** (3) — namespaced ids
  (`"${instance.id}/${childId}"`) keep every expanded node unique; covered by a
  dedicated uniqueness test.
- **Trace quality on noisy bitmaps** (5) — contour tracing is sensitive to
  threshold and anti-aliasing. Mitigated by RDP pre-simplification, a tunable
  tolerance, and the AI backend for semantic cases — with the deterministic local
  tracer always available as the fallback so a trace never hard-fails.
