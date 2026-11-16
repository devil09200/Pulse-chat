package com.example.calling

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityIncomingCallBinding
import com.example.util.MediaUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var signaling: CallSignaling

    private var callId: String = ""
    private var callerId: String = ""
    private var callerName: String = "Incoming Caller"
    private var callerPhotoBase64: String = ""
    private var callerAvatarColorHex: String = "#128C7E"
    private var callType: String = "voice"

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var observerJob: Job? = null
    private var actionTaken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signaling = CallSignaling(this)
        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME).orEmpty().ifBlank { "PulseChat Contact" }
        callerPhotoBase64 = intent.getStringExtra(EXTRA_CALLER_PHOTO).orEmpty()
        callerAvatarColorHex = intent.getStringExtra(EXTRA_CALLER_COLOR).orEmpty().ifBlank { "#128C7E" }
        callType = intent.getStringExtra(EXTRA_CALL_TYPE).orEmpty().ifBlank { "voice" }

        if (callId.isBlank()) {
            finish()
            return
        }

        CallNotificationHelper.cancelCallNotification(this, callId)

        setupInsets()
        bindCallerUi()
        startRingAndVibration()
        markRingingAndObserveCall()

        binding.btnAcceptIncomingCall.setOnClickListener {
            acceptCall()
        }
        binding.btnRejectIncomingCall.setOnClickListener {
            rejectCall()
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.incomingCallRoot.updatePadding(
                top = sysBars.top + dp(12),
                bottom = sysBars.bottom + dp(20)
            )
            insets
        }
    }

    private fun bindCallerUi() {
        binding.incomingCallerName.text = callerName
        binding.incomingCallTypeLabel.text = getString(
            if (callType == "video") R.string.incoming_video_call_label else R.string.incoming_voice_call_label
        )

        val bmp = if (callerPhotoBase64.isNotBlank()) {
            MediaUtils.decodeBase64ToBitmap(callerPhotoBase64)
        } else null
        if (bmp != null) {
            binding.incomingCallerAvatarImage.setImageBitmap(bmp)
            binding.incomingCallerAvatarImage.visibility = View.VISIBLE
            binding.incomingCallerInitials.visibility = View.GONE
        } else {
            binding.incomingCallerInitials.text = extractInitials(callerName)
            val colorInt = runCatching { Color.parseColor(callerAvatarColorHex) }
                .getOrDefault(ContextCompat.getColor(this, R.color.call_secondary_green))
            binding.incomingCallerInitials.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorInt)
            }
            binding.incomingCallerAvatarImage.visibility = View.GONE
            binding.incomingCallerInitials.visibility = View.VISIBLE
        }
    }

    private fun markRingingAndObserveCall() {
        lifecycleScope.launch {
            signaling.markRinging(callId)
        }
        observerJob = lifecycleScope.launch {
            signaling.observeCall(callId).collectLatest { call ->
                if (call == null || actionTaken) return@collectLatest
                if (call.callerPhotoBase64.isNotBlank() && callerPhotoBase64.isBlank()) {
                    callerPhotoBase64 = call.callerPhotoBase64
                    bindCallerUi()
                }
                if (call.status in CallSignaling.TERMINAL_STATUSES ||
                    call.status == "accepted" ||
                    call.status == "connected"
                ) {
                    stopRingAndVibration()
                    ActiveCallTracker.markCallFinished(callId)
                    finish()
                }
            }
        }
    }

    private fun acceptCall() {
        if (actionTaken) return
        actionTaken = true
        stopRingAndVibration()
        CallNotificationHelper.cancelCallNotification(this, callId)
        lifecycleScope.launch {
            signaling.acceptCall(callId)
        }
        val callIntent = CallActivity.createIncomingIntent(
            context = this,
            callId = callId,
            callerId = callerId,
            callerName = callerName,
            callType = callType,
            callerPhotoBase64 = callerPhotoBase64,
            callerAvatarColorHex = callerAvatarColorHex
        )
        startActivity(callIntent)
        finish()
    }

    private fun rejectCall() {
        if (actionTaken) return
        actionTaken = true
        stopRingAndVibration()
        CallNotificationHelper.cancelCallNotification(this, callId)
        ActiveCallTracker.markCallFinished(callId)
        lifecycleScope.launch {
            signaling.rejectCall(callId)
            finish()
        }
    }

    private fun startRingAndVibration() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    play()
                }
            }
        }

        if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                val pattern = longArrayOf(0, 600, 900)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        }
    }

    private fun stopRingAndVibration() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        stopRingAndVibration()
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
        const val EXTRA_CALL_ID = "extra_incoming_call_id"
        const val EXTRA_CALLER_ID = "extra_incoming_caller_id"
        const val EXTRA_CALLER_NAME = "extra_incoming_caller_name"
        const val EXTRA_CALLER_PHOTO = "extra_incoming_caller_photo"
        const val EXTRA_CALLER_COLOR = "extra_incoming_caller_color"
        const val EXTRA_CALL_TYPE = "extra_incoming_call_type"

        fun createIntent(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String,
            callType: String,
            callerPhotoBase64: String = "",
            callerAvatarColorHex: String = "#128C7E"
        ): Intent {
            return Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALL_TYPE, callType)
                putExtra(EXTRA_CALLER_PHOTO, callerPhotoBase64)
                putExtra(EXTRA_CALLER_COLOR, callerAvatarColorHex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}
