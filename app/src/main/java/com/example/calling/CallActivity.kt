package com.example.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityCallBinding
import com.example.util.MediaUtils
import com.example.util.TimeFormatter
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var signaling: CallSignaling
    private var webRTCClient: WebRTCClient? = null

    private var callId: String = ""
    private var isIncoming: Boolean = false
    private var isVideoCall: Boolean = false
    private var peerId: String = ""
    private var peerName: String = "Contact"
    private var peerPhotoBase64: String = ""
    private var peerAvatarColorHex: String = "#128C7E"
    private var callerDisplayName: String = "PulseChat User"
    private var callerPhotoBase64: String = ""
    private var callerAvatarColorHex: String = "#128C7E"

    private var participantIds: List<String> = emptyList()
    private var remoteAnswerApplied = false
    private var lastAppliedOfferSdp = ""
    private var lastAppliedAnswerSdp = ""
    private var isCallAcceptedByPeer = false
    private var isCallConnected = false
    private var isCallTerminated = false
    private var elapsedSeconds = 0L

    private var isMuted = false
    private var isSpeakerOn = false
    private var isCameraOn = false

    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var iceConnectTimeoutJob: Job? = null
    private var callObserverJob: Job? = null
    private var candidatesObserverJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)
        val camGranted = results[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        if (!micGranted) {
            showCallError(getString(R.string.call_permission_mic_required), markFailed = true)
            return@registerForActivityResult
        }
        if (isVideoCall && !camGranted) {
            isCameraOn = false
            updateCameraToggleUi()
            binding.localCameraOffLabel.visibility = View.VISIBLE
            binding.callErrorText.text = getString(R.string.call_permission_camera_required)
            binding.callErrorRetryButton.visibility = View.GONE
            binding.callErrorCard.visibility = View.VISIBLE
        }
        startWebRtcAndSignaling(hasCamera = isVideoCall && camGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signaling = CallSignaling(this)

        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        callId = intent.getStringExtra(EXTRA_CALL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "voice"
        isVideoCall = callType == "video"
        isSpeakerOn = isVideoCall
        isCameraOn = isVideoCall

        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty().ifBlank { "Contact" }
        peerPhotoBase64 = intent.getStringExtra(EXTRA_PEER_PHOTO).orEmpty()
        peerAvatarColorHex = intent.getStringExtra(EXTRA_PEER_COLOR).orEmpty().ifBlank { "#128C7E" }

        callerDisplayName = intent.getStringExtra(EXTRA_CALLER_NAME).orEmpty().ifBlank {
            Firebase.auth.currentUser?.displayName ?: "PulseChat User"
        }
        callerPhotoBase64 = intent.getStringExtra(EXTRA_CALLER_PHOTO).orEmpty()
        callerAvatarColorHex = intent.getStringExtra(EXTRA_CALLER_COLOR).orEmpty().ifBlank { "#128C7E" }

        ActiveCallTracker.setActiveCall(callId)
        CallNotificationHelper.cancelCallNotification(this, callId)

        setupEdgeToEdgeInsets()
        setupInitialUi()
        setupControls()
        setupDraggableLocalPreview()
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (isVideoCall && isCameraOn && hasPermission(Manifest.permission.CAMERA)) {
            webRTCClient?.resumeVideoCapture(hasCameraPermission = true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isVideoCall && !isFinishing) {
            webRTCClient?.pauseVideoCapture()
        }
    }

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.callTopBar.updatePadding(top = sysBars.top + dp(14))
            binding.callControlsContainer.updatePadding(bottom = sysBars.bottom + dp(20))
            insets
        }
    }

    private fun setupInitialUi() {
        binding.callPeerName.text = peerName
        binding.callStatusText.text = if (isIncoming) {
            getString(R.string.call_status_connecting)
        } else {
            getString(R.string.call_status_calling)
        }

        val avatarBmp = if (peerPhotoBase64.isNotBlank()) {
            MediaUtils.decodeBase64ToBitmap(peerPhotoBase64)
        } else null
        if (avatarBmp != null) {
            binding.voicePeerAvatarImage.setImageBitmap(avatarBmp)
            binding.voicePeerAvatarImage.visibility = View.VISIBLE
            binding.voicePeerAvatarInitials.visibility = View.GONE
        } else {
            binding.voicePeerAvatarInitials.text = extractInitials(peerName)
            val colorInt = runCatching { Color.parseColor(peerAvatarColorHex) }
                .getOrDefault(ContextCompat.getColor(this, R.color.call_secondary_green))
            binding.voicePeerAvatarInitials.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorInt)
            }
            binding.voicePeerAvatarImage.visibility = View.GONE
            binding.voicePeerAvatarInitials.visibility = View.VISIBLE
        }

        if (isVideoCall) {
            binding.videoStageContainer.visibility = View.VISIBLE
            binding.voiceHeaderBackdrop.visibility = View.GONE
            binding.voiceAvatarContainer.visibility = View.GONE
            binding.localVideoContainer.visibility = View.VISIBLE
            binding.groupToggleCamera.visibility = View.VISIBLE
            binding.groupSwitchCamera.visibility = View.VISIBLE
            binding.callControlsContainer.setBackgroundColor(
                ContextCompat.getColor(this, R.color.call_video_control_bg)
            )
        } else {
            binding.videoStageContainer.visibility = View.GONE
            binding.voiceHeaderBackdrop.visibility = View.VISIBLE
            binding.voiceAvatarContainer.visibility = View.VISIBLE
            binding.localVideoContainer.visibility = View.GONE
            binding.groupToggleCamera.visibility = View.GONE
            binding.groupSwitchCamera.visibility = View.GONE
        }

        updateMuteUi()
        updateSpeakerUi()
        updateCameraToggleUi()
    }

    private fun setupControls() {
        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            webRTCClient?.setMuted(isMuted)
            updateMuteUi()
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            webRTCClient?.setSpeakerphoneOn(isSpeakerOn)
            updateSpeakerUi()
        }

        binding.btnToggleCamera.setOnClickListener {
            if (!hasPermission(Manifest.permission.CAMERA)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                return@setOnClickListener
            }
            isCameraOn = !isCameraOn
            val applied = webRTCClient?.setCameraEnabled(isCameraOn) ?: false
            if (!applied) isCameraOn = false
            binding.localCameraOffLabel.visibility = if (isCameraOn) View.GONE else View.VISIBLE
            updateCameraToggleUi()
        }

        binding.btnSwitchCamera.setOnClickListener {
            webRTCClient?.switchCamera()
        }

        binding.btnEndCall.setOnClickListener {
            if (!isIncoming && !isCallConnected && !isCallAcceptedByPeer && !remoteAnswerApplied) {
                cancelOutgoingCallAndClose()
            } else {
                terminateAndClose(finalStatus = "ended")
            }
        }

        binding.callErrorRetryButton.setOnClickListener {
            retryCallConnection()
        }

        binding.callErrorDismissButton.setOnClickListener {
            if (isCallTerminated || !isCallConnected) {
                terminateAndClose(finalStatus = "failed")
            } else {
                binding.callErrorCard.visibility = View.GONE
            }
        }
    }

    private fun setupDraggableLocalPreview() {
        var dX = 0f
        var dY = 0f
        binding.localVideoContainer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = binding.callRoot
                    val maxX = (parent.width - view.width).coerceAtLeast(0).toFloat()
                    val maxY = (binding.callControlsContainer.top - view.height).coerceAtLeast(0).toFloat()
                    view.x = (event.rawX + dX).coerceIn(0f, maxX)
                    view.y = (event.rawY + dY).coerceIn(0f, maxY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val required = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            required.add(Manifest.permission.RECORD_AUDIO)
        }
        if (isVideoCall && !hasPermission(Manifest.permission.CAMERA)) {
            required.add(Manifest.permission.CAMERA)
        }
        if (required.isEmpty()) {
            startWebRtcAndSignaling(hasCamera = isVideoCall)
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun retryCallConnection() {
        timerJob?.cancel()
        ringTimeoutJob?.cancel()
        iceConnectTimeoutJob?.cancel()
        callObserverJob?.cancel()
        candidatesObserverJob?.cancel()
        webRTCClient?.dispose()
        webRTCClient = null

        ActiveCallTracker.markCallFinished(callId)
        callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        ActiveCallTracker.setActiveCall(callId)

        isIncoming = false
        remoteAnswerApplied = false
        lastAppliedOfferSdp = ""
        lastAppliedAnswerSdp = ""
        isCallAcceptedByPeer = false
        isCallConnected = false
        isCallTerminated = false
        elapsedSeconds = 0L

        binding.callErrorCard.visibility = View.GONE
        binding.callTimerText.visibility = View.GONE
        binding.callStatusText.text = getString(R.string.call_status_calling)
        ensurePermissionsAndStart()
    }

    private fun startWebRtcAndSignaling(hasCamera: Boolean) {
        val currentUid = Firebase.auth.currentUser?.uid
        if (currentUid == null) {
            showCallError("Please sign in to place or receive calls.", markFailed = true)
            return
        }

        val client = WebRTCClient(
            context = applicationContext,
            isVideoCall = isVideoCall,
            callbacks = object : WebRTCClient.Callbacks {
                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    val participants = participantIds.ifEmpty {
                        listOf(currentUid, peerId.ifBlank { currentUid }).distinct()
                    }
                    lifecycleScope.launch {
                        signaling.addIceCandidate(callId, participants, candidate)
                    }
                }

                override fun onIceConnectionStateChanged(
                    state: PeerConnection.IceConnectionState,
                    connectionStateLabel: String
                ) {
                    lifecycleScope.launch {
                        signaling.updateConnectionState(callId, connectionStateLabel)
                    }
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            if (!isCallConnected) {
                                binding.callStatusText.text = getString(R.string.call_status_connecting)
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            binding.callStatusText.text = "Reconnecting…"
                        }
                        else -> Unit
                    }
                }

                override fun onConnected() {
                    isCallConnected = true
                    ringTimeoutJob?.cancel()
                    iceConnectTimeoutJob?.cancel()
                    binding.callErrorCard.visibility = View.GONE
                    binding.callStatusText.text = getString(R.string.call_status_connected)
                    binding.callTimerText.visibility = View.VISIBLE
                    startDurationTimer()
                    lifecycleScope.launch {
                        signaling.markConnected(callId)
                    }
                }

                override fun onIceRestartOffer(offer: SessionDescription) {
                    lifecycleScope.launch {
                        signaling.sendOffer(callId, offer.description)
                    }
                }

                override fun onConnectionFailure(errorMessage: String) {
                    showCallError(errorMessage, markFailed = true)
                }

                override fun onRemoteVideoTrack(videoTrack: VideoTrack) {
                    // Remote video sink attached automatically on main thread in WebRTCClient
                }
            }
        )
        webRTCClient = client
        client.initialize(
            localView = if (isVideoCall) binding.localVideoView else null,
            remoteView = if (isVideoCall) binding.remoteVideoView else null
        )
        client.startLocalMedia(hasCameraPermission = hasCamera)
        client.setSpeakerphoneOn(isSpeakerOn)

        if (isIncoming) {
            startIncomingFlow(currentUid)
        } else {
            startOutgoingFlow(currentUid)
        }
    }

    private fun startOutgoingFlow(currentUid: String) {
        if (peerId.isBlank() || peerId == currentUid) {
            showCallError("Select another contact to place a call.", markFailed = false)
            return
        }
        participantIds = listOf(currentUid, peerId)

        lifecycleScope.launch {
            val createRes = signaling.createOutgoingCall(
                callId = callId,
                callerName = callerDisplayName,
                callerPhotoBase64 = callerPhotoBase64,
                callerAvatarColorHex = callerAvatarColorHex,
                receiverId = peerId,
                receiverName = peerName,
                callType = if (isVideoCall) "video" else "voice"
            )
            if (createRes.isFailure) {
                showCallError(
                    createRes.exceptionOrNull()?.localizedMessage ?: "Failed to initiate call signaling.",
                    markFailed = true
                )
                return@launch
            }

            webRTCClient?.createOffer(
                onOfferCreated = { offerDesc ->
                    lifecycleScope.launch {
                        signaling.sendOffer(callId, offerDesc.description)
                    }
                },
                onError = { err ->
                    showCallError("Failed to prepare call offer: $err", markFailed = true)
                }
            )

            startCallAndIceObservers()
            startRingNoAnswerTimeout()
        }
    }

    private fun startIncomingFlow(currentUid: String) {
        lifecycleScope.launch {
            val existing = signaling.getCall(callId)
            if (existing == null) {
                showCallError("This call session is no longer active.", markFailed = false)
                return@launch
            }
            if (existing.status in CallSignaling.TERMINAL_STATUSES) {
                binding.callStatusText.text = when (existing.status) {
                    "rejected" -> getString(R.string.call_status_rejected)
                    "cancelled" -> getString(R.string.call_status_cancelled)
                    "missed" -> getString(R.string.call_status_missed)
                    else -> getString(R.string.call_status_ended)
                }
                delay(900L)
                finishCleanly()
                return@launch
            }

            signaling.acceptCall(callId)

            participantIds = existing.participantIds.ifEmpty {
                listOf(existing.callerId, currentUid).distinct()
            }
            if (existing.callerName.isNotBlank()) {
                peerName = existing.callerName
                binding.callPeerName.text = peerName
            }

            if (existing.sdpOffer.isNotBlank()) {
                applyOfferAndAnswer(existing.sdpOffer)
            }
            startCallAndIceObservers()
        }
    }

    private fun applyOfferAndAnswer(offerSdp: String) {
        if (offerSdp.isBlank() || offerSdp == lastAppliedOfferSdp) return
        val isIceRestart = lastAppliedOfferSdp.isNotBlank()
        lastAppliedOfferSdp = offerSdp
        if (!isIceRestart) {
            binding.callStatusText.text = getString(R.string.call_status_connecting)
            startIceConnectTimeout()
        }

        val onAnswer: (SessionDescription) -> Unit = { answerDesc ->
            lifecycleScope.launch {
                signaling.sendAnswer(callId, answerDesc.description)
            }
        }
        val onErr: (String) -> Unit = { err ->
            showCallError("Could not answer WebRTC call: $err", markFailed = true)
        }

        if (isIceRestart) {
            webRTCClient?.applyRemoteIceRestartOffer(offerSdp, onAnswer, onErr)
        } else {
            webRTCClient?.createAnswer(offerSdp, onAnswer, onErr)
        }
    }

    private fun startCallAndIceObservers() {
        callObserverJob?.cancel()
        callObserverJob = lifecycleScope.launch {
            signaling.observeCall(callId).collectLatest { call ->
                if (call == null || isCallTerminated) return@collectLatest
                if (participantIds.isEmpty() && call.participantIds.isNotEmpty()) {
                    participantIds = call.participantIds
                }

                if (call.status == "accepted" || call.status == "connected") {
                    isCallAcceptedByPeer = true
                }

                if (isIncoming && call.sdpOffer.isNotBlank() && call.sdpOffer != lastAppliedOfferSdp) {
                    applyOfferAndAnswer(call.sdpOffer)
                }

                if (!isIncoming && call.sdpAnswer.isNotBlank() && call.sdpAnswer != lastAppliedAnswerSdp) {
                    lastAppliedAnswerSdp = call.sdpAnswer
                    remoteAnswerApplied = true
                    ringTimeoutJob?.cancel()
                    binding.callStatusText.text = getString(R.string.call_status_connecting)
                    startIceConnectTimeout()
                    webRTCClient?.setRemoteAnswer(
                        remoteAnswerSdp = call.sdpAnswer,
                        onError = { err ->
                            showCallError("Failed to apply remote answer: $err", markFailed = true)
                        }
                    )
                }

                when (call.status) {
                    "ringing" -> {
                        if (!isIncoming && !isCallConnected && !remoteAnswerApplied) {
                            binding.callStatusText.text = getString(R.string.call_status_ringing)
                        }
                    }
                    "accepted" -> {
                        if (!isIncoming && !isCallConnected) {
                            ringTimeoutJob?.cancel()
                            binding.callStatusText.text = getString(R.string.call_status_connecting)
                        }
                    }
                    "cancelled" -> {
                        isCallTerminated = true
                        binding.callStatusText.text = getString(R.string.call_status_cancelled)
                        delay(900L)
                        finishCleanly()
                    }
                    "rejected" -> {
                        isCallTerminated = true
                        binding.callStatusText.text = getString(R.string.call_status_rejected)
                        delay(1100L)
                        finishCleanly()
                    }
                    "missed" -> {
                        isCallTerminated = true
                        binding.callStatusText.text = getString(R.string.call_status_missed)
                        delay(1100L)
                        finishCleanly()
                    }
                    "failed" -> {
                        val reason = call.failureReason.ifBlank { getString(R.string.call_stun_nat_error) }
                        showCallError(reason, markFailed = false)
                    }
                    "ended" -> {
                        isCallTerminated = true
                        binding.callStatusText.text = getString(R.string.call_status_ended)
                        delay(800L)
                        finishCleanly()
                    }
                }
            }
        }

        candidatesObserverJob?.cancel()
        candidatesObserverJob = lifecycleScope.launch {
            signaling.observeRemoteIceCandidates(callId).collect { cand ->
                webRTCClient?.addRemoteIceCandidate(
                    sdpMid = cand.sdpMid,
                    sdpMLineIndex = cand.sdpMLineIndex,
                    candidateSdp = cand.candidate
                )
            }
        }
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (!isCallTerminated) {
                binding.callTimerText.text = TimeFormatter.formatDuration(elapsedSeconds)
                delay(1000L)
                elapsedSeconds += 1L
            }
        }
    }

    private fun startRingNoAnswerTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = lifecycleScope.launch {
            delay(RING_TIMEOUT_MS)
            if (!isCallConnected && !remoteAnswerApplied && !isCallAcceptedByPeer && !isCallTerminated) {
                binding.callStatusText.text = getString(R.string.call_status_missed)
                terminateAndClose(finalStatus = "missed")
            }
        }
    }

    private fun startIceConnectTimeout() {
        iceConnectTimeoutJob?.cancel()
        iceConnectTimeoutJob = lifecycleScope.launch {
            delay(ICE_CONNECT_TIMEOUT_MS)
            if (!isCallConnected && !isCallTerminated) {
                showCallError(getString(R.string.call_stun_nat_error), markFailed = true)
            }
        }
    }

    private fun showCallError(message: String, markFailed: Boolean) {
        if (isFinishing) return
        binding.callStatusText.text = getString(R.string.call_status_failed)
        binding.callErrorText.text = message
        binding.callErrorRetryButton.visibility = if (peerId.isNotBlank()) View.VISIBLE else View.GONE
        binding.callErrorCard.visibility = View.VISIBLE
        if (markFailed && !isCallTerminated) {
            isCallTerminated = true
            timerJob?.cancel()
            ringTimeoutJob?.cancel()
            iceConnectTimeoutJob?.cancel()
            lifecycleScope.launch {
                signaling.endCall(
                    callId = callId,
                    durationSec = elapsedSeconds,
                    finalStatus = "failed",
                    failureReason = message
                )
            }
        }
    }

    private fun cancelOutgoingCallAndClose() {
        if (!isCallTerminated) {
            isCallTerminated = true
            timerJob?.cancel()
            ringTimeoutJob?.cancel()
            iceConnectTimeoutJob?.cancel()
            cleanupScope.launch(NonCancellable) {
                signaling.cancelCall(callId)
            }
        }
        finishCleanly()
    }

    private fun terminateAndClose(finalStatus: String) {
        if (!isCallTerminated) {
            isCallTerminated = true
            timerJob?.cancel()
            ringTimeoutJob?.cancel()
            iceConnectTimeoutJob?.cancel()
            cleanupScope.launch(NonCancellable) {
                signaling.endCall(
                    callId = callId,
                    durationSec = elapsedSeconds,
                    finalStatus = finalStatus
                )
            }
        }
        finishCleanly()
    }

    private fun finishCleanly() {
        ActiveCallTracker.markCallFinished(callId)
        if (!isFinishing) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        ringTimeoutJob?.cancel()
        iceConnectTimeoutJob?.cancel()
        callObserverJob?.cancel()
        candidatesObserverJob?.cancel()

        if (!isCallTerminated) {
            isCallTerminated = true
            cleanupScope.launch(NonCancellable) {
                if (!isIncoming && !isCallConnected && !isCallAcceptedByPeer && !remoteAnswerApplied) {
                    signaling.cancelCall(callId)
                } else {
                    signaling.endCall(
                        callId = callId,
                        durationSec = elapsedSeconds,
                        finalStatus = "ended"
                    )
                }
            }
        }
        ActiveCallTracker.markCallFinished(callId)
        webRTCClient?.dispose()
        webRTCClient = null
    }

    private fun updateMuteUi() {
        binding.btnMute.setIconResource(
            if (isMuted) R.drawable.ic_call_mic_off else R.drawable.ic_call_mic_on
        )
        binding.btnMute.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isMuted) R.color.white else R.color.call_secondary_green
            )
        )
        val iconTint = ContextCompat.getColor(
            this,
            if (isMuted) R.color.call_main_text else R.color.white
        )
        binding.btnMute.setIconTint(android.content.res.ColorStateList.valueOf(iconTint))
        binding.labelMute.text = getString(if (isMuted) R.string.call_unmute else R.string.call_mute)
    }

    private fun updateSpeakerUi() {
        binding.btnSpeaker.setIconResource(
            if (isSpeakerOn) R.drawable.ic_call_speaker_on else R.drawable.ic_call_speaker_off
        )
        binding.btnSpeaker.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isSpeakerOn) R.color.white else R.color.call_secondary_green
            )
        )
        val iconTint = ContextCompat.getColor(
            this,
            if (isSpeakerOn) R.color.call_main_text else R.color.white
        )
        binding.btnSpeaker.setIconTint(android.content.res.ColorStateList.valueOf(iconTint))
    }

    private fun updateCameraToggleUi() {
        binding.btnToggleCamera.setIconResource(
            if (isCameraOn) R.drawable.ic_call_video_on else R.drawable.ic_call_video_off
        )
        binding.btnToggleCamera.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isCameraOn) R.color.call_secondary_green else R.color.white
            )
        )
        val iconTint = ContextCompat.getColor(
            this,
            if (isCameraOn) R.color.white else R.color.call_main_text
        )
        binding.btnToggleCamera.setIconTint(android.content.res.ColorStateList.valueOf(iconTint))
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun extractInitials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "PC"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_PEER_PHOTO = "extra_peer_photo"
        const val EXTRA_PEER_COLOR = "extra_peer_color"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_PHOTO = "extra_caller_photo"
        const val EXTRA_CALLER_COLOR = "extra_caller_color"

        private const val RING_TIMEOUT_MS = 45_000L
        private const val ICE_CONNECT_TIMEOUT_MS = 24_000L

        private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun createOutgoingIntent(
            context: Context,
            peerId: String,
            peerName: String,
            callType: String,
            peerPhotoBase64: String = "",
            peerAvatarColorHex: String = "#128C7E",
            callerName: String = "",
            callerPhotoBase64: String = "",
            callerAvatarColorHex: String = "#128C7E"
        ): Intent {
            return Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_IS_INCOMING, false)
                putExtra(EXTRA_CALL_TYPE, callType)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_PEER_PHOTO, peerPhotoBase64)
                putExtra(EXTRA_PEER_COLOR, peerAvatarColorHex)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_PHOTO, callerPhotoBase64)
                putExtra(EXTRA_CALLER_COLOR, callerAvatarColorHex)
            }
        }

        fun createIncomingIntent(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String,
            callType: String,
            callerPhotoBase64: String = "",
            callerAvatarColorHex: String = "#128C7E"
        ): Intent {
            return Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_IS_INCOMING, true)
                putExtra(EXTRA_CALL_TYPE, callType)
                putExtra(EXTRA_PEER_ID, callerId)
                putExtra(EXTRA_PEER_NAME, callerName)
                putExtra(EXTRA_PEER_PHOTO, callerPhotoBase64)
                putExtra(EXTRA_PEER_COLOR, callerAvatarColorHex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}
