# Vision + Roadmap: From "icon sketchpad" to "the app that makes every phone icon"

## Context

This is a **strategy/roadmap brainstorm**, not an implementation this session.
The ask: imagine an app so good it's used to produce *all* mobile-phone vector
icons, look hard at the current vector icon system, and map what it would take
to close the gap. Two parallel explorations of the codebase produced the
"current state" below. The user prioritized four gaps: **editable Bézier scene
graph, boolean path ops, a pixel-perfect icon pipeline, and unifying the two
disconnected vector domains.** This document turns those into a phased plan.

No code is written here. Each phase names the files it would touch and how it
would be verified, so it can be peeled off and executed later.

---

## 1. The gold standard — what "makes every phone icon" actually requires

A tool that ships production icon sets (think Material Symbols / Nucleo /
Figma-for-icons class) is defined by a small number of non-negotiables:

- **A retained, editable Bézier scene graph.** Every shape is anchor points +
  control handles you can grab, nudge, add, delete, and convert (corner↔smooth)
  *forever* — not a one-shot rasterized stroke.
- **Boolean construction.** Icons are built by union/subtract/intersect/exclude
  of primitives, plus *outline-stroke* (stroke→fillable shape). This is how you
  get clean, scalable geometry instead of overlapping paint.
- **Pixel-perfect discipline.** Fixed keyline grids, integer-coordinate
  snapping, multiple synchronized artboards (24/48/108 dp), optical alignment,
  and a target that quantizes to the grid so the icon is crisp at 1×.
- **Lossless, spec-correct export.** Android VectorDrawable + SVG that round-trip
  with zero geometry drift; batch export of a whole set.
- **One canvas.** Draw, import, refine, and AI-assist in a single editable
  model — no mode where geometry is "view only."

## 2. Where we are today

The repo already has an impressive amount of the *substrate* — but split across
two domains that don't share a model:

**A. Note drawing canvas** (`ui/components/notes/DrawingSurface.kt`,
`data/model/NoteItem.kt`, `ui/components/notes/StrokeCodec.kt`)
- Stylus-first freehand ink: pressure/tilt samples → quadratic-Bézier polylines,
  rendered **pixel-centric to a bitmap**. Strokes are **immutable after commit**.
- Has real strengths to build on: layers (`data/model/NoteLayer.kt`), lasso +
  affine transform (`ui/components/notes/StrokeTransform.kt`), grid/angle/endpoint
  snapping (`ui/components/notes/Snap.kt`), icon-artboard clipping, and
  AI edit-ops (`data/notes/EditProtocol.kt`).
- Export: `data/notes/NoteSvgExporter.kt`, `NoteVectorDrawableExporter.kt`
  (**lossy** — strokes flatten to an average width; round caps/joins hardcoded).

**B. Vector Tune-Up** (`data/vector/*`, `ui/screens/vector/VectorTuneupScreen.kt`)
- A genuinely strong **import + optimize + AI-edit** pipeline: tolerant path
  parser (`data/vector/PathDataParser.kt`), full `VectorDocument` model with
  groups/transforms/styles (`data/vector/VectorDocument.kt`), RDP simplify,
  metrics/validation, AI edit-plans and safe "semantic redraw", multi-format
  export. **But it has no drawing/editing surface — it is view-and-optimize only.**

**The root cause of the gap:** there is no single *editable vector scene graph*.
Domain A can edit but its geometry is raster-friendly and immutable. Domain B has
a rich vector model (`VectorDocument`/`PathCommand`) but treats it as read-only.
Almost everything else (parsers, writers, snapping, layers, AI plumbing) already
exists and can be reused once that core exists.

## 3. The core architectural bet

**Promote `data/vector/VectorDocument` from a read-only import target into the
live, editable document model**, and make *both* the notes canvas and the
Tune-Up screen edit *it*. This single decision closes "unify the two domains"
structurally and is the foundation every other phase builds on. The existing
`PathCommand` sealed type and `PathDataParser`/`AndroidVectorDrawableWriter`
already give us lossless parse↔serialize; what's missing is an interactive
editing layer on top.

---

## 4. Phased roadmap

### Phase 0 — Decide the editable model (spike, no user-facing change)
- Confirm `VectorPath.PathCommand[]` (`data/vector/VectorDocument.kt`) as the
  single source of truth for editable geometry; define an in-memory "node view"
  (anchor + in/out control handles + corner/smooth flag) derived from cubic
  commands, with a stable mapping back to `PathCommand[]`.
- Decide selection/identity model (stable IDs per node/segment/subpath).
- **Verify:** pure-JVM round-trip tests — `PathCommand[] → nodes → PathCommand[]`
  is byte-stable for representative icons; reuse fixtures already exercised by
  `PathDataParserTest`-style tests in `app/src/test`.

### Phase 1 — Editable Bézier scene graph + pen tool *(top priority)*
- New interactive editor over `VectorDocument`: a **pen tool** (click to place
  anchors, drag for handles), **direct-selection** (move anchors/handles),
  add/delete anchor, convert corner↔smooth, close/open subpath.
- Reuse: `Snap.kt` for grid/angle snapping of anchors; `StrokeTransform.kt`
  affine math for move/scale/rotate of selections; `NoteLayer.kt` for layering;
  the existing viewport/pan-zoom controller from `DrawingSurface.kt`.
- Rendering: draw paths via Compose/Canvas `Path` from `PathCommand[]` (the
  Tune-Up preview already approximates this in `VectorPreviewCanvas` — extend it
  to interactive, hit-testable rendering).
- **Verify:** unit tests for node-edit operations (insert/delete/convert keep the
  path valid and reversible); manual run on device building a simple glyph;
  undo/redo via the existing `EditorAction` log pattern.

### Phase 2 — Boolean path operations + stroke outlining
- Add union / subtract / intersect / exclude on selected paths, plus
  **outline-stroke** (convert a stroked path to a filled outline) and offset.
- Implementation choice to settle during the phase: port a compact poly-clipping
  algorithm (e.g. a Kotlin Martinez/greiner-hormann implementation) operating on
  flattened curves, then refit to cubics — keeps it dependency-light and
  JVM-testable. Reuse `VectorPathSampler`/`VectorPathSimplifier` for flatten/refit.
- **Verify:** golden-geometry unit tests (two overlapping circles → known union
  outline within tolerance); area/winding invariants; visual diff via the
  existing `VectorVersionDiffAnalyzer`.

### Phase 3 — Pixel-perfect icon pipeline
- **Keyline grids** (Material keylines: square/circle/rounded-square/vertical/
  horizontal templates) layered over the artboard; extend `Snap.kt` with
  integer-coordinate snapping and a "snap to pixel grid" mode.
- **Synchronized multi-size artboards** (24 / 48 / 108 dp) editing one master,
  building on the existing `NoteFrame`/artboard-clipping concept and the size
  options already in `NoteVectorDrawableExporter.kt`.
- **Grid-quantized, lossless export**: replace the lossy stroke-flatten export
  path with serialization straight from the editable `VectorDocument` via the
  existing `AndroidVectorDrawableWriter`/`VectorSvgWriter` — geometry is already
  vector, so no averaging. Add batch/set export.
- **Verify:** export → re-import (`AndroidVectorDrawableParser`) is geometry-
  stable; rendered raster at 1× has no half-pixel seams on a test icon set;
  exporter unit tests assert integer coordinates when quantization is on.

### Phase 4 — Unify the two domains into one editor
- Make the notes canvas and Tune-Up two *views* of the same editable
  `VectorDocument`: import (SVG/VectorDrawable via existing parsers) drops
  straight onto the editable canvas; freehand strokes are vectorized into editable
  paths on commit (reuse RDP simplify + curve-fit from the AI "clean up"/
  "auto-shape" path in `EditPreviewController`).
- Route existing AI features (`EditProtocol`, Tune-Up edit-plans / semantic
  redraw) to emit edits against the unified model so AI assist works everywhere.
- **Verify:** an icon can be imported, node-edited, boolean-combined,
  grid-aligned, and exported losslessly in one session; the previously
  separate Tune-Up optimizations still apply to the same document.

### Phase 5 — Production polish (post-core, lower priority)
- Stroke styling (caps/joins/dashes, variable-width profiles), gradients/advanced
  fills (the `VectorStyle` model already has fields to extend), reusable vector
  **symbols** (replace raster stamps with master/instance vector symbols),
  keyboard nudge/shortcuts, and AI auto-trace (bitmap→vector) feeding the same model.

---

## 5. Sequencing & risk

- **Strict dependency order:** Phase 0→1 is the keystone; 2, 3, 4 can then proceed
  with some parallelism, but all depend on the editable model existing.
- **Biggest risk:** boolean ops (Phase 2) — robust curve clipping is genuinely
  hard. De-risk by flattening to high-density polylines first (correctness over
  curve fidelity), then refitting; ship that before pursuing exact-curve clipping.
- **Performance:** moving from bitmap-blit (`DrawingSurface`) to a live,
  hit-tested vector scene graph changes the render hot path — budget for spatial
  indexing of nodes for large icons.
- **Scope honesty:** this is a multi-quarter arc. The single highest-leverage,
  smallest-surface win is **Phase 1** — it converts the app from "sketch + view"
  into a real vector editor, and is what a follow-up "build phase 1" plan would
  detail.

## 6. What we deliberately reuse (don't rebuild)

`data/vector/PathDataParser.kt`, `VectorDocument.kt`, `AndroidVectorDrawableParser/
Writer`, `VectorSvgParser/Writer`, `VectorPathSampler/Simplifier`,
`VectorPreviewCanvas`, `VectorVersionDiffAnalyzer`; and from notes: `Snap.kt`,
`StrokeTransform.kt`, `NoteLayer.kt`, `EditorAction*` undo log, the viewport
controller, and the AI edit plumbing (`EditProtocol`, Tune-Up AI services).

## 7. Verification of the roadmap itself

Because nothing is built this session, "verification" = the plan is actionable:
each phase above names concrete files to touch, a reuse list so we're not
reinventing parsers/snapping/undo, an explicit ordering with the keystone called
out, and a per-phase test strategy (pure-JVM round-trip + geometry golden tests,
following the existing `app/src/test/java/...` patterns, plus on-device manual
runs). The next concrete step, when approved, is a detailed Phase 1 implementation
plan.
