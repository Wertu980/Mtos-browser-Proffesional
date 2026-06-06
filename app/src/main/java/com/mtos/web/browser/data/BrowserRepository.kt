package com.mtos.web.browser.data

import kotlinx.coroutines.flow.Flow

class BrowserRepository(database: AppDatabase) {
    private val bookmarkDao = database.bookmarkDao()
    private val historyDao = database.historyDao()
    private val tabDao = database.tabDao()

    val allBookmarks: Flow<List<Bookmark>> = bookmarkDao.getAllBookmarks()
    val allHistory: Flow<List<HistoryItem>> = historyDao.getAllHistory()

    suspend fun getAllTabs(): List<TabEntity> {
        return tabDao.getAllTabs()
    }

    suspend fun insertTab(tab: TabEntity) {
        tabDao.insertTab(tab)
    }

    suspend fun deleteTabsExcept(ids: List<String>) {
        tabDao.deleteTabsExcept(ids)
    }

    suspend fun clearAllTabs() {
        tabDao.clearAllTabs()
    }

    suspend fun insertBookmark(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    fun isBookmarked(url: String): Flow<Boolean> {
        return bookmarkDao.isBookmarkedFlow(url)
    }

    suspend fun isBookmarkedSync(url: String): Boolean {
        return bookmarkDao.isBookmarkedSync(url)
    }

    suspend fun insertHistory(historyItem: HistoryItem) {
        historyDao.insertOrUpdateHistory(historyItem)
    }

    suspend fun deleteHistoryItem(id: Int) {
        historyDao.deleteHistoryItem(id)
    }

    suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }

    private val downloadDao = database.downloadDao()
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    suspend fun insertDownload(download: DownloadEntity) {
        downloadDao.insertDownload(download)
    }

    suspend fun deleteDownload(id: String) {
        downloadDao.deleteDownloadById(id)
    }

    suspend fun clearAllDownloads() {
        downloadDao.clearAllDownloads()
    }

    private val passwordDao = database.passwordDao()
    val allPasswords: Flow<List<PasswordCredential>> = passwordDao.getAllPasswords()

    suspend fun insertPassword(credential: PasswordCredential) {
        passwordDao.insertPassword(credential)
    }

    suspend fun deletePassword(credential: PasswordCredential) {
        passwordDao.deletePassword(credential)
    }

    suspend fun deletePasswordById(id: Int) {
        passwordDao.deletePasswordById(id)
    }
}
