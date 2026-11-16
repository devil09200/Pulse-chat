package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.model.PhoneDirectoryEntry
import com.example.data.model.PublicProfile
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.PrimaryGreen
import com.example.util.ContactsHelper
import com.example.util.PhoneContact
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ContactsScreen(
    currentUserId: String,
    defaultCountryCode: String,
    publicProfiles: List<PublicProfile>,
    checkingContactId: String?,
    onBack: () -> Unit,
    onCheckContactRegistration: (PhoneContact, (PhoneDirectoryEntry?) -> Unit) -> Unit,
    onOpenRegisteredChat: (PhoneDirectoryEntry, PhoneContact) -> Unit,
    onCreateChat: (title: String, participantIds: List<String>, isGroup: Boolean, initialMsg: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pulseColors = LocalPulseChatColors.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    var permissionDeniedPermanently by rememberSaveable { mutableStateOf(false) }

    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var isLoadingContacts by remember { mutableStateOf(false) }
    var refreshTrigger by rememberSaveable { mutableIntStateOf(0) }

    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var inviteCandidate by remember { mutableStateOf<PhoneContact?>(null) }
    var showNewGroupDialog by remember { mutableStateOf(false) }

    val activity = remember(context) { context.findActivity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRequestedPermission = true
        hasPermission = granted
        if (granted) {
            permissionDeniedPermanently = false
            refreshTrigger++
        } else {
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.READ_CONTACTS
                )
            } ?: false
            permissionDeniedPermanently = !shouldShowRationale
        }
    }

    // Re-check permission whenever user returns from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (nowGranted != hasPermission) {
                    hasPermission = nowGranted
                    if (nowGranted) {
                        permissionDeniedPermanently = false
                        refreshTrigger++
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Prompt Android runtime permission dialog automatically on first entry if needed
    LaunchedEffect(Unit) {
        if (!hasPermission && !hasRequestedPermission) {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // Load device contacts via ContactsContract whenever permitted or refreshed
    LaunchedEffect(hasPermission, refreshTrigger, defaultCountryCode) {
        if (hasPermission) {
            isLoadingContacts = true
            try {
                contacts = ContactsHelper.loadDeviceContacts(
                    context = context,
                    defaultCountryCode = defaultCountryCode.ifBlank { "+1" }
                )
            } catch (e: SecurityException) {
                hasPermission = false
                contacts = emptyList()
            } catch (e: Exception) {
                onShowMessage("Failed to load device contacts")
            } finally {
                isLoadingContacts = false
            }
        } else {
            contacts = emptyList()
        }
    }

    BackHandler {
        if (isSearchOpen) {
            isSearchOpen = false
            searchQuery = ""
        } else {
            onBack()
        }
    }

    val filteredContacts = remember(contacts, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            contacts
        } else {
            val qDigits = q.filter { it.isDigit() }
            contacts.filter { contact ->
                contact.displayName.contains(q, ignoreCase = true) ||
                    contact.phoneNumber.contains(q, ignoreCase = true) ||
                    contact.normalizedPhone.contains(q, ignoreCase = true) ||
                    (qDigits.isNotEmpty() && contact.normalizedPhone.filter { it.isDigit() }.contains(qDigits))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            Surface(
                color = pulseColors.headerBackground,
                shadowElevation = 3.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (isSearchOpen) {
                                    isSearchOpen = false
                                    searchQuery = ""
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier.testTag("contacts_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = pulseColors.headerContent
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.contacts_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = pulseColors.headerContent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    !hasPermission -> "Permission required"
                                    isLoadingContacts -> "Loading contacts..."
                                    else -> "${contacts.size} contacts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = pulseColors.headerContent.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                        }

                        IconButton(
                            onClick = {
                                isSearchOpen = !isSearchOpen
                                if (!isSearchOpen) searchQuery = ""
                            },
                            modifier = Modifier.testTag("contacts_search_button")
                        ) {
                            Icon(
                                imageVector = if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search contacts",
                                tint = pulseColors.headerContent
                            )
                        }

                        IconButton(
                            onClick = {
                                if (hasPermission) {
                                    scope.launch {
                                        isLoadingContacts = true
                                        try {
                                            contacts = ContactsHelper.loadDeviceContacts(
                                                context = context,
                                                defaultCountryCode = defaultCountryCode.ifBlank { "+1" }
                                            )
                                            onShowMessage("Contacts refreshed (${contacts.size})")
                                        } catch (_: Exception) {
                                            onShowMessage("Could not refresh contacts")
                                        } finally {
                                            isLoadingContacts = false
                                        }
                                    }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            },
                            modifier = Modifier.testTag("contacts_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.contacts_refresh),
                                tint = pulseColors.headerContent
                            )
                        }
                    }

                    AnimatedVisibility(visible = isSearchOpen) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    "Search name or phone number...",
                                    color = pulseColors.headerContent.copy(alpha = 0.72f)
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Black.copy(alpha = 0.14f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.14f),
                                focusedTextColor = pulseColors.headerContent,
                                unfocusedTextColor = pulseColors.headerContent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .testTag("contacts_search_input")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
            ) {
                if (!hasPermission) {
                    ContactsPermissionDeniedView(
                        permanentlyDenied = permissionDeniedPermanently,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        onOpenSettings = {
                            context.openAppPermissionSettings()
                        },
                        onNewGroup = { showNewGroupDialog = true },
                        onSelfChat = {
                            onCreateChat(
                                "Saved Notes",
                                listOf(currentUserId),
                                false,
                                "Welcome to your private Saved Notes!"
                            )
                        }
                    )
                } else if (isLoadingContacts && contacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PrimaryGreen)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Reading phone contacts...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (searchQuery.isBlank()) {
                            item {
                                QuickContactActionRow(
                                    icon = Icons.Default.GroupAdd,
                                    title = "New group",
                                    subtitle = "Create a group conversation",
                                    testTag = "contacts_new_group_row",
                                    onClick = { showNewGroupDialog = true }
                                )
                            }
                            item {
                                QuickContactActionRow(
                                    icon = Icons.Default.NoteAlt,
                                    title = "Message yourself",
                                    subtitle = "Saved Notes & personal reminders",
                                    testTag = "contacts_self_chat_row",
                                    onClick = {
                                        onCreateChat(
                                            "Saved Notes",
                                            listOf(currentUserId),
                                            false,
                                            "Welcome to your private Saved Notes!"
                                        )
                                    }
                                )
                            }
                            item {
                                HorizontalDivider(color = pulseColors.divider, thickness = 0.7.dp)
                                Text(
                                    text = "Contacts on your phone",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }

                        if (filteredContacts.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp)
                                ) {
                                    EmptyStatePanel(
                                        icon = Icons.Default.Contacts,
                                        title = if (searchQuery.isBlank()) {
                                            stringResource(R.string.contacts_empty_title)
                                        } else {
                                            "No contacts match \"$searchQuery\""
                                        },
                                        subtitle = if (searchQuery.isBlank()) {
                                            stringResource(R.string.contacts_empty_subtitle)
                                        } else {
                                            "Try another name or phone number."
                                        },
                                        action = if (searchQuery.isBlank()) {
                                            {
                                                OutlinedButton(
                                                    onClick = { refreshTrigger++ },
                                                    modifier = Modifier.testTag("contacts_empty_refresh_button")
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(stringResource(R.string.contacts_refresh))
                                                }
                                            }
                                        } else null
                                    )
                                }
                            }
                        } else {
                            items(
                                items = filteredContacts,
                                key = { "${it.contactId}_${it.normalizedPhone}" }
                            ) { contact ->
                                val isChecking = checkingContactId == contact.contactId
                                DeviceContactRow(
                                    contact = contact,
                                    isChecking = isChecking,
                                    onClick = {
                                        onCheckContactRegistration(contact) { entry ->
                                            if (entry != null) {
                                                onOpenRegisteredChat(entry, contact)
                                            } else {
                                                inviteCandidate = contact
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                    color = pulseColors.divider,
                                    thickness = 0.5.dp
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    val pendingInvite = inviteCandidate
    if (pendingInvite != null) {
        val inviteText = stringResource(R.string.contacts_invite_message)
        AlertDialog(
            onDismissRequest = { inviteCandidate = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = PrimaryGreen
                )
            },
            title = {
                Text(stringResource(R.string.contacts_invite_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${pendingInvite.displayName} (${pendingInvite.phoneNumber}) is not on PulseChat yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = inviteText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        inviteCandidate = null
                        context.launchInviteIntent(
                            phoneNumber = pendingInvite.phoneNumber,
                            inviteBody = inviteText,
                            onFallbackMessage = onShowMessage
                        )
                    },
                    modifier = Modifier.testTag("contacts_send_invite_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Invite")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("PulseChat Invite", inviteText))
                            inviteCandidate = null
                            onShowMessage("Invite message copied")
                        },
                        modifier = Modifier.testTag("contacts_copy_invite_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                    TextButton(onClick = { inviteCandidate = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showNewGroupDialog) {
        NewChatDialog(
            publicProfiles = publicProfiles,
            currentUserId = currentUserId,
            initialGroupMode = true,
            onDismiss = { showNewGroupDialog = false },
            onCreateChat = { title, participants, isGroup, initialMsg ->
                showNewGroupDialog = false
                onCreateChat(title, participants, isGroup, initialMsg)
            }
        )
    }
}

@Composable
private fun QuickContactActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(PrimaryGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceContactRow(
    contact: PhoneContact,
    isChecking: Boolean,
    onClick: () -> Unit
) {
    val avatarColor = remember(contact.contactId) {
        CONTACT_AVATAR_COLORS[abs(contact.contactId.hashCode()) % CONTACT_AVATAR_COLORS.size]
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(enabled = !isChecking, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("contact_row_${contact.contactId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = contact.displayName,
            colorHex = avatarColor,
            size = 46.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isChecking) {
            Spacer(modifier = Modifier.width(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = PrimaryGreen
            )
        }
    }
}

@Composable
private fun ContactsPermissionDeniedView(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewGroup: () -> Unit,
    onSelfChat: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        QuickContactActionRow(
            icon = Icons.Default.GroupAdd,
            title = "New group",
            subtitle = "Start a group chat without contact access",
            testTag = "contacts_denied_new_group_row",
            onClick = onNewGroup
        )
        QuickContactActionRow(
            icon = Icons.Default.NoteAlt,
            title = "Message yourself",
            subtitle = "Saved Notes & personal reminders",
            testTag = "contacts_denied_self_chat_row",
            onClick = onSelfChat
        )
        HorizontalDivider(color = LocalPulseChatColors.current.divider)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Contacts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (permanentlyDenied) {
                    stringResource(R.string.contacts_permission_denied_title)
                } else {
                    stringResource(R.string.contacts_permission_title)
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (permanentlyDenied) {
                    stringResource(R.string.contacts_permission_denied_subtitle)
                } else {
                    stringResource(R.string.contacts_permission_rationale)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Numbers are matched securely using hashed lookup only.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .heightIn(min = 48.dp)
                    .testTag(if (permanentlyDenied) "contacts_open_settings_button" else "contacts_allow_access_button")
            ) {
                Icon(
                    imageVector = if (permanentlyDenied) Icons.Default.Settings else Icons.Default.Contacts,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (permanentlyDenied) stringResource(R.string.contacts_open_settings)
                    else stringResource(R.string.contacts_allow_access)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = if (permanentlyDenied) onRequestPermission else onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .heightIn(min = 48.dp)
                    .testTag(if (permanentlyDenied) "contacts_retry_permission_button" else "contacts_secondary_settings_button")
            ) {
                Text(
                    if (permanentlyDenied) "Try permission prompt again"
                    else stringResource(R.string.contacts_open_settings)
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppPermissionSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun Context.launchInviteIntent(
    phoneNumber: String,
    inviteBody: String,
    onFallbackMessage: (String) -> Unit
) {
    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
        putExtra("sms_body", inviteBody)
    }
    try {
        startActivity(smsIntent)
    } catch (_: Exception) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, inviteBody)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Invite via"))
        } catch (_: Exception) {
            onFallbackMessage("No messaging app found to send invite")
        }
    }
}

private val CONTACT_AVATAR_COLORS = listOf(
    "#128C7E", "#075E54", "#25D366", "#1D6F8A", "#5B6AD0", "#B54D7C", "#C97A2E"
)
