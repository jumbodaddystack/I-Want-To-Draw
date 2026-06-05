package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorWarning

/**
 * Phase 5 (sub-feature 3) — expands reusable [VectorSymbol] instances into plain
 * groups+paths so every existing consumer (metrics, optimizer, preview, both
 * writers) keeps working on resolved geometry with **zero** new code.
 *
 * Each [VectorNode.InstanceNode] is replaced by a [VectorNode.GroupNode] whose
 * transform is the instance transform and whose children are the symbol's root
 * children, with every id namespaced `"${instance.id}/${childId}"` so multiple
 * instances of one master never collide. An instance's optional `styleOverride`
 * is folded onto each leaf path of that instance (a per-placement recolour).
 *
 * "Edits to the master propagate" falls out for free: change the [VectorSymbol]
 * in the library and re-run [expand] — every instance reflects the new geometry.
 *
 * Pure (no Android imports). The host document stores the *unexpanded* tree + a
 * symbol library; expansion runs at the export/preview boundary.
 */
object SymbolResolver {

    /**
     * Return a copy of [doc] with every instance replaced by its expanded
     * group. Unresolvable instances (missing master, or a cyclic reference) are
     * dropped and surfaced as [VectorWarning]s appended to the document.
     */
    fun expand(doc: VectorDocument, library: Map<String, VectorSymbol>): VectorDocument {
        if (library.isEmpty() && doc.root.containsNoInstances()) return doc
        val warnings = ArrayList<VectorWarning>()
        val newRoot = expandGroup(doc.root, library, warnings, emptySet())
        return doc.copy(
            root = newRoot,
            warnings = if (warnings.isEmpty()) doc.warnings else doc.warnings + warnings,
        )
    }

    private fun expandGroup(
        group: VectorGroup,
        library: Map<String, VectorSymbol>,
        warnings: MutableList<VectorWarning>,
        seen: Set<String>,
    ): VectorGroup {
        val newChildren = ArrayList<VectorNode>(group.children.size)
        for (child in group.children) {
            when (child) {
                is VectorNode.PathNode -> newChildren += child
                is VectorNode.GroupNode ->
                    newChildren += VectorNode.GroupNode(expandGroup(child.group, library, warnings, seen))
                is VectorNode.InstanceNode -> {
                    val expanded = expandInstance(child.instance, library, warnings, seen)
                    if (expanded != null) newChildren += VectorNode.GroupNode(expanded)
                }
            }
        }
        return group.copy(children = newChildren)
    }

    private fun expandInstance(
        instance: SymbolInstance,
        library: Map<String, VectorSymbol>,
        warnings: MutableList<VectorWarning>,
        seen: Set<String>,
    ): VectorGroup? {
        val symbol = library[instance.symbolId]
        if (symbol == null) {
            warnings += VectorWarning(
                VectorWarning.Codes.SYMBOL_UNRESOLVED,
                "Instance \"${instance.id}\" references unknown symbol \"${instance.symbolId}\".",
                instance.id,
            )
            return null
        }
        if (instance.symbolId in seen) {
            warnings += VectorWarning(
                VectorWarning.Codes.SYMBOL_CYCLE,
                "Symbol \"${instance.symbolId}\" instances itself; dropping the cyclic placement.",
                instance.id,
            )
            return null
        }
        // Namespace + style-fold the symbol body, then recursively expand any
        // nested instances (cycle-guarded by adding this master to `seen`).
        val body = symbol.root.children.map { namespaceNode(it, instance.id, instance.styleOverride) }
        val placement = VectorGroup(
            id = instance.id,
            name = symbol.name,
            rotation = instance.rotation.takeIf { it != 0f },
            scaleX = instance.scaleX.takeIf { it != 1f },
            scaleY = instance.scaleY.takeIf { it != 1f },
            translateX = instance.translateX.takeIf { it != 0f },
            translateY = instance.translateY.takeIf { it != 0f },
            children = body,
        )
        return expandGroup(placement, library, warnings, seen + instance.symbolId)
    }

    /** Prefix every id in the subtree with `"$prefix/"` and fold [override] onto each leaf path. */
    private fun namespaceNode(node: VectorNode, prefix: String, override: VectorStyle?): VectorNode =
        when (node) {
            is VectorNode.PathNode -> VectorNode.PathNode(
                node.path.copy(
                    id = "$prefix/${node.path.id}",
                    style = foldStyle(node.path.style, override),
                ),
            )
            is VectorNode.GroupNode -> VectorNode.GroupNode(
                node.group.copy(
                    id = "$prefix/${node.group.id}",
                    children = node.group.children.map { namespaceNode(it, prefix, override) },
                ),
            )
            // A nested instance keeps its own override; we only namespace its id so the
            // later expansion pass produces globally-unique children.
            is VectorNode.InstanceNode -> VectorNode.InstanceNode(
                node.instance.copy(id = "$prefix/${node.instance.id}"),
            )
        }

    /** Overlay every non-null field of [override] onto [base] (null override ⇒ base unchanged). */
    private fun foldStyle(base: VectorStyle, override: VectorStyle?): VectorStyle {
        if (override == null) return base
        return base.copy(
            fillColor = override.fillColor ?: base.fillColor,
            fillAlpha = override.fillAlpha ?: base.fillAlpha,
            fillType = override.fillType ?: base.fillType,
            strokeColor = override.strokeColor ?: base.strokeColor,
            strokeAlpha = override.strokeAlpha ?: base.strokeAlpha,
            strokeWidth = override.strokeWidth ?: base.strokeWidth,
            strokeLineCap = override.strokeLineCap ?: base.strokeLineCap,
            strokeLineJoin = override.strokeLineJoin ?: base.strokeLineJoin,
            strokeMiterLimit = override.strokeMiterLimit ?: base.strokeMiterLimit,
            strokeDashArray = override.strokeDashArray ?: base.strokeDashArray,
            strokeDashOffset = override.strokeDashOffset ?: base.strokeDashOffset,
            variableWidth = override.variableWidth ?: base.variableWidth,
            fill = override.fill ?: base.fill,
        )
    }

    private fun VectorGroup.containsNoInstances(): Boolean = children.none { child ->
        when (child) {
            is VectorNode.InstanceNode -> true
            is VectorNode.GroupNode -> !child.group.containsNoInstances()
            is VectorNode.PathNode -> false
        }
    }
}
