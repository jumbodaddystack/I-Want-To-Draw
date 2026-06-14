# AI Art-Assist Ideas — features to help non-artists make excellent art

Status: **backlog / ideation** (not yet scheduled). Captured from the
pen-size-zoom-scaling planning session. These build on the AI integration that
already ships, so each is incremental rather than a from-scratch system.

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
