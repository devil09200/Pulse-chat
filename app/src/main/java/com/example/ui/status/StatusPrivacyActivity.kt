package com.example.ui.status

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.repository.PulseChatRepository
import com.example.databinding.ActivityStatusPrivacyBinding
import com.example.databinding.ItemStatusPrivacyContactBinding
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class StatusPrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusPrivacyBinding
    private lateinit var repository: PulseChatRepository
    private lateinit var contactsAdapter: PrivacyContactsAdapter

    private var currentUid: String = ""
    private var selectedPrivacy: String = "contacts"
    private val excludedIds = mutableSetOf<String>()
    private val allowedIds = mutableSetOf<String>()
    private var allContacts: List<PublicProfile> = emptyList()
    private var searchQuery: String = ""
    private var initializedFromProfile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityStatusPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = StatusHelpers.createRepository(this)
        currentUid = Firebase.auth.currentUser?.uid.orEmpty()

        setupInsets()
        setupContactsList()
        setupListeners()
        observeData()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.privacyHeader.updatePadding(top = sysBars.top)
            binding.fabPrivacySave.translationY = -sysBars.bottom.toFloat()
            insets
        }
    }

    private fun setupContactsList() {
        contactsAdapter = PrivacyContactsAdapter { profile ->
            val targetSet = if (selectedPrivacy == "contacts_except") excludedIds else allowedIds
            if (profile.userId in targetSet) {
                targetSet.remove(profile.userId)
            } else {
                targetSet.add(profile.userId)
            }
            refreshContactsFilter()
        }
        binding.rvPrivacyContacts.layoutManager = LinearLayoutManager(this)
        binding.rvPrivacyContacts.adapter = contactsAdapter
    }

    private fun setupListeners() {
        binding.btnPrivacyBack.setOnClickListener { saveAndClose() }
        binding.fabPrivacySave.setOnClickListener { saveAndClose() }

        binding.rgStatusPrivacy.setOnCheckedChangeListener { _, checkedId ->
            selectedPrivacy = when (checkedId) {
                R.id.rb_privacy_contacts -> "contacts"
                R.id.rb_privacy_except -> "contacts_except"
                R.id.rb_privacy_only_with -> "only_share_with"
                else -> "everyone"
            }
            updateContactPickerVisibility()
            refreshContactsFilter()
        }

        binding.etPrivacyContactSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                refreshContactsFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeUserProfile().collect { profile ->
                        if (profile != null && !initializedFromProfile) {
                            initializedFromProfile = true
                            selectedPrivacy = profile.statusPrivacy.ifBlank { "everyone" }
                            excludedIds.clear()
                            excludedIds.addAll(profile.statusExcludedIds)
                            allowedIds.clear()
                            allowedIds.addAll(profile.statusAllowedIds)
                            applyRadioSelection(selectedPrivacy)
                        }
                    }
                }
                launch {
                    repository.observePublicProfiles().collect { peers ->
                        allContacts = peers.filter { it.userId != currentUid }
                        refreshContactsFilter()
                    }
                }
            }
        }
    }

    private fun applyRadioSelection(privacy: String) {
        val radioId = when (privacy) {
            "contacts" -> R.id.rb_privacy_contacts
            "contacts_except" -> R.id.rb_privacy_except
            "only_share_with" -> R.id.rb_privacy_only_with
            else -> R.id.rb_privacy_everyone
        }
        binding.rgStatusPrivacy.check(radioId)
        updateContactPickerVisibility()
    }

    private fun updateContactPickerVisibility() {
        val showPicker = selectedPrivacy == "contacts_except" || selectedPrivacy == "only_share_with"
        binding.privacyContactsSection.visibility = if (showPicker) View.VISIBLE else View.GONE
        binding.tvPrivacyPickerHeader.text = when (selectedPrivacy) {
            "contacts_except" -> "Hide status from (${excludedIds.size} selected)"
            "only_share_with" -> "Only share with (${allowedIds.size} selected)"
            else -> "Select contacts"
        }
        binding.tvPrivacyDescription.text = when (selectedPrivacy) {
            "contacts" -> getString(R.string.status_privacy_my_contacts_desc)
            "contacts_except" -> getString(R.string.status_privacy_except_desc)
            "only_share_with" -> getString(R.string.status_privacy_only_with_desc)
            else -> getString(R.string.status_privacy_everyone_desc)
        }
    }

    private fun refreshContactsFilter() {
        updateContactPickerVisibility()
        val filtered = if (searchQuery.isBlank()) {
            allContacts
        } else {
            allContacts.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
        val activeSet = if (selectedPrivacy == "contacts_except") excludedIds else allowedIds
        contactsAdapter.submit(filtered, activeSet)
        binding.tvPrivacyContactsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveAndClose() {
        lifecycleScope.launch {
            val res = repository.updateStatusPrivacySettings(
                privacy = selectedPrivacy,
                excludedIds = excludedIds.toList(),
                allowedIds = allowedIds.toList()
            )
            if (res.isSuccess) {
                StatusHelpers.showToast(this@StatusPrivacyActivity, "Status privacy updated")
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private class PrivacyContactsAdapter(
        private val onToggle: (PublicProfile) -> Unit
    ) : RecyclerView.Adapter<PrivacyContactsAdapter.Holder>() {

        private var items: List<PublicProfile> = emptyList()
        private var checkedIds: Set<String> = emptySet()

        class Holder(val binding: ItemStatusPrivacyContactBinding) : RecyclerView.ViewHolder(binding.root)

        fun submit(newItems: List<PublicProfile>, newCheckedIds: Set<String>) {
            items = newItems
            checkedIds = newCheckedIds.toSet()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemStatusPrivacyContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val checked = item.userId in checkedIds
            holder.binding.tvPrivacyContactName.text = item.displayName.ifBlank { "Vice User" }
            holder.binding.tvPrivacyContactAbout.text = item.about.ifBlank { "Available on Vice Messenger" }
            holder.binding.cbPrivacyContact.isChecked = checked
            holder.binding.privacyContactAvatar.bind(
                displayName = item.displayName,
                colorHex = item.avatarColorHex,
                bitmap = StatusHelpers.decodeAvatarBitmap(item.photoBase64),
                seenFlags = emptyList()
            )
            holder.binding.root.setOnClickListener { onToggle(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, StatusPrivacyActivity::class.java)
    }
}
