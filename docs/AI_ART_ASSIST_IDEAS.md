# AI Art-Assist Implementation Plan

Status: **reconciled with the app codebase as of 2026-06-16**.

This document supersedes the older brainstorm-style notes in this file. It keeps
only the AI art-assist work that still makes sense for the app as it exists now,
then breaks the roadmap into phased tasks that can be implemented over multiple
sessions.

## 0. Codebase reality check

### Already shipped and user-reachable

- **Live beautify on pen lift** is wired into the editor. `DrawingSurface` exposes
  `onStrokeBeautifyAccepted`, and `NoteEditorScreen` forwards that callback to
  `NoteEditorViewModel.onStrokeBeautifyAccepted`.
- **On-canvas AI edit diff preview is already built.** The previous plan treated
  the UX audit's A1/P0 visual-diff gap as a blocker. That is now fixed: staged AI
  edits render through `AiEditDiffOverlay` before Accept/Reject, with green added
  items, red removed items, and amber modified items. The banner also shows the
  same legend and partial-failure summary.
- **Single-icon GENERATE and Make-real REFINE exist.** They are not separate
  service modes; they are EDIT requests with `AskRequest.generate` and
  `AskRequest.refine` flags.
- **Auto-vectorize photo / AI trace exists.** `AiBitmapTracer` is present with an
  AI-guided path and deterministic local fallback.

### Built headless but not yet productized

- **AI brush designer (N1)** has a service mode (`AskMode.DESIGN_BRUSH`), a
  `designBrush(prompt, turnId)` ViewModel entry point, brush-spec parsing, and
  preset persistence. It still lacks a Compose entry point, prompt sheet, preview
  stroke, and save/apply flow.
- **Select-similar + snap suggestions (N2 / idea #8)** exist in the ViewModel as
  `selectSimilarTo`, `proposeSnaps`, and `aiRankSelection`. They are gated behind
  `inkAuthoring` and have no visible magic-wand/snap UI entry point.
- **Draw-with-me tutor + replay (N4 / idea #7)** exists as `startDrawWithMe`,
  `buildReplayTimeline`, `TutorGuide`, and `TutorSession`. It is also gated behind
  `inkAuthoring` and lacks a start UI, tutor controls, replay playhead UI, and
  export UX.

### Still missing or only partially covered

- **Composition critique** is still missing. `VectorQualityScorer` is not a
  substitute: it scores vector XML readiness, not a user's hand-drawn note via
  vision with beginner-friendly guidance.
- **Palette / color-harmony assistant** is still missing. The `recolor` edit-op
  and user-chosen `aiRecolorPrompt()` exist, but no feature proposes harmonious
  palettes.
- **Named style preset restyling** is partial. Local `StyleTransfer` copies style
  from one item to another, and GENERATE can use style-reference icons when
  authoring new icons. There is no user-facing “restyle this selection as flat /
  line-art / isometric” flow.
- **Prompt-to-vector scene generation** is partial. The app can generate a single
  icon/vector from a prompt, but not a small multi-object scene with grouped,
  relatively placed elements and a layout plan.
- **Conversational editing context** remains limited. The AI sheet presents a
  conversation and lets the user re-scope subsequent turns, but edit requests are
  still effectively one-shot unless explicit history packing is added.

## 1. Implementation principles

1. **Prefer surfacing existing headless work before building new engines.** N1,
   N2, and N4 already paid most of their data-path cost.
2. **Every canvas mutation must stage as `PendingEdit`.** New feature work should
   use the existing `EditPreviewController` + `AiEditDiffOverlay` +
   `AiEditPreviewBanner` accept/reject path instead of committing directly.
3. **Keep non-mutating AI separate from edit-ops.** Critiques, titles, tags, and
   alt text should return structured prose/metadata and should not pretend to be
   edits unless there is a concrete op the user can preview.
4. **Do not make AndroidX Ink default-on from an art-assist feature.** Features
   already gated by `inkAuthoring` can be surfaced as experimental, or receive a
   non-ink fallback in a separate task.
5. **Design for multiple short sessions.** Each phase below has a shippable end
   state and a task tracker.

## 2. Phased roadmap and task tracker

Legend: `[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked/needs a
product decision.

---

## Phase 1 — Re-baseline and harden the shared AI edit surface

**Goal:** make sure all future AI art-assist features use the already-shipped
preview/diff infrastructure consistently.

**Why first:** the old plan incorrectly listed the visual diff as missing. Before
adding more buttons, confirm the current diff works for AI-authored edits,
locally-authored snap/tidy edits, and generated geometry.

### Tasks

- [ ] Add/verify JVM coverage for `AiEditDiffOverlay`-adjacent behavior at the
  simulation layer: added, removed, modified, and skipped edit buckets.
- [ ] Add a small UI smoke test or screenshot test for the banner legend counts
  and partial-failure message, if the project test stack supports it.
- [ ] Audit every existing art-assist entry point and document whether it stages
  `PendingEdit`, mutates locally, or only returns prose.
- [ ] Ensure locally-authored snap/tidy docs use `stageLocalEdit` so they receive
  the same visual diff as model-authored edits.
- [ ] Update stale comments in `NoteEditorScreen` that still describe the visual
  diff as a future follow-up.

### Acceptance criteria

- AI EDIT, GENERATE, REFINE, and locally-staged snap/tidy all preview through the
  same accept/reject surface.
- The user can tell what will be added, removed, and modified before accepting.
- No new AI art-assist feature bypasses `PendingEdit` for canvas mutation.

---

## Phase 2 — Ship palette & color-harmony assistant

**Goal:** help non-artists choose and apply cohesive colors.

**MVP behavior:** user selects strokes/shapes or uses whole note scope, taps
“Palette help”, chooses a scheme type, and receives 3–6 swatches plus an optional
previewable `recolor` batch.

### Tasks

- [ ] Add a structured palette response contract, e.g. `PaletteSuggestion` with
  scheme name, swatches, rationale, and optional id-to-color assignments.
- [ ] Add prompt text to ask the model for beginner-friendly color harmony using
  current canvas colors from `VectorCanvasJson` and, when available, the raster
  preview.
- [ ] Add a local color-theory fallback for simple analogous/complementary/triadic
  palettes so the feature can still suggest swatches when AI is unavailable.
- [ ] Add a ViewModel entry point such as `suggestPalette(scope, scheme)` that can
  either return swatches only or stage `recolor` edit-ops.
- [ ] Add UI in the AI sheet or a compact art-assist menu: scheme chips,
  swatches, “Preview recolor”, “Apply”, and “Copy palette”.
- [ ] Ensure recolor preview goes through `PendingEdit` and `AiEditDiffOverlay`.
- [ ] Add tests for parser validation, fallback palette generation, and recolor
  op construction.

### Acceptance criteria

- A user can get a palette suggestion without changing the canvas.
- Applying the palette is previewable and rejectable.
- Existing colors and locked layers are respected.

---

## Phase 3 — Ship guided composition / layout critique

**Goal:** answer “How can I improve this?” with concrete, beginner-friendly
feedback and optional previewable actions.

**MVP behavior:** the feature returns 3–5 suggestions. Each suggestion has a
plain-language reason, confidence/effort label, and optionally an action button
that stages edit-ops such as align, scale, simplify, restyle, or recolor.

### Tasks

- [ ] Define a structured critique schema: `summary`, `suggestions[]`,
  `principle`, `why`, `optionalEditOps`, and `safetyNotes`.
- [ ] Add a critique prompt that uses vision when available and falls back to
  `VectorCanvasJson`/OCR when not.
- [ ] Add parser/validator logic that tolerates prose-only suggestions and rejects
  unsafe or unparseable edit-op payloads.
- [ ] Add a ViewModel entry point such as `requestCompositionCritique(scope)`.
- [ ] Add UI to display suggestions as cards with “Preview fix” only when an
  edit-op is valid.
- [ ] Route “Preview fix” through the existing staged edit surface.
- [ ] Add tests for prose-only critique, mixed valid/invalid actions, and locked
  layer handling.

### Acceptance criteria

- The feature is useful even when no edit-op is returned.
- Suggested edits are previewed on canvas and can be rejected.
- The model cannot silently apply broad layout changes.

---

## Phase 4 — Surface AI brush designer (N1)

**Goal:** turn the existing `DESIGN_BRUSH` data path into a user-facing brush
creation flow.

**MVP behavior:** user opens a brush designer sheet, types “dry gouache with soft
edges”, sees a sample stroke, saves the generated brush as a user preset, and can
select it from the brush palette.

### Tasks

- [ ] Add a Brush Designer entry point from the brush palette or AI sheet.
- [ ] Add prompt input with example chips: “inky brush pen”, “dry gouache”,
  “soft marker”, “scratchy pencil”.
- [ ] Wire the UI to `NoteEditorViewModel.designBrush(prompt, turnId)`.
- [ ] Show streaming/progress state in the sheet.
- [ ] Render a deterministic preview stroke for the returned `BrushPreset`.
- [ ] Add save/cancel controls and confirm the preset appears in the palette.
- [ ] Add validation UX for unsupported spec fields or parser failure.
- [ ] Add tests for brush-spec parsing, preset persistence, and duplicate names.

### Acceptance criteria

- No canvas mutation occurs during brush design.
- Saved brushes are reusable, editable presets.
- Failure states are understandable and do not leave partial presets behind.

---

## Phase 5 — Surface select-similar and snap suggestions (N2 / idea #8)

**Goal:** expose the existing magic-wand and constraint-snap engines safely.

**MVP behavior:** with the experimental ink engine enabled, the user can tap a
magic-wand action for a selected stroke to select similar strokes, then preview
snap/alignment suggestions as normal staged edits.

### Tasks

- [ ] Decide product gating: experimental-only while `inkAuthoring` is off by
  default, or add a non-ink fallback for simple bounds-based selection/snapping.
- [ ] Add a visible Magic Wand action when a single stroke is selected and
  `inkSelectionToolsEnabled()` is true.
- [ ] Wire the action to `selectSimilarTo(itemId)` and update selection chrome.
- [ ] Add threshold/strictness UI only after the one-tap default feels good.
- [ ] Add “Snap selection” or “Align suggestion” action for multi-selection when
  `inkSelectionToolsEnabled()` is true.
- [ ] Wire snap action to `proposeSnaps(selection)`.
- [ ] Optionally add “Ask AI to group/refine selection” wired to
  `aiRankSelection(extraInstruction)`.
- [ ] Add UI copy explaining why the controls are unavailable when ink is off.
- [ ] Add tests for selection expansion, locked layers, and staged snap previews.

### Acceptance criteria

- Select-similar changes selection only; it does not mutate the canvas.
- Snap suggestions stage as previewable `PendingEdit` transforms.
- Hidden experimental gating is visible and understandable to users.

---

## Phase 6 — Surface draw-with-me tutor and replay (N4 / idea #7)

**Goal:** let users learn from generated construction guides and replay existing
strokes.

**MVP behavior:** user enters “draw a fox”, reviews generated guide strokes in the
normal diff preview, accepts them onto a ghost guide layer, and steps through the
construction with Next/Back/Skip/Redo controls.

### Tasks

- [ ] Decide product gating: experimental-only behind `inkAuthoring`, or split a
  non-ink guide-layer MVP from ink replay/export work.
- [ ] Add a “Draw with me” entry point in the AI sheet or art-assist menu.
- [ ] Wire prompt submission to `startDrawWithMe(prompt)`.
- [ ] Add tutor controls bound to `tutorNext`, `tutorBack`, `tutorSkip`,
  `tutorRedo`, and `endTutor`.
- [ ] Ensure `tutorHiddenIds` is observed by the canvas so unrevealed guide items
  stay hidden.
- [ ] Add a replay playhead UI for `buildReplayTimeline()`.
- [ ] Defer video/GIF export until replay playback is shippable.
- [ ] Add tests for guide-layer creation, accept flow, step transitions, and
  hidden-item filtering.

### Acceptance criteria

- Generated tutor geometry is previewed before it becomes a guide layer.
- Guide strokes are editable and visually distinct from user strokes.
- Tutor controls are recoverable: Back/Skip/Redo/End never corrupt the note.

---

## Phase 7 — Finish named style preset restyling

**Goal:** let users restyle existing selections into named visual styles, not just
copy local style or generate new icons from references.

**MVP behavior:** user selects items, chooses “Flat icon”, “Line art”,
“Isometric”, or “Sticker”, previews restyle ops, and accepts/rejects.

### Tasks

- [ ] Define a small curated preset catalog with prompt text and constraints for
  each style.
- [ ] Add style preset chips to the AI sheet, selection toolbar, or art-assist
  menu.
- [ ] Route presets through EDIT mode with selection scope and `restyle`,
  `recolor`, `smooth`, `simplify`, and `replace_with_shape` ops allowed.
- [ ] Keep `StyleTransfer` as a separate local “copy style from selection” tool;
  do not conflate it with named AI presets.
- [ ] Add validation to discourage adding new subject matter during restyle.
- [ ] Add tests for preset prompt construction and valid/invalid restyle docs.

### Acceptance criteria

- Existing geometry remains recognizably the same subject.
- Presets are previewable and rejectable.
- Style-copy and AI named presets are clearly different in UI copy.

---

## Phase 8 — Expand prompt-to-vector from icons to small scenes

**Goal:** broaden GENERATE from a single icon to a compact editable scene made of
multiple grouped elements.

**MVP behavior:** user prompts “small campsite at night”; the model returns a
scene plan with groups/layers and editable `add_path`/`add_shape` geometry that
lands inside the current viewport or selected frame.

### Tasks

- [ ] Add a `generateScene` request variant or prompt flag over existing EDIT +
  `generate=true` plumbing.
- [ ] Extend generation prompt constraints for multi-object layout, grouping,
  relative placement, and layer names.
- [ ] Ensure generated object ids map cleanly to groups/layers in the edit-op
  parser and preview simulation.
- [ ] Add placement UI: current viewport, selected frame, or icon bounds.
- [ ] Add optional “simple / detailed” complexity chips.
- [ ] Add tests for grouped add ops, placement transforms, and scene-size limits.

### Acceptance criteria

- Scenes remain editable vectors, not raster images.
- Generated geometry is bounded and does not appear far off-canvas.
- The preview clearly shows all added elements before acceptance.

---

## Phase 9 — Metadata and accessibility helpers

**Goal:** add low-risk, non-mutating AI helpers that improve organization and
exports.

### Candidate tasks

- [ ] Auto-title and auto-tag notes/icons from OCR, `VectorCanvasJson`, and/or
  raster preview.
- [ ] Generate alt text / description for PNG and SVG export.
- [ ] Add settings for auto-suggest vs manual-trigger behavior.
- [ ] Store generated metadata in existing note/tag/export metadata structures;
  add migrations only if no suitable fields exist.
- [ ] Add tests for title/tag validation, duplicate tags, and export metadata.

### Acceptance criteria

- Metadata suggestions never alter canvas geometry.
- Users can accept, edit, or discard suggested text.
- Exported alt text is short, accurate, and optional.

---

## Phase 10 — Longer-horizon AI art-assist ideas

Defer these until the core roadmap above is stable:

- **Conversational multi-turn editing:** pack previous turns and edit outcomes so
  “make it bigger”, “no, just the circle”, and “now blue” resolve correctly.
- **Diagram cleanup:** recognize boxes/arrows/text and use existing snapping,
  connector routing, and restyle ops to normalize diagrams.
- **Style consistency across an icon set:** detect inconsistent stroke weights,
  corner radii, and palettes across selected icons.
- **AI flat-fill / color-this-in:** detect enclosed regions in line art and add
  filled shapes behind strokes.
- **Narrated timelapse:** align audio transcript/captions to replay timelines.
- **Semantic art search:** embeddings for OCR text and visual thumbnails.
- **Reference-image palette/style:** extract palette and style descriptors from a
  photo to feed palette assistance and style presets.
- **Variations / show me three:** orchestrate multiple GENERATE/REFINE candidates
  side-by-side for user selection.

## 3. Suggested session slicing

Use this sequence for multi-session implementation:

1. **Session A:** Phase 1 audit/comment fixes and tests.
2. **Session B:** Phase 2 palette response contract + local fallback + parser
   tests.
3. **Session C:** Phase 2 palette UI and staged recolor preview.
4. **Session D:** Phase 3 critique schema + prompt/parser tests.
5. **Session E:** Phase 3 critique UI and preview-action cards.
6. **Session F:** Phase 4 brush designer UI over existing `designBrush` path.
7. **Session G:** Phase 5 magic-wand/snap UI over existing headless N2 path.
8. **Session H:** Phase 6 tutor start/step UI over existing headless N4 path.
9. **Session I:** Phase 7 named style presets.
10. **Session J:** Phase 8 scene generation.
11. **Session K:** Phase 9 metadata/accessibility helpers.

## 4. Out-of-scope for this plan

- Replacing the drawing engine or flipping AndroidX Ink default-on.
- Raster image generation as the primary output for these features.
- Any feature that commits canvas changes without a previewable edit-op or an
  explicit non-canvas metadata confirmation.
