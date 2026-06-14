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
2. **Storage is conservative until parity is proven.** No migration of existing
   personal notes. `StrokeCodec` remains the canonical on-disk format through
   I0–I2, while ink payloads may be written as optional/derived data for
   validation. After rendering + authoring parity, choose either continued
   `StrokeCodec` canonical storage, dual-write, or an ink-native new-stroke
   format — but always keep legacy v1/v2 decode so nothing already drawn is
   lost.
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
- **Prerequisite:** add a mesh/cache/index layer before product UX: per-stroke
  mesh cache, invalidation on transform/restyle/delete, bounding-box or spatial
  prefilter, and a reliable mapping from mesh hits back to note item IDs/layers.

### N3. Live beautify via ink smoothing  (upgrade of idea #1)
- **What:** on pen-lift, offer a one-tap clean snap. ink's input smoothing +
  our `InkBeautifier` (RDP + Chaikin) + `ShapeRecognizer` combine for a
  noticeably cleaner result than today.
- **Ink:** `ink-authoring` input smoothing on the live batch; optionally render
  the candidate via `CanvasStrokeRenderer` for a crisp preview.
- **Pipeline:** purely local for the geometric clean; AI only consulted for the
  ambiguous "did you mean this shape?" cases (reuses `AUTO_SHAPE` /
  `replace_with_shape`). Ghost-preview the beautified stroke; tap to accept.

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
| **I0.7 — Rendering fidelity spike** | Render 50–100 representative strokes through both `StrokeRenderer` and `CanvasStrokeRenderer`; pixel/visual diff; per-tool go/no-go (pen, pencil, highlighter, marker) **before** building the authoring path | rendering, brush | Low — throwaway |
| **I1 — Authoring prototype (ink-first)** | `InProgressStrokesView` wired finish→convert→commit behind a fallback-capable runtime switch; not default until the parity checklist passes | authoring, rendering | Med |
| **I2 — Rendering + behavior parity gate** | Match ink mesh rendering to `StrokeRenderer` (taper/jitter/texture) and verify latency, undo/redo, layer commit, eraser (incl. **no regression for non-stroke kinds** — shapes, stickies, connectors, paths), shape recognition, and audio timestamp sync before default-on | rendering, brush | Med |
| **I3 — Storage decision + optional v3 lane** | Decide `StrokeCodec` canonical vs dual-write vs ink-native new-stroke storage; define exact v3 orientation/timestamp layout if needed | strokes, storage | Med |
| **I4 — Brush richness + N1 foundation** | Stable brush-family mapping first; isolate 1.1-alpha programmable brush experiments; add `DESIGN_BRUSH` only after the spec/adapter settles | brush, rendering | Med |
| **I5 — Live beautify (N3)** | ink input smoothing into the pen-lift beautify flow | authoring, rendering | Low/Med |
| **I6 — Mesh-backed geometry adoption** | Back `HitTest`/`LassoController` with `PartitionedMesh`; add per-stroke mesh cache, invalidation, spatial prefilter, and item/layer mapping | geometry | Low/Med |
| **I7 — Select-similar + snapping (N2, idea #8)** | mesh-based similarity + constraint engine + AI ranking | geometry | Med |
| **I8 — Replay / draw-with-me (N4, idea #7)** | timestamp-driven replay, tutor guide layer, timelapse export | strokes, rendering | Med |

Sequencing logic: **I0 (the seam) is a hard prerequisite** for everything. The
ink-first stance pulls the **authoring path forward to I1** — it's the headline
win on this hardware and the rest composes around it — but the **I0.7 fidelity
spike gates I1**: a cheap throwaway render comparison tells us which tools can
ship through ink rendering *before* we invest in `InProgressStrokesView` wiring,
and I2 is the full default-on gate, not an afterthought. I3 settles storage before the app accumulates new
ink-authored notes. Brushes (I4) and beautify (I5) build directly on the new
authoring path; geometry/snapping/replay (I6–I8) layer on once the engine is the
default.

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
- **Storage ambiguity.** Keep `StrokeCodec` canonical through I0–I2; use optional
  ink payloads only as derived/validation data. I3 must explicitly choose the
  long-term canonical format and any v3 orientation/timestamp binary layout.
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
- **Geometry cache correctness.** Mesh-backed hit testing and selection need
  cache invalidation for transforms/restyles/deletes, spatial prefiltering for
  large notes, and deterministic mapping from geometry hits back to note
  items/layers.
- **Tutor content quality.** Replay is technically straightforward once
  timestamps exist; the risk is whether generated construction strokes are
  simple, ordered, non-cluttered, and actually useful to trace.
- **No BLE S-Pen.** The S25 Ultra pen has no Bluetooth — don't plan air-action /
  remote-button features. Digitizer-side button (eraser override) still works.
- **Orientation lane (opportunity, not risk).** Capturing orientation as a v3
  lane is feasible on the target device; sequence it with I3 if a brush behavior
  actually wants it.
