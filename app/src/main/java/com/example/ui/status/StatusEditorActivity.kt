package com.example.ui.status

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.model.UserProfile
import com.example.data.repository.PulseChatRepository
import com.example.databinding.ActivityStatusEditorBinding
import com.example.ui.status.views.StatusEditorCanvasView
import com.example.util.MediaUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class StatusEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusEditorBinding
    private lateinit var repository: PulseChatRepository

    private var mediaUri: Uri? = null
    private var mediaType: String = "photo"
    private var exoPlayer: ExoPlayer? = null
    private var isPreviewMode = false
    private var isUploading = false

    private var cachedProfile: UserProfile? = null
    private var cachedPublicProfiles: List<PublicProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityStatusEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = StatusHelpers.createRepository(this)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "photo"
        val uriStr = intent.getStringExtra(EXTRA_MEDIA_URI)
        mediaUri = uriStr?.let { Uri.parse(it) }

        if (mediaUri == null) {
            StatusHelpers.showToast(this, "Could not load selected media")
            finish()
            return
        }

        setupEdgeToEdgeInsets()
        preloadUserAndContacts()
        setupToolbarAndActions()
        setupDrawPalette()
        setupEmojiTray()

        if (mediaType == "video") {
            setupVideoStage(mediaUri!!)
        } else {
            setupPhotoStage(mediaUri!!)
        }
    }

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPad = maxOf(sysBars.bottom, ime.bottom) + dp(10)
            binding.editorTopBar.updatePadding(top = sysBars.top + dp(6))
            binding.editorBottomBar.updatePadding(bottom = bottomPad)
            insets
        }
    }

    private fun preloadUserAndContacts() {
        lifecycleScope.launch {
            cachedProfile = runCatching { repository.observeUserProfile().firstOrNull() }.getOrNull()
            cachedPublicProfiles = runCatching { repository.observePublicProfiles().firstOrNull() }.getOrNull().orEmpty()
        }
    }

    private fun setupPhotoStage(uri: Uri) {
        binding.statusEditorCanvas.visibility = View.VISIBLE
        binding.statusEditorPlayerView.visibility = View.GONE
        binding.btnEditorCrop.visibility = View.VISIBLE
        binding.btnEditorDraw.visibility = View.VISIBLE
        binding.btnEditorTextOverlay.visibility = View.VISIBLE
        binding.btnEditorEmoji.visibility = View.VISIBLE

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                StatusHelpers.decodeOrientedBitmap(this@StatusEditorActivity, uri, maxDim = 1280)
            }
            if (bitmap == null) {
                StatusHelpers.showToast(this@StatusEditorActivity, "Unable to decode photo")
                finish()
            } else {
                binding.statusEditorCanvas.setBitmap(bitmap)
            }
        }

        binding.statusEditorCanvas.onStickerTapped = { sticker ->
            if (!isPreviewMode) {
                showEditStickerDialog(sticker)
            }
        }
    }

    private fun setupVideoStage(uri: Uri) {
        binding.statusEditorCanvas.visibility = View.GONE
        binding.statusEditorPlayerView.visibility = View.VISIBLE
        binding.btnEditorCrop.visibility = View.GONE
        binding.btnEditorDraw.visibility = View.GONE
        binding.btnEditorTextOverlay.visibility = View.GONE
        binding.btnEditorEmoji.visibility = View.GONE

        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
        exoPlayer = player
        binding.statusEditorPlayerView.player = player
    }

    private fun setupToolbarAndActions() {
        binding.btnEditorClose.setOnClickListener { finish() }

        binding.btnEditorCrop.setOnClickListener {
            hideEmojiAndDrawBars()
            val showCrop = binding.cropToolsBar.visibility != View.VISIBLE
            binding.cropToolsBar.visibility = if (showCrop) View.VISIBLE else View.GONE
            binding.statusEditorCanvas.editorMode =
                if (showCrop) StatusEditorCanvasView.EditorMode.CROP
                else StatusEditorCanvasView.EditorMode.VIEW
        }

        binding.btnCropRotate.setOnClickListener {
            binding.statusEditorCanvas.rotate90Degrees()
        }
        binding.btnCropFree.setOnClickListener {
            binding.statusEditorCanvas.setCropAspectRatio(null)
        }
        binding.btnCropSquare.setOnClickListener {
            binding.statusEditorCanvas.setCropAspectRatio(1f)
        }
        binding.btnCropStory.setOnClickListener {
            binding.statusEditorCanvas.setCropAspectRatio(9f / 16f)
        }
        binding.btnCropApply.setOnClickListener {
            binding.statusEditorCanvas.applyCrop()
            binding.cropToolsBar.visibility = View.GONE
        }

        binding.btnEditorDraw.setOnClickListener {
            binding.cropToolsBar.visibility = View.GONE
            binding.editorEmojiTray.visibility = View.GONE
            val showDraw = binding.drawToolsBar.visibility != View.VISIBLE
            binding.drawToolsBar.visibility = if (showDraw) View.VISIBLE else View.GONE
            binding.statusEditorCanvas.editorMode =
                if (showDraw) StatusEditorCanvasView.EditorMode.DRAW
                else StatusEditorCanvasView.EditorMode.VIEW
        }

        binding.btnDrawUndo.setOnClickListener {
            binding.statusEditorCanvas.undoStroke()
        }

        binding.btnDrawDone.setOnClickListener {
            binding.drawToolsBar.visibility = View.GONE
            binding.statusEditorCanvas.editorMode = StatusEditorCanvasView.EditorMode.VIEW
        }

        binding.btnEditorTextOverlay.setOnClickListener {
            hideSubToolbars()
            showAddTextOverlayDialog()
        }

        binding.btnEditorEmoji.setOnClickListener {
            binding.cropToolsBar.visibility = View.GONE
            binding.drawToolsBar.visibility = View.GONE
            binding.statusEditorCanvas.editorMode = StatusEditorCanvasView.EditorMode.VIEW
            binding.editorEmojiTray.visibility =
                if (binding.editorEmojiTray.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnCaptionEmoji.setOnClickListener {
            binding.editorEmojiTray.visibility =
                if (binding.editorEmojiTray.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnEditorPreview.setOnClickListener { togglePreview(true) }
        binding.btnExitPreview.setOnClickListener { togglePreview(false) }

        binding.fabEditorShare.setOnClickListener {
            if (!isUploading) {
                shareStatus()
            }
        }
    }

    private fun hideSubToolbars() {
        binding.cropToolsBar.visibility = View.GONE
        binding.drawToolsBar.visibility = View.GONE
        binding.editorEmojiTray.visibility = View.GONE
        binding.statusEditorCanvas.editorMode = StatusEditorCanvasView.EditorMode.VIEW
    }

    private fun hideEmojiAndDrawBars() {
        binding.drawToolsBar.visibility = View.GONE
        binding.editorEmojiTray.visibility = View.GONE
    }

    private fun togglePreview(preview: Boolean) {
        isPreviewMode = preview
        hideSubToolbars()
        binding.editorTopBar.visibility = if (preview) View.GONE else View.VISIBLE
        binding.editorBottomBar.visibility = if (preview) View.GONE else View.VISIBLE
        binding.btnExitPreview.visibility = if (preview) View.VISIBLE else View.GONE
        if (mediaType == "video") {
            binding.statusEditorPlayerView.useController = !preview
        }
    }

    private fun setupDrawPalette() {
        val palette = binding.drawColorPalette
        palette.removeAllViews()
        StatusHelpers.DRAW_COLORS.forEachIndexed { idx, hex ->
            val colorInt = StatusHelpers.parseColorSafe(hex, Color.WHITE)
            val swatch = View(this).apply {
                val size = dp(30)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    setStroke(dp(2), Color.WHITE)
                }
                alpha = if (idx == 1) 1.0f else 0.78f
                contentDescription = "Brush color"
                setOnClickListener {
                    binding.statusEditorCanvas.brushColor = colorInt
                    for (i in 0 until palette.childCount) {
                        palette.getChildAt(i).alpha = 0.72f
                    }
                    alpha = 1.0f
                }
            }
            palette.addView(swatch)
        }
    }

    private fun setupEmojiTray() {
        val container = binding.editorEmojiContainer
        container.removeAllViews()
        StatusHelpers.EMOJI_PALETTE.forEach { emoji ->
            val tv = TextView(this).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = "Emoji $emoji"
                setOnClickListener {
                    if (mediaType == "photo" && !binding.etStatusCaption.hasFocus()) {
                        binding.statusEditorCanvas.addSticker(emoji, Color.WHITE, hasPillBg = false)
                        binding.editorEmojiTray.visibility = View.GONE
                    } else {
                        val start = binding.etStatusCaption.selectionStart.coerceAtLeast(0)
                        binding.etStatusCaption.text?.insert(start, emoji)
                    }
                }
            }
            container.addView(tv)
        }
    }

    private fun showAddTextOverlayDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.status_editor_text_prompt)
            setPadding(dp(20), dp(16), dp(20), dp(12))
            maxLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add text to photo")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val txt = input.text?.toString()?.trim().orEmpty()
                if (txt.isNotBlank()) {
                    binding.statusEditorCanvas.addSticker(txt, Color.WHITE, hasPillBg = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditStickerDialog(sticker: StatusEditorCanvasView.StickerItem) {
        val input = EditText(this).apply {
            setText(sticker.text)
            setSelection(sticker.text.length)
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit overlay")
            .setView(input)
            .setPositiveButton("Update") { _, _ ->
                val txt = input.text?.toString()?.trim().orEmpty()
                if (txt.isNotBlank()) {
                    binding.statusEditorCanvas.updateSticker(sticker.id, txt, sticker.color)
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                binding.statusEditorCanvas.removeSticker(sticker.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareStatus() {
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            StatusHelpers.showToast(this, "Sign in required to post status")
            return
        }

        isUploading = true
        binding.editorUploadOverlay.visibility = View.VISIBLE
        binding.pbEditorUpload.progress = 8
        binding.tvEditorUploadStatus.text = getString(R.string.status_uploading)

        val caption = binding.etStatusCaption.text?.toString()?.trim().orEmpty()
        val statusId = "st_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        lifecycleScope.launch {
            val profile = cachedProfile ?: runCatching { repository.observeUserProfile().firstOrNull() }.getOrNull()
            val peers = if (cachedPublicProfiles.isNotEmpty()) cachedPublicProfiles
            else runCatching { repository.observePublicProfiles().firstOrNull() }.getOrNull().orEmpty()

            val (privacy, visibleTo) = StatusHelpers.computeVisibleRecipients(profile, uid, peers)
            val userName = profile?.displayName?.ifBlank { Firebase.auth.currentUser?.displayName ?: "Vice User" }
                ?: (Firebase.auth.currentUser?.displayName ?: "Vice User")
            val avatarColor = profile?.avatarColorHex ?: "#128C7E"

            if (mediaType == "video") {
                val uri = mediaUri ?: return@launch
                val uploadRes = repository.uploadStatusVideoUri(uri, statusId) { pct ->
                    runOnUiThread {
                        binding.pbEditorUpload.progress = pct.coerceIn(10, 95)
                        binding.tvEditorUploadStatus.text = "Uploading video… $pct%"
                    }
                }
                if (uploadRes.isFailure) {
                    isUploading = false
                    binding.editorUploadOverlay.visibility = View.GONE
                    val msg = uploadRes.exceptionOrNull()?.localizedMessage
                        ?: "Video upload failed. Ensure Firebase Storage is enabled in your Firebase project."
                    StatusHelpers.showToast(this@StatusEditorActivity, msg)
                    return@launch
                }
                val (downloadUrl, storagePath) = uploadRes.getOrThrow()
                val postRes = repository.postStatusUpdate(
                    userName = userName,
                    avatarColorHex = avatarColor,
                    mediaType = "video",
                    caption = caption.ifBlank { "Video update" },
                    mediaUrl = downloadUrl,
                    storagePath = storagePath,
                    backgroundColorHex = "#000000",
                    privacy = privacy,
                    visibleTo = visibleTo,
                    statusIdOverride = statusId
                )
                finishShareResult(postRes.isSuccess, postRes.exceptionOrNull()?.localizedMessage)
            } else {
                val composite = withContext(Dispatchers.Default) {
                    binding.statusEditorCanvas.renderCompositeBitmap()
                }
                if (composite == null) {
                    isUploading = false
                    binding.editorUploadOverlay.visibility = View.GONE
                    StatusHelpers.showToast(this@StatusEditorActivity, "No image loaded")
                    return@launch
                }

                val uploadBytes = withContext(Dispatchers.Default) {
                    StatusHelpers.bitmapToUploadJpegBytes(composite)
                }

                var mediaUrl = ""
                var storagePath = ""
                var fallbackBase64 = ""

                val storageRes = repository.uploadStatusImageBytes(uploadBytes, statusId) { pct ->
                    runOnUiThread {
                        binding.pbEditorUpload.progress = pct.coerceIn(10, 90)
                        binding.tvEditorUploadStatus.text = "Uploading photo… $pct%"
                    }
                }

                if (storageRes.isSuccess) {
                    val pair = storageRes.getOrThrow()
                    mediaUrl = pair.first
                    storagePath = pair.second
                } else {
                    // Fallback to compressed JPEG Base64 when Storage bucket is not activated yet
                    fallbackBase64 = withContext(Dispatchers.Default) {
                        MediaUtils.bitmapToCompressedBase64(composite, maxDim = 720)
                    }
                }

                binding.pbEditorUpload.progress = 96
                val postRes = repository.postStatusUpdate(
                    userName = userName,
                    avatarColorHex = avatarColor,
                    mediaType = "photo",
                    caption = caption,
                    mediaBase64 = fallbackBase64,
                    mediaUrl = mediaUrl,
                    storagePath = storagePath,
                    backgroundColorHex = "#000000",
                    privacy = privacy,
                    visibleTo = visibleTo,
                    statusIdOverride = statusId
                )
                finishShareResult(postRes.isSuccess, postRes.exceptionOrNull()?.localizedMessage)
            }
        }
    }

    private fun finishShareResult(success: Boolean, errorMsg: String?) {
        isUploading = false
        binding.editorUploadOverlay.visibility = View.GONE
        if (success) {
            binding.pbEditorUpload.progress = 100
            StatusHelpers.showToast(this, "Status shared!")
            setResult(RESULT_OK)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            StatusHelpers.showToast(this, errorMsg ?: "Failed to publish status")
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"

        fun createIntent(context: Context, uri: Uri, mediaType: String): Intent {
            return Intent(context, StatusEditorActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_URI, uri.toString())
                putExtra(EXTRA_MEDIA_TYPE, mediaType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
