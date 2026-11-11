package com.difft.android.chat.ui

import android.widget.ImageView
import android.app.Activity
import com.difft.android.base.widget.floatAsTopBarButton
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.TaskWait
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.base.utils.globalServices
import com.difft.android.network.NetworkException
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.auth.withInvalidTokenRetry
import com.difft.android.network.config.GlobalConfigsManager
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.members
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.ForwardAttachmentMaterializer
import com.difft.android.chat.attachment.deepCopyWithNewAttachmentIdentities
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
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.base.widget.DifftSearchInputView
import com.difft.android.base.widget.sideBar.SectionDecoration
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import com.difft.android.chat.gif.favorite.attachmentFileHashOf
import com.difft.android.chat.util.MediaUtil
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import javax.inject.Inject

data class ChatsContact(
    val id: String,
    val name: String?,
    val avatar: String?,
    val firstLetters: String?,
    val isGroup: Boolean,
    val itemType: Int,  
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
    lateinit var authTokenProvider: AuthTokenProvider

    @Inject
    lateinit var pushTextSendJobFactory: PushTextSendJobFactory

    @Inject
    lateinit var pushForwardNoticeSendJobFactory: PushForwardNoticeSendJobFactory

    






    private suspend fun handleTaskSuccess(message: Int, wait: TaskWait, onDismiss: () -> Unit) {
        L.i { "[SelectChatsUtils] forward message success" }
        withContext(Dispatchers.Main) {
            wait.release()
            ToastUtil.show(message)
            onDismiss()
        }
    }

    private suspend fun handleTaskError(e: Exception, wait: TaskWait) {
        L.e(e) { "[SelectChatsUtils] forward message failed:" }
        withContext(Dispatchers.Main) {
            wait.release()
            ToastUtil.show(R.string.operation_failed)
        }
    }

    
    private suspend fun releaseTaskWait(wait: TaskWait) {
        withContext(NonCancellable + Dispatchers.Main) { wait.release() }
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
        
        
        
        
        sourceConversation: For? = null,
        
        
        
        sourceAuthorIds: List<String>? = null,
        
        
        
        messageCount: Int? = null,
        
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        
        
        carriesForeignContent: Boolean = true,
        
        
        onFileReleased: (() -> Unit)? = null,
    ) {
        val sending = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        fun releaseOwnedFile() {
            if (onFileReleased != null && released.compareAndSet(false, true)) {
                try { onFileReleased() }
                catch (e: Exception) { L.w { "[SelectChats] source_cleanup_failed error=${e.javaClass.simpleName}" } }
            }
        }
        val fragment = ChatSelectBottomSheetFragment.newInstance(
            isContactOnly = false,
            excludeNonFriendRooms = file != null
        )
        fragment.onSelected = onSelected@{ data ->
            if (data == null) {
                if (!sending.get()) releaseOwnedFile()
                return@onSelected
            }
            try {
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
                    onDismiss = { fragment.dismiss() },
                    onSendStarted = { onFileReleased == null || (!released.get() && sending.compareAndSet(false, true)) },
                    onFileReleased = if (onFileReleased == null) null else ::releaseOwnedFile,
                )
            } catch (e: Exception) {
                if (onFileReleased == null) throw e
                releaseOwnedFile()
                fragment.dismissAllowingStateLoss()
                L.w { "[SelectChats] file_confirmation_failed error=${e.javaClass.simpleName}" }
            }
        }
        if (onFileReleased != null) fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && !sending.get()) releaseOwnedFile()
        })
        try {
            fragment.show((context as FragmentActivity).supportFragmentManager, "ChatSelectDialog")
        } catch (e: IllegalStateException) {
            releaseOwnedFile()
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

    private var etMessage: DifftClearableInputView? = null

    
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
        onDismiss: () -> Unit,
        onSendStarted: () -> Boolean = { true },
        onFileReleased: (() -> Unit)? = null,
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
                
                
                textContent.text = contentText.normalizeNewlines()
            },
            onConfirm = {
                if (!onSendStarted()) return@showMessageDialog
                val message = forwardNoteText(etMessage)

                
                val wait = try { TaskWait.claim(context, wait = null) }
                catch (e: Exception) {
                    if (onFileReleased == null) throw e
                    onFileReleased()
                    onDismiss()
                    L.w { "[SelectChats] file_send_start_failed error=${e.javaClass.simpleName}" }
                    return@showMessageDialog
                }

                val sendTasks = mutableListOf<suspend () -> Unit>()

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
                        
                        if (mode == 1) {
                            if (chatsContact.isGroup) {
                                val memberCount = wcdb.getGroupMemberCount(chatsContact.id)
                                val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                                if (memberCount >= limit) mode = 0
                            } else if (chatsContact.id.isOfficialAccount()) {
                                mode = 0
                            }
                        }
                        
                        
                        
                        
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

                if (!TextUtils.isEmpty(message)) {
                    sendTasks.add {
                        sendTextPush(context, message, chatsContact.id, chatsContact.isGroup)
                    }
                }

                val sendJob = scope.launch(Dispatchers.IO) {
                    try {
                        sendTasks.forEach { task ->
                            task()
                        }
                        handleTaskSuccess(R.string.chat_sent, wait, onDismiss)
                    } catch (e: CancellationException) {
                        if (onFileReleased != null) throw e
                        handleTaskError(e, wait)
                    } catch (e: Exception) {
                        handleTaskError(e, wait)
                        if (onFileReleased != null) withContext(Dispatchers.Main) { onDismiss() }
                    } finally {
                        releaseTaskWait(wait)
                    }
                }
                
                
                if (onFileReleased != null) sendJob.invokeOnCompletion { onFileReleased() }
            },
        )
    }

    



    fun saveToNotes(
        context: Activity,
        content: String,
        forwardContexts: List<ForwardContext>? = null,
        
        sourceConversation: For? = null,
        
        sourceAuthorIds: List<String>? = null,
        
        messageCount: Int? = null,
        
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        
        carriesForeignContent: Boolean = true,
        
        
        wait: PageWait? = null,
    ) {
        val taskWait = TaskWait.claim(context, wait)

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
                    
                    
                    
                    
                    if (sourceConversation != null) {
                        
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
                    handleTaskSuccess(R.string.chat_saved, taskWait) {}
                } catch (e: Exception) {
                    handleTaskError(e, taskWait)
                } finally {
                    releaseTaskWait(taskWait)
                }
            }
        } else {
            
            appScope.launch(Dispatchers.IO) {
                try {
                    sendTextPush(context, content, globalServices.myId, false)
                    handleTaskSuccess(R.string.chat_sent, taskWait) {}
                } catch (e: Exception) {
                    handleTaskError(e, taskWait)
                } finally {
                    releaseTaskWait(taskWait)
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
        sourceConversation: For? = null,   
        sourceAuthorIds: List<String>? = null,   
        messageCount: Int? = null,   
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        carriesForeignContent: Boolean = true,   
    ) = coroutineScope {
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

        
        
        awaitAll(*deferredTasks.toTypedArray())

        
        
        
        
        if (sourceConversation != null) {
            sendForwardNotice(sourceConversation, forwardContexts, scene, sourceAuthorIds, messageCount, combinedForwardMode, targetConversationId = chatsContact.id, carriesForeignContent = carriesForeignContent)
        } else {
            L.w { "[ForwardNotice] sourceConversation missing, skip notice (non-forward share or caller bug?)" }
        }
    }

    














    private fun sendForwardNotice(
        sourceConversation: For,   
        forwardedContexts: List<ForwardContext>,
        scene: ForwardNoticeData.Scene,
        
        
        
        
        
        sourceAuthorIds: List<String>? = null,
        
        
        
        
        messageCount: Int? = null,
        
        
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        
        
        targetConversationId: String? = null,
        
        
        
        carriesForeignContent: Boolean = true,
    ) {
        
        if (!carriesForeignContent) {
            L.i { "[ForwardNotice] skip — no foreign content (all self) (conv=${sourceConversation.id})" }
            return
        }
        
        
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
                
                
                val fileHash = attachmentFileHashOf(it)
                
                
                
                
                val fileExistResp = withContext(Dispatchers.IO) {
                    runCatching {
                        authTokenProvider.withInvalidTokenRetry("forwardRepairIsExist") { token ->
                            val resp = fileShareRepo.isExist(FileExistReq(token, fileHash, recipientIds)).execute()
                            if (!resp.isSuccessful) {
                                throw NetworkException(resp.code(), "isExist fail: ${resp.message()}")
                            }
                            resp.body() ?: throw IOException("isExist response body is null")
                        }
                    }.onFailure {
                        L.w { "[SelectChatsUtils] isExist failed, forward keeps its existing pointer: ${it.stackTraceToString()}" }
                    }.getOrNull()?.data
                }
                if (fileExistResp?.exists == true) {
                    forwardContext?.forwards?.forEach { forward ->
                        changeAttachmentDigest(forward, attachment.localId, fileExistResp, fileHash)
                    }
                } else if (fileExistResp?.exists == false) {
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    L.w { "[SelectChatsUtils] isExist miss, forward attachment needs re-upload localId=${attachment.localId}" }
                    attachment.authorityId = 0L
                    attachment.fileHash = fileHash
                }
            }

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

        
        
        
        
        val targetForwardContext = forwardContext?.deepCopyWithNewAttachmentIdentities()
        if (targetForwardContext != null) {
            
            
            
            try {
                val result = withContext(Dispatchers.IO) { ForwardAttachmentMaterializer.materialize(targetForwardContext) }
                if (result.copied > 0 || result.failed > 0) {
                    L.i { "[FwdAttachCopy] target=$accountID copied=${result.copied} skipped=${result.skipped} failed=${result.failed}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[FwdAttachCopy] materialize failed target=$accountID: ${e.stackTraceToString()}" }
            }
        }
        var finalForwardContext = targetForwardContext
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
            
        }
        if (archiveTime != null && confidentialMode != null) {
            sendMessage(archiveTime, confidentialMode)
        } else {
            try {
                val time = messageArchiveManager.getMessageArchiveTime(forWhat)
                val response = getConversationConfigs(activity, listOf(accountID))
                var mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0
                
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
                
                
                targetForwardContext?.let { ForwardAttachmentMaterializer.discard(it) }
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
        
        
        val attachmentLocalId = UUID.randomUUID().toString()
        
        try {
            val filePath = AttachmentPathResolver.stagingFileFor(attachmentLocalId, fileName)
            FileSystemUtils.copy(attachmentUri.path, filePath)

            val mimeType = MediaUtil.getMimeType(com.difft.android.base.utils.application, filePath.toUri()) ?: ""
            val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(filePath, mimeType)
            val fileSize = FileSystemUtils.getLength(filePath)

            val attachment = Attachment(
                
                
                
                "",
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
                AttachmentStatus.LOADING.code,
                localId = attachmentLocalId
            )

            val time = messageArchiveManager.getMessageArchiveTime(forWhat)
            val response = getConversationConfigs(context, listOf(accountID))
            var mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0
            
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
                .fetchGetConversationSet(GetConversationSetRequestBody(conversations))
        } catch (e: Exception) {
            L.e { "[SelectChatsUtils] getConversationConfigs error: ${e.stackTraceToString()}" }
            throw e
        }
    }
}






internal fun changeAttachmentDigest(
    forward: Forward,
    attachmentLocalId: String,
    fileExistResp: FileExistResp,
    fileHash: String
) {
    forward.attachments
        ?.find { attachment -> attachment.localId == attachmentLocalId }
        ?.let { attachment ->
            attachment.digest = FileSystemUtils.decodeDigestHex(fileExistResp.cipherHash)
            attachment.authorityId = fileExistResp.authorizeId
            attachment.fileHash = fileHash
        }

    forward.forwards?.forEach {
        changeAttachmentDigest(it, attachmentLocalId, fileExistResp, fileHash)
    }
}

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

    override fun getContentLayoutResId(): Int = R.layout.chat_layout_forward_select_chat

    override fun isFullScreen(): Boolean = true

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        
        
        if (onSelected == null) {
            L.i { "[SelectChats] restored picker without callback, dismissing" }
            dismissAllowingStateLoss()
            return
        }
        selectChatsUtils.excludeNonFriendRooms = excludeNonFriendRooms

        if (isContactOnly) {
            view.findViewById<TextView>(R.id.title).text = getString(R.string.select_contact)
        }

        view.findViewById<ImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
            dismiss()
        }

        val searchInput = view.findViewById<DifftSearchInputView>(R.id.search_input)

        searchInput.onQueryChanged = { key ->
            selectChatsUtils.searchKey = key.trim()
            selectChatsUtils.search(lifecycleScope)
        }
        searchInput.onClear = {
            selectChatsUtils.searchKey = ""
            selectChatsUtils.search(lifecycleScope)
        }

        val recentChatsAdapter = object : ChatsContactSelectAdapter(isContactOnly) {
            override fun onItemClicked(data: ChatsContact?, position: Int) {
                onSelected?.invoke(data)
                
                if (isContactOnly) {
                    dismiss()
                }
                
            }
        }

        val tvNoResult = view.findViewById<AppCompatTextView>(R.id.tv_no_result)
        val recyclerViewRecentChats = view.findViewById<RecyclerView>(R.id.recyclerview_recent_chats)
        recyclerViewRecentChats.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentChatsAdapter
        }

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
        
        onSelected?.invoke(null)
        onSelected = null
        selectChatsUtils.searchKey = ""
    }
}


internal fun forwardNoteText(input: DifftClearableInputView?): String = input?.query.orEmpty().trim()
