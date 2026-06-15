# Ink I2 — Rendering + Behaviour Parity Gate

Status: **in progress — headless slice landed; I4 brush gaps closed; I5 beautify,
I6 mesh-backed geometry, and I7 select-similar + snapping added (all headless,
default-off); device-only items open. Ink stays default-OFF.** This is the default-on gate the
migration plan
([`ANDROIDX_INK_MIGRATION_PLAN.md`](ANDROIDX_INK_MIGRATION_PLAN.md), phase **I2**)
requires before the "Ink engine (experimental)" switch
(`ToolPalettePrefsStore.inkAuthoring`) can flip from opt-in fallback to the
default live-drawing engine.

> **Bottom line:** every part of the gate that can be proven on the headless
> cloud container **is** proven — eraser parity across all `NoteItem` kinds,
> commit-pipeline + audio-timestamp correctness, and the per-tool footprint
> geometry — and these now ride as permanent JVM regression tests. The
> **rendering gap this gate originally surfaced** (ink's stable stock
> `pressurePen` rendered a constant-width tube, no pressure taper) has now been
> **closed by phase I4**: a stable custom `BrushTip`/`BrushBehavior` adapter
> ([`InkBrushFamilies`](../app/src/main/java/com/aichat/sandbox/data/ink/InkBrushFamilies.kt))
> makes ink track `StrokeRenderer`'s pressure taper (pen), tilt-width (pencil),
> and highlighter width — all measured at parity headless (correlation > 0.999;
> highlighter areaRatio 0.71×→~1.0×; every tool now **GO**). What remains for the
> default-on flip genuinely needs the **S25 Ultra** (on-device latency feel,
> front-buffer compositing, the live colour/opacity/**texture**/AA pixel diff,
> and overlay touch pass-through) plus the two brush items that are *not*
> headless-expressible — **procedural texture** (a `BrushPaint.TextureLayer`
> device-pixel item) and **jitter** (no stable randomized-source exists; it lives
> in the isolated 1.1-alpha path). Until the device harness passes, **ink is not
> default-on** and the switch stays off — exactly as Adoption principle 1
> requires.

This repo's build/test environment is a **headless container with no Android
device or emulator** (per `CLAUDE.md`). So this gate is split deliberately into
two columns: what we *verified headless* (with the test that proves it), and what
remains a *documented, reproducible on-device checklist* that a maintainer runs
on the target hardware before flipping the default. Nothing below claims a
device-only item passed.

---

## How to run the headless gate

```bash
# The full I2 headless parity suite (eraser + commit + render parity):
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.ink.parity.*"

# Plus the I0 seam + I0.7 coverage spike it builds on:
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.ink.*"
```

Reports written by the run:
- `app/build/reports/ink-i2/parity-coverage.txt` — per-tool footprint verdicts
  (the I0.7 corpus, re-asserted as a permanent gate).

---

## Checklist — verified headless vs. device-only

| # | Gate item | Status | Evidence / how |
|---|---|---|---|
| 1 | **Eraser — stroke kind** still hit-tests via `HitTest` | ✅ headless | `EraserHitTestParityTest.strokeKindHitsAndMisses` |
| 2 | **Eraser — no regression for non-stroke kinds** (shapes, stickies, connectors, paths) | ✅ headless | `EraserHitTestParityTest` per-kind tests + `nonStrokeKindsDoNotDependOnStrokeGeometry` |
| 3 | **Eraser — ink-authored stroke erases identically** to a hand-drawn one | ✅ headless | `EraserHitTestParityTest.inkAuthoredStrokeErasesIdenticallyToHandDrawn` |
| 4 | **Audio timestamp sync** (two-clock reconciliation survives the authoring round-trip) | ✅ headless | `InkCommitParityTest.audioTimestampSyncSurvivesAuthoringRoundTrip` |
| 5 | **`StrokeCodec` canonical / AI pipeline never sees ink** at the commit seam | ✅ headless | `InkCommitParityTest.committedPayloadIsCanonicalForAiPipeline`, `noRecordingCommitsV1` |
| 6 | **Rendering taper parity** — ink local width follows the `ToolDynamics` pressure curve | ✅ **closed by I4** | `InkRenderParityTest.pressureTaperMatchesCurrentEngineAfterI4` (was `…IsCurrentEngineOnlyUntilI4`): the I4 custom pen family drives `SIZE_MULTIPLIER` from `NORMALIZED_PRESSURE` (`0.35×–1.15×`, `pressure^0.7`), so ink now tapers ~2.4× over the ramp vs the engine's ~2.5×, **correlation 0.9997**. Pencil tilt-width is closed the same way (`pencilTiltWidthMatchesCurrentEngineAfterI4`, corr 0.99997). |
| 7 | **Footprint parity** per tool (pen/pencil/highlighter/marker) | ✅ headless (geometry) | `InkRenderParityTest.footprintVerdictsHoldAsPermanentGate`. After I4 **all four tools are GO** (pen cov 0.81→0.92, pencil 0.84→0.94, highlighter 0.69→0.97, marker 0.89); highlighter areaRatio 0.71×→1.01× via `highlighterFootprintMatchesAfterI4`. |
| 8 | **Brush colour / opacity mapping** matches the preset semantics | ✅ headless (analytic) | `InkInteropTest.toBrushFoldsOpacityIntoAlpha`, `applyOpacityToArgbClampsAndPreservesRgb` |
| 9 | **Undo/redo + layer commit** go through the *same* listener pipeline as a hand-drawn stroke | ◑ headless reasoning, device feel open | ink commits via `buildStrokeItem` → `strokeListener` (shared with `commitLiveStroke`); the committed item is a normal `STROKE_KIND` `NoteItem` (#3/#5). Behavioural undo/redo/layer feel needs the device. |
| 10 | **Shape recognition** (hold-to-recognize) preserved on the ink path | ◑ headless reasoning, device feel open | `finishInkStroke` carries the hold decision into `onInkStrokesFinished`, which fires `strokeHoldRecognizeListener` exactly like `commitLiveStroke`. The gesture itself needs the device. |
| 11 | **Colour / opacity / texture / anti-aliasing pixel diff** on the target panel | ⬜ device-only | `CanvasStrokeRenderer` on the S25 Ultra — see "On-device harness". Brush-identity colour/alpha is #8; *blending/texture/AA* are not headless-reproducible. |
| 12 | **On-device latency feel + front-buffer compositing** | ⬜ device-only | High-refresh LTPO panel; not reproducible headless. |
| 13 | **Overlay touch pass-through** (the `InProgressStrokesView` sibling doesn't steal/duplicate input) | ⬜ device-only | Needs real `MotionEvent` dispatch through the `FrameLayout` stack. |
| 14 | **Fallback recovers without data loss** (toggle off mid-stroke; ink call failure drops one stroke) | ◑ logic in place, device feel open | `detachInkAuthoring`/`cancelInkStroke` guard every ink call; the in-flight stroke is abandoned cleanly. Verified by reading the lifecycle; the *felt* recovery needs the device. |
| 15 | **Jitter + procedural texture parity** (pen grain, marker/watercolor tiles) | ◑ **partially closed by I4** | I4 closed the *geometry* brush gaps (taper, tilt-width, highlighter width — items 6/7). **Procedural texture** stays deferred: it's a `BrushPaint.TextureLayer` appearance item that needs the on-device `CanvasStrokeRenderer` pixel pass (item 11). **Jitter** stays deferred: stable 1.0.0 exposes no randomized/noise `BrushBehavior.Source`, so a faithful jitter brush is only expressible on the isolated 1.1-alpha path (`data.ink.experimental.InkProgrammableBrush`) — never on the stable seam. |

| 16 | **Live beautify (I5/N3) geometry** — input-smoothing low-pass + RDP + Chaikin clean, and the ink-rendered result is smoother | ✅ headless | `StrokeSmoothingTest`, `InkBeautifierTest` (candidate/offer), `InkSmoothParityTest` — ink's native mesh outline turning sum drops ~6× (60.1→9.7) after beautify, faithful to within ~3.3% of the bbox diagonal |
| 17 | **Live beautify ghost appearance + tap-to-accept feel** | ⬜ device-only | The translucent candidate overlay and the confirm-tap target; logic/state wired (`pendingBeautify` → `onStrokeBeautifyAccepted` → one `CompositeEdit`), appearance needs the panel — see "On-device harness" E |
| 18 | **Mesh-backed geometry (I6) — cache + prefilter + hit-test accuracy** | ✅ headless | `SpatialIndexTest`, `LassoTriangulationTest`, `data.ink.parity.MeshGeometryParityTest`: signature invalidation (transform/restyle/delete), spatial prefilter, deterministic id mapping, and — against the real ink engine — the eraser hitting a wide stroke's body the centerline misses + the lasso selecting a *crossing* stroke the sample loop misses, each falling back exactly to the loop when no mesh |
| 19 | **Mesh-backed eraser/lasso on-device feel + lasso contract change** | ⬜ device-only | The ink-on mesh path is gated behind the ink switch (default-off = byte-identical fallback); the *felt* accuracy and the UX decision on the wider lasso contract need the S25 Ultra — see "On-device harness" F |
| 20 | **Select-similar (I7) — local similarity + ranking, and agreement with ink geometry** | ✅ headless | `StrokeSimilarityTest`, `SelectSimilarTest`, and `data.ink.parity.SelectSimilarSnapParityTest`: the scale/translation-invariant descriptor metric, the deterministic ranker, and — against the **real ink engine** — that the cheap descriptor proxy ranks candidates the same way ink's mesh IoU does (and picks the same top match) |
| 21 | **Constraint/snap engine (I7) — detection + edit-ops snap of real geometry** | ✅ headless | `ConstraintSnapTest` (alignment / even-spacing / symmetry detection, conflict-free `resolve`, conservatism guards) + `SelectSimilarSnapParityTest.snapTranslationAlignsRealInkGeometry`: a snap, applied as an `EditOp.Transform` translation on the canonical payload, makes the **real ink mesh** left-extents coincide |
| 22 | **Select-similar / snap on-device feel + AI-ranking quality** | ⬜ device-only | The felt tap-to-select-similar gesture, the snap-chip preview appearance, and the optional AI "which belong together" ranking quality on large notes need the S25 Ultra — see "On-device harness" G. Gated behind ink-on (default-off), so today's selection/erase is unaffected |

Legend: ✅ verified headless · ◑ logic verified headless, on-device confirmation still owed · ⬜ device-only / deferred.

---

## On-device harness (reproducible, run on the S25 Ultra)

These steps are the device column of the gate. Run them on the target hardware
with the ink switch **temporarily** forced on (overflow menu → "Ink engine
(experimental)"). Record pass/fail per row before considering the default flip.

### A. Colour / opacity / texture / AA pixel diff (item 11)
The I0.7 spike compared *coverage geometry* headless and explicitly deferred the
pixel-level appearance. To close it:
1. Draw the I0.7 corpus (16 shapes × pen/pencil/highlighter/marker) once with the
   switch **off** (current `StrokeRenderer`) and once **on** (ink +
   `CanvasStrokeRenderer`), at the same colour/opacity/texture per tool.
2. Capture each layer to a bitmap (`NoteRasterizer` already renders committed
   strokes; add an equivalent capture of the ink wet layer, or screenshot).
3. Diff per-pixel (ΔE on colour, alpha delta on opacity). I4 calibrated the
   highlighter width (the headless footprint is now ~1.0× — no longer the ~0.71×
   I0.7 saw), so expect width parity there; **expect remaining divergence on
   textured tools** (pencil grain, marker/watercolor tiles) — that procedural
   texture is the `BrushPaint.TextureLayer` work still deferred past I4. Record
   the magnitude.

### B. Latency + front-buffer compositing (item 12)
4. With the switch on, draw fast loops/zigzags; confirm the wet line tracks the
   S-Pen with the front-buffered low-latency feel and no visible lag step at
   pen-down. Compare side-by-side against the switch-off path.

### C. Overlay touch pass-through (item 13)
5. Confirm a single stylus stroke produces exactly one committed `NoteItem` (no
   doubling between `DrawingSurface` and the overlay), that eraser/lasso/shape/
   text/connector tools still reach `DrawingSurface` with the overlay attached,
   and that finger pan/zoom is unaffected.

### D. Behavioural sweeps (items 9, 10, 14)
6. Undo/redo a mix of ink-authored and hand-drawn strokes; confirm one undo
   removes one stroke and order is preserved.
7. Commit ink strokes onto named/locked/hidden layers; confirm layer assignment,
   z-order, and that locked/hidden layers behave as before.
8. Hold-to-recognize: draw a rough shape on the ink path and hold before lifting;
   confirm the shape replaces the stroke as one undoable edit.
9. Toggle the switch off mid-stroke and force an ink failure (e.g. detach); confirm
   no half-drawn or lost stroke and a clean fall back to the quad-Bézier path.

### E. Live beautify ghost + accept (items 16, 17 — phase I5)
The beautify **geometry** is verified headless (item 16). What needs the device
is the **preview surface**, with the ink switch forced on and beautify enabled:
10. Draw a jittery stroke; confirm the raw stroke commits and a translucent
    **cleaned ghost** appears over it. Confirm the ghost is the smoother line
    (it should read as visibly de-noised vs the raw beneath it).
11. **Tap the ghost** → it replaces the raw stroke as one edit; a single **undo**
    restores the exact raw ink. **Tap away** (or start a new stroke) → the ghost
    dismisses and the raw stroke is kept untouched.
12. Confirm a **hold-to-recognize** gesture still wins (a held lift recognizes a
    shape and no beautify ghost is offered for that stroke).
13. With a recording active, beautify a stroke and confirm **audio sync** holds
    (the v2 `t` lane survives the clean — headless-checked, re-confirm the felt
    replay timing on device).

---

### F. Mesh-backed eraser / lasso (items 18, 19 — phase I6)
The mesh geometry, cache, prefilter, and hit-test accuracy are verified headless
(item 18). What needs the device is the **felt** behaviour, with the ink switch
forced on:
14. Erase across a **thick** stroke (e.g. a wide marker/highlighter); confirm the
    eraser bites where the visible body is, not only along the centerline, and
    that thin strokes erase as before.
15. Lasso a cluster of **overlapping** strokes, including one whose body crosses
    the loop but whose endpoints sit outside it; confirm the crossing stroke is
    selected (the mesh contract) and decide whether that wider contract is the
    desired UX before it rides along with the default-on flip. Compare against the
    switch-off lasso.
16. On a **large** note (many strokes), confirm eraser/lasso stay responsive —
    the `SpatialIndex` prefilter should keep mesh queries off the strokes far from
    the gesture.
17. Erase / restyle / transform / undo a stroke and immediately re-query; confirm
    the derived mesh tracks the edit (signature invalidation) with no stale hits.

### G. Select-similar + snapping (items 20, 21, 22 — phase I7)
The similarity metric, the ranker, and the constraint/snap detection are verified
headless (items 20, 21), including against the real ink engine. What needs the
device is the **felt UX** and the **ranking quality**, with the ink switch forced
on:
18. Tap a stroke among a cluster of similar marks (e.g. several tick marks /
    leaves / arrowheads) and confirm "select similar" grabs the matching ones and
    leaves unrelated strokes out; sweep the threshold for over-/under-selection.
19. With a near-aligned / near-evenly-spaced / near-symmetric selection, invoke
    snapping and confirm the proposed nudges read as "tidy this up", that the
    accept/decline chips behave exactly like an AI edit (one undo reverts the
    whole snap), and that a deliberately irregular layout is left alone.
20. Optionally run the AI "group similar" ranking on a large, busy note and judge
    whether its grouping/recolor suggestions help more than they confuse — the
    open *quality* question for I7.

## Decision

**Ink remains default-OFF.** Phase **I6** adds the mesh-backed geometry layer
(items 18, 19) entirely behind the ink switch, so it does **not** change this
decision: with ink off, the eraser and lasso run the unchanged point-to-segment
fallback. The headless half (item 18) is locked in by permanent JVM tests; the
on-device feel and the lasso-contract UX call (item 19) join the device column
(section F).

Phase **I7** adds select-similar + the constraint/snap engine (items 20, 21, 22),
again **entirely behind the ink switch**, so it likewise does **not** change this
decision. The geometry — the local similarity/ranking and the snap detection — is
locked in by permanent JVM tests (including agreement with the real ink mesh
overlap), and snaps surface as ordinary `EditOp.Transform`s through the existing
accept/decline chips, so `StrokeCodec` stays canonical and the AI pipeline is
untouched. The felt tap-to-select / snap-chip UX and the optional AI-ranking
quality (item 22) join the device column (section G).

The headless half of the gate (items 1–8, plus the
shared-pipeline reasoning for 9–10 and 14) is complete and locked in by permanent
JVM tests, and **phase I4 has now closed the brush-geometry gaps** (items 6 + 7,
and the geometry half of 15): pressure taper, pencil tilt-width, and highlighter
width are all measured at parity headless. The default-on flip is now blocked
only on:

- **Device-only items 11–13** (the colour/opacity/**texture**/AA pixel diff,
  latency/front-buffer feel, and overlay touch pass-through), which this
  environment cannot execute, and
- **The two non-geometry brush items** that I4 deliberately left deferred:
  **procedural texture** (a `BrushPaint.TextureLayer` appearance item folded into
  the device pixel diff, item 11) and **jitter** (no stable randomized source;
  isolated to the 1.1-alpha path). Neither is a headless-expressible regression.

**Phase I5 (live beautify / N3)** adds two rows (16, 17): the beautify
**geometry** is closed headless — including against the *real* ink engine
(`InkSmoothParityTest` shows ink renders the beautified stroke with a ~6×
smaller mesh-outline turning sum) — while the ghost **appearance** and
tap-to-accept feel join the device-only column (section E). I5 builds on the
authoring path but does **not** change this decision.

Per Adoption principle 1 ("ink is the intended primary, the flag exists to fall
*back*, not to opt *in* — but it still needs a checklist before default-on"), the
switch is **not** flipped until the on-device harness above passes. This document
is the live record of what remains.
