# Vector Icon System Roadmap

A staged plan to grow the app's vector tooling from "icon sketchpad + import-only
tune-up" into a true editable vector icon editor. Start with
[`00-overview.md`](00-overview.md) for the vision, current-state analysis, and the
core architectural bet (promote `data/vector/VectorDocument` into the live
editable model that every surface edits).

| Phase | Doc | Focus | Status |
|------|-----|-------|--------|
| — | [00-overview.md](00-overview.md) | Vision + gap analysis + phased roadmap | — |
| 1 | [phase-1-editable-bezier-scene-graph.md](phase-1-editable-bezier-scene-graph.md) | Editable Bézier scene graph + pen tool | Phase 0 (model + converters) landed; editor UI pending |
| 2 | [phase-2-boolean-path-ops.md](phase-2-boolean-path-ops.md) | Boolean ops (union/subtract/intersect/exclude), outline-stroke, offset | Planned |
| 3 | [phase-3-pixel-perfect-pipeline.md](phase-3-pixel-perfect-pipeline.md) | Keyline grids, multi-size artboards, lossless grid-quantized export | Planned |
| 4 | [phase-4-unify-domains.md](phase-4-unify-domains.md) | Fold the notes canvas + tune-up into one editor on the shared model | Planned |
| 5 | [phase-5-production-polish.md](phase-5-production-polish.md) | Stroke styling, gradients, vector symbols, ergonomics, AI auto-trace | Planned |

Each phase doc is self-contained: scope (in/out), concrete new/modified files,
a "reuse, don't rebuild" list of existing utilities, a pure-JVM test plan, and
risks. Phases depend on Phase 1's editable model
(`data/vector/edit/EditablePath`); 2–4 can then proceed with some parallelism.

## Phase 1 — Phase 0 slice (landed)

The foundational, UI-free slice is implemented and unit-tested:

- `data/vector/edit/EditablePath.kt` — the all-cubic, absolute-coordinate node
  model (`EditAnchor` + in/out `ControlPoint` handles, `EditSubpath`, `EditablePath`).
- `data/vector/edit/EditablePathFactory.kt` — `VectorPath → EditablePath`, reusing
  `VectorPreviewPathNormalizer` (quads elevated to cubics, closed-curve folding).
- `data/vector/edit/EditablePathSerializer.kt` — `EditablePath → PathCommand[]/VectorPath`,
  reusing `PathDataFormatter`.
- `VectorDocument.replacePath(id, newPath)` — write an edited path back into the tree.

Round-trip tests under `app/src/test/java/com/aichat/sandbox/data/vector/edit/`
prove M/L/C/Z geometry round-trips exactly and quads stay curve-equivalent.
