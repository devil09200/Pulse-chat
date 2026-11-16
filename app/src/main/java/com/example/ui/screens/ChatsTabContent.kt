package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Chat
import com.example.data.model.PublicProfile
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.MessageStatusTick
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.viewmodel.UiState
import com.example.util.TimeFormatter

@Composable
fun ChatsTabContent(
    chatsState: UiState<List<Chat>>,
    publicProfiles: List<PublicProfile>,
    currentUserId: String,
    searchQuery: String,
    selectedChatId: String?,
    onOpenChat: (Chat) -> Unit,
    onTogglePin: (Chat) -> Unit,
    onToggleMute: (Chat) -> Unit,
    onDeleteChat: (String) -> Unit,
    onCreateSelfChat: () -> Unit,
    onShowNewChatDialog: () -> Unit
) {
    val pulseColors = LocalPulseChatColors.current

    when (chatsState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        is UiState.Error -> {
            EmptyStatePanel(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Unable to load chats",
                subtitle = chatsState.message
            )
        }
        is UiState.Success -> {
            val filtered = remember(chatsState.data, searchQuery) {
                if (searchQuery.isBlank()) chatsState.data
                else chatsState.data.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                        it.lastMessageText.contains(searchQuery, ignoreCase = true)
                }
            }

            if (filtered.isEmpty()) {
                EmptyStatePanel(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = if (searchQuery.isBlank()) stringResource(R.string.empty_chats_title) else "No matching chats",
                    subtitle = if (searchQuery.isBlank()) stringResource(R.string.empty_chats_subtitle) else "Try a different contact name or message keyword.",
                    action = if (searchQuery.isBlank()) {
                        {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onShowNewChatDialog,
                                    modifier = Modifier.testTag("start_new_chat_button")
                                ) {
                                    Icon(Icons.Default.AddComment, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Start New Chat")
                                }
                                OutlinedButton(
                                    onClick = onCreateSelfChat,
                                    modifier = Modifier.testTag("message_yourself_button")
                                ) {
                                    Icon(Icons.Default.NoteAlt, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Message Yourself (Saved Notes)")
                                }
                            }
                        }
                    } else null
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { chat ->
                        val isPinned = currentUserId in chat.pinnedBy
                        val isMuted = currentUserId in chat.mutedBy
                        val otherProfile = remember(publicProfiles, chat.participantIds) {
                            val otherId = chat.participantIds.firstOrNull { it != currentUserId }
                            publicProfiles.firstOrNull { it.userId == otherId }
                        }
                        val isOnline = if (chat.isGroup) false else (otherProfile?.isOnline ?: true)

                        ChatListRow(
                            chat = chat,
                            isSelected = chat.id == selectedChatId,
                            isPinned = isPinned,
                            isMuted = isMuted,
                            isOnline = isOnline,
                            currentUserId = currentUserId,
                            photoBase64 = otherProfile?.photoBase64 ?: "",
                            onClick = { onOpenChat(chat) },
                            onTogglePin = { onTogglePin(chat) },
                            onToggleMute = { onToggleMute(chat) },
                            onDelete = { onDeleteChat(chat.id) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                            color = pulseColors.divider,
                            thickness = 0.6.dp
                        )
                    }
                    item { Spacer(modifier = Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListRow(
    chat: Chat,
    isSelected: Boolean,
    isPinned: Boolean,
    isMuted: Boolean,
    isOnline: Boolean,
    currentUserId: String,
    photoBase64: String,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onDelete: () -> Unit
) {
    val pulseColors = LocalPulseChatColors.current
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.background
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chat_item_${chat.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                name = chat.title,
                colorHex = chat.avatarColorHex,
                photoBase64 = photoBase64,
                size = 52.dp,
                isGroup = chat.isGroup,
                showOnlineBadge = isOnline
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = TimeFormatter.formatChatListTime(chat.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chat.unreadCount > 0) pulseColors.accentGreen else pulseColors.bubbleSubtext
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (chat.lastMessageSenderId == currentUserId) {
                        MessageStatusTick(
                            status = chat.lastMessageStatus,
                            tintDefault = pulseColors.bubbleSubtext
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    when (chat.lastMessageType) {
                        "image" -> {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = pulseColors.bubbleSubtext,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        "voice" -> {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                tint = pulseColors.accentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }

                    Text(
                        text = chat.lastMessageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = pulseColors.bubbleSubtext,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isMuted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.VolumeOff,
                            contentDescription = "Muted",
                            tint = pulseColors.bubbleSubtext,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = pulseColors.bubbleSubtext,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(pulseColors.unreadBadgeBg)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = pulseColors.unreadBadgeText,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Unpin conversation" else "Pin conversation") },
                onClick = {
                    showMenu = false
                    onTogglePin()
                }
            )
            DropdownMenuItem(
                text = { Text(if (isMuted) "Unmute notifications" else "Mute notifications") },
                onClick = {
                    showMenu = false
                    onToggleMute()
                }
            )
            if (chat.createdBy == currentUserId) {
                DropdownMenuItem(
                    text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
fun NewChatDialog(
    publicProfiles: List<PublicProfile>,
    currentUserId: String,
    initialGroupMode: Boolean = false,
    onDismiss: () -> Unit,
    onCreateChat: (title: String, participantIds: List<String>, isGroup: Boolean, initialMsg: String) -> Unit
) {
    var isGroup by remember { mutableStateOf(initialGroupMode) }
    var titleInput by remember { mutableStateOf("") }
    var customPeerId by remember { mutableStateOf("") }
    var initialMessage by remember { mutableStateOf("") }
    val selectedPeers = remember { mutableStateListOf<PublicProfile>() }

    val otherUsers = remember(publicProfiles, currentUserId) {
        publicProfiles.filter { it.userId != currentUserId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isGroup) "New Group Chat" else "New Conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (!isGroup) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isGroup = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Direct", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isGroup) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isGroup = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Group", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text(if (isGroup) "Group name" else "Contact / chat title") },
                    placeholder = { Text(if (isGroup) "e.g. Weekend Trip" else "e.g. Saved Notes or Alex") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_chat_title_input")
                )

                if (otherUsers.isNotEmpty()) {
                    Text(
                        text = "Discoverable PulseChat Users",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LazyColumn(modifier = Modifier.height(140.dp)) {
                        items(otherUsers, key = { it.userId }) { person ->
                            val checked = person in selectedPeers
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isGroup) {
                                            if (checked) selectedPeers.remove(person) else selectedPeers.add(person)
                                        } else {
                                            selectedPeers.clear()
                                            selectedPeers.add(person)
                                            if (titleInput.isBlank()) titleInput = person.displayName
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = person.displayName,
                                    colorHex = person.avatarColorHex,
                                    photoBase64 = person.photoBase64,
                                    size = 36.dp,
                                    showOnlineBadge = person.isOnline
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(person.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        person.about,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isGroup) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                } else if (checked) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = customPeerId,
                        onValueChange = { customPeerId = it.trim() },
                        label = { Text("Recipient User ID (optional)") },
                        placeholder = { Text("Leave blank for Saved Notes / Solo Group") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = initialMessage,
                    onValueChange = { initialMessage = it },
                    label = { Text("First message (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val participantIds = buildList {
                        add(currentUserId)
                        selectedPeers.forEach { add(it.userId) }
                        if (customPeerId.isNotBlank()) add(customPeerId)
                    }.distinct()
                    val finalTitle = titleInput.ifBlank {
                        selectedPeers.firstOrNull()?.displayName ?: if (isGroup) "New Group" else "Saved Notes"
                    }
                    onCreateChat(finalTitle, participantIds, isGroup, initialMessage)
                },
                modifier = Modifier.testTag("confirm_create_chat_button")
            ) {
                Text(if (isGroup) "Create Group" else "Start Chat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
