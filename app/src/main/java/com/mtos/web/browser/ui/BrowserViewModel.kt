package com.mtos.web.browser.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtos.web.browser.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.UUID

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "browser://home",
    val title: String = "Start Page",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val jsEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val isIncognito: Boolean = false
)

data class DownloadItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val url: String,
    val size: String,
    val progress: Float, // 0.0 to 1.0
    val isCompleted: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val email: String,
    val displayName: String,
    val provider: String,
    val avatarUrl: String? = null,
    val joinedTimestamp: Long = System.currentTimeMillis()
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = BrowserRepository(database)
    private val dbMutex = Mutex()
    private val sharedPrefs = application.getSharedPreferences("browser_auth_prefs", android.content.Context.MODE_PRIVATE)

    // User Session & Sync states
    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    // Observe bookmarks and history
    val bookmarks: StateFlow<List<Bookmark>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryItem>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tabs management
    private val _tabs = MutableStateFlow<List<BrowserTab>>(listOf(BrowserTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<BrowserTab?> = combine(_tabs, _activeTabId) { tabs, activeId ->
        tabs.find { it.id == activeId } ?: tabs.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _tabs.value.first())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Download & Notification States
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun showToast(message: String) {
        _toastMessage.value = message
        viewModelScope.launch {
            kotlinx.coroutines.delay(3500)
            _toastMessage.compareAndSet(message, null)
        }
    }

    init {
        // Restore user session & sync state
        val savedEmail = sharedPrefs.getString("user_email", null)
        val savedName = sharedPrefs.getString("user_name", null)
        val savedProvider = sharedPrefs.getString("user_provider", null)
        val savedAvatar = sharedPrefs.getString("user_avatar", null)
        val savedSyncTime = sharedPrefs.getLong("last_sync_time", -1L)

        if (savedEmail != null && savedName != null && savedProvider != null) {
            _currentUser.value = UserProfile(
                email = savedEmail,
                displayName = savedName,
                provider = savedProvider,
                avatarUrl = savedAvatar
            )
        }
        if (savedSyncTime != -1L) {
            _lastSyncTime.value = savedSyncTime
        }

        viewModelScope.launch {
            try {
                val savedTabs = repository.getAllTabs()
                if (savedTabs.isNotEmpty()) {
                    val browserTabs = savedTabs.map { entity ->
                        BrowserTab(
                            id = entity.id,
                            url = entity.url,
                            title = entity.title,
                            jsEnabled = entity.jsEnabled,
                            desktopMode = entity.desktopMode
                        )
                    }
                    _tabs.value = browserTabs
                    val activeEntity = savedTabs.find { it.lastActive } ?: savedTabs.first()
                    _activeTabId.value = activeEntity.id
                } else {
                    persistTabsState()
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Database load failed, initializing default tab", e)
            } finally {
                _isInitialized.value = true
            }
        }
    }

    fun selectUser(email: String, name: String, provider: String, avatar: String?) {
        val profile = UserProfile(email, name, provider, avatar)
        _currentUser.value = profile
        sharedPrefs.edit()
            .putString("user_email", email)
            .putString("user_name", name)
            .putString("user_provider", provider)
            .putString("user_avatar", avatar)
            .apply()
        showToast("Signed in as $name.")
    }

    fun logout() {
        _currentUser.value = null
        _lastSyncTime.value = null
        sharedPrefs.edit()
            .remove("user_email")
            .remove("user_name")
            .remove("user_provider")
            .remove("user_avatar")
            .remove("last_sync_time")
            .apply()
        showToast("Logged out successfully.")
    }

    fun registerUser(email: String, name: String, password: String): Boolean {
        val trimmedEmail = email.trim().lowercase()
        if (trimmedEmail.isBlank() || name.isBlank() || password.length < 6) return false

        val registered = sharedPrefs.getStringSet("registered_emails", null) ?: mutableSetOf()
        if (registered.contains(trimmedEmail)) return false

        val updated = registered.toMutableSet().apply { add(trimmedEmail) }
        sharedPrefs.edit()
            .putStringSet("registered_emails", updated)
            .putString("pass_$trimmedEmail", password)
            .putString("name_$trimmedEmail", name.trim())
            .apply()
        return true
    }

    fun loginUser(email: String, password: String): Boolean {
        val trimmedEmail = email.trim().lowercase()
        val registered = sharedPrefs.getStringSet("registered_emails", null) ?: emptySet()
        if (!registered.contains(trimmedEmail)) return false

        val savedPass = sharedPrefs.getString("pass_$trimmedEmail", null)
        if (savedPass == password) {
            val name = sharedPrefs.getString("name_$trimmedEmail", "User") ?: "User"
            selectUser(trimmedEmail, name, "Email", null)
            return true
        }
        return false
    }

    fun triggerDataSync() {
        val user = _currentUser.value ?: return
        if (_isSyncing.value) return

        _isSyncing.value = true
        viewModelScope.launch {
            // Simulated clouds handshake & merging of data
            kotlinx.coroutines.delay(2000)
            _isSyncing.value = false
            val now = System.currentTimeMillis()
            _lastSyncTime.value = now
            sharedPrefs.edit().putLong("last_sync_time", now).apply()
            showToast("Sync completed for ${user.email}!")
        }
    }

    fun handleExternalUrl(url: String?) {
        if (url.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                // Wait for initialization to complete to avoid race conditions with restored tabs
                _isInitialized.first { it }

                val currentTabs = _tabs.value
                val activeId = _activeTabId.value
                val active = currentTabs.find { it.id == activeId }
                if (active != null && (active.url == "browser://home" || active.url.isBlank() || active.url == "about:blank")) {
                    updateUrl(activeId, url)
                } else {
                    createNewTab(url)
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Error handling external URL", e)
            }
        }
    }

    private fun persistTabsState() {
        val currentTabs = _tabs.value
        val activeId = _activeTabId.value
        viewModelScope.launch {
            dbMutex.withLock {
                try {
                    val nonIncognitoTabs = currentTabs.filter { !it.isIncognito }
                    val currentIds = nonIncognitoTabs.map { it.id }
                    repository.deleteTabsExcept(currentIds)
                    nonIncognitoTabs.forEachIndexed { index, tab ->
                        repository.insertTab(
                            TabEntity(
                                id = tab.id,
                                url = tab.url,
                                title = tab.title,
                                jsEnabled = tab.jsEnabled,
                                desktopMode = tab.desktopMode,
                                lastActive = tab.id == activeId,
                                displayOrder = index
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BrowserViewModel", "Failed to persist tabs state to DB", e)
                }
            }
        }
    }

    // Toggle Bookmarks flow for UI highlights
    fun isBookmarked(url: String): Flow<Boolean> = repository.isBookmarked(url)

    // Actions
    fun createNewTab(url: String = "browser://home", isIncognito: Boolean = false) {
        val title = when (url) {
            "browser://home" -> if (isIncognito) "Private Tab" else "Start Page"
            "https://www.google.com" -> "Google"
            else -> if (isIncognito) "Private Tab" else "New Tab"
        }
        val newTab = BrowserTab(url = url, title = title, isIncognito = isIncognito)
        _tabs.update { it + newTab }
        _activeTabId.value = newTab.id
        persistTabsState()
    }

    fun closeTab(tabId: String) {
        val currentList = _tabs.value
        if (currentList.size <= 1) {
            // Re-initialize if the last tab is closed
            val replacement = BrowserTab()
            _tabs.value = listOf(replacement)
            _activeTabId.value = replacement.id
            persistTabsState()
            return
        }

        val closingIndex = currentList.indexOfFirst { it.id == tabId }
        val newList = currentList.filter { it.id != tabId }
        _tabs.value = newList

        if (_activeTabId.value == tabId) {
            // Focus another tab
            val nextActiveIndex = if (closingIndex >= newList.size) newList.size - 1 else closingIndex
            _activeTabId.value = newList[nextActiveIndex].id
        }
        persistTabsState()
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        persistTabsState()
    }

    private fun updateTab(tabId: String, block: (BrowserTab) -> BrowserTab) {
        _tabs.update { list ->
            list.map { if (it.id == tabId) block(it) else it }
        }
    }

    fun updateUrl(tabId: String, url: String) {
        updateTab(tabId) { it.copy(url = url) }
        persistTabsState()
    }

    fun updateProgress(tabId: String, progress: Int, isLoading: Boolean) {
        updateTab(tabId) { it.copy(progress = progress, isLoading = isLoading) }
    }

    fun updateNavigationState(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun toggleJs(tabId: String) {
        updateTab(tabId) { it.copy(jsEnabled = !it.jsEnabled) }
        persistTabsState()
    }

    fun toggleDesktopMode(tabId: String) {
        updateTab(tabId) { it.copy(desktopMode = !it.desktopMode) }
        persistTabsState()
    }

    fun startDownload(context: android.content.Context, url: String, fileName: String, sizeText: String) {
        val downloadId = java.util.UUID.randomUUID().toString()
        val newDownload = DownloadItem(
            id = downloadId,
            fileName = fileName,
            url = url,
            size = sizeText,
            progress = 0f,
            isCompleted = false
        )
        _downloads.update { it + newDownload }
        showToast("Started downloading $fileName...")

        try {
            val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                setMimeType(android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    android.webkit.MimeTypeMap.getFileExtensionFromUrl(url)
                ))
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(fileName)
                setDescription("Downloading $fileName via Web Browser")
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val id = downloadManager.enqueue(request)
            showDownloadNotification(context, fileName, "Downloading (0%)...")

            viewModelScope.launch {
                var completed = false
                while (!completed) {
                    kotlinx.coroutines.delay(1000)
                    val query = android.app.DownloadManager.Query().setFilterById(id)
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val bytesDownloadedCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalBytesCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val statusCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)

                        if (bytesDownloadedCol != -1 && totalBytesCol != -1 && statusCol != -1) {
                            val bytesDownloaded = cursor.getInt(bytesDownloadedCol)
                            val totalBytes = cursor.getInt(totalBytesCol)
                            val status = cursor.getInt(statusCol)

                            val progress = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0f
                            _downloads.update { list ->
                                list.map {
                                    if (it.id == downloadId) {
                                        it.copy(
                                            progress = progress,
                                            isCompleted = status == android.app.DownloadManager.STATUS_SUCCESSFUL
                                        )
                                    } else it
                                }
                            }

                            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                completed = true
                                showToast("Download completed: $fileName")
                                showDownloadNotification(context, fileName, "Download completed successfully!")
                            } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                                completed = true
                                showToast("Download failed: $fileName")
                                showDownloadNotification(context, fileName, "Download failed.")
                            }
                        }
                    } else {
                        completed = true
                    }
                    cursor?.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Failed to enqueue download", e)
            showToast("Failed to start download.")
        }
    }

    private fun showDownloadNotification(context: android.content.Context, title: String, message: String) {
        val channelId = "browser_downloads"
        val channelName = "Downloads"
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, channelName, android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Browser download updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(title.hashCode(), notification)
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Failed to post notification", e)
        }
    }

    fun onPageFinished(tabId: String, title: String, url: String) {
        val tab = _tabs.value.find { it.id == tabId }
        val isIncognito = tab?.isIncognito == true
        
        val polishedTitle = if (title.isBlank()) {
            if (url.startsWith("https://www.google.com/search")) "Google Search" else url
        } else {
            title
        }
        updateTab(tabId) { it.copy(url = url, title = if (isIncognito && (title.isBlank() || title == "Start Page")) "Private Page" else polishedTitle) }
        persistTabsState()

        // Insert into history (ignore search engine queries if desired or load cleanly)
        if (!isIncognito && url.isNotBlank() && !url.startsWith("about:") && !url.startsWith("chrome:")) {
            viewModelScope.launch {
                try {
                    repository.insertHistory(
                        HistoryItem(
                            url = url,
                            title = polishedTitle
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.e("BrowserViewModel", "Failed to insert history item to DB", e)
                }
            }
        }
    }

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch {
            try {
                val isAlreadyBookmarked = repository.isBookmarkedSync(url)
                val currentTitle = if (title.isBlank()) url else title
                if (isAlreadyBookmarked) {
                    repository.deleteBookmark(Bookmark(url = url, title = currentTitle))
                } else {
                    repository.insertBookmark(Bookmark(url = url, title = currentTitle))
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to toggle bookmark", e)
            }
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            try {
                repository.deleteHistoryItem(id)
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to delete history item", e)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                repository.clearHistory()
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to clear history", e)
            }
        }
    }

    fun resolveUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://www.google.com"

        // Recognizes domains like google.com, test.co, custom IP, etc.
        val urlPattern = "^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]{2,}(/.*)?$".toRegex()
        return if (trimmed.matches(urlPattern) || trimmed.startsWith("localhost") || trimmed.startsWith("10.0.2.2")) {
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else {
                trimmed
            }
        } else {
            // Google search query
            val queryEncoded = URLEncoder.encode(trimmed, "UTF-8")
            "https://www.google.com/search?q=$queryEncoded"
        }
    }
}
