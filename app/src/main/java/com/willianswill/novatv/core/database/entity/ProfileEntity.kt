package com.willianswill.novatv.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProfileType {
    XTREAM,
    M3U_URL,
    M3U_LOCAL,
    STALKER
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // ProfileType.name
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val isActive: Boolean = false,
    val lastSyncAt: Long = 0L,
    val totalChannels: Int = 0,
    val totalMovies: Int = 0,
    val totalSeries: Int = 0
)
