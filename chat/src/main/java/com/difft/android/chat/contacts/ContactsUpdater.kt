package com.difft.android.chat.contacts

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.messageserialization.db.store.DBMessageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import com.difft.android.websocket.api.messages.Member
import com.difft.android.websocket.api.messages.TTNotifyMessage
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ContactsUpdater @Inject constructor(
    private val userManager: UserManager,
    private val dbMessageStore: DBMessageStore,
    private val wcdb: WCDB,
) {
    private data class PendingContactMessage(
        val message: TTNotifyMessage,
        val directoryVersion: Int
    )

    private val notifyMessageChannel = Channel<PendingContactMessage>(Channel.BUFFERED)

    init {
        appScope.launch {
            processNotifyMessages()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processNotifyMessages() {
        while (true) {
            delay(3000) // Wait for 3 seconds before processing the next batch

            val batch = mutableListOf<PendingContactMessage>()
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

    private suspend fun processBatch(batch: List<PendingContactMessage>) {
        L.i { "[ContactsUpdater] Processing batch size:${batch.size}, versions:${batch.map { it.directoryVersion }}" }
        val sortedMessages = batch.sortedBy { it.directoryVersion }
        var currentVersion = getCurrentDirectoryVersion()

        for (pendingMessage in sortedMessages) {
            val memberIds = pendingMessage.message.data?.members?.map { it.number } ?: emptyList()
            if (pendingMessage.directoryVersion == currentVersion + 1) {
                L.i { "[ContactsUpdater] Version +1 current:$currentVersion, msg:${pendingMessage.directoryVersion}, members:$memberIds" }
                processContactNotifyMessage(pendingMessage.message)
                currentVersion = pendingMessage.directoryVersion
                setCurrentDirectoryVersion(currentVersion)
            } else if (pendingMessage.directoryVersion > currentVersion + 1) {
                L.i { "[ContactsUpdater] Version gap current:$currentVersion, msg:${pendingMessage.directoryVersion}, members:$memberIds, triggering full sync" }
                ContactorUtil.fetchAndSaveContactors()
                processContactNotifyMessage(pendingMessage.message)
                currentVersion = pendingMessage.directoryVersion
                setCurrentDirectoryVersion(currentVersion)
                break
            } else {
                L.i { "[ContactsUpdater] Skipping old version current:$currentVersion, msg:${pendingMessage.directoryVersion}, members:$memberIds" }
            }
        }
    }

    private suspend fun processContactNotifyMessage(message: TTNotifyMessage) {
        val members = message.data?.members
        if (members.isNullOrEmpty()) {
            L.w { "[ContactsUpdater] processContactNotifyMessage members is null or empty" }
            return
        }
        
        members.forEach { member ->
            val memberId = member.number
            val action = member.action
            when (action) {
                0 -> {
                    val newContact = member.toContactor()
                    L.i { "[ContactsUpdater] action=0(Add) id:$memberId, hasName:${member.name != null}, hasAvatar:${member.avatar != null}" }
                    wcdb.contactor.deleteObjects(DBContactorModel.id.eq(member.number))
                    wcdb.contactor.insertObject(newContact)
                    ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                    ContactorUtil.updateContactRequestStatus(member.number.toString(), isDelete = true)
                }

                1 -> {
                    val contact = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(member.number.toString()))
                    if (contact != null) {
                        val localHasAvatar = contact.avatar != null
                        val localName = contact.name
                        contact.updateFrom(member)
                        L.i { "[ContactsUpdater] action=1(Update) id:$memberId, localName:$localName, serverName:${member.name}, localHasAvatar:$localHasAvatar, serverHasAvatar:${member.avatar != null}, resultHasAvatar:${contact.avatar != null}" }
                        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(contact.id))
                        wcdb.contactor.insertObject(contact)
                        ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                    } else {
                        L.w { "[ContactsUpdater] action=1(Update) id:$memberId not in contactor table, skipped. serverHasAvatar:${member.avatar != null}" }
                    }
                }

                2 -> {
                    L.i { "[ContactsUpdater] action=2(DeleteByMe) id:$memberId" }
                    wcdb.contactor.deleteObjects(DBContactorModel.id.eq(member.number.toString()))
                    dbMessageStore.removeRoomAndMessages(member.number.toString())
                    ContactorUtil.updateContactRequestStatus(member.number.toString(), isDelete = true)
                    ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                }
                
                3 -> {
                    L.i { "[ContactsUpdater] action=3(DeleteByOther) id:$memberId" }
                    wcdb.contactor.deleteObjects(DBContactorModel.id.eq(member.number.toString()))
                    dbMessageStore.removeRoomAndMessages(member.number.toString())
                    ContactorUtil.updateContactRequestStatus(member.number.toString(), isDelete = true)
                    ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                }
                
                else -> {
                    L.w { "[ContactsUpdater] Unknown action=$action id:$memberId, hasName:${member.name != null}, hasAvatar:${member.avatar != null}" }
                }
            }
        }
    }

    suspend fun updateBySignalNotifyMessage(message: TTNotifyMessage) {
        val directoryVersion = message.data?.directoryVersion ?: 0
        val memberIds = message.data?.members?.map { it.number } ?: emptyList()
        val actions = message.data?.members?.map { it.action } ?: emptyList()
        L.i { "[ContactsUpdater] Received notify - version:$directoryVersion, members:$memberIds, actions:$actions" }
        notifyMessageChannel.send(PendingContactMessage(message, directoryVersion))
    }

    private fun getCurrentDirectoryVersion(): Int {
        return userManager.getUserData()?.directoryVersionForContactors ?: 0
    }

    private fun setCurrentDirectoryVersion(version: Int) {
        userManager.update {
            directoryVersionForContactors = version
        }
    }

    private fun Member.toContactor(): ContactorModel {
        return ContactorModel().also {
            it.id = this.number ?: ""
            it.name = this.name
            it.avatar = this.avatar
            it.meetingVersion = this.publicConfigs?.meetingVersion ?: 0
            it.publicName = this.publicConfigs?.publicName
            it.customUid = this.customUid
        }
    }

    private fun ContactorModel.updateFrom(member: Member) {
        member.name?.let {
            name = it
        }
        member.avatar?.let { avatar = it }
        member.publicConfigs?.let {
            meetingVersion = it.meetingVersion
            publicName = it.publicName
        }
        member.customUid?.let { customUid = it }
    }
}

