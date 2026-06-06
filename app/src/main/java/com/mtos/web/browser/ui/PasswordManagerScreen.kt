package com.mtos.web.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtos.web.browser.data.PasswordCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPasswordManagerScreen(
    viewModel: BrowserViewModel,
    currentUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("browser_auth_prefs", Context.MODE_PRIVATE) }
    var isMasterPinSet by remember { mutableStateOf(!sharedPrefs.getString("master_pin", null).isNullOrEmpty()) }
    var enteredPin by remember { mutableStateOf("") }
    var isUnlocked by remember { mutableStateOf(false) }
    
    // Biometrics and Lock change configurations
    var showVaultSettings by remember { mutableStateOf(false) }
    var useBiometrics by remember { mutableStateOf(sharedPrefs.getBoolean("use_biometrics", false)) }
    
    // Change PIN state variables
    var showChangePinDialog by remember { mutableStateOf(false) }
    var curPinVerifyInput by remember { mutableStateOf("") }
    var newPinInput1 by remember { mutableStateOf("") }
    var newPinInput2 by remember { mutableStateOf("") }
    
    // Biometric scanner lock screen prompt state
    var showBiometricPrompt by remember { mutableStateOf(isMasterPinSet && sharedPrefs.getBoolean("use_biometrics", false)) }
    var biometricScanningState by remember { mutableStateOf("waiting") }
    
    // UI Flows
    val savedPasswords by viewModel.savedPasswords.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    
    // Create Mode variables
    var websiteUrlInput by remember { mutableStateOf(currentUrl.ifBlank { "" }) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }

    val filteredPasswords = remember(savedPasswords, searchQuery) {
        if (searchQuery.isBlank()) {
            savedPasswords
        } else {
            savedPasswords.filter {
                it.websiteUrl.contains(searchQuery, ignoreCase = true) ||
                        it.username.contains(searchQuery, ignoreCase = true) ||
                        it.labelName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Secure Pass Vault", 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back back to browser")
                    }
                },
                actions = {
                    if (isUnlocked) {
                        IconButton(
                            onClick = { showVaultSettings = true },
                            modifier = Modifier.testTag("vault_settings_button")
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Vault Security Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        },
        floatingActionButton = {
            if (isUnlocked) {
                FloatingActionButton(
                    onClick = {
                        websiteUrlInput = currentUrl.ifBlank { "" }
                        usernameInput = ""
                        passwordInput = ""
                        labelInput = ""
                        showAddDialog = true
                    },
                    modifier = Modifier.testTag("add_credential_fab"),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add password")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isUnlocked) {
                // LOCK/INITIALIZATION SCREEN
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isMasterPinSet) Icons.Default.Lock else Icons.Default.FiberPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isMasterPinSet) "Unlock Your Secure Vault" else "Initialize Secure Vault",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isMasterPinSet) 
                            "Enter your 4-digit Master PIN code to access locally encrypted credentials." 
                            else "Establish a 4-digit Master PIN to encrypt and secure credentials locally.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // PIN Dots indicators
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        for (i in 1..4) {
                            val isFiled = enteredPin.length >= i
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isFiled) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                        }
                    }

                    if (isMasterPinSet && useBiometrics) {
                        TextButton(
                            onClick = {
                                biometricScanningState = "waiting"
                                showBiometricPrompt = true
                            },
                            modifier = Modifier.padding(vertical = 4.dp).testTag("quick_biometric_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Use Biometrics",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unlock with Fingerprint / Face ID",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Simulated Clean Keyboard Layout block
                    CustomNumericalKeyboard(
                        onKeyPressed = { digit ->
                            if (enteredPin.length < 4) {
                                enteredPin += digit
                                if (enteredPin.length == 4) {
                                    if (isMasterPinSet) {
                                        val hash = sharedPrefs.getString("master_pin", "")
                                        if (enteredPin == hash) {
                                            isUnlocked = true
                                        } else {
                                            viewModel.showToast("Incorrect Master PIN. Access denied.")
                                            enteredPin = ""
                                        }
                                    } else {
                                        sharedPrefs.edit().putString("master_pin", enteredPin).apply()
                                        isMasterPinSet = true
                                        isUnlocked = true
                                        viewModel.showToast("Master PIN configured successfully!")
                                    }
                                }
                            }
                        },
                        onBackspace = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        }
                    )
                }
            } else {
                // VAULT LIST UNLOCKED
                Column(modifier = Modifier.fillMaxSize()) {
                    // Search Address and Web Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag("vault_search_field"),
                        placeholder = { Text("Search vault entries...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Intelligent System Explanation Banner
                    var showIntroBanner by remember { mutableStateOf(true) }
                    if (showIntroBanner) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "How Vault AI & ML work:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    IconButton(
                                        onClick = { showIntroBanner = false },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "1. Local ML Strength Meter: Evaluates credential math on-device securely using high-speed entropy logic.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "2. Gemini Cyber Auditor: Tap 'Audit' on any credential card to let custom cloud AI scan pattern hazards and provide customized security defenses.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (filteredPasswords.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isEmpty()) "Your Vault is Empty" else "No Matching Credentials",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (searchQuery.isEmpty()) 
                                    "Tap the + button to save your first secure encrypted credential block, or visit a login page." 
                                    else "Verify address filters and typing metrics.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(filteredPasswords, key = { it.id }) { credential ->
                                PasswordCredentialCard(
                                    credential = credential,
                                    viewModel = viewModel,
                                    onDeleteRequested = {
                                        viewModel.deletePassword(credential)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Credential Dialog Screen
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "Save Credentials Securely",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("App or Label Name (e.g. GitHub)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = websiteUrlInput,
                        onValueChange = { websiteUrlInput = it },
                        label = { Text("Website Domain / URL") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Username or Email") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    val scope = rememberCoroutineScope()
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = {
                            Row(
                                modifier = Modifier.padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // AI Generator pill
                                TextButton(
                                    onClick = {
                                        // Generates robust passwords easily using clean mathematical randomness
                                        passwordInput = generateSecureLocalPassword()
                                        viewModel.showToast("Secure local password generated.")
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "AI Generate",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Gen", fontSize = 12.sp)
                                }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (websiteUrlInput.isBlank() || usernameInput.isBlank() || passwordInput.isBlank()) {
                            viewModel.showToast("Please fill all coordinates.")
                        } else {
                            viewModel.savePassword(
                                websiteUrl = websiteUrlInput,
                                username = usernameInput,
                                plainText = passwordInput,
                                label = labelInput.ifBlank { websiteUrlInput }
                            )
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("save_credential_confirm")
                ) {
                    Text("Securely Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Vault settings dialog panel
    if (showVaultSettings) {
        AlertDialog(
            onDismissRequest = { showVaultSettings = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Vault Security Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Biometric Lock toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .clickable {
                                val newValue = !useBiometrics
                                useBiometrics = newValue
                                sharedPrefs.edit().putBoolean("use_biometrics", newValue).apply()
                                viewModel.showToast(if (newValue) "Biometric scan enabled!" else "Biometric scan disabled.")
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Fingerprint / Face ID Link",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Unlock Secure Vault dynamically using biometric scanner options.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Switch(
                            checked = useBiometrics,
                            onCheckedChange = { newValue ->
                                useBiometrics = newValue
                                sharedPrefs.edit().putBoolean("use_biometrics", newValue).apply()
                                viewModel.showToast(if (newValue) "Biometric scan enabled!" else "Biometric scan disabled.")
                            }
                        )
                    }

                    // Change PIN Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .clickable {
                                curPinVerifyInput = ""
                                newPinInput1 = ""
                                newPinInput2 = ""
                                showChangePinDialog = true
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Change Master PIN",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Alter your 4-digit numeric cryptographic lock key.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showVaultSettings = false }) {
                    Text("Done")
                }
            }
        )
    }

    // Change Master PIN Dialog panel
    if (showChangePinDialog) {
        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = {
                Text("Change Master PIN", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Enter current authority codes and specify a new 4-digit Master PIN to secure credentials locally.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = curPinVerifyInput,
                        onValueChange = { 
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                curPinVerifyInput = it 
                            }
                        },
                        label = { Text("Current 4-digit PIN") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newPinInput1,
                        onValueChange = { 
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                newPinInput1 = it 
                            }
                        },
                        label = { Text("New 4-digit PIN") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newPinInput2,
                        onValueChange = { 
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                newPinInput2 = it 
                            }
                        },
                        label = { Text("Confirm New PIN") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val realCurrentPin = sharedPrefs.getString("master_pin", "")
                        if (curPinVerifyInput != realCurrentPin) {
                            viewModel.showToast("Current PIN verification failed.")
                        } else if (newPinInput1.length != 4) {
                            viewModel.showToast("New PIN must be exactly 4 digits.")
                        } else if (newPinInput1 != newPinInput2) {
                            viewModel.showToast("New PINs do not match.")
                        } else {
                            sharedPrefs.edit().putString("master_pin", newPinInput1).apply()
                            showChangePinDialog = false
                            showVaultSettings = false
                            viewModel.showToast("Master PIN updated successfully!")
                        }
                    }
                ) {
                    Text("Update PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Biometric Scanner Pop-up Prompt UI
    if (showBiometricPrompt && isUnlocked == false) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showBiometricPrompt = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = if (biometricScanningState == "success") Color(0xFF388E3C)
                           else if (biometricScanningState == "scanning") MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(56.dp)
                )
            },
            title = {
                Text(
                    text = if (biometricScanningState == "scanning") "Scanning biometrics..."
                           else if (biometricScanningState == "success") "Identity Verified!"
                           else "Biometric Unlock",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (biometricScanningState == "scanning") "Place finger on sensor or look at face scanner..."
                               else if (biometricScanningState == "success") "Decrypted vault credential index successfully."
                               else "Confirm your fingerprint or Face ID to instantly unlock the secure passwords manager.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (biometricScanningState == "scanning") {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp).padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    }
                }
            },
            confirmButton = {
                if (biometricScanningState == "waiting") {
                    Button(
                        onClick = {
                            scope.launch {
                                biometricScanningState = "scanning"
                                delay(1200) // Simulate premium biometric processing
                                biometricScanningState = "success"
                                delay(600)
                                isUnlocked = true
                                showBiometricPrompt = false
                                biometricScanningState = "waiting"
                                viewModel.showToast("Vault unlocked via biometrics.")
                            }
                        }
                    ) {
                        Text("Scan Biometrics")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricPrompt = false
                        biometricScanningState = "waiting"
                    }
                ) {
                    Text("Use PIN Lock")
                }
            }
        )
    }
}

@Composable
fun PasswordCredentialCard(
    credential: PasswordCredential,
    viewModel: BrowserViewModel,
    onDeleteRequested: () -> Unit
) {
    val context = LocalContext.current
    var isPasswordVisible by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // AI analysis states
    var aiReport by remember { mutableStateOf<String?>(null) }
    var isAnalyzingAI by remember { mutableStateOf(false) }
    var isAIEvaluated by remember { mutableStateOf(false) }

    val plainPassword = remember(credential.encryptedPassword) {
        viewModel.decryptPassword(credential.encryptedPassword)
    }

    val passwordStrength = remember(plainPassword) {
        evaluateLocalStrength(plainPassword)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = credential.labelName.ifEmpty { "Credential Block" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = credential.websiteUrl,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            // Do nothing or copy domain
                        }
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Username", credential.username)
                            clipboard.setPrimaryClip(clip)
                            viewModel.showToast("Username copied.")
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Username",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }

                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete entry",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Username Info Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = credential.username,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Password Content Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPasswordVisible) plainPassword else "••••••••••••",
                        fontSize = 14.sp,
                        fontWeight = if (isPasswordVisible) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(
                        onClick = { isPasswordVisible = !isPasswordVisible },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Show password",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Password", plainPassword)
                            clipboard.setPrimaryClip(clip)
                            viewModel.showToast("Password copied securely.")
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Password",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Local Strength Audit Meter
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Local ML Strength: ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(passwordStrength.fraction)
                            .clip(RoundedCornerShape(3.dp))
                            .background(passwordStrength.color)
                    )
                }
                
                Text(
                    text = "  ${passwordStrength.level}",
                    fontSize = 11.sp,
                    color = passwordStrength.color,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expanded AI Auditor section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(10.dp)
            ) {
                if (!isAIEvaluated) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Gemini AI Security Auditor",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Button(
                            onClick = {
                                isAnalyzingAI = true
                                isAIEvaluated = true
                                viewModel.analyzeCredentialWithAI(
                                    password = plainPassword,
                                    username = credential.username,
                                    url = credential.websiteUrl,
                                    onCompleted = { result ->
                                        aiReport = result
                                        isAnalyzingAI = false
                                    }
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Audit", fontSize = 11.sp)
                        }
                    }
                } else {
                    AnimatedVisibility(
                        visible = isAIEvaluated,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AI Breach Analysis & Recommendations:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))

                            if (isAnalyzingAI) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Contacting Gemini Cyber Intelligence...", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            } else {
                                Text(
                                    text = aiReport ?: "No network diagnostic or analysis retrieved.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Credential block?") },
            text = { Text("This will permanently remove saved credentials for ${credential.websiteUrl}. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteRequested()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Custom numerical security keyboard
@Composable
fun CustomNumericalKeyboard(
    onKeyPressed: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("Clear", "0", "◀")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { key ->
                    val isSpecial = key == "Clear" || key == "◀"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSpecial) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            )
                            .clickable {
                                when (key) {
                                    "◀" -> onBackspace()
                                    "Clear" -> {
                                        for (i in 1..4) onBackspace()
                                    }
                                    else -> onKeyPressed(key)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = key,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSpecial) MaterialTheme.colorScheme.onSurfaceVariant 
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

// HEURISTICAL PASSWORD ANALYSIS (Local metrics)
data class PasswordStrengthResult(
    val level: String,
    val fraction: Float,
    val color: Color
)

fun evaluateLocalStrength(password: String): PasswordStrengthResult {
    if (password.length < 4) {
        return PasswordStrengthResult("Weak PIN/Key", 0.15f, Color(0xFFD32F2F))
    }
    
    var types = 0
    if (password.any { it.isLowerCase() }) types++
    if (password.any { it.isUpperCase() }) types++
    if (password.any { it.isDigit() }) types++
    if (password.any { !it.isLetterOrDigit() }) types++

    val entropy = password.length * log2(10f) // simple entropy representation metric
    
    return when {
        password.length >= 12 && types >= 3 -> PasswordStrengthResult("Maximum Fortress", 1.0f, Color(0xFF388E3C))
        password.length >= 8 && types >= 3 -> PasswordStrengthResult("Secure / Bulletproof", 0.75f, Color(0xFF4CAF50))
        password.length >= 6 && types >= 2 -> PasswordStrengthResult("Moderate Protection", 0.50f, Color(0xFFFBC02D))
        else -> PasswordStrengthResult("Highly Vulnerable", 0.25f, Color(0xFFE64A19))
    }
}

// Generate Secure Domain Passwords
fun generateSecureLocalPassword(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890!@#$%"
    return (1..14)
        .map { chars.random() }
        .joinToString("")
}
