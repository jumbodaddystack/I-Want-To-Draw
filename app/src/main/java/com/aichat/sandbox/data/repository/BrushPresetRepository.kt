package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.BrushPresetDao
import com.aichat.sandbox.data.model.BrushPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 6.5 — thin wrapper around [BrushPresetDao].
 *
 * The seeded app-scope rows are inserted by `MIGRATION_7_8`; this repository
 * only handles user-scope mutations and the read paths.
 */
@Singleton
class BrushPresetRepository @Inject constructor(
    private val dao: BrushPresetDao,
) {

    fun observeAll(): Flow<List<BrushPreset>> = dao.observeAll()

    suspend fun forTool(tool: String): List<BrushPreset> = withContext(Dispatchers.IO) {
        dao.forTool(tool)
    }

    suspend fun saveUserPreset(preset: BrushPreset): BrushPreset = withContext(Dispatchers.IO) {
        val toPersist = preset.copy(ownerScope = BrushPreset.SCOPE_USER)
        dao.upsert(toPersist)
        toPersist
    }

    suspend fun deleteUserPreset(id: String) = withContext(Dispatchers.IO) {
        dao.deleteUserPreset(id)
    }
}
