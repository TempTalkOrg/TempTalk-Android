package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatFragmentRecentChatListItemBinding
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE







class FoldedGroupsViewHolder(container: ViewGroup) : RecyclerView.ViewHolder(
    ChatFragmentRecentChatListItemBinding.inflate(LayoutInflater.from(container.context), container, false).root
) {
    private val binding = ChatFragmentRecentChatListItemBinding.bind(itemView)

    
    var lastTouchX: Int = 0
        private set
    var lastTouchY: Int = 0
        private set

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        summary: FoldedGroupsSummary,
        swipeCallback: ChatListSwipeLayout.Callback?,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onEndFocus: () -> Unit,
    ) {
        val context = binding.root.context
        applyTextSizes(TextSizeUtil.isLarger)
        binding.imageviewGroupAvatar.visibility = View.GONE
        binding.imageviewAvatar.visibility = View.VISIBLE
        binding.imageviewAvatar.setAvatar(R.drawable.chat_ic_folded_groups_avatar)
        binding.imageviewBotBadge.isVisible = false
        binding.timerRow.isVisible = false
        binding.imageviewFocus.isVisible = false
        binding.textviewFocusLine2.isVisible = false
        binding.textviewFocusLine3.isVisible = false
        binding.imageviewSending.isVisible = false
        binding.imageviewPinned.isVisible = false
        binding.callBarDuration.isVisible = false
        binding.textviewDate.isVisible = true

        binding.textviewLabel.text = context.getString(R.string.focus_list_folded_title, summary.count)
        val resume = summary.resumeLabel
        binding.imageviewFoldResume.isVisible = resume != null
        binding.textviewFoldResume.isVisible = resume != null
        if (resume != null) {
            val time = if (resume.tomorrow) context.getString(R.string.focus_end_tomorrow, resume.time) else resume.time
            binding.textviewFoldResume.text = context.getString(R.string.focus_list_fold_resume, time)
        }
        binding.textviewDate.text = summary.lastActiveTimeText
        val name = summary.latestGroupName?.toString().orEmpty()
        val content = summary.latestContent?.toString().orEmpty()
        binding.textviewDetail.isVisible = true
        binding.textviewDetail.text = if (content.isEmpty()) name else context.getString(R.string.focus_list_folded_preview, name, content)
        
        binding.textviewDetail.setTextColor(ContextCompat.getColor(context, detailColorRes(summary.unreadCount, isMuted = true)))

        val tags = buildTagSegments(
            criticalAlertType = CRITICAL_ALERT_TYPE_NONE,
            sendStatus = ROOM_SEND_STATUS_NONE,
            mentionType = summary.mentionType,
            hasDraft = false,
            labels = ChatListTagLabels(
                criticalAlert = "",
                sendFailed = "",
                atYou = context.getString(R.string.chat_list_at_you),
                atAll = context.getString(R.string.chat_list_at_all),
                draft = "",
            ),
        )
        binding.textviewAt.isVisible = tags.isNotEmpty()
        binding.textviewAt.text = joinTags(tags)

        val displayUnread = if (summary.unreadCount > 0) summary.unreadCount else if (summary.hasManualUnread) 1 else 0
        binding.textviewMissedNumber.text = if (summary.unreadCount > 99) "99+" else summary.unreadCount.toString()
        applyUnreadBadge(binding.textviewMissedNumber, displayUnread, isMuted = true, isDot = summary.unreadCount == 0 && summary.hasManualUnread)
        binding.swipeForeground.setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg1))

        binding.swipeForeground.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.rawX.toInt()
                lastTouchY = event.rawY.toInt()
            }
            false
        }
        binding.swipeForeground.setOnClickListener { onClick() }
        binding.swipeForeground.setOnLongClickListener {
            onLongClick()
            true
        }
        bindSwipe(swipeCallback, onEndFocus)
    }

    private fun bindSwipe(callback: ChatListSwipeLayout.Callback?, onEndFocus: () -> Unit) {
        val root = binding.root
        root.callback = callback
        root.swipeEnabled = callback != null
        root.onFullSwipeStart = null
        binding.swipeStartActions.isVisible = false
        binding.actionPin.isVisible = false
        binding.actionDelete.isVisible = false
        bindFocusSwipeAction(binding.actionFocus, binding.actionFocusIcon, binding.actionFocusLabel, FocusRowAction.END)
        binding.actionFocus.setOnClickListener {
            root.close(animate = false)
            onEndFocus()
        }
    }

    private fun applyTextSizes(isLarger: Boolean) {
        binding.textviewLabel.textSize = if (isLarger) 24f else 16f
        binding.textviewDate.textSize = if (isLarger) 21f else 14f
        binding.textviewAt.textSize = if (isLarger) 21f else 14f
        binding.textviewDetail.textSize = if (isLarger) 21f else 14f
        binding.textviewMissedNumber.textSize = if (isLarger) 12f else 10f
        binding.textviewFoldResume.textSize = if (isLarger) 16f else 12f
    }
}
