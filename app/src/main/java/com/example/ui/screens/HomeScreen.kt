package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.CallRecord
import com.example.data.model.Chat
import com.example.data.model.Community
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.PrimaryGreen
import com.example.ui.viewmodel.UiState
import com.example.util.MediaUtils

private data class HomeNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun HomeScreen(
    currentUserId: String,
    userProfile: UserProfile?,
    publicProfiles: List<PublicProfile>,
    chatsState: UiState<List<Chat>>,
    selectedChatId: String?,
    myStatuses: List<StatusUpdate>,
    friendsStatuses: List<StatusUpdate>,
    communitiesState: UiState<List<Community>>,
    callsState: UiState<List<CallRecord>>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenChat: (Chat) -> Unit,
    onCreateChat: (title: String, participantIds: List<String>, isGroup: Boolean, initialMessage: String) -> Unit,
    onTogglePinChat: (Chat) -> Unit,
    onToggleMuteChat: (Chat) -> Unit,
    onDeleteChat: (String) -> Unit,
    onPostStatus: (mediaType: String, caption: String, mediaBase64: String, bgHex: String, privacy: String) -> Unit,
    onMarkStatusViewed: (StatusUpdate) -> Unit,
    onUpdateStatusPrivacy: (String) -> Unit,
    onCreateCommunity: (name: String, description: String, announcement: String) -> Unit,
    onPostAnnouncement: (Community, String) -> Unit,
    onAddGroupToCommunity: (Community, String) -> Unit,
    onStartCall: (peerId: String, peerName: String, callType: String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContacts: () -> Unit,
    onSignOut: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val pulseColors = LocalPulseChatColors.current

    val tabs = remember {
        listOf(
            HomeNavTab("Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
            HomeNavTab("Updates", Icons.Default.Update, Icons.Outlined.Update),
            HomeNavTab("Communities", Icons.Default.Groups, Icons.Outlined.Groups),
            HomeNavTab("Calls", Icons.Default.Call, Icons.Outlined.Call)
        )
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var newChatGroupMode by remember { mutableStateOf(false) }

    val cameraSnapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val base64 = MediaUtils.bitmapToCompressedBase64(bitmap)
            onPostStatus("photo", "Camera update", base64, "#075E54", userProfile?.statusPrivacy ?: "everyone")
            selectedTab = 1
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraSnapLauncher.launch(null)
        else onShowMessage("Camera permission denied.")
    }

    val allChats = (chatsState as? UiState.Success)?.data ?: emptyList()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selectedTab != 1) {
                Surface(
                    color = pulseColors.headerBackground,
                    shadowElevation = 3.dp
                ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_pulsechat_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = pulseColors.headerContent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) cameraSnapLauncher.launch(null)
                                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.testTag("home_camera_button")
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Quick camera",
                                tint = pulseColors.headerContent
                            )
                        }

                        IconButton(
                            onClick = {
                                isSearchOpen = !isSearchOpen
                                if (!isSearchOpen) onSearchQueryChange("")
                            },
                            modifier = Modifier.testTag("home_search_button")
                        ) {
                            Icon(
                                if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = pulseColors.headerContent
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("home_menu_button")
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Menu",
                                    tint = pulseColors.headerContent
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New group") },
                                    onClick = {
                                        showOverflowMenu = false
                                        newChatGroupMode = true
                                        showNewChatDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New community") },
                                    onClick = {
                                        showOverflowMenu = false
                                        selectedTab = 2
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Profile") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenProfile()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSettings()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSignOut()
                                    }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isSearchOpen) {
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = {
                                Text(
                                    "Search chats, updates, calls...",
                                    color = pulseColors.headerContent.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                .testTag("home_search_input")
                        )
                    }
                }
            }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = pulseColors.navContainer,
                tonalElevation = 4.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = pulseColors.navIndicator
                        ),
                        modifier = Modifier.testTag(
                            "nav_tab_${tab.label.lowercase().replace(" ", "_")}"
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = onOpenContacts,
                    containerColor = PrimaryGreen,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("new_chat_fab")
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = "Select contact")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatsTabContent(
                    chatsState = chatsState,
                    publicProfiles = publicProfiles,
                    currentUserId = currentUserId,
                    searchQuery = searchQuery,
                    selectedChatId = selectedChatId,
                    onOpenChat = onOpenChat,
                    onTogglePin = onTogglePinChat,
                    onToggleMute = onToggleMuteChat,
                    onDeleteChat = onDeleteChat,
                    onCreateSelfChat = {
                        onCreateChat(
                            "Saved Notes",
                            listOf(currentUserId),
                            false,
                            "Welcome to your private Saved Notes!"
                        )
                    },
                    onShowNewChatDialog = onOpenContacts
                )
                1 -> UpdatesTabContent(
                    userProfile = userProfile,
                    myStatuses = myStatuses,
                    friendsStatuses = friendsStatuses,
                    publicProfiles = publicProfiles,
                    currentUserId = currentUserId,
                    searchQuery = searchQuery
                )
                2 -> CommunitiesTabContent(
                    communitiesState = communitiesState,
                    allChats = allChats,
                    searchQuery = searchQuery,
                    onCreateCommunity = onCreateCommunity,
                    onPostAnnouncement = onPostAnnouncement,
                    onAddGroupToCommunity = onAddGroupToCommunity,
                    onOpenLinkedChat = onOpenChat
                )
                3 -> CallsTabContent(
                    callsState = callsState,
                    publicProfiles = publicProfiles,
                    currentUserId = currentUserId,
                    searchQuery = searchQuery,
                    onStartCall = onStartCall
                )
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            publicProfiles = publicProfiles,
            currentUserId = currentUserId,
            initialGroupMode = newChatGroupMode,
            onDismiss = { showNewChatDialog = false },
            onCreateChat = { title, participants, isGroup, initialMsg ->
                showNewChatDialog = false
                onCreateChat(title, participants, isGroup, initialMsg)
            }
        )
    }
}
