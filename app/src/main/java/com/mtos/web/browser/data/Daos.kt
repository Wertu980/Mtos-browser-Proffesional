package com.mtos.web.browser.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarkedFlow(url: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun isBookmarkedSync(url: String): Boolean
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_items ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyItem: HistoryItem)

    @Query("DELETE FROM history_items WHERE url = :url")
    suspend fun deleteHistoryByUrl(url: String)

    @Query("DELETE FROM history_items WHERE id = :id")
    suspend fun deleteHistoryItem(id: Int)

    @Query("DELETE FROM history_items")
    suspend fun clearAllHistory()

    @Transaction
    suspend fun insertOrUpdateHistory(historyItem: HistoryItem) {
        deleteHistoryByUrl(historyItem.url)
        insertHistory(historyItem)
    }
}

@Dao
interface TabDao {
    @Query("SELECT * FROM browser_tabs ORDER BY displayOrder ASC")
    suspend fun getAllTabs(): List<TabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: TabEntity)

    @Query("DELETE FROM browser_tabs WHERE id NOT IN (:ids)")
    suspend fun deleteTabsNotInList(ids: List<String>): Int

    @Query("DELETE FROM browser_tabs")
    suspend fun clearAllTabs()

    @Transaction
    suspend fun deleteTabsExcept(ids: List<String>) {
        if (ids.isEmpty()) {
            clearAllTabs()
        } else {
            deleteTabsNotInList(ids)
        }
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: String)

    @Query("DELETE FROM downloads")
    suspend fun clearAllDownloads()
}

@Dao
interface PasswordDao {
    @Query("SELECT * FROM password_credentials ORDER BY timestamp DESC")
    fun getAllPasswords(): Flow<List<PasswordCredential>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPassword(credential: PasswordCredential)

    @Delete
    suspend fun deletePassword(credential: PasswordCredential)

    @Query("DELETE FROM password_credentials WHERE id = :id")
    suspend fun deletePasswordById(id: Int)
}



