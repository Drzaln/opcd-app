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

    /** Drops entries not refreshed since [before] (keeps the offline copy from growing forever). */
    @Query("DELETE FROM cache WHERE updatedAt < :before AND key != 'outbox'")
    suspend fun prune(before: Long)
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

    /** The saved JSON and when it was saved — for "showing the copy saved 2h ago". */
    suspend fun getEntry(key: String): CacheEntry? = runCatching { dao.get(key) }.getOrNull()

    private val pruned = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun put(key: String, json: String) {
        runCatching { dao.put(CacheEntry(key, json, System.currentTimeMillis())) }
        if (pruned.compareAndSet(false, true)) runCatching { dao.prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000) }
    }

    suspend fun clear() {
        runCatching { dao.clear() }
    }
}