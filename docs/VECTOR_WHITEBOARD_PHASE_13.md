# Phase 13 — Boolean ops & gradients

> Shape algebra (union / subtract / intersect / exclude) on path + shape
> selections, gradient fills (linear / radial) for shapes, paths and
> stickies, and an eyedropper + style copy/paste. Everything follows the
> master plan's cross-cutting decisions: trailing optional payload fields
> decoded via `buf.hasRemaining()`, every mutation one
> `EditorAction.CompositeEdit`, no new tables, pure-JVM math under test.

## Status: 13.1–13.3 code-complete

All three sub-phases implemented and committed on this branch; build green,
JVM suites green. On-device verification is pending (runs together with
10.7 / Phase 11 / Phase 12 on real hardware). Implementation notes /
deviations from the original tracker bullets below:

- **Booleans run on the in-repo clipper, not `android.graphics.Path.op()`** —
  the master plan suggested `Path.op()`, but reading the result's geometry
  back out of a framework `Path` needs `PathIterator` (API 34) or a new
  `androidx.graphics:graphics-path` dependency, and the result would be
  untestable on the JVM. The vector tune-up lane already ships a proven,
  pure-Kotlin pipeline (`PathBoolean` → `PolygonClipper` → `CurveRefit`,
  `data/vector/edit/boolean/`, fully JVM-tested) that flattens cubics,
  clips polygons and refits the result to smooth/corner cubic anchors.
  `PathBooleanBridge` adapts notes `PathCodec.PathPayload`s to that
  pipeline and back. Same UX, strictly better testability, zero new deps.
- **Multi-ring results become one item per ring, grouped** — `PathCodec`
  is single-subpath by design (Phase 12), so a result with several
  contours lands as several closed path items sharing a fresh `groupId`
  (they select/move as one, Phase 10.4). **Holes are a documented
  limitation:** a donut (subtract a circle from a rect's middle) emits the
  hole ring as its own filled path on top rather than punching through.
  True even-odd holes need a multi-subpath codec — deferred with the other
  vector-network ideas (master plan, out of scope).
- **Gradient geometry is normalized to item bounds** (SVG
  `objectBoundingBox` semantics), so gradients survive every transform
  for free and the SVG export is exact. VectorDrawable export falls back
  to a solid fill of the first stop (the format has no XML gradient at
  our minSdk) — same spirit as the documented dash fallback.
- **The eyedropper is selection-based** ("Pick colour" lifts the selected
  item's stroke colour into the active ink tool + recents), not a
  pixel-sampling magnifier — consistent with the selection-menu surface
  everything else in this plan lives on.

## Sub-phase 13.1 — Boolean ops

### Pipeline

`NoteEditorViewModel.combineSelection(op)` collects the selected
shape/path items (≥ 2 required), sorted by zIndex ascending; the
bottom-most is the **subject** (so Subtract = "minus front", Illustrator
semantics). Shapes convert through the existing `PathConversions.fromShape`
first; open inputs are implicitly closed (booleans are area ops).
`PathBooleanBridge.combine` does payload → `EditSubpath` (relative handle
deltas → absolute control points) → `PathBoolean.combine` → result
subpaths → closed `PathPayload`s.

### Result styling & undo

- One `CompositeEdit("Union 2 paths")` (op name + input count): removes
  the eligible inputs, adds the result item(s); other selected kinds are
  untouched. Selection moves to the results.
- Results inherit the subject's stroke colour / width / layer / z /
  stroke style / cap-join; fill = the subject's fill if it had one,
  else the subject's stroke colour (an area op should produce a visible
  area). Gradients carry over from the subject.
- Empty results (e.g. disjoint intersect) are a silent no-op — nothing
  lands on the undo log.

### Files

- `ui/components/notes/PathBooleanBridge.kt` — **new**, pure
  (payload ↔ editable-model adapters + the combine entry point).
- `NoteEditorViewModel` — `selectionCanCombine()` / `combineSelection(op)`.
- `SelectionOverlay` — "Combine" submenu (Union / Subtract / Intersect /
  Exclude), gated on ≥ 2 shape/path items selected; `NoteEditorScreen`
  wires it.

### Definition of done

- Two overlapping filled rects union into one path whose area ≈ the
  union; subtract / intersect / exclude produce the right ring counts and
  areas; one undo restores the originals byte-identical.
  `PathBooleanBridgeTest` pins payload↔subpath round-trip, all four ops'
  ring counts + bounds, open-input closing, and the empty-intersect null.

## Sub-phase 13.2 — Gradient fills

### Schema

New shared trailing block, encoded/decoded by `FillStyle` (pure) and
appended after the last existing optional field of each codec —
`ShapeCodec` (after `strokeStyle`), `PathCodec` (after `capJoin`),
`StickyCodec` (after `body`):

```
[fillType:u8]              0 = solid/none (gradient block ends here)
                           1 = linear, 2 = radial
[x0:f][y0:f][x1:f][y1:f]   geometry, normalized to the item's bounds
                           (linear: start→end; radial: x0,y0 = centre,
                           x1 = radius, y1 unused)
[stopCount:u8]
per stop: [offset:f][argb:i32]
```

Absent block (or `fillType` 0) decodes as "no gradient", so every
pre-Phase-13 payload round-trips; old builds ignore the trailing bytes.
When a gradient is set, the legacy `fillArgb` is set to the first stop's
colour so old builds / fallback exporters still show a sensible fill.

### Rendering & restyle

- `GradientShaderFactory` (Android) maps the normalized geometry onto the
  item's world bounds — `LinearGradient` / `RadialGradient` shaders set
  during the fill pass only (stroke pass untouched), in `ShapeRenderer`,
  `PathRenderer` and `StickyRenderer`. Radial radius scales by
  `sqrt((w²+h²)/2)`, matching SVG `objectBoundingBox` percentage rules.
- Selection **Style** popover gains linear + radial gradient preset rows;
  `setSelectionGradient` restyles shapes, paths **and stickies** in one
  CompositeEdit. Solid fill / "No fill" clears the gradient (stickies
  keep their fill on "No fill" — a sticky is always opaque).
- Every payload round-trip path (`ItemTransformer`, duplicate, stamp
  re-key, `EditPreviewController`, stroke-style restyle) preserves the
  gradient; bounds-normalized geometry makes transforms a no-op.

### Export parity

- `NoteSvgExporter` — a `<defs>` pre-pass emits one
  `<linearGradient>`/`<radialGradient>` per gradient item
  (`gradientUnits` defaults to `objectBoundingBox`, which is exactly our
  normalized storage); fills reference `url(#gradN)`. Stops carry
  `stop-opacity` when translucent.
- `NoteVectorDrawableExporter` — solid first-stop fallback (documented).
- `VectorCanvasJson` / AI pipeline — unchanged: items report their
  `fillArgb` (first stop), and AI restyle ops write solid fills.

### Definition of done

- A gradient shape/path/sticky survives save/reload, transform,
  duplicate, and SVG export renders identically in an external viewer.
  Codec tests pin round-trip + legacy decode; `NoteSvgExporterGradientTest`
  pins the `<defs>` wire format.

## Sub-phase 13.3 — Eyedropper + style copy/paste

- `StyleTransfer` (**new**, pure) — `styleOf(item)` lifts a
  `CopiedStyle(strokeArgb, strokeWidthPx, fillArgb, gradient, strokeStyle,
  capJoin)` from any styleable kind (stroke, shape, path, sticky,
  connector, text); `applyTo(item, style)` writes the applicable subset
  back per kind (text takes colour only; stickies take fill/gradient
  only; connectors take colour/width/stroke style; etc.), returning null
  when nothing applies.
- `StyleClipboard` — process-scoped singleton mirroring `NoteClipboard`.
- Selection menu:
  - **Pick colour** (single item) — the eyedropper: copies the item's
    stroke colour into the active ink tool and the recents list.
  - **Copy style** (single styleable item) — fills the style clipboard.
  - **Paste style** (any selection, clipboard non-empty) — one
    `CompositeEdit("Paste style")` across every styleable selected item.

### Definition of done

- Copy style from a dashed gradient-filled path, paste onto a mixed
  selection: shapes/paths take fill + gradient + line style + width +
  colour, stickies take the fill, text takes the colour; one undo
  restores everything. `StyleTransferTest` pins per-kind lift/apply and
  the no-op null contract.

## JVM test roster

| Test | Pins |
| --- | --- |
| `PathBooleanBridgeTest` | payload↔EditSubpath round-trip (handle abs/rel, types), union/subtract/intersect/exclude ring counts + bounds/area, open-input close, empty-intersect → null |
| `ShapeCodecGradientTest` | gradient round-trip on shapes, legacy payload (no block) decodes null, fillType 0 decodes null, stop list fidelity |
| `PathCodecTest` (extended) | gradient round-trip after capJoin, legacy decode, transform preserves gradient |
| `StickyCodecTest` (extended) | gradient round-trip after body, legacy decode |
| `NoteSvgExporterGradientTest` | `<defs>` linear + radial wire format, `url(#…)` fill refs, stop-opacity |
| `StyleTransferTest` | per-kind styleOf/applyTo, cross-kind subsets, null for unstyleable kinds, byte-identical no-op skip |
