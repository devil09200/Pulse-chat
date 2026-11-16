package com.example.ui.status

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.model.PublicProfile
import com.example.data.model.StatusUpdate
import com.example.databinding.ItemStatusContactBinding
import com.example.databinding.ItemStatusSectionHeaderBinding

sealed class StatusFeedListItem {
    data class SectionHeader(val title: String) : StatusFeedListItem()
    data class ContactGroup(
        val userId: String,
        val userName: String,
        val avatarColorHex: String,
        val avatarPhotoBase64: String,
        val statuses: List<StatusUpdate>,
        val seenFlags: List<Boolean>,
        val allViewed: Boolean
    ) : StatusFeedListItem()
}

class StatusFeedAdapter(
    private val onContactClick: (StatusFeedListItem.ContactGroup) -> Unit
) : ListAdapter<StatusFeedListItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is StatusFeedListItem.SectionHeader -> TYPE_HEADER
            is StatusFeedListItem.ContactGroup -> TYPE_CONTACT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                ItemStatusSectionHeaderBinding.inflate(inflater, parent, false)
            )
            else -> ContactViewHolder(
                ItemStatusContactBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is StatusFeedListItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is StatusFeedListItem.ContactGroup -> (holder as ContactViewHolder).bind(item, onContactClick)
        }
    }

    class HeaderViewHolder(
        private val binding: ItemStatusSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StatusFeedListItem.SectionHeader) {
            binding.tvSectionTitle.text = item.title
        }
    }

    class ContactViewHolder(
        private val binding: ItemStatusContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: StatusFeedListItem.ContactGroup,
            onContactClick: (StatusFeedListItem.ContactGroup) -> Unit
        ) {
            val latest = item.statuses.firstOrNull()
            binding.tvContactName.text = item.userName
            val timeLabel = StatusHelpers.formatStatusTimestamp(latest?.createdAt)
            val typeLabel = when (latest?.mediaType) {
                "photo" -> "Photo"
                "video" -> "Video"
                else -> "Text"
            }
            binding.tvStatusTimestamp.text = "$timeLabel • $typeLabel"

            val avatarBmp = StatusHelpers.decodeAvatarBitmap(item.avatarPhotoBase64)
            binding.ivContactStatusAvatar.bind(
                displayName = item.userName,
                colorHex = item.avatarColorHex,
                bitmap = avatarBmp,
                seenFlags = item.seenFlags,
                animateRing = false
            )

            if (item.statuses.size > 1) {
                binding.tvStatusCountBadge.visibility = View.VISIBLE
                binding.tvStatusCountBadge.text = "${item.statuses.size}"
            } else {
                binding.tvStatusCountBadge.visibility = View.GONE
            }

            binding.root.contentDescription = "${item.userName}, ${item.statuses.size} status updates"
            binding.root.setOnClickListener { onContactClick(item) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1

        fun buildGroupedItems(
            context: android.content.Context,
            statuses: List<StatusUpdate>,
            currentUserId: String,
            publicProfiles: List<PublicProfile>,
            searchQuery: String
        ): List<StatusFeedListItem> {
            val query = searchQuery.trim()
            val profileById = publicProfiles.associateBy { it.userId }

            val filtered = if (query.isBlank()) {
                statuses
            } else {
                statuses.filter {
                    it.userName.contains(query, ignoreCase = true) ||
                        it.caption.contains(query, ignoreCase = true)
                }
            }

            val groups = filtered
                .groupBy { it.userId }
                .map { (userId, userStatuses) ->
                    val sorted = userStatuses.sortedBy { it.createdAt?.toDate()?.time ?: 0L }
                    val newestFirst = sorted.reversed()
                    val profile = profileById[userId]
                    val seenFlags = sorted.map { currentUserId in it.viewedBy }
                    val allViewed = seenFlags.all { it }
                    StatusFeedListItem.ContactGroup(
                        userId = userId,
                        userName = profile?.displayName?.ifBlank { newestFirst.first().userName }
                            ?: newestFirst.first().userName,
                        avatarColorHex = profile?.avatarColorHex?.ifBlank { newestFirst.first().avatarColorHex }
                            ?: newestFirst.first().avatarColorHex,
                        avatarPhotoBase64 = profile?.photoBase64 ?: "",
                        statuses = newestFirst,
                        seenFlags = seenFlags,
                        allViewed = allViewed
                    )
                }

            val recent = groups.filter { !it.allViewed }
            val viewed = groups.filter { it.allViewed }

            val result = mutableListOf<StatusFeedListItem>()
            if (recent.isNotEmpty()) {
                result.add(StatusFeedListItem.SectionHeader(context.getString(R.string.recent_updates_header)))
                result.addAll(recent)
            }
            if (viewed.isNotEmpty()) {
                result.add(StatusFeedListItem.SectionHeader(context.getString(R.string.viewed_updates_header)))
                result.addAll(viewed)
            }
            return result
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StatusFeedListItem>() {
            override fun areItemsTheSame(oldItem: StatusFeedListItem, newItem: StatusFeedListItem): Boolean {
                return when {
                    oldItem is StatusFeedListItem.SectionHeader && newItem is StatusFeedListItem.SectionHeader ->
                        oldItem.title == newItem.title
                    oldItem is StatusFeedListItem.ContactGroup && newItem is StatusFeedListItem.ContactGroup ->
                        oldItem.userId == newItem.userId
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: StatusFeedListItem, newItem: StatusFeedListItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
