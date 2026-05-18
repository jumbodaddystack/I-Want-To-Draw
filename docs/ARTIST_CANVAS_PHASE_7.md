# Phase 7 — AI vector edit pipeline

> The model can already see the note as a PNG. This phase gives it the **vector JSON alongside the image** and a typed response format it uses to *edit existing items only*. No agent-drawable canvas (not yet). Every AI edit lands as a dry-run preview the user accepts; on accept it becomes one undo entry.

Parent plan: [`ARTIST_CANVAS_PLAN.md`](./ARTIST_CANVAS_PLAN.md).

## Design summary

Existing `ASK` mode (sub-phases 2.5 / 2.6 of `STYLUS_NOTES_PLAN.md`):

- Vision model → `[image, prompt]` → free-form text reply.
- Non-vision model → `[ocrText, prompt]` → free-form text reply.

New `EDIT` mode (this phase):

- Vision model → `[image, vector_json, prompt, edit_protocol_system_message]` → JSON `edit-ops` reply.
- Non-vision model → `[ocrText, vector_json, prompt, edit_protocol_system_message]` → JSON `edit-ops` reply.

Both branches converge on a parser that produces an `EditOps` list. The applier validates the ops against the current item set, builds a preview overlay, and waits for user confirmation. On accept, the ops become one `EditorAction.CompositeEdit` in the undo stack.

The model is restricted to operations on **existing** items. It cannot author new freehand strokes from scratch. It *can*:

- Transform existing items (move / scale / rotate).
- Recolor / restyle items.
- Replace a stroke with a shape (`auto-shape`).
- Replace a stroke with a smoothed version (`clean-up`).
- Delete items.
- Group / regroup (layer assignment).

It cannot:

- `add_stroke` with arbitrary geometry.
- Modify items that are not in the current selection / context.
- Touch items on locked or hidden layers.

## Sub-phase 7.1 — Vector JSON serializer

### Scope

Build a compact, model-friendly JSON view of an item set. Stable enough that round-tripping `items → JSON → ops → items` works; small enough to fit a typical selection inside a 200 KB prompt.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/notes/VectorCanvasJson.kt` — `serialize(items, bounds, layers): String`, `parseEditOps(json): Result<List<EditOp>>`.
- Unit tests for serialization stability, parser fuzz cases, max-payload truncation.

### Wire format

```json
{
  "schema": 1,
  "bounds": { "minX": 12.0, "minY": 8.0, "maxX": 920.5, "maxY": 1200.0 },
  "layers": [
    { "id": "L1", "name": "Ink",        "ordinal": 0,  "opacity": 1.0 },
    { "id": "L2", "name": "Highlights", "ordinal": -1, "opacity": 0.5 }
  ],
  "items": [
    {
      "id": "s_001",
      "kind": "stroke",
      "tool": "pen",
      "layer": "L1",
      "color": "#1A1A1A",
      "width": 2.4,
      "points": [[12.0,8.0,0.55,0.0],[14.2,9.1,0.62,0.0], ... ]
    },
    {
      "id": "h_001",
      "kind": "shape",
      "type": "rect",
      "layer": "L1",
      "color": "#1A1A1A",
      "width": 2.0,
      "fill": null,
      "geometry": { "x0": 100.0, "y0": 80.0, "x1": 300.0, "y1": 220.0, "r": 8.0 }
    },
    {
      "id": "i_001",
      "kind": "image",
      "layer": "L1",
      "path": "img/ref-pose.jpg",
      "bbox": { "x": 400, "y": 60, "w": 300, "h": 450 }
    }
  ]
}
```

### Step-by-step

1. Decide per-kind float precision: strokes round coords to 1 decimal, pressure / tilt to 2. Shapes / images keep full precision.
2. Truncate `points` arrays: if `len > 64`, sample every Nth point so the JSON length stays bounded. Document the lossy step — the **image** is the lossless representation; JSON is the addressable index. Record `pointsDownsampled: true` on truncated strokes so the model can decide whether to ask for the full data.
3. Soft-cap the total JSON at 180 KB. If exceeded, drop strokes by `(layer.ordinal asc, points.size desc)` (least-visible / largest first) until under cap.
4. ID format: short prefixes per kind (`s_`, `h_`, `i_`) + sequential index, **not** the full UUID — saves a lot of bytes and the model handles short IDs better. Maintain a `idMap: Map<String, String>` so the parser (7.2) can translate back to UUIDs.

### Definition of done

- A 50-stroke note serializes to < 30 KB.
- A 500-stroke note serializes to < 180 KB after downsampling.
- Round-trip: `serialize → deserialize → re-serialize` is byte-identical for the same inputs.
- Unit tests cover edge cases: empty selection, single image, mixed kinds, locked layers excluded.

### Non-goals

- Streaming serialization.
- Per-segment width / opacity output (we serialize `baseWidthPx` only; the model doesn't need per-segment dynamics for the supported ops).

### Risks

- **ID stability.** If `serialize` reassigns short IDs every call, the model can't reference items across multi-turn. We're one-shot so this is fine, but document it.

---

## Sub-phase 7.2 — Edit protocol

### Scope

Define the JSON the model returns, and the system message that teaches it the format. Conservative grammar — easier to validate, harder for the model to drift.

### Wire format

The model responds with **only** a fenced ` ```edit-ops ` JSON block (system message enforces this):

```json
{
  "schema": 1,
  "summary": "Cleaned 3 freehand strokes into smooth shapes.",
  "ops": [
    { "op": "transform", "ids": ["s_001"], "matrix": [1,0,0, 0,1,0, 0,0,1] },
    { "op": "recolor",   "ids": ["s_002", "s_003"], "color": "#1A1A1A" },
    { "op": "replace_with_shape",
      "id": "s_004",
      "shape": { "type": "ellipse", "cx": 120, "cy": 80, "rx": 40, "ry": 30, "rotation": 0 } },
    { "op": "smooth", "ids": ["s_005"], "amount": 0.5 },
    { "op": "delete", "ids": ["s_006"] },
    { "op": "set_layer", "ids": ["s_007", "s_008"], "layer": "L2" }
  ]
}
```

### Supported ops

| op | Inputs | Effect |
| --- | --- | --- |
| `transform` | `ids`, `matrix` (3×3 affine row-major) | Bakes the matrix into each item, identical to `EditorAction.TransformItems`. |
| `recolor` | `ids`, `color` | Overwrites `NoteItem.colorArgb`. |
| `restyle` | `ids`, `width?`, `opacity?` | Overwrites the listed style fields. |
| `replace_with_shape` | `id`, `shape` | Deletes the source stroke, inserts a new `kind=shape` item with the same bounds. |
| `smooth` | `ids`, `amount ∈ [0,1]` | Resamples stroke points through Chaikin smoothing `floor(amount * 4)` iterations. |
| `simplify` | `ids`, `tolerance` | Ramer-Douglas-Peucker simplification at `tolerance` world units. |
| `delete` | `ids` | Removes items. |
| `set_layer` | `ids`, `layer` | Moves items between layers (target must exist and be unlocked). |
| `group` | `ids` | (Phase 8) inserts into a frame; for Phase 7 this op is parsed but rejected with a friendly error until 8.1 lands. |

### System message

```
You are an assistant that edits the user's hand-drawn note. You receive
the note as both an image and a JSON description of every item by ID.
Reply with ONLY a fenced ```edit-ops block matching this schema:

{ "schema": 1, "summary": "<one short sentence>",
  "ops": [ /* operations referencing items by ID */ ] }

Rules:
- Modify only items that appear in the provided JSON.
- Do not invent new strokes from scratch. To turn a freehand stroke into
  a clean shape, use `replace_with_shape` referencing the original ID.
- Do not target items on locked or hidden layers.
- If you can't fulfil the request, return an empty ops array and explain
  in `summary`. Never reply outside the fenced block.
```

### Files

New:
- `app/src/main/java/com/aichat/sandbox/data/notes/EditProtocol.kt` — schema definitions (sealed `EditOp` class with one variant per op).
- `app/src/main/java/com/aichat/sandbox/data/notes/EditOpsParser.kt` — extracts the fenced block, parses JSON, validates each op, returns `Result<EditOpsDoc>`.
- Tests including malformed input cases (missing fence, extra prose, unknown op, locked-layer references).

### Definition of done

- Parser accepts every documented op with a representative payload.
- Parser rejects unknown ops, unknown IDs, locked-layer refs, malformed matrices.
- Parser tolerates leading / trailing whitespace and `summary` being missing.
- Fuzz test (1000 random malformations) never crashes.

### Non-goals

- Multi-turn refinement.
- Streaming partial ops (the side sheet shows "AI thinking…" then a single preview).

### Risks

- **Models drift.** Some models won't honor the "ONLY fenced block" rule. Mitigation: a fallback regex that finds the first ` ```edit-ops ` block in the reply even if surrounded by prose, plus telemetry on parse failures.

---

## Sub-phase 7.3 — Combined image + JSON request

### Scope

Plumb the new `EDIT` mode through `NoteAiService` and `AskRequest`. Vision branch sends `[image, vector_json, prompt]`; non-vision sends `[ocr_text, vector_json, prompt]`.

### Files

Modified:
- `app/src/main/java/com/aichat/sandbox/data/notes/AskRequest.kt` — add `mode: AskMode = ASK | EDIT`.
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteAiService.kt`:
  - Branch on `request.mode`.
  - `EDIT` + vision: render PNG as today, also call `VectorCanvasJson.serialize(items, ...)`, build a multimodal user `Message` whose `content` is:

    ```
    <prompt>

    Here is the vector JSON of the note. Edit by referencing IDs from `items`:
    ```json
    {…vector_json…}
    ```
    ```

    plus the image attachment in `metadata` exactly as today. The vector JSON sits inside the text content so any chat backend can pass it through unchanged.
  - `EDIT` + non-vision: same body but text-only, OCR text prepended.
  - Both `EDIT` paths use the Phase 7.2 system message instead of the existing `SYSTEM_INSTRUCTION`.
  - Pipe the stream into the existing `mapEvent` path, but on stream completion run `EditOpsParser` against the accumulated text and emit `AiChunk.EditPreview(ops, summary)` (new variant) instead of `AiChunk.Complete`.

- `app/src/main/java/com/aichat/sandbox/data/notes/AiChunk.kt` — add `EditPreview(ops, summary, idMap)`.

### Step-by-step

1. Wire `AskRequest.mode` through every call site. Default = `ASK` to keep existing flows untouched.
2. In `buildVisionStream`, branch on `mode`. Extract a `buildEditPromptBody(prompt, json): String` helper so unit tests can pin the formatting.
3. Buffer the full streamed text into a `StringBuilder`. When the stream completes, run the parser; if it succeeds emit `EditPreview`, if it fails emit `AiChunk.Error("Could not parse AI edit response — try rephrasing.")` plus a logcat snippet.
4. Add a unit test that feeds a fake `ChatStreamer` returning a canned ops document and asserts a single `EditPreview` is emitted.

### Definition of done

- `ASK` mode unchanged byte-for-byte at the wire (regression test).
- `EDIT` mode against a recorded canned response yields a parsed `EditPreview` with the expected ops.
- Non-vision `EDIT` works (skips image, sends OCR + JSON).
- Token-budget guard: if `vector_json.size > 180 KB`, prefer non-vision-style truncation (logged).

### Non-goals

- Function/tool-calls API. We use a text-channel protocol so it works on every backend the user might point at.

### Risks

- **Vision backends differ in how they accept the image.** We reuse the existing `ImageAttachment(dataUri)` plumbing in `ApiClient` exactly as `ASK` does today, so any backend that passes `ASK` vision passes `EDIT` vision.

---

## Sub-phase 7.4 — Edit applier

### Scope

Apply a parsed `EditOpsDoc` to the canvas as a **preview** (rendered as a translucent overlay; original items unchanged underneath). Show the diff in the AI side sheet with **Accept** / **Reject** / **Modify…** buttons. On accept, commit as one `EditorAction.CompositeEdit`.

### Files

New:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/EditPreviewController.kt` — holds the pending preview state.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/EditorAction.kt` — new variant:

  ```kotlin
  data class CompositeEdit(
      val description: String,
      val added: List<NoteItem>,
      val removed: List<NoteItem>,
      val modified: List<Pair<NoteItem, NoteItem>>, // (before, after)
  ) : EditorAction
  ```

  Both `apply()` and `revert()` operate transactionally.
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/AiEditPreviewSheet.kt` — extension of the existing AI side sheet that renders the diff summary, op-by-op breakdown, Accept / Reject.

Modified:
- `DrawingSurface.kt` — accepts an `Overlay` (the pending preview); renders preview items with 60% alpha and a magenta outline.
- `NoteEditorViewModel.kt` — `submitAiPrompt()` for `EDIT` mode receives `EditPreview`, runs through `EditPreviewController`, exposes `pendingEdit: StateFlow<PendingEdit?>`.

### Step-by-step

1. `EditPreviewController.simulate(currentItems, ops)` — pure function returning `(addedItems, removedItems, modifiedItems)` without touching repo / view state. Reused by Accept and by the preview overlay.
2. The overlay renders:
   - Items being removed: dashed magenta outline, original render dimmed to 30% alpha.
   - Items being added (e.g. from `replace_with_shape`): solid magenta outline, normal fill.
   - Items being modified: render the *new* version with 60% alpha on top of the old (also dimmed to 30%).
3. Accept: commit the `CompositeEdit` action to the repo + undo stack, clear the preview.
4. Reject: clear the preview, leave items untouched.
5. "Modify…" opens a tiny ops list editor where the user can uncheck individual ops before accepting (stretch goal; can defer to 7.6 if time-boxed).

### Definition of done

- Vision model edit response renders a visible diff overlay.
- Accept commits exactly one undo entry; Ctrl-Z / Undo button reverts everything.
- Reject leaves no trace.
- A locked-layer reference op is silently dropped (parser already rejects it, but defense in depth in the applier).
- Preview survives viewport pan / zoom.
- Compose recomposition cost during preview is < 5 ms / frame on a 100-item note.

### Non-goals

- Per-op accept / reject UI in v1 (stretch).
- Multi-step previews (chain of edits).

### Risks

- **Model returns an op set that violates invariants.** Parser already guards, but a buggy `replace_with_shape` could leave dangling layer refs. Mitigation: applier `simulate()` re-validates and rejects with a clear error string surfaced in the sheet.

---

## Sub-phase 7.5 — Canned edit actions

### Scope

Surface the new edit pipeline through one-tap actions on the lasso selection floating menu and the AI side sheet:

- **Clean up** — `smooth(amount=0.4)` over the selection.
- **Straighten** — `transform` with rotation snapped to the nearest 15°.
- **Auto-shape** — model decides per item whether to `replace_with_shape` or leave alone.
- **Recolor** — opens a color picker, sends `recolor`.
- **Continue** — model extends a repeating pattern by suggesting `add_stroke`… wait, that's forbidden. **Reframed**: model takes the last N strokes in the selection, returns a `replace_with_shape` for a polyline that *extends* their pattern. We still don't allow arbitrary new strokes. (If polyline-extension proves too constrained, we revisit the "no add" rule in a follow-up.)

### Files

Modified:
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/LassoController.kt` — five new action items.
- `app/src/main/java/com/aichat/sandbox/data/notes/CannedPrompts.kt` — each action maps to a short prompt + `mode = EDIT`.

### Step-by-step

1. Each canned action builds an `AskRequest(mode=EDIT, userPrompt=…, selection=…)` and pipes through 7.3 → 7.4.
2. Clean-up / Straighten can be implemented **locally** (no model needed) — Chaikin smoothing and rotation-snap are trivial. Default to **local** for these two; only call the model if the user holds down a modifier or picks "AI Clean up" explicitly. This saves API cost on the most-common operations and makes them instant.
3. Auto-shape, Recolor (with intent like "make these all the same blue"), Continue → always go through the model.

### Definition of done

- All five actions visible in lasso menu when something is selected.
- Local Clean-up / Straighten do not call the network.
- Model-backed actions show a loading state, then a preview, then commit on accept.

### Non-goals

- Saving custom canned actions per user.
- A history of recent AI edits.

### Risks

- **Continue might be too narrow.** If 90% of "extend my pattern" requests need new strokes, the no-add rule blocks the feature. Flag this in the verification matrix and revisit the rule in a follow-up if needed.

---

## Sub-phase 7.6 — AI edit safety + Phase 7 verification

### Scope

Pre-ship safety pass plus the device matrix.

### Safety checklist

- [ ] Parser never crashes on malformed input (fuzz test ≥ 1000 cases).
- [ ] Applier validates every op against current item set (rejects unknown IDs, locked layers, hidden layers, foreign layer targets).
- [ ] Accept commits one undo entry, period.
- [ ] Reject leaves the canvas byte-identical to pre-preview.
- [ ] AI edits never touch items outside the original selection (when a selection was sent; whole-note edits can touch anything in the JSON).
- [ ] No PII / image bytes are written to logcat at any verbosity level.
- [ ] If the model returns 0 ops, the sheet shows the `summary` and a "No changes proposed" state — not an error.

### Verification matrix (Samsung S25 Ultra)

1. Lasso 3 wobbly freehand circles → Auto-shape → preview shows 3 clean ellipses → Accept → one Undo reverts all 3.
2. Lasso a tilted line → Straighten → snaps to 0° / 15° locally without a network call.
3. Lasso 5 strokes → Clean up (local) → strokes smoothed, no network call.
4. Lasso 5 strokes → AI Clean up → model returns smooth ops, preview shows diff.
5. Recolor "make all of these dark grey" via AI → preview shows recolored items.
6. Continue: lasso 3 dashes of a dashed line → Continue → model extends pattern with a polyline replacement.
7. Vision model with the JSON present produces semantically better results than vision-only (smoke test, not regression).
8. Non-vision model edit path works (OCR + JSON only).
9. Locked layer: any op targeting locked-layer item is silently dropped, surfaced in the sheet summary.
10. Reject leaves the canvas pixel-identical to pre-preview.

### Definition of done

- All 10 matrix items pass.
- Update **Status** in `ARTIST_CANVAS_PLAN.md`.
