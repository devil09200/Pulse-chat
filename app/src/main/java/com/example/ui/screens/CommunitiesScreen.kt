package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Chat
import com.example.data.model.Community
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.viewmodel.UiState

@Composable
fun CommunitiesTabContent(
    communitiesState: UiState<List<Community>>,
    allChats: List<Chat>,
    searchQuery: String,
    onCreateCommunity: (name: String, description: String, announcement: String) -> Unit,
    onPostAnnouncement: (Community, String) -> Unit,
    onAddGroupToCommunity: (Community, String) -> Unit,
    onOpenLinkedChat: (Chat) -> Unit
) {
    val pulseColors = LocalPulseChatColors.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var announcementTarget by remember { mutableStateOf<Community?>(null) }
    var addGroupTarget by remember { mutableStateOf<Community?>(null) }

    when (communitiesState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        is UiState.Error -> {
            EmptyStatePanel(
                icon = Icons.Default.Groups,
                title = "Could not load communities",
                subtitle = communitiesState.message
            )
        }
        is UiState.Success -> {
            val filtered = remember(communitiesState.data, searchQuery) {
                if (searchQuery.isBlank()) communitiesState.data
                else communitiesState.data.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCreateDialog = true }
                            .padding(16.dp)
                            .testTag("create_community_banner"),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "New Community",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "New Community",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Organize topic groups & broadcast announcements",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        Box(modifier = Modifier.height(260.dp)) {
                            EmptyStatePanel(
                                icon = Icons.Default.Groups,
                                title = "No communities yet",
                                subtitle = "Create a community to manage related group chats and post announcements."
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { community ->
                        val linkedChats = remember(allChats, community.linkedChatIds) {
                            allChats.filter { it.id in community.linkedChatIds }
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    UserAvatar(
                                        name = community.name,
                                        colorHex = community.avatarColorHex,
                                        size = 48.dp,
                                        isGroup = true
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = community.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (community.description.isNotBlank()) {
                                            Text(
                                                text = community.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Announcement Box
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Campaign,
                                            contentDescription = "Announcement",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Announcements",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = community.announcementText,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }

                                if (linkedChats.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Linked Groups (${linkedChats.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = pulseColors.bubbleSubtext
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    linkedChats.forEach { groupChat ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onOpenLinkedChat(groupChat) }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            UserAvatar(
                                                name = groupChat.title,
                                                colorHex = groupChat.avatarColorHex,
                                                size = 34.dp,
                                                isGroup = true
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(groupChat.title, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    groupChat.lastMessageText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = pulseColors.bubbleSubtext,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    color = pulseColors.divider
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { announcementTarget = community },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Announce", maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(
                                        onClick = { addGroupTarget = community },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Add Group", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var announcement by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Community") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Community name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("community_name_input")
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = announcement,
                        onValueChange = { announcement = it },
                        label = { Text("Welcome announcement") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateCommunity(name, desc, announcement)
                            showCreateDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_create_community_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (announcementTarget != null) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { announcementTarget = null },
            title = { Text("Post Announcement") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Announcement message") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (text.isNotBlank()) {
                        onPostAnnouncement(announcementTarget!!, text)
                        announcementTarget = null
                    }
                }) { Text("Broadcast") }
            },
            dismissButton = {
                TextButton(onClick = { announcementTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (addGroupTarget != null) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addGroupTarget = null },
            title = { Text("Add Group to ${addGroupTarget!!.name}") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("New group name") },
                    placeholder = { Text("e.g. General Discussion") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (groupName.isNotBlank()) {
                        onAddGroupToCommunity(addGroupTarget!!, groupName)
                        addGroupTarget = null
                    }
                }) { Text("Add Group") }
            },
            dismissButton = {
                TextButton(onClick = { addGroupTarget = null }) { Text("Cancel") }
            }
        )
    }
}
