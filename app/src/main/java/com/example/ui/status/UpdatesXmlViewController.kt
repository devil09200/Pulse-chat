package com.example.ui.status

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.data.model.UserProfile
import com.example.databinding.BottomSheetAddStatusBinding
import com.example.databinding.ViewUpdatesStatusBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class UpdatesXmlViewController(
    private val rawContext: Context,
    private val onLaunchCameraPermission: (String) -> Unit,
    private val onLaunchTakePicture: (Uri) -> Unit,
    private val onLaunchCaptureVideo: (Uri) -> Unit,
    private val onLaunchGalleryPicker: (PickVisualMediaRequest) -> Unit
) {
    private val themedContext = ContextThemeWrapper(rawContext, R.style.Theme_PulseChat_StatusSubscreen)
    val binding: ViewUpdatesStatusBinding =
        ViewUpdatesStatusBinding.inflate(LayoutInflater.from(themedContext))

    private var currentUserId: String = ""
    private var userProfile: UserProfile? = null
    private var myStatuses: List<StatusUpdate> = emptyList()
    private var friendsStatuses: List<StatusUpdate> = emptyList()
    private var publicProfiles: List<PublicProfile> = emptyList()
    private var externalSearchQuery: String = ""
    private var localSearchQuery: String = ""

    private var pendingCameraMode: String = "photo" // "photo" | "video"
    var pendingCameraCaptureUri: Uri? = null
        private set

    private val feedAdapter = StatusFeedAdapter { group ->
        animatePress(binding.rvStatusUpdates)
        rawContext.startActivity(
            StatusViewerActivity.createIntent(
                context = rawContext,
                targetUserId = group.userId,
                isMyStatus = false
            )
        )
    }

    init {
        setupInsets()
        setupRecyclerView()
        setupHeaderActions()
        setupMyStatusAndQuickActions()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.updatesRoot) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            binding.updatesHeader.updatePadding(top = sysBars.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.updatesRoot)
    }

    private fun setupRecyclerView() {
        binding.rvStatusUpdates.layoutManager = LinearLayoutManager(themedContext)
        binding.rvStatusUpdates.adapter = feedAdapter
        binding.rvStatusUpdates.itemAnimator = null
    }

    private fun setupHeaderActions() {
        binding.btnHeaderSearch.setOnClickListener {
            val opening = binding.searchBarContainer.visibility != View.VISIBLE
            binding.searchBarContainer.visibility = if (opening) View.VISIBLE else View.GONE
            if (!opening) {
                binding.etStatusSearch.setText("")
                localSearchQuery = ""
                refreshFeed()
            } else {
                binding.etStatusSearch.requestFocus()
            }
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etStatusSearch.setText("")
            localSearchQuery = ""
            binding.searchBarContainer.visibility = View.GONE
            refreshFeed()
        }

        binding.etStatusSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                localSearchQuery = s?.toString().orEmpty()
                refreshFeed()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnHeaderCamera.setOnClickListener { view ->
            animatePress(view)
            showCameraCaptureChoiceDialog()
        }

        binding.btnHeaderMore.setOnClickListener { anchor ->
            val menu = PopupMenu(themedContext, anchor)
            menu.menu.add(0, 1, 0, R.string.status_privacy_title)
            menu.menu.add(0, 2, 1, R.string.add_status_text)
            if (myStatuses.isNotEmpty()) {
                menu.menu.add(0, 3, 2, "View my status")
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openStatusPrivacy()
                    2 -> openTextStatusEditor()
                    3 -> openMyStatusViewer()
                }
                true
            }
            menu.show()
        }
    }

    private fun setupMyStatusAndQuickActions() {
        binding.btnStatusPrivacyChip.setOnClickListener { v ->
            animatePress(v)
            openStatusPrivacy()
        }

        binding.rowMyStatus.setOnClickListener { v ->
            animatePress(v)
            if (myStatuses.isNotEmpty()) {
                openMyStatusViewer()
            } else {
                showAddStatusBottomSheet()
            }
        }

        binding.btnMyStatusMore.setOnClickListener { anchor ->
            val menu = PopupMenu(themedContext, anchor)
            menu.menu.add(0, 1, 0, "View my status")
            menu.menu.add(0, 2, 1, "Add new update")
            menu.menu.add(0, 3, 2, R.string.status_privacy_title)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openMyStatusViewer()
                    2 -> showAddStatusBottomSheet()
                    3 -> openStatusPrivacy()
                }
                true
            }
            menu.show()
        }

        binding.btnQuickCamera.setOnClickListener { v ->
            animatePress(v)
            showCameraCaptureChoiceDialog()
        }

        binding.btnQuickGallery.setOnClickListener { v ->
            animatePress(v)
            launchGalleryPicker()
        }

        binding.btnQuickText.setOnClickListener { v ->
            animatePress(v)
            openTextStatusEditor()
        }

        binding.fabTextStatus.setOnClickListener { v ->
            animatePress(v)
            openTextStatusEditor()
        }

        binding.fabAddStatus.setOnClickListener { v ->
            animatePress(v)
            showAddStatusBottomSheet()
        }
    }

    fun bind(
        userProfile: UserProfile?,
        myStatuses: List<StatusUpdate>,
        friendsStatuses: List<StatusUpdate>,
        publicProfiles: List<PublicProfile>,
        currentUserId: String,
        searchQuery: String
    ) {
        this.userProfile = userProfile
        this.myStatuses = myStatuses
        this.friendsStatuses = friendsStatuses
        this.publicProfiles = publicProfiles
        this.currentUserId = currentUserId
        this.externalSearchQuery = searchQuery

        val privacyLabel = StatusHelpers.formatPrivacyShortLabel(userProfile?.statusPrivacy ?: "everyone")
        binding.tvPrivacyChipLabel.text = privacyLabel

        val myName = userProfile?.displayName?.ifBlank { "My Status" } ?: "My Status"
        val myBmp = StatusHelpers.decodeAvatarBitmap(userProfile?.photoBase64)
        val myRingFlags = List(myStatuses.size) { false }
        binding.myStatusAvatar.bind(
            displayName = myName,
            colorHex = userProfile?.avatarColorHex ?: "#128C7E",
            bitmap = myBmp,
            seenFlags = myRingFlags,
            animateRing = true,
            defaultSubtleBorder = myStatuses.isEmpty()
        )

        if (myStatuses.isEmpty()) {
            binding.ivMyStatusPlus.visibility = View.VISIBLE
            binding.btnMyStatusMore.visibility = View.GONE
            binding.tvMyStatusSubtitle.text = rawContext.getString(R.string.my_status_subtitle_empty)
        } else {
            binding.ivMyStatusPlus.visibility = View.GONE
            binding.btnMyStatusMore.visibility = View.VISIBLE
            val latest = myStatuses.first()
            val time = StatusHelpers.formatStatusTimestamp(latest.createdAt)
            val totalViews = latest.viewedBy.filter { it != currentUserId }.size
            binding.tvMyStatusSubtitle.text =
                "${myStatuses.size} update(s) • $time • $totalViews view(s)"
        }

        refreshFeed()
    }

    private fun refreshFeed() {
        val activeQuery = if (localSearchQuery.isNotBlank()) localSearchQuery else externalSearchQuery
        val grouped = StatusFeedAdapter.buildGroupedItems(
            context = rawContext,
            statuses = friendsStatuses,
            currentUserId = currentUserId,
            publicProfiles = publicProfiles,
            searchQuery = activeQuery
        )
        feedAdapter.submitList(grouped)
        binding.statusEmptyState.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddStatusBottomSheet() {
        val dialog = BottomSheetDialog(themedContext)
        val sheet = BottomSheetAddStatusBinding.inflate(LayoutInflater.from(themedContext))
        dialog.setContentView(sheet.root)

        sheet.optionCamera.setOnClickListener {
            dialog.dismiss()
            showCameraCaptureChoiceDialog()
        }
        sheet.optionGallery.setOnClickListener {
            dialog.dismiss()
            launchGalleryPicker()
        }
        sheet.optionTextStatus.setOnClickListener {
            dialog.dismiss()
            openTextStatusEditor()
        }
        dialog.show()
    }

    private fun showCameraCaptureChoiceDialog() {
        val options = arrayOf(
            rawContext.getString(R.string.status_camera_photo),
            rawContext.getString(R.string.status_camera_video)
        )
        MaterialAlertDialogBuilder(themedContext)
            .setTitle(R.string.add_status_camera)
            .setItems(options) { _, which ->
                pendingCameraMode = if (which == 0) "photo" else "video"
                startCameraWithPermissionCheck()
            }
            .show()
    }

    private fun startCameraWithPermissionCheck() {
        val granted = ContextCompat.checkSelfPermission(
            rawContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchPendingCameraCapture()
        } else {
            onLaunchCameraPermission(Manifest.permission.CAMERA)
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            launchPendingCameraCapture()
        } else {
            MaterialAlertDialogBuilder(themedContext)
                .setTitle("Camera permission needed")
                .setMessage(R.string.status_camera_permission_denied)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", rawContext.packageName, null)
                    )
                    rawContext.startActivity(intent)
                }
                .setNeutralButton("Use Gallery") { _, _ ->
                    launchGalleryPicker()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchPendingCameraCapture() {
        val dir = File(rawContext.cacheDir, "status_camera").apply { mkdirs() }
        val ext = if (pendingCameraMode == "video") "mp4" else "jpg"
        val file = File(dir, "capture_${System.currentTimeMillis()}.$ext")
        val uri = FileProvider.getUriForFile(
            rawContext,
            "${rawContext.packageName}.fileprovider",
            file
        )
        pendingCameraCaptureUri = uri
        if (pendingCameraMode == "video") {
            onLaunchCaptureVideo(uri)
        } else {
            onLaunchTakePicture(uri)
        }
    }

    fun onCameraCaptureSuccess(mediaType: String) {
        val uri = pendingCameraCaptureUri ?: return
        pendingCameraCaptureUri = null
        openMediaStatusEditor(uri, mediaType)
    }

    fun onGalleryMediaSelected(uri: Uri) {
        val mime = rawContext.contentResolver.getType(uri).orEmpty()
        val mediaType = if (mime.startsWith("video/")) "video" else "photo"
        openMediaStatusEditor(uri, mediaType)
    }

    private fun launchGalleryPicker() {
        onLaunchGalleryPicker(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun openMediaStatusEditor(uri: Uri, mediaType: String) {
        rawContext.startActivity(StatusEditorActivity.createIntent(rawContext, uri, mediaType))
    }

    private fun openTextStatusEditor() {
        rawContext.startActivity(TextStatusActivity.createIntent(rawContext))
    }

    private fun openMyStatusViewer() {
        rawContext.startActivity(
            StatusViewerActivity.createIntent(
                context = rawContext,
                targetUserId = currentUserId,
                isMyStatus = true
            )
        )
    }

    private fun openStatusPrivacy() {
        rawContext.startActivity(StatusPrivacyActivity.createIntent(rawContext))
    }

    private fun animatePress(view: View) {
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(80L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
            .start()
    }
}
