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
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.chat.contacts.contactsall.PinyinComparator
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
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
import difft.android.messageserialization.model.Card
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.TextMessage
import com.difft.android.network.BaseResponse
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.base.widget.sideBar.SectionDecoration
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.RoomModel
import util.FileUtils
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.MediaUtil
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

class SelectChatsUtils @Inject constructor() {

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var fileShareRepo: FileShareRepo

    @Inject
    lateinit var pushTextSendJobFactory: PushTextSendJobFactory

    private var mChatSelectDialog: ChatSelectBottomSheetFragment? = null

    private fun clearDialog() {
        mChatSelectDialog?.dismiss()
        mChatSelectDialog = null
        dismissWaitDialog()
    }

    // 获取当前Dialog的CoroutineScope，如果Dialog存在则使用其lifecycleScope，否则使用appScope作为fallback
    private val dialogScope: CoroutineScope
        get() = mChatSelectDialog?.lifecycleScope ?: appScope

    private fun showWaitDialog(activity: Activity, message: String = "") {
        ComposeDialogManager.showWait(activity, message, cancelable = false)
    }

    private fun dismissWaitDialog() {
        ComposeDialogManager.dismissWait()
    }

    /**
     * 任务成功完成后的统一处理
     * @param message 成功提示消息的资源ID
     */
    private suspend fun handleTaskSuccess(message: Int) {
        L.i { "[SelectChatsUtils] forward message success" }
        withContext(Dispatchers.Main) {
            ToastUtil.show(message)
            clearDialog()
        }
    }

    /**
     * 任务失败后的统一处理
     */
    private suspend fun handleTaskError(e: Exception) {
        L.e { "[SelectChatsUtils] forward message failed:" + e.stackTraceToString() }
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

    fun showChatSelectAndSendDialog(
        context: Activity,
        content: String,
        title: String? = null,
        logFile: File? = null,
        forwardContexts: List<ForwardContext>? = null,
        card: Card? = null,
    ) {
        showChatSelectDialog(context) { data ->
            if (data == null) return@showChatSelectDialog
            if (data.isGroup) {
                val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(data.id))
                if (group != null && !GroupUtil.canSpeak(group, globalServices.myId)) {
                    ToastUtil.show(context.getString(R.string.group_only_moderators_can_speak_tip))
                    return@showChatSelectDialog
                }
            }
            showSendToDialog(
                context,
                data,
                content,
                title,
                logFile,
                forwardContexts,
                card
            )
        }
    }

    private fun showChatSelectDialog(
        context: Activity,
        onSelected: (ChatsContact?) -> Unit
    ) {
        val fragment = ChatSelectBottomSheetFragment.newInstance(isContactOnly = false)
        fragment.onSelected = { data ->
            onSelected(data)
            // 选择会话后不关闭弹窗，只有取消时才关闭
            if (data == null) {
                clearDialog()
            }
        }

        mChatSelectDialog = fragment
        fragment.show((context as FragmentActivity).supportFragmentManager, "ChatSelectDialog")
    }

    fun showContactSelectDialog(
        context: Activity,
        onSelected: (ChatsContact?) -> Unit
    ) {
        val fragment = ChatSelectBottomSheetFragment.newInstance(isContactOnly = true)
        fragment.onSelected = onSelected

        mChatSelectDialog = fragment
        fragment.show((context as FragmentActivity).supportFragmentManager, "ContactSelectDialog")
    }


    fun saveToNotes(
        context: Activity,
        content: String,
        forwardContext: ForwardContext? = null,
        me: ContactorModel
    ) {
        val meChatsContact = ChatsContact(me.id ?: "", me.getDisplayNameForUI(), me.avatar, me.getDisplayNameForUI().getFirstLetter(), false, ITEM_TYPE_CHAT, null)

        sendToWithoutShowDialog(context, meChatsContact, content, forwardContext)
    }


    fun search() {
        dialogScope.launch(Dispatchers.IO) {
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
            val rooms = baseRooms + if (ResUtils.getString(R.string.chat_favorites).uppercase().contains(searchKey.uppercase()) &&
                !baseRooms.any { it.roomId == globalServices.myId }
            ) {
                wcdb.room.getFirstObject(DBRoomModel.roomId.eq(globalServices.myId))?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }

            val sortedRooms = rooms.sortedWith(compareByDescending<RoomModel> { it.pinnedTime ?: 0L }
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

    private fun showSendToDialog(
        context: Activity,
        chatsContact: ChatsContact,
        content: String,
        title: String? = null,
        file: File? = null,
        forwardContexts: List<ForwardContext>? = null,
        card: Card? = null
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
                textContent.text = contentText
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
                        val time = messageArchiveManager.getMessageArchiveTime(forWhat).await()
                        val response = getConversationConfigs(context, listOf(forWhat.id))
                        val mode = response.data?.conversations?.find { body -> body.conversation == forWhat.id }?.confidentialMode ?: 0
                        processForwardContextsSequentially(context, forwardContexts, content, chatsContact, archiveTime = time.toInt(), confidentialMode = mode)
                    }
                } else {
                    sendTasks.add {
                        sendTextPush(context, content, chatsContact.id, chatsContact.isGroup, card = card)
                    }
                }

                // 添加额外消息发送任务
                if (!TextUtils.isEmpty(message)) {
                    sendTasks.add {
                        sendTextPush(context, message, chatsContact.id, chatsContact.isGroup)
                    }
                }

                // 顺序执行所有发送任务
                dialogScope.launch(Dispatchers.IO) {
                    try {
                        sendTasks.forEach { task ->
                            task()
                        }
                        handleTaskSuccess(R.string.chat_sent)
                    } catch (e: Exception) {
                        handleTaskError(e)
                    }
                }
            }
        )
    }

    private fun sendToWithoutShowDialog(
        context: Activity,
        chatsContact: ChatsContact,
        content: String,
        forwardContext: ForwardContext? = null
    ) {
        // 显示全局 WaitDialog，设置为不可取消
        showWaitDialog(context)

        if (forwardContext != null) {
            val list = checkForwardAttachments(forwardContext)
            if (list.isNotEmpty()) {
                dialogScope.launch(Dispatchers.IO) {
                    try {
                        givePermissionForAttachments(
                            context,
                            content,
                            chatsContact.id,
                            chatsContact.isGroup,
                            list,
                            forwardContext
                        )
                        handleTaskSuccess(R.string.chat_saved)
                    } catch (e: Exception) {
                        handleTaskError(e)
                    }
                }
            } else {
                dialogScope.launch(Dispatchers.IO) {
                    try {
                        sendTextPush(
                            context,
                            content,
                            chatsContact.id,
                            chatsContact.isGroup,
                            forwardContext,
                            sharedContactId = forwardContext.sharedContactId,
                            sharedContactName = forwardContext.sharedContactName
                        )
                        handleTaskSuccess(R.string.chat_saved)
                    } catch (e: Exception) {
                        handleTaskError(e)
                    }
                }
            }
        } else {
            dialogScope.launch(Dispatchers.IO) {
                try {
                    sendTextPush(context, content, chatsContact.id, chatsContact.isGroup)
                    handleTaskSuccess(R.string.chat_sent)
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
        confidentialMode: Int? = null
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
                    L.e { "[SelectChatsUtils] processForwardContextsSequentially task error: ${e.stackTraceToString()}" }
                    throw e
                }
            }
        }

        // 等待所有任务完成
        awaitAll(*deferredTasks.toTypedArray())
    }

    private fun givePermissionForAttachments(
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
                val group = GroupUtil.getSingleGroupInfo(activity, accountID, false).blockingFirst()
                if (group.isPresent) {
                    group.get().members.forEach { member ->
                        recipientIds.add(member.id)
                    }
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

    private fun requestPermission(
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
                val response = fileShareRepo.isExist(FileExistReq(SecureSharedPrefsUtil.getToken(), fileHash, recipientIds))
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
        attachment?.digest = FileUtils.decodeDigestHex(fileExistResp.cipherHash)
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

    private fun sendTextPush(
        activity: Activity,
        content: String?,
        accountID: String,
        isGroup: Boolean = false,
        forwardContext: ForwardContext? = null,
        sharedContactId: String? = null,
        sharedContactName: String? = null,
        card: Card? = null,
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
                sharedContact = sharedContacts,
                card = card
            )
            L.i { "[Message] forward message success:" + textMessage.id }
            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
            // 注意：WaitDialog 和成功提示现在由调用方管理
        }
        if (archiveTime != null && confidentialMode != null) {
            sendMessage(archiveTime, confidentialMode)
        } else {
            // 由于需要调用 await()，这里需要启动协程
            try {
                val time = messageArchiveManager.getMessageArchiveTime(forWhat).blockingGet()
                val response = getConversationConfigs(activity, listOf(accountID))
                val mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0
                sendMessage(time.toInt(), mode)
            } catch (e: Exception) {
                L.e { "[SelectChatsUtils] sendTextPush error: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }

    private fun sendFile(context: Activity, attachmentUri: Uri, accountID: String, isGroup: Boolean = false) {
        val forWhat: For = if (isGroup) {
            For.Group(accountID)
        } else {
            For.Account(accountID)
        }

        val timeStamp = getSafeTimestamp()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"

        val fileName = FileUtils.getFileName(attachmentUri.path).replace(" ", "")
        //copy file
        try {
            val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
            FileUtils.copy(attachmentUri.path, filePath)

            val mimeType = MediaUtil.getMimeType(com.difft.android.base.utils.application, filePath.toUri()) ?: ""
            val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(filePath, mimeType)
            val fileSize = FileUtils.getLength(filePath)

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

            val time = messageArchiveManager.getMessageArchiveTime(forWhat).blockingGet()
            val response = getConversationConfigs(context, listOf(accountID))
            val mode = response.data?.conversations?.find { body -> body.conversation == accountID }?.confidentialMode ?: 0

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


    private fun getConversationConfigs(
        activity: Activity,
        conversations: List<String>
    ): BaseResponse<GetConversationSetResponseBody> {
        try {
            return activity.getEntryPoint().getHttpClient().httpService
                .fetchGetConversationSet(SecureSharedPrefsUtil.getBasicAuth(), GetConversationSetRequestBody(conversations))
                .blockingGet()
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
class ChatSelectBottomSheetFragment() : BottomSheetDialogFragment() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val isContactOnly: Boolean by lazy { arguments?.getBoolean(ARG_IS_CONTACT_ONLY) ?: false }

    var onSelected: ((ChatsContact?) -> Unit)? = null

    companion object {
        private const val ARG_IS_CONTACT_ONLY = "arg_is_contact_only"

        fun newInstance(isContactOnly: Boolean): ChatSelectBottomSheetFragment {
            return ChatSelectBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_CONTACT_ONLY, isContactOnly)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.chat_layout_forward_select_chat, container, false)
    }

    override fun onStart() {
        super.onStart()

        // 设置底部弹窗为全屏显示
        val dialog = dialog
        if (dialog != null) {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                // 设置底部弹窗高度为全屏
                val layoutParams = bottomSheet.layoutParams
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                bottomSheet.layoutParams = layoutParams
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 设置背景色
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1))

        if (isContactOnly) {
            view.findViewById<TextView>(R.id.title).text = getString(R.string.select_contact)
        }

        val tvClose = view.findViewById<AppCompatTextView>(R.id.tv_close)
        tvClose.setOnClickListener {
            onSelected?.invoke(null)
            dismiss()
        }

        val btnClear = view.findViewById<AppCompatImageButton>(R.id.button_clear)
        val etSearch = view.findViewById<AppCompatEditText>(R.id.edittext_search_input)

        btnClear.setOnClickListener {
            etSearch.text = null
        }
        selectChatsUtils.resetButtonClear(btnClear)

        etSearch.addTextChangedListener {
            selectChatsUtils.searchKey = it.toString().trim()
            selectChatsUtils.search()
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
                    val contactsList = contacts.sortedWith(PinyinComparator()).map { contact ->
                        ChatsContact(
                            contact.id,
                            contact.getDisplayNameForUI(),
                            contact.avatar,
                            contact.getDisplayNameForUI().getFirstLetter(),
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
                        .map { group ->
                            ChatsContact(
                                group.gid,
                                group.name,
                                null,
                                null,
                                true,
                                SelectChatsUtils.ITEM_TYPE_GROUP,
                                group.avatar
                            )
                        }

                    val contactsList = contacts.sortedWith(PinyinComparator()).map { contact ->
                        ChatsContact(
                            contact.id,
                            contact.getDisplayNameForUI(),
                            contact.avatar,
                            contact.getDisplayNameForUI().getFirstLetter(),
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

        selectChatsUtils.search()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        L.i { "onDismiss" }
        selectChatsUtils.searchKey = ""
    }
}