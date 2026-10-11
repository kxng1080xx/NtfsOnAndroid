package dev.ntfsfm.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.ntfsfm.core.data.dao.BookmarkDao
import dev.ntfsfm.core.data.dao.IndexedFileDao
import dev.ntfsfm.core.data.dao.RecentLocationDao
import dev.ntfsfm.core.data.dao.RecycleBinDao
import dev.ntfsfm.core.data.dao.SearchHistoryDao
import dev.ntfsfm.core.data.dao.VolumeDao
import dev.ntfsfm.core.data.entity.BookmarkEntity
import dev.ntfsfm.core.data.entity.IndexedFileEntity
import dev.ntfsfm.core.data.entity.RecentLocationEntity
import dev.ntfsfm.core.data.entity.RecycleBinEntryEntity
import dev.ntfsfm.core.data.entity.SearchHistoryEntity
import dev.ntfsfm.core.data.entity.VolumeEntity

@Database(
    entities = [
        BookmarkEntity::class,
        RecentLocationEntity::class,
        RecycleBinEntryEntity::class,
        SearchHistoryEntity::class,
        VolumeEntity::class,
        IndexedFileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun indexedFileDao(): IndexedFileDao
    abstract fun recentLocationDao(): RecentLocationDao
    abstract fun recycleBinDao(): RecycleBinDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun volumeDao(): VolumeDao

    companion object {
        const val DATABASE_NAME = "ntfsfm.db"
    }
}
