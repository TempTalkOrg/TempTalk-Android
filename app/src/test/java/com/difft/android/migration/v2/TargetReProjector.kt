package com.difft.android.migration.v2

import android.util.Base64
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralAttachmentMeta
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralForwardContext
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralMention
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralParticipantReadPosition
import com.difft.android.migration.v2.model.NeutralQuote
import com.difft.android.migration.v2.model.NeutralReadPosition
import com.difft.android.migration.v2.model.NeutralReaction
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.model.NeutralSharedContact
import com.difft.android.migration.v2.model.NeutralSharedContactPhone
import com.difft.android.migration.v2.model.SendState
import com.difft.android.migration.v2.model.NeutralScreenShot
import com.google.gson.Gson
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.model.deriveContentKind
import com.difft.android.migration.v2.model.fkOrNull
import org.difft.app.database.NO_SERVER_ATTACHMENT_ID
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MessageModel














object TargetReProjector {

    
    private const val ATTACHMENT_STATUS_SUCCESS = 3

    private const val MENTION_TYPE_ALL = 1
    private const val MENTION_TYPE_ME = 2

    




    fun reProject(t: InMemoryWcdbWriteTarget, selfUid: String = ""): List<NeutralRecord> {
        val out = ArrayList<NeutralRecord>()
        
        t.groupKeys.forEach { out += NeutralRecord.GroupKey(NeutralGroupKey(it.gid ?: "", it.rGroup ?: "")) }
        t.rooms.forEach {
            out += NeutralRecord.ConversationLocal(
                NeutralConversationLocal(
                    conversationRef = it.roomId,
                    roomType = it.roomType,
                    pinnedTime = it.pinnedTime?.takeIf { v -> v > 0 },
                    readPosition = it.readPosition.takeIf { v -> v > 0 }?.let { v ->
                        NeutralReadPosition(readAt = 0L, maxServerTime = v, maxNotifySequenceId = 0L, maxSequenceId = 0L)
                    },
                    
                    
                    participantReadPositions = t.readInfos.filter { ri -> ri.roomId == it.roomId }.map { ri ->
                        NeutralParticipantReadPosition(
                            uid = ri.uid,
                            readPosition = NeutralReadPosition(readAt = 0L, maxServerTime = ri.readPosition, maxNotifySequenceId = 0L, maxSequenceId = 0L),
                        )
                    },
                )
            )
        }
        t.messages.forEach { out += NeutralRecord.Message(message(t, it, selfUid)) }
        
        
        t.attachments.filter { it.quoteModelDatabaseId.fkOrNull() == null }
            .forEach { out += NeutralRecord.Attachment(attachment(t, it)) }
        return out
    }

    private fun message(t: InMemoryWcdbWriteTarget, m: MessageModel, selfUid: String): NeutralMessage {
        
        
        
        val quote = m.quoteDatabaseId.fkOrNull()?.let { qPk ->
            
            t.quotes.firstOrNull { it.databaseId.toLong() == qPk }?.let { qm ->
                NeutralQuote(
                    author = qm.author,
                    sentAt = qm.id,
                    textPreview = qm.text.takeIf { it.isNotEmpty() },
                    attachmentsMeta = t.attachments
                        .filter { it.quoteModelDatabaseId.fkOrNull() == qPk }
                        .map { metaOf(it) },
                )
            }
        }
        val forwardContext = m.forwardContextDatabaseId.fkOrNull()?.let { fcPk ->
            
            
            val top = t.forwards.filter { it.forwardContextDatabaseId.fkOrNull() == fcPk && it.parentForwardModelDatabaseId.isNoParent() }
            if (top.isEmpty()) null else {
                val fromGroup = t.forwardContexts.firstOrNull { it.databaseId.toLong() == fcPk }?.isFromGroup ?: false
                NeutralForwardContext(isFromGroup = fromGroup, roots = top.map { forwardOf(t, it) })
            }
        }
        val mentions = t.mentions.filter { it.messageId == m.id }.map {
            NeutralMention(
                uid = it.uid ?: "",
                start = it.start,
                length = it.length,
                
                type = null,
            )
        }
        val reactions = t.reactions.filter { it.messageId == m.id }.map {
            NeutralReaction(emoji = it.emoji, fromUid = it.uid ?: "", removed = false, sentAt = it.timeStamp)
        }
        
        
        val sharedContacts = t.sharedContacts.filter { it.messageId == m.id }.map { sc ->
            NeutralSharedContact(
                givenName = sc.givenName,
                familyName = sc.familyName,
                namePrefix = sc.namePrefix,
                nameSuffix = sc.nameSuffix,
                middleName = sc.middleName,
                displayName = sc.displayName,
                phones = t.sharedContactPhones.filter { it.sharedContactDatabaseId == sc.databaseId.toLong() }.map { ph ->
                    NeutralSharedContactPhone(value = ph.phoneNumber, type = ph.phoneNumberType, label = ph.phoneNumberLabel)
                },
            )
        }
        
        val attachmentRefs = t.attachments.filter { it.messageId == m.id }.mapNotNull { it.id }
        
        
        val isSystem = m.type == MessageModel.TYPE_NOTIFY
        val notify = if (isSystem) NeutralProjections.parseNotifyJson(m.messageText, m.id) else null
        
        val direction = if (selfUid.isNotEmpty()) {
            if (m.fromWho == selfUid) MessageDirection.OUTGOING else MessageDirection.INCOMING
        } else if (m.sendType > 0) MessageDirection.OUTGOING else MessageDirection.INCOMING
        return NeutralMessage(
            stableId = m.id,
            
            conversationRef = m.roomId ?: "",
            roomType = m.roomType,
            sender = m.fromWho ?: "",
            direction = direction,
            body = if (isSystem) null else m.messageText,
            sentAt = m.timeStamp,
            
            serverAt = m.systemShowTimestamp.takeIf { it != 0L } ?: m.timeStamp,
            receivedAt = m.receivedTimeStamp.takeIf { it != 0L },
            expireTimer = m.expiresInSeconds,
            
            
            expireStartedAt = when {
                m.readTime > 0 -> m.readTime
                m.readTime < 0 -> 0L
                else -> null
            },
            contentKind = deriveContentKind(m),
            systemType = notify?.rawSystemType,
            systemPayload = notify?.rawPayload,
            displayText = notify?.displayText,
            confidential = m.mode == 1,
            quote = quote,
            forwardContext = forwardContext,
            mentions = mentions,
            reactions = reactions,
            sharedContacts = sharedContacts,
            attachmentRefs = attachmentRefs,
            sendState = if (direction == MessageDirection.OUTGOING && m.sendType == 2) SendState.FAILED else null,
            sequenceId = m.sequenceId.takeIf { it != 0L },
            notifySequenceId = m.notifySequenceId.takeIf { it != 0L },
            criticalAlertType = m.criticalAlertType.takeIf { it != 0 },
            screenShot = m.screenShotJson?.takeIf { it.isNotEmpty() }
                ?.let { NeutralScreenShot(author = m.fromWho.orEmpty(), sentAt = m.timeStamp) },
            recipients = m.receiverIds?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Gson().fromJson(it, Array<String>::class.java).toList() }.getOrNull() }
                ?: emptyList(),
            
            platformExtras = if (m.playStatus == 1) mapOf("android.played" to "false") else emptyMap(),
        )
    }

    
    private fun attachment(t: InMemoryWcdbWriteTarget, a: AttachmentModel): NeutralAttachment {
        
        val pointerOnly = a.status != ATTACHMENT_STATUS_SUCCESS
        
        
        val fwd = a.forwardModelDatabaseId.fkOrNull()?.let { pk -> t.forwards.firstOrNull { it.databaseId.toLong() == pk } }
        val owner = if (fwd != null) AttachmentOwner.FORWARD else AttachmentOwner.MESSAGE
        
        
        val hostOfForward = fwd?.forwardContextDatabaseId?.let { fc -> t.messages.firstOrNull { it.forwardContextDatabaseId == fc }?.id }
        return NeutralAttachment(
            
            stableId = a.id ?: "",
            messageRef = a.messageId ?: hostOfForward ?: "",
            contentType = a.contentType,
            size = a.size.toLong(),
            width = a.width.takeIf { v -> v > 0 },
            height = a.height.takeIf { v -> v > 0 },
            durationMs = a.totalTime,
            flags = a.flags,
            amplitudes = a.amplitudes?.takeIf { it.isNotEmpty() },
            fileName = a.fileName?.takeIf { s -> s.isNotEmpty() },
            
            
            serverAttachmentId = a.authorityId?.takeIf { it != NO_SERVER_ATTACHMENT_ID },
            encryptionKey = a.key?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            
            digestBase64 = a.digest?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            pointerOnly = pointerOnly,
            owner = owner,
            ownerAuthor = fwd?.author,
            
            ownerSentAt = fwd?.let { it.id.takeIf { v -> v > 0 } ?: it.serverTimestamp },
        )
    }

    private fun metaOf(a: AttachmentModel): NeutralAttachmentMeta =
        NeutralAttachmentMeta(stableId = NeutralProjections.attachmentStableRef(a) ?: "", contentType = a.contentType, fileName = a.fileName?.takeIf { it.isNotEmpty() })

    private fun forwardOf(t: InMemoryWcdbWriteTarget, fm: ForwardModel): NeutralForward {
        
        val children = t.forwards.filter { it.parentForwardModelDatabaseId == fm.databaseId.toLong() }
        return NeutralForward(
            author = fm.author,
            sentAt = fm.serverTimestamp,
            body = fm.text.takeIf { it.isNotEmpty() },
            attachmentsMeta = t.attachments
                .filter { it.forwardModelDatabaseId.fkOrNull() == fm.databaseId.toLong() }
                .map { metaOf(it) },
            nested = children.map { forwardOf(t, it) },
        )
    }

    
    private fun Long?.isNoParent(): Boolean = fkOrNull() == null
}
