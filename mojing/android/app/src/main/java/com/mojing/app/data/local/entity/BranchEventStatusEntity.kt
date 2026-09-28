package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A branch-local choice for an event inherited from an earlier story line. */
@Entity(
    tableName = "branch_event_status",
    primaryKeys = ["sessionId", "branchId", "eventId"],
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE),
        ForeignKey(SessionEventNodeEntity::class, ["id"], ["eventId"], ForeignKey.CASCADE),
    ],
    indices = [Index("eventId")],
)
data class BranchEventStatusEntity(
    val sessionId: Long,
    val branchId: String,
    val eventId: Long,
    val resolved: Boolean,
)
