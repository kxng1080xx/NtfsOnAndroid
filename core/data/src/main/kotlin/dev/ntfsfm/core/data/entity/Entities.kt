package dev.ntfsfm.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val path: String,
    val displayName: String,
    val createdAt: Long,
    val sortOrder: Int,
)

@Entity(tableName = "recent_locations")
data class RecentLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val path: String,
    val displayName: String,
    val lastAccessedAt: Long,
)

@Entity(tableName = "recycle_bin_entries")
data class RecycleBinEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val originalPath: String,
    val trashPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val deletedAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val searchedAt: Long,
)

/** Last-known facts about a volume, so it can be listed while unplugged. */
@Entity(tableName = "volumes")
data class VolumeEntity(
    @PrimaryKey val volumeId: String,
    val displayName: String,
    val lastIndexedAt: Long?,
    val totalBytes: Long,
    val usedBytes: Long,
)

/** One file seen by the indexer, for category views and search without walking the volume. */
@Entity(tableName = "indexed_files", indices = [Index("volumeId", "category")])
data class IndexedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val path: String,
    val displayName: String,
    val category: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val modifiedAt: Long,
)
