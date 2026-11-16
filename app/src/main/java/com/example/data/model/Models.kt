package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val about: String = "Hey there! I am using PulseChat.",
    val phoneNumber: String = "",
    val countryCode: String = "+1",
    val email: String = "",
    val photoBase64: String = "",
    val avatarColorHex: String = "#128C7E",
    val themeMode: String = "system", // "system", "light", "dark"
    val notificationsEnabled: Boolean = true,
    val readReceiptsEnabled: Boolean = true,
    val lastSeenVisibility: String = "everyone", // "everyone", "contacts", "nobody"
    val statusPrivacy: String = "everyone", // "everyone", "contacts", "contacts_except", "only_share_with", "private"
    val statusExcludedIds: List<String> = emptyList(),
    val statusAllowedIds: List<String> = emptyList(),
    val appLanguage: String = "English",
    val securityLockEnabled: Boolean = false,
    val isOnline: Boolean = true,
    val lastSeenText: String = "Online",
    val fcmToken: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PublicProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val about: String = "",
    val avatarColorHex: String = "#128C7E",
    val photoBase64: String = "",
    val discoverable: Boolean = true,
    val isOnline: Boolean = true,
    val lastSeenText: String = "Online",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Chat(
    @DocumentId val id: String = "",
    val title: String = "",
    val isGroup: Boolean = false,
    val avatarColorHex: String = "#128C7E",
    val participantIds: List<String> = emptyList(),
    val createdBy: String = "",
    val lastMessageText: String = "",
    val lastMessageType: String = "text", // "text", "image", "voice", "call"
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "sent", // "sent", "delivered", "read"
    val pinnedBy: List<String> = emptyList(),
    val mutedBy: List<String> = emptyList(),
    val unreadCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val participantIds: List<String> = emptyList(),
    val text: String = "",
    val mediaType: String = "text", // "text", "image", "voice"
    val mediaBase64: String = "",
    val voiceDurationSec: Long = 0L,
    val status: String = "sent", // "sent", "delivered", "read"
    val readBy: List<String> = emptyList(),
    val replyToText: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StatusUpdate(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val avatarColorHex: String = "#128C7E",
    val mediaType: String = "text", // "text", "photo", "video"
    val caption: String = "",
    val mediaBase64: String = "",
    val mediaUrl: String = "",
    val storagePath: String = "",
    val backgroundColorHex: String = "#075E54",
    val fontStyle: String = "sans", // "sans", "serif", "bold", "mono", "cursive"
    val privacy: String = "everyone", // "everyone", "contacts", "contacts_except", "only_share_with", "private"
    val visibleTo: List<String> = emptyList(),
    val viewedBy: List<String> = emptyList(),
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAt?.toDate()?.time
        if (exp != null) return exp <= nowMs
        val created = createdAt?.toDate()?.time ?: return false
        return nowMs - created >= 86_400_000L
    }
}

@IgnoreExtraProperties
data class Community(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val avatarColorHex: String = "#128C7E",
    val ownerId: String = "",
    val memberIds: List<String> = emptyList(),
    val linkedChatIds: List<String> = emptyList(),
    val announcementText: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CallRecord(
    @DocumentId val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerPhotoBase64: String = "",
    val callerAvatarColorHex: String = "#128C7E",
    val receiverId: String = "",
    val receiverName: String = "",
    val participantIds: List<String> = emptyList(),
    val callType: String = "voice", // "voice", "video"
    val direction: String = "outgoing", // "outgoing", "incoming", "missed"
    val status: String = "calling", // "calling", "ringing", "accepted", "connected", "rejected", "cancelled", "ended", "missed", "failed"
    val connectionState: String = "new", // "new", "checking", "connected", "reconnecting", "disconnected", "failed", "closed"
    val durationSec: Long = 0L,
    val sdpOffer: String = "",
    val sdpAnswer: String = "",
    val failureReason: String = "",
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class IceCandidateRecord(
    @DocumentId val id: String = "",
    val callId: String = "",
    val senderId: String = "",
    val participantIds: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PhoneDirectoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val avatarColorHex: String = "#128C7E",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
