package com.example.ui.status

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.data.repository.PulseChatRepository
import com.example.databinding.ActivityStatusViewerBinding
import com.example.databinding.BottomSheetStatusViewersBinding
import com.example.databinding.ItemStatusViewerRowBinding
import com.example.util.MediaUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class StatusViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusViewerBinding
    private lateinit var repository: PulseChatRepository

    private var isMyStatusMode: Boolean = false
    private var initialTargetUserId: String = ""
    private var currentUid: String = ""

    private var userProfile: UserProfile? = null
    private var publicProfiles: List<PublicProfile> = emptyList()
    private var playlists: List<UserPlaylist> = emptyList()
    private var currentUserIndex: Int = 0
    private var currentStatusIndex: Int = 0
    private var renderedStatusId: String? = null

    private var progressBars: List<ProgressBar> = emptyList()
    private var storyAnimator: ValueAnimator? = null
    private var videoProgressJob: Job? = null
    private var exoPlayer: ExoPlayer? = null
    private var isPaused: Boolean = false
    private var isMuted: Boolean = false

    private data class UserPlaylist(
        val userId: String,
        val userName: String,
        val avatarColorHex: String,
        val avatarPhotoBase64: String,
        val statuses: List<StatusUpdate>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityStatusViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = StatusHelpers.createRepository(this)
        currentUid = Firebase.auth.currentUser?.uid.orEmpty()
        isMyStatusMode = intent.getBooleanExtra(EXTRA_IS_MY_STATUS, false)
        initialTargetUserId = intent.getStringExtra(EXTRA_TARGET_USER_ID).orEmpty()

        setupInsets()
        setupButtons()
        setupQuickReactions()
        setupGestureOverlay()
        observeData()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.viewerTopContainer.updatePadding(top = sysBars.top + dp(10))
            binding.viewerBottomContainer.updatePadding(bottom = maxOf(sysBars.bottom, ime.bottom) + dp(14))
            insets
        }
    }

    private fun setupButtons() {
        binding.btnViewerClose.setOnClickListener {
            finishWithTransition()
        }
        binding.btnViewerPause.setOnClickListener {
            setViewerPaused(!isPaused)
        }
        binding.btnViewerMute.setOnClickListener {
            isMuted = !isMuted
            exoPlayer?.volume = if (isMuted) 0f else 1f
            binding.btnViewerMute.setImageResource(
                if (isMuted) R.drawable.ic_status_volume_off else R.drawable.ic_status_volume_up
            )
        }
        binding.btnViewerDelete.setOnClickListener {
            val status = currentStatus() ?: return@setOnClickListener
            setViewerPaused(true)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.status_delete_confirm_title)
                .setMessage(R.string.status_delete_confirm_msg)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        val res = repository.deleteStatusUpdate(status)
                        if (res.isSuccess) {
                            StatusHelpers.showToast(this@StatusViewerActivity, "Status deleted")
                        } else {
                            StatusHelpers.showToast(this@StatusViewerActivity, "Could not delete status")
                            setViewerPaused(false)
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> setViewerPaused(false) }
                .setOnCancelListener { setViewerPaused(false) }
                .show()
        }
        binding.viewerOwnerViewsBar.setOnClickListener {
            val status = currentStatus() ?: return@setOnClickListener
            showViewersBottomSheet(status)
        }
        binding.btnViewerSendReply.setOnClickListener { sendReplyFromInput() }
        binding.etViewerReply.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendReplyFromInput()
                true
            } else {
                false
            }
        }
        binding.etViewerReply.setOnFocusChangeListener { _, hasFocus ->
            setViewerPaused(hasFocus)
        }
    }

    private fun setupQuickReactions() {
        val row = binding.viewerReactionsRow
        row.removeAllViews()
        StatusHelpers.QUICK_REACTIONS.forEach { emoji ->
            val btn = TextView(this).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(44)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                contentDescription = "React $emoji"
                setOnClickListener {
                    sendPrivateReply(emoji)
                }
            }
            row.addView(btn)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureOverlay() {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var longPressPaused = false
        var longPressRunnable: Runnable? = null

        binding.viewerGestureOverlay.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    longPressPaused = false
                    longPressRunnable = Runnable {
                        longPressPaused = true
                        setViewerPaused(true)
                    }
                    v.postDelayed(longPressRunnable!!, 220L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > dp(24) || abs(dy) > dp(24)) {
                        longPressRunnable?.let { v.removeCallbacks(it) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    if (longPressPaused) {
                        setViewerPaused(false)
                        return@setOnTouchListener true
                    }
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val duration = System.currentTimeMillis() - downTime
                    val swipeThreshold = dp(56)

                    when {
                        dy > swipeThreshold && abs(dy) > abs(dx) -> {
                            finishWithTransition()
                        }
                        dx < -swipeThreshold && abs(dx) > abs(dy) -> {
                            goToNextUser()
                        }
                        dx > swipeThreshold && abs(dx) > abs(dy) -> {
                            goToPrevUser()
                        }
                        duration < 300L && abs(dx) < dp(24) && abs(dy) < dp(24) -> {
                            if (binding.etViewerReply.hasFocus()) {
                                binding.etViewerReply.clearFocus()
                            } else if (event.x < v.width * 0.30f) {
                                goToPrevStatus()
                            } else {
                                goToNextStatus()
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { v.removeCallbacks(it) }
                    if (longPressPaused) setViewerPaused(false)
                    true
                }

                else -> false
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeUserProfile().collect { profile ->
                        userProfile = profile
                    }
                }
                launch {
                    repository.observePublicProfiles().collect { peers ->
                        publicProfiles = peers
                        refreshCurrentHeaderAvatar()
                    }
                }
                launch {
                    val flow = if (isMyStatusMode) repository.observeMyStatuses()
                    else repository.observeFriendsStatuses()

                    flow.collect { statuses ->
                        rebuildPlaylists(statuses)
                    }
                }
            }
        }
    }

    private fun rebuildPlaylists(statuses: List<StatusUpdate>) {
        if (statuses.isEmpty()) {
            finishWithTransition()
            return
        }

        val profileMap = publicProfiles.associateBy { it.userId }
        val activeUserId = playlists.getOrNull(currentUserIndex)?.userId ?: initialTargetUserId
        val activeStatusId = currentStatus()?.id

        val newPlaylists = if (isMyStatusMode) {
            val ordered = statuses.sortedBy { it.createdAt?.toDate()?.time ?: 0L }
            val name = userProfile?.displayName?.ifBlank { "My Status" } ?: "My Status"
            listOf(
                UserPlaylist(
                    userId = currentUid,
                    userName = name,
                    avatarColorHex = userProfile?.avatarColorHex ?: "#128C7E",
                    avatarPhotoBase64 = userProfile?.photoBase64 ?: "",
                    statuses = ordered
                )
            )
        } else {
            statuses
                .groupBy { it.userId }
                .map { (uid, list) ->
                    val ordered = list.sortedBy { it.createdAt?.toDate()?.time ?: 0L }
                    val peer = profileMap[uid]
                    UserPlaylist(
                        userId = uid,
                        userName = peer?.displayName?.ifBlank { ordered.first().userName }
                            ?: ordered.first().userName,
                        avatarColorHex = peer?.avatarColorHex?.ifBlank { ordered.first().avatarColorHex }
                            ?: ordered.first().avatarColorHex,
                        avatarPhotoBase64 = peer?.photoBase64 ?: "",
                        statuses = ordered
                    )
                }
        }

        if (newPlaylists.isEmpty()) {
            finishWithTransition()
            return
        }

        playlists = newPlaylists

        val foundUserIdx = newPlaylists.indexOfFirst { it.userId == activeUserId }
        currentUserIndex = if (foundUserIdx >= 0) foundUserIdx else 0
        val currentList = playlists[currentUserIndex].statuses

        if (activeStatusId != null) {
            val foundStatusIdx = currentList.indexOfFirst { it.id == activeStatusId }
            currentStatusIndex = if (foundStatusIdx >= 0) foundStatusIdx else 0
        } else {
            val firstUnseen = currentList.indexOfFirst { currentUid !in it.viewedBy }
            currentStatusIndex = if (firstUnseen >= 0) firstUnseen else 0
        }

        val activeStatus = currentStatus()
        if (activeStatus == null || activeStatus.id != renderedStatusId) {
            renderCurrentStatus()
        } else {
            // Refresh live view count if owner views list updated
            updateOwnerFooter(activeStatus)
        }
    }

    private fun refreshCurrentHeaderAvatar() {
        val playlist = playlists.getOrNull(currentUserIndex) ?: return
        val peer = publicProfiles.firstOrNull { it.userId == playlist.userId }
        val photo = if (playlist.userId == currentUid) userProfile?.photoBase64 ?: "" else peer?.photoBase64 ?: playlist.avatarPhotoBase64
        binding.viewerAvatar.bind(
            displayName = playlist.userName,
            colorHex = playlist.avatarColorHex,
            bitmap = StatusHelpers.decodeAvatarBitmap(photo),
            seenFlags = emptyList()
        )
    }

    private fun currentStatus(): StatusUpdate? {
        return playlists.getOrNull(currentUserIndex)?.statuses?.getOrNull(currentStatusIndex)
    }

    private fun renderCurrentStatus() {
        val playlist = playlists.getOrNull(currentUserIndex) ?: return
        val status = playlist.statuses.getOrNull(currentStatusIndex) ?: return
        renderedStatusId = status.id
        isPaused = false
        binding.btnViewerPause.setImageResource(R.drawable.ic_status_pause)

        stopPlaybackAndTimers()
        buildSegmentedProgressBars(playlist.statuses.size)
        updateCompletedSegments(currentStatusIndex)

        binding.tvViewerName.text = if (playlist.userId == currentUid) "My Status" else playlist.userName
        binding.tvViewerTimestamp.text = StatusHelpers.formatStatusTimestamp(status.createdAt)
        refreshCurrentHeaderAvatar()

        val isOwner = status.userId == currentUid
        binding.btnViewerDelete.visibility = if (isOwner) View.VISIBLE else View.GONE
        binding.viewerOwnerViewsBar.visibility = if (isOwner) View.VISIBLE else View.GONE
        binding.viewerReactionsScroll.visibility = if (isOwner) View.GONE else View.VISIBLE
        binding.viewerReplyBar.visibility = if (isOwner) View.GONE else View.VISIBLE
        updateOwnerFooter(status)

        // Reset stage visibility
        binding.viewerTextContainer.visibility = View.GONE
        binding.ivViewerPhoto.visibility = View.GONE
        binding.pvViewerVideo.visibility = View.GONE
        binding.btnViewerMute.visibility = View.GONE
        binding.pbViewerLoading.visibility = View.GONE

        when (status.mediaType) {
            "video" -> {
                binding.viewerMediaStage.setBackgroundColor(Color.BLACK)
                binding.pvViewerVideo.visibility = View.VISIBLE
                binding.btnViewerMute.visibility = View.VISIBLE
                binding.pbViewerLoading.visibility = View.VISIBLE
                showCaptionIfPresent(status.caption)
                playVideoStatus(status)
            }

            "photo" -> {
                binding.viewerMediaStage.setBackgroundColor(Color.BLACK)
                binding.ivViewerPhoto.visibility = View.VISIBLE
                showCaptionIfPresent(status.caption)
                if (status.mediaUrl.isNotBlank()) {
                    binding.pbViewerLoading.visibility = View.VISIBLE
                    binding.ivViewerPhoto.load(status.mediaUrl) {
                        crossfade(true)
                        listener(
                            onSuccess = { _, _ ->
                                binding.pbViewerLoading.visibility = View.GONE
                                startTimedProgress(PHOTO_STORY_DURATION_MS)
                            },
                            onError = { _, _ ->
                                binding.pbViewerLoading.visibility = View.GONE
                                val fallback = MediaUtils.decodeBase64ToBitmap(status.mediaBase64)
                                if (fallback != null) binding.ivViewerPhoto.setImageBitmap(fallback)
                                startTimedProgress(PHOTO_STORY_DURATION_MS)
                            }
                        )
                    }
                } else {
                    val bmp = MediaUtils.decodeBase64ToBitmap(status.mediaBase64)
                    if (bmp != null) {
                        binding.ivViewerPhoto.setImageBitmap(bmp)
                    }
                    startTimedProgress(PHOTO_STORY_DURATION_MS)
                }
            }

            else -> {
                val bg = StatusHelpers.parseColorSafe(status.backgroundColorHex)
                binding.viewerMediaStage.setBackgroundColor(bg)
                binding.viewerTextContainer.setBackgroundColor(bg)
                binding.viewerTextContainer.visibility = View.VISIBLE
                binding.tvViewerCaption.visibility = View.GONE
                val (tf, style) = StatusHelpers.resolveTypeface(status.fontStyle)
                binding.tvViewerTextStory.setTypeface(tf, style)
                binding.tvViewerTextStory.text = status.caption
                startTimedProgress(TEXT_STORY_DURATION_MS)
            }
        }

        if (!isOwner && currentUid !in status.viewedBy) {
            lifecycleScope.launch {
                repository.markStatusViewed(status)
            }
        }
    }

    private fun updateOwnerFooter(status: StatusUpdate) {
        val count = status.viewedBy.filter { it != currentUid }.size
        binding.tvViewerViewCount.text = if (count == 1) "1 view" else "$count views"
    }

    private fun showCaptionIfPresent(caption: String) {
        if (caption.isNotBlank()) {
            binding.tvViewerCaption.visibility = View.VISIBLE
            binding.tvViewerCaption.text = caption
        } else {
            binding.tvViewerCaption.visibility = View.GONE
        }
    }

    private fun buildSegmentedProgressBars(count: Int) {
        val container = binding.viewerProgressSegments
        container.removeAllViews()
        val bars = mutableListOf<ProgressBar>()
        val trackColor = Color.argb(95, 255, 255, 255)
        val fillColor = Color.WHITE

        for (i in 0 until count) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                val layer = LayerDrawable(
                    arrayOf(
                        ColorDrawable(trackColor),
                        ClipDrawable(ColorDrawable(fillColor), Gravity.START, ClipDrawable.HORIZONTAL)
                    )
                )
                layer.setId(0, android.R.id.background)
                layer.setId(1, android.R.id.progress)
                progressDrawable = layer

                layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f).apply {
                    marginEnd = if (i < count - 1) dp(4) else 0
                }
            }
            container.addView(pb)
            bars.add(pb)
        }
        progressBars = bars
    }

    private fun updateCompletedSegments(activeIndex: Int) {
        progressBars.forEachIndexed { idx, pb ->
            pb.progress = when {
                idx < activeIndex -> 1000
                idx == activeIndex -> 0
                else -> 0
            }
        }
    }

    private fun startTimedProgress(durationMs: Long) {
        storyAnimator?.cancel()
        val bar = progressBars.getOrNull(currentStatusIndex) ?: return
        storyAnimator = ValueAnimator.ofInt(0, 1000).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                bar.progress = anim.animatedValue as Int
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && !isFinishing) {
                        goToNextStatus()
                    }
                }
            })
            start()
        }
    }

    private fun playVideoStatus(status: StatusUpdate) {
        exoPlayer?.release()
        if (status.mediaUrl.isBlank()) {
            binding.pbViewerLoading.visibility = View.GONE
            showCaptionIfPresent(status.caption.ifBlank { "Video unavailable" })
            startTimedProgress(TEXT_STORY_DURATION_MS)
            return
        }

        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(status.mediaUrl))
            volume = if (isMuted) 0f else 1f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.pbViewerLoading.visibility = View.GONE
                            startVideoProgressPolling()
                        }
                        Player.STATE_ENDED -> {
                            goToNextStatus()
                        }
                        Player.STATE_BUFFERING -> {
                            binding.pbViewerLoading.visibility = View.VISIBLE
                        }
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
        exoPlayer = player
        binding.pvViewerVideo.player = player
    }

    private fun startVideoProgressPolling() {
        videoProgressJob?.cancel()
        val bar = progressBars.getOrNull(currentStatusIndex) ?: return
        videoProgressJob = lifecycleScope.launch {
            while (isActive) {
                val player = exoPlayer ?: break
                val dur = player.duration
                if (dur > 0L) {
                    val pct = ((player.currentPosition * 1000L) / dur).toInt().coerceIn(0, 1000)
                    bar.progress = pct
                }
                delay(40L)
            }
        }
    }

    private fun setViewerPaused(paused: Boolean) {
        isPaused = paused
        binding.btnViewerPause.setImageResource(
            if (paused) R.drawable.ic_status_play else R.drawable.ic_status_pause
        )
        if (paused) {
            storyAnimator?.pause()
            exoPlayer?.pause()
        } else {
            storyAnimator?.resume()
            exoPlayer?.play()
        }
    }

    private fun goToNextStatus() {
        val currentList = playlists.getOrNull(currentUserIndex)?.statuses ?: return
        if (currentStatusIndex + 1 < currentList.size) {
            currentStatusIndex++
            renderCurrentStatus()
        } else {
            goToNextUser()
        }
    }

    private fun goToPrevStatus() {
        if (currentStatusIndex > 0) {
            currentStatusIndex--
            renderCurrentStatus()
        } else {
            goToPrevUser()
        }
    }

    private fun goToNextUser() {
        if (currentUserIndex + 1 < playlists.size) {
            currentUserIndex++
            currentStatusIndex = 0
            renderCurrentStatus()
        } else {
            finishWithTransition()
        }
    }

    private fun goToPrevUser() {
        if (currentUserIndex > 0) {
            currentUserIndex--
            currentStatusIndex = 0
            renderCurrentStatus()
        } else {
            renderCurrentStatus()
        }
    }

    private fun sendReplyFromInput() {
        val text = binding.etViewerReply.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.etViewerReply.setText("")
        binding.etViewerReply.clearFocus()
        sendPrivateReply(text)
    }

    private fun sendPrivateReply(replyText: String) {
        val status = currentStatus() ?: return
        if (status.userId == currentUid) return
        val myName = userProfile?.displayName?.ifBlank { Firebase.auth.currentUser?.displayName ?: "Vice User" }
            ?: (Firebase.auth.currentUser?.displayName ?: "Vice User")
        val targetName = playlists.getOrNull(currentUserIndex)?.userName ?: status.userName
        val targetColor = playlists.getOrNull(currentUserIndex)?.avatarColorHex ?: status.avatarColorHex

        lifecycleScope.launch {
            val res = repository.sendPrivateStatusReply(
                targetUserId = status.userId,
                targetUserName = targetName,
                targetAvatarColorHex = targetColor,
                senderName = myName,
                statusSummary = StatusHelpers.summarizeStatusForReply(status),
                replyText = replyText
            )
            if (res.isSuccess) {
                StatusHelpers.showToast(this@StatusViewerActivity, "Reply sent to $targetName")
            } else {
                StatusHelpers.showToast(this@StatusViewerActivity, "Could not send reply")
            }
        }
    }

    private fun showViewersBottomSheet(status: StatusUpdate) {
        setViewerPaused(true)
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetStatusViewersBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val viewerIds = status.viewedBy.filter { it != currentUid }.distinct()
        val profileMap = publicProfiles.associateBy { it.userId }
        val viewerProfiles = viewerIds.map { uid ->
            profileMap[uid] ?: PublicProfile(userId = uid, displayName = "Vice User")
        }

        sheetBinding.tvViewersSheetTitle.text =
            "${getString(R.string.status_viewed_by_title)} (${viewerProfiles.size})"
        sheetBinding.rvStatusViewers.layoutManager = LinearLayoutManager(this)
        sheetBinding.rvStatusViewers.adapter = ViewersSheetAdapter(viewerProfiles)
        sheetBinding.tvViewersEmpty.visibility = if (viewerProfiles.isEmpty()) View.VISIBLE else View.GONE

        sheetBinding.btnViewersDelete.setOnClickListener {
            dialog.dismiss()
            binding.btnViewerDelete.performClick()
        }

        dialog.setOnDismissListener {
            if (!isFinishing) setViewerPaused(false)
        }
        dialog.show()
    }

    private class ViewersSheetAdapter(
        private val viewers: List<PublicProfile>
    ) : RecyclerView.Adapter<ViewersSheetAdapter.Holder>() {
        class Holder(val binding: ItemStatusViewerRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemStatusViewerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = viewers[position]
            holder.binding.tvViewerRowName.text = item.displayName.ifBlank { "Vice User" }
            holder.binding.viewerRowAvatar.bind(
                displayName = item.displayName,
                colorHex = item.avatarColorHex,
                bitmap = StatusHelpers.decodeAvatarBitmap(item.photoBase64),
                seenFlags = emptyList()
            )
        }

        override fun getItemCount(): Int = viewers.size
    }

    private fun stopPlaybackAndTimers() {
        storyAnimator?.cancel()
        storyAnimator = null
        videoProgressJob?.cancel()
        videoProgressJob = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun finishWithTransition() {
        stopPlaybackAndTimers()
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onStop() {
        super.onStop()
        setViewerPaused(true)
    }

    override fun onDestroy() {
        stopPlaybackAndTimers()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TARGET_USER_ID = "extra_target_user_id"
        private const val EXTRA_IS_MY_STATUS = "extra_is_my_status"
        private const val TEXT_STORY_DURATION_MS = 5500L
        private const val PHOTO_STORY_DURATION_MS = 6000L

        fun createIntent(
            context: Context,
            targetUserId: String,
            isMyStatus: Boolean
        ): Intent {
            return Intent(context, StatusViewerActivity::class.java).apply {
                putExtra(EXTRA_TARGET_USER_ID, targetUserId)
                putExtra(EXTRA_IS_MY_STATUS, isMyStatus)
            }
        }
    }
}
