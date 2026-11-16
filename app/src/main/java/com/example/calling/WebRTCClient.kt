package com.example.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC peer connection manager supporting STUN servers and optional configurable TURN servers
 * via BuildConfig secrets, ICE restart reconnection, AudioFocus, and background camera lifecycle.
 */
class WebRTCClient(
    private val context: Context,
    private val isVideoCall: Boolean,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState, connectionStateLabel: String)
        fun onConnected()
        fun onConnectionFailure(errorMessage: String)
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
        fun onIceRestartOffer(offer: SessionDescription) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val iceServers: List<PeerConnection.IceServer> = buildIceServers()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    private var isDisposed = false
    private var hasReportedConnected = false
    private var hasReportedFailure = false
    private var attemptedIceRestart = false
    private var isVideoCaptureRunning = false
    private var isCameraUserEnabled = isVideoCall
    private var disconnectGraceRunnable: Runnable? = null

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* handled by call controls */ }

    fun initialize(
        localView: SurfaceViewRenderer?,
        remoteView: SurfaceViewRenderer?
    ) {
        if (isDisposed) return
        localRenderer = localView
        remoteRenderer = remoteView

        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        if (isVideoCall) {
            localView?.let {
                it.init(eglContext, null)
                it.setMirror(true)
                it.setEnableHardwareScaler(true)
                it.setZOrderMediaOverlay(true)
            }
            remoteView?.let {
                it.init(eglContext, null)
                it.setMirror(false)
                it.setEnableHardwareScaler(true)
            }
        }

        configureAudioForCall(speakerOn = isVideoCall)
        createPeerConnection()
    }

    private fun configureAudioForCall(speakerOn: Boolean) {
        previousAudioMode = audioManager.mode
        requestCallAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphoneOn(speakerOn)
    }

    private fun requestCallAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonCallAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (isDisposed || newState == null) return
                Log.d(TAG, "ICE connection state: $newState")
                val stateLabel = when (newState) {
                    PeerConnection.IceConnectionState.NEW -> "new"
                    PeerConnection.IceConnectionState.CHECKING -> "checking"
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> "connected"
                    PeerConnection.IceConnectionState.DISCONNECTED -> "reconnecting"
                    PeerConnection.IceConnectionState.FAILED -> "failed"
                    PeerConnection.IceConnectionState.CLOSED -> "closed"
                }
                mainHandler.post {
                    if (isDisposed) return@post
                    callbacks.onIceConnectionStateChanged(newState, stateLabel)
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            cancelDisconnectGrace()
                            attemptedIceRestart = false
                            if (!hasReportedConnected) {
                                hasReportedConnected = true
                                callbacks.onConnected()
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectGrace()
                            reportFailureOnce(buildConnectivityErrorMessage())
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            attemptIceRestartOnce()
                            scheduleDisconnectGrace()
                        }
                        else -> Unit
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (isDisposed || newState == null) return
                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    mainHandler.post {
                        if (!isDisposed) {
                            reportFailureOnce(buildConnectivityErrorMessage())
                        }
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (isDisposed || candidate == null) return
                mainHandler.post {
                    if (!isDisposed) callbacks.onLocalIceCandidate(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull() ?: return
                attachRemoteVideoTrack(track)
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                attachRemoteVideoTrack(track)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                attachRemoteVideoTrack(track)
            }
        })
    }

    private fun attemptIceRestartOnce() {
        if (attemptedIceRestart || isDisposed) return
        val pc = peerConnection ?: return
        attemptedIceRestart = true
        runCatching {
            pc.restartIce()
            if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false")
                    )
                }
                pc.createOffer(object : SimpleSdpObserver("iceRestartOffer") {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (isDisposed || desc == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver("setIceRestartLocalOffer") {
                            override fun onSetSuccess() {
                                mainHandler.post {
                                    if (!isDisposed) callbacks.onIceRestartOffer(desc)
                                }
                            }
                        }, desc)
                    }
                }, constraints)
            }
        }
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        mainHandler.post {
            if (isDisposed) return@post
            track.setEnabled(true)
            remoteRenderer?.let { renderer ->
                track.removeSink(renderer)
                track.addSink(renderer)
            }
            callbacks.onRemoteVideoTrack(track)
        }
    }

    fun startLocalMedia(hasCameraPermission: Boolean) {
        if (isDisposed) return
        val factory = peerConnectionFactory ?: return
        val pc = peerConnection ?: return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource).apply {
            setEnabled(true)
        }
        pc.addTrack(localAudioTrack, listOf("ARDAMS"))

        if (isVideoCall && hasCameraPermission) {
            isCameraUserEnabled = true
            val capturer = createCameraCapturer()
            if (capturer != null) {
                videoCapturer = capturer
                val eglContext = eglBase?.eglBaseContext ?: return
                surfaceTextureHelper = SurfaceTextureHelper.create("WebRTCCaptureThread", eglContext)
                videoSource = factory.createVideoSource(capturer.isScreencast)
                capturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource!!.capturerObserver)
                startCameraCaptureSafe(capturer)
                localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource).apply {
                    setEnabled(true)
                    localRenderer?.let { addSink(it) }
                }
                pc.addTrack(localVideoTrack, listOf("ARDAMS"))
            }
        }
    }

    private fun startCameraCaptureSafe(capturer: VideoCapturer) {
        try {
            capturer.startCapture(720, 1280, 30)
            isVideoCaptureRunning = true
        } catch (e: Exception) {
            Log.w(TAG, "Fallback camera resolution", e)
            runCatching {
                capturer.startCapture(480, 640, 24)
                isVideoCaptureRunning = true
            }
        }
    }

    fun pauseVideoCapture() {
        if (isDisposed || !isVideoCall || !isVideoCaptureRunning) return
        runCatching {
            (videoCapturer as? CameraVideoCapturer)?.stopCapture()
            isVideoCaptureRunning = false
            localVideoTrack?.setEnabled(false)
        }.onFailure {
            Log.w(TAG, "Failed to pause video capture cleanly", it)
        }
    }

    fun resumeVideoCapture(hasCameraPermission: Boolean) {
        if (isDisposed || !isVideoCall || !isCameraUserEnabled || !hasCameraPermission || isVideoCaptureRunning) return
        val capturer = videoCapturer ?: return
        startCameraCaptureSafe(capturer)
        localVideoTrack?.setEnabled(true)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context.applicationContext)
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val fallbackCamera = deviceNames.firstOrNull()
        val chosen = frontCamera ?: fallbackCamera ?: return null
        return enumerator.createCapturer(chosen, null)
    }

    fun createOffer(
        onOfferCreated: (SessionDescription) -> Unit,
        onError: (String) -> Unit
    ) {
        val pc = peerConnection ?: run {
            onError("PeerConnection not initialized")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false")
            )
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (isDisposed || desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        mainHandler.post {
                            if (!isDisposed) onOfferCreated(desc)
                        }
                    }

                    override fun onSetFailure(error: String?) {
                        mainHandler.post {
                            onError(error ?: "Failed to set local offer")
                        }
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                mainHandler.post {
                    onError(error ?: "Failed to create WebRTC offer")
                }
            }
        }, constraints)
    }

    fun createAnswer(
        remoteOfferSdp: String,
        onAnswerCreated: (SessionDescription) -> Unit,
        onError: (String) -> Unit
    ) {
        val pc = peerConnection ?: run {
            onError("PeerConnection not initialized")
            return
        }
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteOffer") {
            override fun onSetSuccess() {
                synchronized(pendingRemoteIceCandidates) {
                    isRemoteDescriptionSet = true
                    pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
                    pendingRemoteIceCandidates.clear()
                }
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false")
                    )
                }
                pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (isDisposed || answerDesc == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver("setLocalAnswer") {
                            override fun onSetSuccess() {
                                mainHandler.post {
                                    if (!isDisposed) onAnswerCreated(answerDesc)
                                }
                            }

                            override fun onSetFailure(error: String?) {
                                mainHandler.post {
                                    onError(error ?: "Failed to set local answer")
                                }
                            }
                        }, answerDesc)
                    }

                    override fun onCreateFailure(error: String?) {
                        mainHandler.post {
                            onError(error ?: "Failed to create WebRTC answer")
                        }
                    }
                }, constraints)
            }

            override fun onSetFailure(error: String?) {
                mainHandler.post {
                    onError(error ?: "Failed to apply remote offer")
                }
            }
        }, offerDesc)
    }

    fun applyRemoteIceRestartOffer(
        remoteOfferSdp: String,
        onAnswerCreated: (SessionDescription) -> Unit,
        onError: (String) -> Unit
    ) {
        createAnswer(remoteOfferSdp, onAnswerCreated, onError)
    }

    fun setRemoteAnswer(
        remoteAnswerSdp: String,
        onApplied: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        val pc = peerConnection ?: return
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
            override fun onSetSuccess() {
                synchronized(pendingRemoteIceCandidates) {
                    isRemoteDescriptionSet = true
                    pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
                    pendingRemoteIceCandidates.clear()
                }
                mainHandler.post { onApplied() }
            }

            override fun onSetFailure(error: String?) {
                mainHandler.post {
                    onError(error ?: "Failed to apply remote answer")
                }
            }
        }, answerDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String) {
        if (isDisposed) return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        synchronized(pendingRemoteIceCandidates) {
            if (!isRemoteDescriptionSet) {
                pendingRemoteIceCandidates.add(candidate)
            } else {
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setCameraEnabled(enabled: Boolean): Boolean {
        val track = localVideoTrack ?: return false
        isCameraUserEnabled = enabled
        track.setEnabled(enabled)
        return true
    }

    fun switchCamera(onSwitched: (isFrontCamera: Boolean) -> Unit = {}) {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                mainHandler.post {
                    localRenderer?.setMirror(isFrontCamera)
                    onSwitched(isFrontCamera)
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "switchCamera error: $errorDescription")
            }
        })
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphoneOn(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = if (enabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == targetType }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
            } else if (!enabled) {
                audioManager.clearCommunicationDevice()
            }
        } else {
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun scheduleDisconnectGrace() {
        cancelDisconnectGrace()
        val runnable = Runnable {
            if (!isDisposed) {
                reportFailureOnce(
                    "Call connection interrupted. Unable to reconnect over the current network."
                )
            }
        }
        disconnectGraceRunnable = runnable
        mainHandler.postDelayed(runnable, DISCONNECT_GRACE_MS)
    }

    private fun cancelDisconnectGrace() {
        disconnectGraceRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnectGraceRunnable = null
    }

    private fun buildConnectivityErrorMessage(): String {
        val hasTurn = BuildConfig.TURN_SERVER_URL.isNotBlank()
        return if (hasTurn) {
            "Unable to establish call media. Please check your internet connection or TURN relay configuration and retry."
        } else {
            "Unable to connect directly over STUN. Restrictive NAT or carrier firewalls may require configuring TURN relay credentials."
        }
    }

    private fun reportFailureOnce(reason: String) {
        if (hasReportedFailure || isDisposed) return
        hasReportedFailure = true
        callbacks.onConnectionFailure(reason)
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        cancelDisconnectGrace()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousAudioMode
        }
        abandonCallAudioFocus()

        runCatching {
            if (isVideoCaptureRunning) {
                (videoCapturer as? CameraVideoCapturer)?.stopCapture()
                isVideoCaptureRunning = false
            }
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null

        runCatching { localRenderer?.release() }
        localRenderer = null
        runCatching { remoteRenderer?.release() }
        remoteRenderer = null

        runCatching { eglBase?.release() }
        eglBase = null
    }

    private open class SimpleSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "$label onCreateFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.w(TAG, "$label onSetFailure: $error")
        }
    }

    companion object {
        private const val TAG = "WebRTCClient"
        private const val DISCONNECT_GRACE_MS = 10_000L

        fun buildIceServers(): List<PeerConnection.IceServer> {
            val servers = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
            )
            val turnUrlsRaw = BuildConfig.TURN_SERVER_URL.trim()
                .takeUnless { it == "TURN_SERVER_URL_OPTIONAL" }
                .orEmpty()
            if (turnUrlsRaw.isNotEmpty()) {
                val turnUser = BuildConfig.TURN_SERVER_USERNAME.trim()
                    .takeUnless { it == "TURN_SERVER_USERNAME_OPTIONAL" }
                    .orEmpty()
                val turnCredential = BuildConfig.TURN_SERVER_CREDENTIAL.trim()
                    .takeUnless { it == "TURN_SERVER_CREDENTIAL_OPTIONAL" }
                    .orEmpty()
                turnUrlsRaw.split(",")
                    .map { it.trim() }
                    .filter { it.startsWith("turn:") || it.startsWith("turns:") }
                    .forEach { url ->
                        val builder = PeerConnection.IceServer.builder(url)
                        if (turnUser.isNotEmpty()) {
                            builder.setUsername(turnUser)
                        }
                        if (turnCredential.isNotEmpty()) {
                            builder.setPassword(turnCredential)
                        }
                        servers.add(builder.createIceServer())
                    }
            }
            return servers
        }
    }
}
