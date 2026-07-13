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
    val filePath: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class UserProfile(
    val email: String,
    val displayName: String,
    val provider: String,
    val avatarUrl: String? = null,
    val joinedTimestamp: Long = System.currentTimeMillis()
)

data class PasswordSavePromptState(
    val url: String,
    val username: String,
    val plainText: String
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

    // Download & Notification States backed by Room Database
    val downloads: StateFlow<List<DownloadItem>> = repository.allDownloads
        .map { entityList ->
            entityList.map { entity ->
                DownloadItem(
                    id = entity.id,
                    fileName = entity.fileName,
                    url = entity.url,
                    size = entity.size,
                    progress = entity.progress,
                    isCompleted = entity.isCompleted,
                    filePath = entity.filePath,
                    timestamp = entity.timestamp
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedPasswords: StateFlow<List<PasswordCredential>> = repository.allPasswords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _passwordSavePrompt = MutableStateFlow<PasswordSavePromptState?>(null)
    val passwordSavePrompt: StateFlow<PasswordSavePromptState?> = _passwordSavePrompt.asStateFlow()

    fun triggerPasswordSavePrompt(url: String, username: String, plainText: String) {
        viewModelScope.launch {
            if (username.isBlank() || plainText.isBlank() || url.isBlank()) return@launch
            // Standardize URL to clean host domain format
            val cleanUrl = try {
                val uri = android.net.Uri.parse(url)
                uri.host ?: url
            } catch (e: Exception) {
                url
            }
            val currentList = savedPasswords.value
            val alreadyExists = currentList.any { credential ->
                credential.websiteUrl.contains(cleanUrl, ignoreCase = true) &&
                        credential.username.equals(username, ignoreCase = true)
            }
            if (!alreadyExists) {
                _passwordSavePrompt.value = PasswordSavePromptState(cleanUrl, username, plainText)
            }
        }
    }

    fun clearPasswordSavePrompt() {
        _passwordSavePrompt.value = null
    }

    fun savePassword(websiteUrl: String, username: String, plainText: String, label: String = "") {
        viewModelScope.launch {
            val encrypted = CryptographyHelper.encrypt(plainText)
            val credential = PasswordCredential(
                websiteUrl = websiteUrl.trim(),
                username = username.trim(),
                encryptedPassword = encrypted,
                labelName = label.trim()
            )
            repository.insertPassword(credential)
            showToast("Credential saved securely.")
        }
    }

    fun deletePassword(credential: PasswordCredential) {
        viewModelScope.launch {
            repository.deletePassword(credential)
            showToast("Credential removed.")
        }
    }

    fun deletePasswordById(id: Int) {
        viewModelScope.launch {
            repository.deletePasswordById(id)
            showToast("Credential removed.")
        }
    }

    fun decryptPassword(encrypted: String): String {
        return CryptographyHelper.decrypt(encrypted)
    }

    fun analyzeCredentialWithAI(password: String, username: String, url: String, onCompleted: (String) -> Unit) {
        viewModelScope.launch {
            val analysis = GeminiHelper.evaluatePasswordSecurity(password, username, url)
            onCompleted(analysis)
        }
    }

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

    fun startDownload(
        context: android.content.Context,
        url: String,
        fileName: String,
        sizeText: String,
        mimetype: String = "",
        contentDisposition: String = "",
        userAgent: String = ""
    ) {
        val downloadId = java.util.UUID.randomUUID().toString()

        // Handle Base64 / Data URLs
        if (url.startsWith("data:")) {
            downloadBase64Data(context, url, fileName, mimetype, downloadId)
            return
        }

        // Standardize file name extension if needed
        var finalFileName = fileName
        var finalMimeType = mimetype
        if (finalMimeType.isEmpty()) {
            val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(url.lowercase())
            if (extension.isNotEmpty()) {
                finalMimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ""
            }
        }
        
        if (finalMimeType.isNotEmpty() && !finalFileName.contains(".")) {
            val extensionToUse = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(finalMimeType)
            if (!extensionToUse.isNullOrEmpty()) {
                finalFileName = "$finalFileName.$extensionToUse"
            }
        }

        val publicDownloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (!publicDownloadsDir.exists()) {
            publicDownloadsDir.mkdirs()
        }
        val file = java.io.File(publicDownloadsDir, finalFileName)
        val actualFilePath = file.absolutePath

        val initialDownload = DownloadEntity(
            id = downloadId,
            fileName = finalFileName,
            url = url,
            size = sizeText.ifEmpty { "Calculating..." },
            progress = 0f,
            isCompleted = false,
            filePath = actualFilePath
        )
        viewModelScope.launch {
            repository.insertDownload(initialDownload)
        }
        showToast("Starting download: $finalFileName")

        // Multi-threaded Coroutine Network Fetch with Cookie & UserAgent preservation
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null
            var outputStream: java.io.FileOutputStream? = null
            try {
                val urlObj = java.net.URL(url)
                connection = urlObj.openConnection() as java.net.HttpURLConnection
                
                // Add Session Cookies
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                // Add User-Agent headers
                if (userAgent.isNotEmpty()) {
                    connection.setRequestProperty("User-Agent", userAgent)
                } else {
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                }
                
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw java.io.IOException("Server returned HTTP response error status: $responseCode")
                }

                val contentLength = connection.contentLength
                var readableSize = sizeText
                if (contentLength > 0) {
                    val kb = contentLength / 1024
                    readableSize = if (kb > 1024) "${kb / 1024} MB" else "$kb KB"
                } else if (readableSize.isEmpty() || readableSize == "Calculating...") {
                    readableSize = "Unknown size"
                }

                inputStream = connection.inputStream
                outputStream = java.io.FileOutputStream(file)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                // Spawn status bar progress indicator
                showDownloadNotification(context, finalFileName, "Downloading (0%)...", progress = 0, isFinal = false, file = file)

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    val progress = if (contentLength > 0) (totalBytesRead.toFloat() / contentLength) else 0f
                    val progressPct = (progress * 100).toInt().coerceIn(0, 100)
                    
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 1200 || totalBytesRead == contentLength.toLong()) {
                        lastProgressUpdate = now
                        
                        val updatedDownload = DownloadEntity(
                            id = downloadId,
                            fileName = finalFileName,
                            url = url,
                            size = readableSize,
                            progress = if (contentLength > 0) progress else 0.5f,
                            isCompleted = false,
                            filePath = actualFilePath
                        )
                        repository.insertDownload(updatedDownload)
                        showDownloadNotification(context, finalFileName, "Downloading ($progressPct%)...", progress = progressPct, isFinal = false, file = file)
                    }
                }

                outputStream.flush()

                // Register file download completed in SQLite and System Notification panel
                val finalDownload = DownloadEntity(
                    id = downloadId,
                    fileName = finalFileName,
                    url = url,
                    size = readableSize,
                    progress = 1.0f,
                    isCompleted = true,
                    filePath = actualFilePath
                )
                repository.insertDownload(finalDownload)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Download completed successfully!")
                    showDownloadNotification(context, finalFileName, "Download completed!", progress = 100, isFinal = true, file = file)
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Direct Applet Download failed", e)
                try {
                    file.delete()
                } catch (ignored: Exception) {}
                
                val failedDownload = DownloadEntity(
                    id = downloadId,
                    fileName = finalFileName,
                    url = url,
                    size = "Failed",
                    progress = 0f,
                    isCompleted = false,
                    filePath = ""
                )
                repository.insertDownload(failedDownload)
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Download failed: $finalFileName")
                    showDownloadNotification(context, finalFileName, "Download failed or timed out.", progress = 0, isFinal = true, file = null)
                }
            } finally {
                try { outputStream?.close() } catch (ignored: Exception) {}
                try { inputStream?.close() } catch (ignored: Exception) {}
                try { connection?.disconnect() } catch (ignored: Exception) {}
            }
        }
    }

    fun downloadBase64Data(
        context: android.content.Context,
        base64Url: String,
        fileName: String,
        mimetype: String,
        downloadId: String = java.util.UUID.randomUUID().toString()
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val commaIndex = base64Url.indexOf(",")
                val cleanBase64 = if (commaIndex != -1) base64Url.substring(commaIndex + 1) else base64Url
                val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)

                var ext = ""
                if (mimetype.isNotEmpty()) {
                    ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype) ?: ""
                }
                if (ext.isEmpty() && base64Url.startsWith("data:")) {
                    val firstPart = base64Url.substring(0, commaIndex)
                    if (firstPart.contains(";") && firstPart.contains("/")) {
                        val mime = firstPart.substring(firstPart.indexOf(":") + 1, firstPart.indexOf(";"))
                        ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: ""
                    }
                }

                var finalFileName = fileName
                if (ext.isNotEmpty() && !finalFileName.contains(".")) {
                    finalFileName = "$finalFileName.$ext"
                }

                val publicDownloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!publicDownloadsDir.exists()) {
                    publicDownloadsDir.mkdirs()
                }
                val file = java.io.File(publicDownloadsDir, finalFileName)
                file.writeBytes(decodedBytes)

                val sizeText = "${decodedBytes.size / 1024} KB"
                val finalDownload = DownloadEntity(
                    id = downloadId,
                    fileName = finalFileName,
                    url = "data:...",
                    size = sizeText,
                    progress = 1.0f,
                    isCompleted = true,
                    filePath = file.absolutePath
                )
                repository.insertDownload(finalDownload)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Download completed successfully!")
                    showDownloadNotification(context, finalFileName, "Download completed!", progress = 100, isFinal = true, file = file)
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to decode and save base64 data stream", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Failed to process download data format.")
                }
            }
        }
    }

    fun deleteDownload(id: String, filePath: String) {
        viewModelScope.launch {
            try {
                repository.deleteDownload(id)
                if (filePath.isNotEmpty()) {
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                showToast("Download deleted successfully")
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to delete download", e)
                showToast("Failed to delete download")
            }
        }
    }

    private fun showDownloadNotification(
        context: android.content.Context,
        title: String,
        message: String,
        progress: Int = 0,
        isFinal: Boolean = false,
        file: java.io.File? = null
    ) {
        val channelId = "browser_downloads"
        val channelName = "Downloads"
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, channelName, android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Browser download updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Build elegant Chrome-like PendingIntent supporting direct file-opening triggers
        val intent = if (isFinal && file != null && file.exists()) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "com.mtos.web.browser.fileprovider",
                    file
                )
                val ext = file.extension.lowercase()
                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }

        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getActivity(context, title.hashCode(), intent, flags)

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(if (isFinal) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (!isFinal) {
            builder.setProgress(100, progress, false)
            builder.setOngoing(true) // prevent dismiss in-progress downloads
        } else {
            builder.setProgress(0, 0, false)
        }

        try {
            notificationManager.notify(title.hashCode(), builder.build())
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Failed to post secure notification", e)
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
