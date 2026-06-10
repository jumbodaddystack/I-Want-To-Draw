package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.aichat.sandbox.data.model.VectorTuneupProjectEntity
import com.aichat.sandbox.data.model.VectorTuneupVersionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 6 — persistence for Vector Art Tune-Up projects and their versions.
 *
 * Project/version orchestration (creating a project with its original version,
 * branching, etc.) lives in `VectorTuneupRepository`; this interface stays a
 * thin set of queries so the repository can compose them on `Dispatchers.IO`.
 */
@Dao
interface VectorTuneupDao {

    @Query(
        """
        SELECT * FROM vector_tuneup_projects
        ORDER BY updatedAt DESC
        """,
    )
    fun observeProjects(): Flow<List<VectorTuneupProjectEntity>>

    @Query("SELECT * FROM vector_tuneup_projects WHERE id = :projectId")
    suspend fun getProject(projectId: String): VectorTuneupProjectEntity?

    @Query(
        """
        SELECT * FROM vector_tuneup_versions
        WHERE projectId = :projectId
        ORDER BY createdAt ASC
        """,
    )
    fun observeVersions(projectId: String): Flow<List<VectorTuneupVersionEntity>>

    @Query(
        """
        SELECT * FROM vector_tuneup_versions
        WHERE projectId = :projectId
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getVersions(projectId: String): List<VectorTuneupVersionEntity>

    @Query("SELECT * FROM vector_tuneup_versions WHERE id = :versionId")
    suspend fun getVersion(versionId: String): VectorTuneupVersionEntity?

    /** Number of versions that branch directly off [versionId] (Phase 10 leaf-delete guard). */
    @Query("SELECT COUNT(*) FROM vector_tuneup_versions WHERE parentId = :versionId")
    suspend fun childCount(versionId: String): Int

    /** The project's [com.aichat.sandbox.data.model.VectorTuneupMode.ORIGINAL] root, if any. */
    @Query(
        """
        SELECT * FROM vector_tuneup_versions
        WHERE projectId = :projectId AND mode = 'ORIGINAL'
        ORDER BY createdAt ASC
        LIMIT 1
        """,
    )
    suspend fun getOriginalVersion(projectId: String): VectorTuneupVersionEntity?

    // Upsert, not @Insert(REPLACE): REPLACE deletes the conflicting project
    // row first, which cascade-deletes the project's version history.
    @Upsert
    suspend fun upsertProject(project: VectorTuneupProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVersion(version: VectorTuneupVersionEntity)

    @Query(
        """
        UPDATE vector_tuneup_projects
        SET activeVersionId = :versionId, updatedAt = :updatedAt
        WHERE id = :projectId
        """,
    )
    suspend fun setActiveVersion(projectId: String, versionId: String, updatedAt: Long)

    @Query(
        """
        UPDATE vector_tuneup_projects
        SET title = :title, updatedAt = :updatedAt
        WHERE id = :projectId
        """,
    )
    suspend fun renameProject(projectId: String, title: String, updatedAt: Long)

    @Query("DELETE FROM vector_tuneup_projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)

    @Query("DELETE FROM vector_tuneup_versions WHERE id = :versionId")
    suspend fun deleteVersion(versionId: String)
}
