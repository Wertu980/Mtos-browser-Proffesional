package com.mtos.web.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtos.web.browser.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSearchEngineScreen(
    viewModel: BrowserViewModel,
    onOpenEngineSite: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val customEngines by viewModel.customSearchEngines.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeSearchEngine.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var customLinkInput by remember { mutableStateOf("") }
    var customNameInput by remember { mutableStateOf("") }
    var editingEngineId by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val previewUrl = remember(customLinkInput) {
        val normalized = BrowserViewModel.normalizeCustomSearchLink(customLinkInput)
        if (normalized != null) {
            BrowserViewModel.buildSearchUrlForTemplate("example", normalized)
        } else {
            null
        }
    }

    val submitCustomEngine = {
        val error = viewModel.addOrUpdateCustomSearchEngine(
            customName = customNameInput,
            rawLink = customLinkInput,
            editingEngineId = editingEngineId
        )
        if (error != null) {
            validationError = error
        } else {
            validationError = null
            customLinkInput = ""
            customNameInput = ""
            editingEngineId = null
            focusManager.clearFocus()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.search_engine_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("search_engine_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_engine_back_desc)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Active Search Engine Hero Banner
                item(key = "active_hero") {
                    ActiveSearchEngineHeroCard(
                        engine = activeEngine,
                        onOpenSite = { onOpenEngineSite(activeEngine.homeUrl) }
                    )
                }

                // Built-in Search Engines Section (Google pre-selected, MTOS second)
                item(key = "builtin_header") {
                    SectionLabel(
                        title = stringResource(R.string.search_engine_builtin_section),
                        subtitle = "Choose your default web search provider"
                    )
                }

                items(
                    items = BrowserViewModel.BUILT_IN_SEARCH_ENGINES,
                    key = { it.id }
                ) { engine ->
                    val isSelected = activeEngine.id == engine.id
                    SearchEngineOptionCard(
                        engine = engine,
                        isSelected = isSelected,
                        badgeText = when (engine.id) {
                            BrowserViewModel.GOOGLE_ENGINE_ID -> "Default"
                            BrowserViewModel.MTOS_ENGINE_ID -> "MTOS"
                            else -> null
                        },
                        onSelect = { viewModel.selectSearchEngine(engine.id) },
                        onEdit = null,
                        onDelete = null
                    )
                }

                // User's Saved Custom Search Engines
                if (customEngines.isNotEmpty()) {
                    item(key = "custom_header") {
                        SectionLabel(
                            title = stringResource(R.string.search_engine_custom_section),
                            subtitle = "Saved links you added"
                        )
                    }

                    items(
                        items = customEngines,
                        key = { it.id }
                    ) { engine ->
                        val isSelected = activeEngine.id == engine.id
                        SearchEngineOptionCard(
                            engine = engine,
                            isSelected = isSelected,
                            badgeText = "Custom",
                            onSelect = { viewModel.selectSearchEngine(engine.id) },
                            onEdit = {
                                editingEngineId = engine.id
                                customNameInput = engine.name
                                customLinkInput = engine.searchUrlTemplate
                                validationError = null
                            },
                            onDelete = {
                                if (editingEngineId == engine.id) {
                                    editingEngineId = null
                                    customNameInput = ""
                                    customLinkInput = ""
                                    validationError = null
                                }
                                viewModel.deleteCustomSearchEngine(engine.id)
                            }
                        )
                    }
                }

                // Add / Edit Custom Search Engine Link Card
                item(key = "custom_composer") {
                    CustomSearchEngineComposerCard(
                        isEditing = editingEngineId != null,
                        linkInput = customLinkInput,
                        nameInput = customNameInput,
                        validationError = validationError,
                        previewUrl = previewUrl,
                        onLinkChange = {
                            customLinkInput = it
                            if (validationError != null) validationError = null
                        },
                        onNameChange = { customNameInput = it },
                        onInsertQueryPlaceholder = {
                            val current = customLinkInput.trim()
                            customLinkInput = when {
                                current.isEmpty() -> "https://example.com/search?q=%s"
                                current.contains("%s") -> current
                                current.endsWith("=") -> "${current}%s"
                                current.contains("?") -> "${current}&q=%s"
                                current.endsWith("/") -> "${current}?q=%s"
                                else -> "${current}/?q=%s"
                            }
                            validationError = null
                        },
                        onSubmit = submitCustomEngine,
                        onCancelEdit = {
                            editingEngineId = null
                            customLinkInput = ""
                            customNameInput = ""
                            validationError = null
                            focusManager.clearFocus()
                        }
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ActiveSearchEngineHeroCard(
    engine: SearchEngineOption,
    onOpenSite: () -> Unit
) {
    val gradientColors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_search_engine_card"),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.search_engine_active_label),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onOpenSite,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("open_active_search_engine_button"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.search_engine_open_home),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EngineMonogramBadge(engine = engine, isSelected = true)

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = engine.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = engine.displayDomain,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = engine.searchUrlTemplate,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchEngineOptionCard(
    engine: SearchEngineOption,
    isSelected: Boolean,
    badgeText: String?,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "engine_card_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "engine_card_border"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
            .testTag("search_engine_option_${engine.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EngineMonogramBadge(engine = engine, isSelected = isSelected)

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = engine.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = engine.displayDomain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = engine.searchUrlTemplate,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onEdit != null && onDelete != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("edit_engine_${engine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit ${engine.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_engine_${engine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete ${engine.name}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("radio_engine_${engine.id}")
            )
        }
    }
}

@Composable
private fun EngineMonogramBadge(
    engine: SearchEngineOption,
    isSelected: Boolean
) {
    val bgColor = when (engine.id) {
        BrowserViewModel.GOOGLE_ENGINE_ID -> MaterialTheme.colorScheme.primary
        BrowserViewModel.MTOS_ENGINE_ID -> MaterialTheme.colorScheme.tertiary
        else -> if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
    }
    val fgColor = when (engine.id) {
        BrowserViewModel.GOOGLE_ENGINE_ID -> MaterialTheme.colorScheme.onPrimary
        BrowserViewModel.MTOS_ENGINE_ID -> MaterialTheme.colorScheme.onTertiary
        else -> if (isSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
    }
    val monogram = engine.name.trim().firstOrNull()?.uppercase() ?: "S"

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (engine.isBuiltIn) {
            Text(
                text = monogram,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = fgColor
            )
        } else {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CustomSearchEngineComposerCard(
    isEditing: Boolean,
    linkInput: String,
    nameInput: String,
    validationError: String?,
    previewUrl: String?,
    onLinkChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onInsertQueryPlaceholder: () -> Unit,
    onSubmit: () -> Unit,
    onCancelEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("custom_search_engine_composer"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLink,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isEditing) R.string.search_engine_edit_section
                            else R.string.search_engine_add_section
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Write any other link to use as your search engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = linkInput,
                onValueChange = onLinkChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_search_link_input"),
                label = { Text(stringResource(R.string.search_engine_url_label)) },
                placeholder = { Text(stringResource(R.string.search_engine_url_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null
                    )
                },
                isError = validationError != null,
                supportingText = if (validationError != null) {
                    { Text(validationError, color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_search_name_input"),
                label = { Text(stringResource(R.string.search_engine_name_label)) },
                placeholder = { Text(stringResource(R.string.search_engine_name_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )

            // Helper row with %s shortcut
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.search_engine_helper_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onInsertQueryPlaceholder,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("insert_query_placeholder_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Insert %s query format")
                }
            }

            AnimatedVisibility(
                visible = previewUrl != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                previewUrl?.let { url ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Live search preview",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isEditing) {
                    OutlinedButton(
                        onClick = onCancelEdit,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("cancel_edit_custom_engine_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.search_engine_cancel))
                    }
                }

                Button(
                    onClick = onSubmit,
                    enabled = linkInput.isNotBlank(),
                    modifier = Modifier
                        .weight(if (isEditing) 1.5f else 1f)
                        .heightIn(min = 50.dp)
                        .testTag("save_custom_engine_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (isEditing) R.string.search_engine_update_choose
                            else R.string.search_engine_save_choose
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
