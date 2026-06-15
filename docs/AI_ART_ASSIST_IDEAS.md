# AI Art-Assist Ideas — features to help non-artists make excellent art

Status: **partially built — see the reality check below** (updated 2026-06).
Captured from the pen-size-zoom-scaling planning session, then split (2026-06)
from the AndroidX Ink engine migration plan so this document can stay focused on
user-facing AI art-assist improvements. These build on the AI integration that
already ships, so each is incremental rather than a from-scratch system.

## Reality check (2026-06) — what the migration actually shipped vs. this plan

The AndroidX Ink migration ran ahead and **inverted this document's own
sequencing**. The plan called items 1–5 (non-ink, highest value-to-effort) the
"next round" and N1–N4 (ink-dependent) "best after the migration." In practice
the opposite happened: **all of N1–N4 / migration phases I0–I8 were built
(headless)**, while the high-value *non-ink* first wave was largely skipped.
Three facts every reader should hold:

1. **Built ≠ reachable.** Only **live beautify (N3/I5)** is wired into the editor
   UI (`onStrokeBeautifyAccepted` in `NoteEditorScreen`). The other three
   migration features — **select-similar/snap (N2/I7)**, **tutor/replay (N4/I8)**,
   and the **AI brush designer (N1/I4, `designBrush`)** — exist **only as
   `NoteEditorViewModel` methods with no Compose entry point**, and N2/N4 are also
   gated **default-off behind the experimental ink flag**. A user cannot reach
   them today. The remaining work for these is *UX surfacing*, not engine code.
2. **The non-ink first wave is still unbuilt.** Idea **#2 (composition critique)**
   and **#5 (palette / colour-harmony)** have **no AI implementation** (the
   deterministic `VectorQualityScorer` and local `StyleTransfer` are not these).
   **#3 (style presets)** and **#4 (text-to-vector scene)** are only *partially*
   covered by the single-icon GENERATE path (`add_path`/`add_shape`, 17.5#1).
   These were the cheapest, highest-payoff items and remain the best next round.
3. **A cross-cutting trust gap blocks all of it.** The Vector Ink & AI UX Audit's
   **Critical A1** finding — "AI edits are accepted *blind*; no on-canvas diff" —
   applies to every edit-ops feature, *including* the new snap/tutor chips, which
   ride the same `PendingEdit` / `AiEditPreviewBanner` surface. Shipping more
   edit-ops features on top of a blind-accept surface compounds the risk; the
   on-canvas visual diff (audit P0) should land before or alongside the next wave.

Accuracy note: `NoteAiService`'s modes are `AskMode { ASK, EDIT, DESIGN_BRUSH }`.
**GENERATE and REFINE are flags on EDIT** (`AskRequest.generate` / `.refine`),
not separate pipelines — the "ASK / EDIT / GENERATE / REFINE" phrasing below is
shorthand for those request shapes.

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

Status legend: ✅ shipped to users · 🟡 built but not user-reachable (no UI, or
default-off, or partial) · ❌ not built.

1. ✅ **"Beautify my stroke" live assist** — as the pen lifts, offer a one-tap
   cleanup that snaps wobbly lines to clean shapes. Extends the existing
   `smooth` / `AUTO_SHAPE` / `replace_with_shape` ops. **Shipped (I5/N3):**
   `StrokeSmoothing` + `InkBeautifier` ghost with tap-to-accept, palette toggle,
   wired through `onStrokeBeautifyAccepted`. The only first-wave idea actually
   reachable by a user.
2. ❌ **Guided composition / layout critique (ASK + vision)** — "How can I improve
   this?" returns concrete, beginner-friendly suggestions (balance, spacing,
   contrast), optionally surfaced as applicable edit-ops. **Not built.** The
   deterministic `VectorQualityScorer` (vector-tuneup tool) is *not* this — it
   scores XML, doesn't critique a hand-drawn canvas. **Highest-value gap.**
3. 🟡 **Reference-driven style presets** — extend GENERATE's style-reference
   gallery so the user picks a style ("flat", "line-art", "isometric") and the AI
   restyles the selection to match. **Partial:** the GENERATE style-reference
   gallery exists (17.5#1) but only *authors new* icons in a reference style;
   *restyling an existing selection to match a chosen style* is unbuilt. Local
   `StyleTransfer` does copy/paste of one item's style, not a named-style preset.
4. 🟡 **Text-to-vector scene/icon from a prompt** — broaden GENERATE beyond single
   icons to small multi-element scenes via `add_path` / `add_shape`. No
   image-generation dependency; output stays editable vectors. **Partial:**
   single-icon GENERATE ships (17.5#1); multi-element *scenes* (layout, grouping,
   relative placement of several objects) are unbuilt.
5. ❌ **Palette & color-harmony assistant** — AI suggests a cohesive palette and
   applies it via `recolor`. Big win for non-artists who struggle with color.
   **Not built.** `recolor` exists as an op and `aiRecolorPrompt()` recolors to a
   *user-chosen* color, but nothing *proposes a harmonious palette*.
6. ✅ **Auto-vectorize a photo (AI-guided trace)** — combine the existing
   `AiBitmapTracer` with an AI pass that picks tracing parameters and cleans the
   result into editable strokes. **Shipped:** `AiBitmapTracer` does vision
   raster→vector with a deterministic local-tracer fallback (Phase 5b).
7. 🟡 **Step-by-step "draw with me" tutor** — AI breaks a subject into construction
   shapes and ghosts them on a guide layer for the user to trace (`add_shape` on
   a dedicated layer). **Built headless (I8/N4) but unreachable:** `TutorGuide` /
   `TutorSession` / `startDrawWithMe` are ViewModel-only, gated default-off, **no
   UI entry point and no start button**. Replay's frame-by-frame animation loop is
   also still unwired.
8. 🟡 **Smart constraints / snapping suggestions** — AI proposes alignment/symmetry
   it can enforce (align edges, mirror), surfaced as edit-ops the user accepts.
   **Built headless (I7/N2) but unreachable:** `ConstraintSnap` + `proposeSnaps` /
   `selectSimilarTo` are ViewModel-only with **no UI entry point**. (The palette's
   deterministic grid-snap toggle is a *different*, older feature — not the AI
   proposal engine.)

## Sequencing notes (revised 2026-06 — supersedes the original recommendation)

The original advice ("items 1–5 first, 6–8 later") was **not** what got built —
see the reality check at the top. Given today's state, the recommended order is:

- **Wave 1 — close the trust gap, then the unbuilt high-value first wave.** Land
  the on-canvas visual diff (UX audit P0/A1) so *any* edit-ops feature can be
  accepted with confidence, then build the two genuinely-missing high-payoff
  items: **#5 palette/colour-harmony** and **#2 composition critique** (both lean
  entirely on the shipped vision-ASK + `recolor`/edit-ops infra, no ink needed).
- **Wave 2 — surface what's already built.** The expensive engine work for
  **N1 brush designer**, **N2 select-similar/snap**, and **N4 tutor/replay** is
  done and tested headless; they just need *UI entry points* (and, for N2/N4, the
  default-off ink-flag decision). This is comparatively cheap UX work with
  outsized payoff — finished features no user can currently reach.
- **Wave 3 — finish the partials.** Extend **#3** to restyle-an-existing-selection
  and **#4** to multi-element scenes; both build on the shipped GENERATE path.
- **Cross-cutting:** the device-only I2 parity gate still keeps the ink engine
  (and anything gated behind it) default-off. Surfacing N2/N4 either waits on that
  flip or needs a non-ink code path.

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

> **Build status (2026-06):** N1–N4 were all **built headless** during the ink
> migration (phases I4–I8 — see `ANDROIDX_INK_MIGRATION_PLAN.md`). What's missing
> is the *last mile to the user*: **N3 (beautify) is the only one wired into the
> UI and reachable today.** **N1 (brush designer), N2 (select-similar/snap), and
> N4 (tutor/replay)** have working `NoteEditorViewModel` methods (`designBrush`,
> `selectSimilarTo`/`proposeSnaps`/`aiRankSelection`,
> `startDrawWithMe`/`buildReplayTimeline`/`tutorNext`) but **no Compose entry
> points**, and N2/N4 are also default-off behind the experimental ink flag.
> Treat the remaining N1/N2/N4 work as **UX surfacing**, not engine work.


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

## AI feature sequencing recommendation (original — kept for context)

> ⚠️ **Superseded.** This was the *plan*; the reality check and revised
> "Sequencing notes" above describe what actually shipped and what to do next.
> The migration built the third bullet ("best after Ink") in full and skipped
> most of the first bullet ("can proceed before Ink").

- **Can proceed before AndroidX Ink:** guided composition/layout critique,
  palette & color-harmony assistant, reference-driven style presets, and
  text-to-vector scene/icon generation.
- **Can start on the current engine, then improve with Ink:** live beautify via
  existing `InkBeautifier` / `ShapeRecognizer`, later upgraded with Ink input
  smoothing and rendering previews.
- **Best after the Ink migration foundation:** AI brush designer,
  mesh-backed select-similar/snapping, stroke replay, and draw-with-me tutor.

---

# New AI ideas (2026-06 brainstorm)

Fresh candidates beyond the original eight, grounded in the current codebase
(`NoteAiService`, the `edit-ops` pipeline, vision/OCR, audio-synced v2 strokes,
multi-provider routing). Grouped by how much new infrastructure they need.

## Tier A — small, leans entirely on shipped infra

- **B1. Palette & colour-harmony assistant (idea #5, made concrete).** Extract
  the selection's current colours, ask the model (or a local colour-theory pass)
  for a cohesive scheme — complementary / analogous / triadic / monochrome — and
  apply it as a batch `recolor`. Preview as before/after swatch chips. This is the
  single highest payoff-to-effort item still unbuilt; everything it needs
  (`recolor`, `VectorCanvasJson`, the pending-edit chip surface) already ships.
- **B2. Composition critique that yields *actions*, not prose (idea #2).** A
  vision-ASK "how can I improve this?" that returns a short, structured list of
  suggestions, each with an *optional* applicable edit-op (e.g. "align these three
  icons" → `transform`, "thin the outline" → `restyle`) surfaced as the same
  accept/decline chips as AI edits. Reuses the N2/I7 `PendingEdit` plumbing.
- **B3. Auto-title & auto-tag a note/icon.** A cheap text-only ASK over the OCR
  transcript (and, for icons, the `VectorCanvasJson`) that proposes a title and
  2–4 tags, dropped straight into the existing `note_tags` table (17.1). Low cost,
  high organisational payoff; no canvas mutation.
- **B4. "Explain / describe this drawing" → alt-text.** Vision-ASK that emits a
  one-sentence description, stored as accessibility alt-text on PNG/SVG export.
  Tiny, and the only accessibility-facing AI feature.
- **B5. Tidy-pass smart defaults.** `NoteTidy` (simplify + grid-snap + merge)
  already exists locally; add an AI pre-step that *chooses* the tidy parameters
  (which strokes are shapes, sensible tolerance) instead of fixed constants.

## Tier B — moderate, one new mode or surface

- **B6. Conversational multi-turn editing (closes UX audit A7).** Pack prior
  turns into later requests so "make it bigger" → "no, just the circle" → "now
  blue" threads. Today every turn is one-shot. Pairs with re-scoping the frozen
  selection (audit A6). The chat-bubble UI already *looks* conversational.
- **B7. AI brush-designer UI (finishes N1).** The `designBrush` data path is
  done; add a prompt sheet + live preview stroke so "make me a dry-gouache brush
  with soft taper" produces a saved `BrushPreset`. Mirror the existing
  colour-picker / brush-sheet patterns. Pure surfacing of finished work.
- **B8. Diagram cleanup.** Recognise box-arrow-text clusters as a diagram and
  propose: align boxes to a grid, normalise box sizes, straighten/re-route
  connectors, even spacing. Composes `ShapeRecognizer` + `ConstraintSnap` (built)
  + the connector kind, surfaced as edit-op chips.
- **B9. Style-consistency enforcer across an icon set.** Over a multi-icon
  selection, detect inconsistent stroke weights / corner radii / palette and
  propose a normalising `restyle` batch. Builds directly on `selectSimilarTo`
  (N2, built) — give that orphaned feature a real product home.
- **B10. AI flat-fill ("colour this in").** Given line art, detect enclosed
  regions and propose flat fills (palette from B1), landing as `add_shape` /
  filled `add_path` behind the strokes. Turns outline sketches into finished art.

## Tier C — ambitious, new pipeline or dependency

- **B11. Narrated timelapse.** v2 strokes already carry audio-synced timestamps
  and `ReplayTimeline`/`TimelapseFramePlan` are built. Transcribe the recorded
  audio, align it to the stroke timeline, and export a timelapse with captions /
  voiceover — a genuinely novel artifact from infra that already exists.
- **B12. Cross-note semantic search & "find similar art."** Embed OCR text and/or
  rasterised thumbnails; let the user search notes by meaning or find visually
  similar icons. Bigger lift (embeddings store, a provider that exposes
  embeddings) but a strong differentiator for a growing library.
- **B13. Reference-image → palette / style.** Drop in a photo; extract a palette
  (feeds B1) and/or a descriptive style the GENERATE/restyle path can target —
  bridges the shipped `AiBitmapTracer` ingestion with B1/idea #3.
- **B14. Variations / "show me three."** For GENERATE and "Make real" (REFINE),
  return 2–3 alternates side-by-side so the user picks rather than re-rolling one
  at a time. Mostly an orchestration + placement-layout change over shipped modes.

## Cross-cutting prerequisite (do this first)

**On-canvas visual diff for AI edits (UX audit P0 / A1).** Every edit-ops
feature — shipped and proposed — currently commits *blind* (the banner shows only
counts; the `added`/`removed`/`modified` lists are computed but never drawn).
This is the highest-leverage AI investment in the app: it raises trust in *all*
of the above at once, and the simulation data already exists. Land it before
piling more edit-ops features onto a blind-accept surface.
