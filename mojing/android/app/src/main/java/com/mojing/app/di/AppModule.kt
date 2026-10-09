package com.mojing.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase.JournalMode
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.Migrations
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.SecureStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "mojing.db")
            .addMigrations(
                Migrations.MIGRATION_1_2,
                Migrations.MIGRATION_2_3,
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6,
                Migrations.MIGRATION_6_7,
                Migrations.MIGRATION_7_8,
                Migrations.MIGRATION_8_9,
                Migrations.MIGRATION_9_10,
                Migrations.MIGRATION_10_11,
                Migrations.MIGRATION_11_12,
                Migrations.MIGRATION_12_13,
                Migrations.MIGRATION_13_14,
                Migrations.MIGRATION_14_15,
                Migrations.MIGRATION_15_16,
                Migrations.MIGRATION_16_17,
                Migrations.MIGRATION_17_18,
                Migrations.MIGRATION_18_19,
                Migrations.MIGRATION_19_20,
                Migrations.MIGRATION_20_21,
                Migrations.MIGRATION_21_22,
                Migrations.MIGRATION_22_23,
                Migrations.MIGRATION_23_24,
                Migrations.MIGRATION_24_25,
                Migrations.MIGRATION_25_26,
                Migrations.MIGRATION_26_27,
                Migrations.MIGRATION_27_28,
            )
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideCharacterDao(db: AppDatabase): CharacterDao = db.characterDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideConfigDao(db: AppDatabase): ConfigDao = db.configDao()
    @Provides fun provideParticipantDao(db: AppDatabase): ParticipantDao = db.participantDao()
    @Provides fun provideSessionWorldDao(db: AppDatabase): SessionWorldDao = db.sessionWorldDao()
    @Provides fun provideSessionBranchDao(db: AppDatabase): SessionBranchDao = db.sessionBranchDao()
    @Provides fun provideSessionMemorySegmentDao(db: AppDatabase): SessionMemorySegmentDao = db.sessionMemorySegmentDao()
    @Provides fun provideSessionEventNodeDao(db: AppDatabase): SessionEventNodeDao = db.sessionEventNodeDao()
    @Provides fun provideSessionContextMemoryDao(db: AppDatabase): SessionContextMemoryDao = db.sessionContextMemoryDao()
    @Provides fun provideCharacterStateDao(db: AppDatabase): CharacterStateDao = db.characterStateDao()
    @Provides fun provideCharacterProfileDao(db: AppDatabase): CharacterProfileDao = db.characterProfileDao()
    @Provides fun provideExpressionDao(db: AppDatabase): ExpressionDao = db.expressionDao()
    @Provides fun provideVoiceProfileDao(db: AppDatabase): VoiceProfileDao = db.voiceProfileDao()
    @Provides fun providePersonaDao(db: AppDatabase): PersonaDao = db.personaDao()
    @Provides fun provideEncyclopediaDao(db: AppDatabase): EncyclopediaDao = db.encyclopediaDao()
    @Provides fun provideEncyclopediaEntryDao(db: AppDatabase): EncyclopediaEntryDao = db.encyclopediaEntryDao()
    @Provides fun provideEntryRelationDao(db: AppDatabase): EntryRelationDao = db.entryRelationDao()
    @Provides fun provideEntryVersionDao(db: AppDatabase): EntryVersionDao = db.entryVersionDao()
    @Provides fun provideTimelineEventDao(db: AppDatabase): TimelineEventDao = db.timelineEventDao()
    @Provides fun provideWorldTemplateDao(db: AppDatabase): WorldTemplateDao = db.worldTemplateDao()
    @Provides fun provideWorldLoreEntryDao(db: AppDatabase): WorldLoreEntryDao = db.worldLoreEntryDao()
    @Provides fun providePromptTemplateDao(db: AppDatabase): PromptTemplateDao = db.promptTemplateDao()
    @Provides fun provideAttachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideCostRecordDao(db: AppDatabase): CostRecordDao = db.costRecordDao()
    @Provides fun provideGenerationTaskDao(db: AppDatabase): GenerationTaskDao = db.generationTaskDao()
    @Provides fun provideSessionMemoryCorrectionDao(db: AppDatabase): SessionMemoryCorrectionDao = db.sessionMemoryCorrectionDao()
    @Provides fun provideLegacyWorldMappingDao(db: AppDatabase): LegacyWorldMappingDao = db.legacyWorldMappingDao()

    @Provides @Singleton
    fun provideSecureStorage(@ApplicationContext context: Context): SecureStorage {
        val ss = SecureStorage()
        ss.init(context)
        return ss
    }
}
