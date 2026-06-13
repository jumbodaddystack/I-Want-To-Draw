package com.aichat.sandbox.di

import android.content.Context
import androidx.room.Room
import com.aichat.sandbox.data.local.AppDatabase
import com.aichat.sandbox.data.local.BrushPresetDao
import com.aichat.sandbox.data.local.ChatDao
import com.aichat.sandbox.data.local.MIGRATION_1_2
import com.aichat.sandbox.data.local.MIGRATION_2_3
import com.aichat.sandbox.data.local.MIGRATION_3_4
import com.aichat.sandbox.data.local.MIGRATION_4_5
import com.aichat.sandbox.data.local.MIGRATION_5_6
import com.aichat.sandbox.data.local.MIGRATION_6_7
import com.aichat.sandbox.data.local.MIGRATION_7_8
import com.aichat.sandbox.data.local.MIGRATION_8_9
import com.aichat.sandbox.data.local.MIGRATION_9_10
import com.aichat.sandbox.data.local.MIGRATION_10_11
import com.aichat.sandbox.data.local.MIGRATION_11_12
import com.aichat.sandbox.data.local.MIGRATION_12_13
import com.aichat.sandbox.data.local.MIGRATION_13_14
import com.aichat.sandbox.data.local.MIGRATION_14_15
import com.aichat.sandbox.data.local.MIGRATION_15_16
import com.aichat.sandbox.data.local.MIGRATION_16_17
import com.aichat.sandbox.data.local.MIGRATION_17_18
import com.aichat.sandbox.data.local.MIGRATION_18_19
import com.aichat.sandbox.data.local.MIGRATION_19_20
import com.aichat.sandbox.data.local.MIGRATION_20_21
import com.aichat.sandbox.data.local.createNotesSearchIndex
import com.aichat.sandbox.data.local.NoteAudioDao
import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.local.NoteFrameDao
import com.aichat.sandbox.data.local.NoteSearchDao
import com.aichat.sandbox.data.local.NoteTagDao
import com.aichat.sandbox.data.local.NotebookDao
import com.aichat.sandbox.data.local.StampDao
import com.aichat.sandbox.data.local.UserTemplateDao
import com.aichat.sandbox.data.local.VectorSymbolDao
import com.aichat.sandbox.data.local.VectorTuneupDao
import com.aichat.sandbox.data.notes.HandwritingOcr
import com.aichat.sandbox.data.notes.NoteAiService
import com.aichat.sandbox.data.remote.ApiClient
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.VectorTuneupRepository
import com.aichat.sandbox.ui.components.MarkwonProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ai_chat_sandbox.db"
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        ).addCallback(object : androidx.room.RoomDatabase.Callback() {
            // `notes_ocr_fts` is not a Room entity, so fresh installs (which
            // skip migrations) must create it here. Upgrades get the same DDL
            // via MIGRATION_17_18.
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                createNotesSearchIndex(db)
            }
        }).build()
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    fun provideNoteDao(database: AppDatabase): NoteDao {
        return database.noteDao()
    }

    @Provides
    fun provideNoteTagDao(database: AppDatabase): NoteTagDao {
        return database.noteTagDao()
    }

    @Provides
    fun provideBrushPresetDao(database: AppDatabase): BrushPresetDao {
        return database.brushPresetDao()
    }

    @Provides
    fun provideNoteFrameDao(database: AppDatabase): NoteFrameDao {
        return database.noteFrameDao()
    }

    @Provides
    fun provideStampDao(database: AppDatabase): StampDao {
        return database.stampDao()
    }

    @Provides
    fun provideUserTemplateDao(database: AppDatabase): UserTemplateDao {
        return database.userTemplateDao()
    }

    @Provides
    fun provideNotebookDao(database: AppDatabase): NotebookDao {
        return database.notebookDao()
    }

    @Provides
    fun provideNoteSearchDao(database: AppDatabase): NoteSearchDao {
        return database.noteSearchDao()
    }

    @Provides
    fun provideNoteAudioDao(database: AppDatabase): NoteAudioDao {
        return database.noteAudioDao()
    }

    @Provides
    fun provideVectorTuneupDao(database: AppDatabase): VectorTuneupDao {
        return database.vectorTuneupDao()
    }

    @Provides
    fun provideVectorSymbolDao(database: AppDatabase): VectorSymbolDao {
        return database.vectorSymbolDao()
    }

    @Provides
    @Singleton
    fun provideVectorTuneupRepository(
        @ApplicationContext context: Context,
        vectorTuneupDao: VectorTuneupDao,
    ): VectorTuneupRepository {
        return VectorTuneupRepository(context, vectorTuneupDao)
    }

    @Provides
    @Singleton
    fun provideNoteRepository(
        @ApplicationContext context: Context,
        noteDao: NoteDao,
        noteFrameDao: NoteFrameDao,
        noteTagDao: NoteTagDao,
        handwritingOcr: HandwritingOcr,
    ): NoteRepository {
        return NoteRepository(context, noteDao, noteFrameDao, noteTagDao, handwritingOcr)
    }

    @Provides
    @Singleton
    fun provideMarkwonProvider(): MarkwonProvider {
        return MarkwonProvider()
    }

    @Provides
    @Singleton
    fun provideHandwritingOcr(): HandwritingOcr {
        return HandwritingOcr()
    }

    @Provides
    @Singleton
    fun provideChatStreamer(apiClient: ApiClient): ChatStreamer = apiClient

    @Provides
    @Singleton
    fun provideNoteAiService(
        chatStreamer: ChatStreamer,
        ocr: HandwritingOcr,
    ): NoteAiService {
        // Pass the concrete recognizer in; the service holds the
        // `HandwritingRecognizer` interface so tests can substitute a fake.
        return NoteAiService(chatStreamer, ocr)
    }
}
