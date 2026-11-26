package com.willianswill.novatv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.willianswill.novatv.core.database.dao.CategoryDao
import com.willianswill.novatv.core.database.dao.EpgDao
import com.willianswill.novatv.core.database.dao.ProfileDao
import com.willianswill.novatv.core.database.dao.SeriesDao
import com.willianswill.novatv.core.database.dao.StreamDao
import com.willianswill.novatv.core.database.dao.WatchHistoryDao
import com.willianswill.novatv.core.database.entity.CategoryEntity
import com.willianswill.novatv.core.database.entity.EpgProgramEntity
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity

@Database(
    entities = [
        ProfileEntity::class,
        CategoryEntity::class,
        StreamEntity::class,
        SeriesEntity::class,
        EpgProgramEntity::class,
        WatchHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NovaTvDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun categoryDao(): CategoryDao
    abstract fun streamDao(): StreamDao
    abstract fun seriesDao(): SeriesDao
    abstract fun epgDao(): EpgDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: NovaTvDatabase? = null

        fun getInstance(context: Context): NovaTvDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaTvDatabase::class.java,
                    "novatv_iptv.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
