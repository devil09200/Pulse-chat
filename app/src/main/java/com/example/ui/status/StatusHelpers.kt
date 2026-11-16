package com.example.ui.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.data.repository.PulseChatRepository
import com.example.util.MediaUtils
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object StatusHelpers {

    val TEXT_BG_COLORS = listOf(
        "#075E54",
        "#128C7E",
        "#1D6F8A",
        "#5B6AD0",
        "#8E44AD",
        "#B54D7C",
        "#C0392B",
        "#D35400",
        "#2C3E50"
    )

    val DRAW_COLORS = listOf(
        "#FFFFFF",
        "#25D366",
        "#FF3B30",
        "#FFCC00",
        "#0A84FF",
        "#AF52DE",
        "#FF9500",
        "#111B21"
    )

    val EMOJI_PALETTE = listOf(
        "🔥", "❤️", "😂", "🙌", "😍", "🎉", "👏", "🤩", "💯", "✨", "😎", "🥳", "🙏", "👍"
    )

    val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🙏", "👏", "🔥", "🎉")

    val FONT_OPTIONS = listOf(
        "sans" to "Sans",
        "serif" to "Serif",
        "bold" to "Bold",
        "mono" to "Mono",
        "cursive" to "Classic"
    )

    fun createRepository(context: Context): PulseChatRepository {
        val db = FirebaseFirestore.getInstance(context.applicationContext.getString(R.string.firestore_database_id))
        return PulseChatRepository(db, Firebase.auth)
    }

    fun parseColorSafe(hex: String, fallback: Int = Color.parseColor("#075E54")): Int {
        if (hex.isBlank()) return fallback
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    fun resolveTypeface(fontStyle: String): Pair<Typeface, Int> {
        return when (fontStyle) {
            "serif" -> Typeface.SERIF to Typeface.NORMAL
            "bold" -> Typeface.SANS_SERIF to Typeface.BOLD
            "mono" -> Typeface.MONOSPACE to Typeface.NORMAL
            "cursive" -> Typeface.SERIF to Typeface.ITALIC
            else -> Typeface.SANS_SERIF to Typeface.NORMAL
        }
    }

    fun formatStatusTimestamp(timestamp: Timestamp?): String {
        val date = timestamp?.toDate() ?: return "Just now"
        val nowMs = System.currentTimeMillis()
        val diffMs = (nowMs - date.time).coerceAtLeast(0L)
        if (diffMs < 60_000L) return "Just now"
        if (diffMs < 3_600_000L) {
            val mins = (diffMs / 60_000L).coerceAtLeast(1L)
            return "${mins}m ago"
        }

        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        return if (
            today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        ) {
            "Today, ${timeFmt.format(date)}"
        } else {
            today.add(Calendar.DAY_OF_YEAR, -1)
            if (
                today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            ) {
                "Yesterday, ${timeFmt.format(date)}"
            } else {
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
            }
        }
    }

    fun formatPrivacyShortLabel(privacy: String): String {
        return when (privacy) {
            "contacts" -> "My contacts"
            "contacts_except" -> "Contacts except…"
            "only_share_with" -> "Only share with…"
            "private" -> "Only me"
            else -> "Everyone"
        }
    }

    fun computeVisibleRecipients(
        userProfile: UserProfile?,
        currentUserId: String,
        publicProfiles: List<PublicProfile>
    ): Pair<String, List<String>> {
        val privacy = userProfile?.statusPrivacy ?: "everyone"
        val allPeers = publicProfiles.map { it.userId }.filter { it.isNotBlank() && it != currentUserId }
        val excluded = userProfile?.statusExcludedIds.orEmpty().toSet()
        val allowed = userProfile?.statusAllowedIds.orEmpty()

        val visibleTo = when (privacy) {
            "contacts" -> (allPeers + currentUserId).distinct()
            "contacts_except" -> (allPeers.filterNot { it in excluded } + currentUserId).distinct()
            "only_share_with" -> (allowed + currentUserId).distinct()
            "private" -> listOf(currentUserId)
            else -> (allPeers + currentUserId).distinct()
        }
        return privacy to visibleTo
    }

    fun decodeOrientedBitmap(context: Context, uri: Uri, maxDim: Int = 1280): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val rotationDeg = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) {
                0f
            }

            val oriented = if (rotationDeg != 0f) {
                val m = Matrix().apply { postRotate(rotationDeg) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                    if (it != raw) raw.recycle()
                }
            } else {
                raw
            }

            val scale = minOf(maxDim.toFloat() / oriented.width, maxDim.toFloat() / oriented.height, 1f)
            if (scale < 1f) {
                val w = (oriented.width * scale).toInt().coerceAtLeast(1)
                val h = (oriented.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(oriented, w, h, true).also {
                    if (it != oriented) oriented.recycle()
                }
            } else {
                oriented
            }
        } catch (_: Exception) {
            null
        }
    }

    fun bitmapToUploadJpegBytes(bitmap: Bitmap, maxBytes: Int = 900_000): ByteArray {
        var quality = 88
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > maxBytes && quality >= 40)
        return bytes
    }

    fun decodeAvatarBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return MediaUtils.decodeBase64ToBitmap(base64)
    }

    fun summarizeStatusForReply(status: StatusUpdate): String {
        return when {
            status.caption.isNotBlank() -> status.caption
            status.mediaType == "photo" -> "Photo status"
            status.mediaType == "video" -> "Video status"
            else -> "Text status"
        }
    }

    fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
