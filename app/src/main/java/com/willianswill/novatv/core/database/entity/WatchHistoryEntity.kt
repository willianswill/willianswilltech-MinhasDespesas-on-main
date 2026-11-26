package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [
        Index(value = ["profileId", "userProfileId", "contentKey"], unique = true),
        Index(value = ["profileId", "userProfileId", "updatedAt"])
    ]
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val userProfileId: Long = 1L,
    val contentKey: String,
    val mediaType: String,
    val title: String,
    val iconUrl: String = "",
    val streamUrl: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seriesId: String = "",
    val seasonNum: Int = 0,
    val episodeNum: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
