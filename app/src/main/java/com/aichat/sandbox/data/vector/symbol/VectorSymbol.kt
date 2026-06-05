package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorViewport

/**
 * Phase 5 (sub-feature 3) — a reusable **vector** symbol: a master definition
 * authored once and instanced many times. A symbol *is* a mini-document — it
 * reuses the existing [VectorGroup] tree and a [VectorViewport] coordinate box,
 * so the same parser/writer/preview machinery understands it once it is expanded
 * into a host document (see [SymbolResolver]).
 *
 * A symbol carries no fill/stroke of its own beyond what its [root] paths
 * already hold; per-placement recolours live on the [SymbolInstance] as a
 * [com.aichat.sandbox.data.vector.VectorStyle] override.
 *
 * Symbols are app-scoped (mirroring the raster [com.aichat.sandbox.data.model.Stamp]
 * library) — a personal library reused across documents. Persistence of the
 * library is intentionally not part of this pure model layer.
 */
data class VectorSymbol(
    /** Stable library id referenced by [SymbolInstance.symbolId]. */
    val id: String,
    val name: String,
    /** The symbol's own coordinate box; an instance maps it into the host via its transform. */
    val viewport: VectorViewport,
    /** The symbol body — the same node tree every other surface already edits. */
    val root: VectorGroup,
)
