package dev.ntfsfm.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.ntfsfm.core.data.entity.BookmarkEntity
import dev.ntfsfm.core.data.entity.IndexedFileEntity
import dev.ntfsfm.core.data.entity.RecentLocationEntity
import dev.ntfsfm.core.data.entity.RecycleBinEntryEntity
import dev.ntfsfm.core.data.entity.SearchHistoryEntity
import dev.ntfsfm.core.data.entity.VolumeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)
}

@Dao
interface IndexedFileDao {
    @Query("SELECT * FROM indexed_files WHERE category = :category")
    fun observeByCategory(category: String): Flow<List<IndexedFileEntity>>

    @Query("SELECT * FROM indexed_files WHERE displayName LIKE '%' || :query || '%' AND (:category IS NULL OR category = :category)")
    fun search(query: String, category: String?): Flow<List<IndexedFileEntity>>

    @Query("SELECT * FROM indexed_files WHERE volumeId = :volumeId ORDER BY sizeBytes DESC LIMIT :limit")
    fun largestForVolume(volumeId: String, limit: Int = 50): Flow<List<IndexedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<IndexedFileEntity>)

    @Query("DELETE FROM indexed_files WHERE volumeId = :volumeId")
    suspend fun clearForVolume(volumeId: String)
}

@Dao
interface RecentLocationDao {
    @Query("SELECT * FROM recent_locations ORDER BY lastAccessedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RecentLocationEntity>>

    @Upsert
    suspend fun upsert(location: RecentLocationEntity)

    @Query("DELETE FROM recent_locations WHERE id NOT IN (SELECT id FROM recent_locations ORDER BY lastAccessedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM recycle_bin_entries WHERE volumeId = :volumeId ORDER BY deletedAt DESC")
    fun observeForVolume(volumeId: String): Flow<List<RecycleBinEntryEntity>>

    @Query("SELECT * FROM recycle_bin_entries WHERE expiresAt <= :now")
    suspend fun expired(now: Long): List<RecycleBinEntryEntity>

    @Insert
    suspend fun insert(entry: RecycleBinEntryEntity): Long

    @Delete
    suspend fun delete(entry: RecycleBinEntryEntity)

    @Query("DELETE FROM recycle_bin_entries WHERE volumeId = :volumeId")
    suspend fun clearForVolume(volumeId: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<SearchHistoryEntity>>

    @Insert
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface VolumeDao {
    @Query("SELECT * FROM volumes")
    fun observeAll(): Flow<List<VolumeEntity>>

    @Upsert
    suspend fun upsert(volume: VolumeEntity)

    @Query("DELETE FROM volumes WHERE volumeId = :volumeId")
    suspend fun delete(volumeId: String)
}
