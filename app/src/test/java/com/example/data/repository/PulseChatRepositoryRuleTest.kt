package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.calling.CallSignaling
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.webrtc.IceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PulseChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureUserProfile_authenticatedUser_createsAndObservesProfile() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = PulseChatRepository(firestore, auth)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice Pulse", ALICE_EMAIL, "+15550101", "+1")
        }
        assertTrue(result.isSuccess)
        val profile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserProfile().first { it != null }
        }
        assertNotNull(profile)
        assertEquals(uid, profile?.userId)
        assertEquals("Alice Pulse", profile?.displayName)
    }

    @Test
    fun createChatAndSendMessage_authenticatedParticipants_emitsRealtimeMessages() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        signInTestUser(ALICE_EMAIL)

        val aliceRepo = PulseChatRepository(firestore, auth)
        val chatTitle = "Project Pulse ${UUID.randomUUID().toString().take(6)}"
        val chatId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createOrOpenChat(
                title = chatTitle,
                participantIds = listOf(aliceUid, bobUid),
                isGroup = true,
                initialMessage = "Hello team!"
            ).getOrThrow()
        }

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeMessages(chatId).first { it.isNotEmpty() }
        }
        assertEquals(1, messages.size)
        assertEquals("Hello team!", messages.first().text)

        // Bob can observe the shared chat and messages
        signInTestUser(BOB_EMAIL)
        val bobRepo = PulseChatRepository(firestore, auth)
        val bobChats = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeChats().first { list -> list.any { it.id == chatId } }
        }
        assertTrue(bobChats.any { it.id == chatId })
    }

    @Test
    fun getOtherUsersChat_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = PulseChatRepository(firestore, auth)
        val chatId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createOrOpenChat(
                title = "Alice Private Notes",
                participantIds = listOf(aliceUid),
                isGroup = false,
                initialMessage = "Secret note"
            ).getOrThrow()
        }

        signInTestUser(CHARLIE_EMAIL)
        var capturedError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("chats").document(chatId).get().await()
            }
        } catch (e: Exception) {
            capturedError = e.unwrapFirestoreException()
        }
        assertNotNull(capturedError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, capturedError?.code)
    }

    @Test
    fun observeChats_unauthenticatedUser_failsBeforeQuery() = runBlocking {
        auth.signOut()
        val repo = PulseChatRepository(firestore, auth)
        var threwIllegalState = false
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeChats().first()
            }
        } catch (e: IllegalStateException) {
            threwIllegalState = true
        }
        assertTrue(threwIllegalState)
    }

    @Test
    fun postStatusAndCreateCommunityAndCall_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        signInTestUser(ALICE_EMAIL)
        val repo = PulseChatRepository(firestore, auth)

        val statusId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.postStatusUpdate(
                userName = "Alice Pulse",
                avatarColorHex = "#128C7E",
                mediaType = "text",
                caption = "Shipping today!",
                privacy = "everyone"
            ).getOrThrow()
        }
        val myStatuses = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMyStatuses().first { list -> list.any { it.id == statusId } }
        }
        assertTrue(myStatuses.any { it.id == statusId })

        val commId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createCommunity(
                name = "Pulse Builders",
                description = "Kotlin Compose builders",
                announcementText = "Welcome aboard"
            ).getOrThrow()
        }
        val communities = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCommunities().first { list -> list.any { it.id == commId } }
        }
        assertTrue(communities.any { it.id == commId })

        // Verify call creation and call history observation via call_sessions
        val signaling = CallSignaling(firestore, auth)
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            signaling.createOutgoingCall(
                callId = callId,
                callerName = "Alice Pulse",
                receiverId = bobUid,
                receiverName = "Bob",
                callType = "voice"
            ).getOrThrow()
        }
        val calls = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCalls().first { list -> list.any { it.id == callId } }
        }
        assertTrue(calls.any { it.id == callId && it.callerId == aliceUid })
    }

    @Test
    fun phoneDirectory_hashedLookupAllowedAndListDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = PulseChatRepository(firestore, auth)
        val rawPhone = "+15550199"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserProfile("Alice Pulse", ALICE_EMAIL, rawPhone, "+1").getOrThrow()
        }

        // Bob can perform exact lookup using normalized formatted phone
        signInTestUser(BOB_EMAIL)
        val bobRepo = PulseChatRepository(firestore, auth)
        val found = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.lookupRegisteredUserByPhone("(555) 019-9", "+1").getOrNull()
                ?: bobRepo.lookupRegisteredUserByPhone("+1 555-0199", "+1").getOrThrow()
        }
        assertNotNull(found)
        assertEquals(aliceUid, found?.userId)

        // Unregistered number returns null
        val missing = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.lookupRegisteredUserByPhone("+15559998888", "+1").getOrThrow()
        }
        assertEquals(null, missing)

        // Listing phone_directory collection must fail with PERMISSION_DENIED
        var listError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("phone_directory").get().await()
            }
        } catch (e: Exception) {
            listError = e.unwrapFirestoreException()
        }
        assertNotNull(listError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, listError?.code)
    }

    @Test
    fun statusPrivacyAndLifecycle_enforcesVisibilityAndViewRecording() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        val charlieUid = signInTestUser(CHARLIE_EMAIL)

        signInTestUser(ALICE_EMAIL)
        val aliceRepo = PulseChatRepository(firestore, auth)

        val statusId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.postStatusUpdate(
                userName = "Alice Pulse",
                avatarColorHex = "#128C7E",
                mediaType = "text",
                caption = "For Bob only!",
                mediaUrl = "https://example.com/story.jpg",
                storagePath = "statuses/$aliceUid/story.jpg",
                backgroundColorHex = "#075E54",
                fontStyle = "serif",
                privacy = "only_share_with",
                visibleTo = listOf(aliceUid, bobUid)
            ).getOrThrow()
        }

        // Bob can observe the status and record a view
        signInTestUser(BOB_EMAIL)
        val bobRepo = PulseChatRepository(firestore, auth)
        val bobVisible = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeFriendsStatuses().first { list -> list.any { it.id == statusId } }
        }
        val statusForBob = bobVisible.first { it.id == statusId }
        assertEquals("serif", statusForBob.fontStyle)
        assertNotNull(statusForBob.expiresAt)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.markStatusViewed(statusForBob).getOrThrow()
        }

        // Charlie cannot read Alice's only_share_with status
        signInTestUser(CHARLIE_EMAIL)
        var charlieError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("statuses").document(statusId).get().await()
            }
        } catch (e: Exception) {
            charlieError = e.unwrapFirestoreException()
        }
        assertNotNull(charlieError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, charlieError?.code)

        // Bob cannot delete Alice's status
        signInTestUser(BOB_EMAIL)
        val bobDeleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.deleteStatusUpdate(statusForBob)
        }
        assertTrue(bobDeleteRes.isFailure)

        // Alice can delete her own status
        signInTestUser(ALICE_EMAIL)
        val aliceDeleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.deleteStatusUpdate(statusForBob)
        }
        assertTrue(aliceDeleteRes.isSuccess)
        assertTrue(charlieUid.isNotBlank())
    }

    @Test
    fun webRtcCallSessions_offerAnswerIceHistoryAndIsolation_worksWithRules() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        signInTestUser(CHARLIE_EMAIL)

        signInTestUser(ALICE_EMAIL)
        val aliceSignaling = CallSignaling(firestore, auth)
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val created = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSignaling.createOutgoingCall(
                callId = callId,
                callerName = "Alice Pulse",
                receiverId = bobUid,
                receiverName = "Bob",
                callType = "video"
            ).getOrThrow()
        }
        assertEquals("calling", created.status)
        assertEquals(bobUid, created.receiverId)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSignaling.sendOffer(callId, "v=0\r\no=- 100 2 IN IP4 127.0.0.1\r\n").getOrThrow()
            aliceSignaling.addIceCandidate(
                callId = callId,
                participantIds = listOf(aliceUid, bobUid),
                iceCandidate = IceCandidate("0", 0, "candidate:1 1 UDP 2122252543 192.0.2.1 54321 typ host")
            ).getOrThrow()
        }

        // Bob reads incoming call, accepts, sends answer & ICE candidate, marks connected, then ends
        signInTestUser(BOB_EMAIL)
        val bobSignaling = CallSignaling(firestore, auth)
        val incoming = withTimeout(FLOW_TIMEOUT_MS) {
            bobSignaling.observeIncomingCalls().first { list -> list.any { it.id == callId } }
        }
        assertTrue(incoming.any { it.id == callId })

        val remoteCand = withTimeout(FLOW_TIMEOUT_MS) {
            bobSignaling.observeRemoteIceCandidates(callId).first()
        }
        assertEquals(aliceUid, remoteCand.senderId)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            bobSignaling.acceptCall(callId).getOrThrow()
            bobSignaling.sendAnswer(callId, "v=0\r\no=- 200 2 IN IP4 127.0.0.1\r\n").getOrThrow()
            bobSignaling.markConnected(callId).getOrThrow()
            bobSignaling.endCall(callId, 28L, "ended").getOrThrow()
        }

        // Verify call history in repository reflects ended session with duration and timestamps
        val bobRepo = PulseChatRepository(firestore, auth)
        val bobCalls = withTimeout(FLOW_TIMEOUT_MS) {
            bobRepo.observeCalls().first { list -> list.any { it.id == callId && it.status == "ended" } }
        }
        val recordedCall = bobCalls.first { it.id == callId }
        assertEquals(28L, recordedCall.durationSec)
        assertNotNull(recordedCall.startedAt)
        assertNotNull(recordedCall.endedAt)

        // Charlie cannot read Bob/Alice call session
        signInTestUser(CHARLIE_EMAIL)
        var charlieError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("call_sessions").document(callId).get().await()
            }
        } catch (e: Exception) {
            charlieError = e.unwrapFirestoreException()
        }
        assertNotNull(charlieError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, charlieError?.code)
    }

    @Test
    fun webRtcCallSessions_callerCancelAndUnauthorizedTerminationDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        signInTestUser(CHARLIE_EMAIL)

        signInTestUser(ALICE_EMAIL)
        val aliceSignaling = CallSignaling(firestore, auth)
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSignaling.createOutgoingCall(
                callId = callId,
                callerName = "Alice Pulse",
                receiverId = bobUid,
                receiverName = "Bob",
                callType = "voice"
            ).getOrThrow()
        }

        // Unauthorized third-party Charlie cannot cancel or terminate Alice/Bob's call
        signInTestUser(CHARLIE_EMAIL)
        var unauthorizedEndError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("call_sessions").document(callId)
                    .update("status", "ended").await()
            }
        } catch (e: Exception) {
            unauthorizedEndError = e.unwrapFirestoreException()
        }
        assertNotNull(unauthorizedEndError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, unauthorizedEndError?.code)

        // Caller Alice cancels the call cleanly before answer
        signInTestUser(ALICE_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSignaling.cancelCall(callId).getOrThrow()
        }
        val cancelled = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceSignaling.getCall(callId)
        }
        assertEquals("cancelled", cancelled?.status)
        assertEquals(aliceUid, cancelled?.callerId)
        assertNotNull(cancelled?.endedAt)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_pulse@test.com"
        const val BOB_EMAIL = "bob_pulse@test.com"
        const val CHARLIE_EMAIL = "charlie_pulse@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
