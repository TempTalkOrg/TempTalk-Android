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
        // Sort messages by directory version
        val sortedMessages = batch.sortedBy { it.directoryVersion }
        var currentVersion = getCurrentDirectoryVersion()

        for (pendingMessage in sortedMessages) {
            if (pendingMessage.directoryVersion == currentVersion + 1) {
                L.i { "[ContactsUpdater] Version has grown by 1. Current: $currentVersion, Message: ${pendingMessage.directoryVersion}" }
                // Process message normally
                processContactNotifyMessage(pendingMessage.message)
                currentVersion = pendingMessage.directoryVersion
                setCurrentDirectoryVersion(currentVersion)
            } else if (pendingMessage.directoryVersion > currentVersion + 1) {
                L.i { "[ContactsUpdater] Version gap detected. Current: $currentVersion, Message: ${pendingMessage.directoryVersion}" }
                // Force update contacts
                ContactorUtil.fetchAndSaveContactors()
                // Still process the notification message
                processContactNotifyMessage(pendingMessage.message)
                // Update version after force update
                currentVersion = pendingMessage.directoryVersion
                setCurrentDirectoryVersion(currentVersion)
                break
            } else {
                L.i { "[ContactsUpdater] Skipping older version message. Current: $currentVersion, Message: ${pendingMessage.directoryVersion}" }
            }
        }
    }

    private suspend fun processContactNotifyMessage(message: TTNotifyMessage) {
        // Process member actions
        message.data?.members?.let { members ->
            members.forEach { member ->
                when (member.action) {
                    0 -> {
                        L.i { "[ContactsUpdater] Add new contact into contactors ${member.number}" }
                        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(member.number))
                        wcdb.contactor.insertObject(member.toContactor())
                        ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                        ContactorUtil.updateContactRequestStatus(member.number.toString(), isDelete = true)
                    }

                    1 -> {
                        L.i { "[ContactsUpdater] Update contact into contactors ${member.number}" }
                        val contact = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(member.number.toString()))
                        if (contact != null) {
                            contact.updateFrom(member)
                            wcdb.contactor.deleteObjects(DBContactorModel.id.eq(contact.id))
                            wcdb.contactor.insertObject(contact)
                            ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                        }
                        if (member.number == globalServices.myId) {
                            member.privateConfigs?.saveToPhotos?.let {
                                L.i { "[ContactsUpdater] Update saveToPhotos for my contact: $it" }
                                userManager.update { saveToPhotos = it }
                            }
                        }
                    }

                    2, 3 -> {
                        L.i { "[ContactsUpdater] Delete contact from contactors ${member.number}" }
                        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(member.number.toString()))
                        dbMessageStore.removeRoomAndMessages(member.number.toString())
                        ContactorUtil.updateContactRequestStatus(member.number.toString(), isDelete = true)
                        ContactorUtil.emitContactsUpdate(listOf(member.number.toString()))
                    }
                }
            }
        }
    }

    suspend fun updateBySignalNotifyMessage(message: TTNotifyMessage) {
        val directoryVersion = message.data?.directoryVersion ?: 0
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
    }
}

