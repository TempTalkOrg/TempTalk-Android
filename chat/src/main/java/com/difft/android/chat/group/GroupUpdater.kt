package com.difft.android.chat.group

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import difft.android.messageserialization.model.NotifyMessage
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.rx3.awaitFirstOrNull
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupMemberContactorModel
import com.difft.android.websocket.api.messages.GroupNotifyDetailType
import com.difft.android.websocket.api.messages.Member
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates group information and member details upon receiving a group message from the signal server.
 * The update process involves saving the updated data to the database.
 *
 * Note: The following notification types do not require checking the group version value:
 * - DTGroupNotifyTypeCallEndFeedback: Indicates a call end feedback notification.
 * - DTGroupNotifyTypeGroupCycleReminder: Indicates a group cycle reminder notification.
 * - DTGroupNotifyTypeMeetingReminder: Indicates a meeting reminder notification.
 * - DTGroupNotifyDetailTypeGroupInactive: Indicates the group is inactive and will be automatically destroyed.
 * - DTGroupNotifyDetailTypeArchive: Indicates the group will be automatically archived and synced to the mobile client.
 *
 * For other notification types, it is necessary to check the group version.
 * If the group version is continuously increasing without interruption (e.g., 1, 2, 3, 4),
 * handle the data accordingly. If there is a discrepancy in the version sequence,
 * perform a network request to update the group information.
 *
 * The update is based on the specific group notification and group detail notification types received.
 */

@Singleton
class GroupUpdater @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val messageArchiveManager: MessageArchiveManager,
    private val conversationSettingsManager: ConversationSettingsManager,
    private val gson: Gson,
    private val dbMessageStore: DBMessageStore,
    private val wcdb: WCDB,
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
) {
    private data class PendingGroupMessage(
        val message: TTNotifyMessage,
        val wrapperData: SignalServiceDataClass,
        val groupVersion: Int,
    )

    private val notifyMessageChannel = Channel<PendingGroupMessage>(Channel.BUFFERED)
    private val groupVersionCache = mutableMapOf<String, Int>()

    init {
        appScope.launch {
            processNotifyMessages()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processNotifyMessages() {
        while (true) {
            delay(3000) // Wait for 3 seconds before processing the next batch

            val batch = mutableListOf<PendingGroupMessage>()
            // Drain the channel and collect messages into the batch
            while (!notifyMessageChannel.isEmpty) {
                val message = notifyMessageChannel.receive()
                batch.add(message)
            }

            if (batch.isNotEmpty()) {
                processBatch(batch)
            }
        }
    }

    private suspend fun processBatch(batch: List<PendingGroupMessage>) {
        // Group messages by group ID
        val messagesByGroup = batch.groupBy { it.wrapperData.conversation.id }

        messagesByGroup.forEach { (groupId, messages) ->
            // Sort messages by group version
            val sortedMessages = messages.sortedBy { it.groupVersion }

            // Get current version from cache or database
            var currentVersion = groupVersionCache[groupId] ?: -1
            if (currentVersion == -1) {
                val currentGroup = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupId))
                currentVersion = currentGroup?.version ?: -1
                groupVersionCache[groupId] = currentVersion
            }

            // Process messages in order
            for (pendingMessage in sortedMessages) {
                try {
                    val messageVersion = pendingMessage.groupVersion

                    // Skip messages with older versions
                    if (messageVersion < currentVersion) {
                        L.i { "[GroupUpdater] Skipping older/equal version message for group $groupId. Current: $currentVersion, Message: $messageVersion" }
                        continue
                    }

                    // Check if version is consecutive
                    if (messageVersion == currentVersion + 1) {
                        L.i { "[GroupUpdater] Version has grown by 1 for group $groupId. Current: $currentVersion, Message: $messageVersion" }
                        processGroupNotifyMessage(pendingMessage.message, pendingMessage.wrapperData)
                        // Update version only after successful processing
                        currentVersion = messageVersion
                        groupVersionCache[groupId] = currentVersion
                    } else {
                        // Version gap detected, force update and recheck
                        L.i { "[GroupUpdater] Version gap detected for group $groupId. Current: $currentVersion, Message: $messageVersion" }
                        processGroupNotifyMessage(pendingMessage.message, pendingMessage.wrapperData)
                        forceUpdateGroup(groupId)
                        // Recheck version after force update
                        val updatedGroup = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupId))
                        val newCurrentVersion = updatedGroup?.version ?: -1
                        groupVersionCache[groupId] = newCurrentVersion
                    }
                } catch (e: Exception) {
                    L.e { "[GroupUpdater] Error processing group notify message for group $groupId, timestamp:${pendingMessage.wrapperData.signalServiceEnvelope.timestamp}, version:${pendingMessage.groupVersion}, error:${e.stackTraceToString()}" }
                    continue
                }
            }
        }
    }

    suspend fun handleGroupNotifyMessage(
        message: TTNotifyMessage,
        wrapperData: SignalServiceDataClass
    ) {
        val groupVersion = message.data?.groupVersion ?: 0
        notifyMessageChannel.send(PendingGroupMessage(message, wrapperData, groupVersion))
    }

    private suspend fun processGroupNotifyMessage(
        message: TTNotifyMessage,
        wrapperData: SignalServiceDataClass
    ) {
        L.i { "[GroupUpdater] Start process group notify message for group: ${wrapperData.conversation.id}" }
        val myId = wrapperData.myId
        val groupID = wrapperData.conversation.id
        val groupDetailType = message.data?.groupNotifyDetailedType ?: -1
        val groupVersion = message.data?.groupVersion ?: 0
        val operator: String = message.data?.inviter ?: message.data?.operator ?: myId
        val operatorName: String = if (operator == myId) {
            ResUtils.getString(R.string.you)
        } else {
            val contactor = ContactorUtil.getContactWithID(context, operator).await()
            if (contactor.isPresent) contactor.get().getDisplayNameForUI() else ""
        }

        var notifyContent: String? = null
        var expiresTime: Int = getExpiresTime(For.Group(groupID))

        when (groupDetailType) {
            GroupNotifyDetailType.CreateGroup.value -> {
                // Create notification content first
                notifyContent = context.getString(R.string.group_create_a_group, operatorName)
                // Then create group in parallel
                appScope.launch {
                    createNewGroupIfNotExist(groupID)
                }
            }

            GroupNotifyDetailType.DismissGroup.value -> {
                L.i { "[GroupUpdater] Group dismiss notify, disable group $groupID" }
                disableGroup(groupID)
            }

            GroupNotifyDetailType.GroupNameChange.value -> {
                L.i { "[GroupUpdater] Group name change notify" }
                updateGroupName(groupID, message.data?.group?.name ?: "", groupVersion)
                notifyContent = context.getString(R.string.group_changed_group_name, operatorName)
            }

            GroupNotifyDetailType.GroupAvatarChange.value -> {
                L.i { "[GroupUpdater] Group avatar change notify" }
                updateGroupAvatar(groupID, message.data?.group?.avatar ?: "", groupVersion)
                notifyContent = context.getString(R.string.group_changed_group_avatar, operatorName)
            }

            GroupNotifyDetailType.GroupMsgExpiryChange.value -> {
                L.i { "[GroupUpdater] Group message expire change notify" }
                message.data?.group?.let {
                    updateGroupExpireChange(groupID, it.messageExpiry, it.messageClearAnchor, groupVersion)
                }
            }

            GroupNotifyDetailType.Destroy.value -> {
                L.i { "[GroupUpdater] Group Destroy" }
                disableGroup(groupID)
            }

            GroupNotifyDetailType.GroupPublishRuleChange.value -> {
                L.i { "[GroupUpdater] Group GroupPublishRuleChange" }
                message.data?.group?.publishRule?.let {
                    updateGroupPublishRule(groupID, it, groupVersion)
                }

                notifyContent = if (message.data?.group?.publishRule == 2) {
                    context.getString(R.string.group_allow_everyone_to_speak)
                } else {
                    context.getString(R.string.group_only_moderators_can_speak)
                }
            }

            GroupNotifyDetailType.GroupInvitationRuleChange.value -> {
                L.i { "[GroupUpdater] Group GroupInvitationRuleChange" }
                message.data?.group?.invitationRule?.let {
                    updateGroupInvitationRule(groupID, it, groupVersion)
                }
            }

            GroupNotifyDetailType.LeaveGroup.value -> {
                L.i { "[GroupUpdater] Group leave notify, disable group $groupID" }
                disableGroup(groupID)
                if (operatorName == ResUtils.getString(R.string.you)) {
                    expiresTime = -1
                } else {
                    notifyContent = context.getString(R.string.group_left_the_group, operatorName)
                }
            }

            GroupNotifyDetailType.InviteJoinGroup.value -> {
                message.data?.members?.let {
                    L.i { "[GroupUpdater] Group invite notify, update group members" }
                    updateGroupMembers(groupID, it, groupVersion)
                }
                val operatorName2 = StringBuilder()
                message.data?.members?.forEach {
                    if (wrapperData.myId == it.uid) {
                        operatorName2.append(context.getString(R.string.you) + ",")
                    } else {
                        operatorName2.append(it.displayName + ",")
                    }
                }
                notifyContent = context.getString(
                    R.string.group_invited_join_group,
                    operatorName,
                    operatorName2.substring(0, operatorName2.length - 1)
                )
            }

            GroupNotifyDetailType.KickoutGroup.value, GroupNotifyDetailType.KickoutAutoClear.value -> {
                L.i { "[GroupUpdater] Group kickout notify" }
                var isKickoutMe = false
                message.data?.members?.forEach {
                    if (myId == it.uid) {
                        isKickoutMe = true
                    }
                }
                if (isKickoutMe) {
                    L.i { "[GroupUpdater] Group kickout me notify, disable group $groupID" }
                    disableGroup(groupID)
                } else {
                    message.data?.members?.let {
                        L.i { "[GroupUpdater] Group kickout someone not me notify, update group members" }
                        updateGroupMembers(groupID, it, groupVersion)
                    }
                }
                val operatorName2 = StringBuilder()
                message.data?.members?.forEach {
                    if (wrapperData.myId == it.uid) {
                        operatorName2.append(context.getString(R.string.you) + ",")
                    } else {
                        operatorName2.append(it.displayName + ",")
                    }
                }
                notifyContent = context.getString(
                    R.string.group_removed_from_group,
                    operatorName2.substring(0, operatorName2.length - 1)
                )
            }

            GroupNotifyDetailType.GroupAddAdmin.value -> {
                message.data?.members?.let {
                    L.i { "[GroupUpdater] GroupAddAdmin, update group members" }
                    updateGroupMembers(groupID, it, groupVersion)
                }
                val operatorName2 = StringBuilder()
                message.data?.members?.forEach {
                    if (wrapperData.myId == it.uid) {
                        operatorName2.append(context.getString(R.string.you) + ",")
                    } else {
                        operatorName2.append(it.displayName + ",")
                    }
                }
                notifyContent = context.getString(
                    R.string.group_become_new_moderator,
                    operatorName2.substring(0, operatorName2.length - 1)
                )
            }

            GroupNotifyDetailType.GroupDeleteAdmin.value -> {
                message.data?.members?.let {
                    L.i { "[GroupUpdater] GroupDeleteAdmin, update group members" }
                    updateGroupMembers(groupID, it, groupVersion)
                }
                val operatorName2 = StringBuilder()
                message.data?.members?.forEach {
                    if (wrapperData.myId == it.uid) {
                        operatorName2.append(context.getString(R.string.you) + ",")
                    } else {
                        operatorName2.append(it.displayName + ",")
                    }
                }
                notifyContent = context.getString(
                    R.string.group_been_removed_moderator,
                    operatorName2.substring(0, operatorName2.length - 1)
                )
            }

            GroupNotifyDetailType.GroupOwnerChange.value -> {
                message.data?.members?.let {
                    L.i { "[GroupUpdater] GroupOwnerChange, update group members" }
                    updateGroupMembers(groupID, it, groupVersion)
                }
                val newOwner = message.data?.members?.find { it.uid != operator }
                newOwner?.let {
                    val newOwnerName = if (it.uid == wrapperData.myId) {
                        context.getString(R.string.you)
                    } else {
                        it.displayName ?: ""
                    }
                    notifyContent = context.getString(
                        R.string.group_transferred_moderator,
                        operatorName,
                        newOwnerName
                    )
                }
            }

            GroupNotifyDetailType.GroupCriticalAlertChange.value -> {
                message.data?.group?.criticalAlert?.let {
                    updateGroupCriticalAlert(groupID, it, groupVersion)
                }
            }
        }

        // Create and save notify message if needed
        if (!notifyContent.isNullOrEmpty()) {
            message.showContent = notifyContent
            val messageId = message.notifyTime.toString() + operator.replace("+", "") + wrapperData.signalServiceEnvelope.sourceDevice
            val fromWho: For = For.Account(operator)

            val notifyMessage = NotifyMessage(
                messageId,
                fromWho,
                wrapperData.conversation,
                wrapperData.signalServiceEnvelope.systemShowTimestamp,
                wrapperData.signalServiceEnvelope.timestamp,
                System.currentTimeMillis(),
                SendType.Sent.rawValue,
                expiresTime,
                wrapperData.signalServiceEnvelope.notifySequenceId,
                wrapperData.sequenceId,
                0,
                gson.toJson(message),
            )

            try {
                dbMessageStore.putWhenNonExist(notifyMessage)
                L.i { "[GroupUpdater] save 1 messages to db ${notifyMessage.timeStamp}" }
//                //sendAck for messages
//                removeServerMessage(wrapperData.signalServiceEnvelope.source, wrapperData.signalServiceEnvelope.timestamp)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                L.e { "[GroupUpdater] save message exception -> ${e.stackTraceToString()}" }
            }
        }
    }

//    private suspend fun removeServerMessage(source: String, timestamp: Long) {
//        kotlin.runCatching {
//            val response = httpClient.httpService.removePendingMessage(SecureSharedPrefsUtil.getBasicAuth(), source, timestamp.toString()).await()
//            if (response.status == 0) {
//                L.i { "[GroupUpdater] remove Server Message success $timestamp" }
//            } else {
//                L.e { "[GroupUpdater] remove Server Message failed, status: ${response.status}, reason: ${response.reason}" }
//            }
//        }.onFailure {
//            L.e { "[GroupUpdater] remove Server Message failed -> ${it.stackTraceToString()}" }
//        }
//    }

    private suspend fun createNewGroupIfNotExist(groupID: String) {
        L.i { "[GroupUpdater] Create new group if not exist, group id is $groupID" }
        if (wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID)) != null) {
            return
        } else {
            val result = GroupUtil.fetchAndSaveSingleGroupInfo(context, groupID, true).awaitFirstOrNull()
            L.i { "[GroupUpdater] $result" }
        }
    }

    private suspend fun updateGroupName(groupID: String, newGroupName: String, version: Int) {
        L.i { "[GroupUpdater] Update group name, new name is $newGroupName" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.name = newGroupName
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.name, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }

    private suspend fun updateGroupAvatar(groupID: String, avatar: String, version: Int) {
        L.i { "[GroupUpdater] Update group avatar, new avatar is $avatar" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.avatar = avatar
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.avatar, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }

    private suspend fun updateGroupExpireChange(groupID: String, messageExpireValue: Int, messageClearAnchor: Long, version: Int) {
        L.i { "[GroupUpdater] Update group expire, new expire is $messageExpireValue" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.messageExpiry = messageExpireValue
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.messageExpiry, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            messageArchiveManager.updateLocalArchiveTime(For.Group(groupID), messageExpireValue.toLong(), messageClearAnchor)
            // 通知 ViewModel 更新
            conversationSettingsManager.emitConversationSettingUpdate(
                conversationId = groupID,
                messageExpiry = messageExpireValue.toLong(),
                messageClearAnchor = messageClearAnchor
            )
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }

    private suspend fun updateGroupInvitationRule(groupID: String, invitationRule: Int, version: Int) {
        L.i { "[GroupUpdater] Update group invitationRule: $invitationRule" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.invitationRule = invitationRule
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.invitationRule, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }

    private suspend fun updateGroupPublishRule(groupID: String, publishRule: Int, version: Int) {
        L.i { "[GroupUpdater] Update group PublishRule: $publishRule" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.publishRule = publishRule
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.publishRule, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }

    private suspend fun updateGroupMembers(groupID: String, members: List<Member>, version: Int) {
        try {
            L.i { "[GroupUpdater] Start update group members ${members.map { it.uid }}" }
            val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
            if (group == null) {
                L.i { "[GroupUpdater] Group not exist, create new group $groupID" }
                createNewGroupIfNotExist(groupID)
            } else {
                // Update group's members info from members and make up members' info from contactors
                val currentMembers = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(group.gid))

                members.forEach { member ->
                    when (member.action) {
                        0 -> { // Add
                            // Query contactor information for the new member
                            val newMember = GroupMemberContactorModel().apply {
                                gid = groupID
                                id = member.uid ?: ""
                                displayName = member.displayName
                                rapidRole = member.rapidRole
                                groupRole = member.role
                            }
                            L.i { "[GroupUpdater] Add new member ${newMember.id}" }
                            currentMembers.add(newMember)
                        }

                        1 -> { // Update
                            val existingMember = currentMembers.find { it.id == member.uid }
                            existingMember?.let {
                                it.displayName = member.displayName
                                it.rapidRole = member.rapidRole
                                it.groupRole = member.role
                            }
                            L.i { "[GroupUpdater] Update existing member $existingMember" }
                        }

                        2, 3 -> { // Delete or Permanent Delete
                            currentMembers.removeAll { it.id == member.uid }
                            L.i { "[GroupUpdater] Delete member ${member.uid}" }
                        }
                    }
                }

                // Set the updated members list to the group
                group.version = version
                wcdb.group.updateObject(group, DBGroupModel.version, DBGroupModel.gid.eq(groupID))
                val distinctMembers = currentMembers.distinctBy { it.id }
                L.d { "[GroupUpdater] Update group members, new members are [${distinctMembers.mapNotNull { it.displayName }.joinToString(",")}]" }
                // Save the updated group back to the database
                wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(groupID))
                wcdb.groupMemberContactor.insertObjects(distinctMembers)
                GroupUtil.emitSingleGroupUpdate(group)
            }
        } catch (e: Exception) {
            L.e { "[GroupUpdater] Update group members error: ${e.stackTraceToString()}" }
        }
    }

    private suspend fun disableGroup(groupID: String) {
        L.i { "[GroupUpdater] Group disable notify, disable group $groupID" }
        GroupUtil.fetchAndSaveSingleGroupInfo(context, groupID, true).awaitFirst()
        // Update cache after disable
        groupVersionCache.remove(groupID)
    }

    private suspend fun forceUpdateGroup(groupID: String) {
        L.i { "[GroupUpdater] force Update Group $groupID" }
        GroupUtil.fetchAndSaveSingleGroupInfo(context, groupID, true).awaitFirst()
        // Update cache after force update
        val updatedGroup = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        groupVersionCache[groupID] = updatedGroup?.version ?: -1
    }

    private suspend fun getExpiresTime(forWhat: For): Int {
        val option = messageArchiveManager.getMessageArchiveTime(forWhat).await()
        return option.toInt()
    }

    private suspend fun updateGroupCriticalAlert(groupID: String, criticalAlert: Boolean, version: Int) {
        L.i { "[GroupUpdater] Update group CriticalAlert: $criticalAlert" }
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(groupID))
        if (group != null) {
            group.criticalAlert = criticalAlert
            group.version = version
            wcdb.group.updateObject(group, arrayOf(DBGroupModel.criticalAlert, DBGroupModel.version), DBGroupModel.gid.eq(groupID))
            GroupUtil.emitSingleGroupUpdate(group)
        } else {
            createNewGroupIfNotExist(groupID)
        }
    }
}