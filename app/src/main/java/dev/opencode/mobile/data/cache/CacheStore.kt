package dev.opencode.mobile.data.cache

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

@Entity(tableName = "cache")
data class CacheEntry(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE key = :key LIMIT 1")
    suspend fun get(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntry)

    @Query("DELETE FROM cache")
    suspend fun clear()
}

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

class CacheStore(context: Context) {

    private val dao = Room.databaseBuilder(context.applicationContext, CacheDatabase::class.java, "opencode-cache")
        .fallbackToDestructiveMigration()
        .build()
        .cacheDao()

    suspend fun get(key: String): String? = runCatching { dao.get(key)?.json }.getOrNull()

    suspend fun put(key: String, json: String) {
        runCatching { dao.put(CacheEntry(key, json, System.currentTimeMillis())) }
    }

    suspend fun clear() {
        runCatching { dao.clear() }
    }
}