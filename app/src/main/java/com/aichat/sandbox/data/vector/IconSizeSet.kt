package com.aichat.sandbox.data.vector

/**
 * Phase 3 — synchronized multi-size icon artboards (pure, JVM-testable).
 *
 * A single [master] [VectorDocument] (the square icon space the user edits) is
 * derived into one document per [IconTarget] by **pure uniform scale** of the
 * viewport and geometry, reusing [VectorQuantizer.mapCoordinates] for the
 * coordinate walk. Optical adjustment is a *manual* hook only ([OpticalAdjust]) —
 * a per-size uniform scale about the centre plus an optional safe-zone padding
 * override — never an automatic, shape-aware trim, so the derived set stays
 * deterministic. The editor previews every target at once by rendering each
 * derived document through the existing `VectorPreviewCanvas` (no new renderer).
 */
enum class IconTarget(val dp: Int) {
    MATERIAL_24(24),
    MEDIUM_48(48),
    ADAPTIVE_108(108),
}

/**
 * Manual optical-adjustment hook for one target. Identity ([scale] = 1, no
 * [paddingOverride]) leaves the derived geometry a pure scale of the master.
 */
data class OpticalAdjust(
    /** Uniform shrink/grow of the live area about the centre (e.g. 0.96 to trim 108). */
    val scale: Float = 1f,
    /** Override the keyline safe-zone padding for this size; null keeps the default. */
    val paddingOverride: Float? = null,
)

data class IconSizeSet(
    val master: VectorDocument,
    val targets: List<IconTarget> = IconTarget.entries.toList(),
    val adjust: Map<IconTarget, OpticalAdjust> = emptyMap(),
) {

    /**
     * Derive the document for [target]: scale the viewport and every coordinate
     * from the master edge `E` to `target.dp`, then apply the target's
     * [OpticalAdjust] uniform scale about the centre. Stroke widths track
     * `target.dp / E × adjust.scale` so stroke weight scales with the icon.
     */
    fun derive(target: IconTarget): VectorDocument {
        val edge = master.viewport.viewportWidth
        if (edge <= 0f) return master
        val t = target.dp.toFloat()
        val s = adjust[target]?.scale ?: 1f
        val ratio = t / edge
        val center = t / 2f

        val newViewport = VectorViewport(
            widthDp = t,
            heightDp = t,
            viewportWidth = t,
            viewportHeight = t,
        )
        // Scale to the target edge, then the optical scale about the new centre.
        val geom = VectorQuantizer.mapCoordinates(master.copy(viewport = newViewport)) { x, y ->
            val sx = center + (x * ratio - center) * s
            val sy = center + (y * ratio - center) * s
            sx to sy
        }
        return scaleStrokeWidths(geom, ratio * s)
    }

    /** Derive every target in [targets], preserving order. */
    fun deriveAll(): Map<IconTarget, VectorDocument> =
        targets.associateWith { derive(it) }

    private fun scaleStrokeWidths(doc: VectorDocument, factor: Float): VectorDocument {
        if (factor == 1f) return doc
        return doc.copy(root = scaleGroup(doc.root, factor))
    }

    private fun scaleGroup(group: VectorGroup, factor: Float): VectorGroup = group.copy(
        children = group.children.map { child ->
            when (child) {
                is VectorNode.InstanceNode -> child // unresolved instance passes through
                is VectorNode.GroupNode -> VectorNode.GroupNode(scaleGroup(child.group, factor))
                is VectorNode.PathNode -> {
                    val w = child.path.style.strokeWidth
                    if (w == null) child
                    else VectorNode.PathNode(
                        child.path.copy(style = child.path.style.copy(strokeWidth = w * factor)),
                    )
                }
            }
        },
    )
}
