package com.willianswill.novatv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.willianswill.novatv.core.database.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isActive DESC, id DESC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE isActive = 1 LIMIT 1")
    fun observeActiveProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("UPDATE profiles SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun markActive(profileId: Long)

    @Transaction
    suspend fun setActiveProfile(profileId: Long) {
        clearActive()
        markActive(profileId)
    }

    @Query("DELETE FROM profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: Long)
}
