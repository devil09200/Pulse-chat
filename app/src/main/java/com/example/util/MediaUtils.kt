package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.Timestamp
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaUtils {
    fun uriToCompressedBase64(context: Context, uri: Uri, maxDim: Int = 640): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = input.use { BitmapFactory.decodeStream(it) } ?: return null
            bitmapToCompressedBase64(original, maxDim)
        } catch (e: Exception) {
            Log.e("MediaUtils", "Failed to read image URI", e)
            null
        }
    }

    fun bitmapToCompressedBase64(bitmap: Bitmap, maxDim: Int = 640): String {
        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = if (targetW != bitmap.width || targetH != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }
        var quality = 78
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 12
        } while (bytes.size > 350_000 && quality >= 30)

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decodeBase64ToBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}

class VoiceRecorderHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(): Boolean {
        return try {
            stopAndDiscard()
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to start recording", e)
            stopAndDiscard()
            false
        }
    }

    fun stopAndGetBase64(): Pair<String, Long>? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            val file = outputFile ?: return null
            val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
            val bytes = file.readBytes()
            file.delete()
            outputFile = null
            if (bytes.isEmpty() || bytes.size > 420_000) {
                null
            } else {
                Base64.encodeToString(bytes, Base64.NO_WRAP) to durationSec
            }
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to stop recording", e)
            stopAndDiscard()
            null
        }
    }

    fun stopAndDiscard() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}

class VoicePlayerHelper(private val context: Context) {
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    fun playBase64(base64: String, onCompletion: () -> Unit): Boolean {
        stop()
        if (base64.isBlank()) return false
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val file = File(context.cacheDir, "play_${System.currentTimeMillis()}.m4a")
            file.writeBytes(bytes)
            tempFile = file
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onCompletion()
                }
                prepare()
                start()
            }
            player = mediaPlayer
            true
        } catch (e: Exception) {
            Log.e("VoicePlayer", "Failed to play voice message", e)
            stop()
            false
        }
    }

    fun stop() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        tempFile?.delete()
        tempFile = null
    }
}

object TimeFormatter {
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun formatMessageTime(timestamp: Timestamp?): String {
        val date = timestamp?.toDate() ?: Date()
        return timeFmt.format(date)
    }

    fun formatChatListTime(timestamp: Timestamp?): String {
        val date = timestamp?.toDate() ?: return "Now"
        val diff = System.currentTimeMillis() - date.time
        return if (diff < 86_400_000L) timeFmt.format(date) else dateTimeFmt.format(date)
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
    }
}
