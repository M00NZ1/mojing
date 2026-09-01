package com.mojing.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(
    contentEntity = MessageEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["searchNormalized"],
)
@Entity(tableName = "message_search_fts")
data class MessageSearchFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val searchNormalized: String,
    val searchTerms: String,
)
