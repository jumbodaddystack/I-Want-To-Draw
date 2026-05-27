# Vector Art Tune-Up — User Guide

Vector Tune-Up is a local workspace for cleaning up, redrawing, and exporting
Android `VectorDrawable` artwork. You import a vector, inspect what's wrong with
it, generate improved versions (deterministically or with AI), compare them, and
export the one you like — with every version kept in a branchable history.

## Supported inputs

- **Android VectorDrawable XML** (`<vector>` … `</vector>`).
- **SVG** (`<svg>` … `</svg>`) — converted to Android VectorDrawable internally so
  every tool keeps working on one canonical format.
- **Project bundle JSON** — a Vector Tune-Up bundle previously exported from this
  app, re-imported as a brand-new project (History → Import project bundle).

You can paste any of these into the Input tab, or import a `.xml` / `.svg` /
`.json` file with **Import file**. Imports are capped at 5 MB.

## Supported outputs

- **Android VectorDrawable XML** — the canonical format.
- **SVG** — generated from the version's canonical Android XML.
- **Project bundle JSON** — the whole project (all versions + metrics) in one
  portable file you can archive, share, or re-import.

## Typical workflow

1. **Input** — paste or import an Android XML, SVG, or project bundle.
2. **Diagnostics** — see the health rating (size/complexity), a preview, metrics,
   and warnings.
3. **Compare** — generate candidates and compare them against the original:
   - **Optimize** for safe, local, appearance-preserving cleanup.
   - **AI Tune-Up** to edit the existing paths from a typed instruction.
   - **AI Redraw** for a more transformative, icon-like rebuild.
4. **Edit** — select individual paths to delete, simplify, recolor, or restyle,
   and apply batch restyles by color.
5. **History** — every generated version is saved with its parent lineage. Select
   any version to branch from it, duplicate it, delete a leaf version, or import a
   bundle.
6. **Export** — save the selected version as Android XML, SVG, or a project bundle.

## Optimize vs AI Tune-Up vs AI Redraw vs Manual Edit

| Mode | What it does | Changes geometry? | Needs an API key? |
|------|--------------|-------------------|-------------------|
| **Optimize** | Deterministic local cleanup: drops tiny/zero-length paths, simplifies noisy paths, rounds floats. Preserves the look. | Minimally (within tolerance) | No |
| **AI Tune-Up** | The model proposes a typed *edit plan* over the existing paths; the app validates and applies it. | Conservatively | Yes |
| **AI Redraw** | The model proposes a clean *scene* of primitives; the app compiles it to XML. | Yes, substantially | Yes |
| **Manual Edit** | You select paths and delete/simplify/recolor/restyle them directly. | Only what you choose | No |

The original source XML is never mutated — every operation produces a new version.

## Project history and branching

- Projects persist across app restarts.
- Each version records its **parent**, so the history is a tree, not a line.
- The version you **select** is the one the next operation branches from.
- **Duplicate** copies the selected version as a new manual-edit child.
- **Delete** removes a *leaf* version only; the original and any version with
  children are protected.

## Bundle export / import

- Export a project as **Project bundle JSON** from the Export tab.
- Re-import it from **History → Import project bundle** (paste or pick a file).
- Imports always create a **new** project — they never overwrite an open one.
- Version IDs are remapped, parent lineage is preserved, missing parents are
  repaired, and SVG-bearing versions are canonicalized, all with warnings.

## Large or complex files

The Diagnostics **Health** panel rates a vector OK / Large / Very large / Too
large based on size, path count, and command count:

- **Large** — everything works; edits may take a moment.
- **Very large** — AI is held back until you tick "Run AI on large input".
- **Too large** — AI is disabled; run a local Optimize first or import a smaller
  file. Parsing, diagnostics, and export still work.

## Known limitations

See `VECTOR_ART_TUNEUP_LIMITATIONS.md` for the full list (SVG subset, preview
fidelity, unsupported features, AI safety bounds, bundle constraints).
