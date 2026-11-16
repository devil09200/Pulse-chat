package com.example.calling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.util.MediaUtils
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallNotificationService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token")
        serviceScope.launch {
            CallSignaling(applicationContext).saveFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val callId = data["callId"]?.trim().orEmpty()
        if (callId.isBlank()) return

        val callerId = data["callerId"]?.trim().orEmpty()
        val currentUid = Firebase.auth.currentUser?.uid
        if (currentUid != null && callerId == currentUid) return

        val callerName = data["callerName"]?.trim().orEmpty().ifBlank { "PulseChat Contact" }
        val callerAvatar = data["callerAvatar"]?.trim().orEmpty()
        val callerColor = data["callerColor"]?.trim().orEmpty().ifBlank { "#128C7E" }
        val callType = if (data["callType"] == "video") "video" else "voice"

        // Deduplicate against active call or foreground Firestore listener
        if (!ActiveCallTracker.tryClaimIncomingCall(callId)) {
            Log.d(TAG, "Ignoring duplicate incoming call notification for $callId")
            return
        }

        // Show incoming call alert immediately so background notifications arrive without delay
        CallNotificationHelper.showIncomingCallNotification(
            context = applicationContext,
            callId = callId,
            callerId = callerId,
            callerName = callerName,
            callType = callType,
            callerPhotoBase64 = callerAvatar,
            callerColorHex = callerColor
        )

        serviceScope.launch {
            val signaling = CallSignaling(applicationContext)
            val call = signaling.getCall(callId)
            if (call != null && call.status in CallSignaling.TERMINAL_STATUSES) {
                CallNotificationHelper.cancelCallNotification(applicationContext, callId)
                ActiveCallTracker.markCallFinished(callId)
                return@launch
            }
            if (call != null && call.status == "calling") {
                signaling.markRinging(callId)
            }
            if (callerAvatar.isBlank() && call != null && call.callerPhotoBase64.isNotBlank()) {
                CallNotificationHelper.showIncomingCallNotification(
                    context = applicationContext,
                    callId = callId,
                    callerId = callerId,
                    callerName = callerName,
                    callType = callType,
                    callerPhotoBase64 = call.callerPhotoBase64,
                    callerColorHex = callerColor
                )
            }
        }
    }

    companion object {
        private const val TAG = "CallNotificationService"
    }
}

object CallNotificationHelper {
    const val CHANNEL_ID_INCOMING_CALL = "pulsechat_incoming_calls"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID_INCOMING_CALL) != null) return

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID_INCOMING_CALL,
            context.getString(R.string.call_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.call_channel_description)
            setSound(ringtoneUri, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 600, 500)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String,
        callType: String,
        callerPhotoBase64: String = "",
        callerColorHex: String = "#128C7E"
    ) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationId = callId.hashCode()

        val contentIntent = IncomingCallActivity.createIntent(
            context = context,
            callId = callId,
            callerId = callerId,
            callerName = callerName,
            callType = callType,
            callerPhotoBase64 = callerPhotoBase64,
            callerAvatarColorHex = callerColorHex
        )
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = CallActivity.createIncomingIntent(
            context = context,
            callId = callId,
            callerId = callerId,
            callerName = callerName,
            callType = callType,
            callerPhotoBase64 = callerPhotoBase64,
            callerAvatarColorHex = callerColorHex
        )
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REJECT_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = resolveCallerAvatarBitmap(callerName, callerPhotoBase64, callerColorHex)
        val callTypeText = context.getString(
            if (callType == "video") R.string.incoming_video_call_label else R.string.incoming_voice_call_label
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_call_accept)
            .setContentTitle(callerName)
            .setContentText(callTypeText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000L)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_call_end, context.getString(R.string.call_reject), rejectPendingIntent)
            .addAction(R.drawable.ic_call_accept, context.getString(R.string.call_accept), acceptPendingIntent)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    fun cancelCallNotification(context: Context, callId: String) {
        if (callId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }

    private fun resolveCallerAvatarBitmap(
        name: String,
        photoBase64: String,
        colorHex: String
    ): Bitmap? {
        if (photoBase64.isNotBlank()) {
            MediaUtils.decodeBase64ToBitmap(photoBase64)?.let { return it }
        }
        return runCatching {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bgColor = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.parseColor("#128C7E"))
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val initials = when {
                parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "PC"
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 48f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(initials, canvas.width / 2f, yPos, textPaint)
            bitmap
        }.getOrNull()
    }
}
