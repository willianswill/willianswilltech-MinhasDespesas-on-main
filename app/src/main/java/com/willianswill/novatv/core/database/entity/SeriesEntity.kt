package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["profileId", "categoryId"]),
        Index(value = ["profileId", "name"]),
        Index(value = ["profileId", "seriesId"], unique = true)
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val seriesId: String,
    val name: String,
    val coverUrl: String = "",
    val categoryId: String = "",
    val categoryName: String = "Séries",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val rating: String = "",
    val isFavorite: Boolean = false,
    val isAdult: Boolean = false,
    val lastModified: Long = 0L
)
