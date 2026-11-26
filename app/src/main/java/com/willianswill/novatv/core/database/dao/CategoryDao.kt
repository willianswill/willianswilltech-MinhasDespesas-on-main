package com.willianswill.novatv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.willianswill.novatv.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query(
        """
        SELECT * FROM categories
        WHERE profileId = :profileId AND mediaType = :mediaType
        AND (:includeAdult = 1 OR isAdult = 0)
        ORDER BY sortOrder ASC, name ASC
        """
    )
    fun observeCategories(
        profileId: Long,
        mediaType: String,
        includeAdult: Boolean
    ): Flow<List<CategoryEntity>>

    @Query("DELETE FROM categories WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
