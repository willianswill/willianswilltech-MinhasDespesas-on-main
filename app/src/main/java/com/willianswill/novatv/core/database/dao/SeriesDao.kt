package com.willianswill.novatv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.willianswill.novatv.core.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Query(
        """
        SELECT * FROM series
        WHERE profileId = :profileId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:onlyFavorites = 0 OR isFavorite = 1)
          AND (:includeAdult = 1 OR isAdult = 0)
          AND (:query = '' OR name LIKE '%' || :query || '%')
        ORDER BY name ASC
        """
    )
    fun pagingSource(
        profileId: Long,
        categoryId: String?,
        onlyFavorites: Boolean,
        includeAdult: Boolean,
        query: String
    ): PagingSource<Int, SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE profileId = :profileId
          AND (:includeAdult = 1 OR isAdult = 0)
          AND (
            LOWER(categoryName) LIKE '%' || :kw1 || '%'
            OR LOWER(categoryName) LIKE '%' || :kw2 || '%'
            OR (:kw3 != '' AND LOWER(categoryName) LIKE '%' || :kw3 || '%')
          )
        ORDER BY lastModified DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeSeriesByCategoryKeywords(
        profileId: Long,
        kw1: String,
        kw2: String,
        kw3: String = "",
        includeAdult: Boolean,
        limit: Int = 10
    ): Flow<List<SeriesEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE profileId = :profileId AND (:includeAdult = 1 OR isAdult = 0)
        ORDER BY lastModified DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyAdded(profileId: Long, includeAdult: Boolean, limit: Int = 30): Flow<List<SeriesEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE profileId = :profileId
          AND (:includeAdult = 1 OR isAdult = 0)
          AND name LIKE '%' || :query || '%'
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    suspend fun searchGlobal(
        profileId: Long,
        query: String,
        includeAdult: Boolean,
        limit: Int = 30
    ): List<SeriesEntity>

    @Query("SELECT seriesId FROM series WHERE profileId = :profileId AND isFavorite = 1")
    suspend fun getFavoriteSeriesIds(profileId: Long): List<String>

    @Query("UPDATE series SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM series WHERE profileId = :profileId")
    suspend fun countByProfile(profileId: Long): Int

    @Query("DELETE FROM series WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
