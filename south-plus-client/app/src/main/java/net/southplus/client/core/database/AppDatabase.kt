package net.southplus.client.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 页面 HTML 缓存（方案 §3.3 激进缓存 / §6.9）。 */
@Entity(tableName = "cached_pages")
data class CachedPageEntity(
    @PrimaryKey val cacheKey: String, // "index" | "thread:{fid}:{page}" | "read:{tid}:{page}"
    val url: String,                  // 解析时的 baseUri
    val html: String,
    val fetchedAt: Long,
    val schemaVersion: Int,
    val bytes: Int,
)

@Entity(tableName = "history", indices = [Index(value = ["tid"], unique = true), Index(value = ["visitedAt"])])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tid: String,
    val title: String,
    val url: String,
    val visitedAt: Long,
)

@Entity(tableName = "read_marks")
data class ReadMarkEntity(
    @PrimaryKey val tid: String,
    val lastPage: Int,
    val visitedAt: Long,
)

/** 屏蔽规则（方案 §6.10 用户/关键词屏蔽）。 */
@Entity(tableName = "block_rules")
data class BlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // USER | KEYWORD
    val value: String,
)

data class CacheSize(val cacheKey: String, val bytes: Int)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_pages WHERE cacheKey = :key")
    suspend fun get(key: String): CachedPageEntity?

    @Upsert
    suspend fun put(page: CachedPageEntity)

    @Query("DELETE FROM cached_pages WHERE cacheKey LIKE :prefix")
    suspend fun invalidatePrefix(prefix: String)

    @Query("DELETE FROM cached_pages")
    suspend fun clear()

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM cached_pages")
    suspend fun totalBytes(): Long

    @Query(
        "DELETE FROM cached_pages WHERE cacheKey NOT IN " +
            "(SELECT cacheKey FROM cached_pages ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("SELECT cacheKey, bytes FROM cached_pages ORDER BY fetchedAt ASC LIMIT 32")
    suspend fun oldestSizes(): List<CacheSize>

    @Query("DELETE FROM cached_pages WHERE cacheKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Transaction
    suspend fun trimToBytes(limit: Long) {
        var total = totalBytes()
        while (total > limit) {
            val oldest = oldestSizes()
            if (oldest.isEmpty()) break
            deleteKeys(oldest.map { it.cacheKey })
            total -= oldest.sumOf { it.bytes.toLong() }
        }
    }

    /** 选择器大版本升级时使旧缓存失效（方案 §6.3 schema_version）。 */
    @Query("DELETE FROM cached_pages WHERE schemaVersion != :version")
    suspend fun invalidateByVersion(version: Int)
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun add(item: HistoryEntity)

    @Query("SELECT id FROM history WHERE tid = :tid LIMIT 1")
    suspend fun idOf(tid: String): Long?

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC, id DESC LIMIT :limit)")
    suspend fun trim(limit: Int = 1000)

    @Transaction
    suspend fun record(item: HistoryEntity) {
        add(item.copy(id = idOf(item.tid) ?: 0))
        trim()
    }

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface ReadMarkDao {
    @Upsert
    suspend fun set(mark: ReadMarkEntity)

    @Query("SELECT * FROM read_marks WHERE tid = :tid")
    suspend fun get(tid: String): ReadMarkEntity?

    @Query("SELECT tid FROM read_marks WHERE tid IN (:tids)")
    suspend fun readTids(tids: List<String>): List<String>
}

@Dao
interface BlockDao {
    @Insert
    suspend fun add(rule: BlockRuleEntity)

    @Query("DELETE FROM block_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM block_rules")
    fun all(): Flow<List<BlockRuleEntity>>

    @Query("SELECT * FROM block_rules")
    suspend fun allNow(): List<BlockRuleEntity>
}

@Database(
    entities = [CachedPageEntity::class, HistoryEntity::class, ReadMarkEntity::class, BlockRuleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun historyDao(): HistoryDao
    abstract fun readMarkDao(): ReadMarkDao
    abstract fun blockDao(): BlockDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS history_migration_tid ON history(tid)")
                db.execSQL("DELETE FROM history WHERE id IN (SELECT h.id FROM history h JOIN history n ON h.tid = n.tid AND (n.visitedAt > h.visitedAt OR (n.visitedAt = h.visitedAt AND n.id > h.id)))")
                db.execSQL("DROP INDEX history_migration_tid")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_tid ON history(tid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_visitedAt ON history(visitedAt)")
                db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC, id DESC LIMIT 1000)")
                db.execSQL("UPDATE cached_pages SET bytes = length(CAST(html AS BLOB))")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "southplus.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
