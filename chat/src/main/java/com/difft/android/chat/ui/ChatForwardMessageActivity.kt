package com.difft.android.chat.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.databinding.ChatActivityForwardMessageBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.messageserialization.db.store.ConversationUtils
import com.google.gson.Gson
import com.hi.dhl.binding.viewbind
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import io.reactivex.rxjava3.core.Observable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.components.reaction.ConversationItemSelection
import org.thoughtcrime.securesms.components.reaction.ConversationReactionDelegate
import org.thoughtcrime.securesms.components.reaction.ConversationReactionOverlay
import org.thoughtcrime.securesms.components.reaction.InteractiveConversationElement
import org.thoughtcrime.securesms.components.reaction.MotionEventRelay
import org.thoughtcrime.securesms.components.reaction.SelectedConversationModel
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.StorageUtil
import org.thoughtcrime.securesms.util.Stub
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.WindowUtil
import util.TimeFormatter
import util.concurrent.TTExecutors
import java.io.File
import java.util.concurrent.TimeUnit

class ChatForwardMessageActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity, title: String, forwards: ForwardContext, confidentialMessageId: String? = null) {
            ConversationUtils.intentForwardData = Gson().toJson(forwards)
            val intent = Intent(activity, ChatForwardMessageActivity::class.java)
            intent.putExtra("title", title)
//            intent.putExtra("forwards", Gson().toJson(forwards)) //TransactionTooLargeException
            intent.putExtra("confidentialMessageId", confidentialMessageId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ChatActivityForwardMessageBinding by viewbind()

    /**
     * 页面级联系人缓存
     * Activity生命周期结束时自动释放
     *
     * 合并转发可能包含大量消息和联系人（发送者、reactions用户等），
     * 页面级缓存会将所有需要的联系人加载到内存，无需容量限制
     */
    private val contactorCache = MessageContactsCacheUtil()

    private val confidentialMessageId: String? by lazy {
        intent.getStringExtra("confidentialMessageId")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()

        FileUtil.progressUpdate
            .map { messageId ->
                val message = chatMessageAdapter.currentList.find { it.id == messageId }
                var position = -1
                if (message != null && message is TextChatMessage) {
                    val attachment = wcdb.attachment.getFirstObject(
                        DBAttachmentModel.authorityId.eq(message.id)
                    )
                    if (attachment != null) {
                        message.attachment?.status = attachment.status
                        position = chatMessageAdapter.currentList.indexOf(message)
                    }
                }
                position
            }
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this@ChatForwardMessageActivity))
            .subscribe({ position ->
                if (position != -1) {
                    chatMessageAdapter.notifyItemChanged(position)
                }
            }, { it.printStackTrace() })
    }

    private lateinit var reactionDelegate: ConversationReactionDelegate
    private val motionEventRelay: MotionEventRelay by viewModels()
    private lateinit var backPressedCallback: BackPressedDelegate

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return motionEventRelay.offer(ev) || super.dispatchTouchEvent(ev)
    }

    private inner class BackPressedDelegate : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (reactionDelegate.isShowing) {
                reactionDelegate.hide()
            } else {
                finish()
            }
        }
    }

    private fun doAfterFirstRender() {
        backPressedCallback = BackPressedDelegate()
        onBackPressedDispatcher.addCallback(
            this,
            backPressedCallback
        )

        val conversationReactionStub = Stub<ConversationReactionOverlay>(mBinding.conversationReactionScrubberStub)
        reactionDelegate = ConversationReactionDelegate(conversationReactionStub)
        motionEventRelay.setDrain(MotionEventRelayDrain(this))
    }

    private fun handleReaction(
        rootView: View,
        conversationMessage: TextChatMessage,
        onActionSelectedListener: ConversationReactionOverlay.OnActionSelectedListener,
        selectedConversationModel: SelectedConversationModel,
        onHideListener: ConversationReactionOverlay.OnHideListener
    ) {
        reactionDelegate.setOnActionSelectedListener(onActionSelectedListener)
        reactionDelegate.setOnHideListener(onHideListener)
        reactionDelegate.show(
            this,
            rootView,
            conversationMessage,
            false,
            selectedConversationModel
        )
    }

    private inner class MotionEventRelayDrain(lifecycleOwner: LifecycleOwner) :
        MotionEventRelay.Drain {
        private val lifecycle = lifecycleOwner.lifecycle

        override fun accept(motionEvent: MotionEvent): Boolean {
            return if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                reactionDelegate.applyTouchEvent(motionEvent)
            } else {
                false
            }
        }
    }

    private inner class ReactionsToolbarListener(
        private val data: TextChatMessage
    ) : ConversationReactionOverlay.OnActionSelectedListener {
        override fun onActionSelected(action: ConversationReactionOverlay.Action, rootView: View) {
            when (action) {
                ConversationReactionOverlay.Action.SAVE -> {
                    if (StorageUtil.canWriteToMediaStore()) {
                        saveAttachment(data)
                    } else {
                        pendingSaveAttachmentMessage = data
                        mediaPermission.launchMultiplePermission(PermissionUtil.picturePermissions)
                    }
                }

                else -> {}
            }
        }
    }

    private fun hasAttachment(data: TextChatMessage): Boolean {
        if (data.isAttachmentMessage()) return true

        val forwards = data.forwardContext?.forwards
        if (forwards?.size == 1) {
            val forward = forwards.firstOrNull()
            return forward?.attachments?.isNotEmpty() == true
        }

        return false
    }

    private var pendingSaveAttachmentMessage: TextChatMessage? = null

    private val mediaPermission = registerPermission {
        onMediaPermissionResult(it)
    }

    private fun onMediaPermissionResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onMediaPermissionForMessageResult: Denied" }
                ToastUtil.show(getString(R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onMediaPermissionForMessageResult: Granted" }
                pendingSaveAttachmentMessage?.let {
                    saveAttachment(it)
                }
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onMediaPermissionForMessageResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.tip),
                    message = getString(R.string.no_permission_picture_tip),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    onConfirm = {
                        PermissionUtil.launchSettings(this)
                    },
                    onCancel = {
                        ToastUtil.show(getString(R.string.not_granted_necessary_permissions))
                    }
                )
            }
        }
        pendingSaveAttachmentMessage = null
    }

    private fun saveAttachment(data: TextChatMessage) {
        val attachment = when {
            data.isAttachmentMessage() -> data.attachment
            data.forwardContext?.forwards?.size == 1 -> data.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
            else -> null
        }

        attachment?.let {
            val attachmentPath = FileUtil.getMessageAttachmentFilePath(data.id) + it.fileName
            val progress = data.getAttachmentProgress()

            if (File(attachmentPath).exists() && (progress == null || progress == 100)) {
                val saveAttachment = SaveAttachmentTask.Attachment(
                    File(attachmentPath).toUri(),
                    it.contentType,
                    System.currentTimeMillis(),
                    it.fileName,
                    false,
                    true
                )

                SaveAttachmentTask(this).executeOnExecutor(TTExecutors.BOUNDED, saveAttachment)
            } else {
                L.i { "save attachment error,exists:" + File(attachmentPath).exists() + " download completed:" + (progress == null || progress == 100) }
                ToastUtil.show(resources.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
            }
        }
    }

    /**
     * Check if attachment needs manual download (failed or large file not downloaded)
     * @return true if needs to download, false otherwise
     */
    private fun shouldTriggerManualDownload(
        attachment: Attachment,
        progress: Int?,
        messageId: String
    ): Boolean {
        // Check if download failed or expired
        val isFailedOrExpired = if (progress != null) {
            progress == -1 || progress == -2
        } else {
            attachment.status == AttachmentStatus.FAILED.code || attachment.status == AttachmentStatus.EXPIRED.code
        }
        if (isFailedOrExpired) return true

        // Check if large file needs manual download (>10M)
        val fileSize = attachment.size
        val isLargeFile = fileSize > FileUtil.LARGE_FILE_THRESHOLD
        val fileName = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
        val isFileValid = FileUtil.isFileValid(attachmentPath)

        return isLargeFile && (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) && progress == null
    }

    private fun downloadAttachment(messageId: String, attachment: Attachment) {
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                messageId,
                attachment.id,
                filePath,
                attachment.authorityId,
                attachment.key ?: byteArrayOf(),
                !attachment.isAudioMessage(),
                confidentialMessageId == null && (attachment.isImage() || attachment.isVideo())
            )
        )
    }

    private val chatMessageAdapter = object : ChatMessageAdapter(forWhat = null, contactorCache = contactorCache) {

        override fun onItemClick(rootView: View, data: ChatMessage) {
            if (data is TextChatMessage) {
                if (data.isAttachmentMessage()) {
                    val attachment = data.attachment ?: return
                    val progress = data.getAttachmentProgress()

                    if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                        downloadAttachment(data.id, attachment)
                        return
                    }

                    if (data.attachment?.isImage() == true || data.attachment?.isVideo() == true) {
                        openPreview(data)
                    }
                } else if (data.forwardContext != null && !data.forwardContext?.forwards.isNullOrEmpty()) {
                    val forwardContext = data.forwardContext ?: return
                    if (forwardContext.forwards?.size == 1) {
                        val forward = forwardContext.forwards?.getOrNull(0) ?: return
                        val attachment = forward.attachments?.getOrNull(0) ?: return
                        val progress = data.getAttachmentProgress()

                        if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                            downloadAttachment(data.id, attachment)
                            return
                        }

                        if (attachment.isImage() || attachment.isVideo()) {
                            openPreview(generateMessageFromForward(forward) as TextChatMessage)
                        }
                    } else {
                        startActivity(this@ChatForwardMessageActivity, getString(R.string.chat_history), forwardContext)
                    }
                }
            }
        }

        override fun onItemLongClick(rootView: View, data: ChatMessage) {
            if (data !is TextChatMessage) return
            if (!hasAttachment(data)) return

            Observable.just(Unit)
                .delay(100, TimeUnit.MILLISECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this@ChatForwardMessageActivity))
                .subscribe({
                    mBinding.reactionsShade.visibility = View.VISIBLE
                    mBinding.recyclerViewMessage.suppressLayout(true)

                    val target: InteractiveConversationElement? = rootView as? InteractiveConversationElement

                    if (target != null) {
                        val snapshot = ConversationItemSelection.snapshotView(
                            target,
                            mBinding.recyclerViewMessage,
                            data,
                            null
                        )
                        val bodyBubble = target.bubbleView
                        val selectedConversationModel = SelectedConversationModel(
                            bitmap = snapshot,
                            itemX = rootView.x,
                            itemY = rootView.y + mBinding.recyclerViewMessage.translationY,
                            bubbleY = bodyBubble.y,
                            bubbleWidth = bodyBubble.width,
                            audioUri = null,
                            isOutgoing = data.isMine,
                            focusedView = null,
                            snapshotMetrics = target.getSnapshotStrategy()?.snapshotMetrics
                                ?: InteractiveConversationElement.SnapshotMetrics(
                                    snapshotOffset = bodyBubble.x,
                                    contextMenuPadding = bodyBubble.x
                                ),
//                                emojis = globalConfigsManager.getEmojis(),
                            mostUseEmojis = null,
                            chatUIData = null,
                            isForForward = true,
                            isSaved = false
                        )
                        handleReaction(
                            rootView,
                            data,
                            ReactionsToolbarListener(data),
                            selectedConversationModel,
                            object : ConversationReactionOverlay.OnHideListener {
                                override fun startHide(focusedView: View?) {
                                }

                                override fun onHide() {
                                    ViewUtil.fadeOut(mBinding.reactionsShade, 50, View.GONE)

                                    mBinding.recyclerViewMessage.suppressLayout(false)

                                    WindowUtil.setLightStatusBarFromTheme(this@ChatForwardMessageActivity)
                                    WindowUtil.setLightNavigationBarFromTheme(this@ChatForwardMessageActivity)
                                }
                            }
                        )
                    }
                }, { it.printStackTrace() })
        }

        override fun onAvatarClicked(contactor: ContactorModel?) {
        }

        override fun onAvatarLongClicked(contactor: ContactorModel?) {
        }

        override fun onQuoteClicked(quote: Quote) {
        }

        override fun onReactionClick(
            message: ChatMessage,
            emoji: String,
            remove: Boolean,
            originTimeStamp: Long
        ) {
        }

        override fun onReactionLongClick(
            message: ChatMessage,
            emoji: String,
        ) {
        }

        override fun onSendStatusClicked(rootView: View, message: TextChatMessage) {

        }
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }
        mBinding.tvTitle.text = intent.getStringExtra("title")

        mBinding.recyclerViewMessage.apply {
            layoutManager = LinearLayoutManager(this.context)
            itemAnimator = null
            adapter = chatMessageAdapter
        }

        ConversationUtils.intentForwardData?.let {
            globalServices.gson.fromJson(it, ForwardContext::class.java)?.let { forwardContext ->
                lifecycleScope.launch {
                    val list = mutableListOf<ChatMessage>()
                    forwardContext.forwards?.forEach { forward ->
                        list.add(generateMessageFromForward(forward))
                    }

                    // 批量加载联系人到当前页面的缓存
                    val contactIds = MessageContactsCacheUtil.collectContactIds(list)
                    contactorCache.loadContactors(contactIds)

                    val newList = list.sortedBy { message -> message.systemShowTimestamp }
                        .mapIndexed { index, message ->
                            val previousMessage = if (index > 0) list[index - 1] else null
                            val isSameDayWithPreviousMessage = TimeFormatter.isSameDay(message.timeStamp, previousMessage?.timeStamp ?: 0L)

                            val nextMessage = if (index < list.size - 1) list[index + 1] else null
                            val isSameDayWithNextMessage = TimeFormatter.isSameDay(message.timeStamp, nextMessage?.timeStamp ?: 0L)

                            message.showName = !isSameDayWithPreviousMessage || message.authorId != previousMessage?.authorId
                            message.showDayTime = !isSameDayWithPreviousMessage
                            message.showTime = !isSameDayWithNextMessage || message.authorId != nextMessage?.authorId
                            message
                        }

                    chatMessageAdapter.submitList(newList) {
                        doAfterFirstRender()
                    }
                }
            }
            ConversationUtils.intentForwardData = null
        }
    }


    private fun openPreview(message: TextChatMessage) {
        val filePath = FileUtil.getMessageAttachmentFilePath(message.id) + message.attachment?.fileName
        if (!FileUtil.isFileValid(filePath)) {
            ToastUtil.showLong(R.string.file_load_error)
            return
        }
        val list = arrayListOf<LocalMedia>().apply {
            this.add(LocalMedia.generateLocalMedia(this@ChatForwardMessageActivity, filePath))
        }
        PictureSelector.create(this@ChatForwardMessageActivity)
            .openPreview()
            .isHidePreviewDownload(false)
            .isAutoVideoPlay(true)
            .isVideoPauseResumePlay(true)
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(this))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(this@ChatForwardMessageActivity))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setExternalPreviewEventListener(object : OnExternalPreviewEventListener {
                override fun onPreviewDelete(position: Int) {
                }

                override fun onLongPressDownload(context: Context?, media: LocalMedia?): Boolean {
                    return false
                }
            }).startActivityPreview(0, false, list)
    }

    override fun onDestroy() {
        confidentialMessageId?.let {
            ApplicationDependencies.getMessageStore().deleteMessage(listOf(it))
        }
        super.onDestroy()
    }
}