package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "streams",
    indices = [
        Index(value = ["profileId", "mediaType", "categoryId"]),
        Index(value = ["profileId", "mediaType", "categoryId", "baseName"]),
        Index(value = ["profileId", "mediaType", "name"]),
        Index(value = ["profileId", "isFavorite"]),
        Index(value = ["profileId", "streamUrl"], unique = true)
    ]
)
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val streamId: String,
    val mediaType: String, // MediaType.LIVE or MediaType.MOVIE
    val name: String,
    val baseName: String = name,
    val qualityLabel: String = "Padrão",
    val qualityRank: Int = 2, // 1=SD, 2=HD, 3=FHD, 4=4K
    val streamUrl: String,
    val iconUrl: String = "",
    val categoryId: String = "",
    val categoryName: String = "Geral",
    val epgChannelId: String = "",
    val containerExtension: String = "",
    val rating: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val releaseDate: String = "",
    val hasCatchup: Boolean = false,
    val catchupDays: Int = 0,
    val userAgent: String? = null,
    val referrer: String? = null,
    val isFavorite: Boolean = false,
    val isAdult: Boolean = false,
    val addedAt: Long = 0L
)
