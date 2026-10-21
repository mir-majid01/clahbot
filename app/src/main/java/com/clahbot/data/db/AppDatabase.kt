package com.clahbot.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val label: String,
    val imagePath: String,
    val regionX: Int = 0,
    val regionY: Int = 0,
    val regionW: Int = 0,
    val regionH: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val durationMs: Long,
    val resourcesCollected: Long,
    val attacksLaunched: Int,
    val startedAt: Long,
    val endedAt: Long
)

@Entity(tableName = "bot_settings")
data class BotSettingsEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    suspend fun getAll(): List<TemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: TemplateEntity): Long

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SessionLogDao {
    @Insert
    suspend fun insert(log: SessionLogEntity): Long

    @Query("SELECT * FROM session_logs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<SessionLogEntity>
}

@Dao
interface BotSettingsDao {
    @Query("SELECT value FROM bot_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: BotSettingsEntity)
}

@Database(
    entities = [TemplateEntity::class, SessionLogEntity::class, BotSettingsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun templateDao(): TemplateDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun botSettingsDao(): BotSettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clahbot.db"
                ).build().also { instance = it }
            }
        }
    }
}
