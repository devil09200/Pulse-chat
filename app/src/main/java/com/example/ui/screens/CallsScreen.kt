package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.CallRecord
import com.example.data.model.PublicProfile
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.MissedCallRed
import com.example.ui.theme.PrimaryGreen
import com.example.ui.viewmodel.UiState
import com.example.util.TimeFormatter

@Composable
fun CallsTabContent(
    callsState: UiState<List<CallRecord>>,
    publicProfiles: List<PublicProfile>,
    currentUserId: String,
    searchQuery: String,
    onStartCall: (peerId: String, peerName: String, callType: String) -> Unit
) {
    val pulseColors = LocalPulseChatColors.current
    var selectedFilter by remember { mutableStateOf("All") }
    var showNewCallDialog by remember { mutableStateOf(false) }

    when (callsState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        is UiState.Error -> {
            EmptyStatePanel(
                icon = Icons.Default.Call,
                title = "Could not load calls",
                subtitle = callsState.message
            )
        }
        is UiState.Success -> {
            val filtered = remember(callsState.data, selectedFilter, searchQuery, currentUserId) {
                callsState.data.filter { call ->
                    val isCaller = call.callerId == currentUserId
                    val isMissedOrRejected = call.status == "missed" ||
                        call.status == "cancelled" ||
                        call.direction == "missed" ||
                        (call.status == "rejected" && !isCaller)
                    val matchesFilter = when (selectedFilter) {
                        "Missed" -> isMissedOrRejected
                        "Incoming" -> !isCaller && !isMissedOrRejected
                        "Outgoing" -> isCaller
                        "Video" -> call.callType == "video"
                        else -> true
                    }
                    val peerName = if (isCaller) call.receiverName else call.callerName
                    val matchesQuery = searchQuery.isBlank() || peerName.contains(searchQuery, ignoreCase = true)
                    matchesFilter && matchesQuery
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listOf("All", "Missed", "Incoming", "Outgoing", "Video")) { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter) }
                            )
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        Box(modifier = Modifier.height(280.dp)) {
                            EmptyStatePanel(
                                icon = Icons.Default.AddIcCall,
                                title = "No recent calls",
                                subtitle = "Place encrypted voice or video calls directly from any chat or tap the call button.",
                                action = {
                                    Button(onClick = { showNewCallDialog = true }) {
                                        Text("Start a Call")
                                    }
                                }
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { call ->
                        val isCaller = call.callerId == currentUserId
                        val peerId = if (isCaller) call.receiverId else call.callerId
                        val peerName = if (isCaller) call.receiverName else call.callerName
                        val isMissed = call.direction == "missed" ||
                            call.status == "missed" ||
                            call.status == "rejected" ||
                            call.status == "cancelled" ||
                            call.status == "failed"
                        val directionIcon = when {
                            isMissed -> Icons.AutoMirrored.Filled.CallMissed
                            isCaller -> Icons.AutoMirrored.Filled.CallMade
                            else -> Icons.AutoMirrored.Filled.CallReceived
                        }
                        val directionTint = if (isMissed) MissedCallRed else PrimaryGreen

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onStartCall(peerId, peerName, call.callType) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(name = peerName, colorHex = "#128C7E", size = 48.dp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isMissed) MissedCallRed else MaterialTheme.colorScheme.onBackground
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = directionIcon,
                                        contentDescription = call.direction,
                                        tint = directionTint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val statusSuffix = when {
                                        call.durationSec > 0 -> " (${TimeFormatter.formatDuration(call.durationSec)})"
                                        call.status == "rejected" -> " • Declined"
                                        call.status == "cancelled" -> " • Cancelled"
                                        call.status == "failed" -> " • Failed"
                                        call.status == "missed" -> " • Missed"
                                        else -> ""
                                    }
                                    Text(
                                        text = "${TimeFormatter.formatChatListTime(call.startedAt ?: call.createdAt)}$statusSuffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = pulseColors.bubbleSubtext
                                    )
                                }
                            }
                            IconButton(onClick = { onStartCall(peerId, peerName, "voice") }) {
                                Icon(Icons.Default.Call, contentDescription = "Voice call", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { onStartCall(peerId, peerName, "video") }) {
                                Icon(Icons.Default.Videocam, contentDescription = "Video call", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }

    if (showNewCallDialog) {
        val callableContacts = remember(publicProfiles, currentUserId) {
            publicProfiles.filter { it.userId != currentUserId }
        }
        var selectedPeer by remember(callableContacts) {
            mutableStateOf(callableContacts.firstOrNull())
        }
        var selectedType by remember { mutableStateOf("voice") }

        AlertDialog(
            onDismissRequest = { showNewCallDialog = false },
            title = { Text("Start New Call") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (callableContacts.isEmpty()) {
                        Text(
                            text = "No registered PulseChat contacts available to call yet. Invite or add a contact first.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        ) {
                            items(callableContacts, key = { it.userId }) { contact ->
                                val isSelected = selectedPeer?.userId == contact.userId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedPeer = contact }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserAvatar(
                                        name = contact.displayName,
                                        colorHex = contact.avatarColorHex,
                                        photoBase64 = contact.photoBase64,
                                        size = 38.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedType == "voice",
                            onClick = { selectedType = "voice" },
                            label = { Text("Voice Call") }
                        )
                        FilterChip(
                            selected = selectedType == "video",
                            onClick = { selectedType = "video" },
                            label = { Text("Video Call") }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedPeer != null,
                    onClick = {
                        selectedPeer?.let { target ->
                            onStartCall(target.userId, target.displayName, selectedType)
                        }
                        showNewCallDialog = false
                    }
                ) { Text("Call") }
            },
            dismissButton = {
                TextButton(onClick = { showNewCallDialog = false }) { Text("Cancel") }
            }
        )
    }
}
