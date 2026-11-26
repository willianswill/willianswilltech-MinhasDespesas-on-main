package com.willianswill.novatv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query(
        """
        SELECT * FROM watch_history
        WHERE profileId = :profileId
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(profileId: Long, limit: Int = 25): Flow<List<WatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE profileId = :profileId AND mediaType = :mediaType
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeByMediaType(profileId: Long, mediaType: String, limit: Int = 25): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND seriesId = :seriesId")
    suspend fun getBySeriesId(profileId: Long, seriesId: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND contentKey = :contentKey LIMIT 1")
    suspend fun getProgress(profileId: Long, contentKey: String): WatchHistoryEntity?

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
