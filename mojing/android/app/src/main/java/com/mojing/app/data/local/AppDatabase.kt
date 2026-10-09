package com.mojing.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.*

@Database(
    entities = [
        SessionEntity::class, CharacterEntity::class, MessageEntity::class, ConfigEntity::class,
        SessionParticipantEntity::class, SessionWorldEntity::class,
        CharacterProfileEntity::class, CharacterExpressionEntity::class,
        VoiceProfileEntity::class, PersonaEntity::class,
        EncyclopediaEntity::class, EncyclopediaEntryEntity::class,
        EntryRelationEntity::class, EntryVersionEntity::class, TimelineEventEntity::class,
        WorldTemplateEntity::class, WorldLoreEntryEntity::class, PromptTemplateEntity::class,
        SessionCharacterStateEntity::class, SessionMemorySegmentEntity::class,
        SessionEventNodeEntity::class, SessionBranchEntity::class, SessionContextMemoryEntity::class,
        MessageAttachmentEntity::class, MessageBookmarkEntity::class, CostRecordEntity::class,
        GenerationTaskEntity::class, MessageSearchFtsEntity::class,
        MessageSearchIndexStateEntity::class, BranchVisibilitySegmentEntity::class,
        SessionMemoryCorrectionEntity::class, BranchSwipeSelectionEntity::class,
        BranchContextExclusionEntity::class,
        LegacyWorldMappingEntity::class, LegacyLoreMappingEntity::class,
        BranchEventStatusEntity::class,
    ],
    version = 28,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun configDao(): ConfigDao
    abstract fun participantDao(): ParticipantDao
    abstract fun sessionWorldDao(): SessionWorldDao
    abstract fun sessionBranchDao(): SessionBranchDao
    abstract fun sessionMemorySegmentDao(): SessionMemorySegmentDao
    abstract fun sessionEventNodeDao(): SessionEventNodeDao
    abstract fun sessionContextMemoryDao(): SessionContextMemoryDao
    abstract fun characterStateDao(): CharacterStateDao
    abstract fun characterProfileDao(): CharacterProfileDao
    abstract fun expressionDao(): ExpressionDao
    abstract fun voiceProfileDao(): VoiceProfileDao
    abstract fun personaDao(): PersonaDao
    abstract fun encyclopediaDao(): EncyclopediaDao
    abstract fun encyclopediaEntryDao(): EncyclopediaEntryDao
    abstract fun entryRelationDao(): EntryRelationDao
    abstract fun entryVersionDao(): EntryVersionDao
    abstract fun timelineEventDao(): TimelineEventDao
    abstract fun worldTemplateDao(): WorldTemplateDao
    abstract fun worldLoreEntryDao(): WorldLoreEntryDao
    abstract fun promptTemplateDao(): PromptTemplateDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun costRecordDao(): CostRecordDao
    abstract fun generationTaskDao(): GenerationTaskDao
    abstract fun sessionMemoryCorrectionDao(): SessionMemoryCorrectionDao
    abstract fun legacyWorldMappingDao(): LegacyWorldMappingDao
}
