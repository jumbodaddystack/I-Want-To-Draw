# Phase 3 Implementation Plan — Pixel-Perfect Icon Production Pipeline

## Context

Phase 1 (in progress) turns the read-only `VectorDocument`
(`data/vector/VectorDocument.kt`) into an editable Bézier scene graph
(`data/vector/edit/EditablePath.kt`) driven by a node editor under
`ui/screens/vector/edit/` (state/action/reducer/viewmodel/canvas), reusing
`ViewportController`, `Snap`, and `VectorPreviewCanvas` internals. That gives us
a real vector editor but no production discipline: anchors land on arbitrary
floats, there is no icon-grid guidance, you can only see one size at a time, and
the only "icon export" path today is the **lossy raster/stroke-flatten** route
in `data/notes/NoteVectorDrawableExporter.kt`, which samples freehand strokes
into mid-point quadratics.

Phase 3 makes the editor produce **pixel-perfect, production-ready icon sets**.
It adds Material keyline-grid overlays, integer/pixel-grid snapping, a
synchronized multi-size artboard (edit one master, preview 24/48/108 dp at
once), and a **grid-quantized lossless export** that serializes straight from
the already-vector `EditablePath`/`VectorDocument` via the existing
`AndroidVectorDrawableWriter` / `VectorSvgWriter` — no averaging, no resampling —
plus a batch/set export that emits N icons × M sizes in one pass. Everything
load-bearing is pure JVM math living next to the editor, so the value is
unit-testable without a device.

**Scope of this phase (in):**
- A pure **keyline-grid geometry model** (`KeylineGrid`) for the standard
  Material templates + 24dp safe-zone padding, rendered as a canvas overlay.
- **Integer / pixel-grid snapping** added to the snap stack as a new mask bit,
  so dragged/placed anchors quantize to the icon grid.
- **Synchronized multi-size artboards**: a master `EditablePath`/`VectorDocument`
  derived into 24/48/108 dp targets with per-size optical-adjustment hooks.
- **Grid-quantized lossless export**: a pure quantization pass over an
  `EditablePath`/`VectorDocument` + a multi-size, multi-icon batch exporter
  that writes well-formed VectorDrawable + SVG documents.

**Out of scope (explicitly):** TrueType-style hinting / sub-pixel rendering;
*automatic* optical correction (we provide manual per-size hooks only); boolean
ops (Phase 2); folding the notes canvas / freehand vectorization (Phase 4);
gradients & stroke styling (Phase 5). The lossy `NoteVectorDrawableExporter`
stays for the notes/freehand path — Phase 3 does **not** delete it; it adds a
parallel lossless path for editor-authored geometry.

---

## Design

The pure model and converters live in `data/vector/` (next to the existing
writers/parser, so the same tests can re-parse output). The keyline geometry is
shared by the overlay (UI) and the snapper (pure), so it lives in `data/vector/`
too, not in `ui/`.

### Keyline grid geometry (new — `data/vector/KeylineGrid.kt`)

A pure description of a Material keyline template normalized to a square viewport
edge (default `24f`, matching `VectorViewport.viewportWidth` for an icon doc).
All numbers are viewport-space floats; the overlay scales them through
`ViewportController.worldToScreen*` exactly like the canvas does for anchors.

```kotlin
// data/vector/KeylineGrid.kt
enum class KeylineShape { SQUARE, CIRCLE, ROUNDED_SQUARE, RECT_HORIZONTAL, RECT_VERTICAL }

data class KeylineGrid(
    val edge: Float = 24f,          // viewport edge (square icon space)
    val padding: Float = 2f,        // 24dp safe-zone inset (Material: 1dp keyline → 2 on 24 grid is the live area inset; tunable)
    val shapes: Set<KeylineShape> = setOf(KeylineShape.SQUARE, KeylineShape.CIRCLE),
) {
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    data class Circle(val cx: Float, val cy: Float, val r: Float)
    data class RoundRect(val l: Float, val t: Float, val r: Float, val b: Float, val corner: Float)

    // Standard Material keyline sizes expressed on [edge]=24:
    //   square live area = 18, circle Ø = 20, vertical rect = 16×20,
    //   horizontal rect = 20×16, rounded-square = 18 with 2 corner.
    fun safeZone(): RoundRect            // [padding] inset rect = the 24dp live area
    fun keylineLines(): List<Line>       // the inset guide lines (4) + center cross (2)
    fun shapeFigures(): List<Any>        // Circle / RoundRect / Line per [shapes] (sealed in impl)
}

object KeylinePresets {
    val MATERIAL_24 = KeylineGrid(edge = 24f, padding = 1f)   // exact Material grid
    fun forViewport(vp: VectorViewport): KeylineGrid          // scale edge to vp.viewportWidth
}
```

Pure, JVM-testable: every size derives from `edge` by ratio, so a 48-edge grid
is the 24-grid ×2. No Android imports.

### Integer / pixel-grid snapping (new — `ui/components/notes/EditSnap.kt`, extends `Snap`)

`Snap` (`ui/components/notes/Snap.kt`) already has `MASK_ANGLE`/`MASK_GRID`/
`MASK_ENDPOINT` and `DEFAULT_GRID_SPACING_WORLD=32f`. Phase 3 adds an
**integer-quantize** mode. Rather than reopen the well-tested `Snap` object, add
a thin sibling `EditSnap` that defines the new bit and a pure quantizer, and
reuses `Snap.snapToGrid` for the existing behavior.

```kotlin
// ui/components/notes/EditSnap.kt
object EditSnap {
    /** Bit 3 of snapMask — quantize anchors to integer icon-grid coordinates. */
    const val MASK_PIXEL: Int = 0x8

    /** Snap (x,y) to the nearest multiple of [step] (default 1f = integer grid),
     *  unconditionally (no tolerance window — quantization, not magnetism). */
    fun quantize(x: Float, y: Float, step: Float = 1f): Snap.SnapResult

    /** Clamp into [bounds] = [minX,minY,maxX,maxY] after quantizing, so a snapped
     *  anchor can never leave the artboard. Returns integer coords on an integer grid. */
    fun quantizeInBounds(x: Float, y: Float, bounds: FloatArray, step: Float = 1f): Snap.SnapResult
}
```

The editor's anchor-commit code (Phase 1 `VectorEditReducer` `PlaceAnchor` /
`MoveSelection` / `DragHandle`) runs the world coordinate through
`Snap.snapToGrid` / `snapAngleTo` / `snapToEndpoints` as today, then — when
`snapMask and EditSnap.MASK_PIXEL != 0` — through `EditSnap.quantizeInBounds`
as the final step, so the persisted anchor is integer-aligned to the icon grid.
`step` is `viewportWidth / targetDp` for a true device-pixel grid (= `1f` when
authoring directly on the 24/48/108 viewport).

### Synchronized multi-size artboards (new — `data/vector/IconSizeSet.kt`)

The master is a single `EditablePath`/`VectorDocument` whose viewport is the
square icon space. Targets are derived by **pure uniform scale** of the
viewport + geometry; optical adjustment is a *manual* hook (a per-size affine +
optional padding override), never automatic.

```kotlin
// data/vector/IconSizeSet.kt
enum class IconTarget(val dp: Int) { MATERIAL_24(24), MEDIUM_48(48), ADAPTIVE_108(108) }

/** Manual optical-adjustment hook for one target. Identity = no change. */
data class OpticalAdjust(
    val scale: Float = 1f,          // uniform shrink/grow of live area (e.g. 0.96 for 108 trim)
    val paddingOverride: Float? = null,  // override safe-zone padding for this size
)

data class IconSizeSet(
    val master: VectorDocument,
    val targets: List<IconTarget> = IconTarget.values().toList(),
    val adjust: Map<IconTarget, OpticalAdjust> = emptyMap(),
) {
    /** Derive the per-target document: scale viewport+geometry from the master
     *  edge to target.dp, apply the target's OpticalAdjust about the center. */
    fun derive(target: IconTarget): VectorDocument
    fun deriveAll(): Map<IconTarget, VectorDocument>
}
```

`derive` mapping (master edge `E`, target edge `T = target.dp`):
- New `VectorViewport(widthDp=T, heightDp=T, viewportWidth=T, viewportHeight=T)`.
- Every coordinate `c -> (c / E) * T`, applied by walking `commands` (reuse the
  same affine helper the quantizer uses; see below).
- `OpticalAdjust.scale s` about center `T/2`: `c' = T/2 + (c - T/2) * s`.
- Style `strokeWidth` scales by `T/E * s` so stroke weight tracks size.

The editor previews all targets simultaneously by rendering each
`deriveAll()` document through `VectorPreviewCanvas` at thumbnail size next to
the live master canvas (no new render path — reuse preview).

### Grid-quantized lossless export (new — `data/vector/VectorQuantizer.kt` + `data/vector/IconSetExporter.kt`)

A pure affine + quantize pass over a `VectorDocument`, then serialization through
the **existing** writers — geometry is already vector, so there is nothing to
average or resample.

```kotlin
// data/vector/VectorQuantizer.kt
object VectorQuantizer {
    /** Map every coordinate in every path's [commands] through [fn]; paths with
     *  null commands (unparsed) pass through verbatim, pathData re-emitted. */
    fun mapCoordinates(doc: VectorDocument, fn: (x: Float, y: Float) -> Pair<Float, Float>): VectorDocument

    /** Round every absolute coordinate to the nearest multiple of [step]
     *  (default 1f = integers), clamped into the viewport box. Idempotent.
     *  H/V handled on their single axis; ArcTo radii left intact (out of scope). */
    fun quantize(doc: VectorDocument, step: Float = 1f): VectorDocument
}
```

```kotlin
// data/vector/IconSetExporter.kt — pure document → strings (no Android I/O)
object IconSetExporter {
    enum class Format { ANDROID_VECTOR_DRAWABLE, SVG }
    data class Spec(val sizes: IconSizeSet, val formats: Set<Format>, val quantize: Boolean = true)
    data class Artifact(val target: IconTarget, val format: Format, val filename: String, val content: String)

    /** Derive each target, quantize if requested, serialize via
     *  AndroidVectorDrawableWriter / VectorSvgWriter. Returns one Artifact per
     *  (target × format). Pure — testable without FileProvider. */
    fun exportSet(spec: Spec): List<Artifact>

    /** Batch: many master icons at once → flat list of artifacts. */
    fun exportBatch(specs: List<Pair<String, Spec>>): List<Artifact>  // name -> spec
}
```

The Android-side file/URI wrapper reuses `VectorTuneupExporter`
(`data/vector/VectorTuneupExporter.kt`, already a Hilt `@Singleton` writing into
the exports cache dir via `FileProvider`); we add a `exportIconSet(...)` method
there that calls `IconSetExporter.exportSet` and writes each `Artifact` to a
zip/dir, mirroring its existing `exportBundle`.

---

## Reuse, don't rebuild

- **Quantize-on-export geometry**: the writers already emit from `commands` via
  `PathDataFormatter.format` (`AndroidVectorDrawableWriter.writePath`,
  `data/vector/AndroidVectorDrawableWriter.kt:65`), and `PathDataFormatter.num`
  (`data/vector/PathDataFormatter.kt:65`) already rounds to 3dp. The quantizer
  only needs to round coordinates *in the model*; serialization is unchanged.
- **Multi-size mapping**: the size enum + square-viewport sizing already exists
  in `NoteVectorDrawableExporter.IconSize` (24/48/108,
  `data/notes/NoteVectorDrawableExporter.kt:51`) — mirror its dp values in
  `IconTarget`.
- **Snapping**: extend `Snap` (`ui/components/notes/Snap.kt`) — reuse its
  `SnapResult`, `snapToGrid`, `MASK_*` convention; `EditSnap` only adds the
  integer/pixel bit.
- **Bounded artboard**: `ViewportController.setPanBounds` / `clampOffsets`
  (`ui/components/notes/ViewportController.kt:38`) already clamps an icon canvas;
  reuse it for the master and each preview, and reuse its bounds rect as the
  quantizer's clamp box.
- **Frame/region bounds**: `NoteFrame.bounds()` (`data/model/NoteFrame.kt:50`)
  returns the `[minX,minY,maxX,maxY]` rect shape the quantizer/clamp consume.
- **Re-parse for tests**: `AndroidVectorDrawableParser.parse`
  (`data/vector/AndroidVectorDrawableParser.kt:33`) + `PathDataParser.parse`
  round-trip exported strings for geometry-stability assertions.
- **Preview render**: `ui/screens/vector/VectorPreviewCanvas.kt` for the
  multi-size thumbnails (no new renderer).
- **File/URI export plumbing**: `VectorTuneupExporter`
  (`data/vector/VectorTuneupExporter.kt`) — its `exportBundle` pattern for the
  batch writer.
- **Icon-doc helpers**: `VectorDocument.allPaths()` /
  (Phase 1) `replacePath` (`data/vector/VectorDocument.kt:231`) to walk/replace
  geometry inside `mapCoordinates`.

---

## File-by-file work list

**New (`data/vector/`)**
- `KeylineGrid.kt` — keyline template geometry + `KeylinePresets` (pure).
- `IconSizeSet.kt` — `IconTarget`, `OpticalAdjust`, `IconSizeSet.derive*`.
- `VectorQuantizer.kt` — `mapCoordinates` + `quantize` (pure, idempotent).
- `IconSetExporter.kt` — `Spec`/`Artifact`, `exportSet`/`exportBatch` (pure).

**New (`ui/components/notes/`)**
- `EditSnap.kt` — `MASK_PIXEL` + `quantize` / `quantizeInBounds` (pure).

**New (`ui/screens/vector/edit/`)** — extends Phase 1 editor
- `KeylineOverlay.kt` — Compose overlay drawing a `KeylineGrid` through
  `ViewportController.worldToScreen*` (lines, circle, rounded-square, safe zone).
- `MultiSizePreviewStrip.kt` — renders `IconSizeSet.deriveAll()` as small
  `VectorPreviewCanvas` thumbnails alongside the master canvas.
- `IconExportPanel.kt` — UI for size/format selection + "quantize on export"
  toggle + batch trigger; dispatches into `VectorTuneupExporter.exportIconSet`.

**Modified**
- `ui/screens/vector/edit/VectorEditState.kt` (Phase 1) — add
  `keyline: KeylineGrid?`, `sizeSet: IconSizeSet?`, and the `EditSnap.MASK_PIXEL`
  bit handling in `snapMask`.
- `ui/screens/vector/edit/VectorEditReducer.kt` (Phase 1) — in
  `PlaceAnchor`/`MoveSelection`/`DragHandle`, append `EditSnap.quantizeInBounds`
  as the final snap step when `MASK_PIXEL` is set; add `ToggleKeyline`,
  `SetPixelSnap`, `SetOpticalAdjust(target, adjust)` actions.
- `ui/screens/vector/edit/VectorEditCanvas.kt` (Phase 1) — compose
  `KeylineOverlay` under the anchor overlay.
- `data/vector/VectorTuneupExporter.kt` — add suspend `exportIconSet(name, spec)`
  that calls `IconSetExporter.exportSet` and writes artifacts (mirrors
  `exportBundle`).

**Reused unchanged:** `AndroidVectorDrawableWriter`, `VectorSvgWriter`,
`PathDataFormatter`, `AndroidVectorDrawableParser`, `PathDataParser`,
`ViewportController`, `Snap`, `VectorPreviewCanvas`, `NoteFrame`.

---

## Test plan (pure JVM — the bulk of the value)

`app/src/test/java/com/aichat/sandbox/data/vector/`
- `KeylineGridTest`
  - `safeZone_insets_by_padding` — 24-edge / padding 1 → rect `(1,1,23,23)`.
  - `circle_diameter_is_material_20_on_24_grid` — center `(12,12)`, r `10`.
  - `forViewport_scales_edge_to_viewportWidth` — 48 viewport → all figures ×2 of
    the 24 grid (lines, circle r, corner radius scale linearly).
  - `keylineLines_symmetric_about_center` — the cross passes through `(edge/2,*)`.
- `VectorQuantizerTest`
  - `quantize_lands_all_coords_on_integers` — every emitted coordinate, after
    `PathDataFormatter.format`, parses back to an integer (no fractional part).
  - `quantize_is_idempotent` — `quantize(quantize(doc)) == quantize(doc)`.
  - `quantize_clamps_into_viewport` — a coord at `23.9` on a 24 box rounds to
    `24`, never `25`; negative rounds to `0`.
  - `quantize_preserves_command_kinds` — `M/L/C/Z` counts unchanged; H/V quantize
    on their single axis; unparsed (null `commands`) paths pass through verbatim.
  - `quantize_step_supports_device_pixel_grid` — `step = viewportWidth/dp`.
- `IconSizeSetTest`
  - `derive_24_keeps_geometry` — identity when master edge == 24.
  - `derive_48_doubles_viewport_and_coords` — viewport `48×48`, every coord ×2,
    `strokeWidth` ×2.
  - `derive_108_scales_correctly` — viewport `108`, coord ratio `108/edge`.
  - `optical_adjust_scales_about_center` — `scale=0.9` leaves center fixed,
    moves edges inward by 10%.
  - `deriveAll_emits_one_doc_per_target_with_correct_viewport`.
- `IconSetExporterTest`
  - `exportSet_emits_one_artifact_per_size_and_format` — N targets × M formats.
  - `exported_vector_drawable_reimports_geometry_stable` — export → parse via
    `AndroidVectorDrawableParser` → compare command coords within `1e-3`
    (lossless: same vector, no flatten).
  - `quantized_export_has_only_integer_coordinates` — assert on the emitted
    `android:pathData` / SVG `d` strings.
  - `svg_and_vd_describe_same_geometry` — both formats parse to matching coords.
  - `exportBatch_emits_N_well_formed_documents` — `exportBatch` of K masters ×
    sizes → every artifact parses without `MALFORMED_*` warnings.
- `EditSnapTest` (place under `data/vector/` or co-locate with the editor tests)
  - `quantize_rounds_to_nearest_integer`.
  - `quantizeInBounds_never_leaves_artboard`.
  - `quantize_is_idempotent_for_already_integer_input`.

`app/src/test/java/com/aichat/sandbox/data/notes/`
- `IconSetExportParityTest` — assert the new lossless `IconSetExporter` output
  for an editor-authored square reproduces the **same** geometry as the master,
  documenting that it does *not* go through the lossy `NoteVectorDrawableExporter`
  mid-point-quadratic flatten (contrast fixture).

`app/src/test/java/com/aichat/sandbox/ui/screens/vector/edit/`
- `VectorEditReducerPixelSnapTest` — with `MASK_PIXEL` set, `PlaceAnchor(12.7,
  3.2)` commits `(13,3)`; clears to float when the bit is off; `SetOpticalAdjust`
  updates `sizeSet` without touching master geometry.

Run:
```bash
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.vector.KeylineGridTest" \
  --tests "com.aichat.sandbox.data.vector.VectorQuantizerTest" \
  --tests "com.aichat.sandbox.data.vector.IconSizeSetTest" \
  --tests "com.aichat.sandbox.data.vector.IconSetExporterTest" \
  --tests "com.aichat.sandbox.data.vector.EditSnapTest"
```
(The known pre-existing Android-`Color`/`Log` "not mocked" failures in
`NoteSvg/VectorDrawable/AiService` tests are unrelated; treat the suite as green
if those are the only failures.)

---

## Sequencing within the phase

1. `VectorQuantizer` + `IconSizeSet` + their tests (no UI) — pure geometry,
   de-risks the lossless claim and the size mapping first.
2. `IconSetExporter` + re-import/geometry-stable + integer-coordinate tests —
   pins the wire format against the existing parser.
3. `KeylineGrid` + tests, then `KeylineOverlay` drawn on the Phase 1 canvas.
4. `EditSnap` + reducer integration (pixel snap as final step) + reducer test.
5. `MultiSizePreviewStrip` (reuse `VectorPreviewCanvas`) + `OpticalAdjust` hooks.
6. `IconExportPanel` + `VectorTuneupExporter.exportIconSet` (file/URI) + batch.

## Risks

- **"Lossless" is only true if the model is already cubic/integer.** Mitigated:
  Phase 1 normalizes to all-cubic absolute coords on entering edit mode, and the
  quantizer rounds *before* serialization; the geometry-stable re-import test is
  the guardrail, landing in step 2.
- **Quantization can collapse near-coincident anchors** (two points rounding to
  the same integer), changing topology. Mitigated: `quantize` only moves
  coordinates, never merges; a degenerate zero-length segment is allowed (the
  writers emit it) and flagged for the optical-adjust hook, not auto-removed
  (auto-correction is out of scope).
- **Keyline padding interpretation** (Material's 1dp keyline vs. live-area inset)
  is easy to get visually wrong. Mitigated: `padding` is a tunable field with a
  `MATERIAL_24` preset, and `KeylineGridTest` pins the exact derived rects so a
  designer can adjust one constant.
- **Optical-adjust scope creep** — keep it a manual affine + padding override
  only; no shape-aware auto-trim (that is explicitly out of scope and would
  re-introduce non-determinism into a deterministic export).
