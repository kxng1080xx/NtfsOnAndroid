package dev.ntfsfm.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.ntfsfm.core.data.AppDatabase
import dev.ntfsfm.core.transfer.TransferEngine
import dev.ntfsfm.core.transport.usb.UsbMassStorageManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()

    @Provides fun bookmarkDao(db: AppDatabase) = db.bookmarkDao()
    @Provides fun recentLocationDao(db: AppDatabase) = db.recentLocationDao()
    @Provides fun recycleBinDao(db: AppDatabase) = db.recycleBinDao()
    @Provides fun searchHistoryDao(db: AppDatabase) = db.searchHistoryDao()
    @Provides fun volumeDao(db: AppDatabase) = db.volumeDao()
    @Provides fun indexedFileDao(db: AppDatabase) = db.indexedFileDao()
}

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {
    @Provides
    @Singleton
    fun provideUsbMassStorageManager(@ApplicationContext context: Context) = UsbMassStorageManager(context)

    @Provides
    @Singleton
    fun provideTransferEngine() = TransferEngine()
}
