package org.thoughtcrime.securesms.util

import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.wcdb
import com.difft.android.chat.message.createForward
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.TextMessage
import com.google.protobuf.ByteString
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import org.difft.app.database.models.DBAttachmentModel
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import org.whispersystems.signalservice.internal.push.DataMessageKt.ContactKt.name
import org.whispersystems.signalservice.internal.push.DataMessageKt.ContactKt.phone
import org.whispersystems.signalservice.internal.push.DataMessageKt.contact
import org.whispersystems.signalservice.internal.push.DataMessageKt.forwardContext
import org.whispersystems.signalservice.internal.push.DataMessageKt.group
import org.whispersystems.signalservice.internal.push.DataMessageKt.mention
import org.whispersystems.signalservice.internal.push.DataMessageKt.quote
import org.whispersystems.signalservice.internal.push.DataMessageKt.reaction
import org.whispersystems.signalservice.internal.push.DataMessageKt.recall
import org.whispersystems.signalservice.internal.push.DataMessageKt.screenShot
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage.Contact
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage.Mention
import org.whispersystems.signalservice.internal.push.attachmentPointer
import org.whispersystems.signalservice.internal.push.card
import org.whispersystems.signalservice.internal.push.dataMessage
import org.whispersystems.signalservice.internal.push.rapidFile
import org.whispersystems.signalservice.internal.push.realSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataMessageCreator @Inject constructor(
) {
    fun createFrom(textMessage: TextMessage): SignalServiceProtos.DataMessage {
        val quote: SignalServiceProtos.DataMessage.Quote? = textMessage.quote?.let {
            quote {
                id = it.id
                author = it.author
                text = it.text
            }
        }

        val forwardContext = textMessage.forwardContext?.let { it ->
            forwardContext {
                it.forwards?.map { createForward(it) }?.let { it1 -> forwards.addAll(it1) }
                isFromGroup = it.isFromGroup

                val attachments = getForwardAttachments(it)
                attachments.forEach { attachment ->
                    val rapidFile = rapidFile {
                        rapidHash = attachment.fileHash ?: ""
                        authorizedId = attachment.authorityId.toString()
                    }
                    rapidFiles.add(rapidFile)
                }
            }
        }

        val recall: SignalServiceProtos.DataMessage.Recall? = textMessage.recall?.let {
            it.realSource?.let { realSource ->
                recall {
                    source = realSource {
                        source = realSource.source
                        sourceDevice = realSource.sourceDevice
                        timestamp = realSource.timestamp
                        serverTimestamp = realSource.serverTimestamp
                    }
                }
            }
        }

        val card: SignalServiceProtos.Card? = textMessage.card?.let {
            card {
                it.appId?.let { appId = it }
                it.cardId?.let { cardId = it }
                version = it.version
                it.creator?.let { creator = it }
                timestamp = it.timestamp
                it.content?.let { content = it }
                contentType = it.contentType
                type = it.type
                fixedWidth = it.fixedWidth
            }
        }

        val mentions: List<Mention> =
            textMessage.mentions?.let { mentions ->
                mutableListOf<Mention>().apply {
                    mentions.forEach { mention ->
                        this.add(
                            mention {
                                start = mention.start
                                length = mention.length
                                mention.uid?.let { uid = it }
                                type = Mention.Type.valueOf(mention.type)
                            }
                        )
                    }
                }
            } ?: emptyList()

        val reaction: SignalServiceProtos.DataMessage.Reaction? = textMessage.reactions?.let {
            val reaction = it.firstOrNull()
            reaction?.realSource?.let { source ->
                reaction {
                    emoji = reaction.emoji
                    remove = reaction.remove
                    originTimestamp = if (remove) reaction.originTimestamp else 0L
                    this.source = realSource {
                        this.source = source.source
                        sourceDevice = source.sourceDevice
                        timestamp = source.timestamp
                        serverTimestamp = source.serverTimestamp
                    }
                }
            }
        }

        val screenShot: SignalServiceProtos.DataMessage.ScreenShot? = textMessage.screenShot?.let {
            it.realSource?.let { source ->
                screenShot {
                    this.source = realSource {
                        this.source = source.source
                        sourceDevice = source.sourceDevice
                        timestamp = source.timestamp
                        serverTimestamp = source.serverTimestamp
                    }
                }
            }
        }

        val sharedContacts: List<Contact> =
            textMessage.sharedContact?.let { contacts ->
                mutableListOf<Contact>().apply {
                    contacts.forEach { contact ->
                        val name = name {
                            contact.name?.displayName?.let {
                                familyName = it
                                displayName = it
                            }
                        }
                        val phone = mutableListOf<Contact.Phone>().apply {
                            contact.phone?.forEach {
                                this.add(
                                    phone {
                                        type = Contact.Phone.Type.valueOf(it.type)
                                        it.value?.let(::value::set)
                                        it.label?.let(::label::set)
                                    }
                                )
                            }
                        }
                        this.add(
                            contact {
                                this.name = name
                                this.number.addAll(phone)
                            }
                        )
                    }
                }
            } ?: emptyList()
        val attachment = wcdb.attachment.getFirstObject(DBAttachmentModel.messageId.eq(textMessage.id))
        val signalServiceAttachment: AttachmentPointer? = attachment?.let {
            attachmentPointer {
                cdnNumber = 0
                id = attachment.authorityId
                contentType = attachment.contentType
                key = ByteString.copyFrom(attachment.key)
                size = attachment.size
                width = attachment.width
                height = attachment.height
                digest = ByteString.copyFrom(attachment.digest)
                attachment.fileName?.let(::fileName::set)
                uploadTimestamp = System.currentTimeMillis()
                flags = attachment.flags
            }
        }

        val groupId = textMessage.forWhat.id.transformGroupIdFromLocalToServer().takeIf { textMessage.forWhat is For.Group }
        val createdDataMessage = dataMessage {
            timestamp = textMessage.timeStamp
            textMessage.text?.let(::body::set)
            quote?.let(::quote::set)
            forwardContext?.let(::forwardContext::set)
            recall?.let(::recall::set)
            card?.let(::card::set)
            textMessage.atPersons?.let(::atPersons::set)
            mentions.let { this.mentions.addAll(it) }
            textMessage.expiresInSeconds.let(::expireTimer::set)
            SignalServiceProtos.Mode.valueOf(textMessage.mode).let(::messageMode::set)
            reaction?.let(::reaction::set)
            screenShot?.let(::screenShot::set)
            sharedContacts.let { this.contact.addAll(it) }
            signalServiceAttachment?.let { this.attachments.add(it) }
            groupId?.let(ByteString::copyFrom)?.let {
                group {
                    id = it
                    type = SignalServiceProtos.DataMessage.Group.Type.DELIVER
                }
            }?.let(::group::set)
            requiredProtocolVersion = calculateRequiredProtocolVersion(textMessage)
        }
        L.i { "[Message] createDataMessage -> messageId:${textMessage.id}, requiredProtocolVersion:${createdDataMessage.requiredProtocolVersion}" }
        return createdDataMessage
    }

    /**
     * Calculate the minimum required protocol version based on message content.
     * This allows older clients to display "unsupported message" for features they don't support.
     */
    private fun calculateRequiredProtocolVersion(textMessage: TextMessage): Int {
        var version = SignalServiceProtos.DataMessage.ProtocolVersion.INITIAL_VALUE

        if (textMessage.forwardContext != null) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.FORWARD_VALUE)
        }
        if (!textMessage.sharedContact.isNullOrEmpty()) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.CONTACT_VALUE)
        }
        if (textMessage.recall != null) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.RECALL_VALUE)
        }
        if (!textMessage.reactions.isNullOrEmpty()) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.REACTION_VALUE)
        }
        if (textMessage.card != null) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.CARD_VALUE)
        }
        if (textMessage.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.CONFIDE_VALUE)
        }
        if (textMessage.screenShot != null) {
            version = maxOf(version, SignalServiceProtos.DataMessage.ProtocolVersion.SCREEN_SHOT_VALUE)
        }

        return version
    }

    private fun getForwardAttachments(forwardContext: ForwardContext): List<Attachment> {
        val attachments = mutableListOf<Attachment>()
        forwardContext.forwards?.forEach {
            addForwardAttachments(it, attachments)
        }
        return attachments
    }

    private fun addForwardAttachments(it: Forward, attachmentList: MutableList<Attachment>) {
        it.attachments?.let { attachments ->
            attachmentList.addAll(attachments)
        }

        it.forwards?.let { forward ->
            forward.forEach { forward1 ->
                addForwardAttachments(forward1, attachmentList)
            }
        }
    }
}