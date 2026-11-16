package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.calling.ActiveCallTracker
import com.example.calling.CallActivity
import com.example.calling.CallNotificationHelper
import com.example.calling.CallSignaling
import com.example.calling.IncomingCallActivity
import com.example.data.repository.PulseChatRepository
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ContactsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.PulseChatTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_CHAT = "chat/{chatId}"
const val ROUTE_CONTACTS = "contacts"
const val ROUTE_PROFILE = "profile"
const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )
        setContent {
            PulseChatApp()
        }
    }
}

@Composable
fun PulseChatApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var showSplash by rememberSaveable { mutableStateOf(true) }
    var themeModeOverride by rememberSaveable { mutableStateOf("system") }
    var autoSignInAttempted by rememberSaveable { mutableStateOf(false) }

    var pendingPhoneNumber by rememberSaveable { mutableStateOf("") }
    var pendingCountryCode by rememberSaveable { mutableStateOf("+1") }

    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(showSplash) {
        if (!showSplash && !autoSignInAttempted && auth.currentUser == null) {
            autoSignInAttempted = true
            delay(350L)
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    PulseChatTheme(themeMode = themeModeOverride) {
        MobilePortraitViewport {
            Crossfade(targetState = showSplash, label = "splashTransition") { splashing ->
                if (splashing) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                    val user = currentUser
                    if (user == null) {
                        LoginScreen(
                            isSignedInNeedingPhone = false,
                            onGoogleAuthSuccess = { phoneEntered, countryCode ->
                                val digits = phoneEntered.filter { it.isDigit() }
                                if (digits.length >= 6) {
                                    pendingPhoneNumber = "${countryCode.trim().ifBlank { "+1" }} ${phoneEntered.trim()}"
                                    pendingCountryCode = countryCode.trim().ifBlank { "+1" }
                                }
                            },
                            onPhoneOtpVerified = { _, _ -> },
                            onSkipPhone = {}
                        )
                    } else {
                        key(user.uid) {
                            AuthenticatedAppContent(
                                currentUserId = user.uid,
                                displayName = user.displayName ?: "PulseChat User",
                                email = user.email ?: "",
                                pendingPhoneNumber = pendingPhoneNumber,
                                pendingCountryCode = pendingCountryCode,
                                onClearPendingPhone = { pendingPhoneNumber = "" },
                                onThemeModeChanged = { themeModeOverride = it },
                                onSignOut = {
                                    signOutUser(credentialManager, scope)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobilePortraitViewport(
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B141A)),
        contentAlignment = Alignment.Center
    ) {
        val isCompactPhoneWidth = maxWidth <= 480.dp
        val containerModifier = if (isCompactPhoneWidth) {
            Modifier.fillMaxSize()
        } else {
            // Keep wide viewports constrained to a 9:16 portrait mobile layout without stretching
            Modifier
                .fillMaxHeight()
                .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .widthIn(min = 340.dp, max = 430.dp)
        }
        Box(modifier = containerModifier) {
            content()
        }
    }
}

@Composable
private fun AuthenticatedAppContent(
    currentUserId: String,
    displayName: String,
    email: String,
    pendingPhoneNumber: String,
    pendingCountryCode: String,
    onClearPendingPhone: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val viewModel: MainViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MainViewModel(
                    repository = PulseChatRepository(db),
                    currentUserId = currentUserId,
                    initialDisplayName = displayName,
                    initialEmail = email
                )
            }
        }
    )

    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val publicProfiles by viewModel.publicProfiles.collectAsStateWithLifecycle()
    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val selectedChatId by viewModel.selectedChatId.collectAsStateWithLifecycle()
    val activeMessagesState by viewModel.activeChatMessagesState.collectAsStateWithLifecycle()
    val myStatuses by viewModel.myStatuses.collectAsStateWithLifecycle()
    val friendsStatuses by viewModel.friendsStatuses.collectAsStateWithLifecycle()
    val communitiesState by viewModel.communitiesState.collectAsStateWithLifecycle()
    val callsState by viewModel.callsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val checkingContactId by viewModel.checkingContactId.collectAsStateWithLifecycle()

    LaunchedEffect(currentUserId, pendingPhoneNumber) {
        if (pendingPhoneNumber.isNotBlank()) {
            viewModel.saveVerifiedPhone(pendingPhoneNumber, pendingCountryCode)
            onClearPendingPhone()
        }
    }

    LaunchedEffect(userProfile?.themeMode) {
        val mode = userProfile?.themeMode
        if (!mode.isNullOrBlank()) {
            onThemeModeChanged(mode)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val callSignaling = remember(context) { CallSignaling(context) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(currentUserId) {
        CallNotificationHelper.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(currentUserId, userProfile != null) {
        if (userProfile != null) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    coroutineScope.launch {
                        callSignaling.saveFcmToken(token)
                    }
                }
            }
        }
    }

    LaunchedEffect(currentUserId) {
        callSignaling.observeIncomingCalls().collect { incomingCalls ->
            val call = incomingCalls.firstOrNull() ?: return@collect
            if (ActiveCallTracker.tryClaimIncomingCall(call.id)) {
                context.startActivity(
                    IncomingCallActivity.createIntent(
                        context = context,
                        callId = call.id,
                        callerId = call.callerId,
                        callerName = call.callerName,
                        callType = call.callType,
                        callerPhotoBase64 = call.callerPhotoBase64,
                        callerAvatarColorHex = call.callerAvatarColorHex
                    )
                )
            }
        }
    }

    val launchRealCall: (peerId: String, peerName: String, callType: String) -> Unit = { peerId, peerName, callType ->
        if (peerId.isBlank() || peerId == currentUserId) {
            coroutineScope.launchSafe(snackbarHostState, "Select another contact to place a call.")
        } else {
            val peerProfile = publicProfiles.firstOrNull { it.userId == peerId }
            context.startActivity(
                CallActivity.createOutgoingIntent(
                    context = context,
                    peerId = peerId,
                    peerName = peerName,
                    callType = callType,
                    peerPhotoBase64 = peerProfile?.photoBase64.orEmpty(),
                    peerAvatarColorHex = peerProfile?.avatarColorHex ?: "#128C7E",
                    callerName = userProfile?.displayName?.ifBlank { displayName } ?: displayName,
                    callerPhotoBase64 = userProfile?.photoBase64.orEmpty(),
                    callerAvatarColorHex = userProfile?.avatarColorHex ?: "#128C7E"
                )
            )
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { _ ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_HOME,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(ROUTE_HOME) {
                    HomeScreen(
                        currentUserId = currentUserId,
                        userProfile = userProfile,
                        publicProfiles = publicProfiles,
                        chatsState = chatsState,
                        selectedChatId = selectedChatId,
                        myStatuses = myStatuses,
                        friendsStatuses = friendsStatuses,
                        communitiesState = communitiesState,
                        callsState = callsState,
                        searchQuery = searchQuery,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onOpenChat = { chat ->
                            viewModel.selectChat(chat.id)
                            navController.navigate("chat/${chat.id}")
                        },
                        onCreateChat = { title, participants, isGroup, initialMsg ->
                            viewModel.createChatAndOpen(title, participants, isGroup, initialMsg) { chatId ->
                                navController.navigate("chat/$chatId")
                            }
                        },
                        onTogglePinChat = viewModel::togglePinChat,
                        onToggleMuteChat = viewModel::toggleMuteChat,
                        onDeleteChat = viewModel::deleteChat,
                        onPostStatus = viewModel::postStatus,
                        onMarkStatusViewed = viewModel::markStatusViewed,
                        onUpdateStatusPrivacy = { privacy -> viewModel.updateSetting("statusPrivacy", privacy) },
                        onCreateCommunity = viewModel::createCommunity,
                        onPostAnnouncement = viewModel::postAnnouncement,
                        onAddGroupToCommunity = viewModel::addGroupToCommunity,
                        onStartCall = launchRealCall,
                        onOpenProfile = { navController.navigate(ROUTE_PROFILE) },
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenContacts = { navController.navigate(ROUTE_CONTACTS) },
                        onSignOut = onSignOut,
                        onShowMessage = { msg ->
                            coroutineScope.launchSafe(snackbarHostState, msg)
                        }
                    )
                }

                composable(ROUTE_CONTACTS) {
                    ContactsScreen(
                        currentUserId = currentUserId,
                        defaultCountryCode = userProfile?.countryCode?.ifBlank { "+1" } ?: "+1",
                        publicProfiles = publicProfiles,
                        checkingContactId = checkingContactId,
                        onBack = { navController.popBackStack() },
                        onCheckContactRegistration = viewModel::checkContactRegistration,
                        onOpenRegisteredChat = { entry, contact ->
                            viewModel.findOrOpenPrivateChatWithUser(
                                entry = entry,
                                fallbackDisplayName = contact.displayName
                            ) { chatId ->
                                navController.navigate("chat/$chatId") {
                                    popUpTo(ROUTE_CONTACTS) { inclusive = true }
                                }
                            }
                        },
                        onCreateChat = { title, participants, isGroup, initialMsg ->
                            viewModel.createChatAndOpen(title, participants, isGroup, initialMsg) { chatId ->
                                navController.navigate("chat/$chatId") {
                                    popUpTo(ROUTE_CONTACTS) { inclusive = true }
                                }
                            }
                        },
                        onShowMessage = { msg ->
                            coroutineScope.launchSafe(snackbarHostState, msg)
                        }
                    )
                }

                composable(
                    route = ROUTE_CHAT,
                    arguments = listOf(navArgument("chatId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
                    LaunchedEffect(chatId) {
                        viewModel.selectChat(chatId)
                    }
                    val allChats = (chatsState as? UiState.Success)?.data ?: emptyList()
                    val chat = allChats.firstOrNull { it.id == chatId }
                    if (chat != null) {
                        val otherId = chat.participantIds.firstOrNull { it != currentUserId }
                        val otherProfile = publicProfiles.firstOrNull { it.userId == otherId }
                        ChatScreen(
                            chat = chat,
                            messagesState = activeMessagesState,
                            currentUserId = currentUserId,
                            peerOnlineText = otherProfile?.lastSeenText ?: "Online",
                            peerPhotoBase64 = otherProfile?.photoBase64 ?: "",
                            showBackButton = true,
                            onBack = { navController.popBackStack() },
                            onSendText = { text, reply -> viewModel.sendTextMessage(chat, text, reply) },
                            onSendImage = { b64 -> viewModel.sendImageMessage(chat, b64) },
                            onSendVoice = { b64, dur -> viewModel.sendVoiceMessage(chat, b64, dur) },
                            onMarkMessagesRead = { msgs -> viewModel.markMessagesAsRead(chat.id, msgs) },
                            onStartVoiceCall = {
                                launchRealCall(otherId.orEmpty(), chat.title, "voice")
                            },
                            onStartVideoCall = {
                                launchRealCall(otherId.orEmpty(), chat.title, "video")
                            },
                            onTogglePin = { viewModel.togglePinChat(chat) },
                            onToggleMute = { viewModel.toggleMuteChat(chat) },
                            onDeleteChat = {
                                viewModel.deleteChat(chat.id)
                                navController.popBackStack()
                            },
                            onShowMessage = { msg ->
                                coroutineScope.launchSafe(snackbarHostState, msg)
                            }
                        )
                    }
                }

                composable(ROUTE_PROFILE) {
                    ProfileScreen(
                        userProfile = userProfile,
                        onBack = { navController.popBackStack() },
                        onUpdateProfile = viewModel::updateProfile,
                        onToggleOnline = { online ->
                            viewModel.updateSetting("isOnline", online)
                            viewModel.updateSetting("lastSeenText", if (online) "Online" else "Last seen recently")
                        },
                        onOpenPrivacySettings = { navController.navigate(ROUTE_SETTINGS) }
                    )
                }

                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        userProfile = userProfile,
                        currentThemeMode = userProfile?.themeMode ?: "system",
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { navController.navigate(ROUTE_PROFILE) },
                        onUpdateThemeMode = { mode ->
                            onThemeModeChanged(mode)
                            viewModel.updateSetting("themeMode", mode)
                        },
                        onUpdateSetting = viewModel::updateSetting,
                        onSignOut = onSignOut
                    )
                }
            }
        }
    }
}

private fun CoroutineScope.launchSafe(
    snackbarHostState: SnackbarHostState,
    message: String
) {
    launch {
        snackbarHostState.showSnackbar(message)
    }
}
