package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object MediaType {
    const val LIVE = "LIVE"
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"
}

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["profileId", "mediaType", "categoryId"], unique = true)
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val categoryId: String,
    val name: String,
    val mediaType: String, // MediaType.LIVE / MOVIE / SERIES
    val isAdult: Boolean = false,
    val sortOrder: Int = 0
)
