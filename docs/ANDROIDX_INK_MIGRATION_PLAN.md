# AndroidX Ink Migration Plan

Status: **backlog / engineering plan** (not yet scheduled). This plan covers the
AndroidX Ink (`androidx.ink`) engine migration and supporting infrastructure.
For the user-facing AI feature roadmap, see
[`AI_ART_ASSIST_IDEAS.md`](AI_ART_ASSIST_IDEAS.md).

This migration enables richer versions of several AI art-assist features, but it
is not required for the first wave of AI product improvements. Guided critique,
palette assistance, style presets, and text-to-vector generation can proceed on
the current drawing engine.

---

## AndroidX Ink (`androidx.ink`) evaluation

> **Bottom line:** ink has reached a **stable 1.0.0 core release**. This is a
> **personal, single-device project** — the only target is a **Samsung Galaxy
> S25 Ultra on Android 16 (API 36)** — so there is no compatibility surface to
> protect and **every "optional" ink enhancement is baseline**: front-buffered
> low-latency rendering (API 29+) and improved rendering effects/perf (API 34+)
> are both *below* our target. Given that, the plan is **ink-first**, but not
> reckless: build ink's low-latency authoring path as the intended primary
> engine, make it default only after a visual/behavioral parity gate passes, keep
> the v1/v2 `StrokeCodec` decode path as a **safety net** for existing personal
> notes (no forced migration), and pull in the brush, geometry, and serialization
> modules as the engine matures.

## Target environment (the only one we support)

- **Device:** Samsung Galaxy S25 Ultra. **OS:** Android 16 (**API 36**).
- **S-Pen:** **4,096 pressure levels**, improved tilt recognition, 0.7mm tip —
  high-fidelity pressure/tilt signal for ink's input smoothing and brush
  dynamics. **No Bluetooth** on the S25 Ultra S-Pen, so **no BLE air-action /
  remote-button gestures** (the side button still works via the digitizer, so
  the existing button-as-eraser override is unaffected). Don't design features
  around remote pen gestures.
- **Display:** high-refresh LTPO panel — the front-buffer latency win is
  directly *felt* on this hardware, which is why the authoring path leads.
- **Consequence:** no API 26–28 degradation paths, no multi-device input
  quirks. Code can assume modern APIs throughout.

## Module map (what ships, and our interest level)

| Module | Provides | Interest |
|---|---|---|
| `ink-strokes` | `Stroke` (immutable), `StrokeInput`, `StrokeInputBatch` | High — interchange + geometry input |
| `ink-authoring` | `InProgressStrokesView`, low-latency front buffer, input smoothing/prediction | High — drawing feel + beautify |
| `ink-brush` | `Brush`, `Brush.Builder`, `BrushFamily` (programmable in 1.1), `StockBrushes`, `BrushPaint.TextureLayer` | High — brush richness |
| `ink-geometry` | `PartitionedMesh`, `Box`, `Parallelogram`, intersection / coverage / hit-testing, lasso→mesh | High — selection + snapping |
| `ink-rendering` | `CanvasStrokeRenderer`, `ViewStrokeRenderer` | Medium — only if we render ink strokes |
| `ink-storage` | `StrokeInputBatch` (de)serialization, version-compatible `BrushFamily.decode()` | Medium — interop, not our source of truth |
| `*-compose` | Compose interop for each module | Low now (our surface is a `View`) |
| `ink-nativeloader` | JNI loader for the native geometry/rendering core | Transitive |

Key properties worth noting for our pipelines:
- Runs on **server-side JVM (Linux x86_64)**, not just Android — geometry/format
  code could run in a backend or test harness without an emulator.
- `StrokeInput` carries **position, timestamp, pressure, tilt, orientation** —
  a superset of our v2 lane set (we don't currently capture orientation).
- `Stroke` = `ImmutableStrokeInputBatch` (inputs) + `Brush` (style) +
  `PartitionedMesh` (geometry) — clean separation that maps onto our
  payload / `BrushPreset` / derived-bounds split.

## Adoption principles (decided — ink-first)

1. **Ink-first engine.** ink's `InProgressStrokesView` becomes the **default**
   live-drawing path **after** parity is proven; the custom `DrawingSurface`
   quad-Bézier path is retained as a runtime fallback/reference until ink
   reaches visual + behavioral parity. This inverts the usual "experiment behind
   a flag" stance — ink is the intended primary, and the flag exists to fall
   *back*, not to opt *in* — but it still needs a checklist before default-on.
2. **`StrokeCodec` stays canonical — permanently — and the AI edit pipeline is
   inviolable.** This is a hard constraint, not a phase outcome. The AI
   canvas-editing path (`edit-ops` via `EditPreviewController` /
   `VectorCanvasJson` / `EditOpsParser`: `transform`, `recolor`, `restyle`,
   `smooth`, `simplify`, `merge_paths`, `add_path`, …) reads and rewrites
   `StrokeCodec` float samples, and **nothing in this migration may break that.**
   Consequence: ink is a live-authoring + rendering + *derived*-geometry layer
   only. An ink-authored stroke is converted to `StrokeCodec`
   (`InkInterop.fromStroke`) the instant the pen lifts, so once committed it is
   byte-indistinguishable from a stroke drawn today and the AI pipeline never
   sees ink. **Ink-native canonical storage is explicitly ruled out** — it is the
   one option that would force the AI edit-ops to round-trip through ink, so it is
   off the table regardless of how well ink performs. No migration of existing
   personal notes; legacy v1/v2 decode is kept forever.
3. **`BrushPreset` stays user-facing; map it onto `BrushFamily`.**
   A `BrushPreset → Brush/BrushFamily` adapter preserves color / width /
   opacity / taper / jitter / pressure-curve / texture semantics. ink's brush
   model becomes the rendering/encoding target while presets remain the thing
   the UI edits.
4. **Capture the orientation lane when a brush needs it.** The S-Pen reports
   stylus orientation, which our codec drops. Since the target device reliably
   provides it, a future **v3 sample lane**
   `[x,y,pressure,tilt,orientation,timestamp]` can feed ink brush behaviors that
   key off orientation. Treat the exact v3 binary marker/layout as part of the
   storage-decision phase so timestamp handling stays unambiguous.

## Toolchain prerequisite (decided: bump minSdk to 35/36)

Raising `minSdk` to match the Android-16-only target is approved, but it is
**not a one-line change**: `minSdk` cannot exceed `compileSdk`, which is
currently **34** (AGP **8.2.2**, Gradle **8.5**, Kotlin **1.9.22**). Reaching
`minSdk 36` requires:

- `compileSdk` / `targetSdk` → **36**,
- **AGP** → 8.9+ (8.11+ recommended for SDK 36) and a matching **Gradle**
  wrapper bump,
- a re-verify of the build + the known-good Android SDK install steps in
  `CLAUDE.md`.

Treat this as its own small, verified task (toolchain upgrade) **before** the
ink-authoring phase, since `InProgressStrokesView` is where assuming modern
APIs pays off. Until then, ink itself still works (it supports API 21+).

### Sample ↔ ink conversion seam (the one new primitive everything shares)

A small bidirectional adapter is the linchpin for every ink integration:

```
StrokeCodec floats  ── toInputBatch() ──▶  StrokeInputBatch / Stroke
[x,y,pressure,tilt,(t)]                     (+ Brush from BrushPreset)
        ▲                                          │
        └────────────  fromStroke()  ◀─────────────┘
```

Build this first (call it `InkInterop`), unit-test the round-trip on JVM
(ink runs headless), and the rest of the work composes on top of it.

**Timestamp reconciliation is part of this seam, not deferred to I3.** Our v2
lane stores time as **milliseconds relative to the audio recording's
`recordingStartedAt`** (written live as
`elapsedRealtime() - recordingStartedAt` in `DrawingSurface`). ink's
`StrokeInput.elapsedTimeMillis` is **stroke-relative** (since the start of the
stroke). These are two different clocks, so `InkInterop` must define explicitly:
- **toInputBatch:** subtract the stroke's first-sample time to produce
  stroke-relative `elapsedTimeMillis` for ink smoothing/playback.
- **fromStroke:** re-add a stroke-origin offset to restore recording-relative
  time for audio sync, so committed payloads keep the v2 contract.
- **v1 strokes (no timestamps):** how time is synthesized (e.g. uniform
  cadence) when feeding ink.
- **export payloads:** whether an exported ink/`StrokeInputBatch` carries
  stroke-relative or recording-relative time.

Get this wrong and replay, audio sync, and "draw with me" (N4) drift. Unit-test
the two-clock round-trip alongside pressure/tilt.

## Where ink helps each of the four capability areas

### A. Programmable brushes + textures  (`ink-brush`)
- Map `BrushPreset` → `Brush`/`BrushFamily`; render via `CanvasStrokeRenderer`
  behind the brush flag. Gains: `StockBrushes` (pressure pen, pencil, laser,
  highlighter with `SelfOverlap`), multi-`TextureLayer` brushes, seed-based
  randomized behaviors — richer than our single-shader `TextureRegistry`.
- 1.1's **public programmatic brush API** means an AI can emit brush parameters
  directly (see "AI brush designer" below).
- Keep our procedural textures as `TextureBitmapStore` inputs so existing
  presets render unchanged.

### B. Mesh geometry — selection / snapping  (`ink-geometry`)
- Convert committed strokes → `PartitionedMesh` for **robust intersection /
  coverage / hit-testing**, replacing the point-to-segment loops in `HitTest`
  for the cases that need accuracy (lasso of overlapping strokes, partial
  erase, "what's inside this region").
- ink already ships **lasso → mesh** conversion, directly upgrading
  `LassoController`.
- Mesh coverage/intersection is the enabler for the **constraint/snap engine**
  (idea #8): detect near-alignment, shared edges, near-symmetry → propose snaps.

### C. Low-latency authoring + smoothing  (`ink-authoring`)  — the headline win
- `InProgressStrokesView` gives a true **front-buffered** low-latency stroke
  layer (baseline on API 36) plus **built-in input smoothing/prediction**,
  replacing our `motionprediction` + manual history iteration. On the S25
  Ultra's high-refresh panel + 4,096-level S-Pen this is the most *felt*
  upgrade ink offers — which is why it leads the ink-first plan.
- ink owns the live layer; on `InProgressStrokesFinishedListener` we convert the
  finished `Stroke` to a `StrokeCodec` payload via `InkInterop` and commit
  through the existing layer/undo/storage pipeline (those stay intact at first).
- ink's smoothing also feeds the **live beautify** flow (idea #1) for free.
- `DrawingSurface`'s quad-Bézier path stays as a fallback until ink reaches
  rendering parity (see Risks).

### D. Standard stroke serialization  (`ink-strokes` / `ink-storage`)
- `StrokeInputBatch` is a compact, portable, **cross-platform** stroke format.
  Use it first as an **interchange/export** format (AI round-trips, share,
  future web/desktop), *not* as on-disk truth — `StrokeCodec` stays canonical
  until the I2/I3 parity + storage decision is complete.
- Bonus: a stable interchange format makes it easier to hand stroke geometry to
  the model and get edits back as strokes rather than only edit-ops.

---

## Ink-enabled AI features this migration supports

These features are tracked in the AI art-assist roadmap, but they are listed here
as migration consumers so the engine work preserves their requirements.

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
  - **Status (I4):** (a) **done** — `InkBrushFamilies` builds custom
    `BrushTip`/`BrushBehavior` families on stable 1.0.0 (opt-in-annotated, *not*
    the alpha dependency). (b) **done** — isolated to
    `data.ink.experimental.InkProgrammableBrush`, with no alpha dependency added
    (jitter parks here because stable exposes no randomized source). (c) the
    *data path* is scaffolded: `AskMode.DESIGN_BRUSH` → `BrushSpecParser`
    (validated like `edit-ops`) → user-scope `BrushPreset`, persisted via
    `BrushPresetRepository`, **no canvas mutation**. The user-facing designer UI
    (a prompt entry + preview) is the remaining surface work.
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
- **Prerequisite (✅ landed in I6):** the mesh/cache/index layer is in place —
  the signature-keyed per-stroke [`StrokeMeshCache`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeMeshCache.kt)
  (invalidation on transform/restyle/delete), the uniform-grid
  [`SpatialIndex`](../app/src/main/java/com/aichat/sandbox/data/ink/SpatialIndex.kt)
  prefilter, and `MeshHitTest.strokesInRegion` returning a deterministic,
  registration-ordered id list for mesh-hit → item-id/layer mapping.
- **Status (I7): ✅ done — headless slice; AI-ranking quality + on-device feel
  device-only; gated behind ink-on, default-off.** The *similarity ranking +
  constraint engine + optional AI* now sits on top of that prerequisite:
  - (a) **Select similar** — [`StrokeSimilarity`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeSimilarity.kt)
    reduces a stroke to a **scale/translation-invariant** shape descriptor
    (aspect, straightness, turning) plus its style features (tool/width/colour);
    [`SelectSimilar`](../app/src/main/java/com/aichat/sandbox/data/ink/SelectSimilar.kt)
    ranks every candidate against the tapped stroke and returns the selection set
    (`NoteEditorViewModel.selectSimilarTo`, plugged into the existing selection
    model with the lasso's group-expansion + locked-layer rules). Local, offline,
    deterministic. A cheap **descriptor proxy** is used in production (true
    per-pair mesh IoU is too costly on a tap) and held honest against ink's real
    mesh overlap in the parity test.
  - (b) **Constraint/snap engine** — [`ConstraintSnap`](../app/src/main/java/com/aichat/sandbox/data/ink/ConstraintSnap.kt)
    detects near-alignment (6 edge/centre keys), even spacing, and near-symmetry
    across the selection, then **resolves** the proposals into one conflict-free
    per-axis nudge set. Each nudge is an ordinary `EditOp.Transform` translation
    on a canonical payload, staged through `EditPreviewController` →
    `PendingEdit` so it surfaces as the **same accept/decline chips** as AI edits
    (`NoteEditorViewModel.proposeSnaps`).
  - (c) **AI ranking is optional / local-first** — geometry proposes locally; the
    AI is consulted only to confirm "which of these belong together" via the
    unchanged `submitAiEdit` EDIT pipeline (`NoteEditorViewModel.aiRankSelection`),
    emitting group/recolor/transform edit-ops the user accepts. `StrokeCodec`
    stays canonical and the AI never sees ink (Adoption principle 2).

### N3. Live beautify via ink smoothing  (upgrade of idea #1)
- **What:** on pen-lift, offer a one-tap clean snap. ink's input smoothing +
  our `InkBeautifier` (RDP + Chaikin) + `ShapeRecognizer` combine for a
  noticeably cleaner result than today.
- **Ink:** `ink-authoring` input smoothing on the live batch; optionally render
  the candidate via `CanvasStrokeRenderer` for a crisp preview.
- **Pipeline:** purely local for the geometric clean; AI only consulted for the
  ambiguous "did you mean this shape?" cases (reuses `AUTO_SHAPE` /
  `replace_with_shape`). Ghost-preview the beautified stroke; tap to accept.
- **Status (I5):** **done — headless slice; ghost appearance device-only.** The
  three-stage clean is now (1) [`StrokeSmoothing`] input-smoothing low-pass →
  (2) RDP de-noise → (3) Chaikin, wired into **both** commit paths
  (`commitLiveStroke` and the ink-authoring `onInkStrokesFinished`). Beautify is
  a tap-to-accept **candidate**: the raw stroke commits, and when the clean would
  visibly alter it ([`InkBeautifier.Candidate.changed`]) a translucent ghost is
  staged; a tap on it accepts (`onStrokeBeautifyAccepted` → one undoable
  `CompositeEdit("Beautify")`), a tap elsewhere (or a new stroke) declines. The
  committed payload stays canonical [`StrokeCodec`] (v2 timestamps preserved — we
  beautify in the payload's own stride), so the AI edit-ops pipeline never sees
  ink. **Headless-verified:** `StrokeSmoothingTest`, the extended
  `InkBeautifierTest` (candidate/offer decision), and `InkSmoothParityTest` —
  which builds *real ink strokes* (`ink-*-jvm` + `libink.so`) and shows ink
  renders the beautified stroke with a ~6× smaller mesh-outline turning sum
  (60.1 → 9.7) while staying faithful to the input (max deviation ~3.3% of the
  stroke's size). **Device-only (documented checklist, not claimed to pass):**
  the on-screen ghost appearance, the tap-to-accept feel, and the live wet-layer
  smoothing on the S25 Ultra — see [`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md).
  I5 builds on the authoring path but is **not** a trigger to flip the I2
  default-on switch: ink stays **default-off**.

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

---

## Proposed phasing

| Phase | Scope | Ink modules | Risk |
|---|---|---|---|
| **I0 — `InkInterop` seam** | Bidirectional `StrokeCodec ↔ StrokeInputBatch/Stroke`; stable `BrushPreset → Brush` adapter; JVM round-trip tests | strokes, brush, geometry | Low — no UI |
| **I0.5 — Toolchain bump** | `compileSdk`/`targetSdk` 36, AGP 8.9+/8.11+, Gradle wrapper, re-verify build | — | Low/Med |
| **I0.7 — Rendering fidelity spike** ✅ **done** | Render 50–100 representative strokes through both `StrokeRenderer` and `CanvasStrokeRenderer`; pixel/visual diff; per-tool go/no-go (pen, pencil, highlighter, marker) **before** building the authoring path. **Result:** pen/pencil/marker **GO**, highlighter **GO-with-brush-work** (ink stock highlighter ~0.71× our footprint); no NO-GO — see [`INK_I07_RENDERING_FIDELITY_SPIKE.md`](INK_I07_RENDERING_FIDELITY_SPIKE.md) | rendering, brush | Low — throwaway |
| **I1 — Authoring prototype (ink-first)** ✅ **done** | `InProgressStrokesView` wired finish→convert→commit behind a fallback-capable runtime switch ("Ink engine (experimental)", default **off**); not default until the I2 parity checklist passes. ink ships on-device (`ink-authoring`/`ink-rendering` + `libink.so`); the live wet layer is ink's, the pen-lift `Stroke → StrokeCodec` conversion (`InkInterop.fromStroke`) keeps the committed payload byte-identical so storage / undo / the AI edit pipeline never see ink. | authoring, rendering | Med |
| **I2 — Rendering + behavior parity gate** 🟡 **headless slice done; I4 brush gaps closed; device items open — ink stays default-off** | Match ink mesh rendering to `StrokeRenderer` (taper/tilt/width) and verify latency, undo/redo, layer commit, eraser (incl. **no regression for non-stroke kinds** — shapes, stickies, connectors, paths), shape recognition, and audio timestamp sync before default-on. **Headless verified:** eraser parity across all `NoteItem` kinds, commit-pipeline + audio-timestamp correctness, and — after I4 — pressure-taper / pencil-tilt-width / highlighter-width / footprint geometry parity, all as permanent JVM tests (`data.ink.parity.*`). **Still open (no device here):** on-device latency/front-buffer, the colour/opacity/**texture**/AA pixel diff, overlay touch pass-through, and the deferred procedural-texture + jitter brush appearance. See [`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md). | rendering, brush | Med |
| **I3 — Optional additive storage + v3 lane** | `StrokeCodec` stays canonical (decided). *Only* if a concrete need appears (e.g. an AI-designed `BrushFamily` per stroke, or a cross-device interchange blob), add **additive dual-write** data that existing code never reads; define the exact v3 orientation/timestamp layout if a brush needs it. Ink-native canonical is ruled out. | strokes, storage | Low/Med |
| **I4 — Brush richness + N1 foundation** ✅ **done (stable adapter + N1 scaffold; texture/jitter still deferred)** | Stable custom-brush adapter ([`InkBrushFamilies`](INK_I2_PARITY_GATE.md)) closes the I2 gate's brush-geometry gaps — pressure taper (pen), tilt-width (pencil), highlighter width — all at parity headless (corr > 0.999; every tool now GO). The 1.1-alpha programmable/jitter path is isolated and **not** depended on (`data.ink.experimental.InkProgrammableBrush`). The `DESIGN_BRUSH` (N1) AI mode is scaffolded end-to-end (text→validated brush-spec JSON→user-scope `BrushPreset`, no canvas mutation). Procedural **texture** + **jitter** stay deferred (device-pixel / alpha-only). | brush, rendering | Med |
| **I5 — Live beautify (N3)** ✅ **done (headless slice; ghost appearance device-only; ink stays default-off)** | ink input smoothing into the pen-lift beautify flow. A pure-JVM input-smoothing low-pass ([`StrokeSmoothing`](../app/src/main/java/com/aichat/sandbox/ui/components/notes/StrokeSmoothing.kt)) — the headless stand-in for `ink-authoring`'s Android-only device-side input modeler — now feeds [`InkBeautifier`]'s RDP+Chaikin clean, on **both** the legacy and the ink-authoring commit paths. The clean is a **candidate**, not an in-place mutation: the pen-lift commits the raw stroke and a ghost is offered (tap to accept → raw→beautified as one undoable `CompositeEdit`, decline = keep raw), reusing the hold-recognize swap model. The geometric clean stays **purely local**; the AI is consulted only for the ambiguous shape cases via the unchanged `AUTO_SHAPE`/`replace_with_shape` hold-recognize path. Verified headless against the **real ink engine**: ink renders the beautified stroke with a ~6× smoother native mesh outline (`InkSmoothParityTest`). | authoring, rendering | Low/Med |
| **I6 — Mesh-backed geometry adoption** ✅ **done (headless slice; mesh path gated behind ink-on, default-off; on-device feel deferred)** | A derived, **signature-keyed** per-stroke [`StrokeMeshCache`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeMeshCache.kt) (invalidates on transform/restyle/delete — closing the "Geometry cache correctness" open question), a uniform-grid [`SpatialIndex`](../app/src/main/java/com/aichat/sandbox/data/ink/SpatialIndex.kt) prefilter, ear-clip [`LassoTriangulation`](../app/src/main/java/com/aichat/sandbox/data/ink/LassoTriangulation.kt) (stable `ink-geometry` ships no public polygon→mesh builder), and [`InkGeometry`](../app/src/main/java/com/aichat/sandbox/data/ink/InkGeometry.kt)/[`MeshHitTest`](../app/src/main/java/com/aichat/sandbox/data/ink/MeshHitTest.kt) backing the eraser (tip-box vs rendered width) and lasso (mesh∩triangle — catches *crossing* strokes the sample loop misses) with the point-to-segment loops as the **fallback**. Wired into `DrawingSurface`/`NoteEditorViewModel` gated behind ink-on, so default-off behaviour is byte-identical. All proven headless against the real ink engine (`data.ink.*` + `data.ink.parity.MeshGeometryParityTest`). | geometry | Low/Med |
| **I7 — Select-similar + snapping (N2, idea #8)** ✅ **done (headless; gated behind ink-on, default-off; on-device feel + AI-ranking quality deferred)** | Local-first similarity + constraint engine + optional AI ranking. Pure-JVM [`StrokeSimilarity`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeSimilarity.kt) (scale/translation-invariant shape descriptors + tool/width/colour) feeds [`SelectSimilar`](../app/src/main/java/com/aichat/sandbox/data/ink/SelectSimilar.kt) (the magic-wand ranker, builds on the I6 cache/prefilter) and a pure [`ConstraintSnap`](../app/src/main/java/com/aichat/sandbox/data/ink/ConstraintSnap.kt) engine (near-alignment / even-spacing / near-symmetry → translations). Snaps are expressed as ordinary `EditOp.Transform`s on canonical payloads and ride the **same** `EditPreviewController` → `PendingEdit` accept/decline chips as AI edits; the AI ranking is optional and routes through the unchanged `submitAiEdit` EDIT pipeline. Proven headless against the **real ink engine** (`data.ink.parity.SelectSimilarSnapParityTest`): the descriptor proxy ranks candidates the same way ink's mesh IoU does, and a snap translation aligns real ink geometry. | geometry | Med |
| **I8 — Replay / draw-with-me (N4, idea #7)** | timestamp-driven replay, tutor guide layer, timelapse export | strokes, rendering | Med |

Sequencing logic: **I0 (the seam) is a hard prerequisite** for everything. The
ink-first stance pulls the **authoring path forward to I1** — it's the headline
win on this hardware and the rest composes around it — but the **I0.7 fidelity
spike gates I1**: a cheap throwaway render comparison tells us which tools can
ship through ink rendering *before* we invest in `InProgressStrokesView` wiring,
and I2 is the full default-on gate, not an afterthought. Storage is already
decided (`StrokeCodec` canonical, AI pipeline inviolable), so I3 is reduced to an
optional additive-data question rather than a fork in the road.
Brushes (I4) and beautify (I5) build directly on the new
authoring path; geometry/snapping/replay (I6–I8) layer on once the engine is the
default.

### I1 — what shipped (and what I2 still owns)

The authoring prototype is wired end-to-end behind the **"Ink engine
(experimental)"** switch in the note editor's overflow menu (persisted via
`ToolPalettePrefsStore.inkAuthoring`, default **off**):

- **On-device engine.** `ink-authoring` + `ink-rendering` moved from
  `compileOnly` to `implementation`, so the native core (`libink.so`) now ships
  in the APK. The JVM round-trip tests still run headless — the android ink
  variants are excluded from the unit-test classpaths so only the `-jvm`
  artifacts (with `linux-x86_64/libink.so`) are present there.
- **Overlay, not a rewrite.** `DrawingSurfaceView` wraps the existing
  `DrawingSurface` in a `FrameLayout` and, only while the switch is on, attaches
  an `InProgressStrokesView` sibling on top as the live wet-ink layer. With the
  switch off the container holds just the `DrawingSurface` and behaves exactly
  as before (zero overhead; sketch mode included).
- **ink owns only the wet layer.** `DrawingSurface` still handles all touch.
  When the switch is on **and** the in-flight tool is an ink tool, the stroke is
  forwarded to ink (`startStroke`/`addToStroke`/`finishStroke`) with a
  screen→world `motionEventToWorldTransform`, so the finished `Stroke` is in
  world coordinates. Eraser / lasso / shapes / text / connectors / paths always
  stay on the existing path.
- **Inviolable commit contract.** On `onStrokesFinished`, each `Stroke` is
  converted back to a canonical `StrokeCodec` payload via
  `InkInterop.fromStroke` (re-adding the recording-relative origin captured at
  pen-down so the v2 audio-sync contract holds), then committed through the
  **same** `strokeListener` → layer/undo/storage pipeline as a hand-drawn
  stroke and rendered by `StrokeRenderer`. ink is told to drop the finished
  stroke (`removeFinishedStrokes`) once we own its pixels. Hold-to-recognize
  (11.3) is preserved. The AI edit pipeline never sees ink.
- **Fallback-capable.** Every ink call is guarded; a failure logs and drops the
  one stroke rather than half-drawing through two engines, and toggling the
  switch off mid-session cancels any in-flight ink stroke without data loss.

Deferred to **I2 (parity gate)** — these need a real device/emulator and are
out of scope for a headless prototype: on-device latency feel, the
colour/opacity/texture/AA pixel diff, undo/redo + layer-commit + eraser
(incl. non-stroke kinds) behavioural checks, the highlighter width calibration
and pencil tilt-width/grain from I0.7/I4, and confirming the overlay's
touch-pass-through and front-buffer compositing on the S25 Ultra panel before
ink can become default-on.

### I2 — what's verified headless vs. deferred to device

The full default-on checklist and the reproducible on-device harness live in
[`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md). Summary of this phase's work:

- **Verified headless (permanent JVM tests, `data.ink.parity.*`):**
  - **Eraser parity across every `NoteItem` kind.** The per-kind eraser dispatch
    was lifted out of `DrawingSurface.eraseAtLastSample` into a pure
    `EraserHitTest` helper; `EraserHitTestParityTest` proves strokes, shapes,
    stickies, connectors, and paths all still hit-test through `HitTest` — and
    that the non-stroke kinds erase **without ever touching stroke geometry**, so
    ink's stroke-only mesh hit-testing can't displace them. It also proves a
    committed ink-authored stroke erases byte-for-byte like a hand-drawn one.
  - **Commit-pipeline + audio-timestamp correctness.** `InkCommitParityTest`
    proves the two-clock reconciliation survives the authoring round-trip
    (ink sees stroke-relative time; the commit restores recording-relative time),
    that no-recording strokes stay v1, and that the committed payload decodes to
    the canonical `[x,y,p,t]` lanes the AI `edit-ops` pipeline reads — `StrokeCodec`
    stays canonical and the AI pipeline never sees ink (Adoption principle 2).
  - **Rendering taper measurement + footprint geometry parity.**
    `InkRenderParityTest` adds a perpendicular cross-section **width-profile**
    measurement and, with it, **pins the taper gap**: the current engine tapers
    ~2.5× over a pressure ramp, while ink's *stable stock* `pressurePen` holds a
    constant `size`-width tube — pressure taper needs a custom
    `BrushTip`/`BrushBehavior` and is an explicit **I4** item, so this is a parity
    *gap*, not parity. It also promotes the throwaway I0.7 coverage spike into a
    **permanent** per-tool footprint regression guard (no tool may regress to
    NO_GO; pen/marker stay GO).
- **Deferred to device / I4 (gate still open → ink stays default-off):**
  the on-device colour/opacity/**texture**/AA pixel diff via `CanvasStrokeRenderer`,
  latency + front-buffer compositing feel, the overlay's touch pass-through, and
  the I4 brush-richness work (jitter, procedural texture, highlighter-width and
  pencil tilt/grain). Until the on-device harness passes and I4 closes the
  texture/jitter gap, the "Ink engine (experimental)" switch is **not** flipped.

### I5 — what shipped (headless) vs. device-only

The live-beautify (N3) clean-snap, built on the I1 authoring path and kept
**default-off** (it does not flip the I2 switch):

- **Three-stage clean.** `InkBeautifier` now runs an **input-smoothing low-pass**
  (`StrokeSmoothing`) before its existing RDP de-noise + Chaikin. The low-pass is
  a binomial (`0.25/0.5/0.25`) centerline filter — endpoint- and
  monotone-timestamp-preserving, stride-agnostic, pure and deterministic. It is
  the **headless stand-in** for `ink-authoring`'s device-side input modeler:
  that modeler lives in the Android-only `ink-authoring` module (off the
  unit-test classpath) and only shapes the *wet* rendering — the finished
  `Stroke.inputs` ink hands back on pen-lift are raw — so to feed "ink-style"
  smoothing into beautify *and* verify it headless, I5 re-expresses the low-pass
  in pure JVM.
- **Both commit paths.** Beautify is offered identically from `commitLiveStroke`
  (legacy quad-Bézier path) and `onInkStrokesFinished` (ink-authoring path), so
  turning ink on doesn't change the clean behaviour.
- **Candidate + ghost, not in-place mutation.** The pen-lift commits the **raw**
  stroke; `InkBeautifier.candidate()` returns the cleaned samples plus a
  `changed` flag (max-deviation vs the raw, normalised by the stroke's bbox —
  so dots/already-clean strokes don't nag). When changed, `DrawingSurface` stages
  a translucent ghost; the next touch-down resolves it (tap on it → accept, tap
  elsewhere → decline). Accept fires `onStrokeBeautifyAccepted`, which swaps
  raw → beautified via one `CompositeEdit("Beautify")` (matched by id through
  `modified`) so a single undo restores the raw ink — the same swap model as
  hold-to-recognize, which still takes precedence when a hold is detected.
- **Inviolable contract held.** The beautified payload is encoded in the raw
  stroke's own codec stride (v2 keeps its per-sample `t` lane, so audio sync
  survives) and stays a canonical `StrokeCodec` `STROKE_KIND` item — the AI
  edit-ops pipeline never sees ink (Adoption principle 2).

- **Verified headless (permanent JVM tests):**
  - `StrokeSmoothingTest` — endpoint preservation, jitter attenuation,
    sample-count + monotone-time invariance, iteration monotonicity,
    no-input-mutation, determinism.
  - `InkBeautifierTest` (extended) — `beautify == candidate.samples`, and the
    offer decision (noisy → offered, clean line / short stroke → not offered).
  - `InkSmoothParityTest` (`data.ink.parity.*`) — the **ink-native** slice:
    builds real ink `Stroke`s through `InkInterop` and measures ink's
    `PartitionedMesh` outline. The beautified stroke renders with a ~6× smaller
    total turning sum than the raw (60.1 → 9.7 radians), and the clean stays
    faithful (max deviation ~3.3% of the stroke's bbox diagonal). This is the
    genuine "ink input smoothing makes the snap cleaner", proven against ink's
    own geometry on the headless container.
- **Deferred to device (documented checklist, not claimed to pass):** the
  on-screen ghost appearance, the tap-to-accept feel, and whether the live ink
  wet-layer smoothing is visibly cleaner on the S25 Ultra. See
  [`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md), section E.

### I6 — what shipped (headless) vs. device-only

Mesh-backed geometry adoption, built on the `ink-geometry` core that runs on the
headless JVM (`ink-geometry-jvm` + `libink.so`) and kept **default-off** — the
mesh path is gated behind the existing ink switch, so I6 is **not** a trigger to
flip the I2 default-on switch.

- **The derived layer (`data.ink`).** All new code is on the stable
  strokes/brush/geometry classpath split (no `ink-rendering`/`ink-authoring`), so
  it is fully pure-JVM testable:
  - [`StrokeMeshCache`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeMeshCache.kt)
    — derived per-stroke `PartitionedMesh`, **never on-disk truth**, rebuilt from
    canonical `StrokeCodec` payloads via [`InkInterop`]. Lazy: `register` records
    only a cheap padded centerline AABB (no native build) so a large note can be
    pre-filtered before any mesh is materialised. **Signature-keyed** (payload +
    tool + width) invalidation handles transform/restyle/delete correctly,
    including the same-id `item.copy(payload=…)` edit a pure id cache serves
    stale — this closes the "Geometry cache correctness" open question.
  - [`SpatialIndex`](../app/src/main/java/com/aichat/sandbox/data/ink/SpatialIndex.kt)
    — uniform-grid AABB prefilter (the large-note bounding-box prefilter),
    deterministic insertion-ordered queries.
  - [`LassoTriangulation`](../app/src/main/java/com/aichat/sandbox/data/ink/LassoTriangulation.kt)
    — ear-clips the lasso loop into triangles, because the stable `ink-geometry`
    artifact exposes no public polygon→`PartitionedMesh` builder, only primitive
    intersection. A stroke is selected when its mesh hits *any* loop triangle.
  - [`InkGeometry`](../app/src/main/java/com/aichat/sandbox/data/ink/InkGeometry.kt)
    / [`MeshHitTest`](../app/src/main/java/com/aichat/sandbox/data/ink/MeshHitTest.kt)
    — robust point/box/triangle intersection + coverage (identity transform,
    world coords), and the **fallback-aware** façade: every entry point takes a
    `fallback` lambda wrapping the existing point-to-segment loop, so a null mesh
    (unregistered / build failure / ink-off) degrades to today's behaviour.
- **Live wiring (gated, fallback-capable).** `NoteEditorViewModel.onLassoCompleted`
  and `DrawingSurface.eraseAtLastSample` route the **stroke** kind through
  `MeshHitTest` only when ink is on; the cache is invalidated in lockstep with
  `decodedCache` (`replayItems` retain/register, erase removal). **Non-stroke
  kinds** (shapes, stickies, connectors, paths) always stay on `EraserHitTest` /
  `HitTest`, so ink's stroke-only mesh can never displace them (the I2 eraser
  guarantee holds).
- **Verified headless (permanent JVM tests):**
  - `SpatialIndexTest`, `LassoTriangulationTest` — the pure prefilter + ear-clip
    (overlap correctness, transform re-index, determinism; exact area-tiling for
    convex *and* concave loops).
  - `data.ink.parity.MeshGeometryParityTest` — against the **real ink engine**:
    the eraser mesh hits a wide stroke's body where the centerline fallback misses
    (partial-erase accuracy); the lasso mesh selects a stroke the loop *crosses*
    with no sample inside it (overlapping-stroke accuracy); a null/empty-triangle
    mesh defers to the exact fallback (so ink-off is unchanged); and the cache
    infrastructure — signature reuse-vs-rebuild on restyle/transform, `retain`
    delete, the spatial prefilter culling a far stroke, and the deterministic
    region-query id list feeding an id→layer map.
- **Deferred to device (documented checklist, not claimed to pass):** the *felt*
  accuracy of the ink-on mesh eraser/lasso on the S25 Ultra, and the **lasso
  contract change** (the mesh path catches strokes the old sample loop missed) as
  a UX decision to confirm before the mesh path rides along with the default-on
  flip. See [`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md), section F.

### I7 — what shipped (headless) vs. device-only

Select-similar + the constraint/snap engine (N2, idea #8), built on the I6
mesh/cache/index layer and kept **default-off** — both features are gated behind
the ink switch, so I7 is **not** a trigger to flip the I2 default-on switch.

- **The local geometry core (`data.ink`, pure JVM).** All new code is on the
  stable strokes/brush/geometry classpath split (no `ink-rendering`/`ink-authoring`),
  fully pure-JVM testable, and never persisted (`StrokeCodec` stays canonical):
  - [`StrokeSimilarity`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeSimilarity.kt)
    — a stroke → scale/translation-invariant shape descriptor (aspect,
    straightness, turning) + style features (tool/width/colour) → a `[0,1]`
    similarity. The cheap, offline candidate metric.
  - [`SelectSimilar`](../app/src/main/java/com/aichat/sandbox/data/ink/SelectSimilar.kt)
    — deterministic ranking of candidates vs the tapped stroke (target first,
    then descending score, stable on ties), with a threshold gate.
  - [`ConstraintSnap`](../app/src/main/java/com/aichat/sandbox/data/ink/ConstraintSnap.kt)
    — near-alignment / even-spacing / near-symmetry detection over item AABBs,
    conservative (only tidies near-regular layouts, only above a `minMove`), with
    a conflict-free per-axis `resolve`.
- **Live wiring (gated, edit-ops-shaped).** `NoteEditorViewModel.selectSimilarTo`
  lands its result in the existing `selection` model (reusing the lasso's
  group-expansion + locked-layer rules); `proposeSnaps` converts the resolved
  nudges to `EditOp.Transform`s and stages them as a `PendingEdit` (the same
  accept/decline chips as AI edits, via a `stageLocalEdit` that uses identity
  short-id↔uuid maps); `aiRankSelection` optionally routes the group through the
  unchanged `submitAiEdit` EDIT pipeline. With ink off all three are inert.
- **Verified headless (permanent JVM tests):**
  - `StrokeSimilarityTest`, `SelectSimilarTest`, `ConstraintSnapTest` — the pure
    similarity metric (identity, translation/scale invariance, shape/style
    separation, determinism), the ranker (selects similar, excludes dissimilar,
    threshold gate, stable order), and each snap detection in isolation plus the
    conflict-resolution and conservatism guards.
  - `data.ink.parity.SelectSimilarSnapParityTest` — against the **real ink
    engine**: the descriptor proxy ranks candidates the *same way* ink's mesh
    overlap (IoU, computed from `PartitionedMesh` in a normalised frame) does, the
    local top-match agrees with ink geometry, and a snap translation applied to
    the canonical payload makes the **real ink mesh** left-extents coincide — the
    engine snaps geometry, not just a bounding box.
- **Deferred to device (documented checklist, not claimed to pass):** the *felt*
  tap-to-select-similar gesture, the snap-chip preview appearance/feel, and the
  **AI ranking quality** ("which of these belong together") on large, busy notes —
  see [`INK_I2_PARITY_GATE.md`](INK_I2_PARITY_GATE.md), section G.

## Risks & open questions

- **Rendering parity (the gating risk).** Because ink leads the live path, its
  mesh rendering must visually match `StrokeRenderer`'s variable-width Bézier
  output (taper/jitter/texture). Keep `DrawingSurface` as a fallback until I2
  demonstrates parity; only then retire it.
- **Default-on checklist.** Before ink becomes default, verify latency on-device,
  pen/pencil/highlighter/marker output, undo/redo, layer commit, eraser behavior,
  shape recognition, and audio timestamp sync. The eraser check must explicitly
  confirm **no regression for non-stroke `NoteItem` kinds** — the current eraser
  hit-tests strokes, shapes, stickies, connectors, and paths, and ink's
  stroke-only mesh hit-testing must not displace that. The fallback switch must
  be able to recover without data loss.
- **Storage ambiguity — resolved.** `StrokeCodec` stays canonical permanently and
  the AI edit-ops pipeline is an inviolable invariant (see Adoption principle 2),
  so ink-native canonical storage is off the table. The features that seem to
  want ink-native storage don't: select-similar/snap (N2) needs a *derived,
  cached* `PartitionedMesh`, and replay (N4) needs the v2 timestamps `StrokeCodec`
  already carries — neither requires changing the source of truth. I3 only
  decides whether any *additive* dual-write data is worth its write-path
  consistency cost, and the exact v3 orientation/timestamp layout.
- **Toolchain upgrade.** The minSdk 36 bump drags AGP/Gradle/Kotlin forward
  (I0.5). Self-contained, but verify the build + the `CLAUDE.md` SDK-install
  steps still hold afterward.
- **APK size / native libs.** ink bundles a native core (`ink-nativeloader`).
  Less of a concern for a personal single-ABI (arm64) install, but worth a
  glance.
- **Alpha vs stable.** Programmable brushes (N1) rely on **1.1.0-alpha**; pin a
  version and isolate behind the brush path until 1.1 stabilizes. Do not let the
  alpha API block the stable `BrushPreset → Brush` adapter or authoring
  migration. Core authoring/geometry/rendering are on **stable 1.0.0**.
- **Geometry cache correctness — addressed in I6.** Mesh-backed hit testing and
  selection need cache invalidation for transforms/restyles/deletes, spatial
  prefiltering for large notes, and deterministic mapping from geometry hits back
  to note items/layers. [`StrokeMeshCache`](../app/src/main/java/com/aichat/sandbox/data/ink/StrokeMeshCache.kt)
  keys each slot on a **content signature** (payload bytes + tool + width), so an
  in-place edit (`item.copy(payload = …)` keeps the id but changes geometry —
  which the id-only `decodedCache` would serve stale) bumps the signature and
  rebuilds; deletes drop via `retain(keep)`. The [`SpatialIndex`](../app/src/main/java/com/aichat/sandbox/data/ink/SpatialIndex.kt)
  gives the large-note prefilter and `MeshHitTest.strokesInRegion` the
  deterministic id mapping. All pinned by `data.ink.*` JVM tests.
- **Select-similar / snap quality (the I7 risk) — partially addressed.** The
  risk for I7 is *ranking + UX quality*, not correctness: whether the local
  similarity threshold picks the marks a human would call "the same", whether the
  snap engine's tolerances tidy a layout without fighting the user's intent, and
  whether the optional AI "which belong together" ranking helps more than it
  confuses. The geometry is pinned headless (the descriptor proxy is held honest
  against ink's real mesh IoU; each snap detection is unit-tested; conservatism
  guards stop irregular layouts being reflowed), but the felt thresholds, the
  snap-chip UX, and AI-ranking usefulness on large notes are **device-only** (see
  the gate doc, section G) — and the whole feature is gated behind the default-off
  ink switch, so it can't regress today's selection/erase.
- **Tutor content quality.** Replay is technically straightforward once
  timestamps exist; the risk is whether generated construction strokes are
  simple, ordered, non-cluttered, and actually useful to trace. (This is N4/I8,
  not I7.)
- **No BLE S-Pen.** The S25 Ultra pen has no Bluetooth — don't plan air-action /
  remote-button features. Digitizer-side button (eraser override) still works.
- **Orientation lane (opportunity, not risk).** Capturing orientation as a v3
  lane is feasible on the target device; sequence it with I3 if a brush behavior
  actually wants it.
