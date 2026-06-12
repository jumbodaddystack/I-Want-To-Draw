# Vector Art & Whiteboard — Master Plan

> Follow-on to `STYLUS_NOTES_PLAN.md` (Phases 1–4) and `ARTIST_CANVAS_PLAN.md` (Phases 5–9). Those plans delivered the stylus ink surface, the artist toolbox (shapes, layers, snapping, brushes, images, SVG export), the AI vector-edit pipeline, frames/stamps/favorites, and notebook mode. This plan turns the same canvas into a **vector-art tool** (bezier paths, node editing, booleans, gradients) and a **single-user whiteboard** for education and brainstorming (sticky notes, bound connectors, shape recognition, templates, presentation mode).
>
> Open this doc at the start of every implementation session, find the next unchecked sub-phase in the tracker, open the linked phase doc, and execute it.

## Status

- **Current phase:** Phase 14 — sub-phases 14.1–14.3 code-complete (build green, JVM suites green; see [`VECTOR_WHITEBOARD_PHASE_14.md`](./VECTOR_WHITEBOARD_PHASE_14.md)). Beautify: opt-in `InkBeautifier` pass (RDP de-noise scaled to the stroke's bbox + the same two Chaikin passes as Clean up, stride-agnostic so v2 timestamped strokes work) runs at commit time in `DrawingSurface` behind a persisted "Beautify" palette chip — the beautified stroke *is* the item, so one undo removes it. Connectors: `ConnectorCodec` gained a trailing `routeStyle:u8` (absent = straight); pure `ConnectorRouter` produces elbow (orthogonal, anchor-normal stubs, candidate routes scored by crossings of the two bound items' inflated bounds → bends → length) and curved (single cubic, anchor-normal control points) routes consumed by the renderer, rasterizer, eraser hit-test, drag preview and SVG export (`<polyline>` / `C` path, tangent-aligned heads); Straight/Elbow/Curved chips on the connector row, persisted. Templates: four new built-ins (Weekly planner / Retrospective / SWOT / Storyboard); "Save as template" snapshots items+frames through the new pure `TemplatePayloadCodec` into a `user_templates` table (DB v19→20), and the new-note menu's "Your templates" section seeds them re-keyed (fresh UUIDs, connector bindings + groupIds remapped, dangling refs dropped) via `note/new?template=user:<id>`.
- **Next sub-phase:** 14.4 — whole-plan device verification on hardware (the 10.7 matrix plus Phase 11 + 12 + 13 + 14 passes: stickies/connectors/recognition/templates/present + pen/node-edit/convert/exports + combine/gradients/style tools + beautify/elbow-curved routing/user templates).
- **Last verified device pass:** n/a (10.7 pending; Phase 11 + 12 + 13 + 14 device passes pending — all gated on 14.4).

## Phase index

| Phase | Focus | Breakdown doc | Sub-phases |
| --- | --- | --- | --- |
| 10 | Shared vector foundations (fill, stroke style, grouping, align, z-order) | [`VECTOR_WHITEBOARD_PHASE_10.md`](./VECTOR_WHITEBOARD_PHASE_10.md) | 7 |
| 11 | Whiteboard primitives (stickies, connectors, recognition, templates, presenting) | [`VECTOR_WHITEBOARD_PHASE_11.md`](./VECTOR_WHITEBOARD_PHASE_11.md) | 5 |
| 12 | Vector pen & node editing (path item kind) | [`VECTOR_WHITEBOARD_PHASE_12.md`](./VECTOR_WHITEBOARD_PHASE_12.md) | 5 |
| 13 | Boolean ops & gradients | [`VECTOR_WHITEBOARD_PHASE_13.md`](./VECTOR_WHITEBOARD_PHASE_13.md) | 3 |
| 14 | Polish & differentiators | [`VECTOR_WHITEBOARD_PHASE_14.md`](./VECTOR_WHITEBOARD_PHASE_14.md) | 4 |

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

### Phase 11 — Whiteboard primitives · [`details`](./VECTOR_WHITEBOARD_PHASE_11.md)

- [x] **11.1** Sticky notes — new item kind `"sticky"` + `StickyCodec` (rect, 8-colour preset fill, UTF-8 body, auto-shrinking font), `Tool.STICKY` drops a 160×160-world-unit sticky and opens the inline text editor; `StickyRenderer` (rounded rect + soft shadow + laid-out text); fifth branch in `ItemTransformer`, hit-test, exporters, `VectorCanvasJson`
- [x] **11.2** Bound connectors — new item kind `"connector"` + `ConnectorCodec` `{fromItemId?, fromAnchor:u8 (N/E/S/W/centre), toItemId?, toAnchor, fallback x0 y0 x1 y1, style (arrowheads, dash)}`; free endpoints allowed; `ConnectorResolver` recomputes endpoints from bound items' current bounds **at render time** (drag never spams the undo log); deleting a bound item unbinds (fallback geometry kept) rather than cascading; `Tool.CONNECTOR` with hover-highlight of bindable anchors
- [x] **11.3** Hold-to-snap shape recognition — stylus still ≥ 600 ms before lift runs a pure `ShapeRecognizer` (closed loop → ellipse/rect/polygon; straightness → line/arrow); replacement committed as one `CompositeEdit("Recognized rectangle")` so undo restores raw ink; reuse the local Clean-up/Straighten machinery from Phase 7.5
- [x] **11.4** Starter templates — code-defined `NoteTemplate` builders (brainstorm grid, kanban, mind map, Cornell notes) stamped into a fresh note (frames + shapes + stickies + text, re-ID'd à la `StampPayloadCodec`); surfaced on the new-note flow; no DB change
- [x] **11.5** Presentation mode — full-screen stepper over `NoteFrame`s in ordinal order via `ViewportController.flyTo`; stylus button advances; transient "laser" ink drawn on the front buffer, never committed

### Phase 12 — Vector pen & node editing · [`details`](./VECTOR_WHITEBOARD_PHASE_12.md)

- [x] **12.1** `"path"` item kind + `PathCodec` — `[version:u8][flags:u8 (closed)][count:u16]` then per-anchor `(x, y, inDx, inDy, outDx, outDy, type:u8 corner/smooth/symmetric)`, trailing `fillArgb:i32` + `strokeStyle:u8` (+ `capJoin:u8`) following the ShapeCodec trailing-optional-fields convention; `PathRenderer` (cubicTo), bounds, transform, hit-test
- [x] **12.2** Pen tool — `Tool.PATH_PEN`: tap = corner anchor, drag = pulled handles, tap-first-anchor = close, tool-switch = commit; front-buffer preview like the polygon tool
- [x] **12.3** Node editor — single selected path enters a node-edit overlay mode (`PathNodeEditor`, opened from the selection menu): drag anchors/handles, double-tap toggles corner/smooth, insert/delete anchors; one `CompositeEdit` per gesture (`PathNodeMath` pure + tested)
- [x] **12.4** Convert — shape→path (exact anchors for rect/ellipse/polygon/line/arrow) and stroke→path (RDP simplification + least-squares cubic fit, pure JVM, heavily unit tested); both on the selection menu ("To path")
- [x] **12.5** Stroke styling completeness — cap/join on paths, dash on paths, export parity (`NoteSvgExporter` `d=` output, VectorDrawable `pathData`); `VectorCanvasJson` gains the path kind (`p_` ids) so AI edits keep working (`EditOpsParser` is id-agnostic; the preview controller routes paths through `ItemTransformer`)

### Phase 13 — Boolean ops & gradients · [`details`](./VECTOR_WHITEBOARD_PHASE_13.md)

- [x] **13.1** Boolean ops — union/subtract/intersect/exclude on path/shape selections, one `CompositeEdit("Union 2 paths")`; implemented on the in-repo pure clipper (`PathBooleanBridge` → `PathBoolean`/`PolygonClipper`/`CurveRefit`) instead of `android.graphics.Path.op()` — JVM-testable, no new deps, anchors come back refit as smooth/corner cubics (documented deviation; holes = one filled item per ring, grouped)
- [x] **13.2** Gradient fills — shared `FillStyle` trailing block `fillType:u8` (solid/linear/radial) + normalized geometry + stops on shapes, paths **and** stickies; `GradientShaderFactory` → `LinearGradient`/`RadialGradient` fill passes; Style popover preset rows; SVG `<defs>` gradients (`objectBoundingBox` = the stored geometry); VectorDrawable falls back to the first stop via the pinned `fillArgb`
- [x] **13.3** Eyedropper + style copy/paste — `StyleTransfer` (pure) + `StyleClipboard`; "Pick colour" (selection → active ink tool + recents), "Copy style" (single item), "Paste style" (one `CompositeEdit` across the selection, per-kind subsets)

### Phase 14 — Polish & differentiators · [`details`](./VECTOR_WHITEBOARD_PHASE_14.md)

- [x] **14.1** Ink beautification pass — `InkBeautifier` (pure: RDP de-noise + Clean-up's Chaikin ×2, all lanes incl. v2 timestamps) at stroke-commit time behind a persisted "Beautify" palette toggle; `InkBeautifierTest`
- [x] **14.2** Connector routing polish — trailing `routeStyle:u8` on `ConnectorCodec` (absent = straight), pure `ConnectorRouter` (elbow: anchor-normal stubs + Manhattan candidates scored by bound-item crossings/bends/length; curved: single cubic), route-aware renderer/eraser/preview/SVG, Straight/Elbow/Curved palette chips; `ConnectorRouterTest` + codec tests
- [x] **14.3** Template gallery growth (Weekly / Retro / SWOT / Storyboard) + "save this note as a template" — `TemplatePayloadCodec` (re-keys ids, remaps connector bindings + groupIds), `user_templates` table (DB v20), editor overflow save + "Your templates" pick/delete in the new-note menu; `TemplatePayloadCodecTest`
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
| Boolean engine (13.1) | The in-repo pure clipper (flatten → clip → refit), not `Path.op()` | JVM-testable, zero new deps; reading geometry back out of a framework `Path` needs API-34 `PathIterator`; refit emits node-editable cubic anchors directly |
| Gradient geometry (13.2) | Normalized to item bounds (`objectBoundingBox`), `fillArgb` pinned to the first stop | Transforms never re-encode the gradient; old builds + VectorDrawable export get a sensible solid fallback for free |
| Beautify (14.1) | Commit-time pass on the packed samples, not a post-hoc undo entry | The committed item, the surface's instant-feedback copy and the hold-recognizer's input stay byte-identical; one undo removes the stroke |
| Connector routing (14.2) | Trailing `routeStyle:u8` + route computed at render time by a pure `ConnectorRouter` | Dragging bound items re-routes for free (same render-time rule as endpoint resolution); old builds draw new connectors straight |
| User templates (14.3) | `user_templates` table + JSON codec (stamps pattern), no thumbnails | A template is a whole-note layout incl. frames, which stamps can't carry; the text dropdown needs no PNG, keeping the save path JVM-pure |

## Out of scope (entire plan)

- Realtime collaboration (cursors, voting, timers, spotlight, comments) — single-user app; the Jamboard lesson is that solo/education users value simplicity.
- Vector networks (Figma-style multi-edge nodes), shape builder, mesh gradients, image trace, vector brushes — differentiators with steep cost; revisit after Phase 14.
- A new whiteboard surface/tab — everything lands on the existing notes canvas and toolset.

## How to use this plan

1. Check the tracker for the next `[ ]` sub-phase.
2. Open the linked phase doc; if it doesn't exist yet, write it first (use `VECTOR_WHITEBOARD_PHASE_10.md` as the template) from the tracker bullet + cross-cutting decisions.
3. Implement, add JVM tests for everything pure, update the tracker + Status block, commit.
4. Device verification sub-phases run on real hardware (S25 Ultra) and gate the phase's `[x]`.
