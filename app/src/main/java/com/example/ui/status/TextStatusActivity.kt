package com.example.ui.status

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.model.PublicProfile
import com.example.data.model.UserProfile
import com.example.data.repository.PulseChatRepository
import com.example.databinding.ActivityTextStatusBinding
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class TextStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextStatusBinding
    private lateinit var repository: PulseChatRepository

    private var colorIndex = 0
    private var fontIndex = 0
    private var isPosting = false
    private var cachedProfile: UserProfile? = null
    private var cachedPeers: List<PublicProfile> = emptyList()

    private val privacyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        lifecycleScope.launch {
            cachedProfile = runCatching { repository.observeUserProfile().firstOrNull() }.getOrNull()
            updatePrivacyBadge()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTextStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = StatusHelpers.createRepository(this)
        setupInsets()
        applyBackgroundColor(animate = false)
        applyFontStyle()
        setupEmojiTray()
        observeProfileAndPeers()
        setupListeners()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.textStatusTopBar.updatePadding(top = sysBars.top + dp(6))
            binding.textStatusBottomBar.updatePadding(bottom = maxOf(sysBars.bottom, ime.bottom) + dp(10))
            insets
        }
    }

    private fun observeProfileAndPeers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeUserProfile().collect { profile ->
                        cachedProfile = profile
                        updatePrivacyBadge()
                    }
                }
                launch {
                    repository.observePublicProfiles().collect { peers ->
                        cachedPeers = peers
                    }
                }
            }
        }
    }

    private fun updatePrivacyBadge() {
        val label = StatusHelpers.formatPrivacyShortLabel(cachedProfile?.statusPrivacy ?: "everyone")
        binding.tvTextPrivacyLabel.text = "Status ($label)"
    }

    private fun setupListeners() {
        binding.btnTextClose.setOnClickListener { finish() }

        binding.btnTextPalette.setOnClickListener {
            colorIndex = (colorIndex + 1) % StatusHelpers.TEXT_BG_COLORS.size
            applyBackgroundColor(animate = true)
        }

        binding.btnTextFont.setOnClickListener {
            fontIndex = (fontIndex + 1) % StatusHelpers.FONT_OPTIONS.size
            applyFontStyle()
        }

        binding.btnTextEmoji.setOnClickListener {
            binding.textEmojiTray.visibility =
                if (binding.textEmojiTray.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnTextPrivacy.setOnClickListener {
            privacyLauncher.launch(StatusPrivacyActivity.createIntent(this))
        }

        binding.fabTextShare.setOnClickListener {
            if (!isPosting) publishTextStatus()
        }
    }

    private fun applyBackgroundColor(animate: Boolean) {
        val targetColor = StatusHelpers.parseColorSafe(StatusHelpers.TEXT_BG_COLORS[colorIndex])
        if (!animate) {
            binding.textStatusRoot.setBackgroundColor(targetColor)
            return
        }
        val prevIndex = (colorIndex - 1 + StatusHelpers.TEXT_BG_COLORS.size) % StatusHelpers.TEXT_BG_COLORS.size
        val startColor = StatusHelpers.parseColorSafe(StatusHelpers.TEXT_BG_COLORS[prevIndex])
        ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 260L
            addUpdateListener { binding.textStatusRoot.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyFontStyle() {
        val (key, label) = StatusHelpers.FONT_OPTIONS[fontIndex]
        val (tf, style) = StatusHelpers.resolveTypeface(key)
        binding.etTextStatus.setTypeface(tf, style)
        binding.btnTextFont.setTypeface(tf, style)
        binding.tvFontStyleBadge.text = "$label • Tap Aa to switch font"
    }

    private fun setupEmojiTray() {
        val container = binding.textEmojiContainer
        container.removeAllViews()
        StatusHelpers.EMOJI_PALETTE.forEach { emoji ->
            val tv = TextView(this).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = "Insert emoji $emoji"
                setOnClickListener {
                    val start = binding.etTextStatus.selectionStart.coerceAtLeast(0)
                    binding.etTextStatus.text?.insert(start, emoji)
                }
            }
            container.addView(tv)
        }
    }

    private fun publishTextStatus() {
        val text = binding.etTextStatus.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            StatusHelpers.showToast(this, "Type something to share your status")
            return
        }
        val uid = Firebase.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            StatusHelpers.showToast(this, "Please sign in first")
            return
        }

        isPosting = true
        binding.textUploadOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            val profile = cachedProfile ?: runCatching { repository.observeUserProfile().firstOrNull() }.getOrNull()
            val peers = if (cachedPeers.isNotEmpty()) cachedPeers
            else runCatching { repository.observePublicProfiles().firstOrNull() }.getOrNull().orEmpty()

            val (privacy, visibleTo) = StatusHelpers.computeVisibleRecipients(profile, uid, peers)
            val userName = profile?.displayName?.ifBlank { Firebase.auth.currentUser?.displayName ?: "Vice User" }
                ?: (Firebase.auth.currentUser?.displayName ?: "Vice User")
            val avatarColor = profile?.avatarColorHex ?: "#128C7E"
            val bgHex = StatusHelpers.TEXT_BG_COLORS[colorIndex]
            val fontStyle = StatusHelpers.FONT_OPTIONS[fontIndex].first

            val res = repository.postStatusUpdate(
                userName = userName,
                avatarColorHex = avatarColor,
                mediaType = "text",
                caption = text,
                backgroundColorHex = bgHex,
                fontStyle = fontStyle,
                privacy = privacy,
                visibleTo = visibleTo
            )

            isPosting = false
            binding.textUploadOverlay.visibility = View.GONE
            if (res.isSuccess) {
                StatusHelpers.showToast(this@TextStatusActivity, "Text status shared!")
                setResult(RESULT_OK)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                StatusHelpers.showToast(
                    this@TextStatusActivity,
                    res.exceptionOrNull()?.localizedMessage ?: "Failed to share text status"
                )
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TextStatusActivity::class.java)
    }
}
