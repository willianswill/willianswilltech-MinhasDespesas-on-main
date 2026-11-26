package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["profileId", "channelId", "startMs", "endMs"])
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val channelId: String,
    val title: String,
    val description: String = "",
    val startMs: Long,
    val endMs: Long
)
