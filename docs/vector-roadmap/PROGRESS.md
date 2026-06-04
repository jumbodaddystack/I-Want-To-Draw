# Vector Icon Editor — Progress & Handoff

**This is the single source of truth for where the vector-icon-editor work stands.**
Point a fresh session at this file (`docs/vector-roadmap/PROGRESS.md`) and it has
everything needed to pick up the next phase.

---

## 0. Orientation (read first)

**Goal:** grow the app from "icon sketchpad + import-only tune-up" into a true
editable vector icon editor. Full vision + gap analysis: [`00-overview.md`](00-overview.md).

**The core architectural bet:** promote the immutable `data/vector/VectorDocument`
into the *live editable model* every surface edits, by layering an editable node
view (`data/vector/edit/EditablePath`) on top of it and reusing the existing
parser / normalizer / writer / snapping / viewport / reducer machinery rather
than building a parallel stack.

**Per-phase plans** (each: scope, concrete files, reuse list, test plan, risks):
- Phase 1 — [`phase-1-editable-bezier-scene-graph.md`](phase-1-editable-bezier-scene-graph.md)
- Phase 2 — [`phase-2-boolean-path-ops.md`](phase-2-boolean-path-ops.md)
- Phase 3 — [`phase-3-pixel-perfect-pipeline.md`](phase-3-pixel-perfect-pipeline.md)
- Phase 4 — [`phase-4-unify-domains.md`](phase-4-unify-domains.md)
- Phase 5 — [`phase-5-production-polish.md`](phase-5-production-polish.md)

**Dependency order:** Phase 0→1 is the keystone. 2, 3, 4 each depend on the Phase 1
editable model; they can then proceed with some parallelism. 5 is post-core polish.

---

## 1. Working agreement (how to run a session here)

- **Branch:** `claude/mobile-icon-system-gap-9xQLc`. Develop, commit, and push here.
  Do **not** open a PR unless explicitly asked.
- **Build/test env:** Android SDK is installed per `CLAUDE.md`. In a fresh web
  container run the one-time SDK setup from `CLAUDE.md` first if `./gradlew` fails
  with "SDK location not found".
- **Run the edit tests:**
  ```
  ./gradlew :app:testDebugUnitTest --console=plain --tests "com.aichat.sandbox.*.edit.*"
  ```
  Full suite: `./gradlew :app:testDebugUnitTest --console=plain`.
- **Known pre-existing failures (NOT regressions):** ~22 failures in
  `NoteSvgExporterTest`, `NoteVectorDrawableExporterTest`, `NoteAiServiceTest`
  (`android.graphics.Color` / `android.util.Log` "not mocked"). Suite is "green"
  if these are the only failures. Ignore them; focus on `*.edit.*` and the tests
  for your phase.
- **Style:** pure, JVM-testable cores (no Android/Compose imports) wherever
  possible — mirror the existing `VectorTuneupReducer` / `Snap` / `ViewportController`
  pattern. Match surrounding code's idiom and comment density.
- **End every session by updating this file** (see §4 Handoff protocol).

---

## 2. Status checklist

Legend: `[x]` done · `[~]` in progress · `[ ]` not started

### Phase 0 — editable model foundation (UI-free) — ✅ COMPLETE
- [x] `data/vector/edit/EditablePath.kt` — `EditAnchor`/`ControlPoint`/`EditSubpath`/`EditablePath`, `AnchorType`
- [x] `data/vector/edit/EditablePathFactory.kt` — `VectorPath → EditablePath` (reuses `VectorPreviewPathNormalizer`; quad→cubic, closed-curve fold, anchor classify)
- [x] `data/vector/edit/EditablePathSerializer.kt` — `EditablePath → PathCommand[]/VectorPath` (reuses `PathDataFormatter`)
- [x] `VectorDocument.replacePath(id, newPath)` helper
- [x] Tests: `EditablePathRoundTripTest`, `VectorDocumentReplacePathTest` (14 tests, green)
- [x] Committed + pushed

### Phase 1 — node editor (the rest of Phase 1) — ⬜ NOT STARTED → **NEXT**
Build in this order (each step is shippable + testable on its own):
- [ ] **1a. Pure reducer core (no UI):** `ui/screens/vector/edit/VectorEditState.kt`,
  `VectorEditAction.kt`, `VectorEditReducer.kt`. Actions: `StartPath`,
  `PlaceAnchor`, `DragHandle`, `CommitPath`, `SelectAnchor`, `MoveSelection`,
  `InsertAnchorOnSegment` (de Casteljau split), `DeleteSelected`, `SetAnchorType`,
  `ToggleSubpathClosed`, `Undo`, `Redo`. Each mutating action pushes an inverse;
  cap ~200. **Tests:** `VectorEditReducerTest` — build a triangle + a curve;
  insert splits a segment without changing the curve; delete; corner↔smooth;
  close/open; undo/redo inverts every action exactly.
- [ ] **1b. Hit-testing:** `ui/screens/vector/edit/EditHitTest.kt` (pure; screen-px
  tolerance ÷ viewport scale). **Tests:** `EditHitTestTest` at varied zoom.
- [ ] **1c. ViewModel:** `VectorEditViewModel.kt` (StateFlow host, Hilt — mirror `VectorTuneupViewModel`).
- [ ] **1d. Canvas + gestures:** `VectorEditCanvas.kt` — render via existing
  `VectorPreviewCanvas` internals (`preparePreviewPaths`/`buildComposePath`/
  `drawPreparedPath`); reuse `ViewportController` (pan/zoom) + `Snap`
  (grid/angle/endpoint). Overlay anchors/handles/rubber-band. Pen + direct-select gestures.
- [ ] **1e. Screen + toolbar:** `VectorEditScreen.kt` (Pen / Direct-select / add /
  delete / corner-smooth / close / undo / redo).
- [ ] **1f. Wire into Tune-Up:** add an "Edit" entry in `ui/screens/vector/VectorTuneupScreen.kt`;
  edited document returns as a new version via the existing version-history reducer. No Room changes.
- [ ] **Open choices to confirm with user before/at build:** (1) in-Tune-Up edit
  mode vs. dedicated editor screen; (2) handles are stored as absolute
  `ControlPoint`s (already chosen in Phase 0 — keep consistent).
- [ ] On-device manual verify (draw closed shape, snap, undo/redo, export round-trips).

### Phase 2 — boolean ops + outline-stroke + offset — ⬜ NOT STARTED
- [ ] Per `phase-2-boolean-path-ops.md`: new pure `data/vector/edit/boolean/`
  (clipper + outliner + offset + curve-refit), flatten→clip→refit, reducer actions, golden-geometry tests.

### Phase 3 — pixel-perfect pipeline — ⬜ NOT STARTED
- [ ] Per `phase-3-pixel-perfect-pipeline.md`: keyline grids, integer-grid snap
  (`EditSnap`), multi-size artboards, grid-quantized lossless + batch export, tests.

### Phase 4 — unify the two domains — ⬜ NOT STARTED
- [ ] Per `phase-4-unify-domains.md`: notes-bridge vectorization, single canonical
  editable `VectorDocument`, route AI (notes `EditOp` + tune-up plans/redraw), lossless export, tests.

### Phase 5 — production polish — ⬜ NOT STARTED
- [ ] Per `phase-5-production-polish.md`: stroke styling, gradients, vector symbols,
  keyboard ergonomics, AI auto-trace. (Independent sub-features; pick by priority.)

---

## 3. Latest handoff (update this each session)

**Last updated:** 2026-06-04 · **Last completed:** Phase 0 (editable model foundation)

**State of the branch:** Phase 0 code + all five phase plan docs + this tracker
committed and pushed to `claude/mobile-icon-system-gap-9xQLc`. Edit tests green
(12 in `*.edit.*`). No PR open.

**What exists now (for the next session to build on):**
- `data/vector/edit/EditablePath.kt` — node model. Handles are **absolute**
  `ControlPoint`s; `inHandle`/`outHandle` null ⇒ straight side. All-cubic, absolute coords.
- `EditablePathFactory.fromPath(VectorPath): EditablePath` — entry to edit mode.
- `EditablePathSerializer.toCommands(EditablePath)` / `.toVectorPath(...)` — exit.
- `VectorDocument.replacePath(id, newPath)` — write back into the tree.
- Reusable, confirmed APIs: `VectorPreviewPathNormalizer.normalize(commands)`,
  `PathDataFormatter.format(commands)`, `PathDataParser.parse(data)`,
  `ViewportController` (world↔screen, bounded clamp), `Snap` (MASK_GRID/ANGLE/ENDPOINT),
  `VectorPreviewCanvas` internals (`preparePreviewPaths`/`buildComposePath`/`drawPreparedPath`).

**→ NEXT ACTION:** Start **Phase 1 step 1a** — the pure `VectorEditReducer` + state
+ actions + `VectorEditReducerTest`. It's UI-free and fully JVM-testable; do it
before any Compose. Then 1b→1f. See the Phase 1 doc + checklist above.

**Watch-outs for next session:**
- Keep the reducer pure (no Android imports) so it stays JVM-testable like `VectorTuneupReducer`.
- `InsertAnchorOnSegment` needs a de Casteljau cubic split — add a dedicated test that the curve shape is unchanged after a split.
- Confirm the two "open choices" with the user before wiring UI (step 1f).
- Re-run `*.edit.*` tests after each step; keep them green.

---

## 4. Handoff protocol (instructions to future me)

At the **end of every session**, before stopping:

1. **Update §2 checklist** — flip `[ ]`→`[~]`/`[x]` for what you touched.
2. **Rewrite §3 "Latest handoff"** — set date + last completed, summarize branch
   state, list any NEW reusable code/APIs you added, and write a crisp **→ NEXT
   ACTION** plus watch-outs. Assume the next session has zero memory of this one;
   give it exactly what it needs to start in one read.
3. **Append a per-phase handoff note** under §5 when you finish a phase (what
   shipped, key decisions, deviations from the plan doc, test status).
4. **Commit this file** with your code changes, and **push** the branch.
5. If a phase's plan doc turned out wrong/incomplete, fix the doc too — keep docs
   and reality in sync.

Keep this file the *only* thing a fresh session must read to be oriented.

---

## 5. Per-phase completion notes

### Phase 0 — editable model foundation (2026-06-04)
- **Shipped:** `EditablePath` model + `EditablePathFactory` + `EditablePathSerializer`
  + `VectorDocument.replacePath`; round-trip + replace tests (14, green).
- **Key decisions:** handles stored as **absolute** `ControlPoint`s (match
  normalizer output + on-screen hit-testing); node model normalized to **all-cubic
  absolute** coords; quads degree-elevated (exact), arcs flattened via the
  normalizer (documented token-level lossiness on edit); closed curves whose
  closing segment lands on the start are **folded** into the start anchor for clean
  node counts and exact curved-close round-trip.
- **Verified:** M/L/C/Z round-trips token-exact; relative resolves to absolute;
  quad stays curve-equivalent (sampled); `replacePath` preserves tree position.
- **Deviation from plan:** none material. The Phase 1 doc sketched handles as
  `inX/inY/outX/outY` floats; implemented as a cleaner nullable `ControlPoint` —
  equivalent, avoids half-null states.
