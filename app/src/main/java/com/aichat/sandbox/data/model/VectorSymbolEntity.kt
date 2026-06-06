package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Phase 5 (sub-feature 3) — persistence row for a reusable **vector** symbol
 * (master/instance). Mirrors the raster [Stamp] library: app-scoped (not
 * per-document) so the user builds a personal library of vector objects reused
 * across icons.
 *
 * [vectorXml] is the symbol's viewport + body serialized by
 * [com.aichat.sandbox.data.vector.symbol.VectorSymbolCodec] (an Android
 * VectorDrawable blob — the same lossless format the editor round-trips). The
 * [id]/[name] columns own the symbol's identity; the blob carries only geometry.
 *
 * To place a symbol, the editor creates a
 * [com.aichat.sandbox.data.vector.VectorNode.InstanceNode] referencing this
 * [id]; export/preview run
 * [com.aichat.sandbox.data.vector.symbol.SymbolResolver.expand] against the
 * decoded library first, so no writer/preview code needs to know about symbols.
 */
@Entity(
    tableName = "vector_symbols",
    indices = [Index("lastUsedAt")],
)
data class VectorSymbolEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** VectorDrawable XML blob from `VectorSymbolCodec.encode`. */
    val vectorXml: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)
