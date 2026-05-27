# Vector Art Tune-Up — Limitations

This documents what the Vector Tune-Up feature intentionally does **not** do, so
expectations are accurate. None of these are bugs.

## Supported SVG subset

Import canonicalizes SVG to Android `VectorDrawable` XML. The supported subset is:

- `<svg>` root with a `viewBox` (or width/height fallback).
- `<path>` with `d` data, plus the basic shapes `<rect>`, `<circle>`,
  `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`.
- `<g>` grouping and presentation attributes: `fill`, `stroke`, `stroke-width`,
  `stroke-linecap`, `stroke-linejoin`, `fill-opacity`, `stroke-opacity`,
  `fill-rule`, and named/hex colors.

## Unsupported features (dropped with a warning, never a crash)

- Gradients (`linearGradient` / `radialGradient`) and pattern fills.
- Clip paths and masks.
- Text (`<text>`), embedded raster images (`<image>`), and `<use>` references.
- Filters, blend modes, and effects.
- CSS `<style>` blocks and external stylesheets.
- Complex `transform` chains beyond what the importer normalizes.

These are reported as `SVG_*` / `*_NOT_SUPPORTED` warnings and the affected
geometry is omitted.

## Preview fidelity

- The preview is an approximate Compose rendering of the parsed paths, **not** a
  pixel-perfect Android `VectorDrawable` inflation.
- Unparseable or unsupported paths are skipped in the preview (with a note); the
  underlying XML is still exported faithfully.
- When nothing can be previewed, the panel shows "Preview unavailable" — metrics,
  diagnostics, editing, and export still work.
- Raster/PNG thumbnail caching is deferred; `previewPngPath` is reserved but unused.

## AI safety bounds

- The model never returns app state or files — it returns a typed edit plan
  (Tune-Up) or scene (Redraw) that the app validates and applies deterministically.
- Unknown path IDs, invalid operations, and out-of-bounds geometry are dropped
  with warnings.
- The original source XML is never mutated; every result is a new version.
- AI requires an API key configured in Settings.

## Large-file guidance

- Imports are capped at **5 MB**; larger files are rejected with a friendly message.
- Inputs are rated OK / Large / Very large / Too large by size, path count, and
  command count.
- **Very large** input disables AI until you opt in; **Too large** input blocks AI
  entirely — run a local Optimize first or import a smaller file.

## Bundle import limitations

- Only schema-1 `vector_tuneup_project` JSON bundles are accepted.
- Import always creates a **new** project; merging into an existing project is not
  supported.
- Version graph repair is conservative: missing/self parents are reparented to the
  root, but descendants are **not** reparented and deletes do **not** cascade.
- Bundles are text JSON only — no ZIP bundles, no binary thumbnails, no import from
  a URL.

## Known unrelated test failures

A clean checkout reports ~22 failures in `NoteSvgExporterTest`,
`NoteVectorDrawableExporterTest`, and `NoteAiServiceTest`. They throw
`android.graphics.Color` / `android.util.Log` "not mocked" errors because those
tests touch real Android framework classes that aren't stubbed in plain JVM unit
tests. They are pre-existing and unrelated to Vector Tune-Up.

## Deferred (not in scope)

Cloud sync, multi-user collaboration, ZIP/encrypted bundles, bundle import from
URL, full SVG feature support, pixel-level visual diff, raw model-generated
XML/SVG, cascade delete / descendant reparenting, a full color picker, and a full
Compose UI test suite.
