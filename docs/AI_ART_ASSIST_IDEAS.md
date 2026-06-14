# AI Art-Assist Ideas — features to help non-artists make excellent art

Status: **backlog / ideation** (not yet scheduled). Captured from the
pen-size-zoom-scaling planning session, then split (2026-06) from the AndroidX
Ink engine migration plan so this document can stay focused on user-facing AI
art-assist improvements. These build on the AI integration that already ships,
so each is incremental rather than a from-scratch system.

## Existing stack these build on

- `NoteAiService` — ASK / EDIT / GENERATE / REFINE pipelines.
- `edit-ops` schema via `EditOpsParser` — `transform`, `recolor`, `restyle`,
  `replace_with_shape`, `smooth`, `simplify`, `merge_paths`, `add_path`,
  `add_shape`, `delete`, `set_layer`, `group`.
- `VectorCanvasJson` — compact JSON view of the canvas the model can read.
- Vision input via `NoteRasterizer` (PNG); OCR fallback for non-vision models.
- Multi-provider routing (`ApiClient` + OpenAI/Anthropic/Gemini adapters),
  capability flags in `ModelCapabilities`.
- Existing canned actions (`CannedEditPrompts`): AI clean-up, auto-shape,
  simplify, flat-style, add-detail, recolor; plus "Make real" sketch refine.

### Current inking engine (custom, mature — what ink would touch)

The drawing core is hand-built and production-grade. Any ink adoption has to
respect these as the incumbents:

- **Input capture** — `DrawingSurface` (plain `View`): `MotionEvent` history
  iteration for S-Pen oversampling, screen↔world transform via
  `ViewportController`, one-frame look-ahead from
  `androidx.input:motionprediction`.
- **Rendering** — `StrokeRenderer.drawStrokePath` (quadratic-Bézier spline
  between sample midpoints, per-segment variable width/alpha); off-screen
  baking via `NoteRasterizer`.
- **Tool dynamics** — `ToolDynamics` maps pressure/tilt → width/alpha per tool
  (pen / pencil / highlighter / marker).
- **Brushes** — `BrushPreset` (color, width, opacity, taper, jitter,
  pressure-curve, texture) + procedural `TextureRegistry`
  (smooth/charcoal/watercolor/marker ALPHA_8 tiles).
- **Geometry** — `HitTest` (point-to-segment, AABB), `LassoController`
  (polygon containment), area/stroke eraser, `ShapeRecognizer` (line / rect /
  ellipse / polygon fit on hold-to-snap), `InkBeautifier` (RDP + Chaikin),
  `StrokeOutliner` (variable-width outline for SVG export).
- **Storage** — `StrokeCodec` v1 `[x,y,pressure,tilt]` / v2
  `[x,y,pressure,tilt,timestamp]` little-endian binary in `NoteItem.payload`
  (Room). Timestamps (v2) sync strokes to audio recordings.

## Ideas (roughly easiest → most ambitious)

1. **"Beautify my stroke" live assist** — as the pen lifts, offer a one-tap
   cleanup that snaps wobbly lines to clean shapes. Extends the existing
   `smooth` / `AUTO_SHAPE` / `replace_with_shape` ops. Highest non-artist payoff
   for the least new code.
2. **Guided composition / layout critique (ASK + vision)** — "How can I improve
   this?" returns concrete, beginner-friendly suggestions (balance, spacing,
   contrast), optionally surfaced as applicable edit-ops.
3. **Reference-driven style presets** — extend GENERATE's style-reference gallery
   so the user picks a style ("flat", "line-art", "isometric") and the AI
   restyles the selection to match.
4. **Text-to-vector scene/icon from a prompt** — broaden GENERATE beyond single
   icons to small multi-element scenes via `add_path` / `add_shape`. No
   image-generation dependency; output stays editable vectors.
5. **Palette & color-harmony assistant** — AI suggests a cohesive palette and
   applies it via `recolor`. Big win for non-artists who struggle with color.
6. **Auto-vectorize a photo (AI-guided trace)** — combine the existing
   `AiBitmapTracer` with an AI pass that picks tracing parameters and cleans the
   result into editable strokes.
7. **Step-by-step "draw with me" tutor** — AI breaks a subject into construction
   shapes and ghosts them on a guide layer for the user to trace (`add_shape` on
   a dedicated layer).
8. **Smart constraints / snapping suggestions** — AI proposes alignment/symmetry
   it can enforce (align edges, mirror), surfaced as edit-ops the user accepts.

## Sequencing notes

- **Items 1–5** are the highest value-to-effort and lean entirely on existing
  infrastructure — good candidates for the next round.
- **Items 6–8** are larger (new tracing/tutor/constraint flows) and warrant their
  own phase docs.

## Dependency note: AndroidX Ink migration

Some ideas become substantially better after the AndroidX Ink migration,
especially live beautify, mesh-backed selection/snapping, stroke replay,
draw-with-me tutor mode, and AI brush design. However, the first wave of AI
art-assist features — guided critique, palette assistance, style presets, and
text-to-vector generation — can proceed on the current drawing engine.

See [`ANDROIDX_INK_MIGRATION_PLAN.md`](ANDROIDX_INK_MIGRATION_PLAN.md) for the
engine migration plan, including `InkInterop`, low-latency authoring, brush
mapping, mesh-backed geometry, storage decisions, and default-on parity gates.

---

# Ink-enhanced AI feature candidates

These are product ideas that should remain in the AI art-assist roadmap, but
that either depend on or become more compelling after the AndroidX Ink migration.
Each names the Ink capability it leans on and how it threads through the
existing AI pipeline.


### N1. AI brush designer (text → brush)
- **What:** "Make me a dry-gouache brush" / "an inky brush pen with taper" →
  the model emits brush parameters; we build a `Brush`/`BrushFamily` and save it
  as a new user-scope `BrushPreset`. Output is a **reusable, editable brush**,
  not a one-off render.
- **Ink:** `ink-brush` 1.1 programmatic `Brush.Builder` / `BrushFamily`,
  `BrushPaint.TextureLayer`, randomized behaviors; `BrushFamily.decode()` for
  shareable brush files.
- **Pipeline:** new `NoteAiService` mode (`DESIGN_BRUSH`) returning a small
  brush-spec JSON (validated like `edit-ops`); adapter maps spec → ink brush →
  `BrushPreset`. No canvas mutation, so it's low-risk to ship.
- **Sequencing:** split this into (a) a deterministic `BrushPreset → Brush`
  adapter using stable APIs, (b) an isolated experimental path for 1.1
  programmable brushes, and only then (c) the AI brush-designer UI. That keeps an
  alpha API out of the core authoring migration.
- **Why now:** the public brush API (1.1) is what makes text→brush tractable
  without reverse-engineering a proto, but it remains optional until stable.

### N2. Magic-wand "select similar" + constraint/snap engine
- **What:** (a) tap a stroke → find geometrically/stylistically similar strokes
  for batch `recolor` / `restyle` / `delete`; (b) AI proposes
  alignment / symmetry / even-spacing and the engine **enforces** it (idea #8).
- **Ink:** `ink-geometry` `PartitionedMesh` intersection/coverage for "similar
  shape / overlapping / inside region"; lasso→mesh for the selection itself.
- **Pipeline:** geometry runs locally (fast, offline); AI optionally ranks
  "which of these belong together" and emits `group` / `transform` / `recolor`
  edit-ops the user accepts. Snapping surfaces as accept/decline chips, same UX
  as existing AI edit suggestions.
- **Prerequisite:** add a mesh/cache/index layer before product UX: per-stroke
  mesh cache, invalidation on transform/restyle/delete, bounding-box or spatial
  prefilter, and a reliable mapping from mesh hits back to note item IDs/layers.

### N3. Live beautify via ink smoothing  (upgrade of idea #1)
- **What:** on pen-lift, offer a one-tap clean snap. ink's input smoothing +
  our `InkBeautifier` (RDP + Chaikin) + `ShapeRecognizer` combine for a
  noticeably cleaner result than today.
- **Ink:** `ink-authoring` input smoothing on the live batch; optionally render
  the candidate via `CanvasStrokeRenderer` for a crisp preview.
- **Pipeline:** purely local for the geometric clean; AI only consulted for the
  ambiguous "did you mean this shape?" cases (reuses `AUTO_SHAPE` /
  `replace_with_shape`). Ghost-preview the beautified stroke; tap to accept.

### N4. Stroke replay / "draw with me"  (supports ideas #7)
- **What:** replay drawing order as an animation — timelapse export, and a
  **tutor** mode that ghosts construction strokes for the user to trace.
- **Ink:** `StrokeInputBatch` **timestamps** drive ordered playback; ink
  rendering animates partial strokes; ties into our v2 codec timestamps already
  synced to audio.
- **Pipeline:** AI (GENERATE) produces the construction strokes on a dedicated
  guide layer; replay plays them back at teaching pace. Export reuses
  `NoteRasterizer` frames → video/GIF.
- **Hard part:** generated stroke quality is the risk, not partial-stroke
  rendering. The tutor needs simple construction primitives, sensible stroke
  order, guide-layer editability, low clutter, and controls to step / skip /
  redo instructions.

## AI feature sequencing recommendation

- **Can proceed before AndroidX Ink:** guided composition/layout critique,
  palette & color-harmony assistant, reference-driven style presets, and
  text-to-vector scene/icon generation.
- **Can start on the current engine, then improve with Ink:** live beautify via
  existing `InkBeautifier` / `ShapeRecognizer`, later upgraded with Ink input
  smoothing and rendering previews.
- **Best after the Ink migration foundation:** AI brush designer,
  mesh-backed select-similar/snapping, stroke replay, and draw-with-me tutor.
