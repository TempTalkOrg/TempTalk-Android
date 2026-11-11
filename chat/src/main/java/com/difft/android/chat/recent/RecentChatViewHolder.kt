package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.app.Activity
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.databinding.ChatFragmentRecentChatListItemBinding
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager.EntryPoint
import com.difft.android.call.state.OnGoingCallStateManager
import dagger.hilt.android.EntryPointAccessors
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import kotlinx.coroutines.launch
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.search.setHighLightText
import com.difft.android.chat.setting.archive.bindCompactExpiry







class RecentChatViewHolder(
    val activity: Activity,
    container: ViewGroup,
    val myID: String,
    private val isForSearch: Boolean,
    private val isFocusCard: Boolean = false,
) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(container.context)
    ChatFragmentRecentChatListItemBinding.inflate(inflater, container, false).root
}) {

    private val binding = ChatFragmentRecentChatListItemBinding.bind(itemView)
    private var currentIsSelected: Boolean = false
    private var boundRoomId: String? = null

    
    val touchTarget: View
        get() = binding.swipeForeground

    
    private val containerWidthPx: Int = container.width

    
    class SwipeActions(
        val callback: ChatListSwipeLayout.Callback,
        val onReadToggle: () -> Unit,
        val onPinToggle: () -> Unit,
        val onDelete: () -> Unit,
        
        val onFocusToggle: () -> Unit = {},
    )

    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        searchKey: String,
        data: RoomViewData,
        isSelected: Boolean = false,
        onItemClick: () -> Unit,
        onItemLongClick: () -> Unit,
        swipe: SwipeActions? = null,
    ) {
        currentIsSelected = isSelected
        updateTextSizes(TextSizeUtil.isLarger)
        bindSwipeActions(data, swipe)

        if (isForSearch && searchKey.isNotEmpty()) {
            binding.textviewLabel.setHighLightText(data.roomName.toString(), searchKey)
        } else {
            binding.textviewLabel.text = data.roomName
        }

        bindCompactExpiry(binding.timerRow, binding.textviewTimer, data.messageExpiry)

        binding.imageviewGroupAvatar.visibility = View.GONE
        binding.imageviewAvatar.visibility = View.GONE

        when (data.type) {
            is RoomViewData.Type.OneOnOne -> {
                binding.imageviewAvatar.visibility = View.VISIBLE
                if (data.roomId == myID) {
                    binding.imageviewBotBadge.isVisible = false
                    binding.imageviewAvatar.showFavorites()
                    if (searchKey.isNotEmpty()) {
                        binding.textviewLabel.setHighLightText(
                            binding.root.context.getString(com.difft.android.base.R.string.chat_favorites),
                            searchKey
                        )
                    } else {
                        binding.textviewLabel.text =
                            binding.root.context.getString(com.difft.android.base.R.string.chat_favorites)
                    }
                } else {
                    binding.imageviewBotBadge.isVisible = data.roomId.isOfficialAccount()
                    val contactAvatar = (data.remarkAvatarJson?.takeIf { it.isNotEmpty() } ?: data.roomAvatarJson)
                        ?.getContactAvatarData()
                    binding.imageviewAvatar.setAvatar(
                        contactAvatar?.getContactAvatarUrl(),
                        contactAvatar?.encKey,
                        data.roomName?.firstOrNull()?.toString() ?: "#",
                        data.roomId,
                        letterTextSizeDp = ROW_AVATAR_LETTER_SIZE_DP,
                    )
                }
            }

            is RoomViewData.Type.Group -> {
                binding.imageviewGroupAvatar.visibility = View.VISIBLE
                binding.imageviewGroupAvatar.setAvatar(data.roomAvatarJson?.getAvatarData(), true, data.groupMembersNumber, data.roomId)
                binding.imageviewBotBadge.isVisible = false
            }
        }

        if (data.isInstantCall) {
            binding.imageviewAvatar.visibility = View.VISIBLE
            binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_instant_call)
        }
        binding.textviewDetail.isVisible = !data.isInstantCall

        binding.textviewDate.text = data.lastActiveTimeText
        applyDetailRow(data)
        binding.textviewMissedNumber.text =
            if (data.unreadMessageNum > 99) "99+" else data.unreadMessageNum.toString()
        applyTagRow(data)

        applyUnreadBadge(binding.textviewMissedNumber, data.displayUnreadNum, data.isMuted, isDot = data.unreadMessageNum == 0 && data.isManualUnread)

        binding.swipeForeground.setOnClickListener {
            onItemClick()
        }
        binding.swipeForeground.setOnLongClickListener {
            onItemLongClick()
            true
        }
        updateCallBarDuration(data)
    }

    






    private fun bindSwipeActions(data: RoomViewData, swipe: SwipeActions?) {
        val root = binding.root
        if (boundRoomId != data.roomId) {
            root.close(animate = false)
            boundRoomId = data.roomId
        }
        root.callback = swipe?.callback
        root.swipeEnabled = swipe != null && !data.hasOngoingCall
        if (swipe == null) {
            root.onFullSwipeStart = null
            return
        }
        val context = root.context
        val hasUnread = data.displayUnreadNum > 0
        binding.actionReadIcon.setImageResource(if (hasUnread) R.drawable.chat_icon_mark_read else R.drawable.chat_icon_mark_unread)
        binding.actionReadLabel.text = context.getString(if (hasUnread) R.string.chat_action_mark_read else R.string.chat_action_mark_unread)
        binding.actionPinIcon.setImageResource(if (data.isPinned) R.drawable.chat_icon_unstick else R.drawable.chat_icon_stick)
        binding.actionPinLabel.text = context.getString(if (data.isPinned) R.string.chat_unstick else R.string.chat_stick)
        binding.actionDelete.isVisible = data.roomId != myID
        bindFocusSwipeAction(binding.actionFocus, binding.actionFocusIcon, binding.actionFocusLabel, data.focusAction)

        
        val runAndClose: (() -> Unit) -> Unit = { action ->
            root.close(animate = false)
            action()
        }
        root.onFullSwipeStart = swipe.onReadToggle
        binding.actionRead.setOnClickListener { runAndClose(swipe.onReadToggle) }
        binding.actionPin.setOnClickListener { runAndClose(swipe.onPinToggle) }
        binding.actionDelete.setOnClickListener { runAndClose(swipe.onDelete) }
        binding.actionFocus.setOnClickListener { runAndClose(swipe.onFocusToggle) }
    }

    private fun updateTextSizes(isLarger: Boolean) {
        
        val timerSize = if (isLarger) TIMER_SIZE_LARGER else TIMER_SIZE
        binding.textviewTimer.textSize = timerSize
        val timerIconPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, timerSize, itemView.resources.displayMetrics).toInt()
        binding.imageviewTimer.updateLayoutParams<ViewGroup.LayoutParams> {
            width = timerIconPx
            height = timerIconPx
        }
        if (isLarger) {
            binding.textviewLabel.textSize = 24f
            binding.textviewDate.textSize = 21f
            binding.textviewAt.textSize = 21f
            binding.textviewDetail.textSize = 21f
            binding.textviewFocusLine2.textSize = 21f
            binding.textviewFocusLine3.textSize = 21f
            binding.textviewMissedNumber.textSize = 12f
        } else {
            binding.textviewLabel.textSize = 16f
            binding.textviewDate.textSize = 14f
            binding.textviewAt.textSize = 14f
            binding.textviewDetail.textSize = 14f
            binding.textviewFocusLine2.textSize = 14f
            binding.textviewFocusLine3.textSize = 14f
            binding.textviewMissedNumber.textSize = 10f
        }
    }

    









    private fun applyDetailRow(data: RoomViewData) {
        val context = binding.root.context
        val draft = data.draftPreview?.takeIf { it.isNotEmpty() }
        val lines: List<CharSequence?> = if (isFocusCard) {
            focusCardLines(draft, data.focusPreviewLines, data.lastDisplayContent)
        } else {
            listOf(draft ?: data.lastDisplayContent)
        }
        binding.textviewDetail.text = lines.firstOrNull()
        
        
        val detailColor = ContextCompat.getColor(context, detailColorRes(data.displayUnreadNum, data.isMuted))
        binding.textviewDetail.setTextColor(detailColor)
        binding.textviewFocusLine2.setTextColor(detailColor)
        binding.textviewFocusLine3.setTextColor(detailColor)
        binding.textviewFocusLine2.isVisible = lines.size > 1
        binding.textviewFocusLine2.text = lines.getOrNull(1)
        binding.textviewFocusLine3.isVisible = lines.size > 2
        binding.textviewFocusLine3.text = lines.getOrNull(2)
    }

    










    private fun applyTagRow(data: RoomViewData) {
        val context = binding.root.context
        
        val showSendingIcon = data.sendingStatus != ROOM_SENDING_STATUS_NONE
        binding.imageviewSending.isVisible = showSendingIcon
        val labels = ChatListTagLabels(
            criticalAlert = context.getString(R.string.chat_list_critical_alert),
            sendFailed = context.getString(R.string.chat_list_send_failed),
            atYou = context.getString(R.string.chat_list_at_you),
            atAll = context.getString(R.string.chat_list_at_all),
            draft = context.getString(R.string.chat_list_draft),
        )
        val all = buildTagSegments(
            criticalAlertType = data.criticalAlertType,
            sendStatus = data.sendStatus,
            mentionType = data.mentionType,
            hasDraft = !data.draftPreview.isNullOrEmpty(),
            labels = labels,
        )
        val rowWidthPx = resolveRowWidthPx(
            itemViewWidthPx = itemView.width,
            containerWidthPx = containerWidthPx,
            displayWidthPx = context.resources.displayMetrics.widthPixels,
        )
        val visible = selectVisibleTags(
            tags = all,
            availableWidthPx = computeTagAvailableWidthPx(
                rowWidthPx = rowWidthPx,
                density = context.resources.displayMetrics.density,
                hasUnreadBadge = data.displayUnreadNum != 0,
                isLargerText = TextSizeUtil.isLarger,
                hasSendingIcon = showSendingIcon,
                hasPinnedIcon = trailingIndicator(data).let { it == TrailingIndicator.PIN || it == TrailingIndicator.FOCUS },
            ),
            measureText = binding.textviewAt.paint::measureText,
        )
        
        
        binding.textviewAt.isVisible = visible.isNotEmpty()
        binding.textviewAt.text = joinTags(visible)
    }

    private fun setupCallingJoinButton(
        view: TextView,
        showTime: String?
    ) {
        view.text = if(!TextUtils.isEmpty(showTime)) showTime else view.context.getString(R.string.join)
    }


    private fun trailingIndicator(data: RoomViewData): TrailingIndicator = trailingIndicator(
        isForSearch = isForSearch,
        isFocusCard = isFocusCard,
        isPinned = data.isPinned,
        displayUnreadNum = data.displayUnreadNum,
    )

    



    fun updateCallBarDuration(data: RoomViewData) {
        val hasCallBar = data.hasOngoingCall
        binding.swipeForeground.setBackgroundColor(
            ContextCompat.getColor(binding.root.context, rowBackgroundColorRes(
                isSelected = currentIsSelected,
                isPinned = !isForSearch && data.isPinned,
                hasCallBar = hasCallBar,
            ))
        )
        val indicator = trailingIndicator(data)
        binding.imageviewPinned.isVisible = indicator == TrailingIndicator.PIN
        binding.imageviewFocus.isVisible = indicator == TrailingIndicator.FOCUS
        
        binding.callBarDuration.isVisible = hasCallBar
        binding.textviewDate.isVisible = !hasCallBar
        val callData = data.callData
        if (callData != null) {
            val roomId = callData.roomId
            if (!roomId.isNullOrEmpty()) {
                val callStateManager = getCallStateManager()
                val showTime = callStateManager.getCallingTime(roomId)
                setupCallingJoinButton(binding.callBarDuration, showTime)
                binding.callBarDuration.setOnClickListener {
                    if (!callStateManager.isInCalling()) {
                        L.i { "[call] CallBar Joining call for roomId:${roomId}." }
                        appScope.launch {
                            val status = LCallManager.joinCall(activity, callData)
                            if (!status) {
                                L.e { "[Call] CallBar join call failed." }
                                ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                            }
                        }
                    } else {
                        if (callStateManager.getCurrentRoomId() == roomId) {
                            L.i { "[call] CallBar Bringing back current call for roomId:${roomId}." }
                            LCallManager.bringCallScreenToFront(ApplicationHelper.instance.applicationContext)
                        } else {
                            ToastUtil.show(com.difft.android.call.R.string.call_newcall_tip)
                        }
                    }
                }
            }
        }
    }

    private fun getCallStateManager(): OnGoingCallStateManager {
        val entryPoint = EntryPointAccessors.fromApplication<EntryPoint>(
            activity.applicationContext
        )
        return entryPoint.onGoingCallStateManager
    }

}





internal val RoomViewData.hasOngoingCall: Boolean
    get() = !callData?.roomId.isNullOrEmpty()





@ColorRes
internal fun rowBackgroundColorRes(isSelected: Boolean, isPinned: Boolean, hasCallBar: Boolean): Int = when {
    isSelected -> com.difft.android.base.R.color.bg3
    isPinned || hasCallBar -> com.difft.android.base.R.color.bg2
    else -> com.difft.android.base.R.color.bg1
}


private val ROW_AVATAR_LETTER_SIZE_DP = AvatarView.letterTextSizeDpFor(52)


private const val TIMER_SIZE = 12f
private const val TIMER_SIZE_LARGER = 18f

internal enum class TrailingIndicator { NONE, UNREAD, FOCUS, PIN }






internal fun trailingIndicator(
    isForSearch: Boolean,
    isFocusCard: Boolean,
    isPinned: Boolean,
    displayUnreadNum: Int,
): TrailingIndicator = when {
    displayUnreadNum > 0 -> TrailingIndicator.UNREAD
    isForSearch -> TrailingIndicator.NONE
    isFocusCard -> TrailingIndicator.FOCUS
    isPinned -> TrailingIndicator.PIN
    else -> TrailingIndicator.NONE
}
