package com.aichat.sandbox.di

import android.content.Context
import androidx.room.Room
import com.aichat.sandbox.data.local.AppDatabase
import com.aichat.sandbox.data.local.BrushPresetDao
import com.aichat.sandbox.data.local.createNotesSearchIndex
import com.aichat.sandbox.data.local.NoteAudioDao
import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.local.NoteFrameDao
import com.aichat.sandbox.data.local.NoteSearchDao
import com.aichat.sandbox.data.local.NoteTagDao
import com.aichat.sandbox.data.local.NotebookDao
import com.aichat.sandbox.data.local.StampDao
import com.aichat.sandbox.data.local.StampTagDao
import com.aichat.sandbox.data.local.UserTemplateDao
import com.aichat.sandbox.data.notes.HandwritingOcr
import com.aichat.sandbox.data.repository.NoteRepository
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
            "doodle_pad.db"
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
    fun provideStampTagDao(database: AppDatabase): StampTagDao {
        return database.stampTagDao()
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
    fun provideHandwritingOcr(): HandwritingOcr {
        return HandwritingOcr()
    }

}
