package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CallRecord
import com.example.data.model.Chat
import com.example.data.model.ChatMessage
import com.example.data.model.Community
import com.example.data.model.PhoneDirectoryEntry
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.data.repository.PulseChatRepository
import com.example.util.PhoneContact
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val repository: PulseChatRepository,
    val currentUserId: String,
    private val initialDisplayName: String,
    private val initialEmail: String
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedChatId = MutableStateFlow<String?>(null)
    val selectedChatId: StateFlow<String?> = _selectedChatId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _checkingContactId = MutableStateFlow<String?>(null)
    val checkingContactId: StateFlow<String?> = _checkingContactId.asStateFlow()

    private fun <T> Flow<T>.toUiStateFlow(label: String): Flow<UiState<T>> =
        map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing $label", rootError)
                emit(UiState.Error(rootError.message ?: "Failed to load $label"))
            }

    val userProfile: StateFlow<UserProfile?> = repository.observeUserProfile()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing userProfile", firestoreError ?: error)
            emit(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    val publicProfiles: StateFlow<List<PublicProfile>> = repository.observePublicProfiles()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing publicProfiles", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val chatsState: StateFlow<UiState<List<Chat>>> = repository.observeChats()
        .toUiStateFlow("chats")
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val activeChatMessagesState: StateFlow<UiState<List<ChatMessage>>> = _selectedChatId
        .flatMapLatest { chatId ->
            if (chatId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeMessages(chatId).toUiStateFlow("messages")
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val myStatuses: StateFlow<List<StatusUpdate>> = repository.observeMyStatuses()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing myStatuses", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val friendsStatuses: StateFlow<List<StatusUpdate>> = repository.observeFriendsStatuses()
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing friendsStatuses", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val communitiesState: StateFlow<UiState<List<Community>>> = repository.observeCommunities()
        .toUiStateFlow("communities")
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val callsState: StateFlow<UiState<List<CallRecord>>> = repository.observeCalls()
        .toUiStateFlow("calls")
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(
                displayName = initialDisplayName,
                email = initialEmail
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectChat(chatId: String?) {
        _selectedChatId.value = chatId
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun saveVerifiedPhone(phoneNumber: String, countryCode: String) {
        viewModelScope.launch {
            val res = repository.ensureUserProfile(
                displayName = initialDisplayName,
                email = initialEmail,
                phoneNumber = phoneNumber,
                countryCode = countryCode
            )
            if (res.isFailure) {
                _statusMessage.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to save phone number"
            }
        }
    }

    fun updateProfile(displayName: String, about: String, phoneNumber: String? = null, photoBase64: String? = null) {
        viewModelScope.launch {
            val updates = mutableMapOf<String, Any>(
                "displayName" to displayName.trim().ifBlank { "PulseChat User" }.take(80),
                "about" to about.trim().take(200)
            )
            if (phoneNumber != null) updates["phoneNumber"] = phoneNumber.trim().take(30)
            if (photoBase64 != null) updates["photoBase64"] = photoBase64.take(500_000)

            val res = repository.updateProfileAndSettings(updates)
            _statusMessage.value = if (res.isSuccess) "Profile updated" else "Failed to update profile"
        }
    }

    fun updateSetting(key: String, value: Any) {
        viewModelScope.launch {
            repository.updateProfileAndSettings(mapOf(key to value))
        }
    }

    fun createChatAndOpen(
        title: String,
        participantIds: List<String>,
        isGroup: Boolean,
        initialMessage: String = "",
        avatarColorHex: String = "#128C7E",
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val res = repository.createOrOpenChat(
                title = title,
                participantIds = participantIds,
                isGroup = isGroup,
                avatarColorHex = avatarColorHex,
                initialMessage = initialMessage
            )
            res.onSuccess { chatId ->
                _selectedChatId.value = chatId
                onCreated(chatId)
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Could not create chat"
            }
        }
    }

    fun checkContactRegistration(
        contact: PhoneContact,
        onResult: (PhoneDirectoryEntry?) -> Unit
    ) {
        if (_checkingContactId.value != null) return
        val defaultCountry = userProfile.value?.countryCode?.ifBlank { "+1" } ?: "+1"
        viewModelScope.launch {
            _checkingContactId.value = contact.contactId
            try {
                val res = repository.lookupRegisteredUserByPhone(
                    rawPhone = contact.phoneNumber,
                    defaultCountryCode = defaultCountry
                )
                res.onSuccess { entry ->
                    onResult(entry)
                }.onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Could not verify contact status"
                }
            } finally {
                _checkingContactId.value = null
            }
        }
    }

    fun findOrOpenPrivateChatWithUser(
        entry: PhoneDirectoryEntry,
        fallbackDisplayName: String,
        onReady: (String) -> Unit
    ) {
        val currentChats = (chatsState.value as? UiState.Success)?.data.orEmpty()
        val existingChat = currentChats.firstOrNull { chat ->
            if (chat.isGroup) return@firstOrNull false
            if (entry.userId == currentUserId) {
                chat.participantIds.size == 1 && currentUserId in chat.participantIds
            } else {
                chat.participantIds.size <= 2 &&
                    currentUserId in chat.participantIds &&
                    entry.userId in chat.participantIds
            }
        }

        if (existingChat != null) {
            _selectedChatId.value = existingChat.id
            onReady(existingChat.id)
        } else {
            val chatTitle = if (entry.userId == currentUserId) {
                "Saved Notes"
            } else {
                entry.displayName.ifBlank { fallbackDisplayName }
            }
            createChatAndOpen(
                title = chatTitle,
                participantIds = listOf(currentUserId, entry.userId).distinct(),
                isGroup = false,
                avatarColorHex = entry.avatarColorHex.ifBlank { "#128C7E" },
                initialMessage = "",
                onCreated = onReady
            )
        }
    }

    fun sendTextMessage(chat: Chat, text: String, replyToText: String = "") {
        if (text.isBlank()) return
        val senderName = userProfile.value?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName
        viewModelScope.launch {
            val res = repository.sendMessage(
                chatId = chat.id,
                participantIds = chat.participantIds,
                senderName = senderName,
                text = text,
                mediaType = "text",
                replyToText = replyToText
            )
            if (res.isFailure) {
                _statusMessage.value = "Failed to send message"
            }
        }
    }

    fun sendImageMessage(chat: Chat, base64Image: String, caption: String = "") {
        val senderName = userProfile.value?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName
        viewModelScope.launch {
            val res = repository.sendMessage(
                chatId = chat.id,
                participantIds = chat.participantIds,
                senderName = senderName,
                text = caption,
                mediaType = "image",
                mediaBase64 = base64Image
            )
            if (res.isFailure) {
                _statusMessage.value = "Failed to send image"
            }
        }
    }

    fun sendVoiceMessage(chat: Chat, base64Audio: String, durationSec: Long) {
        val senderName = userProfile.value?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName
        viewModelScope.launch {
            val res = repository.sendMessage(
                chatId = chat.id,
                participantIds = chat.participantIds,
                senderName = senderName,
                text = "",
                mediaType = "voice",
                mediaBase64 = base64Audio,
                voiceDurationSec = durationSec
            )
            if (res.isFailure) {
                _statusMessage.value = "Failed to send voice message"
            }
        }
    }

    fun markMessagesAsRead(chatId: String, messages: List<ChatMessage>) {
        val readReceipts = userProfile.value?.readReceiptsEnabled ?: true
        if (!readReceipts) return
        viewModelScope.launch {
            messages.filter { currentUserId !in it.readBy || it.status != "read" }
                .takeLast(15)
                .forEach { msg ->
                    repository.markMessageRead(chatId, msg)
                }
        }
    }

    fun togglePinChat(chat: Chat) {
        viewModelScope.launch { repository.togglePinChat(chat) }
    }

    fun toggleMuteChat(chat: Chat) {
        viewModelScope.launch { repository.toggleMuteChat(chat) }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val res = repository.deleteChat(chatId)
            if (res.isSuccess) {
                if (_selectedChatId.value == chatId) _selectedChatId.value = null
                _statusMessage.value = "Chat deleted"
            }
        }
    }

    fun postStatus(
        mediaType: String,
        caption: String,
        mediaBase64: String = "",
        backgroundColorHex: String = "#075E54",
        privacy: String = userProfile.value?.statusPrivacy ?: "everyone"
    ) {
        val profile = userProfile.value
        val name = profile?.displayName?.ifBlank { initialDisplayName } ?: initialDisplayName
        val color = profile?.avatarColorHex ?: "#128C7E"
        viewModelScope.launch {
            val res = repository.postStatusUpdate(
                userName = name,
                avatarColorHex = color,
                mediaType = mediaType,
                caption = caption,
                mediaBase64 = mediaBase64,
                backgroundColorHex = backgroundColorHex,
                privacy = privacy
            )
            _statusMessage.value = if (res.isSuccess) "Status update posted" else "Failed to post status"
        }
    }

    fun markStatusViewed(status: StatusUpdate) {
        viewModelScope.launch { repository.markStatusViewed(status) }
    }

    fun createCommunity(name: String, description: String, announcement: String) {
        viewModelScope.launch {
            val res = repository.createCommunity(name, description, announcement)
            _statusMessage.value = if (res.isSuccess) "Community created" else "Failed to create community"
        }
    }

    fun postAnnouncement(community: Community, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val res = repository.postCommunityAnnouncement(community, text)
            _statusMessage.value = if (res.isSuccess) "Announcement posted" else "Failed to post announcement"
        }
    }

    fun addGroupToCommunity(community: Community, groupName: String) {
        if (groupName.isBlank()) return
        viewModelScope.launch {
            val chatRes = repository.createOrOpenChat(
                title = groupName,
                participantIds = community.memberIds,
                isGroup = true,
                initialMessage = "Group added to ${community.name}"
            )
            chatRes.onSuccess { chatId ->
                repository.linkGroupToCommunity(community, chatId)
                _statusMessage.value = "Group '$groupName' added to community"
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
