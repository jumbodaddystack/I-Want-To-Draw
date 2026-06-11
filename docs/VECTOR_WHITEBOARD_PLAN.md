# Vector Art & Whiteboard — Master Plan

> Follow-on to `STYLUS_NOTES_PLAN.md` (Phases 1–4) and `ARTIST_CANVAS_PLAN.md` (Phases 5–9). Those plans delivered the stylus ink surface, the artist toolbox (shapes, layers, snapping, brushes, images, SVG export), the AI vector-edit pipeline, frames/stamps/favorites, and notebook mode. This plan turns the same canvas into a **vector-art tool** (bezier paths, node editing, booleans, gradients) and a **single-user whiteboard** for education and brainstorming (sticky notes, bound connectors, shape recognition, templates, presentation mode).
>
> Open this doc at the start of every implementation session, find the next unchecked sub-phase in the tracker, open the linked phase doc, and execute it.

## Status

- **Current phase:** Phase 10 — sub-phases 10.1–10.6 code-complete. `ItemTransformer` extracted from `EditorAction.TransformItems`; `AlignmentMath` / `ZOrderMath` pure objects landed with full JVM coverage. Shape fill surfaced end-to-end (palette "Fill" chip + fill swatch → picker target → `DrawingSurface` commit + rubber-band preview); `ShapeCodec` gained the optional trailing `strokeStyle:u8` (solid/dashed/dotted, legacy payloads decode solid, no DB migration); `ShapeRenderer` renders dash via width-scaled `DashPathEffect` (lines route through `Path` for pipeline reliability, fills never inherit the dash) and `NoteSvgExporter` mirrors the pattern as `stroke-dasharray`. Grouping: `note_items.groupId` (MIGRATION_18_19, DB v19), lasso-commit expansion via `expandSelectionToGroups` (locked-layer members stay inert), duplicate/paste re-key via `remapGroupIds`, Group/Ungroup on the selection menu, `groupId` riding the persisted undo log as optional JSON. Align/distribute (6 edges + 2 axes) and band-respecting z-order ops surfaced in an "Arrange" submenu; restyle (fill / line style) in a "Style" popover — every mutation is a single `EditorAction.CompositeEdit`.
- **Next sub-phase:** 10.7 on-device verification matrix, then Phase 11 (whiteboard primitives) starting at 11.1 sticky notes.
- **Last verified device pass:** n/a (10.7 pending).

## Phase index

| Phase | Focus | Breakdown doc | Sub-phases |
| --- | --- | --- | --- |
| 10 | Shared vector foundations (fill, stroke style, grouping, align, z-order) | [`VECTOR_WHITEBOARD_PHASE_10.md`](./VECTOR_WHITEBOARD_PHASE_10.md) | 7 |
| 11 | Whiteboard primitives (stickies, connectors, recognition, templates, presenting) | `VECTOR_WHITEBOARD_PHASE_11.md` (write at phase start) | 5 |
| 12 | Vector pen & node editing (path item kind) | `VECTOR_WHITEBOARD_PHASE_12.md` (write at phase start) | 5 |
| 13 | Boolean ops & gradients | `VECTOR_WHITEBOARD_PHASE_13.md` (write at phase start) | 3 |
| 14 | Polish & differentiators | `VECTOR_WHITEBOARD_PHASE_14.md` (write at phase start) | 4 |

---

## Master implementation tracker

One sub-phase per PR. Mark a sub-phase complete only after its "Definition of done" is met **and** the build is green on this feature branch.

Legend: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked (note reason inline)

### Phase 10 — Shared vector foundations · [`details`](./VECTOR_WHITEBOARD_PHASE_10.md)

- [x] **10.1** `ItemTransformer` extraction + `AlignmentMath` / `ZOrderMath` pure objects
- [x] **10.2** Fill surfacing — palette fill chip/swatch, picker target, surface plumbing, selection restyle
- [x] **10.3** `ShapeCodec` trailing `strokeStyle:u8` + dash rendering + SVG `stroke-dasharray`
- [x] **10.4** Grouping — `note_items.groupId` (DB v19), lasso expansion, Group/Ungroup, clipboard re-key
- [x] **10.5** Align / distribute / z-order in the selection overlay ("Arrange" submenu)
- [x] **10.6** JVM tests (`ShapeCodecStrokeStyleTest`, `AlignmentMathTest`, `ZOrderMathTest`, `ItemTransformerTest`, `GroupOpsTest`, `EditorActionCodecGroupTest`) + docs
- [ ] **10.7** Phase 10 device verification matrix

### Phase 11 — Whiteboard primitives

- [ ] **11.1** Sticky notes — new item kind `"sticky"` + `StickyCodec` (rect, 8-colour preset fill, UTF-8 body, auto-shrinking font), `Tool.STICKY` drops a 160×160-world-unit sticky and opens the inline text editor; `StickyRenderer` (rounded rect + soft shadow + laid-out text); fifth branch in `ItemTransformer`, hit-test, exporters, `VectorCanvasJson`
- [ ] **11.2** Bound connectors — new item kind `"connector"` + `ConnectorCodec` `{fromItemId?, fromAnchor:u8 (N/E/S/W/centre), toItemId?, toAnchor, fallback x0 y0 x1 y1, style (arrowheads, dash)}`; free endpoints allowed; `ConnectorResolver` recomputes endpoints from bound items' current bounds **at render time** (drag never spams the undo log); deleting a bound item unbinds (fallback geometry kept) rather than cascading; `Tool.CONNECTOR` with hover-highlight of bindable anchors
- [ ] **11.3** Hold-to-snap shape recognition — stylus still ≥ 600 ms before lift runs a pure `ShapeRecognizer` (closed loop → ellipse/rect/polygon; straightness → line/arrow); replacement committed as one `CompositeEdit("Recognized rectangle")` so undo restores raw ink; reuse the local Clean-up/Straighten machinery from Phase 7.5
- [ ] **11.4** Starter templates — code-defined `NoteTemplate` builders (brainstorm grid, kanban, mind map, Cornell notes) stamped into a fresh note (frames + shapes + stickies + text, re-ID'd à la `StampPayloadCodec`); surfaced on the new-note flow; no DB change
- [ ] **11.5** Presentation mode — full-screen stepper over `NoteFrame`s in ordinal order via `ViewportController.flyTo`; stylus button advances; transient "laser" ink drawn on the front buffer, never committed

### Phase 12 — Vector pen & node editing

- [ ] **12.1** `"path"` item kind + `PathCodec` — `[version:u8][flags:u8 (closed)][count:u16]` then per-anchor `(x, y, inDx, inDy, outDx, outDy, type:u8 corner/smooth/symmetric)`, trailing `fillArgb:i32` + `strokeStyle:u8` following the ShapeCodec trailing-optional-fields convention; `PathRenderer` (cubicTo), bounds, transform, hit-test
- [ ] **12.2** Pen tool — `Tool.PATH_PEN`: tap = corner anchor, drag = pulled handles, tap-first-anchor = close, tool-switch = commit; front-buffer preview like the polygon tool
- [ ] **12.3** Node editor — single selected path enters a node-edit overlay mode in `SelectionOverlay`: drag anchors/handles, double-tap toggles corner/smooth, insert/delete anchors; one `CompositeEdit` per gesture
- [ ] **12.4** Convert — shape→path (exact anchors for rect/ellipse/polygon/line/arrow) and stroke→path (RDP simplification + least-squares cubic fit, pure JVM, heavily unit tested); both on the selection menu
- [ ] **12.5** Stroke styling completeness — cap/join on paths, dash on paths, export parity (`NoteSvgExporter` `d=` output, VectorDrawable `pathData`); `VectorCanvasJson` + `EditOpsParser` gain the path kind so AI edits keep working

### Phase 13 — Boolean ops & gradients

- [ ] **13.1** Boolean ops — union/subtract/intersect/exclude on path/shape selections via `android.graphics.Path.op()`, re-extract anchors, one `CompositeEdit("Union 2 paths")`
- [ ] **13.2** Gradient fills — fill payload grows `fillType:u8` (solid/linear/radial) + stops; `LinearGradient`/`RadialGradient` shaders; SVG `<defs>` gradients; applies to shapes, paths, stickies
- [ ] **13.3** Eyedropper + style copy/paste (copy style from selection, apply to selection)

### Phase 14 — Polish & differentiators

- [ ] **14.1** Ink beautification pass (optional per-stroke smoothing toggle building on Clean up)
- [ ] **14.2** Connector routing polish (elbow/curved connectors, light obstacle-avoid heuristic)
- [ ] **14.3** Template gallery growth + "save this note as a template"
- [ ] **14.4** Whole-plan device verification matrix on real hardware

---

## Cross-cutting decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| Ink engine | Keep the custom `DrawingSurface` engine; do **not** migrate to androidx.ink | The engine already has pressure/tilt dynamics, motion prediction, palm rejection, textures, and a binary persistence format; migration is a rewrite for marginal gain |
| Native format | Internal binary codecs stay the source of truth; SVG remains export-only | Android has no platform SVG parser; our codecs are versioned and fast |
| New payload fields | Append after the last optional field, decode via `buf.hasRemaining()` (the `strokeStyle` convention) | Backward compatible without `Note.schemaVersion` bumps or migrations |
| New mutations | Always a single `EditorAction.CompositeEdit` | Inherits undo/redo, the persisted undo log (`EditorActionCodec` needs no schema bump), and fail-soft decode for free |
| New canvas objects | New `NoteItem.kind` strings + codec, **not** new tables | Stickies/connectors/paths inherit undo, lasso, layers, clipboard, exports, and the AI pipeline |
| Connectors | Items with item-id refs in the payload + fallback endpoints; resolve at render time; delete unbinds | A `note_connectors` table would need parallel undo/clipboard/export plumbing; render-time resolution keeps drags out of the undo log |
| Sticky notes | One `"sticky"` kind, not a `group(rect, text)` composite | A composite breaks inline text editing, auto-fit font, and single-item semantics in `VectorCanvasJson` |
| Grouping | Flat, single-level, may span layers; expansion at selection time | Keeps every downstream action (transform/delete/duplicate/copy) group-agnostic; nesting deferred until something needs it |
| Old-build downgrade | An old build reading a new undo log silently drops `groupId` on those items | Gson ignores unknown fields; acceptable for a single-user app |
| VectorDrawable dash | Not supported by the format — export renders solid | SVG is the faithful vector export |

## Out of scope (entire plan)

- Realtime collaboration (cursors, voting, timers, spotlight, comments) — single-user app; the Jamboard lesson is that solo/education users value simplicity.
- Vector networks (Figma-style multi-edge nodes), shape builder, mesh gradients, image trace, vector brushes — differentiators with steep cost; revisit after Phase 14.
- A new whiteboard surface/tab — everything lands on the existing notes canvas and toolset.

## How to use this plan

1. Check the tracker for the next `[ ]` sub-phase.
2. Open the linked phase doc; if it doesn't exist yet, write it first (use `VECTOR_WHITEBOARD_PHASE_10.md` as the template) from the tracker bullet + cross-cutting decisions.
3. Implement, add JVM tests for everything pure, update the tracker + Status block, commit.
4. Device verification sub-phases run on real hardware (S25 Ultra) and gate the phase's `[x]`.
