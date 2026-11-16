package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.UserProfile
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors

@Composable
fun SettingsScreen(
    userProfile: UserProfile?,
    currentThemeMode: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onUpdateThemeMode: (String) -> Unit,
    onUpdateSetting: (key: String, value: Any) -> Unit,
    onSignOut: () -> Unit
) {
    BackHandler { onBack() }

    val pulseColors = LocalPulseChatColors.current

    var showThemeDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onUpdateSetting("notificationsEnabled", granted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(color = pulseColors.headerBackground, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = pulseColors.headerContent
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = pulseColors.headerContent
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Profile Header Shortcut
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile() }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(
                        name = userProfile?.displayName ?: "PulseChat User",
                        colorHex = userProfile?.avatarColorHex ?: "#128C7E",
                        photoBase64 = userProfile?.photoBase64 ?: "",
                        size = 60.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userProfile?.displayName ?: "PulseChat User",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = userProfile?.about ?: "Available on PulseChat",
                            style = MaterialTheme.typography.bodySmall,
                            color = pulseColors.bubbleSubtext
                        )
                    }
                }

                HorizontalDivider(color = pulseColors.divider)

                SettingsItemRow(
                    icon = Icons.Default.Key,
                    title = "Account",
                    subtitle = "Security notifications, phone number, email",
                    onClick = { showAccountDialog = true }
                )
                SettingsItemRow(
                    icon = Icons.Default.Lock,
                    title = "Privacy",
                    subtitle = "Last seen (${userProfile?.lastSeenVisibility ?: "everyone"}), read receipts",
                    onClick = { showPrivacyDialog = true }
                )
                SettingsSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    subtitle = "Message, group & call alerts",
                    checked = userProfile?.notificationsEnabled ?: true,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onUpdateSetting("notificationsEnabled", enabled)
                        }
                    }
                )
                SettingsItemRow(
                    icon = Icons.Default.DarkMode,
                    title = "Theme & Chats",
                    subtitle = "Current: ${currentThemeMode.replaceFirstChar { it.uppercase() }}",
                    onClick = { showThemeDialog = true },
                    testTag = "theme_settings_row"
                )
                SettingsItemRow(
                    icon = Icons.Default.DataUsage,
                    title = "Storage and data",
                    subtitle = "Network usage, media compression & uploads",
                    onClick = { showStorageDialog = true }
                )
                SettingsItemRow(
                    icon = Icons.AutoMirrored.Filled.Help,
                    title = "Help and support",
                    subtitle = "Help center, contact us, privacy policy",
                    onClick = { showHelpDialog = true }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = pulseColors.divider
                )

                SettingsItemRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Sign Out",
                    subtitle = "Sign out of your Google account on this device",
                    onClick = onSignOut,
                    testTag = "sign_out_row"
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme") },
            text = {
                Column {
                    listOf(
                        "system" to "System default",
                        "light" to "Light mode",
                        "dark" to "Dark mode"
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp)
                                .testTag("theme_option_$mode"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentThemeMode == mode, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            }
        )
    }

    if (showPrivacyDialog) {
        val currentLastSeen = userProfile?.lastSeenVisibility ?: "everyone"
        val readReceipts = userProfile?.readReceiptsEnabled ?: true
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy Controls") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Read receipts", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Show blue double ticks when messages are read",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = readReceipts,
                            onCheckedChange = { onUpdateSetting("readReceiptsEnabled", it) }
                        )
                    }
                    HorizontalDivider()
                    Text("Last seen & online", style = MaterialTheme.typography.titleSmall)
                    listOf("everyone" to "Everyone", "contacts" to "My contacts", "nobody" to "Nobody").forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUpdateSetting("lastSeenVisibility", key) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentLastSeen == key, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Done") }
            }
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("Account Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Email: ${userProfile?.email?.ifBlank { "Google Account" } ?: "Google Account"}")
                    Text("Phone: ${userProfile?.phoneNumber?.ifBlank { "Not linked" } ?: "Not linked"}")
                    Text("User ID: ${userProfile?.userId?.take(14) ?: "-"}...")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountDialog = false }) { Text("Close") }
            }
        )
    }

    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            title = { Text("Storage & Data") },
            text = {
                Text("Photos are automatically optimized (<350 KB JPEG) and voice clips are encoded in compact AAC (.m4a) for fast real-time delivery over Cloud Firestore.")
            },
            confirmButton = {
                TextButton(onClick = { showStorageDialog = false }) { Text("OK") }
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("PulseChat Support") },
            text = {
                Text("PulseChat v1.0\n\nReal-time messaging, communities, status updates, and calling powered by Cloud Firestore and Google Sign-In.")
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SettingsItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val mod = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .let { if (testTag != null) it.testTag(testTag) else it }

    Row(
        modifier = mod,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
