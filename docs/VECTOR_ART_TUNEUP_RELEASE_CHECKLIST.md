# Vector Art Tune-Up — Release Checklist

Release-readiness summary for the Vector Tune-Up feature (Phases 1–11).

## Supported import formats

- [x] Android VectorDrawable XML (paste or `.xml` file).
- [x] SVG (paste or `.svg` file) — canonicalized to Android XML on import.
- [x] Project bundle JSON (paste or `.json` file) — imported as a new project.
- [x] File picker (system document picker) for all three; 5 MB cap.

## Supported export formats

- [x] Android VectorDrawable XML.
- [x] SVG (generated from canonical Android XML).
- [x] Project bundle JSON (whole project, re-importable).

## Supported SVG subset

- [x] `<svg>` + `viewBox`, `<path>`, `<rect>`, `<circle>`, `<ellipse>`, `<line>`,
  `<polyline>`, `<polygon>`, `<g>`.
- [x] Presentation attrs: fill/stroke/width/linecap/linejoin/opacity/fill-rule.

## Unsupported SVG features (warn, never crash)

- Gradients, patterns, clip paths, masks, text, images, `<use>`, filters,
  CSS `<style>`, complex transforms. All dropped with `SVG_*` warnings.

## AI safety model

- [x] Model returns a typed edit plan / scene, never app state or files.
- [x] App validates every ID, operation, color, and bound; invalid items dropped.
- [x] Original source XML never mutated; every result is a new version.
- [x] API key required; absence shows a friendly message.
- [x] Expensive AI gated on input health (see large-input limits).

## Bundle import/export behavior

- [x] Schema-1 `vector_tuneup_project` only; crash-proof parser.
- [x] Import creates a NEW project (never overwrites).
- [x] Version IDs remapped; parent lineage preserved; missing/self parents repaired.
- [x] Single original guaranteed; SVG versions canonicalized; invalid XML skipped.
- [x] No merge, no cascade delete, no descendant reparenting, no ZIP/URL bundles.

## Manual edit behavior

- [x] Per-path delete / simplify / recolor / restyle on a selection.
- [x] Batch restyle by color.
- [x] Duplicate selected version as a manual-edit child.
- [x] Delete leaf versions only; original and non-leaf versions protected.

## Known preview limitations

- Approximate Compose rendering, not Android inflation; unsupported paths skipped.
- "Preview unavailable" fallback never blocks diagnostics/edit/export.
- Raster thumbnail cache deferred (`previewPngPath` reserved, unused).

## Large-input limits

- Import cap: **5 MB** (`VectorInputLimits.MAX_IMPORT_BYTES` / `MAX_PASTE_CHARS`).
- Health thresholds (`VectorLargeInputGuard`):
  - bytes: ≤500 KB OK · ≤2 MB LARGE · ≤5 MB EXTREME · >5 MB UNSAFE
  - commands: ≤25k OK · ≤100k LARGE · ≤250k EXTREME · >250k UNSAFE
  - paths: ≤500 OK · ≤2k LARGE · ≤10k EXTREME · >10k UNSAFE
- EXTREME disables AI until the user opts in; UNSAFE blocks AI entirely.

## Accessibility

- [x] Icon-only buttons have content descriptions; text buttons are labeled.
- [x] Text fields and export radio options are labeled.
- [x] Preview surfaces expose a textual content description.
- [x] Health rating is text + color (not color alone).
- [x] Error messages are user-facing, never stack traces.
- [x] Blocked/disabled AI actions explain why nearby.

## Test commands

```bash
# Vector Tune-Up targeted suites (recommended)
./gradlew :app:testDebugUnitTest --console=plain \
  --tests "com.aichat.sandbox.data.vector.*" \
  --tests "com.aichat.sandbox.data.repository.*" \
  --tests "com.aichat.sandbox.ui.screens.vector.*"

# Debug build
./gradlew :app:assembleDebug --console=plain
```

## Known unrelated test failures

- `NoteSvgExporterTest`, `NoteVectorDrawableExporterTest`, `NoteAiServiceTest`
  fail on a clean checkout with `android.graphics` / `android.util.Log`
  "not mocked" errors. Pre-existing, unrelated to Vector Tune-Up; do not block.

## Release-blocking issues

- None known. No `TODO`/`FIXME` blockers in Vector Tune-Up code; all "deferred"
  notes are intentional scope deferrals documented in
  `VECTOR_ART_TUNEUP_LIMITATIONS.md`.

## Test/build results

- Targeted Vector Tune-Up suites (`data.vector.*`, `data.repository.*`,
  `ui.screens.vector.*`): **BUILD SUCCESSFUL** — all green, including the new
  Phase 11 guard/audit/reducer/ViewModel hardening tests.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.
- One non-fatal compiler warning (unused `displayName` param on the spec-mandated
  `importBundleTextFromFile` signature); consistent with existing repo warnings.
