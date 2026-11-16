package com.example.calling

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.CallRecord
import com.example.data.model.IceCandidateRecord
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide deduplication tracker so an incoming call is never surfaced twice
 * (e.g. from both FCM push data message and foreground Firestore snapshot listener).
 */
object ActiveCallTracker {
    private val claimedIncomingCallIds = ConcurrentHashMap.newKeySet<String>()
    private val finishedCallIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var currentCallId: String? = null
        private set

    fun tryClaimIncomingCall(callId: String): Boolean {
        if (callId.isBlank() || finishedCallIds.contains(callId) || currentCallId == callId) {
            return false
        }
        return claimedIncomingCallIds.add(callId)
    }

    fun setActiveCall(callId: String?) {
        currentCallId = callId
    }

    fun markCallFinished(callId: String) {
        if (callId.isBlank()) return
        finishedCallIds.add(callId)
        claimedIncomingCallIds.remove(callId)
        if (currentCallId == callId) {
            currentCallId = null
        }
    }
}

class CallSignaling(
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
            ?: throw IllegalStateException("User must be signed in with Google before placing calls.")
    }

    suspend fun saveFcmToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        val sanitized = token.trim().take(512)
        if (sanitized.isBlank()) return
        val userRef = db.collection("users").document(uid)
        try {
            val snap = userRef.get().await()
            if (snap.exists()) {
                userRef.update(
                    mapOf(
                        "fcmToken" to sanitized,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save FCM token to user profile", e)
        }
    }

    suspend fun createOutgoingCall(
        callId: String = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}",
        callerName: String,
        callerPhotoBase64: String = "",
        callerAvatarColorHex: String = "#128C7E",
        receiverId: String,
        receiverName: String,
        callType: String
    ): Result<CallRecord> = runCatching {
        val uid = requireUserId()
        val safeReceiverId = receiverId.trim().take(128)
        require(safeReceiverId.isNotBlank() && safeReceiverId != uid) {
            "Receiver must be another registered user."
        }
        val participants = listOf(uid, safeReceiverId)
        val safeCallType = if (callType == "video") "video" else "voice"
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)

        val payload = hashMapOf<String, Any>(
            "callerId" to uid,
            "callerName" to callerName.trim().ifBlank { "PulseChat User" }.take(80),
            "callerPhotoBase64" to callerPhotoBase64.take(500_000),
            "callerAvatarColorHex" to callerAvatarColorHex.ifBlank { "#128C7E" }.take(16),
            "receiverId" to safeReceiverId,
            "receiverName" to receiverName.trim().ifBlank { "Contact" }.take(80),
            "participantIds" to participants,
            "callType" to safeCallType,
            "direction" to "outgoing",
            "status" to "calling",
            "connectionState" to "new",
            "durationSec" to 0L,
            "sdpOffer" to "",
            "sdpAnswer" to "",
            "failureReason" to "",
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

        CallRecord(
            id = callId,
            callerId = uid,
            callerName = payload["callerName"] as String,
            callerPhotoBase64 = payload["callerPhotoBase64"] as String,
            callerAvatarColorHex = payload["callerAvatarColorHex"] as String,
            receiverId = safeReceiverId,
            receiverName = payload["receiverName"] as String,
            participantIds = participants,
            callType = safeCallType,
            direction = "outgoing",
            status = "calling",
            connectionState = "new"
        )
    }

    suspend fun getCall(callId: String): CallRecord? {
        if (callId.isBlank() || auth.currentUser == null) return null
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                snap.toObject(CallRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read call $callId", e)
            null
        }
    }

    suspend fun sendOffer(callId: String, sdpOffer: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            docRef.update(
                mapOf(
                    "sdpOffer" to sdpOffer.take(32_000),
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

    suspend fun acceptCall(callId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            val snap = docRef.get().await()
            val status = snap.getString("status")
            if (status == "calling" || status == "ringing") {
                val updates = hashMapOf<String, Any>(
                    "status" to "accepted",
                    "connectionState" to "checking",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!snap.contains("startedAt")) {
                    updates["startedAt"] = FieldValue.serverTimestamp()
                }
                docRef.update(updates).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
                throw e
            }
        }
    }

    suspend fun sendAnswer(callId: String, sdpAnswer: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            val snap = docRef.get().await()
            val updates = hashMapOf<String, Any>(
                "sdpAnswer" to sdpAnswer.take(32_000),
                "status" to "accepted",
                "connectionState" to "checking",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!snap.contains("startedAt")) {
                updates["startedAt"] = FieldValue.serverTimestamp()
            }
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun markRinging(callId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            val currentStatus = docRef.get().await().getString("status")
            if (currentStatus == "calling") {
                docRef.update(
                    mapOf(
                        "status" to "ringing",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "markRinging ignored for $callId", e)
        }
    }

    suspend fun markConnected(callId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            docRef.update(
                mapOf(
                    "status" to "connected",
                    "connectionState" to "connected",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                Log.w(TAG, "markConnected ignored for $callId", e)
            }
        }
    }

    suspend fun updateConnectionState(callId: String, connectionState: String) {
        if (auth.currentUser == null || callId.isBlank()) return
        val safeState = when (connectionState) {
            "new", "checking", "connected", "reconnecting", "disconnected", "failed", "closed" -> connectionState
            else -> return
        }
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            docRef.update(
                mapOf(
                    "connectionState" to safeState,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "updateConnectionState($safeState) ignored for $callId", e)
        }
    }

    suspend fun rejectCall(callId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            docRef.update(
                mapOf(
                    "status" to "rejected",
                    "connectionState" to "closed",
                    "durationSec" to 0L,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
                throw e
            }
        }
    }

    suspend fun cancelCall(callId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        try {
            docRef.update(
                mapOf(
                    "status" to "cancelled",
                    "connectionState" to "closed",
                    "durationSec" to 0L,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
                throw e
            }
        }
    }

    suspend fun endCall(
        callId: String,
        durationSec: Long,
        finalStatus: String = "ended",
        failureReason: String = ""
    ): Result<Unit> = runCatching {
        requireUserId()
        val safeStatus = when (finalStatus) {
            "ended", "rejected", "cancelled", "missed", "failed" -> finalStatus
            else -> "ended"
        }
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        val updates = hashMapOf<String, Any>(
            "status" to safeStatus,
            "connectionState" to if (safeStatus == "failed") "failed" else "closed",
            "durationSec" to durationSec.coerceIn(0L, 86_400L),
            "endedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (failureReason.isNotBlank() && safeStatus in setOf("ended", "failed")) {
            updates["failureReason"] = failureReason.take(300)
        }
        if (safeStatus == "missed") {
            updates["direction"] = "missed"
        }
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
                throw e
            }
        }
    }

    suspend fun addIceCandidate(
        callId: String,
        participantIds: List<String>,
        iceCandidate: IceCandidate
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val candId = "ice_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val candRef = db.collection(COLLECTION_CALL_SESSIONS)
            .document(callId)
            .collection("candidates")
            .document(candId)
        val payload = mapOf(
            "callId" to callId,
            "senderId" to uid,
            "participantIds" to participantIds.distinct().take(10),
            "sdpMid" to (iceCandidate.sdpMid ?: "0").take(64),
            "sdpMLineIndex" to iceCandidate.sdpMLineIndex.coerceIn(0, 32),
            "candidate" to iceCandidate.sdp.take(2048),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            candRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTerminalPermissionError(e)) {
                handleFirestoreError(e, OperationType.CREATE, candRef.path, auth)
                throw e
            }
        }
    }

    fun observeCall(callId: String): Flow<CallRecord?> = flow {
        val uid = requireUserId()
        val docRef = db.collection(COLLECTION_CALL_SESSIONS).document(callId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(
                            CallRecord::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "observeCall error for $callId (uid=$uid)", error)
                }
        )
    }

    fun observeRemoteIceCandidates(callId: String): Flow<IceCandidateRecord> = callbackFlow {
        val uid = requireUserId()
        val seenIds = mutableSetOf<String>()
        val registration = db.collection(COLLECTION_CALL_SESSIONS)
            .document(callId)
            .collection("candidates")
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "observeRemoteIceCandidates error for $callId", error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val doc = change.document
                        if (seenIds.add(doc.id)) {
                            val record = doc.toObject(
                                IceCandidateRecord::class.java,
                                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                            )
                            if (record.senderId != uid && record.candidate.isNotBlank()) {
                                trySend(record)
                            }
                        }
                    }
                }
            }
        awaitClose { registration.remove() }
    }

    fun observeIncomingCalls(): Flow<List<CallRecord>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection(COLLECTION_CALL_SESSIONS)
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    val nowMs = System.currentTimeMillis()
                    snapshot.toObjects(
                        CallRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { call ->
                        val createdMs = call.createdAt?.toDate()?.time ?: nowMs
                        val isRecent = (nowMs - createdMs) < 90_000L
                        call.receiverId == uid &&
                            call.callerId != uid &&
                            (call.status == "calling" || call.status == "ringing") &&
                            isRecent
                    }
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "observeIncomingCalls error", error)
                    emit(emptyList())
                }
        )
    }

    private fun isTerminalPermissionError(error: Exception): Boolean {
        val fsErr = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        return fsErr?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    companion object {
        private const val TAG = "CallSignaling"
        const val COLLECTION_CALL_SESSIONS = "call_sessions"
        val TERMINAL_STATUSES = setOf("ended", "rejected", "cancelled", "missed", "failed")
    }
}
