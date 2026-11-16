package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.data.model.Chat
import com.example.data.model.ChatMessage
import com.example.ui.components.ChatWallpaperBackground
import com.example.ui.components.MessageStatusTick
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.MissedCallRed
import com.example.ui.theme.PrimaryGreen
import com.example.ui.viewmodel.UiState
import com.example.util.MediaUtils
import com.example.util.TimeFormatter
import com.example.util.VoicePlayerHelper
import com.example.util.VoiceRecorderHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val QUICK_EMOJIS = listOf(
    "😀", "😂", "❤️", "👍", "🔥", "🎉", "🙏", "😎", "🥳", "😍", "🤔", "👏", "🚀", "✨", "💯", "👋"
)

@Composable
fun ChatScreen(
    chat: Chat,
    messagesState: UiState<List<ChatMessage>>,
    currentUserId: String,
    peerOnlineText: String = "Online",
    peerPhotoBase64: String = "",
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onSendText: (text: String, replyToText: String) -> Unit,
    onSendImage: (base64: String) -> Unit,
    onSendVoice: (base64: String, durationSec: Long) -> Unit,
    onMarkMessagesRead: (List<ChatMessage>) -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onDeleteChat: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    if (showBackButton) {
        BackHandler { onBack() }
    }

    val context = LocalContext.current
    val pulseColors = LocalPulseChatColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var inputText by remember(chat.id) { mutableStateOf("") }
    var showEmojiBar by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }
    var fullScreenImageBase64 by remember { mutableStateOf<String?>(null) }

    // Voice recording & playback
    val recorder = remember { VoiceRecorderHelper(context) }
    val player = remember { VoicePlayerHelper(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var playingMessageId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stopAndDiscard()
            player.stop()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0L
            while (isRecording) {
                delay(1000L)
                recordingSeconds += 1L
            }
        }
    }

    // Android Photo Picker (Zero storage permissions needed!)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val base64 = MediaUtils.uriToCompressedBase64(context, uri)
                if (base64 != null) {
                    onSendImage(base64)
                } else {
                    onShowMessage("Could not process selected image.")
                }
            }
        }
    }

    // Camera quick-capture
    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val base64 = MediaUtils.bitmapToCompressedBase64(bitmap)
            onSendImage(base64)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraCaptureLauncher.launch(null)
        } else {
            onShowMessage("Camera permission is needed to snap a photo.")
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (recorder.startRecording()) {
                isRecording = true
            } else {
                onShowMessage("Unable to access microphone on this device.")
            }
        } else {
            onShowMessage("Microphone permission is needed for voice messages.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pulseColors.chatBackground)
    ) {
        // Header Bar
        Surface(
            color = pulseColors.headerBackground,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackButton) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = pulseColors.headerContent
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(12.dp))
                }

                UserAvatar(
                    name = chat.title,
                    colorHex = chat.avatarColorHex,
                    photoBase64 = peerPhotoBase64,
                    size = 40.dp,
                    isGroup = chat.isGroup,
                    showOnlineBadge = !chat.isGroup && peerOnlineText == "Online"
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = pulseColors.headerContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (chat.isGroup) "${chat.participantIds.size} participants" else peerOnlineText,
                        style = MaterialTheme.typography.bodySmall,
                        color = pulseColors.headerContent.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onStartVideoCall,
                    modifier = Modifier.testTag("chat_video_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video call",
                        tint = pulseColors.headerContent
                    )
                }
                IconButton(
                    onClick = onStartVoiceCall,
                    modifier = Modifier.testTag("chat_voice_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Voice call",
                        tint = pulseColors.headerContent
                    )
                }

                Box {
                    IconButton(
                        onClick = { showTopMenu = true },
                        modifier = Modifier.testTag("chat_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = pulseColors.headerContent
                        )
                    }
                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (currentUserId in chat.pinnedBy) "Unpin chat" else "Pin chat") },
                            onClick = {
                                showTopMenu = false
                                onTogglePin()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (currentUserId in chat.mutedBy) "Unmute chat" else "Mute chat") },
                            onClick = {
                                showTopMenu = false
                                onToggleMute()
                            }
                        )
                        if (chat.createdBy == currentUserId) {
                            DropdownMenuItem(
                                text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showTopMenu = false
                                    onDeleteChat()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Chat Messages Area with Wallpaper
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ChatWallpaperBackground {
                when (messagesState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    is UiState.Error -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = messagesState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    is UiState.Success -> {
                        val messages = messagesState.data
                        LaunchedEffect(messages.size) {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.size - 1)
                                onMarkMessagesRead(messages)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        color = pulseColors.incomingBubble.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(12.dp),
                                        shadowElevation = 0.5.dp
                                    ) {
                                        Text(
                                            text = "Messages are secured in Cloud Firestore.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = pulseColors.bubbleSubtext,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            items(messages, key = { it.id }) { message ->
                                val isMine = message.senderId == currentUserId
                                val isPlaying = playingMessageId == message.id
                                MessageBubble(
                                    message = message,
                                    isMine = isMine,
                                    isGroup = chat.isGroup,
                                    isPlayingVoice = isPlaying,
                                    onReply = { replyingTo = message },
                                    onImageClick = { fullScreenImageBase64 = message.mediaBase64 },
                                    onToggleVoicePlay = {
                                        if (isPlaying) {
                                            player.stop()
                                            playingMessageId = null
                                        } else {
                                            val started = player.playBase64(message.mediaBase64) {
                                                playingMessageId = null
                                            }
                                            if (started) {
                                                playingMessageId = message.id
                                            } else {
                                                onShowMessage("Unable to play audio clip.")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Reply Preview Banner
        AnimatedVisibility(visible = replyingTo != null) {
            val reply = replyingTo
            if (reply != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(36.dp)
                                .background(PrimaryGreen, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = reply.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = reply.text.ifBlank { reply.mediaType.replaceFirstChar { it.uppercase() } },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyingTo = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }
        }

        // Emoji Quick Picker Bar
        AnimatedVisibility(visible = showEmojiBar) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(QUICK_EMOJIS) { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { inputText += emoji }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Bottom Message Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (isRecording) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MissedCallRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Recording ${TimeFormatter.formatDuration(recordingSeconds)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                recorder.stopAndDiscard()
                                isRecording = false
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Discard recording", tint = MissedCallRed)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = CircleShape,
                    color = PrimaryGreen,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .size(50.dp)
                        .clickable {
                            isRecording = false
                            val result = recorder.stopAndGetBase64()
                            if (result != null) {
                                onSendVoice(result.first, result.second)
                            } else {
                                onShowMessage("Recording was empty or too long.")
                            }
                        }
                        .testTag("stop_send_voice_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Stop, contentDescription = "Send voice message", tint = Color.White)
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.5.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showEmojiBar = !showEmojiBar },
                            modifier = Modifier.testTag("emoji_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = "Emoji",
                                tint = pulseColors.bubbleSubtext
                            )
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    "Message",
                                    color = pulseColors.bubbleSubtext
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            maxLines = 4,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input")
                        )

                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("attach_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Attach image",
                                tint = pulseColors.bubbleSubtext
                            )
                        }

                        if (inputText.isBlank()) {
                            IconButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        cameraCaptureLauncher.launch(null)
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.testTag("chat_camera_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Camera",
                                    tint = pulseColors.bubbleSubtext
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = CircleShape,
                    color = PrimaryGreen,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .size(50.dp)
                        .clickable {
                            if (inputText.isNotBlank()) {
                                val replyPreview = replyingTo?.text?.take(120) ?: ""
                                onSendText(inputText, replyPreview)
                                inputText = ""
                                replyingTo = null
                                showEmojiBar = false
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    if (recorder.startRecording()) {
                                        isRecording = true
                                    } else {
                                        onShowMessage("Unable to access microphone on this device.")
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        .testTag(if (inputText.isNotBlank()) "send_message_button" else "record_voice_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (inputText.isNotBlank()) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                            contentDescription = if (inputText.isNotBlank()) "Send" else "Record voice message",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (fullScreenImageBase64 != null) {
        val bmp = remember(fullScreenImageBase64) {
            MediaUtils.decodeBase64ToBitmap(fullScreenImageBase64 ?: "")
        }
        Dialog(onDismissRequest = { fullScreenImageBase64 = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Full image preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    IconButton(
                        onClick = { fullScreenImageBase64 = null },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    isGroup: Boolean,
    isPlayingVoice: Boolean,
    onReply: () -> Unit,
    onImageClick: () -> Unit,
    onToggleVoicePlay: () -> Unit
) {
    val pulseColors = LocalPulseChatColors.current
    val bubbleColor = if (isMine) pulseColors.outgoingBubble else pulseColors.incomingBubble
    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = 84.dp, max = 276.dp)
                .combinedClickable(
                    onClick = {
                        if (message.mediaType == "image") onImageClick()
                        else if (message.mediaType == "voice") onToggleVoicePlay()
                    },
                    onLongClick = onReply
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                if (isGroup && !isMine) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                if (message.replyToText.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = message.replyToText,
                            style = MaterialTheme.typography.bodySmall,
                            color = pulseColors.bubbleSubtext,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                when (message.mediaType) {
                    "image" -> {
                        val bmp = remember(message.mediaBase64) {
                            MediaUtils.decodeBase64ToBitmap(message.mediaBase64)
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Shared photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (message.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = pulseColors.bubbleText
                            )
                        }
                    }
                    "voice" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryGreen)
                                    .clickable { onToggleVoicePlay() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlayingVoice) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingVoice) "Pause voice" else "Play voice",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    listOf(10, 18, 26, 14, 24, 16, 22, 12, 20, 15, 25, 11).forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(h.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    if (isPlayingVoice) PrimaryGreen
                                                    else pulseColors.bubbleSubtext.copy(alpha = 0.55f)
                                                )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = TimeFormatter.formatDuration(message.voiceDurationSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pulseColors.bubbleSubtext
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = pulseColors.bubbleText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TimeFormatter.formatMessageTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = pulseColors.bubbleSubtext
                    )
                    if (isMine) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusTick(
                            status = message.status,
                            tintDefault = pulseColors.bubbleSubtext
                        )
                    }
                }
            }
        }
    }
}
