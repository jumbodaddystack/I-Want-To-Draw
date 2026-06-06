package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.AndroidVectorDrawableWriter
import com.aichat.sandbox.data.vector.VectorDocument

/**
 * Phase 5 (sub-feature 3) — pure, JVM-testable serializer that turns a
 * [VectorSymbol]'s geometry into a single string blob (and back) so the symbol
 * library can persist a master in one Room column.
 *
 * A symbol *is* a mini-document, so we reuse the **existing**
 * [AndroidVectorDrawableWriter]/[AndroidVectorDrawableParser] (both pure, no
 * Android imports — the same pair the editor's `VectorVersion ⇄ VectorDocument`
 * bridge uses) rather than inventing a symbol-specific format. The symbol's own
 * [VectorSymbol.id] and [VectorSymbol.name] are *not* baked into the blob — they
 * are stored as their own columns by the entity — so [decode] takes them as
 * arguments and only reconstructs geometry from the XML.
 *
 * Round-trip contract (pinned by `VectorSymbolCodecTest`): viewport and path
 * geometry survive `encode → decode` exactly (path *ids* are reissued by the
 * VectorDrawable parser, like everywhere else in this codebase — that is fine,
 * because [SymbolResolver] re-namespaces every child id on expansion anyway).
 */
object VectorSymbolCodec {

    /** Serialize a symbol's viewport + body to an Android VectorDrawable XML blob. */
    fun encode(symbol: VectorSymbol): String =
        AndroidVectorDrawableWriter.write(
            VectorDocument(viewport = symbol.viewport, root = symbol.root),
        )

    /**
     * Rebuild a symbol from its stored [id]/[name] and the XML blob produced by
     * [encode]. The blob carries only geometry; identity comes from the columns.
     */
    fun decode(id: String, name: String, vectorXml: String): VectorSymbol {
        val document = AndroidVectorDrawableParser.parse(vectorXml)
        return VectorSymbol(
            id = id,
            name = name,
            viewport = document.viewport,
            root = document.root,
        )
    }
}
