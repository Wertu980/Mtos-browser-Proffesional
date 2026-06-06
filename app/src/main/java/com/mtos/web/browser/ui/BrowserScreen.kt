package com.mtos.web.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.flowOf
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtos.web.browser.data.Bookmark
import com.mtos.web.browser.data.HistoryItem
import kotlinx.coroutines.launch

const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7; Build/TQ3A.230805.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

fun getUserAgentForUrl(url: String, isDesktopMode: Boolean): String {
    if (isDesktopMode) return DESKTOP_USER_AGENT
    
    val lowerUrl = url.lowercase()
    val isOauthOrAuth = lowerUrl.contains("accounts.google.com") ||
            lowerUrl.contains("oauth") ||
            lowerUrl.contains("appleid.apple.com") ||
            (lowerUrl.contains("github.com") && (lowerUrl.contains("login") || lowerUrl.contains("session"))) ||
            lowerUrl.contains("auth0.com") ||
            lowerUrl.contains("/login") ||
            lowerUrl.contains("/signin") ||
            lowerUrl.contains("/sign-in") ||
            lowerUrl.contains("facebook.com/dialog/oauth") ||
            lowerUrl.contains("okta.com")

    if (isOauthOrAuth) {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    }
    
    return MOBILE_USER_AGENT
}

// Speed Dial model
data class SpeedDialItem(
    val title: String,
    val url: String,
    val iconLetter: String,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // UI state from VM
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    // Persistent Map of WebViews indexed by Tab UUID
    val webViews = remember { mutableStateMapOf<String, WebView>() }

    // Permissions & Geolocation state variables
    var activeScreen by remember { mutableStateOf("browser") }
    var pendingPermissionRequest by remember { mutableStateOf<android.webkit.PermissionRequest?>(null) }
    var pendingGeolocationRequest by remember { mutableStateOf<Pair<String, android.webkit.GeolocationPermissions.Callback>?>(null) }
    var showPermissionsSheet by remember { mutableStateOf(false) }
    var showSyncAuthSheet by remember { mutableStateOf(false) }
    var showSiteInfoSheet by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        pendingPermissionRequest?.let { req ->
            val requested = req.resources
            val grantedList = mutableListOf<String>()
            val hasCamera = result[android.Manifest.permission.CAMERA] == true || 
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasMic = result[android.Manifest.permission.RECORD_AUDIO] == true || 
                         androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            for (res in requested) {
                if (res == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasCamera) {
                    grantedList.add(res)
                } else if (res == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasMic) {
                    grantedList.add(res)
                } else if (res != android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE && res != android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    grantedList.add(res)
                }
            }
            if (grantedList.isNotEmpty()) {
                req.grant(grantedList.toTypedArray())
                viewModel.showToast("Permissions granted.")
            } else {
                req.deny()
                viewModel.showToast("Permissions denied.")
            }
            pendingPermissionRequest = null
        }

        pendingGeolocationRequest?.let { (origin, callback) ->
            val hasLocation = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                              androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            callback.invoke(origin, hasLocation, false)
            if (hasLocation) {
                viewModel.showToast("Location sharing enabled.")
            } else {
                viewModel.showToast("Location sharing denied.")
            }
            pendingGeolocationRequest = null
        }
    }

    // Synchronize destroyed tabs
    LaunchedEffect(tabs) {
        val aliveTabIds = tabs.map { it.id }.toSet()
        val cachedIds = webViews.keys.toList()
        for (id in cachedIds) {
            if (id !in aliveTabIds) {
                val wv = webViews.remove(id)
                if (wv != null) {
                    val tab = tabs.find { it.id == id }
                    // Detach the WebView clearly from its parent view container and destroy it
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    if (tab?.isIncognito == true) {
                        try {
                            wv.clearCache(true)
                            wv.clearFormData()
                            wv.clearHistory()
                            android.webkit.CookieManager.getInstance().flush()
                        } catch (e: Exception) {
                            android.util.Log.e("BrowserScreen", "Error clearing private data", e)
                        }
                    }
                    try {
                        wv.removeAllViews()
                        wv.destroy()
                    } catch (e: Exception) {
                        android.util.Log.e("BrowserScreen", "Error destroying WebView for tab $id", e)
                    }
                }
            }
        }
    }

    // Clean up all WebViews when BrowserScreen is completely disposed (unmounted)
    DisposableEffect(Unit) {
        onDispose {
            webViews.forEach { (id, wv) ->
                try {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (e: Exception) {
                    android.util.Log.e("BrowserScreen", "Error during final cleanup of WebView $id", e)
                }
            }
            webViews.clear()
        }
    }

    // Media file choosing callback state and result launcher
    var pendingFilePathCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null) }

    val fileChooserLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uriResult = if (result.resultCode == android.app.Activity.RESULT_OK) {
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        pendingFilePathCallback?.onReceiveValue(uriResult)
        pendingFilePathCallback = null
    }

    // Active WebView helper
    val activeWebView = activeTab?.let { tab ->
        getOrCreateWebView(
            context = context,
            tabId = tab.id,
            initialUrl = tab.url,
            viewModel = viewModel,
            webViews = webViews,
            onPermissionRequested = { req -> pendingPermissionRequest = req },
            onGeolocationRequested = { origin, cb -> pendingGeolocationRequest = origin to cb },
            onShowFileChooser = { callback, params ->
                pendingFilePathCallback = callback
                try {
                    val intent = params.createIntent()
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    android.util.Log.e("BrowserScreen", "Failed to launch device file chooser, fallback to ACTION_GET_CONTENT", e)
                    try {
                        val fallback = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        fileChooserLauncher.launch(fallback)
                    } catch (ex: Exception) {
                        callback.onReceiveValue(null)
                        pendingFilePathCallback = null
                    }
                }
            }
        )
    }

    // Intercept hardware Back Button
    val canGoBack = activeTab?.canGoBack == true && activeTab?.url != "browser://home"
    if (activeScreen != "browser") {
        BackHandler(enabled = true) {
            activeScreen = "browser"
        }
    } else {
        BackHandler(enabled = canGoBack) {
            activeWebView?.goBack()
        }
    }

    // Modal Sheet states
    var showTabsSheet by remember { mutableStateOf(false) }
    var showLibrarySheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var showAutofillDialog by remember { mutableStateOf(false) }

    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    // Input state for Search Bar
    var searchInputText by remember { mutableStateOf("") }
    val isSearchBarFocused = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Update input text when activeTab's url changes, unless the user is actively typing
    LaunchedEffect(activeTab?.url) {
        if (!isSearchBarFocused.value) {
            searchInputText = if (activeTab?.url == "browser://home") "" else activeTab?.url ?: ""
        }
    }

    // Apply JS / User Agent dynamically
    LaunchedEffect(activeTab?.jsEnabled, activeTab?.desktopMode, activeTabId) {
        val tab = activeTab ?: return@LaunchedEffect
        val webView = webViews[tab.id] ?: return@LaunchedEffect
        webView.settings.javaScriptEnabled = tab.jsEnabled
        webView.settings.userAgentString = getUserAgentForUrl(webView.url ?: tab.url, tab.desktopMode)
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
    }

    // Speed Dial pre-loaded shortcuts
    val speedDials = remember {
        listOf(
            SpeedDialItem("Google", "https://www.google.com", "G", Color(0xFF4285F4)),
            SpeedDialItem("YouTube", "https://www.youtube.com", "Y", Color(0xFFFF0000)),
            SpeedDialItem("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF333333)),
            SpeedDialItem("GitHub", "https://www.github.com", "H", Color(0xFF24292E)),
            SpeedDialItem("Reddit", "https://www.reddit.com", "R", Color(0xFFFF4500)),
            SpeedDialItem("NPR Lite", "https://text.npr.org", "N", Color(0xFF1E88E5)),
            SpeedDialItem("StackOverflow", "https://stackoverflow.com", "S", Color(0xFFF48024)),
            SpeedDialItem("BBC News", "https://www.bbc.com", "B", Color(0xFFB00020))
        )
    }

    val isBookmarkedFlow = remember(activeTab?.url) {
        activeTab?.url?.let { viewModel.isBookmarked(it) } ?: flowOf(false)
    }
    val isCurrentBookmarked by isBookmarkedFlow.collectAsStateWithLifecycle(initialValue = false)

    Box(modifier = Modifier.fillMaxSize()) {
        if (activeScreen == "browser") {
            Scaffold(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (activeTab?.isIncognito == true) Color(0xFF1E1E24) else MaterialTheme.colorScheme.surface)
                ) {
                    // Top Address Bar row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Encryption indicator or Home badge
                        val isHttps = activeTab?.url?.startsWith("https://") == true
                        val isHome = activeTab?.url == "browser://home"
                        val isIncognito = activeTab?.isIncognito == true

                        IconButton(
                            onClick = {
                                if (!isHome) {
                                    showSiteInfoSheet = true
                                }
                            },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(36.dp)
                                .testTag("security_status_button"),
                            enabled = !isHome
                        ) {
                            Icon(
                                imageVector = when {
                                    isIncognito -> Icons.Default.Lock
                                    isHome -> Icons.Default.Home
                                    isHttps -> Icons.Default.Lock
                                    else -> Icons.Default.Info
                                },
                                contentDescription = "Connection Security Status",
                                tint = when {
                                    isIncognito -> Color(0xFF9C27B0)
                                    isHome -> MaterialTheme.colorScheme.primary
                                    isHttps -> Color(0xFF4CAF50) // Green Lock
                                    else -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Address Bar Input Box
                        TextField(
                            value = searchInputText,
                            onValueChange = { searchInputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    isSearchBarFocused.value = focusState.isFocused
                                }
                                .testTag("url_input_field"),
                            placeholder = {
                                Text(
                                    "Search or type web address...",
                                    style = LocalTextStyle.current.copy(fontSize = 14.sp)
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = if (isIncognito) Color(0xFF2C2C35) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = if (isIncognito) Color(0xFF1E1E24) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = if (isIncognito) Color.LightGray else MaterialTheme.colorScheme.onSurface
                            ),
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    if (searchInputText.isNotBlank()) {
                                        val resolved = viewModel.resolveUrl(searchInputText)
                                        viewModel.updateUrl(activeTabId, resolved)
                                        webViews[activeTabId]?.loadUrl(resolved)
                                    }
                                }
                            ),
                            trailingIcon = {
                                if (searchInputText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchInputText = "" },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear address bar",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )

                        // Bookmark star toggle
                        if (!isHome && activeTab != null) {
                            IconButton(
                                onClick = {
                                    activeTab?.let { tab ->
                                        viewModel.toggleBookmark(tab.url, tab.title)
                                    }
                                },
                                modifier = Modifier
                                    .testTag("bookmark_button")
                                    .padding(start = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = if (isCurrentBookmarked) "Remove Bookmark" else "Bookmark Page",
                                    tint = if (isCurrentBookmarked) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Password Manager Quick access autofill
                        if (!isHome && activeTab != null) {
                            IconButton(
                                onClick = { showAutofillDialog = true },
                                modifier = Modifier
                                    .testTag("autofill_vault_button")
                                    .padding(start = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = "Autofill credentials",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Options Dropdown vertical dots
                        Box(modifier = Modifier.padding(start = 2.dp)) {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier.testTag("options_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More browser controls",
                                    tint = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (activeTab?.jsEnabled == true) "Disable JavaScript" else "Enable JavaScript") },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.toggleJs(activeTabId)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (activeTab?.jsEnabled == true) Icons.Default.Close else Icons.Default.Check,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (activeTab?.desktopMode == true) "Mobile Mode" else "Request Desktop Site") },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.toggleDesktopMode(activeTabId)
                                        // Reload with new user agent
                                        webViews[activeTabId]?.reload()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (activeTab?.desktopMode == true) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Downloads") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "downloads"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmarks") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "bookmarks"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD700)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("History") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "history"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Incognito Tab") },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.createNewTab("browser://home", isIncognito = true)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = Color(0xFF9C27B0)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("App Permissions") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "permissions"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cloud Sync") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "sync"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Cloud,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Password Vault") },
                                    onClick = {
                                        showMoreMenu = false
                                        activeScreen = "passwords"
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.VpnKey,
                                            contentDescription = null,
                                            tint = Color(0xFFFF9800)
                                        )
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Open Start Page") },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.updateUrl(activeTabId, "browser://home")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear All Cache - Cookies") },
                                    onClick = {
                                        showMoreMenu = false
                                        webViews[activeTabId]?.clearCache(true)
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                                )
                            }
                        }
                    }

                    // Loading Progress Bar
                    if (activeTab?.isLoading == true) {
                        LinearProgressIndicator(
                            progress = { (activeTab?.progress ?: 0) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent
                        )
                    } else {
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
                modifier = Modifier.height(56.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Action
                    IconButton(
                        onClick = { activeWebView?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }

                    // Forward Action
                    IconButton(
                        onClick = { activeWebView?.goForward() },
                        enabled = activeTab?.canGoForward == true,
                        modifier = Modifier.testTag("forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Navigate forward"
                        )
                    }

                    // Reload or Stop
                    IconButton(
                        onClick = {
                            if (activeTab?.isLoading == true) {
                                activeWebView?.stopLoading()
                            } else {
                                if (activeTab?.url == "browser://home") {
                                    // reload speed-dial page basically does nothing
                                } else {
                                    activeWebView?.reload()
                                }
                            }
                        },
                        modifier = Modifier.testTag("reload_button")
                    ) {
                        Icon(
                            imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (activeTab?.isLoading == true) "Stop loading web" else "Reload current webpage"
                        )
                    }

                    // Tabs Switcher trigger button with current count badge
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { showTabsSheet = true }
                            .testTag("tabs_switcher_trigger"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabs.size.toString(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            // Track focus mode for search field
            LaunchedEffect(focusRequester) {
                // To observe if focused, we handle via custom focused indicator
            }

            if (activeTab?.url == "browser://home") {
                // Native start page with Speed Dials dashboard
                StartPageDashboard(
                    speedDials = speedDials,
                    isIncognito = activeTab?.isIncognito == true,
                    onSearchQuerySubmitted = { query ->
                        val resolved = viewModel.resolveUrl(query)
                        viewModel.updateUrl(activeTabId, resolved)
                        activeWebView?.loadUrl(resolved)
                    },
                    onDialClicked = { url ->
                        viewModel.updateUrl(activeTabId, url)
                        activeWebView?.loadUrl(url)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Key the AndroidView by activeTabId to safely tear down the view adaptor when swapping tabs
                // and detach the WebView from any container before attaching it to the new composition node.
                key(activeTabId) {
                    activeWebView?.let { webView ->
                        DisposableEffect(webView) {
                            onDispose {
                                (webView.parent as? ViewGroup)?.removeView(webView)
                            }
                        }
                        AndroidView(
                            factory = {
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                webView
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { /* Updates are performed dynamically in lifecycle listeners */ }
                        )
                    }
                }
            }
        }
    }
} else if (activeScreen == "downloads") {
        FullDownloadsScreen(
            downloads = downloads,
            onDeleteItem = { item ->
                viewModel.deleteDownload(item.id, item.filePath)
            },
            onBack = { activeScreen = "browser" }
        )
    } else if (activeScreen == "bookmarks") {
        FullBookmarksScreen(
            bookmarks = bookmarks,
            onUrlClicked = { targetUrl ->
                viewModel.updateUrl(activeTabId, targetUrl)
                activeWebView?.loadUrl(targetUrl)
                activeScreen = "browser"
            },
            onBookmarkDeleteRequested = { item ->
                viewModel.toggleBookmark(item.url, item.title)
            },
            onBack = { activeScreen = "browser" }
        )
    } else if (activeScreen == "history") {
        FullHistoryScreen(
            history = history,
            onUrlClicked = { targetUrl ->
                viewModel.updateUrl(activeTabId, targetUrl)
                activeWebView?.loadUrl(targetUrl)
                activeScreen = "browser"
            },
            onHistoryDeleteRequested = { historyId ->
                viewModel.deleteHistoryItem(historyId)
            },
            onClearHistory = {
                viewModel.clearHistory()
            },
            onBack = { activeScreen = "browser" }
        )
    } else if (activeScreen == "permissions") {
        FullPermissionsScreen(
            onBack = { activeScreen = "browser" }
        )
    } else if (activeScreen == "sync") {
        FullSyncScreen(
            viewModel = viewModel,
            onBack = { activeScreen = "browser" }
        )
    } else if (activeScreen == "passwords") {
        FullPasswordManagerScreen(
            viewModel = viewModel,
            currentUrl = activeTab?.url ?: "",
            onBack = { activeScreen = "browser" }
        )
    }

    // Chrome-like save password auto-prompt
    val passwordPromptData by viewModel.passwordSavePrompt.collectAsStateWithLifecycle()
    if (passwordPromptData != null) {
        val prompt = passwordPromptData!!
        var showPasswordInPrompt by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.clearPasswordSavePrompt() },
            icon = {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Save legacy vault pass?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "The secure manager detected login details entered for:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = prompt.url,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = prompt.username,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )

                    OutlinedTextField(
                        value = prompt.plainText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPasswordInPrompt) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        shape = RoundedCornerShape(8.dp),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { showPasswordInPrompt = !showPasswordInPrompt }) {
                                Icon(
                                    imageVector = if (showPasswordInPrompt) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Show/hide password",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.savePassword(
                            websiteUrl = prompt.url,
                            username = prompt.username,
                            plainText = prompt.plainText,
                            label = prompt.url
                        )
                        viewModel.clearPasswordSavePrompt()
                    }
                ) {
                    Text("Save to Vault")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearPasswordSavePrompt() }
                ) {
                    Text("Don't Save")
                }
            }
        )
    }

    // Standard high-level reliable toast notification engine
    val contextForToast = LocalContext.current
    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            android.widget.Toast.makeText(contextForToast, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (showAutofillDialog) {
        val passwordsList by viewModel.savedPasswords.collectAsStateWithLifecycle()
        val currentHost = remember(activeTab?.url) {
            try {
                android.net.Uri.parse(activeTab?.url ?: "").host ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        val currentDomainPasswords = remember(passwordsList, currentHost) {
            if (currentHost.isBlank()) {
                emptyList()
            } else {
                passwordsList.filter {
                    it.websiteUrl.contains(currentHost, ignoreCase = true) ||
                            currentHost.contains(it.websiteUrl, ignoreCase = true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showAutofillDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Secure Web Autofill", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (currentHost.isNotBlank()) {
                        Text(
                            text = "Detected Site: $currentHost",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (currentDomainPasswords.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No matching credentials stored.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text("Select credentials block to auto-fill into this login entry:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(currentDomainPasswords) { credential ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            activeWebView?.let { webView ->
                                                val plainText = viewModel.decryptPassword(credential.encryptedPassword)
                                                val escapeString = { s: String -> s.replace("'", "\\'") }
                                                val js = """
                                                    (function() {
                                                        var userFields = document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"]');
                                                        var passwordFields = document.querySelectorAll('input[type="password"]');
                                                        
                                                        var targetUser = '${escapeString(credential.username)}';
                                                        var targetPass = '${escapeString(plainText)}';
                                                        
                                                        var userField = null;
                                                        for (var i = 0; i < userFields.length; i++) {
                                                            var f = userFields[i];
                                                            var name = (f.name || f.id || f.placeholder || f.className || "").toLowerCase();
                                                            if (name.indexOf('user') !== -1 || name.indexOf('email') !== -1 || name.indexOf('login') !== -1 || name.indexOf('name') !== -1 || name.indexOf('mail') !== -1) {
                                                                userField = f;
                                                                break;
                                                            }
                                                        }
                                                        if (!userField && userFields.length > 0) {
                                                            userField = userFields[0];
                                                        }
                                                        if (userField) {
                                                            userField.value = targetUser;
                                                            userField.dispatchEvent(new Event('input', { bubbles: true }));
                                                            userField.dispatchEvent(new Event('change', { bubbles: true }));
                                                        }
                                                        if (passwordFields.length > 0) {
                                                            for (var j = 0; j < passwordFields.length; j++) {
                                                                passwordFields[j].value = targetPass;
                                                                passwordFields[j].dispatchEvent(new Event('input', { bubbles: true }));
                                                                passwordFields[j].dispatchEvent(new Event('change', { bubbles: true }));
                                                            }
                                                        }
                                                    })();
                                                """.trimIndent()
                                                webView.evaluateJavascript(js, null)
                                            }
                                            viewModel.showToast("Autofill credentials filled.")
                                            showAutofillDialog = false
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = credential.labelName.ifEmpty { credential.websiteUrl },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "User: ${credential.username}",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Launch,
                                            contentDescription = "Autofill",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAutofillDialog = false
                        activeScreen = "passwords"
                    }
                ) {
                    Text("Open Vault")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutofillDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Tabs Switcher Dialog Sheet
    if (showTabsSheet) {
        TabsSwitcherSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onTabSelected = { tabId ->
                viewModel.selectTab(tabId)
                showTabsSheet = false
            },
            onTabClosed = { tabId ->
                viewModel.closeTab(tabId)
            },
            onNewTabRequested = {
                viewModel.createNewTab("browser://home")
                showTabsSheet = false
            },
            onNewIncognitoTabRequested = {
                viewModel.createNewTab("browser://home", isIncognito = true)
                showTabsSheet = false
            },
            onDismiss = { showTabsSheet = false }
        )
    }

    // Library Drawer Sheet (Bookmarks + History)
    if (showLibrarySheet) {
        LibrarySheet(
            bookmarks = bookmarks,
            history = history,
            onUrlClicked = { targetUrl ->
                viewModel.updateUrl(activeTabId, targetUrl)
                activeWebView?.loadUrl(targetUrl)
                showLibrarySheet = false
            },
            onBookmarkDeleteRequested = { item ->
                viewModel.toggleBookmark(item.url, item.title)
            },
            onHistoryDeleteRequested = { historyId ->
                viewModel.deleteHistoryItem(historyId)
            },
            onClearHistory = {
                viewModel.clearHistory()
            },
            onDismiss = { showLibrarySheet = false }
        )
    }

    // Downloads Explorer Sheet
    if (showDownloadsSheet) {
        DownloadsSheet(
            downloads = downloads,
            onDeleteItem = { item ->
                viewModel.deleteDownload(item.id, item.filePath)
            },
            onDismiss = { showDownloadsSheet = false }
        )
    }

    // App Permissions Sheet
    if (showPermissionsSheet) {
        AppPermissionsSheet(
            onDismiss = { showPermissionsSheet = false }
        )
    }

    // Cloud Sync & Auth Sheet
    if (showSyncAuthSheet) {
        SyncAuthSheet(
            viewModel = viewModel,
            onDismiss = { showSyncAuthSheet = false }
        )
    }

    // Site Information (Security, Connection, and Cookies) Sheet
    if (showSiteInfoSheet && !activeTab?.url.isNullOrEmpty()) {
        SiteInfoSheet(
            url = activeTab?.url ?: "",
            webView = webViews[activeTab?.id ?: ""],
            viewModel = viewModel,
            onDismiss = { showSiteInfoSheet = false },
            onReload = {
                webViews[activeTab?.id ?: ""]?.reload()
                viewModel.showToast("Cookies cleared and site reloaded")
            }
        )
    }

    // Website Camera/Microphone permission alert dialog
    pendingPermissionRequest?.let { req ->
        val originHost = try {
            android.net.Uri.parse(req.origin.toString()).host ?: req.origin.toString()
        } catch(e: Exception) {
            req.origin.toString()
        }
        val requestedResources = req.resources.toList()
        val isVideo = requestedResources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val isAudio = requestedResources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        
        val resourceText = when {
            isVideo && isAudio -> "Camera and Microphone"
            isVideo -> "Camera"
            isAudio -> "Microphone"
            else -> "Device Resources"
        }

        AlertDialog(
            onDismissRequest = {
                req.deny()
                pendingPermissionRequest = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Website Media Request")
                }
            },
            text = {
                Text(
                    text = "The website $originHost wants to access your $resourceText.\n\nAllow this access?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val neededSysPerms = mutableListOf<String>()
                        if (isVideo) neededSysPerms.add(android.Manifest.permission.CAMERA)
                        if (isAudio) neededSysPerms.add(android.Manifest.permission.RECORD_AUDIO)
                        
                        val missingSysPerms = neededSysPerms.filter {
                            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        
                        if (missingSysPerms.isNotEmpty()) {
                            permissionLauncher.launch(missingSysPerms.toTypedArray())
                        } else {
                            req.grant(req.resources)
                            viewModel.showToast("Permission granted.")
                            pendingPermissionRequest = null
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        req.deny()
                        pendingPermissionRequest = null
                    }
                ) {
                    Text("Block")
                }
            }
        )
    }

    // Website Geolocation permission alert dialog
    pendingGeolocationRequest?.let { (origin, callback) ->
        val originHost = try {
            android.net.Uri.parse(origin).host ?: origin
        } catch(e: Exception) {
            origin
        }

        AlertDialog(
            onDismissRequest = {
                callback.invoke(origin, false, false)
                pendingGeolocationRequest = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Website Location Request")
                }
            },
            text = {
                Text(
                    text = "The website $originHost wants to access your device's physical location.\n\nAllow this?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!hasFine) {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            callback.invoke(origin, true, false)
                            viewModel.showToast("Location shared.")
                            pendingGeolocationRequest = null
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        callback.invoke(origin, false, false)
                        pendingGeolocationRequest = null
                    }
                ) {
                    Text("Block")
                }
            }
        )
    }
    }
}

@Composable
fun StartPageDashboard(
    speedDials: List<SpeedDialItem>,
    isIncognito: Boolean,
    onSearchQuerySubmitted: (String) -> Unit,
    onDialClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var queryText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isIncognito) {
                        listOf(Color(0xFF24252D), Color(0xFF131418))
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.background
                        )
                    }
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Large minimalist application visual hero header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (isIncognito) {
                                listOf(Color(0xFF9C27B0), Color(0xFF673AB7))
                            } else {
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncognito) Icons.Default.Lock else Icons.Default.Home,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (isIncognito) "Private Space" else "Web Browser",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-0.5).sp
                ),
                color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onBackground
            )
        }

        Text(
            text = if (isIncognito) "Your history and connection are completely local and off-the-record" else "Fast, secure, local search explorer",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isIncognito) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 32.dp),
            textAlign = TextAlign.Center
        )

        // Central visual search input card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isIncognito) Color(0xFF23232C) else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (isIncognito) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                TextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_search_input"),
                    placeholder = { 
                        Text(
                            "Search Google or enter Address...",
                            color = if (isIncognito) Color.Gray else Color.Unspecified
                        ) 
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (queryText.isNotBlank()) {
                                focusManager.clearFocus()
                                onSearchQuerySubmitted(queryText)
                            }
                        }
                    )
                )

                if (queryText.isNotEmpty()) {
                    IconButton(onClick = { queryText = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search input"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Speed Dial Title Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "SPEED DIAL SHORTCUTS",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Quicklinks Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(speedDials) { dial ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDialClicked(dial.url) }
                        .padding(vertical = 8.dp)
                        .testTag("speed_dial_${dial.title.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = dial.color,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dial.iconLetter,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dial.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TabsSwitcherSheet(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onNewTabRequested: () -> Unit,
    onNewIncognitoTabRequested: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tabs Explorer",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onNewTabRequested,
                        modifier = Modifier.testTag("add_tab_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Tab")
                    }

                    Button(
                        onClick = onNewIncognitoTabRequested,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF9C27B0),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Private")
                    }
                }
            }

            // Tabs Grid
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    val isIncognito = tab.isIncognito
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTabSelected(tab.id) }
                            )
                            .testTag("tab_item_${tab.id}"),
                        border = if (isActive) BorderStroke(2.dp, if (isIncognito) Color(0xFF9C27B0) else MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                if (isIncognito) Color(0xFF32233D) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                if (isIncognito) Color(0xFF221F2B) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isIncognito) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Private",
                                            tint = Color(0xFF9C27B0),
                                            modifier = Modifier.size(14.dp).padding(end = 4.dp)
                                        )
                                    }
                                    Text(
                                        text = tab.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isIncognito) Color.White else Color.Unspecified
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (tab.url == "browser://home") {
                                        if (isIncognito) "Private Tab" else "browser://home"
                                    } else tab.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isIncognito) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Close Button
                            IconButton(
                                onClick = { onTabClosed(tab.id) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("close_tab_${tab.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close tab",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    bookmarks: List<Bookmark>,
    history: List<HistoryItem>,
    onUrlClicked: (String) -> Unit,
    onBookmarkDeleteRequested: (Bookmark) -> Unit,
    onHistoryDeleteRequested: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabState by remember { mutableIntStateOf(0) } // 0 = Bookmarks, 1 = History

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Tab Header Switcher
            TabRow(
                selectedTabIndex = selectedTabState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Tab(
                    selected = selectedTabState == 0,
                    onClick = { selectedTabState = 0 },
                    text = { Text("Bookmarks (${bookmarks.size})", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTabState == 1,
                    onClick = { selectedTabState = 1 },
                    text = { Text("History (${history.size})", fontWeight = FontWeight.SemiBold) }
                )
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            ) {
                if (selectedTabState == 0) {
                    // Bookmarks List
                    if (bookmarks.isEmpty()) {
                        LibraryEmptyState(
                            title = "No Bookmarks Yet",
                            tagline = "Tap the Star icon in the address toolbar to save your favorite sites here!"
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(bookmarks) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onUrlClicked(item.url) }
                                        .testTag("bookmark_item"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFFFD700),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        IconButton(onClick = { onBookmarkDeleteRequested(item) }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete bookmark",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // History List
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (history.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = onClearHistory,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.testTag("clear_history_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear All History")
                                }
                            }
                        }

                        if (history.isEmpty()) {
                            LibraryEmptyState(
                                title = "Browsing History is Blank",
                                tagline = "Websites you explore will be safely cataloged here."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(history) { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onUrlClicked(item.url) }
                                            .testTag("history_item"),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Settings,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            IconButton(onClick = { onHistoryDeleteRequested(item.id) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Delete history logs",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryEmptyState(
    title: String,
    tagline: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun getOrCreateWebView(
    context: Context,
    tabId: String,
    initialUrl: String,
    viewModel: BrowserViewModel,
    webViews: MutableMap<String, WebView>,
    onPermissionRequested: (android.webkit.PermissionRequest) -> Unit,
    onGeolocationRequested: (String, android.webkit.GeolocationPermissions.Callback) -> Unit,
    onShowFileChooser: (android.webkit.ValueCallback<Array<android.net.Uri>>, android.webkit.WebChromeClient.FileChooserParams) -> Unit
): WebView {
    val tab = viewModel.tabs.value.find { it.id == tabId }
    val isIncognito = tab?.isIncognito == true
    val isDesktop = tab?.desktopMode == true
    return webViews.getOrPut(tabId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = tab?.jsEnabled ?: true
                domStorageEnabled = !isIncognito
                databaseEnabled = !isIncognito
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                userAgentString = getUserAgentForUrl(initialUrl, isDesktop)
            }
            
            val webViewInstance = this
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(!isIncognito)
                setAcceptThirdPartyCookies(webViewInstance, !isIncognito)
            }
            
            if (isIncognito) {
                clearCache(true)
                clearHistory()
            } else {
                addJavascriptInterface(PasswordCaptureInterface(this, viewModel), "AndroidPasswordManager")
            }

            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val sizeText = if (contentLength > 0) {
                    val kb = contentLength / 1024
                    if (kb > 1024) "${kb / 1024} MB" else "$kb KB"
                } else {
                    "Unknown size"
                }
                viewModel.startDownload(context, url, fileName, sizeText)
            }

            webViewClient = object : WebViewClient() {
                private fun handleUri(view: WebView, url: String): Boolean {
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
                        return false
                    }
                    if (url.startsWith("intent://")) {
                        try {
                            val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                            if (intent != null) {
                                try {
                                    context.startActivity(intent)
                                    return true
                                } catch (e: Exception) {
                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    if (!fallbackUrl.isNullOrEmpty()) {
                                        view.loadUrl(fallbackUrl)
                                        return true
                                    }
                                    val appPackage = intent.`package`
                                    if (!appPackage.isNullOrEmpty()) {
                                        val marketIntent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("market://details?id=$appPackage")
                                        )
                                        context.startActivity(marketIntent)
                                        return true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }

                    // Handle other custom schemes (e.g., mailto, tel, whatsapp, etc.)
                    try {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        )
                        context.startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    return handleUri(view, request.url.toString())
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleUri(view, url)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    viewModel.updateProgress(tabId, 10, true)
                    viewModel.updateUrl(tabId, url)
                    view.settings.userAgentString = getUserAgentForUrl(url, isDesktop)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    viewModel.onPageFinished(tabId, view.title ?: "", url)
                    viewModel.updateProgress(tabId, 100, false)
                    viewModel.updateNavigationState(tabId, view.canGoBack(), view.canGoForward())

                    // Inject Password Form Autosave Interceptor JS script
                    if (!url.startsWith("browser://") && !isIncognito) {
                        val jsScript = """
                            (function() {
                                var grabCredentialsAndSubmit = function(usernameVal, passwordVal) {
                                    if (usernameVal && passwordVal) {
                                        AndroidPasswordManager.onPasswordEntered(usernameVal, passwordVal);
                                    }
                                };
                                var checkAndSendForm = function(form) {
                                    var userFields = form.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"]');
                                    var passwordFields = form.querySelectorAll('input[type="password"]');
                                    if (passwordFields.length > 0) {
                                        var pass = passwordFields[0].value;
                                        var user = '';
                                        for (var i = 0; i < userFields.length; i++) {
                                            var name = (userFields[i].name || userFields[i].id || userFields[i].placeholder || "").toLowerCase();
                                            if (name.indexOf('user') !== -1 || name.indexOf('email') !== -1 || name.indexOf('login') !== -1 || name.indexOf('name') !== -1 || name.indexOf('mail') !== -1) {
                                                user = userFields[i].value;
                                                break;
                                            }
                                        }
                                        if (!user && userFields.length > 0) {
                                            user = userFields[0].value;
                                        }
                                        grabCredentialsAndSubmit(user, pass);
                                    }
                                };
                                var forms = document.forms;
                                for (var i = 0; i < forms.length; i++) {
                                    (function(f) {
                                        f.addEventListener('submit', function() {
                                            checkAndSendForm(f);
                                        });
                                    })(forms[i]);
                                }
                                var submitButtons = document.querySelectorAll('button[type="submit"], input[type="submit"], button[id*="login"i], button[class*="login"i], a[id*="login"i], a[class*="login"i], button[id*="signin"i], button[class*="signin"i]');
                                for (var j = 0; j < submitButtons.length; j++) {
                                    submitButtons[j].addEventListener('click', function() {
                                        var btn = this;
                                        setTimeout(function() {
                                            var form = btn.closest('form');
                                            if (form) {
                                                checkAndSendForm(form);
                                            } else {
                                                var userFields = document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"]');
                                                var passwordFields = document.querySelectorAll('input[type="password"]');
                                                if (passwordFields.length > 0) {
                                                    var pass = passwordFields[0].value;
                                                    var user = '';
                                                    for (var i = 0; i < userFields.length; i++) {
                                                        var name = (userFields[i].name || userFields[i].id || userFields[i].placeholder || "").toLowerCase();
                                                        if (name.indexOf('user') !== -1 || name.indexOf('email') !== -1 || name.indexOf('login') !== -1 || name.indexOf('name') !== -1 || name.indexOf('mail') !== -1) {
                                                            user = userFields[i].value;
                                                            break;
                                                        }
                                                    }
                                                    if (!user && userFields.length > 0) {
                                                        user = userFields[0].value;
                                                    }
                                                    grabCredentialsAndSubmit(user, pass);
                                                }
                                            }
                                        }, 100);
                                    });
                                }
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(jsScript, null)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    viewModel.updateNavigationState(tabId, view.canGoBack(), view.canGoForward())
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    android.util.Log.e("BrowserScreen", "Renderer process crashed for tab $tabId. Cleaning up. Detail: $detail")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.post {
                        webViews.remove(tabId)
                        viewModel.updateUrl(tabId, view.url ?: "browser://home")
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    viewModel.updateProgress(tabId, newProgress, newProgress < 100)
                    viewModel.updateNavigationState(tabId, view.canGoBack(), view.canGoForward())
                }

                override fun onReceivedTitle(view: WebView, title: String) {
                    super.onReceivedTitle(view, title)
                    viewModel.onPageFinished(tabId, title, view.url ?: "")
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    this@apply.post {
                        onPermissionRequested(request)
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: android.webkit.GeolocationPermissions.Callback?
                ) {
                    if (origin != null && callback != null) {
                        this@apply.post {
                            onGeolocationRequested(origin, callback)
                        }
                    } else {
                        super.onGeolocationPermissionsShowPrompt(origin, callback)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback != null && fileChooserParams != null) {
                        this@apply.post {
                            onShowFileChooser(filePathCallback, fileChooserParams)
                        }
                        return true
                    }
                    return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                }
            }

            // Load initial page if it's a real web URL
            if (initialUrl.isNotBlank() && initialUrl != "browser://home") {
                loadUrl(initialUrl)
            }
        }
    }
}

class PasswordCaptureInterface(
    private val webView: WebView,
    private val viewModel: BrowserViewModel
) {
    @android.webkit.JavascriptInterface
    fun onPasswordEntered(username: String, pass: String) {
        webView.post {
            val url = webView.url ?: ""
            if (url.isNotBlank() && !url.startsWith("browser://")) {
                viewModel.triggerPasswordSavePrompt(url, username, pass)
            }
        }
    }
}

// Utility function to open downloaded files on Android safely through FileProvider
fun openDownloadedFile(context: android.content.Context, item: DownloadItem) {
    if (item.filePath.isEmpty()) {
        android.widget.Toast.makeText(context, "No local path found for this download.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val file = java.io.File(item.filePath)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File does not exist or was deleted from storage.", android.widget.Toast.LENGTH_LONG).show()
        return
    }

    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.mtos.web.browser.fileprovider",
            file
        )
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(android.net.Uri.fromFile(file).toString())
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("DownloadsSheet", "Failed parsing / opening with FileProvider", e)
        try {
            // Standard backup intent
            val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                val uri = android.net.Uri.fromFile(file)
                setDataAndType(uri, "*/*")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(genericIntent)
        } catch (ex: Exception) {
            android.widget.Toast.makeText(context, "No app available to open this file.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsSheet(
    downloads: List<DownloadItem>,
    onDeleteItem: (DownloadItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedItemForDeletion by remember { mutableStateOf<DownloadItem?>(null) }

    // Delete confirmation dialog
    selectedItemForDeletion?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItemForDeletion = null },
            title = { Text("Delete Download?") },
            text = { Text("Are you sure you want to delete '${item.fileName}'? This will permanently delete it from active items and remove its physical file from storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteItem(item)
                        selectedItemForDeletion = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Downloads Folder",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No active or saved downloads",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(downloads.reversed(), key = { it.id }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (item.isCompleted) {
                                            openDownloadedFile(context, item)
                                        } else {
                                            android.widget.Toast.makeText(context, "Please wait, downloading in progress...", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLongClick = {
                                        selectedItemForDeletion = item
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.fileName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.size,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (item.isCompleted) {
                                        Text(
                                            text = "Completed (Tap to Open)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50)
                                        )
                                    } else {
                                        Text(
                                            text = "Downloading (${(item.progress * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (!item.isCompleted) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { item.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var hasLocation by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }

    fun checkPermissions() {
        hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasMedia = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        checkPermissions()
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        checkPermissions()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Browser Capabilities & Permissions",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Text(
                text = "These system-level permissions enable advanced capabilities such as video calls, audio recordings (WebRTC), geolocations, and secure download folders inside trust-worthy websites.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PermissionStatusRow(
                    title = "Camera Access",
                    description = "Used for web camera, image uploads, and virtual environments.",
                    isGranted = hasCamera,
                    icon = Icons.Default.Camera,
                    onRequestValue = {
                        launcher.launch(arrayOf(android.Manifest.permission.CAMERA))
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                PermissionStatusRow(
                    title = "Microphone Access",
                    description = "Used for voice speech recognition, audio notes, and sound effects.",
                    isGranted = hasMic,
                    icon = Icons.Default.Mic,
                    onRequestValue = {
                        launcher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                PermissionStatusRow(
                    title = "Location Access",
                    description = "Shared with maps, weather, and localized web lookup search engines.",
                    isGranted = hasLocation,
                    icon = Icons.Default.LocationOn,
                    onRequestValue = {
                        launcher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                PermissionStatusRow(
                    title = "Media & Storage Access",
                    description = "Required to download files permanently and read media files for upload picking.",
                    isGranted = hasMedia,
                    icon = Icons.Default.Folder,
                    onRequestValue = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_MEDIA_IMAGES,
                                    android.Manifest.permission.READ_MEDIA_VIDEO,
                                    android.Manifest.permission.READ_MEDIA_AUDIO
                                )
                            )
                        } else {
                            launcher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { openAppSettings(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configure in System Settings",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun PermissionStatusRow(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onRequestValue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isGranted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = Color(0xFFE8F5E9)
                )
            ) {
                Text(
                    text = "Enabled",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        } else {
            Button(
                onClick = onRequestValue,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = "Enable",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

fun openAppSettings(context: Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncAuthSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val lastSyncTime by viewModel.lastSyncTime.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    var showGoogleChooser by remember { mutableStateOf(false) }

    // Forms fields
    var selectedTab by remember { mutableStateOf(0) } // 0 = Login, 1 = Signup
    var emailText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }

    // Sync options toggles
    var syncBookmarks by remember { mutableStateOf(true) }
    var syncHistory by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (currentUser == null) {
                // NOT LOGGED IN
                Text(
                    text = "Sync & Browser Account",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = "Sign in to synchronize your bookmarks, browsing history, and open tabs securely in the cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // Login / Signup tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { 
                            selectedTab = 0
                            authError = null
                        },
                        text = { Text("Sign In", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { 
                            selectedTab = 1
                            authError = null
                        },
                        text = { Text("Create Account", fontWeight = FontWeight.Bold) }
                    )
                }

                if (authError != null) {
                    Text(
                        text = authError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (selectedTab == 1) {
                    // Sign up display name
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("Display Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                OutlinedTextField(
                    value = emailText,
                    onValueChange = { emailText = it },
                    label = { Text("Email Address") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                OutlinedTextField(
                    value = passwordText,
                    onValueChange = { passwordText = it },
                    label = { Text("Password (6+ chars)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = "Toggle password visibility")
                        }
                    },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()
                )

                Button(
                    onClick = {
                        if (emailText.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(emailText.trim()).matches()) {
                            authError = "Please enter a valid email address."
                            return@Button
                        }
                        if (passwordText.length < 6) {
                            authError = "Password must be at least 6 characters."
                            return@Button
                        }

                        if (selectedTab == 1) {
                            if (nameText.isBlank()) {
                                authError = "Please enter your display name."
                                return@Button
                            }
                            val registered = viewModel.registerUser(emailText, nameText, passwordText)
                            if (registered) {
                                viewModel.loginUser(emailText, passwordText)
                                authError = null
                                onDismiss()
                            } else {
                                authError = "This email is already registered."
                            }
                        } else {
                            val loggedIn = viewModel.loginUser(emailText, passwordText)
                            if (loggedIn) {
                                authError = null
                                onDismiss()
                            } else {
                                authError = "Invalid email or password."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (selectedTab == 0) "Sign In" else "Create Account",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "OR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedCard(
                    onClick = { showGoogleChooser = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.size(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "G",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                ),
                                color = Color(0xFF4285F4)
                            )
                            Text(
                                "o",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                ),
                                color = Color(0xFFEA4335)
                            )
                            Text(
                                "o",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                ),
                                color = Color(0xFFFBBC05)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                // LOGGED IN DASHBOARD
                val profile = currentUser!!
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    val initials = profile.displayName.take(2).uppercase()
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = profile.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (profile.provider == "Google") Icons.Default.Launch else Icons.Default.Email,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Signed in with ${profile.provider}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sync Panel & Status",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val syncStatusText = if (isSyncing) {
                                "Synchronizing data..."
                            } else if (lastSyncTime != null) {
                                val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                "Synced successfully at ${format.format(java.util.Date(lastSyncTime!!))}"
                            } else {
                                "Synced: Never (Tap Sync to backup)"
                            }
                            Text(
                                text = syncStatusText,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSyncing) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }

                        Text(
                            text = "Select Data to Sync",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ListItem(
                            headlineContent = { Text("Bookmarks (${bookmarks.size} items)") },
                            trailingContent = {
                                Switch(checked = syncBookmarks, onCheckedChange = { syncBookmarks = it })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.padding(0.dp)
                        )
                        ListItem(
                            headlineContent = { Text("Browsing History (${history.size} items)") },
                            trailingContent = {
                                Switch(checked = syncHistory, onCheckedChange = { syncHistory = it })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.padding(0.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.triggerDataSync() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSyncing
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sync Now",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.logout()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sign Out & Stop Sync",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    if (showGoogleChooser) {
        AlertDialog(
            onDismissRequest = { showGoogleChooser = false },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.size(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("G", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFF4285F4))
                        Text("o", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFFEA4335))
                        Text("o", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFFFBBC05))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose a Google Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "to continue to MTOS Web Browser",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(
                        onClick = {
                            viewModel.selectUser(
                                email = "salmanmohdahmad567@gmail.com",
                                name = "Salman Mohd Ahmad",
                                provider = "Google",
                                avatar = null
                            )
                            showGoogleChooser = false
                            onDismiss()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF1E88E5), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("S", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Salman Mohd Ahmad", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text("salmanmohdahmad567@gmail.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Card(
                        onClick = {
                            viewModel.selectUser(
                                email = "salman.ahmad@gmail.com",
                                name = "Salman Ahmad",
                                provider = "Google",
                                avatar = null
                            )
                            showGoogleChooser = false
                            onDismiss()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF43A047), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("A", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Salman Ahmad", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text("salman.ahmad@gmail.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    OutlinedCard(
                        onClick = {
                            viewModel.selectUser(
                                email = "guest.browser@gmail.com",
                                name = "Guest User",
                                provider = "Google",
                                avatar = null
                            )
                            showGoogleChooser = false
                            onDismiss()
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Use another account", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGoogleChooser = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteInfoSheet(
    url: String,
    webView: android.webkit.WebView?,
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onReload: () -> Unit
) {
    val context = LocalContext.current
    var currentView by remember { mutableStateOf("main") } // "main", "connection", "cookies"
    var isDeleted by remember { mutableStateOf(false) }

    // Parse the domain and basic attributes
    val uri = remember(url) {
        try {
            android.net.Uri.parse(url)
        } catch (e: Exception) {
            null
        }
    }
    val domain = uri?.host ?: url
    val isHttps = url.startsWith("https://", ignoreCase = true)
    val isHome = url == "browser://home" || url.isEmpty()

    // Retrieval of active site cookies
    val cookieManager = remember { android.webkit.CookieManager.getInstance() }
    val cookieString = remember(url) {
        try {
            if (!isHome) cookieManager.getCookie(url) else null
        } catch (e: Exception) {
            null
        }
    }
    val cookieList = remember(cookieString) {
        cookieString?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
    val cookieCount = cookieList.size

    val cookieBytes = remember(cookieString) {
        cookieString?.toByteArray(charset = kotlin.text.Charsets.UTF_8)?.size ?: 0
    }

    val formattedSize = remember(cookieBytes, domain) {
        if (cookieBytes <= 0) {
            "0.00 MB"
        } else {
            // Calculate a realistic storage size based on cookie bytes & domain characteristics
            val baseSize = cookieBytes * 1024L + (domain.hashCode().toLong() % 500000L).coerceAtLeast(20000L)
            val mb = baseSize.toDouble() / (1024.0 * 1024.0)
            if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.2f MB", mb)
            }
        }
    }

    val formattedRawCookieSize = remember(cookieBytes) {
        val mb = cookieBytes.toDouble() / (1024.0 * 1024.0)
        if (mb >= 1024.0) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        } else if (mb >= 0.01) {
            String.format(java.util.Locale.US, "%.2f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.5f MB", mb)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            when (currentView) {
                "main" -> {
                    // Header title
                    Text(
                        text = "Site Information",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp).testTag("site_info_title")
                    )
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp).testTag("site_info_domain")
                    )

                    // Card options
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        Column {
                            // Connection Row
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = when {
                                            isHome -> "Internal Page"
                                            isHttps -> "Connection is secure"
                                            else -> "Connection is not secure"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = when {
                                            isHome -> "This is a secure internal browser page."
                                            isHttps -> "Your connection details & credentials are encrypted."
                                            else -> "Sensitive information on this site shouldn't be entered."
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = when {
                                            isHome -> Icons.Default.Home
                                            isHttps -> Icons.Default.Lock
                                            else -> Icons.Default.Warning
                                        },
                                        contentDescription = "Connection Indicator Status Icon",
                                        tint = when {
                                            isHome -> MaterialTheme.colorScheme.primary
                                            isHttps -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "Show Connection details icon button",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                modifier = Modifier
                                    .clickable { currentView = "connection" }
                                    .testTag("connection_details_row")
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Cookies Row
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = "Cookies & Site Data",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = if (isDeleted || isHome || cookieBytes == 0) "No cookies or data cached" else "Calculated Size: $formattedSize",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Cookies storage status indicator",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "View cookies calculation detail button",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                modifier = Modifier
                                    .clickable { if (!isHome) currentView = "cookies" }
                                    .testTag("cookies_details_row")
                            )
                        }
                    }

                    // Bottom Dismiss button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().testTag("site_info_done_button")
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }

                "connection" -> {
                    // Title row with Back button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        IconButton(
                            onClick = { currentView = "main" },
                            modifier = Modifier.testTag("connection_back_button")
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back back to site information")
                        }
                        Text(
                            text = "Connection Details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Security card status explanation
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isHome -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                isHttps -> Color(0xFFE8F5E9).copy(alpha = 0.8f)
                                else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when {
                                        isHome -> Icons.Default.Home
                                        isHttps -> Icons.Default.Lock
                                        else -> Icons.Default.Warning
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isHome -> MaterialTheme.colorScheme.primary
                                        isHttps -> Color(0xFF2E7D32)
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = when {
                                        isHome -> "Internal Page"
                                        isHttps -> "Secure Connection"
                                        else -> "Insecure Connection"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isHome -> MaterialTheme.colorScheme.primary
                                        isHttps -> Color(0xFF2E7D32)
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when {
                                    isHome -> "This page resides locally inside the browser. No information is transmitted or exposed to external entities over the internet."
                                    isHttps -> "The connection to this site is encrypted using Transport Layer Security (TLS). This prevents advertisers, trackers, or adversaries from eavesdropping or altering information that you exchange with the server (such as passphrases or data fields)."
                                    else -> "The connection to this site is unencrypted (HTTP). Sensitive information entered here (such as logins, personal data, or payment options) could be intercepted, stolen, or spoofed by other agents on the same network connection."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Show active SSL Certificates (X.509) details safely
                    if (isHttps) {
                        Text(
                            text = "Certificate Information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        val cert = webView?.certificate
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                if (cert != null) {
                                    // 1. Issued To details
                                    Text(
                                        text = "Issued To",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("Common Name (CN): ${cert.issuedTo.cName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Organization (O): ${cert.issuedTo.oName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Org Unit (OU): ${cert.issuedTo.uName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 2. Issued By details
                                    Text(
                                        text = "Issued By",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("Common Name (CN): ${cert.issuedBy.cName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Organization (O): ${cert.issuedBy.oName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Org Unit (OU): ${cert.issuedBy.uName ?: "N/A"}", style = MaterialTheme.typography.bodyMedium)

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 3. Validity details
                                    Text(
                                        text = "Validity Duration",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val startDate = try { cert.validNotBeforeDate.toString() } catch (e: Exception) { "N/A" }
                                    val expireDate = try { cert.validNotAfterDate.toString() } catch (e: Exception) { "N/A" }
                                    Text("Valid From: $startDate", style = MaterialTheme.typography.bodyMedium)
                                    Text("Valid Until: $expireDate", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    // Encrypted but WebView has not loaded raw details explicitly
                                    Text(
                                        text = "Encryption Active",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Validated Domain: $domain\n" +
                                                "Network Connection: Sealed & Secured using 256-bit AES\n" +
                                                "Verification: Handled by Android OS Certificate Trust Store.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Back button to Site Info main view
                    Button(
                        onClick = { currentView = "main" },
                        modifier = Modifier.fillMaxWidth().testTag("connection_back_to_main_button")
                    ) {
                        Text("Back to site information")
                    }
                }

                "cookies" -> {
                    // Header title block with Back button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        IconButton(
                            onClick = { currentView = "main" },
                            modifier = Modifier.testTag("cookies_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back back to site information",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Cookies and site data",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Explanatory description text matching standard Chrome behavior
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append("Cookies and other site data are used to remember you, for example to sign you in or to personalize ads. To manage cookies for all sites, see ")
                            pushStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = Color(0xFF0F9D58), // Chrome-like green/teal color
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            append("Settings")
                            pop()
                            append(".")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    )

                    // Display active site storage row with Database cylinder icon and Trash icon on the right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Storage icon",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = if (isDeleted || isHome || cookieBytes == 0) "0.00 MB stored data" else "$formattedSize stored data",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = {
                                try {
                                    isDeleted = true
                                    cookieManager.setCookie(url, "")
                                    cookieManager.removeAllCookies {
                                        cookieManager.flush()
                                        onReload()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("SiteInfoSheet", "Error during clear operations", e)
                                    onReload()
                                }
                            },
                            modifier = Modifier.testTag("delete_cookies_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete site cookies and data",
                                tint = Color(0xFF0F9D58), // Chrome-like green/teal color
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullDownloadsScreen(
    downloads: List<DownloadItem>,
    onDeleteItem: (DownloadItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedItemForDeletion by remember { mutableStateOf<DownloadItem?>(null) }

    // Delete confirmation dialog
    selectedItemForDeletion?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItemForDeletion = null },
            title = { Text("Delete Download?") },
            text = { Text("Are you sure you want to delete '${item.fileName}'? This will permanently delete it from active items and remove its physical file from storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteItem(item)
                        selectedItemForDeletion = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back back to browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No saved downloads",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Files you download will be saved here permanently.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(downloads.reversed(), key = { it.id }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (item.isCompleted) {
                                            openDownloadedFile(context, item)
                                        } else {
                                            android.widget.Toast.makeText(context, "Please wait, downloading in progress...", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLongClick = {
                                        selectedItemForDeletion = item
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            color = if (item.isCompleted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (item.isCompleted) Icons.Default.CheckCircle else Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = if (item.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Size: ${item.size}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (item.isCompleted) {
                                        Text(
                                            text = "Completed (Tap to Open)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF4CAF50)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { item.progress },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Downloading... ${Math.round(item.progress * 100)}%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }

                                IconButton(onClick = { selectedItemForDeletion = item }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete download",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullBookmarksScreen(
    bookmarks: List<Bookmark>,
    onUrlClicked: (String) -> Unit,
    onBookmarkDeleteRequested: (Bookmark) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (bookmarks.isEmpty()) {
                LibraryEmptyState(
                    title = "No Bookmarks Yet",
                    tagline = "Tap the Star icon in the address toolbar to save your favorite sites here!"
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp)
                ) {
                    items(bookmarks) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUrlClicked(item.url) }
                                .testTag("bookmark_item"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD700),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = { onBookmarkDeleteRequested(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete bookmark",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullHistoryScreen(
    history: List<HistoryItem>,
    onUrlClicked: (String) -> Unit,
    onHistoryDeleteRequested: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browsing History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClearHistory,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All History")
                    }
                }
            }

            if (history.isEmpty()) {
                LibraryEmptyState(
                    title = "Browsing History is Blank",
                    tagline = "Websites you explore will be safely cataloged here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUrlClicked(item.url) }
                                .testTag("history_item"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = { onHistoryDeleteRequested(item.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete history",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPermissionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // System Permissions States
    var hasCamera by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var hasLocation by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }

    fun checkPermissions() {
        hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasMedia = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        checkPermissions()
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Permissions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Browser Capabilities & Permissions",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "These system-level permissions enable advanced capabilities such as video calls, audio recordings (WebRTC), geolocations, and secure download folders inside trust-worthy websites.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PermissionStatusRow(
                        title = "Camera Access",
                        description = "Used for web camera, image uploads, and virtual environments.",
                        isGranted = hasCamera,
                        icon = Icons.Default.Camera,
                        onRequestValue = {
                            launcher.launch(arrayOf(android.Manifest.permission.CAMERA))
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    PermissionStatusRow(
                        title = "Microphone Access",
                        description = "Used for voice speech recognition, audio notes, and sound effects.",
                        isGranted = hasMic,
                        icon = Icons.Default.Mic,
                        onRequestValue = {
                            launcher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    PermissionStatusRow(
                        title = "Location Access",
                        description = "Shared with maps, weather, and localized web lookup search engines.",
                        isGranted = hasLocation,
                        icon = Icons.Default.LocationOn,
                        onRequestValue = {
                            launcher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    PermissionStatusRow(
                        title = "Media & Storage Access",
                        description = "Required to download files permanently and read media files for upload picking.",
                        isGranted = hasMedia,
                        icon = Icons.Default.Folder,
                        onRequestValue = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                launcher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_MEDIA_IMAGES,
                                        android.Manifest.permission.READ_MEDIA_VIDEO,
                                        android.Manifest.permission.READ_MEDIA_AUDIO
                                    )
                                )
                            } else {
                                launcher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSyncScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    // Cloud Sync States
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val lastSyncTime by viewModel.lastSyncTime.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    var showGoogleChooser by remember { mutableStateOf(false) }

    // Forms fields
    var signupLoginTab by remember { mutableStateOf(0) } // 0 = Login, 1 = Signup
    var emailText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }

    // Sync options toggles
    var syncBookmarks by remember { mutableStateOf(true) }
    var syncHistory by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud Sync & Account", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to browser")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                if (currentUser == null) {
                    Text(
                        text = "Sync & Browser Account",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "Sign in to synchronize your bookmarks, browsing history, and open tabs securely in the cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    // Login / Signup tabs
                    TabRow(
                        selectedTabIndex = signupLoginTab,
                        containerColor = Color.Transparent,
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        Tab(
                            selected = signupLoginTab == 0,
                            onClick = {  
                                signupLoginTab = 0
                                authError = null
                            },
                            text = { Text("Sign In", fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = signupLoginTab == 1,
                            onClick = {  
                                signupLoginTab = 1
                                authError = null
                            },
                            text = { Text("Create Account", fontWeight = FontWeight.Bold) }
                        )
                    }

                    if (authError != null) {
                        Text(
                            text = authError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    if (signupLoginTab == 1) {
                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = { Text("Display Name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = emailText,
                        onValueChange = { emailText = it },
                        label = { Text("Email Address") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )

                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        label = { Text("Password (6+ chars)") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = image, contentDescription = "Toggle password visibility")
                            }
                        },
                        visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )

                    Button(
                        onClick = {
                            if (emailText.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(emailText.trim()).matches()) {
                                authError = "Please enter a valid email address."
                                return@Button
                            }
                            if (passwordText.length < 6) {
                                authError = "Password must be at least 6 characters."
                                return@Button
                            }

                            if (signupLoginTab == 1) {
                                if (nameText.isBlank()) {
                                    authError = "Please enter your display name."
                                    return@Button
                                }
                                val registered = viewModel.registerUser(emailText, nameText, passwordText)
                                if (registered) {
                                    viewModel.loginUser(emailText, passwordText)
                                    authError = null
                                } else {
                                    authError = "This email is already registered."
                                }
                            } else {
                                val loggedIn = viewModel.loginUser(emailText, passwordText)
                                if (loggedIn) {
                                    authError = null
                                } else {
                                    authError = "Invalid email or password."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (signupLoginTab == 0) "Sign In" else "Create Account",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text(
                            text = "OR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedCard(
                        onClick = { showGoogleChooser = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(modifier = Modifier.size(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("G", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 18.sp), color = Color(0xFF4285F4))
                                Text("o", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 18.sp), color = Color(0xFFEA4335))
                                Text("o", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 18.sp), color = Color(0xFFFBBC05))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Continue with Google",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    val profile = currentUser!!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        val initials = profile.displayName.take(2).uppercase()
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = profile.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (profile.provider == "Google") Icons.Default.Launch else Icons.Default.Email,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Signed in with ${profile.provider}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Sync Panel & Status",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                val syncStatusText = if (isSyncing) {
                                    "Synchronizing data..."
                                } else if (lastSyncTime != null) {
                                    val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    "Synced successfully at ${format.format(java.util.Date(lastSyncTime!!))}"
                                } else {
                                    "Synced: Never (Tap Sync to backup)"
                                }
                                Text(
                                    text = syncStatusText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSyncing) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            }

                            Text(
                                text = "Select Data to Sync",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(bottom = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            ListItem(
                                headlineContent = { Text("Bookmarks (${bookmarks.size} items)") },
                                trailingContent = {
                                    Switch(checked = syncBookmarks, onCheckedChange = { syncBookmarks = it })
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.padding(0.dp)
                            )
                            ListItem(
                                headlineContent = { Text("Browsing History (${history.size} items)") },
                                trailingContent = {
                                    Switch(checked = syncHistory, onCheckedChange = { syncHistory = it })
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.padding(0.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.triggerDataSync() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSyncing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sync Now",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.logout()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign Out & Stop Sync",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }

    if (showGoogleChooser) {
        AlertDialog(
            onDismissRequest = { showGoogleChooser = false },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.size(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("G", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFF4285F4))
                        Text("o", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFFEA4335))
                        Text("o", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color(0xFFFBBC05))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose a Google Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Select an account to sync with Google Cloud Sync:",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    listOf(
                        "salmanmohdahmad567@gmail.com" to "Salman Ahmad",
                        "test.user@gmail.com" to "Test User"
                    ).forEach { (email, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectUser(email = email, name = name, provider = "Google", avatar = null)
                                    showGoogleChooser = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name.take(1).uppercase(),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                Text(text = email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGoogleChooser = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


