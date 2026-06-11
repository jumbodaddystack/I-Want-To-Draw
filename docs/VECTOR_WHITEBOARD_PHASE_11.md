# Phase 11 — Whiteboard primitives

## Status: 11.1–11.5 code-complete

All five sub-phases implemented and committed on this branch; build green,
JVM suites green (only the documented `Color`/`Log` "not mocked" classes
fail, byte-identical to the pre-Phase-11 base). On-device verification is
pending (runs together with 10.7 on real hardware). Implementation notes /
deviations from the spec below:

- **Palette layout** — STICKY and CONNECTOR share one grouped "Board"
  button (mirroring the shapes group, with a `lastBoardTool` slot) so the
  tool row still fits a 360 dp phone.
- **Sticky drop is immediate** — unlike the text tool's NewAt draft, the
  tap commits the sticky first (`AddItems`) and then opens the editor; an
  emptied body keeps the sticky (it's a valid board artifact).
- **Connector duplicate/paste drops bindings** — the copy keeps the
  resolved geometry as plain fallback endpoints; staying glued to the
  *original* targets would surprise more than it helps.
- **Recognition is line-only for open strokes** (no arrow inference) and
  emits axis-aligned rects; a heavily rotated rectangle falls through to a
  closed polygon, which renders it faithfully anyway. Ellipse thresholds
  are documented in-code against the rectangle radial profile.
- **Templates title the note** after the template's display name.
- **Presentation keeps finger pan/pinch** inside a frame; system Back
  exits the presentation before it exits the note.

> Single-user whiteboard features on the existing notes canvas: sticky notes,
> bound connectors, hold-to-snap shape recognition, starter templates, and a
> presentation mode over frames. Everything follows the Phase 10 cross-cutting
> decisions: new `NoteItem.kind` strings + binary codecs (no new tables), all
> mutations as `EditorAction`s the undo log already understands, trailing
> optional payload fields decoded via `buf.hasRemaining()`.

## Sub-phase 11.1 — Sticky notes

### Schema

New item kind `"sticky"`, codec `StickyCodec` (little-endian):

```
[version:u8=1]
[minX:f] [minY:f] [maxX:f] [maxY:f]     world-space rect
[fillArgb:i32]                          one of the 8 preset fills
[fontSize:f]                            base font; renderer shrinks to fit
[bodyLen:i32] [body:utf8]
```

Future trailing fields append after `body` and decode via `hasRemaining()`.

### Semantics

- `Tool.STICKY` is tap-only (same interaction model as TEXT): tap empty canvas
  drops a 160×160-world-unit sticky centred on the tap and opens the inline
  text editor; tap an existing sticky opens its editor.
- The palette carries a `stickyFillColor` slot with the 8-colour preset row
  (`StickyCodec.PRESET_FILLS`), persisted via `ToolPalettePrefsStore`.
- `StickyRenderer` draws a soft drop shadow + rounded rect + the body laid out
  with an auto-shrinking font (largest step ≤ base size that fits the inset
  rect; long bodies clip at the bottom rather than overflow). Layout cache is
  keyed per item id and evicted with the same hook as `TextItemRenderer`.
- Stickies stay axis-aligned: `StickyCodec.transform` maps the rect corners
  through the matrix and re-emits the envelope (rotation degrades to its
  bounding box, like images), scaling `fontSize` by the matrix scale hint.
- Editing an existing body commits one `CompositeEdit("Edit sticky")`; an
  emptied body keeps the sticky (an empty sticky is a valid board artifact).

### Files

- `ui/components/notes/StickyCodec.kt` — **new**, pure (JVM-testable).
- `ui/components/notes/StickyRenderer.kt` — **new**, Android (StaticLayout).
- `ui/components/notes/ToolPaletteState.kt` / `ToolPalette.kt` /
  `data/notes/ToolPalettePrefsStore.kt` — STICKY tool, fill row, persistence.
- `ui/components/notes/DrawingSurface.kt` — sticky tap routing (reuses the
  text tool's tap/pinch state machine) + render branch.
- `ui/screens/notes/NoteEditorViewModel.kt` — `stickyEditTarget` state +
  tap/commit/cancel, `itemBounds` / lasso / duplicate / stamp branches.
- `ui/screens/notes/NoteEditorScreen.kt` — sticky editor overlay (reuses
  `TextItemEditor`), commit on tool switch.
- Fifth branch in `ItemTransformer` + `EditPreviewController.transformItem`.
- Exporters: `NoteRasterizer`, `NoteSvgExporter` (`<rect>` + `<text>`),
  `VectorCanvasJson` (`kind: "sticky"`, bbox + fill + body). VectorDrawable
  export skips stickies (counted, same as text).

### Definition of done

- Drop a sticky, type, reopen note → body + fill survive; transform /
  duplicate / lasso / undo all work; SVG export shows the rect + text.
- `StickyCodecTest` pins round-trip, transform, bounds, future-field decode.

## Sub-phase 11.2 — Bound connectors

### Schema

New item kind `"connector"`, codec `ConnectorCodec`:

```
[version:u8=1]
[fromAnchor:u8] [toAnchor:u8]           0=N 1=E 2=S 3=W 4=CENTRE
[styleFlags:u8]                         bit0 = arrow at end, bit1 = arrow at start
[strokeStyle:u8]                        ShapeCodec STROKE_STYLE_* value
[x0:f] [y0:f] [x1:f] [y1:f]             fallback endpoints (world)
[fromIdLen:u16] [fromId:utf8]           len 0 = free endpoint
[toIdLen:u16]   [toId:utf8]
```

### Semantics

- `ConnectorResolver` (pure) recomputes endpoints **at render time** from the
  bound items' current bounds via a `(itemId) -> bounds?` lookup; a missing
  binding (deleted item) silently falls back to the stored fallback endpoint —
  that *is* the "delete unbinds, fallback geometry kept" rule, and undo of the
  delete restores the binding for free. Drags of bound items never touch the
  connector payload, so the undo log stays quiet.
- `Tool.CONNECTOR`: press near a bindable item (shape / sticky / image / text)
  binds the start to its nearest anchor; drag shows a live preview plus the
  hover candidate's four anchors; release near another item binds the end.
  Free endpoints are allowed at either side. Default style: arrow at end.
- Transforming a connector itself moves only its fallback endpoints
  (`ItemTransformer` branch); bound ends re-resolve next render.
- Eraser + lasso hit-test against the *resolved* segment.

### Files

- `ui/components/notes/ConnectorCodec.kt`, `ConnectorResolver.kt` — **new**, pure.
- `ui/components/notes/ConnectorRenderer.kt` — **new**, Android.
- `DrawingSurface.kt` — `Tool.CONNECTOR` gesture + anchor highlights + render/
  erase branches; `NoteEditorViewModel` — bounds lookup, lasso branch;
  exporters as in 11.1 (SVG `<line>` + head polygons, rasterizer, canvas JSON).

### Definition of done

- Connect two stickies, drag one → the connector follows; delete the target →
  the connector keeps its last fallback geometry; undo restores the binding.
- `ConnectorCodecTest` + `ConnectorResolverTest` pin round-trip, anchors,
  fallback rules, transform-fallback-only.

## Sub-phase 11.3 — Hold-to-snap shape recognition

- `ShapeRecognizer` (**new**, pure): operates on packed `[x,y,p,t]` samples.
  Pipeline: gate on size/sample count → straightness test (max deviation from
  the chord) → `Line`; closed-loop test (start/end gap vs bbox diagonal) →
  ellipse fit (radial variance in the bbox-normalised frame) → `Ellipse`;
  RDP corner extraction (tolerance scaled to bbox diagonal) → 4 corners with
  high bbox coverage → `Rect`, otherwise closed `Polygon`. Open non-straight
  strokes return null (no false positives on handwriting).
- `DrawingSurface` tracks the last screen-space movement ≥ touch slop during
  an ink stroke (PEN / PENCIL only). If the stylus has been still for
  ≥ 600 ms at lift, the stroke commits normally and then fires
  `strokeHoldRecognizeListener` with the committed item.
- `NoteEditorViewModel.onStrokeHoldRecognized` runs the recognizer and, on a
  hit, applies one `CompositeEdit("Recognized rectangle"/…)` that removes the
  raw stroke and adds the shape (same colour / width / layer / z) — a single
  undo restores the raw ink, exactly as the master plan requires. The shape
  inherits the palette's *shape* fill settings? No — recognition keeps fill 0
  so the replacement is visually the user's outline, nothing more.

### Definition of done

- Draw a rectangle-ish loop, hold, lift → shape item; one undo → raw ink.
- `ShapeRecognizerTest` pins line / rect / circle / triangle / scribble-null.

## Sub-phase 11.4 — Starter templates

- `data/notes/NoteTemplates.kt` (**new**, pure): `NoteTemplate` enum
  (BRAINSTORM, KANBAN, MIND_MAP, CORNELL) + `build(template, noteId, layerId)`
  returning fresh-ID'd items (shapes + stickies + text, à la
  `StampPayloadCodec` re-keying) and frames. Raw ARGB ints only — JVM-pure.
- Route `note/new` gains an optional `template={id}` arg; the notes list's
  "New" menu grows a template section. `NoteEditorViewModel` seeds the
  content directly into state in the new-note branch (initial condition, no
  undo entries — same convention as `seedIconArtboard`) and arms an autosave.
- No DB change.

### Definition of done

- Each template opens as a fresh note with its content framed and editable;
  `NoteTemplatesTest` pins decodability + unique ids + non-empty frames.

## Sub-phase 11.5 — Presentation mode

- VM state `presentationIndex: StateFlow<Int?>` (null = not presenting) +
  `startPresentation()` / `advancePresentation(+1/-1)` / `exitPresentation()`.
  Frames present in **ordinal order**; entry point is the editor overflow menu
  ("Present", gated on ≥1 frame).
- The editor hides the top bar, palette, and overlays while presenting and
  flies the viewport to each frame via `ViewportController.flyTo`; a minimal
  control strip (prev / counter / next / exit) floats at the bottom.
- `DrawingSurface.presentationMode`: stylus (or finger) input draws **laser
  ink** — a red glow polyline on the front buffer that fades out after
  ~900 ms and is never committed (no `strokeListener`, no undo entry).
  Two-finger pinch/pan still works. A stylus barrel-button press advances
  (`presentationAdvanceListener`), matching the master-plan bullet.
- Exiting restores the previous chrome; the viewport stays where the show
  ended (cheap, predictable).

### Definition of done

- Present a 3-frame note: stepper walks frames in order, laser ink fades and
  never lands in the item list, exit restores the editor.

## JVM test roster (11.6 equivalent — folded into each sub-phase)

| Test | Pins |
| --- | --- |
| `StickyCodecTest` | round-trip, future-trailing-field decode, transform (translate / scale / rotate-envelope), bounds, preset fills |
| `ConnectorCodecTest` | round-trip bound/free/mixed, styleFlags, transform moves fallback only |
| `ConnectorResolverTest` | all 5 anchors, bound vs fallback resolution, deleted-target fallback, nearestAnchor |
| `ShapeRecognizerTest` | line, rect, circle→ellipse, triangle→closed polygon, open scribble → null, tiny stroke → null |
| `NoteTemplatesTest` | every template: non-empty, unique fresh ids, payloads decode via their codecs, frames present |
