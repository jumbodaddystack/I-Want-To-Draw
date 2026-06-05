package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.VectorStyle

/**
 * Phase 5 (sub-feature 3) — a single placement of a [VectorSymbol] inside a host
 * `VectorDocument`. It lives in the tree as a
 * [com.aichat.sandbox.data.vector.VectorNode.InstanceNode] and is *expanded* into
 * a plain group+paths by [SymbolResolver] before any metrics/optimize/preview/
 * export pass, so every existing consumer keeps working on resolved geometry.
 *
 * The transform maps the symbol's own coordinate space into the host viewport.
 * It is expressed exactly as a `VectorDrawable`/SVG `<group>` transform (translate
 * + scale + rotate about the origin), so expansion folds it straight onto a
 * [com.aichat.sandbox.data.vector.VectorGroup] with no new writer/preview code.
 */
data class SymbolInstance(
    /** Stable id for this placement; becomes the expanded group's id and the namespace for its children. */
    val id: String,
    /** Which master in the library this instances ([VectorSymbol.id]). */
    val symbolId: String,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Degrees, about the origin (matches `VectorGroup.rotation`). */
    val rotation: Float = 0f,
    /** Optional per-instance recolour folded onto every leaf path of the expanded symbol. */
    val styleOverride: VectorStyle? = null,
)
