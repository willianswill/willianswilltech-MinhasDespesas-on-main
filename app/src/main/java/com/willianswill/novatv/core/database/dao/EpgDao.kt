package com.willianswill.novatv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.willianswill.novatv.core.database.entity.EpgProgramEntity

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<EpgProgramEntity>)

    @Query(
        """
        SELECT * FROM epg_programs
        WHERE profileId = :profileId
          AND channelId = :channelId
          AND startMs <= :nowMs
          AND endMs > :nowMs
        ORDER BY startMs ASC
        LIMIT 1
        """
    )
    suspend fun getCurrentProgram(profileId: Long, channelId: String, nowMs: Long): EpgProgramEntity?

    @Query(
        """
        SELECT * FROM epg_programs
        WHERE profileId = :profileId
          AND channelId = :channelId
          AND endMs > :fromMs
          AND startMs < :toMs
        ORDER BY startMs ASC
        """
    )
    suspend fun getProgramsInRange(
        profileId: Long,
        channelId: String,
        fromMs: Long,
        toMs: Long
    ): List<EpgProgramEntity>

    @Query("DELETE FROM epg_programs WHERE profileId = :profileId AND endMs < :cutoffMs")
    suspend fun deleteExpired(profileId: Long, cutoffMs: Long)

    @Query("DELETE FROM epg_programs WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
