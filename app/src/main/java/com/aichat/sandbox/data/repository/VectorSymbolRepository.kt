package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.VectorSymbolDao
import com.aichat.sandbox.data.model.VectorSymbolEntity
import com.aichat.sandbox.data.vector.symbol.VectorSymbol
import com.aichat.sandbox.data.vector.symbol.VectorSymbolCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 5 (sub-feature 3) — repository for the app-scoped reusable vector-symbol
 * library. Mirrors [StampRepository] but the persisted blob is geometry, not a
 * thumbnail PNG, so there is no file-system side: a symbol round-trips entirely
 * through [VectorSymbolCodec] ⇄ a single Room column.
 *
 * The repository is the boundary that maps the storage [VectorSymbolEntity] to
 * the pure-model [VectorSymbol] every other surface (resolver/editor/preview)
 * works with. [loadLibrary] returns exactly the `Map<String, VectorSymbol>` that
 * [com.aichat.sandbox.data.vector.symbol.SymbolResolver.expand] consumes before
 * any export/preview pass.
 */
@Singleton
class VectorSymbolRepository @Inject constructor(
    private val dao: VectorSymbolDao,
) {

    /** MRU-first stream of the decoded library. */
    fun observeAll(): Flow<List<VectorSymbol>> =
        dao.observeAll().map { rows -> rows.map(::toSymbol) }

    suspend fun getSymbol(symbolId: String): VectorSymbol? = withContext(Dispatchers.IO) {
        dao.getSymbol(symbolId)?.let(::toSymbol)
    }

    /**
     * The whole library as the `symbolId → master` map that
     * [com.aichat.sandbox.data.vector.symbol.SymbolResolver.expand] expects.
     */
    suspend fun loadLibrary(): Map<String, VectorSymbol> = withContext(Dispatchers.IO) {
        dao.observeAllOnce().associate { row -> row.id to toSymbol(row) }
    }

    /**
     * Persist (insert or replace) a symbol. Stores the codec blob keyed by the
     * symbol's own [VectorSymbol.id]/[VectorSymbol.name] columns.
     */
    suspend fun saveSymbol(symbol: VectorSymbol): VectorSymbol = withContext(Dispatchers.IO) {
        val named = if (symbol.name.isBlank()) symbol.copy(name = "Symbol") else symbol
        dao.upsert(
            VectorSymbolEntity(
                id = named.id,
                name = named.name,
                vectorXml = VectorSymbolCodec.encode(named),
            ),
        )
        named
    }

    suspend fun rename(symbolId: String, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        dao.rename(symbolId, name.trim())
    }

    suspend fun delete(symbolId: String) = withContext(Dispatchers.IO) {
        dao.delete(symbolId)
    }

    suspend fun touchLastUsed(symbolId: String) = withContext(Dispatchers.IO) {
        dao.touchLastUsed(symbolId, System.currentTimeMillis())
    }

    private fun toSymbol(entity: VectorSymbolEntity): VectorSymbol =
        VectorSymbolCodec.decode(entity.id, entity.name, entity.vectorXml)
}
