package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.CallRecord
import com.example.data.model.Chat
import com.example.data.model.ChatMessage
import com.example.data.model.Community
import com.example.data.model.PhoneDirectoryEntry
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.util.ContactsHelper
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import android.net.Uri
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import com.google.firebase.storage.storageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class PulseChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun <T> Flow<T>.logFirestoreErrors(
        uid: String,
        operationType: OperationType,
        path: String
    ): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path, auth)
        }
        throw error
    }

    // --- User Profile & Settings ---

    fun observeUserProfile(): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .logFirestoreErrors(uid, OperationType.GET, docRef.path)
        )
    }

    suspend fun ensureUserProfile(
        displayName: String,
        email: String,
        phoneNumber: String = "",
        countryCode: String = "+1"
    ): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val existing = try {
            docRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path, auth)
            throw e
        }

        val safeName = displayName.trim().ifBlank { "PulseChat User" }.take(80)
        val safeColor = AVATAR_COLORS[kotlin.math.abs(uid.hashCode()) % AVATAR_COLORS.size]

        if (!existing.exists()) {
            val userPayload = mapOf(
                "userId" to uid,
                "displayName" to safeName,
                "about" to "Available on PulseChat",
                "phoneNumber" to phoneNumber.trim().take(30),
                "countryCode" to countryCode.trim().ifBlank { "+1" }.take(10),
                "email" to email.trim().take(120),
                "avatarColorHex" to safeColor,
                "themeMode" to "system",
                "notificationsEnabled" to true,
                "readReceiptsEnabled" to true,
                "lastSeenVisibility" to "everyone",
                "statusPrivacy" to "everyone",
                "appLanguage" to "English",
                "securityLockEnabled" to false,
                "isOnline" to true,
                "lastSeenText" to "Online",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.set(userPayload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
                throw e
            }
        } else if (phoneNumber.isNotBlank()) {
            try {
                docRef.update(
                    mapOf(
                        "phoneNumber" to phoneNumber.trim().take(30),
                        "countryCode" to countryCode.trim().ifBlank { "+1" }.take(10),
                        "isOnline" to true,
                        "lastSeenText" to "Online",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
                throw e
            }
        }

        // Sync public profile card (no PII)
        val publicRef = db.collection("public_profiles").document(uid)
        val existingPublic = try {
            publicRef.get().await()
        } catch (e: Exception) {
            null
        }
        val currentUser = docRef.get().await().toObject(
            UserProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ) ?: UserProfile(userId = uid, displayName = safeName, email = email)

        if (existingPublic == null || !existingPublic.exists()) {
            val publicPayload = mapOf(
                "userId" to uid,
                "displayName" to currentUser.displayName.ifBlank { safeName },
                "about" to currentUser.about.ifBlank { "Available on PulseChat" },
                "avatarColorHex" to currentUser.avatarColorHex.ifBlank { safeColor },
                "discoverable" to true,
                "isOnline" to true,
                "lastSeenText" to "Online",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                publicRef.set(publicPayload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, publicRef.path, auth)
            }
        }
        if (currentUser.phoneNumber.isNotBlank()) {
            registerPhoneDirectory(
                phoneNumber = currentUser.phoneNumber,
                countryCode = currentUser.countryCode.ifBlank { "+1" },
                displayName = currentUser.displayName.ifBlank { safeName },
                avatarColorHex = currentUser.avatarColorHex.ifBlank { safeColor }
            )
        }
        currentUser
    }

    suspend fun registerPhoneDirectory(
        phoneNumber: String,
        countryCode: String,
        displayName: String,
        avatarColorHex: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val candidates = ContactsHelper.candidateNormalizedPhones(phoneNumber, countryCode)
        for (normalized in candidates) {
            val hash = ContactsHelper.hashPhoneNumber(normalized)
            val docRef = db.collection("phone_directory").document(hash)
            try {
                val snap = docRef.get().await()
                if (!snap.exists()) {
                    docRef.set(
                        mapOf(
                            "userId" to uid,
                            "displayName" to displayName.trim().ifBlank { "PulseChat User" }.take(80),
                            "avatarColorHex" to avatarColorHex.ifBlank { "#128C7E" }.take(16),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } else if (snap.getString("userId") == uid) {
                    docRef.update(
                        mapOf(
                            "displayName" to displayName.trim().ifBlank { "PulseChat User" }.take(80),
                            "avatarColorHex" to avatarColorHex.ifBlank { "#128C7E" }.take(16),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            }
        }
    }

    suspend fun lookupRegisteredUserByPhone(
        rawPhone: String,
        defaultCountryCode: String = "+1"
    ): Result<PhoneDirectoryEntry?> = runCatching {
        requireUserId()
        val candidates = ContactsHelper.candidateNormalizedPhones(rawPhone, defaultCountryCode)
        for (normalized in candidates) {
            val hash = ContactsHelper.hashPhoneNumber(normalized)
            val docRef = db.collection("phone_directory").document(hash)
            val snap = try {
                docRef.get().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, docRef.path, auth)
                throw e
            }
            if (snap.exists()) {
                val entry = snap.toObject(
                    PhoneDirectoryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (entry != null) return@runCatching entry
            }
        }
        null
    }

    suspend fun updateProfileAndSettings(updates: Map<String, Any>): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val sanitized = updates.toMutableMap().apply {
            put("updatedAt", FieldValue.serverTimestamp())
        }
        try {
            docRef.update(sanitized).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }

        // Mirror non-PII changes to public_profiles
        val publicAllowed = setOf(
            "displayName", "about", "photoBase64", "avatarColorHex", "isOnline", "lastSeenText"
        )
        val publicUpdates = updates.filterKeys { it in publicAllowed }.toMutableMap()
        if (publicUpdates.isNotEmpty()) {
            publicUpdates["updatedAt"] = FieldValue.serverTimestamp()
            val publicRef = db.collection("public_profiles").document(uid)
            try {
                publicRef.update(publicUpdates).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, publicRef.path, auth)
            }
        }

        val updatedPhone = updates["phoneNumber"] as? String
        if (!updatedPhone.isNullOrBlank()) {
            val userSnap = try {
                docRef.get().await().toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            } catch (_: Exception) {
                null
            }
            registerPhoneDirectory(
                phoneNumber = updatedPhone,
                countryCode = userSnap?.countryCode?.ifBlank { "+1" } ?: "+1",
                displayName = userSnap?.displayName?.ifBlank { "PulseChat User" } ?: "PulseChat User",
                avatarColorHex = userSnap?.avatarColorHex?.ifBlank { "#128C7E" } ?: "#128C7E"
            )
        }
    }

    fun observePublicProfiles(): Flow<List<PublicProfile>> = flow {
        val uid = requireUserId()
        val path = "public_profiles"
        emitAll(
            db.collection(path)
                .whereEqualTo("discoverable", true)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.displayName.lowercase() }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    // --- Chats & Real-Time Messages ---

    fun observeChats(): Flow<List<Chat>> = flow {
        val uid = requireUserId()
        val path = "chats"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Chat::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareByDescending<Chat> { uid in it.pinnedBy }
                            .thenByDescending { it.updatedAt ?: Timestamp(0, 0) }
                    )
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createOrOpenChat(
        title: String,
        participantIds: List<String>,
        isGroup: Boolean,
        avatarColorHex: String = "#128C7E",
        initialMessage: String = ""
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val allParticipants = (participantIds + uid).distinct().take(50)
        val chatId = if (!isGroup && allParticipants.size <= 2) {
            val sorted = allParticipants.sorted()
            if (sorted.size == 1) "self_${sorted.first()}".take(120)
            else "dm_${sorted[0]}_${sorted[1]}".take(120)
        } else {
            "grp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        }

        val chatRef = db.collection("chats").document(chatId)
        val existingDoc = try {
            chatRef.get().await()
        } catch (e: Exception) {
            null
        }

        if (existingDoc != null && existingDoc.exists()) {
            return@runCatching chatId
        }

        val preview = initialMessage.ifBlank {
            if (isGroup) "Group created" else "Started conversation"
        }.take(500)

        val payload = mapOf(
            "title" to title.trim().ifBlank { "New Chat" }.take(100),
            "isGroup" to isGroup,
            "avatarColorHex" to avatarColorHex.ifBlank { "#128C7E" }.take(16),
            "participantIds" to allParticipants,
            "createdBy" to uid,
            "lastMessageText" to preview,
            "lastMessageType" to "text",
            "lastMessageSenderId" to uid,
            "lastMessageStatus" to "sent",
            "pinnedBy" to emptyList<String>(),
            "mutedBy" to emptyList<String>(),
            "unreadCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            chatRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, chatRef.path, auth)
            throw e
        }

        if (initialMessage.isNotBlank()) {
            sendMessage(
                chatId = chatId,
                participantIds = allParticipants,
                senderName = auth.currentUser?.displayName ?: "You",
                text = initialMessage
            ).getOrThrow()
        }
        chatId
    }

    suspend fun togglePinChat(chat: Chat): Result<Unit> = runCatching {
        val uid = requireUserId()
        val updatedPins = if (uid in chat.pinnedBy) chat.pinnedBy - uid else (chat.pinnedBy + uid).distinct()
        val docRef = db.collection("chats").document(chat.id)
        try {
            docRef.update(
                mapOf(
                    "pinnedBy" to updatedPins.take(50),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun toggleMuteChat(chat: Chat): Result<Unit> = runCatching {
        val uid = requireUserId()
        val updatedMutes = if (uid in chat.mutedBy) chat.mutedBy - uid else (chat.mutedBy + uid).distinct()
        val docRef = db.collection("chats").document(chat.id)
        try {
            docRef.update(
                mapOf(
                    "mutedBy" to updatedMutes.take(50),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteChat(chatId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("chats").document(chatId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    fun observeMessages(chatId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "chats/$chatId/messages"
        emitAll(
            db.collection("chats").document(chatId).collection("messages")
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(Long.MAX_VALUE, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun sendMessage(
        chatId: String,
        participantIds: List<String>,
        senderName: String,
        text: String,
        mediaType: String = "text",
        mediaBase64: String = "",
        voiceDurationSec: Long = 0L,
        replyToText: String = ""
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(messageId)
        val safeParticipants = (participantIds + uid).distinct().take(50)

        val payload = mapOf(
            "chatId" to chatId,
            "senderId" to uid,
            "senderName" to senderName.trim().ifBlank { "PulseChat User" }.take(80),
            "participantIds" to safeParticipants,
            "text" to text.trim().take(4000),
            "mediaType" to mediaType,
            "mediaBase64" to mediaBase64.take(600_000),
            "voiceDurationSec" to voiceDurationSec.coerceIn(0L, 600L),
            "status" to "delivered",
            "readBy" to listOf(uid),
            "replyToText" to replyToText.take(500),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            msgRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path, auth)
            throw e
        }

        val previewText = when (mediaType) {
            "image" -> if (text.isNotBlank()) "Photo: $text" else "Photo"
            "voice" -> "Voice message (${voiceDurationSec}s)"
            else -> text.trim()
        }.take(500)

        val chatRef = db.collection("chats").document(chatId)
        try {
            chatRef.update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to mediaType,
                    "lastMessageSenderId" to uid,
                    "lastMessageStatus" to "delivered",
                    "unreadCount" to 0L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path, auth)
        }
        messageId
    }

    suspend fun markMessageRead(chatId: String, message: ChatMessage): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (uid in message.readBy && message.status == "read") return@runCatching
        val updatedReadBy = (message.readBy + uid).distinct().take(50)
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        try {
            msgRef.update(
                mapOf(
                    "status" to "read",
                    "readBy" to updatedReadBy,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path, auth)
            throw e
        }
    }

    // --- Status Updates ---

    fun observeMyStatuses(): Flow<List<StatusUpdate>> = flow {
        val uid = requireUserId()
        val path = "statuses"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    val nowMs = System.currentTimeMillis()
                    snapshot.toObjects(
                        StatusUpdate::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { !it.isExpired(nowMs) }
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    fun observeFriendsStatuses(): Flow<List<StatusUpdate>> = flow {
        val uid = requireUserId()
        val path = "statuses"
        val publicFlow = db.collection(path)
            .whereEqualTo("privacy", "everyone")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    StatusUpdate::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(uid, OperationType.LIST, path)

        val sharedFlow = db.collection(path)
            .whereArrayContains("visibleTo", uid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    StatusUpdate::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .logFirestoreErrors(uid, OperationType.LIST, path)

        emitAll(
            combine(publicFlow, sharedFlow) { publicList, sharedList ->
                val nowMs = System.currentTimeMillis()
                (publicList + sharedList)
                    .distinctBy { it.id }
                    .filter { it.userId != uid && !it.isExpired(nowMs) }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
        )
    }

    suspend fun uploadStatusImageBytes(
        jpegBytes: ByteArray,
        statusId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Pair<String, String>> = runCatching {
        val uid = requireUserId()
        val storagePath = "statuses/$uid/${statusId}.jpg"
        val ref = Firebase.storage.reference.child(storagePath)
        val metadata = storageMetadata { contentType = "image/jpeg" }
        val task = ref.putBytes(jpegBytes, metadata)
        task.addOnProgressListener { snap ->
            if (snap.totalByteCount > 0L) {
                val pct = ((snap.bytesTransferred * 100L) / snap.totalByteCount).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        }
        task.await()
        onProgress(100)
        val downloadUrl = ref.downloadUrl.await().toString()
        downloadUrl to storagePath
    }

    suspend fun uploadStatusVideoUri(
        videoUri: Uri,
        statusId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Pair<String, String>> = runCatching {
        val uid = requireUserId()
        val storagePath = "statuses/$uid/${statusId}.mp4"
        val ref = Firebase.storage.reference.child(storagePath)
        val metadata = storageMetadata { contentType = "video/mp4" }
        val task = ref.putFile(videoUri, metadata)
        task.addOnProgressListener { snap ->
            if (snap.totalByteCount > 0L) {
                val pct = ((snap.bytesTransferred * 100L) / snap.totalByteCount).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        }
        task.await()
        onProgress(100)
        val downloadUrl = ref.downloadUrl.await().toString()
        downloadUrl to storagePath
    }

    suspend fun postStatusUpdate(
        userName: String,
        avatarColorHex: String,
        mediaType: String,
        caption: String,
        mediaBase64: String = "",
        mediaUrl: String = "",
        storagePath: String = "",
        backgroundColorHex: String = "#075E54",
        fontStyle: String = "sans",
        privacy: String = "everyone",
        visibleTo: List<String> = emptyList(),
        statusIdOverride: String? = null
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val statusId = statusIdOverride?.takeIf { it.isNotBlank() }
            ?: "st_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("statuses").document(statusId)
        val normalizedRecipients = (visibleTo + uid).distinct().take(100)
        val safeFont = if (fontStyle in listOf("sans", "serif", "bold", "mono", "cursive")) fontStyle else "sans"
        val safePrivacy = if (privacy in listOf("everyone", "contacts", "contacts_except", "only_share_with", "private")) {
            privacy
        } else {
            "everyone"
        }
        val expiresAt = Timestamp(Date(System.currentTimeMillis() + 86_400_000L))
        val payload = mapOf(
            "userId" to uid,
            "userName" to userName.trim().ifBlank { "PulseChat User" }.take(80),
            "avatarColorHex" to avatarColorHex.ifBlank { "#128C7E" }.take(16),
            "mediaType" to mediaType,
            "caption" to caption.trim().take(700),
            "mediaBase64" to mediaBase64.take(600_000),
            "mediaUrl" to mediaUrl.take(2048),
            "storagePath" to storagePath.take(512),
            "backgroundColorHex" to backgroundColorHex.ifBlank { "#075E54" }.take(16),
            "fontStyle" to safeFont,
            "privacy" to safePrivacy,
            "visibleTo" to normalizedRecipients,
            "viewedBy" to emptyList<String>(),
            "expiresAt" to expiresAt,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
        statusId
    }

    suspend fun markStatusViewed(status: StatusUpdate): Result<Unit> = runCatching {
        val uid = requireUserId()
        if (uid in status.viewedBy) return@runCatching
        val docRef = db.collection("statuses").document(status.id)
        try {
            docRef.update(
                mapOf(
                    "viewedBy" to (status.viewedBy + uid).distinct().take(100),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteStatusUpdate(status: StatusUpdate): Result<Unit> = runCatching {
        requireUserId()
        if (status.storagePath.isNotBlank()) {
            try {
                Firebase.storage.reference.child(status.storagePath).delete().await()
            } catch (_: Exception) {
                // Best-effort storage cleanup; always remove metadata document
            }
        }
        val docRef = db.collection("statuses").document(status.id)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateStatusPrivacySettings(
        privacy: String,
        excludedIds: List<String>,
        allowedIds: List<String>
    ): Result<Unit> {
        return updateProfileAndSettings(
            mapOf(
                "statusPrivacy" to privacy,
                "statusExcludedIds" to excludedIds.distinct().take(100),
                "statusAllowedIds" to allowedIds.distinct().take(100)
            )
        )
    }

    suspend fun sendPrivateStatusReply(
        targetUserId: String,
        targetUserName: String,
        targetAvatarColorHex: String,
        senderName: String,
        statusSummary: String,
        replyText: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val chatId = createOrOpenChat(
            title = targetUserName.ifBlank { "PulseChat User" },
            participantIds = listOf(uid, targetUserId).distinct(),
            isGroup = false,
            avatarColorHex = targetAvatarColorHex.ifBlank { "#128C7E" },
            initialMessage = ""
        ).getOrThrow()

        sendMessage(
            chatId = chatId,
            participantIds = listOf(uid, targetUserId).distinct(),
            senderName = senderName,
            text = replyText,
            mediaType = "text",
            replyToText = "Status: ${statusSummary.take(120)}"
        ).getOrThrow()
        chatId
    }

    // --- Communities ---

    fun observeCommunities(): Flow<List<Community>> = flow {
        val uid = requireUserId()
        val path = "communities"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Community::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createCommunity(
        name: String,
        description: String,
        announcementText: String,
        linkedChatIds: List<String> = emptyList()
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val commId = "comm_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("communities").document(commId)
        val color = AVATAR_COLORS[kotlin.math.abs(name.hashCode()) % AVATAR_COLORS.size]
        val payload = mapOf(
            "name" to name.trim().ifBlank { "New Community" }.take(100),
            "description" to description.trim().take(500),
            "avatarColorHex" to color,
            "ownerId" to uid,
            "memberIds" to listOf(uid),
            "linkedChatIds" to linkedChatIds.take(20),
            "announcementText" to announcementText.trim().ifBlank { "Welcome to ${name.trim()}!" }.take(1000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, auth)
            throw e
        }
        commId
    }

    suspend fun postCommunityAnnouncement(community: Community, announcement: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("communities").document(community.id)
        try {
            docRef.update(
                mapOf(
                    "announcementText" to announcement.trim().take(1000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun linkGroupToCommunity(community: Community, chatId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("communities").document(community.id)
        val updatedGroups = (community.linkedChatIds + chatId).distinct().take(20)
        try {
            docRef.update(
                mapOf(
                    "linkedChatIds" to updatedGroups,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    // --- Calls & WebRTC Signaling ---

    fun observeCalls(): Flow<List<CallRecord>> = flow {
        val uid = requireUserId()
        val path = "call_sessions"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    companion object {
        private val AVATAR_COLORS = listOf(
            "#128C7E", "#075E54", "#25D366", "#1D6F8A", "#5B6AD0", "#B54D7C", "#C97A2E"
        )
    }
}
