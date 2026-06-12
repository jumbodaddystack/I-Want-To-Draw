# Phase 14 — Polish & differentiators

> The closing phase of the vector/whiteboard plan: an opt-in ink
> beautification pass on pen lift (building on the Phase 7.5 Clean-up
> machinery), elbow/curved connector routing with a light obstacle-avoid
> heuristic, a bigger template gallery plus "save this note as a
> template", and the whole-plan device verification matrix. Everything
> follows the master plan's cross-cutting decisions: trailing optional
> payload fields decoded via `buf.hasRemaining()`, mutations as single
> `EditorAction.CompositeEdit`s where they touch the undo log, no new
> item tables for canvas objects, pure-JVM math under test.

## Status: 14.1–14.3 code-complete

14.1–14.3 implemented on this branch; build green, JVM suites green
(modulo the documented pre-existing "not mocked" failures). 14.4 is the
on-device pass and remains open. Implementation notes / deviations:

- **Beautify is a commit-time pass, not an undo-log entry** — the toggle
  reshapes the stroke *before* it is committed, so the beautified stroke
  is the item (one undo removes it entirely). This differs from the
  selection-menu "Clean up", which stays a second, separately undoable
  `CompositeEdit` on top of raw ink.
- **User templates have no thumbnail** — the gallery row shows name +
  "Saved template". Stamps earned PNG thumbnails because they render in
  a picker grid; the template menu is a text dropdown, and thumbnails
  would drag Android rasterization into an otherwise pure save path.
- **Curved connectors are a single cubic** with tangents along the
  anchor normals — no obstacle avoidance on curves (the heuristic is
  elbow-only, matching the tracker bullet's "light").

## Sub-phase 14.1 — Ink beautification pass

Optional per-stroke smoothing on pen lift. Off by default.

- `InkBeautifier` (**new**, pure, `ui/components/notes/`) —
  `beautify(samples, stride)`: RDP de-noise (tolerance scaled to the
  stroke's bbox diagonal, via the shared `PolylineSimplify`) followed by
  two Chaikin corner-cutting iterations — the same pass "Clean up" runs,
  preceded by jitter removal. Endpoints preserved; all lanes (pressure,
  tilt, v2 timestamps) interpolate alongside x/y; ≤ 5-sample strokes
  return unchanged; output capped at 1024 samples. Stride-agnostic so
  the v2 (timestamped) commit path beautifies identically.
- `DrawingSurface.beautifyInk: Boolean` — `commitLiveStroke` runs the
  beautifier on the packed sample array before encoding, so the
  committed item, the surface's instant-feedback copy, and the payload
  the hold-recognizer sees are all the same beautified stroke.
  Recognition (11.3) is unaffected — it just gets cleaner input.
- Toggle: `ToolPaletteState.inkBeautify` (+ `PalettePrefs.inkBeautify`,
  persisted like the shape-fill toggle); a "Beautify" switch row in the
  ink tool options of `ToolPalette`; `NoteEditorScreen` pipes it into
  the canvas composable.

### Definition of done

- With the toggle on, a jittery stroke commits visibly smoother and
  reloads byte-identical to what was drawn on screen; toggle off
  commits byte-identical to pre-14.1. `InkBeautifierTest` pins endpoint
  preservation, deviation reduction on a noisy line, the short-stroke
  no-op, the sample cap, stride-5 lane fidelity (monotone timestamps),
  and determinism.

## Sub-phase 14.2 — Connector routing polish

- Schema: `ConnectorCodec` gains a trailing `routeStyle:u8` after
  `toId` (0 = straight, 1 = elbow, 2 = curved), decoded via
  `buf.hasRemaining()` — absent byte = straight, so every pre-14.2
  payload round-trips and old builds draw new connectors as straight
  lines.
- `ConnectorRouter` (**new**, pure) — `route(payload, endpoints,
  fromBounds, toBounds): Route`:
  - **Straight** → the 2-point polyline (unchanged behaviour).
  - **Elbow** → orthogonal polyline. Each bound end exits along its
    anchor normal through a fixed stub; candidate Manhattan routes
    (HV / VH / mid-x Z / mid-y Z) are scored by obstacle crossings
    (the two bound items' bounds, inflated by a clearance margin),
    then bend count, then length — lowest score wins. That is the
    whole "light obstacle-avoid heuristic": it clears the two items
    the connector touches, it does not path-find around the canvas.
  - **Curved** → one cubic; control points sit along the anchor
    normals (chord direction for free/centre ends) at
    `clamp(0.4 · dist, 24, 160)` world units. `flattenCubic` samples
    it to a polyline for hit-testing.
- `ConnectorRenderer.draw` takes the `Route` (polyline `lineTo`s or one
  `cubicTo`); arrowheads orient along the route's terminal tangents, so
  elbow heads sit flush with their final segment. Eraser hit-testing in
  `DrawingSurface` tests every route segment (curves via `flattenCubic`);
  the live drag preview routes with the palette's style. `NoteRasterizer`
  inflates routed connectors' bounds by stub + clearance (elbows may
  detour outside the endpoint envelope).
- `NoteSvgExporter` emits `<polyline>` for elbows and a `C` path for
  curves, arrowheads from the same tangents.
- UI: the connector row in `ToolPalette` gains Straight / Elbow / Curved
  chips (`ToolPaletteState.connectorRouteStyle`, persisted in
  `PalettePrefs`); `DrawingSurface.commitConnector` encodes the active
  style.

### Definition of done

- An elbow connector between two stickies leaves/enters perpendicular
  to its anchors, never crosses either sticky, re-routes live as a
  bound sticky drags, and survives save/reload; SVG matches the canvas.
  `ConnectorRouterTest` pins straight passthrough, elbow orthogonality
  + anchor-normal exits + obstacle clearance, curve control points and
  flatten endpoints; `ConnectorCodecTest` pins the routeStyle
  round-trip and the legacy-payload default.

## Sub-phase 14.3 — Template gallery growth + save as template

- **Gallery growth** — four new code-defined `NoteTemplate`s built from
  the existing primitives: `WEEKLY` (7-column weekly planner), `RETRO`
  (Went well / To improve / Action items), `SWOT` (2×2 quadrants),
  `STORYBOARD` (2×3 caption-lined panels).
- **`TemplatePayloadCodec`** (**new**, pure) — JSON
  `{schema:1, items:[…], frames:[…]}`; items mirror the
  `StampPayloadCodec` shape (base64 payload + groupId), frames carry
  `{name, minX, minY, maxX, maxY, ordinal}`. `instantiate(json, noteId,
  layerId)` re-keys every item/frame with fresh UUIDs **remapping
  connector `fromItemId`/`toItemId` bindings and `groupId`s** through
  the old→new id map, reparents to the target note, and drops dangling
  binding ids. Malformed / future-schema JSON parses to null (fail-soft).
- **Storage** — `user_templates` table (DB **v19→20**: id, name,
  payloadJson, createdAt, lastUsedAt), `UserTemplateDao` +
  `UserTemplateRepository`, registered in `AppModule` — the `stamps`
  pattern, minus thumbnails.
- **Save flow** — "Save as template" in the editor's overflow menu
  snapshots the note's current items + frames into the codec and saves
  under the note's title.
- **Pick flow** — the new-note dropdown lists built-ins, then a "Your
  templates" section (observed from the repository) with a delete
  affordance per row; picking one navigates with `template=user:<id>`,
  and `NoteEditorViewModel` seeds from the repository (async) instead of
  the enum builder. `lastUsedAt` is touched on use.

### Definition of done

- Save a note with bound connectors + groups as a template, create a
  new note from it: everything lands re-keyed (connectors bind to the
  *new* ids), title set, frames present; deleting the template removes
  it from the menu. `TemplatePayloadCodecTest` pins round-trip, re-key
  remapping (connector bindings, groupIds, dangling-id drop),
  fail-soft parse; `NoteTemplatesTest` extends to the four new
  built-ins (non-empty, decodable, fresh ids).

## Sub-phase 14.4 — Whole-plan device verification matrix

On real hardware (S25 Ultra): the 10.7 matrix plus Phase 11 (stickies /
connectors / recognition / templates / present), Phase 12 (pen /
node-edit / convert / exports), Phase 13 (combine / gradients / style
tools) and Phase 14 (beautify toggle on/off, elbow + curved routing
with drags, user templates end-to-end incl. process death). Gates the
phase's `[x]` — cannot run in a cloud container.

## JVM test roster

| Test | Pins |
| --- | --- |
| `InkBeautifierTest` | endpoint preservation, noisy-line deviation reduction, short-stroke no-op, 1024-sample cap, stride-5 lane fidelity + monotone timestamps, determinism |
| `ConnectorRouterTest` | straight passthrough, elbow orthogonality + anchor-normal exits + obstacle clearance + determinism, curve control points, flatten endpoints |
| `ConnectorCodecTest` (extended) | routeStyle round-trip, legacy payload defaults to straight |
| `TemplatePayloadCodecTest` | items+frames round-trip, fresh-id re-key with connector-binding + groupId remap, dangling binding drop, malformed/future-schema → null |
| `NoteTemplatesTest` (extended) | the four new built-ins build non-empty decodable content with fresh ids |
