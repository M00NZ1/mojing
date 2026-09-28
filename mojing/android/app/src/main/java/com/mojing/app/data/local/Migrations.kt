package com.mojing.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE characters ADD COLUMN repetitionPenalty REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE characters ADD COLUMN voiceProfileId INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE characters ADD COLUMN voiceProvider TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE characters ADD COLUMN voiceApiBaseUrl TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE characters ADD COLUMN voiceApiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE characters ADD COLUMN voiceModel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE characters ADD COLUMN topK INTEGER NOT NULL DEFAULT 0")

            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN templateId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN label TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN category TEXT NOT NULL DEFAULT '通用'")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN scope TEXT NOT NULL DEFAULT 'story'")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN userPrompt TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN variablesJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN outputFormat TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN isBuiltin INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE prompt_templates ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_prompt_templates_templateId ON prompt_templates(templateId)")

            db.execSQL("ALTER TABLE encyclopedias RENAME TO world_encyclopedias")
            db.execSQL("ALTER TABLE world_encyclopedias ADD COLUMN gameplayMode TEXT NOT NULL DEFAULT '自由剧情'")
            db.execSQL("ALTER TABLE world_encyclopedias ADD COLUMN antiCheatPrompt TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE world_encyclopedias ADD COLUMN narratorConfigJson TEXT NOT NULL DEFAULT '{}'")

            db.execSQL("ALTER TABLE session_character_states ADD COLUMN dynamicStateJson TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN relationsJson TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN privateFactsJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN eventLogJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN goalsJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN emotionalState TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN lastCompactedMessageId INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN lastSignificantEventId INTEGER DEFAULT NULL")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE characters ADD COLUMN cardImagePath TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN thinkMaxEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE characters ADD COLUMN thinkMaxEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE characters ADD COLUMN thinkMaxModelName TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionLlmApiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionLlmBaseUrl TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionImageApiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionImageBaseUrl TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionImageModel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionVoiceApiKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionVoiceBaseUrl TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionVoiceModel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionVoiceSpeechVoice TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN sessionVoicePresetPrefixModel TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN autoCharacterImageGen INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE session_worlds ADD COLUMN autoCharacterSpeech INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE encyclopedia_entries ADD COLUMN coverImagePath TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE world_encyclopedias ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE world_templates ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE characters ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE sessions ADD COLUMN displayContextTokenLimit INTEGER NOT NULL DEFAULT 1000000",
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `generation_tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskKind` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `progressDone` INTEGER NOT NULL DEFAULT 0,
                    `progressTotal` INTEGER NOT NULL DEFAULT 0,
                    `payloadJson` TEXT NOT NULL,
                    `errorMessage` TEXT NOT NULL DEFAULT '',
                    `targetEncyclopediaId` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_tasks_status` ON `generation_tasks` (`status`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_generation_tasks_targetEncyclopediaId` ON `generation_tasks` (`targetEncyclopediaId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_tasks_createdAt` ON `generation_tasks` (`createdAt`)")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE generation_tasks ADD COLUMN targetCharacterId INTEGER")
            db.execSQL("ALTER TABLE generation_tasks ADD COLUMN targetWorldTemplateId INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_generation_tasks_targetCharacterId` ON `generation_tasks` (`targetCharacterId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_generation_tasks_targetWorldTemplateId` ON `generation_tasks` (`targetWorldTemplateId`)",
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE characters ADD COLUMN boundEncyclopediaId INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_characters_boundEncyclopediaId` ON `characters` (`boundEncyclopediaId`)",
            )
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `session_context_memories` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `branchId` TEXT NOT NULL,
                    `globalSummary` TEXT NOT NULL,
                    `userStateJson` TEXT NOT NULL,
                    `characterStatesJson` TEXT NOT NULL,
                    `relationshipStatesJson` TEXT NOT NULL,
                    `worldStateJson` TEXT NOT NULL,
                    `recentTimelineJson` TEXT NOT NULL,
                    `openThreadsJson` TEXT NOT NULL,
                    `continuityRulesJson` TEXT NOT NULL,
                    `sourceStartMessageId` INTEGER NOT NULL,
                    `sourceEndMessageId` INTEGER NOT NULL,
                    `memoryVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_context_memories_sessionId` ON `session_context_memories` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_context_memories_branchId` ON `session_context_memories` (`branchId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_context_memories_sessionId_branchId` ON `session_context_memories` (`sessionId`, `branchId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_context_memories_updatedAt` ON `session_context_memories` (`updatedAt`)")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_regeneratedFromMessageId` ON `messages` (`regeneratedFromMessageId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_branchId_id` ON `messages` (`sessionId`, `branchId`, `id`)",
            )
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 只创建派生索引结构；旧正文由应用启动后的 IO 任务分批补齐，避免迁移阻塞打开。
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `searchNormalized` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `searchTerms` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS `message_search_fts`
                USING FTS4(
                    `searchNormalized` TEXT NOT NULL,
                    `searchTerms` TEXT NOT NULL,
                    tokenize=unicode61,
                    content=`messages`,
                    notindexed=`searchNormalized`
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `message_search_index_state` (
                    `id` INTEGER NOT NULL,
                    `indexVersion` INTEGER NOT NULL,
                    `indexedThroughMessageId` INTEGER NOT NULL,
                    `isComplete` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `message_search_index_state`
                    (`id`, `indexVersion`, `indexedThroughMessageId`, `isComplete`, `updatedAt`)
                VALUES (1, 1, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_search_fts_BEFORE_UPDATE
                BEFORE UPDATE ON `messages` BEGIN
                    DELETE FROM `message_search_fts` WHERE `docid`=OLD.`rowid`;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_search_fts_BEFORE_DELETE
                BEFORE DELETE ON `messages` BEGIN
                    DELETE FROM `message_search_fts` WHERE `docid`=OLD.`rowid`;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_search_fts_AFTER_UPDATE
                AFTER UPDATE ON `messages` BEGIN
                    INSERT INTO `message_search_fts`(`docid`, `searchNormalized`, `searchTerms`)
                    VALUES (NEW.`rowid`, NEW.`searchNormalized`, NEW.`searchTerms`);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_search_fts_AFTER_INSERT
                AFTER INSERT ON `messages` BEGIN
                    INSERT INTO `message_search_fts`(`docid`, `searchNormalized`, `searchTerms`)
                    VALUES (NEW.`rowid`, NEW.`searchNormalized`, NEW.`searchTerms`);
                END
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `branch_visibility_segments` (
                    `sessionId` INTEGER NOT NULL,
                    `targetBranchId` TEXT NOT NULL,
                    `sourceBranchId` TEXT NOT NULL,
                    `maxMessageId` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `targetBranchId`, `sourceBranchId`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_branch_visibility_segments_sessionId_targetBranchId` " +
                    "ON `branch_visibility_segments` (`sessionId`, `targetBranchId`)",
            )
            // 只扫描通常很小的分支元数据表；消息正文和搜索索引均不改写。
            db.execSQL(
                """
                WITH RECURSIVE branch_chain(
                    sessionId, targetBranchId, sourceBranchId, maxMessageId,
                    parentBranchId, parentSourceMessageId, pathKey
                ) AS (
                    SELECT sessionId, branchId, branchId, 9223372036854775807,
                           parentBranchId, sourceMessageId, '|' || branchId || '|'
                    FROM session_branches
                    UNION ALL
                    SELECT chain.sessionId,
                           chain.targetBranchId,
                           chain.parentBranchId,
                           chain.parentSourceMessageId,
                           CASE WHEN chain.parentBranchId = 'main' THEN '' ELSE COALESCE(parent.parentBranchId, '') END,
                           CASE WHEN chain.parentBranchId = 'main' THEN 0 ELSE COALESCE(parent.sourceMessageId, 0) END,
                           chain.pathKey || chain.parentBranchId || '|'
                    FROM branch_chain AS chain
                    LEFT JOIN session_branches AS parent
                      ON parent.sessionId = chain.sessionId
                     AND parent.branchId = chain.parentBranchId
                    WHERE chain.parentBranchId <> ''
                      AND instr(chain.pathKey, '|' || chain.parentBranchId || '|') = 0
                )
                INSERT OR REPLACE INTO branch_visibility_segments(
                    sessionId, targetBranchId, sourceBranchId, maxMessageId
                )
                SELECT sessionId, targetBranchId, sourceBranchId, maxMessageId
                FROM branch_chain
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `session_memory_corrections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `branchId` TEXT,
                    `content` TEXT NOT NULL,
                    `sourceMessageId` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_memory_corrections_sessionId` ON `session_memory_corrections` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_memory_corrections_branchId` ON `session_memory_corrections` (`branchId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_memory_corrections_sourceMessageId` ON `session_memory_corrections` (`sourceMessageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_memory_corrections_sessionId_branchId` ON `session_memory_corrections` (`sessionId`, `branchId`)")
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `branch_swipe_selections` (
                    `sessionId` INTEGER NOT NULL,
                    `branchId` TEXT NOT NULL,
                    `swipeGroupId` TEXT NOT NULL,
                    `selectedMessageId` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `branchId`, `swipeGroupId`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`selectedMessageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_branch_swipe_selections_sessionId_swipeGroupId` " +
                    "ON `branch_swipe_selections` (`sessionId`, `swipeGroupId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_branch_swipe_selections_selectedMessageId` " +
                    "ON `branch_swipe_selections` (`selectedMessageId`)",
            )

            // 旧字段冻结为所有故事线的默认值。只修复历史异常组：多选时保留最新激活项，
            // 零选时采用最新版本；正常组和消息正文不改写，也不按分支全量复制历史。
            db.execSQL("DROP TABLE IF EXISTS temp.legacy_swipe_recovery")
            db.execSQL(
                """
                CREATE TEMP TABLE legacy_swipe_recovery AS
                SELECT sessionId,
                       swipeGroupId,
                       CASE
                           WHEN SUM(CASE WHEN includeInContext = 1 THEN 1 ELSE 0 END) > 0
                               THEN MAX(CASE WHEN includeInContext = 1 THEN id END)
                           ELSE MAX(id)
                       END AS selectedMessageId
                FROM messages
                WHERE swipeGroupId IS NOT NULL
                  AND trim(swipeGroupId) <> ''
                GROUP BY sessionId, swipeGroupId
                HAVING SUM(CASE WHEN includeInContext = 1 THEN 1 ELSE 0 END) <> 1
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE messages
                SET includeInContext = CASE
                    WHEN id = (
                        SELECT recovery.selectedMessageId
                        FROM legacy_swipe_recovery AS recovery
                        WHERE recovery.sessionId = messages.sessionId
                          AND recovery.swipeGroupId = messages.swipeGroupId
                    ) THEN 1
                    ELSE 0
                END
                WHERE EXISTS (
                    SELECT 1
                    FROM legacy_swipe_recovery AS recovery
                    WHERE recovery.sessionId = messages.sessionId
                      AND recovery.swipeGroupId = messages.swipeGroupId
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE temp.legacy_swipe_recovery")
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 只补充可用状态和并发代次；不扫描消息，也不重写任何记忆正文。
            db.execSQL(
                "ALTER TABLE `session_context_memories` " +
                    "ADD COLUMN `isValid` INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE `session_context_memories` " +
                    "ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_world_mappings` (
                    `worldTemplateId` INTEGER NOT NULL,
                    `encyclopediaId` INTEGER NOT NULL,
                    `sourceHash` TEXT NOT NULL,
                    `migrationVersion` INTEGER NOT NULL,
                    PRIMARY KEY(`worldTemplateId`),
                    FOREIGN KEY(`worldTemplateId`) REFERENCES `world_templates`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`encyclopediaId`) REFERENCES `world_encyclopedias`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_legacy_world_mappings_encyclopediaId` ON `legacy_world_mappings` (`encyclopediaId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_lore_mappings` (
                    `loreEntryId` INTEGER NOT NULL,
                    `encyclopediaEntryId` INTEGER,
                    `sourceHash` TEXT NOT NULL,
                    `migrationVersion` INTEGER NOT NULL,
                    PRIMARY KEY(`loreEntryId`),
                    FOREIGN KEY(`loreEntryId`) REFERENCES `world_lore_entries`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`encyclopediaEntryId`) REFERENCES `encyclopedia_entries`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_legacy_lore_mappings_encyclopediaEntryId` ON `legacy_lore_mappings` (`encyclopediaEntryId`)")
        }
    }
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(`llm_cost_records`)").use { cursor ->
                while (cursor.moveToNext()) existing += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            fun addColumn(sql: String) {
                val name = sql.substringAfter("ADD COLUMN `").substringBefore('`')
                if (name !in existing) db.execSQL(sql)
            }
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `platformId` TEXT NOT NULL DEFAULT ''")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `platformName` TEXT NOT NULL DEFAULT ''")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'USD'")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `costKnown` INTEGER NOT NULL DEFAULT 1")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `tokenSource` TEXT NOT NULL DEFAULT 'estimated'")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'legacy'")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `cachedPromptTokens` INTEGER NOT NULL DEFAULT 0")
            addColumn("ALTER TABLE `llm_cost_records` ADD COLUMN `pricingSnapshotJson` TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_cost_records_platformId_modelName_id` ON `llm_cost_records` (`platformId`, `modelName`, `id`)")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `branch_context_exclusions` (
                    `sessionId` INTEGER NOT NULL,
                    `branchId` TEXT NOT NULL,
                    `messageKey` TEXT NOT NULL,
                    PRIMARY KEY(`sessionId`, `branchId`, `messageKey`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_branch_context_exclusions_sessionId` ON `branch_context_exclusions` (`sessionId`)")
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN branchId TEXT NOT NULL DEFAULT 'main'")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN lastSnapshotAttemptUserMessageId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE session_character_states ADD COLUMN snapshotIsValid INTEGER NOT NULL DEFAULT 1")
            // Old snapshots have no branch provenance. Keep the row, but never inject it into a story line.
            db.execSQL("UPDATE session_character_states SET branchId = '__legacy_unscoped__', snapshotIsValid = 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_session_character_states_sessionId_characterId_branchId ON session_character_states (sessionId, characterId, branchId)")
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_session_event_nodes_sessionId_branchId_createdAt_id " +
                    "ON session_event_nodes (sessionId, branchId, createdAt, id)",
            )
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN creationRequestId TEXT")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_creationRequestId " +
                    "ON sessions (creationRequestId)",
            )
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_messages_sessionId_branchId_createdAt_id " +
                    "ON messages (sessionId, branchId, createdAt, id)",
            )
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `branch_event_status` (" +
                    "`sessionId` INTEGER NOT NULL, `branchId` TEXT NOT NULL, " +
                    "`eventId` INTEGER NOT NULL, `resolved` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`, `branchId`, `eventId`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`eventId`) REFERENCES `session_event_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_branch_event_status_eventId` ON `branch_event_status` (`eventId`)")
        }
    }

}
