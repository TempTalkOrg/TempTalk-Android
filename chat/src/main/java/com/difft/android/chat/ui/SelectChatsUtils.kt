package com.difft.android.chat.ui

import android.app.Activity
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.PushForwardNoticeSendJobFactory
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.base.utils.globalServices
import com.difft.android.network.config.GlobalConfigsManager
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.members
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileExistResp
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.setting.archive.MessageArchiveManager
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.TextMessage
import com.difft.android.network.BaseResponse
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.base.widget.sideBar.SectionDecoration
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.RoomModel
import util.FileSystemUtils
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.util.MediaUtil
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class ChatsContact(
    val id: String,
    val name: String?,
    val avatar: String?,
    val firstLetters: String?,
    val isGroup: Boolean,
    val itemType: Int,  //1 chat 2 group 3 contact
    val avatarJson: String?
)

class SelectChatsUtils @Inject constructor(
    private val globalConfigsManager: GlobalConfigsManager,
    private val groupUtil: GroupUtil
) {

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var fileShareRepo: FileShareRepo

    @Inject
    lateinit var pushTextSendJobFactory: PushTextSendJobFactory

    @Inject
    lateinit var pushForwardNoticeSendJobFactory: PushForwardNoticeSendJobFactory

    private fun showWaitDialog(activity: Activity, message: String = "") {
        ComposeDialogManager.showWait(activity, message, cancelable = false)
    }

    private fun dismissWaitDialog() {
        ComposeDialogManager.dismissWait()
    }

    /**
     * 任务成功完成后的统一处理
     * @param message 成功提示消息的资源ID
     * @param onDismiss 关闭 dialog 的回调
     */
    private suspend fun handleTaskSuccess(message: Int, onDismiss: () -> Unit) {
        L.i { "[SelectChatsUtils] forward message success" }
        withContext(Dispatchers.Main) {
            dismissWaitDialog()
            ToastUtil.show(message)
            onDismiss()
        }
    }

    /**
     * 任务失败后的统一处理
     */
    private suspend fun handleTaskError(e: Exception) {
        L.e(e) { "[SelectChatsUtils] forward message failed:" }
        withContext(Dispatchers.Main) {
            dismissWaitDialog()
            ToastUtil.show(R.string.operation_failed)
        }
    }

    companion object {
        const val ITEM_TYPE_CHAT = 1
        const val ITEM_TYPE_GROUP = 2
        const val ITEM_TYPE_CONTACT = 3
    }

    var searchKey: String = ""
    var excludeNonFriendRooms: Boolean = false

    fun showChatSelectAndSendDialog(
        context: Activity,
        content: String,
        title: String? = null,
        file: File? = null,
        forwardContexts: List<ForwardContext>? = null,
        scene: ForwardNoticeData.Scene? = null,
        // Source conversation (where the forwarded messages originally lived). Forward callers
        // MUST pass this — the forwardNotice is posted there to tell original participants their
        // messages were forwarded away. Share callers (file / plain text share) pass null; no
        // notice is sent.
        sourceConversation: For? = null,
        // Authors of user-selected messages. PRD §5.3.4: deduped and priority-sorted by the
        // caller via NoticeAggregator (CF sender first → count desc → ts desc → authorId asc).
        // Used as the authoritative source for the notice's author display list.
        sourceAuthorIds: List<String>? = null,
        // PRD §5.3: explicit selected-message count (CF bubble counts as 1). When null,
        // falls back to sourceAuthorIds.size — only safe when caller didn't dedup (e.g.,
        // single-message paths where size == 1).
        messageCount: Int? = null,
        // PRD v1.0 §5.3 combined-forward mode of the source selection. Default UNKNOWN keeps
        // legacy share/external entries untouched; Phase 4 dispatch sites populate explicitly.
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        // PRD v2.0 §改动1/§改动2 条件①④: whether the forwarded content includes someone else's real
        // message. Default true keeps share/external callers tracing; forward sites compute it.
        carriesForeignContent: Boolean = true,
    ) {
        val fragment = ChatSelectBottomSheetFragment.newInstance(
            isContactOnly = false,
            excludeNonFriendRooms = file != null
        )
        fragment.onSelected = onSelected@{ data ->
            if (data == null) return@onSelected
            if (data.isGroup) {
                val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(data.id))
                if (group != null && !groupUtil.canSpeak(group, globalServices.myId)) {
                    ToastUtil.show(context.getString(R.string.group_only_moderators_can_speak_tip))
                    return@onSelected
                }
            }
            showSendToDialog(
                context,
                data,
                content,
                title,
                file,
                forwardContexts,
                scene,
                sourceConversation,
                sourceAuthorIds,
                messageCount,
                combinedForwardMode,
                carriesForeignContent,
                scope = fragment.lifecycleScope,
                onDismiss = { fragment.dismiss() }
            )
        }
        try {
            fragment.show((context as FragmentActivity).supportFragmentManager, "ChatSelectDialog")
        } catch (e: IllegalStateException) {
            L.e { "[SelectChatsUtils] show ChatSelectDialog error: ${e.message}" }
        }
    }

    fun showContactSelectDialog(
        context: Activity,
        onSelected: (ChatsContact?) -> Unit
    ) {
        val fragment = ChatSelectBottomSheetFragment.newInstance(isContactOnly = true)
        fragment.onSelected = onSelected
        try {
            fragment.show((context as FragmentActivity).supportFragmentManager, "ContactSelectDialog")
        } catch (e: IllegalStateException) {
            L.e { "[SelectChatsUtils] show ContactSelectDialog error: ${e.message}" }
        }
    }

    fun search(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            // 并发查询所有数据
            val roomsDeferred = async { queryRooms() }
            val groupsDeferred = async { queryGroups() }
            val contactsDeferred = async { queryContacts() }

            try {
                awaitAll(roomsDeferred, groupsDeferred, contactsDeferred)
            } catch (e: Exception) {
                L.e { "[SelectChatsUtils] search error: ${e.stackTraceToString()}" }
            }
        }
    }

    fun resetButtonClear(btnClear: AppCompatImageButton) {
        btnClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(searchKey)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    val _roomsFlow = MutableStateFlow<List<RoomModel>>(emptyList())
    val _groupsFlow = MutableStateFlow<List<GroupModel>>(emptyList())
    val _contactsFlow = MutableStateFlow<List<ContactorModel>>(emptyList())

    private suspend fun queryRooms(): List<RoomModel> = withContext(Dispatchers.IO) {
        try {
            val commonQuery = DBRoomModel.roomId.notEq("server")
                .and(DBRoomModel.roomName.notNull())
                .and(DBRoomModel.roomName.notEq(""))

            val baseRooms = if (TextUtils.isEmpty(searchKey)) {
                wcdb.room.getAllObjects(commonQuery)
            } else {
                wcdb.room.getAllObjects(
                    commonQuery.and(DBRoomModel.roomName.upper().like("%${searchKey.uppercase()}%"))
                )
            }

            // 如果需要添加收藏房间且结果中不包含，则添加
            val rooms = baseRooms + if (ResUtils.getString(com.difft.android.base.R.string.chat_favorites).uppercase().contains(searchKey.uppercase()) &&
                !baseRooms.any { it.roomId == globalServices.myId }
            ) {
                wcdb.room.getFirstObject(DBRoomModel.roomId.eq(globalServices.myId))?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }

            val filteredRooms = if (excludeNonFriendRooms) {
                val friendIds = wcdb.contactor.allObjects.map { it.id }.toSet()
                rooms.filter { room -> room.roomType == 1 || room.roomId in friendIds }
                    .also { L.i { "[SelectChatsUtils] excluded ${rooms.size - it.size} non-friend rooms" } }
            } else {
                rooms
            }

            val sortedRooms = filteredRooms.sortedWith(compareByDescending<RoomModel> { it.pinnedTime ?: 0L }
                .thenByDescending { it.lastActiveTime })

            _roomsFlow.value = sortedRooms
            sortedRooms
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] queryRooms error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun queryContacts(): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            val contacts = if (TextUtils.isEmpty(searchKey)) {
                wcdb.contactor.allObjects
            } else {
                wcdb.contactor.search(searchKey)
            }
            _contactsFlow.value = contacts
            contacts
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] queryContacts error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private suspend fun queryGroups(): List<GroupModel> = withContext(Dispatchers.IO) {
        try {
            val groups = if (TextUtils.isEmpty(searchKey)) {
                wcdb.group.allObjects
            } else {
                wcdb.group.searchByNameAndGroupMembers(searchKey)
            }
            _groupsFlow.value = groups
            groups
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] queryGroups error: ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private var etMessage: AppCompatEditText? = null

    // Long by nature: a single forward/share dialog builder (view binding in onViewCreated +
    // sequential send-task assembly in onConfirm). PRD v2.0 only threaded the notice params
    // (targetConversationId / carriesForeignContent) through it, nudging it just past the limit.
    // Suppressed rather than split to keep the dialog's send flow in one readable place.
    @Suppress("LongMethod")
    private fun showSendToDialog(
        context: Activity,
        chatsContact: ChatsContact,
        content: String,
        title: String? = null,
        file: File? = null,
        forwardContexts: List<ForwardContext>? = null,
        scene: ForwardNoticeData.Scene? = null,
        sourceConversation: For? = null,
        sourceAuthorIds: List<String>? = null,
        messageCount: Int? = null,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        carriesForeignContent: Boolean = true,
        scope: CoroutineScope,
        onDismiss: () -> Unit
    ) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.chat_send_to),
            message = "",
            confirmText = context.getString(R.string.chat_send),
            cancelText = context.getString(R.string.chat_send_cancel),
            layoutId = R.layout.chat_layout_forward_dialog,
            onViewCreated = { v ->
                val avatarView = v.findViewById<AvatarView>(R.id.imageview_avatar)
                val groupAvatarView = v.findViewById<GroupAvatarView>(R.id.group_avatar)

                val textViewName = v.findViewById<AppCompatTextView>(R.id.textViewName)
                val textContent = v.findViewById<AppCompatTextView>(R.id.textContent)
                etMessage = v.findViewById(R.id.et_message)

                if (chatsContact.isGroup) {
                    groupAvatarView.visibility = View.VISIBLE
                    groupAvatarView.setAvatar(chatsContact.avatarJson?.getAvatarData())
                } else {
                    avatarView.visibility = View.VISIBLE
                    val contactAvatar = chatsContact.avatar?.getContactAvatarData()
                    avatarView.setAvatar(contactAvatar?.getContactAvatarUrl(), contactAvatar?.encKey, chatsContact.firstLetters, chatsContact.id)
                }
                textViewName.text = chatsContact.name
                val contentText = title ?: content
                // Display-only normalize: this preview bypasses setMarkdownToTextview. Only the bound
                // value is normalized; `content` is reused as the sent payload below and stays raw.
                textContent.text = contentText.normalizeNewlines()
            },
            onConfirm = {
                val message = etMessage?.text.toString().trim()

                // 显示全局 WaitDialog，设置为不可取消
                showWaitDialog(context)

                // 收集所有需要发送的任务
                val sendTasks = mutableListOf<suspend () -> Unit>()

                // 添加主要内容发送任务
                if (file != null) {
                    sendTasks.add {
                        sendFile(context, Uri.fromFile(file), chatsContact.id, chatsContact.isGroup)
                    }
                } else if (forwardContexts != null && forwardContexts.isEmpty().not()) {
                    sendTasks.add {
                        val forWhat = if (chatsContact.isGroup) For.Group(chatsContact.id) else For.Account(chatsContact.id)
                        val time = messageArchiveManager.getMessageArchiveTime(forWhat)
                        val response = getConversationConfigs(context, listOf(forWhat.id))
                        var mode = response.data?.conversations?.find { body -> body.conversation == forWhat.id }?.confidentialMode ?: 0
                        // Check group member limit or bot: force mode to 0
                        if (mode == 1) {
                            if (chatsContact.isGroup) {
                                val memberCount = wcdb.getGroupMemberCount(chatsContact.id)
                                val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                                if (memberCount >= limit) mode = 0
                            } else if (chatsContact.id.isOfficialAccount()) {
                                mode = 0
                            }
                        }
                        // scene fallback: share-external-content entries pass `null`; we land
                        // on SINGLE-equivalent semantics so the notice still reflects reality.
                        // Non-forward callers (file != null, plain text) never hit this branch
                        // because they don't pass `forwardContexts`.
                        val resolvedScene = scene ?: run {
                            L.w { "[SelectChatsUtils] forwardContexts present but scene=null, fallback to SINGLE" }
                            ForwardNoticeData.Scene.SINGLE
                        }
                        processForwardContextsSequentially(
                            context,
                            forwardContexts,
                            content,
                            chatsContact,
                            archiveTime = time.toInt(),
                            confidentialMode = mode,
                            scene = resolvedScene,
                            sourceConversation = sourceConversation,
                            sourceAuthorIds = sourceAuthorIds,
                            messageCount = messageCount,
                            combinedForwardMode = combinedForwardMode,
                            carriesForeignContent = carriesForeignContent,
                        )
                    }
                } else {
                    sendTasks.add {
                        sendTextPush(context, content, chatsContact.id, chatsContact.isGroup)
                        // PRD §5: partial-text forward emits SINGLE notice (mirrors partial copy).
                        // Propagate combinedForwardMode the caller computed (UNKNOWN for main-conv
                        // partial-select, SUB_COMBINED_FORWARD for CF-detail partial-select).
                        if (sourceConversation != null && !sourceAuthorIds.isNullOrEmpty()) {
                            sendForwardNotice(
                                sourceConversation,
                                emptyList(),
                                ForwardNoticeData.Scene.SINGLE,
                                sourceAuthorIds,
                                messageCount,
                                combinedForwardMode,
                                targetConversationId = chatsContact.id,
                                carriesForeignContent = carriesForeignContent,
                            )
                        }
                    }
                }

                // 添加额外消息发送任务
                if (!TextUtils.isEmpty(message)) {
                    sendTasks.add {
                        sendTextPush(context, message, chatsContact.id, chatsContact.isGroup)
                    }
                }

                // 顺序执行所有发送任务
                scope.launch(Dispatchers.IO) {
                    try {
                        sendTasks.forEach { task ->
                            task()
                        }
                        handleTaskSuccess(R.string.chat_sent, onDismiss)
                    } catch (e: Exception) {
                        handleTaskError(e)
                    }
                }
            }
        )
    }

    /**
     * Save one or more forwarded messages to the user's own notes (recipient=self).
     *
     * Signature changed from `forwardContext: ForwardContext?` to
     * `forwardContexts: List<ForwardContext>?` so multi-select save-to-notes keeps
     * EVERY selected message's attachments — the previous `.first()` shortcut at the
     * call site dropped N-1 attachments and produced wrong notice text
     * ("saved 1 message from Y" when actually saved 5).
     *
     * Each ForwardContext is processed independently for attachment permissions /
     * text push; the notice is enqueued once for the whole batch, with aggregated
     * authors and a total count.
     */
    fun saveToNotes(
        context: Activity,
        content: String,
        forwardContexts: List<ForwardContext>? = null,
        // Source conversation where the user initiated save-to-notes. Pass null to skip the notice.
        sourceConversation: For? = null,
        // Authors of the user-selected messages (deduped/priority-sorted by caller). See sendForwardNotice.
        sourceAuthorIds: List<String>? = null,
        // PRD §5.3: explicit selected-message count (CF as 1). Null → falls back to sourceAuthorIds.size.
        messageCount: Int? = null,
        // PRD v1.0 §5.3 combined-forward mode of the source selection. Default UNKNOWN.
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        // PRD v2.0 §改动1/§改动2 条件①④: whether the saved content includes someone else's real message.
        carriesForeignContent: Boolean = true,
    ) {
        // 显示全局 WaitDialog，设置为不可取消
        showWaitDialog(context)

        if (!forwardContexts.isNullOrEmpty()) {
            appScope.launch(Dispatchers.IO) {
                try {
                    forwardContexts.forEach { ctx ->
                        val list = checkForwardAttachments(ctx)
                        if (list.isNotEmpty()) {
                            givePermissionForAttachments(
                                context,
                                content,
                                globalServices.myId,
                                false,
                                list,
                                ctx
                            )
                        } else {
                            sendTextPush(
                                context,
                                content,
                                globalServices.myId,
                                false,
                                ctx,
                                sharedContactId = ctx.sharedContactId,
                                sharedContactName = ctx.sharedContactName
                            )
                        }
                    }
                    // All forward messages persisted/sent → emit one aggregated notice to the
                    // SOURCE conversation (where messages originally lived), telling its members
                    // "these messages were saved to notes". If caller didn't pass sourceConversation
                    // (non-chat entry, test tool, etc.), skip the notice silently.
                    if (sourceConversation != null) {
                        // Save-to-notes target is the user's own notes (globalServices.myId).
                        sendForwardNotice(
                            sourceConversation,
                            forwardContexts,
                            ForwardNoticeData.Scene.SAVE_TO_NOTES,
                            sourceAuthorIds,
                            messageCount,
                            combinedForwardMode,
                            targetConversationId = globalServices.myId,
                            carriesForeignContent = carriesForeignContent,
                        )
                    } else {
                        L.w { "[ForwardNotice] saveToNotes called without sourceConversation, skip notice" }
                    }
                    handleTaskSuccess(R.string.chat_saved) {}
                } catch (e: Exception) {
                    handleTaskError(e)
                }
            }
        } else {
            // Pure text save-to-notes — no source messages → no notice (nothing to summarize).
            appScope.launch(Dispatchers.IO) {
                try {
                    sendTextPush(context, content, globalServices.myId, false)
                    handleTaskSuccess(R.string.chat_sent) {}
                } catch (e: Exception) {
                    handleTaskError(e)
                }
            }
        }
    }

    fun addGroupDecoration(activity: Activity, recyclerView: RecyclerView, list: List<ChatsContact>) {
        for (i in 0 until recyclerView.itemDecorationCount) {
            recyclerView.removeItemDecorationAt(i)
        }

        val decoration = SectionDecoration(activity, object : SectionDecoration.DecorationCallback {
            override fun getGroupId(position: Int): Long {
                return if (position >= 0 && position < list.size) list[position].itemType.toLong() else 0
            }

            override fun getGroupFirstLine(position: Int): String {
                return if (position >= 0 && position < list.size) {
                    when (list[position].itemType) {
                        ITEM_TYPE_CHAT -> {
                            activity.getString(R.string.chat_select_recent_chats)
                        }

                        ITEM_TYPE_GROUP -> {
                            activity.getString(R.string.chat_select_groups)
                        }

                        else -> {
                            activity.getString(R.string.chat_select_contacts)
                        }
                    }
                } else {
                    ""
                }
            }
        })
        recyclerView.addItemDecoration(decoration)
    }

    private suspend fun processForwardContextsSequentially(
        activity: Activity,
        forwardContexts: List<ForwardContext>,
        content: String,
        chatsContact: ChatsContact,
        archiveTime: Int? = null,
        confidentialMode: Int? = null,
        scene: ForwardNoticeData.Scene,
        sourceConversation: For? = null,   // Source conversation (where the forwarded messages originally lived); notice is posted here.
        sourceAuthorIds: List<String>? = null,   // Deduped/priority-sorted authors of the user-selected messages (outer, not nested).
        messageCount: Int? = null,   // PRD §5.3: explicit selected-message count; null falls back to sourceAuthorIds.size.
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        carriesForeignContent: Boolean = true,   // PRD v2.0 §改动1: see sendForwardNotice.
    ) = coroutineScope {
        // 使用 async 等待所有内部协程完成
        val deferredTasks = forwardContexts.map { forwardContext ->
            async {
                try {
                    val list = checkForwardAttachments(forwardContext)

                    if (list.isNotEmpty()) {
                        givePermissionForAttachments(activity, content, chatsContact.id, chatsContact.isGroup, list, forwardContext, archiveTime = archiveTime, confidentialMode = confidentialMode)
                    } else {
                        sendTextPush(activity, content, chatsContact.id, chatsContact.isGroup, forwardContext, archiveTime = archiveTime, confidentialMode = confidentialMode, sharedContactId = forwardContext.sharedContactId, sharedContactName = forwardContext.sharedContactName)
                    }
                } catch (e: Exception) {
                    L.e(e) { "[SelectChatsUtils] processForwardContextsSequentially task error:" }
                    throw e
                }
            }
        }

        // fail-fast: any forwardContext failure throws, which bypasses sendForwardNotice below
        // and propagates to the onConfirm outer try/catch → handleTaskError.
        awaitAll(*deferredTasks.toTypedArray())

        // All original forward messages succeeded → enqueue one notice to the SOURCE conversation
        // (where the forwarded messages originally lived),telling original participants
        // "someone forwarded your messages away". This mirrors screenshot-notification semantics.
        // NOT to the forward target — target-side already sees the forwarded messages themselves.
        if (sourceConversation != null) {
            sendForwardNotice(sourceConversation, forwardContexts, scene, sourceAuthorIds, messageCount, combinedForwardMode, targetConversationId = chatsContact.id, carriesForeignContent = carriesForeignContent)
        } else {
            L.w { "[ForwardNotice] sourceConversation missing, skip notice (non-forward share or caller bug?)" }
        }
    }

    /**
     * Enqueue a [PushForwardNoticeSendJob] summarizing a completed multi-message forward
     * into [sourceConversation] (the chat where the forwarded messages originally lived).
     * The notice tells participants of that original conversation that someone forwarded
     * their messages away — mirroring screenshot-notification semantics.
     *
     * Aggregation rules:
     *   - authorIds: flatten top-level forwards, take `.author`, dedup (first-seen order).
     *     Nested forwards don't contribute their own authors — we count/attribute only
     *     the top-level messages the user chose to forward.
     *   - totalCount: sum of top-level forwards across all ForwardContexts.
     *     Nested forwards count as 1 (the container).
     *
     * Empty aggregation → drop silently (defensive; should not happen in practice).
     */
    private fun sendForwardNotice(
        sourceConversation: For,   // Source conversation; the notice is posted here.
        forwardedContexts: List<ForwardContext>,
        scene: ForwardNoticeData.Scene,
        // Preferred authoritative author list from the caller. PRD §5.3.4: callers SHOULD
        // pass a deduped + priority-sorted list (NoticeAggregator.computeSortedSourceAuthorIds...).
        // When non-null, used directly for the notice's authors block.
        // When null, fall back to the old flat-map aggregation over top-level forwards
        // (may incorrectly expand a combined-forward message into its nested entries).
        sourceAuthorIds: List<String>? = null,
        // PRD §5.3: explicit selected-message count (treats a CF bubble as 1). When provided
        // alongside sourceAuthorIds, this is authoritative — required because the author list
        // is deduped, so `.size` no longer equals the selected count. When null, falls back
        // to `sourceAuthorIds.size` (safe only for single-message callers where size == 1).
        messageCount: Int? = null,
        // PRD v1.0 §5.3 combined-forward mode of the source selection. Default UNKNOWN matches
        // pre-PRD callers; Phase 4 dispatch sites populate explicitly.
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        // PRD v2.0 §改动1: the forward target conversation id. Used for conditions ② (Saved) and
        // ③ (target == source). Null skips the target check (e.g. callers without a single target).
        targetConversationId: String? = null,
        // PRD v2.0 §改动1/§改动2 条件①④: whether the forwarded content includes another person's
        // real message (judged by real author at the dispatch site — CF by its inner authors).
        // Default true keeps legacy/share callers tracing; dispatch sites compute it explicitly.
        carriesForeignContent: Boolean = true,
    ) {
        // PRD v2.0 §改动1 条件①④: nothing of anyone else's left the conversation → no trace.
        if (!carriesForeignContent) {
            L.i { "[ForwardNotice] skip — no foreign content (all self) (conv=${sourceConversation.id})" }
            return
        }
        // PRD v2.0 §改动1 条件②: forwarding from inside the user's own Saved conversation has no
        // other audience. 条件③: forwarding back into the source conversation keeps the content
        // inside it — nothing left. Either way, no trace. Central guard for every forward surface.
        if (sourceConversation.id == globalServices.myId) {
            L.i { "[ForwardNotice] skip — Saved source conversation (conv=${sourceConversation.id})" }
            return
        }
        if (targetConversationId != null && targetConversationId == sourceConversation.id) {
            L.i { "[ForwardNotice] skip — target equals source conversation (conv=${sourceConversation.id})" }
            return
        }
        val authorIds: List<String>
        val totalCount: Int
        if (sourceAuthorIds != null) {
            authorIds = sourceAuthorIds.distinct()
            totalCount = messageCount ?: sourceAuthorIds.size
        } else {
            authorIds = forwardedContexts
                .flatMap { it.forwards.orEmpty() }
                .map { it.author }
                .distinct()
            totalCount = forwardedContexts.sumOf { it.forwards?.size ?: 0 }
        }

        if (authorIds.isEmpty() || totalCount == 0) {
            L.w {
                "[ForwardNotice] skip — empty notice (sourceConversation=${sourceConversation.id}, " +
                    "scene=$scene, authors=${authorIds.size}, count=$totalCount)"
            }
            return
        }

        L.i {
            "[ForwardNotice] enqueue notice job: sourceConversation=${sourceConversation.id}, " +
                "isGroup=${sourceConversation is For.Group}, scene=$scene, " +
                "authors=${authorIds.size}, count=$totalCount, mode=$combinedForwardMode, " +
                "explicitAuthors=${sourceAuthorIds != null}"
        }

        ApplicationDependencies.getJobManager().add(
            pushForwardNoticeSendJobFactory.create(
                null,
                sourceConversation,
                ForwardNoticeData(scene, authorIds, totalCount, combinedForwardMode)
            )
        )
    }

    private suspend fun givePermissionForAttachments(
        activity: Activity,
        content: String,
        accountID: String,
        isGroup: Boolean,
        list: List<Attachment>,
        forwardContext: ForwardContext?,
        archiveTime: Int? = null,
        confidentialMode: Int? = null
    ) {
        try {
            val recipientIds = mutableListOf<String>()
            if (isGroup) {
                val group = groupUtil.getSingleGroupInfo(accountID, false)
                group?.members?.forEach { member ->
                    member.id?.let { recipientIds.add(it) }
                }
                if (group != null) {
                    recipientIds.add(globalServices.myId)
                }
                requestPermission(
                    activity,
                    forwardContext,
                    list,
                    recipientIds,
                    content,
                    accountID,
                    true,
                    archiveTime = archiveTime,
                    confidentialMode = confidentialMode
                )
            } else {
                recipientIds.add(accountID)
                recipientIds.add(globalServices.myId)

                requestPermission(
                    activity,
                    forwardContext,
                    list,
                    recipientIds,
                    content,
                    accountID,
                    false,
                    archiveTime = archiveTime,
                    confidentialMode = confidentialMode
                )
            }
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] givePermissionForAttachments error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    private fun checkForwardAttachments(forwardContext: ForwardContext?): List<Attachment> {
        val attachments = mutableListOf<Attachment>()
        forwardContext?.forwards?.forEach {
            addForwardAttachments(it, attachments)
        }
        return attachments
    }

    private fun addForwardAttachments(it: Forward, attachmentList: MutableList<Attachment>) {
        it.attachments?.let { attachments ->
            attachmentList.addAll(attachments)
        }

        it.forwards?.let { forward ->
            forward.forEach { forward ->
                addForwardAttachments(forward, attachmentList)
            }
        }
    }

    private suspend fun requestPermission(
        activity: Activity,
        forwardContext: ForwardContext?,
        attachmentList: List<Attachment>,
        recipientIds: List<String>?,
        content: String?,
        accountID: String?,
        isGroup: Boolean,
        onComplete: (() -> Unit)? = null,
        index: Int = 0,
        archiveTime: Int? = null,
        confidentialMode: Int? = null
    ) {
        if (index >= attachmentList.size) {
            sendTextPush(activity, content, accountID ?: "", isGroup, forwardContext, archiveTime = archiveTime, confidentialMode = confidentialMode)
            return
        }

        try {
            val attachment = attachmentList[index]
            attachment.key?.let {
                val digest = MessageDigest.getInstance("SHA-256").digest(it)
                val fileHash = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
                val response = fileShareRepo.isExist(FileExistReq((globalServices.userManager.getUserData()?.microToken ?: ""), fileHash, recipientIds))
                val fileExistResp = response.execute().body()?.data
                if (fileExistResp?.exists == true) {
                    forwardContext?.forwards?.forEach { forward ->
                        changeAttachmentDigest(forward, attachment.id, fileExistResp, fileHash)
                    }
                }
            }

            // 递归处理下一个附件
            requestPermission(
                activity,
                forwardContext,
                attachmentList,
                recipientIds,
                content,
                accountID,
                isGroup,
                onComplete,
                index + 1,
                archiveTime = archiveTime,
                confidentialMode = confidentialMode
            )
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] requestPermission error: ${e.stackTraceToString()}" }
            onComplete?.invoke()
            throw e
        }
    }

    private fun changeAttachmentDigest(
        forward: Forward,
        attachmentId: String,
        fileExistResp: FileExistResp,
        fileHash: String
    ) {
        val attachment = forward.attachments?.find { attachment -> attachment.id == attachmentId }
        attachment?.digest = FileSystemUtils.decodeDigestHex(fileExistResp.cipherHash)
        attachment?.authorityId = fileExistResp.authorizeId
        attachment?.fileHash = fileHash

        forward.forwards?.forEach {
            changeAttachmentDigest(it, attachmentId, fileExistResp, fileHash)
        }
    }

    private val lastTimestamp = AtomicLong(0L)

    private fun getSafeTimestamp(): Long {
        while (true) {
            val current = System.currentTimeMillis()
            val last = lastTimestamp.get()
            val next = if (current <= last) last + 1 else current
            if (lastTimestamp.compareAndSet(last, next)) {
                return next
            }
        }
    }

    private suspend fun sendTextPush(
        activity: Activity,
        content: String?,
        accountID: String,
        isGroup: Boolean = false,
        forwardContext: ForwardContext? = null,
        sharedContactId: String? = null,
        sharedContactName: String? = null,
        archiveTime: Int? = null,
        confidentialMode: Int? = null
    ) {
        val forWhat = if (isGroup) For.Group(accountID) else For.Account(accountID)
        val timeStamp = getSafeTimestamp()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"

        var finalForwardContext = forwardContext
        var sharedContacts: List<SharedContact>? = null
        if (sharedContactId != null) {
            sharedContacts = mutableListOf<SharedContact>().apply {
                val phones = mutableListOf<SharedContactPhone>().apply {
                    this.add(SharedContactPhone(sharedContactId, 3, null))
                }
                this.add(SharedContact(SharedContactName(null, null, null, null, null, sharedContactName), phones, null, null, null, null))
            }
            finalForwardContext = null
        }

        val sendMessage: (Int, Int) -> Unit = { archive, mode ->
            val textMessage = TextMessage(
                messageId,
                For.Account(globalServices.myId),
                forWhat,
                timeStamp,
                timeStamp,
                System.currentTimeMillis(),
                -1,
                archive,
                0,
                0,
                mode,
                content,
                forwardContext = finalForwardContext,
                sharedContact = sharedContacts
            )
            L.i { "[Message] forward message success:" + textMessage.id }
            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
            // 注意：WaitDialog 和成功提示现在由调用方管理
        }
        if (archiveTime != null && confidentialMode != null) {
            sendMessage(archiveTime, confidentialMode)
        } else {
            try {
                val time = messageArchiveManager.getMessageArchiveTime(forWhat)
                val response = getConversationConfigs(activity, listOf(accountID))
                var mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0
                // Check group member limit or bot: force mode to 0
                if (mode == 1) {
                    if (isGroup) {
                        val memberCount = wcdb.getGroupMemberCount(accountID)
                        val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                        if (memberCount >= limit) mode = 0
                    } else if (accountID.isOfficialAccount()) {
                        mode = 0
                    }
                }
                sendMessage(time.toInt(), mode)
            } catch (e: Exception) {
                L.e { "[SelectChatsUtils] sendTextPush error: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }

    private suspend fun sendFile(context: Activity, attachmentUri: Uri, accountID: String, isGroup: Boolean = false) {
        val forWhat: For = if (isGroup) {
            For.Group(accountID)
        } else {
            For.Account(accountID)
        }

        val timeStamp = getSafeTimestamp()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"

        val fileName = FileSystemUtils.getFileName(attachmentUri.path)
        //copy file
        try {
            val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
            FileSystemUtils.copy(attachmentUri.path, filePath)

            val mimeType = MediaUtil.getMimeType(com.difft.android.base.utils.application, filePath.toUri()) ?: ""
            val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(filePath, mimeType)
            val fileSize = FileSystemUtils.getLength(filePath)

            val attachment = Attachment(
                messageId,
                0,
                mimeType,
                "".toByteArray(),
                fileSize.toInt(),
                "".toByteArray(),
                "".toByteArray(),
                fileName,
                0,
                mediaWidthAndHeight.first,
                mediaWidthAndHeight.second,
                filePath,
                AttachmentStatus.LOADING.code
            )

            val time = messageArchiveManager.getMessageArchiveTime(forWhat)
            val response = getConversationConfigs(context, listOf(accountID))
            var mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0
            // Check group member limit or bot: force mode to 0
            if (mode == 1) {
                if (isGroup) {
                    val memberCount = wcdb.getGroupMemberCount(accountID)
                    val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                    if (memberCount >= limit) mode = 0
                } else if (accountID.isOfficialAccount()) {
                    mode = 0
                }
            }

            val attachmentMessage = TextMessage(
                messageId,
                For.Account(globalServices.myId),
                forWhat,
                timeStamp,
                timeStamp,
                System.currentTimeMillis(),
                -1,
                time.toInt(),
                0,
                0,
                mode,
                null,
                mutableListOf(attachment)
            )
            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, attachmentMessage))
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] sendAttachmentPush error: ${e.stackTraceToString()}" }
            throw e
        }
    }


    private suspend fun getConversationConfigs(
        activity: Activity,
        conversations: List<String>
    ): BaseResponse<GetConversationSetResponseBody> {
        return try {
            activity.getEntryPoint().getHttpClient().httpService
                .fetchGetConversationSet((globalServices.userManager.getUserData()?.baseAuth ?: ""), GetConversationSetRequestBody(conversations))
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] getConversationConfigs error: ${e.stackTraceToString()}" }
            throw e
        }
    }
}

/**
 * 聊天选择底部弹窗Fragment
 */
@AndroidEntryPoint
class ChatSelectBottomSheetFragment() : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val isContactOnly: Boolean by lazy { arguments?.getBoolean(ARG_IS_CONTACT_ONLY) ?: false }
    private val excludeNonFriendRooms: Boolean by lazy { arguments?.getBoolean(ARG_EXCLUDE_NON_FRIEND_ROOMS) ?: false }

    var onSelected: ((ChatsContact?) -> Unit)? = null

    companion object {
        private const val ARG_IS_CONTACT_ONLY = "arg_is_contact_only"
        private const val ARG_EXCLUDE_NON_FRIEND_ROOMS = "arg_exclude_non_friend_rooms"

        fun newInstance(isContactOnly: Boolean, excludeNonFriendRooms: Boolean = false): ChatSelectBottomSheetFragment {
            return ChatSelectBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_CONTACT_ONLY, isContactOnly)
                    putBoolean(ARG_EXCLUDE_NON_FRIEND_ROOMS, excludeNonFriendRooms)
                }
            }
        }
    }

    // 使用默认容器（带圆角和拖拽条）
    override fun getContentLayoutResId(): Int = R.layout.chat_layout_forward_select_chat

    // 全屏显示
    override fun isFullScreen(): Boolean = true

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        selectChatsUtils.excludeNonFriendRooms = excludeNonFriendRooms

        if (isContactOnly) {
            view.findViewById<TextView>(R.id.title).text = getString(R.string.select_contact)
        }

        val tvClose = view.findViewById<AppCompatTextView>(R.id.tv_close)
        tvClose.setOnClickListener {
            dismiss()  // onDismiss 中会处理回调和清理
        }

        val btnClear = view.findViewById<AppCompatImageButton>(R.id.button_clear)
        val etSearch = view.findViewById<AppCompatEditText>(R.id.edittext_search_input)

        btnClear.setOnClickListener {
            etSearch.text = null
        }
        selectChatsUtils.resetButtonClear(btnClear)

        etSearch.addTextChangedListener {
            selectChatsUtils.searchKey = it.toString().trim()
            selectChatsUtils.search(lifecycleScope)
            selectChatsUtils.resetButtonClear(btnClear)
        }

        val recentChatsAdapter = object : ChatsContactSelectAdapter(isContactOnly) {
            override fun onItemClicked(data: ChatsContact?, position: Int) {
                onSelected?.invoke(data)
                // 只有联系人选择对话框才在选择后自动关闭
                if (isContactOnly) {
                    dismiss()
                }
                // 聊天选择对话框在选择后保持打开状态，等待发送确认对话框显示
            }
        }

        val tvNoResult = view.findViewById<AppCompatTextView>(R.id.tv_no_result)
        val recyclerViewRecentChats = view.findViewById<RecyclerView>(R.id.recyclerview_recent_chats)
        recyclerViewRecentChats.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentChatsAdapter
        }

        // 使用Fragment的lifecycleScope监听数据流变化，确保Fragment销毁时协程也会取消
        lifecycleScope.launch {
            if (isContactOnly) {
                selectChatsUtils._contactsFlow.collect { contacts ->
                    val contactsList = contacts.sortedByPinyin().map { contact ->
                        val displayName = contact.getDisplayNameForUI()
                        ChatsContact(
                            contact.id,
                            displayName,
                            contact.getEffectiveAvatarJson(),
                            displayName.getFirstLetter(),
                            false,
                            SelectChatsUtils.ITEM_TYPE_CONTACT,
                            null
                        )
                    }

                    withContext(Dispatchers.Main) {
                        // 检查Fragment是否仍然attached，避免崩溃
                        if (!isAdded) return@withContext

                        if (contactsList.isEmpty()) {
                            tvNoResult.visibility = View.VISIBLE
                            recyclerViewRecentChats.visibility = View.GONE
                        } else {
                            tvNoResult.visibility = View.GONE
                            recyclerViewRecentChats.visibility = View.VISIBLE
                            recentChatsAdapter.submitList(contactsList) {
                                recyclerViewRecentChats.scrollToPosition(0)
                            }
                            selectChatsUtils.addGroupDecoration(requireActivity(), recyclerViewRecentChats, contactsList)
                        }
                    }
                }
            } else {
                combine(selectChatsUtils._roomsFlow, selectChatsUtils._groupsFlow, selectChatsUtils._contactsFlow) { chats, groups, contacts ->
                    val chatsList = chats.map { chat ->
                        if (chat.roomType == 1) {
                            ChatsContact(
                                chat.roomId,
                                chat.roomName.toString(),
                                chat.roomAvatarJson,
                                null,
                                true,
                                SelectChatsUtils.ITEM_TYPE_CHAT,
                                chat.roomAvatarJson
                            )
                        } else {
                            ChatsContact(
                                chat.roomId,
                                chat.roomName.toString(),
                                chat.roomAvatarJson,
                                ContactorUtil.getFirstLetter(chat.roomName),
                                false,
                                SelectChatsUtils.ITEM_TYPE_CHAT,
                                chat.roomAvatarJson
                            )
                        }
                    }

                    val groupsList = groups.filter { it.status == 0 }
                        .sortedBy { it.name }
                        .map { group ->
                            ChatsContact(
                                group.gid ?: "",
                                group.name,
                                null,
                                null,
                                true,
                                SelectChatsUtils.ITEM_TYPE_GROUP,
                                group.avatar
                            )
                        }

                    val contactsList = contacts.sortedByPinyin().map { contact ->
                        val displayName = contact.getDisplayNameForUI()
                        ChatsContact(
                            contact.id,
                            displayName,
                            contact.getEffectiveAvatarJson(),
                            displayName.getFirstLetter(),
                            false,
                            SelectChatsUtils.ITEM_TYPE_CONTACT,
                            null
                        )
                    }

                    mutableListOf<ChatsContact>().apply {
                        addAll(chatsList)
                        addAll(contactsList)
                        addAll(groupsList)
                    }
                }.collect { combinedList ->
                    withContext(Dispatchers.Main) {
                        // 检查Fragment是否仍然attached，避免崩溃
                        if (!isAdded) return@withContext

                        if (combinedList.isEmpty()) {
                            tvNoResult.visibility = View.VISIBLE
                            recyclerViewRecentChats.visibility = View.GONE
                        } else {
                            tvNoResult.visibility = View.GONE
                            recyclerViewRecentChats.visibility = View.VISIBLE
                            recentChatsAdapter.submitList(combinedList) {
                                recyclerViewRecentChats.scrollToPosition(0)
                            }
                            selectChatsUtils.addGroupDecoration(requireActivity(), recyclerViewRecentChats, combinedList)
                        }
                    }
                }
            }
        }

        selectChatsUtils.search(lifecycleScope)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        L.i { "onDismiss" }
        // 通过回调通知 dialog 关闭，让原始实例清除引用（避免系统返回键关闭时的内存泄漏）
        onSelected?.invoke(null)
        onSelected = null  // 清除回调引用
        selectChatsUtils.searchKey = ""
    }
}