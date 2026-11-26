package com.willianswill.novatv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.willianswill.novatv.core.database.entity.StreamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(streams: List<StreamEntity>)

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId
          AND mediaType = :mediaType
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:onlyFavorites = 0 OR isFavorite = 1)
          AND (:includeAdult = 1 OR isAdult = 0)
          AND (:query = '' OR baseName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        GROUP BY categoryId, baseName
        ORDER BY
          CASE
            WHEN baseName LIKE '%São Paulo%'
              OR baseName LIKE '%SÃO PAULO%'
              OR UPPER(baseName) LIKE '%SAO PAULO%'
              OR UPPER(baseName) LIKE '% SP'
              OR UPPER(baseName) LIKE '% SP %'
              OR UPPER(baseName) LIKE '%-SP%'
              OR UPPER(baseName) LIKE '%(SP)%'
              OR UPPER(baseName) LIKE '%[SP]%'
              OR UPPER(baseName) LIKE '%PAULISTA%'
            THEN 0
            ELSE 1
          END ASC,
          MIN(id) ASC
        """
    )
    fun pagingSource(
        profileId: Long,
        mediaType: String,
        categoryId: String?,
        onlyFavorites: Boolean,
        includeAdult: Boolean,
        query: String
    ): PagingSource<Int, StreamEntity>

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId
          AND mediaType = :mediaType
          AND categoryId = :categoryId
          AND baseName = :baseName
        ORDER BY qualityRank DESC, id ASC
        """
    )
    suspend fun getVariants(
        profileId: Long,
        mediaType: String,
        categoryId: String,
        baseName: String
    ): List<StreamEntity>

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId
          AND mediaType = 'SERIES'
          AND streamId = :seriesId
        ORDER BY qualityRank ASC, id ASC
        """
    )
    suspend fun getM3uSeriesEpisodes(profileId: Long, seriesId: String): List<StreamEntity>

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId AND isFavorite = 1
          AND (:includeAdult = 1 OR isAdult = 0)
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    fun observeFavorites(profileId: Long, includeAdult: Boolean, limit: Int = 40): Flow<List<StreamEntity>>

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId AND mediaType = :mediaType
          AND (:includeAdult = 1 OR isAdult = 0)
        ORDER BY addedAt DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyAdded(profileId: Long, mediaType: String, includeAdult: Boolean, limit: Int = 30): Flow<List<StreamEntity>>

    @Query("SELECT streamUrl FROM streams WHERE profileId = :profileId AND isFavorite = 1")
    suspend fun getFavoriteUrls(profileId: Long): List<String>

    @Query("UPDATE streams SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM streams WHERE profileId = :profileId AND mediaType = :mediaType")
    suspend fun countByType(profileId: Long, mediaType: String): Int

    @Query(
        """
        SELECT * FROM streams
        WHERE profileId = :profileId AND mediaType = 'LIVE'
          AND (UPPER(baseName) LIKE '%GLOBO%' OR UPPER(name) LIKE '%GLOBO%')
        ORDER BY
          CASE
            WHEN baseName LIKE '%São Paulo%'
              OR baseName LIKE '%SÃO PAULO%'
              OR UPPER(baseName) LIKE '%SAO PAULO%'
              OR UPPER(baseName) LIKE '% SP%'
              OR UPPER(baseName) LIKE '%-SP%'
              OR UPPER(baseName) LIKE '%(SP)%'
            THEN 0
            ELSE 1
          END ASC,
          qualityRank DESC,
          id ASC
        LIMIT 1
        """
    )
    suspend fun findPreferredGloboSp(profileId: Long): StreamEntity?

    @Query("DELETE FROM streams WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
