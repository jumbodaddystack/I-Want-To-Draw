# Phase 2 Implementation Plan — Boolean Path Operations + Stroke Outlining

## Context

Phase 1 (`phase1-editable-bezier-scene-graph.md`) gives the Vector Tune-Up
workspace a real editing layer: an **all-cubic, absolute-coordinate** editable
model in `data/vector/edit/` (`EditablePath` / `EditSubpath` / `EditAnchor`),
pure converters to/from the read-only `VectorDocument`
(`EditablePathFactory`, `EditablePathSerializer`), and a pure reducer/ViewModel
stack in `ui/screens/vector/edit/`. A user can grab an anchor and move it, but
they cannot yet combine two shapes.

Phase 2 adds **shape algebra** on top of that model: boolean operations
(union / subtract / intersect / exclude) on 2+ selected paths, **outline-stroke**
(turn a stroked centerline into a filled outline), and **path offset**
(inset/outset). All of it is pure JVM geometry that produces *new*
`EditablePath`s, so the results re-enter the Phase 1 editable model, undo/redo,
version history, and the existing exporters for free.

The hard constraint is that our editable node model is **cubic Béziers**, but
robust boolean clipping on Béziers is fiddly. We deliberately take the
**flatten → clip → refit** route: sample editable cubics to dense polygons
(reuse `VectorPathSampler`), clip the polygons with a pure-Kotlin polygon
clipper, then refit the resulting polylines back to cubic anchors
(`VectorPathSimplifier` for point reduction + a curve-fit step). Correctness
first; curve fidelity is a tolerance knob, and the lossiness is documented and
tested rather than hidden.

**Scope of this phase (in):**
- Boolean ops on 2+ selected `EditablePath`s / subpaths: `UNION`, `SUBTRACT`
  (a − b), `INTERSECT`, `EXCLUDE` (xor).
- `OUTLINE_STROKE`: convert a stroked path (no/transparent fill, non-null
  `style.strokeWidth`) into a filled outline shape honoring cap/join.
- `OFFSET` (inset/outset): grow or shrink a filled shape by a signed distance.
- Respect fill-rule / winding: `nonZero` vs `evenOdd` derived from
  `VectorStyle.fillType`.
- Three reducer actions (`BooleanOp(kind)`, `OutlineStroke`, `OffsetPath`),
  each a **single undo entry**, wired into the Phase 1 reducer/toolbar.

**Out of scope (later / explicitly not done):**
- GPU acceleration; real-time boolean *preview while dragging* (op runs on
  command, not on every frame).
- **Exact-curve clipping.** We flatten and refit. The flattening tolerance and
  refit error are documented constants and are asserted in tests.
- Gradients, multi-result holes-as-gradients, anything `VectorStyle` can't carry.
- New Room schema / persistence — results are ordinary `EditablePath`s that
  serialize through the Phase 1 path already.

---

## Design — the boolean module (new — `data/vector/edit/boolean/`)

Everything here is **pure Kotlin, no Android imports**, so the entire algebra is
JVM-unit-tested like `VectorTuneupReducer` / `VectorPathSimplifier`. The public
entry point is `PathBoolean`; the rest are internal stages it composes.

### Internal polygon representation

We never expose raw polygons outside the module — they are the intermediate
between "editable cubics" and "editable cubics". They reuse the existing
`VectorPoint` (`data/vector/VectorPathSampler.kt`) so we share float-format and
geometry helpers.

```
data/vector/edit/boolean/Polygon.kt

    // A single closed ring of flattened points (absolute viewport coords).
    // Sign of signed-area encodes orientation; the clipper uses it for winding.
    internal data class Ring(val points: List<VectorPoint>) {
        val signedArea: Float           // shoelace; >0 = CCW, <0 = CW
        val area: Float get() = abs(signedArea)
    }

    // One shape = one or more rings (outer + holes), with its fill rule.
    internal data class PolyShape(
        val rings: List<Ring>,
        val fillRule: FillRule,         // NONZERO | EVENODD
    )

    internal enum class FillRule { NONZERO, EVENODD }
```

### Stage 1 — flatten editable cubics → polygons

```
data/vector/edit/boolean/PathFlattener.kt

    internal object PathFlattener {
        // Reuses VectorPathSampler by first serializing the EditablePath's
        // subpaths to List<PathCommand> via the Phase 1 EditablePathSerializer,
        // then sampling each subpath. Open subpaths are closed implicitly for
        // area ops (documented); outline-stroke treats them as centerlines.
        fun flatten(path: EditablePath, tolerance: Float): PolyShape
        fun flattenSubpath(sub: EditSubpath, tolerance: Float): Ring
    }
```

Implementation note: `VectorPathSampler.sample(commands, curveSteps)` takes a
**fixed step count**, not a flatness tolerance. We translate a world-space
`tolerance` into `curveSteps` per subpath (estimate from control-polygon length /
tolerance, clamped to e.g. `4..64`), so denser curves get more samples. Then
`VectorPathSimplifier.removeConsecutiveDuplicates` drops coincident points the
sampler may emit at segment joins.

### Stage 2 — the clipper (the load-bearing decision)

```
data/vector/edit/boolean/PolygonClipper.kt

    internal object PolygonClipper {
        fun clip(subject: PolyShape, clip: PolyShape, op: BoolOp): PolyShape
    }
    internal enum class BoolOp { UNION, INTERSECT, DIFFERENCE, XOR }
```

**Algorithm choice — recommend Martinez–Rueda over Greiner–Hormann.**
- *Greiner–Hormann* is short to write but historically mishandles
  **degenerate / collinear / coincident** edges and shared vertices (exactly
  what icon geometry full of axis-aligned edges produces), needing perturbation
  hacks. Reject for a robustness-critical, test-asserted feature.
- *Martinez–Rueda* (a sweep-line over edge "events" with a status structure) is
  dependency-light, handles all four ops in one pass, natively supports
  multi-ring polygons with holes, and emits correctly-oriented result rings
  (outer CCW, holes CW). It is the right amount of complexity for pure Kotlin.
  **Recommended.** ~400–600 lines, self-contained, no third-party dep.

The clipper consumes/produces `PolyShape` with explicit ring orientation, so
winding is data, not a side channel. `BooleanOp` over >2 selected paths folds
pairwise: `result = paths.reduce { acc, p -> clip(acc, p, op) }` for
union/intersect/xor; subtract is `subject` minus the union of the rest.

### Stage 3 — winding / fill-rule normalization

```
data/vector/edit/boolean/FillRuleResolver.kt

    internal object FillRuleResolver {
        // Map VectorStyle.fillType (String? "evenOdd"/"nonZero"/null) -> FillRule.
        fun ruleOf(style: VectorStyle): FillRule      // null/unknown -> NONZERO
        // Choose the result's fillType string written back onto the new path.
        fun fillTypeFor(rule: FillRule): String?      // EVENODD -> "evenOdd"
    }
```

`VectorStyle.fillType` is a nullable `String` (verified in
`data/vector/VectorDocument.kt`; the SVG writer compares it
`equals("evenOdd", ignoreCase = true)`). We normalize inputs to a `FillRule`,
run the op, and emit a fill rule that reproduces the clipped coverage — boolean
results are non-overlapping outer+hole rings, so **`nonZero` is the canonical
output** unless evenodd genuinely round-trips fewer rings.

### Stage 4 — refit polygons → cubic anchors (back into Phase 1 model)

```
data/vector/edit/boolean/CurveRefit.kt

    internal object CurveRefit {
        // polyline ring -> EditSubpath of cubic anchors within maxError.
        fun refit(ring: Ring, maxError: Float, idPrefix: String): EditSubpath
    }
```

- First reduce points with `VectorPathSimplifier.simplify(points, tolerance)`
  (RDP) — kills the dense flattening samples while keeping corners.
- Then run a **Schneider-style least-squares cubic fit** per run of points
  between detected corners (corner = turn angle above a threshold becomes a
  `CORNER` anchor; smooth runs become `SMOOTH` anchors). Each fitted segment
  yields an `EditAnchor` with absolute `inX/inY` / `outX/outY` handles — exactly
  the Phase 1 `EditAnchor` shape. Straight runs emit handle-less anchors so they
  serialize as `LineTo`.
- Deterministic ids (`"${idPrefix}.a${j}"`) so the new path is selectable/undoable.

### Stage 5 — public façade

```
data/vector/edit/boolean/PathBoolean.kt

    object PathBoolean {
        data class Options(
            val flattenTolerance: Float = 0.25f,   // world units (viewport space)
            val refitMaxError: Float = 0.5f,
            val cornerAngleDeg: Float = 30f,
        )

        fun combine(
            paths: List<EditablePath>,             // >= 2, op-defined order
            op: BoolOp,
            newPathId: String,
            opts: Options = Options(),
        ): EditablePath                            // single result path

        fun outlineStroke(path: EditablePath, newPathId: String, opts: Options): EditablePath
        fun offset(path: EditablePath, delta: Float, newPathId: String, opts: Options): EditablePath
    }
```

Style of the result: inherit the **subject** path's `style`, but force
`strokeWidth = null` / `strokeColor = null` for boolean+outline results (the
output is a pure fill) and set `fillType` from `FillRuleResolver`. Offset keeps
the input style.

### Outline-stroke (new — `StrokeOutliner.kt`)

```
data/vector/edit/boolean/StrokeOutliner.kt

    internal object StrokeOutliner {
        // Centerline polyline + width -> filled outline PolyShape.
        fun outline(
            centerline: Ring,                  // or open polyline
            open: Boolean,
            width: Float,
            cap: LineCap, join: LineJoin,
        ): PolyShape
        internal enum class LineCap { BUTT, ROUND, SQUARE }
        internal enum class LineLineJoin // (JOIN: MITER, ROUND, BEVEL)
    }
```

Approach: offset the centerline by `+width/2` and `−width/2` (left/right
polyline offset), join the two offset sides with cap geometry at the ends (butt =
straight, square = extend half-width, round = sampled arc) and join geometry at
interior vertices (miter up to `style.strokeMiterLimit`, else bevel; round =
sampled fan). For a **closed** centerline, produce two rings (outer + inner hole)
→ an annulus. `cap`/`join`/`miter` come from
`style.strokeLineCap` / `strokeLineJoin` / `strokeMiterLimit`. The result is a
`PolyShape` that then goes through Stage 4 refit unchanged.

### Path offset (new — `PathOffset.kt`)

```
data/vector/edit/boolean/PathOffset.kt

    internal object PathOffset {
        // Signed offset of a closed shape: +delta grows, -delta shrinks.
        fun offset(shape: PolyShape, delta: Float, join: StrokeOutliner.LineLineJoin): PolyShape
    }
```

Implementation: offset each ring's edges by `delta` along the outward normal,
patch the per-vertex gaps/overlaps with the same join logic as the outliner,
then **self-union** the result through `PolygonClipper` to clean self-intersections
that appear on concave insets. Strongly negative deltas can erase a shape — we
return an empty `PolyShape` (→ caller declines the op with a warning, no crash).

---

## Reuse, don't rebuild

| Need | Reuse | Path |
| --- | --- | --- |
| Flatten cubics → polyline | `VectorPathSampler.sample` | `data/vector/VectorPathSampler.kt` |
| Point/vertex type + float format | `VectorPoint` | `data/vector/VectorPathSampler.kt` |
| Drop dense/duplicate points (RDP) | `VectorPathSimplifier.simplify` / `removeConsecutiveDuplicates` | `data/vector/VectorPathSimplifier.kt` |
| Ring length / area helper basis | `VectorPathSimplifier.pathLength` | `data/vector/VectorPathSimplifier.kt` |
| Editable model (input + output) | `EditablePath` / `EditSubpath` / `EditAnchor` | `data/vector/edit/EditablePath.kt` (Phase 1) |
| EditablePath ↔ `List<PathCommand>` | `EditablePathSerializer` / `EditablePathFactory` | `data/vector/edit/` (Phase 1) |
| Commands ↔ `pathData` string | `PathDataFormatter.format` / `PathDataParser.parse` | `data/vector/PathDataFormatter.kt`, `PathDataParser.kt` |
| Fill-rule string convention | `VectorStyle.fillType` ("evenOdd"/"nonZero") | `data/vector/VectorDocument.kt`, `VectorSvgWriter.kt:119` |
| Stroke attrs (cap/join/miter/width) | `VectorStyle.strokeLineCap/LineJoin/MiterLimit/Width` | `data/vector/VectorDocument.kt:83` |
| Pure reducer + undo pattern | `VectorEditReducer` / `VectorEditAction` / `VectorEditState` | `ui/screens/vector/edit/` (Phase 1) |
| Re-entry to document + versions | `VectorDocument.replacePath` / version-history reducer | `data/vector/VectorDocument.kt` (Phase 1 helper), `ui/screens/vector/VectorTuneupReducer.kt` |

Nothing in `VectorPathSampler` / `VectorPathSimplifier` / `PathDataFormatter`
changes; the boolean module composes them.

---

## Reducer actions (modify — `ui/screens/vector/edit/`)

Phase 1 owns `VectorEditAction` / `VectorEditReducer` / `VectorEditState`. We add
three actions and their handling; **each is one undo entry** (mirrors how Phase 1
mutating actions push an inverse onto `undoStack`).

```
// added to VectorEditAction (sealed):
data class BooleanOp(val kind: BoolOpKind) : VectorEditAction   // UNION/SUBTRACT/INTERSECT/EXCLUDE
data object OutlineStroke : VectorEditAction
data class OffsetPath(val delta: Float) : VectorEditAction
enum class BoolOpKind { UNION, SUBTRACT, INTERSECT, EXCLUDE }
```

Reducer handling (pure):
- `BooleanOp`: read the **selected** `EditablePath`s from `state` (≥2 for boolean;
  reducer no-ops + sets a transient message if <2). Call
  `PathBoolean.combine(selected, kind.toBoolOp(), newPathId)`. Replace the
  selected paths in the working document with the single result (preserve
  subject z-order), update `selection` to the new path, push undo.
- `OutlineStroke`: requires exactly one selected stroked path
  (`style.strokeWidth != null`). Call `PathBoolean.outlineStroke`. No-op with a
  message if the path has no stroke.
- `OffsetPath`: one selected path; `PathBoolean.offset(path, delta, …)`. Empty
  result (over-shrunk) → no-op + warning, undo not pushed.
- New ids come from the same id scheme Phase 1 uses (e.g. a `state.nextPathId()`),
  so undo/redo and selection stay stable.

Toolbar buttons (Union / Subtract / Intersect / Exclude / Outline / Offset±) are
added to the Phase 1 edit toolbar in `VectorEditCanvas` / `VectorEditScreen`;
they dispatch the actions. No new ViewModel — reuse `VectorEditViewModel`.

---

## File-by-file work list

**New (`data/vector/edit/boolean/`)**
- `Polygon.kt` — `Ring` / `PolyShape` / `FillRule`, shoelace area + orientation.
- `PathFlattener.kt` — `EditablePath` → `PolyShape` (tolerance → curveSteps; reuses sampler).
- `PolygonClipper.kt` — Martinez–Rueda clip for UNION/INTERSECT/DIFFERENCE/XOR.
- `FillRuleResolver.kt` — `VectorStyle.fillType` ↔ `FillRule`.
- `StrokeOutliner.kt` — centerline + width/cap/join → outline `PolyShape`.
- `PathOffset.kt` — signed offset of a `PolyShape` (self-union cleanup).
- `CurveRefit.kt` — `Ring` → `EditSubpath` of cubic anchors (RDP + Schneider fit).
- `PathBoolean.kt` — public façade (`combine` / `outlineStroke` / `offset`, `Options`, `BoolOp`).

**Modified (`ui/screens/vector/edit/` — Phase 1 files)**
- `VectorEditAction.kt` — add `BooleanOp` / `OutlineStroke` / `OffsetPath` + `BoolOpKind`.
- `VectorEditReducer.kt` — handle the three actions (single undo each, selection/z-order updates).
- `VectorEditState.kt` — only if a transient "needs ≥2 selection" message field is added.
- `VectorEditCanvas.kt` / `VectorEditScreen.kt` — toolbar buttons dispatching the actions.

**Reused unchanged:** `VectorPathSampler`, `VectorPathSimplifier`,
`PathDataFormatter`, `PathDataParser`, `EditablePath*` (Phase 1),
`VectorDocument`/`VectorStyle`, version-history reducer paths, exporters
(`AndroidVectorDrawableWriter`, `VectorSvgWriter`).

---

## Test plan (pure JVM — the bulk of the value)

All under `app/src/test/java/com/aichat/sandbox/data/vector/edit/boolean/`
(create `edit/boolean/`; sibling of the existing
`app/src/test/java/com/aichat/sandbox/data/vector/` suites). Geometry is checked
by **area / winding invariants within tolerance**, not exact float equality,
because flatten→refit is intentionally lossy. Helper: build `EditablePath`s for
circles/rectangles (cubic-approx circle) directly in the test, assert
`PolyShape.area` of the flattened result.

`PolygonClipperTest`
- `union_ofTwoOverlappingCircles_areaWithinTolerance` — area ≈ 2·A − overlap,
  within `flattenTolerance`-derived epsilon.
- `subtract_overlappingCircles_makesCrescent_areaLessThanSubject`.
- `intersect_ofDisjointShapes_isEmpty` — result has no rings.
- `intersect_ofConcentricCircles_equalsSmaller`.
- `xor_ofOverlappingSquares_equalsUnionMinusIntersection_area`.
- `clip_selfIntersectingFigureEight_doesNotThrow_andProducesValidRings`.
- `clip_sharedCollinearEdges_squaresTouchingOnAnEdge_unionIsOneRing` (the
  degenerate case that motivates Martinez over Greiner–Hormann).

`PathBooleanTest` (façade + round-trip)
- `combine_threeOverlappingCircles_union_singleOutlinePath`.
- `combine_resultReSerializes_throughEditablePathSerializer_andReparses`
  — `EditablePathSerializer` → `PathDataFormatter` → `PathDataParser.parse`
  yields the same command count/shape (round-trip back through the Phase 1 pipe).
- `combine_evenOddSubject_resultFillTypeIsCanonical`.
- `combine_fewerThanTwoPaths_returnsInputUnchanged_orThrowsClearly`.

`StrokeOutlinerTest`
- `outline_straightHorizontalStroke_isRectangle_withinTolerance` — area ≈
  `length·width`; 4 corners after refit.
- `outline_closedSquareStroke_producesAnnulus_outerAndInnerRing`.
- `outline_roundCap_addsAreaVersusButtCap`.
- `outline_miterJoin_respectsMiterLimit_fallsBackToBevel`.

`PathOffsetTest`
- `offset_positiveDelta_growsArea_predictably` — circle r→r+δ, area ≈ π(r+δ)².
- `offset_negativeDelta_shrinksArea`.
- `offset_overShrink_returnsEmptyShape_noCrash`.
- `offset_concavePolygon_selfUnionCleansSelfIntersections`.

`CurveRefitTest`
- `refit_ofCirclePolygon_isWithinMaxError_andUsesSmoothAnchors`.
- `refit_ofRectanglePolygon_keepsFourCornerAnchors_noStrayHandles`.
- `refit_straightRun_emitsHandlelessAnchors_serializeAsLineTo`.

`FillRuleResolverTest`
- `ruleOf_mapsEvenOddNonZeroAndNullDefault`.
- `fillTypeFor_roundTripsWithStyleFillType`.

Reducer-level (in `app/src/test/java/com/aichat/sandbox/ui/screens/vector/edit/`,
extending Phase 1's `VectorEditReducerTest` neighborhood):
`VectorBooleanReducerTest`
- `booleanUnion_twoSelected_replacesWithOneResult_singleUndoEntry`.
- `booleanOp_lessThanTwoSelected_isNoOp`.
- `outlineStroke_strokedPath_producesFill_undoRestores`.
- `outlineStroke_unstrokedPath_isNoOp`.
- `offsetPath_undoRedo_invertsExactly`.

Run:
`./gradlew :app:testDebugUnitTest --tests "com.aichat.sandbox.data.vector.edit.boolean.*"
--tests "com.aichat.sandbox.ui.screens.vector.edit.*" --console=plain`.
(The known pre-existing Android-`Color`/`Log` "not mocked" failures in
`NoteSvg/VectorDrawable/AiService` tests are unrelated; the suite is green if
those are the only failures.)

**On-device manual verification:** open an icon → Edit → select two overlapping
shapes → Union (then Subtract / Intersect / Exclude) → confirm the result is a
single editable path whose anchors are draggable → Outline a stroked path →
Offset ±. Undo/redo each. Exit → returns as a new version → exports to
VectorDrawable + SVG that re-import identically.

---

## Sequencing within the phase
1. `Polygon.kt` + `PathFlattener.kt` + `FillRuleResolver.kt` with area/winding
   tests — pure data, de-risks everything downstream.
2. `PolygonClipper.kt` (Martinez–Rueda) with the golden-geometry clipper tests
   — the algorithmic core; land it before any refit so correctness is provable
   on polygons alone.
3. `CurveRefit.kt` + `PathBoolean.combine` round-trip tests — close the loop back
   into the Phase 1 editable model. **Ship flatten→clip→refit booleans here**
   (correctness over curve fidelity) before touching stroke/offset.
4. `StrokeOutliner.kt` + `PathOffset.kt` with their tests — reuse the clipper for
   self-union cleanup.
5. Reducer actions + toolbar wiring + reducer tests.
6. Tune-Up return-as-version + export verification.

## Risks
- **Clipper robustness on degenerate/collinear edges** is the dominant risk
  (icon geometry is full of axis-aligned, coincident edges). Mitigated by
  choosing Martinez–Rueda (not Greiner–Hormann) and by the explicit
  shared-edge / figure-eight / disjoint tests landing with the clipper in step 2.
- **Flatten→refit lossiness** is accepted and bounded: `flattenTolerance` and
  `refitMaxError` are documented `Options` constants, and every geometry test
  asserts *within tolerance* rather than exact equality. Circles become
  near-circles, not algebraic ellipses — by design.
- **Offset self-intersection on concave insets** → handled by a self-union pass
  through the clipper; over-shrink returns an empty shape and the reducer
  no-ops with a message instead of producing a degenerate path.
- **Tolerance ↔ step-count translation** (`VectorPathSampler` takes steps, not a
  flatness target): a wrong heuristic over/under-samples. Covered by the
  circle-area tests, which fail loudly if sampling is too coarse.
- **Result style ambiguity** (which input's fill/stroke wins, what `fillType` to
  emit): pinned by `FillRuleResolver` + the "result is a pure fill, subject style
  inherited" rule, asserted in `PathBooleanTest`.
