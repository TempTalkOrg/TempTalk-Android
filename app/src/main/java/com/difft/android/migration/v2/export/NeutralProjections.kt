package com.difft.android.migration.v2.export

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.SendState
import com.difft.android.migration.v2.model.deriveContentKind
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralAttachmentMeta
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralForwardContext
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralMention
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralScreenShot
import com.difft.android.migration.v2.model.NeutralParticipantReadPosition
import com.difft.android.migration.v2.model.NeutralQuote
import com.difft.android.migration.v2.model.NeutralReadPosition
import com.difft.android.migration.v2.model.NeutralReaction
import com.difft.android.migration.v2.model.NeutralSharedContact
import com.difft.android.migration.v2.model.NeutralSharedContactPhone
import com.difft.android.migration.v2.readtime.AndroidExpiryAnchor
import com.difft.android.migration.v2.readtime.ReadLadder
import org.difft.app.database.NO_SERVER_ATTACHMENT_ID
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.ROOM_FOCUS_PIN_AUTO
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel







internal object NeutralProjections {

    
    private const val SEND_TYPE_SENDING = 0
    private const val SEND_TYPE_FAILED = 2

    
    private const val PLAY_STATUS_NOT_PLAYED = 1

    
    private const val EXTRA_PLAYED = "android.played"

    




    fun message(
        readSource: MigrationReadSource,
        m: MessageModel,
        selfUid: String = "",
        ladder: ReadLadder = ReadLadder.EMPTY,
        roomReadPosition: Long = 0L,
    ): NeutralMessage {
        
        
        val attachmentRefs = readSource.attachment
            .getAllObjects(DBAttachmentModel.messageId.eq(m.id))
            .mapNotNull { it.id }

        val isSystem = m.type == MessageModel.TYPE_NOTIFY
        
        
        
        val notify = if (isSystem) parseNotify(m) else null

        val direction = direction(m, selfUid)
        val serverAt = serverAt(m)

        return NeutralMessage(
            stableId = m.id,
            
            
            conversationRef = m.roomId ?: "",
            
            
            
            roomType = m.roomType,
            sender = m.fromWho ?: "",
            direction = direction,
            
            
            body = if (isSystem) null else m.messageText,
            sentAt = m.timeStamp,
            serverAt = serverAt,
            receivedAt = m.receivedTimeStamp.takeIf { it != 0L },
            expireTimer = m.expiresInSeconds,
            
            
            expireStartedAt = AndroidExpiryAnchor.exception(
                local = AndroidExpiryAnchor.of(m.readTime, serverAt, direction, roomReadPosition),
                derived = ladder.derivedStart(direction, serverAt),
            ),
            contentKind = deriveContentKind(m),
            
            systemType = notify?.rawSystemType,
            
            systemPayload = notify?.rawPayload,
            displayText = notify?.displayText,
            confidential = m.mode == 1,
            quote = quote(readSource, m),
            forwardContext = forwardContext(readSource, m),
            mentions = mentions(readSource, m),
            reactions = reactions(readSource, m),
            sharedContacts = sharedContacts(readSource, m),
            attachmentRefs = attachmentRefs,
            sendState = sendState(m, direction),
            sequenceId = m.sequenceId.takeIf { it != 0L },
            notifySequenceId = m.notifySequenceId.takeIf { it != 0L },
            criticalAlertType = m.criticalAlertType.takeIf { it != 0 },
            screenShot = screenShot(m),
            recipients = recipients(m),
            platformExtras = platformExtras(m, notify),
        )
    }

    











    fun attachment(
        am: AttachmentModel,
        bytesPresent: Boolean,
        owner: AttachmentOwner = AttachmentOwner.MESSAGE,
        ownerAuthor: String? = null,
        ownerSentAt: Long? = null,
        stableRefOverride: String? = null,
        
        
        hostMessageRef: String? = null,
    ): NeutralAttachment = NeutralAttachment(
        
        
        stableId = stableRefOverride ?: am.id ?: "",
        
        
        
        
        messageRef = hostMessageRef ?: am.messageId ?: "",
        contentType = am.contentType,
        size = am.size.toLong(),
        width = am.width.takeIf { it > 0 },
        height = am.height.takeIf { it > 0 },
        durationMs = am.totalTime,
        
        flags = am.flags,
        amplitudes = am.amplitudes?.takeIf { it.isNotEmpty() },
        fileName = am.fileName?.takeIf { it.isNotEmpty() },
        
        
        
        
        serverAttachmentId = am.authorityId?.takeIf { it != NO_SERVER_ATTACHMENT_ID },
        encryptionKey = encodeKey(am.key),
        
        
        digestBase64 = encodeKey(am.digest),
        pointerOnly = !bytesPresent,
        owner = owner,
        ownerAuthor = ownerAuthor,
        ownerSentAt = ownerSentAt,
    )

    





    fun attachmentStableRef(am: AttachmentModel): String? =
        am.id?.takeIf { it.isNotEmpty() } ?: am.authorityId?.takeIf { it != 0L }?.toString()

    
    fun attachmentMeta(am: AttachmentModel): NeutralAttachmentMeta = NeutralAttachmentMeta(
        stableId = attachmentStableRef(am) ?: "",
        contentType = am.contentType,
        fileName = am.fileName?.takeIf { it.isNotEmpty() },
    )

    
    private fun encodeKey(key: ByteArray?): String? =
        key?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    












    fun conversationLocal(
        r: RoomModel,
        readInfoRows: List<ReadInfoModel>,
        ladder: ReadLadder = ReadLadder.EMPTY,
        selfUid: String = "",
    ): NeutralConversationLocal = NeutralConversationLocal(
        conversationRef = r.roomId,
        
        
        
        roomType = r.roomType,
        
        
        
        pinnedTime = r.pinnedTime?.takeIf { it > 0 && r.focusPin != ROOM_FOCUS_PIN_AUTO },
        
        
        readPosition = ladder.watermark,
        readPositions = ladder.rows,
        
        
        participantReadPositions = readInfoRows.filterNot { it.uid == selfUid }.map { participantReadPosition(it) },
    )

    




    fun participantReadPosition(row: ReadInfoModel): NeutralParticipantReadPosition =
        NeutralParticipantReadPosition(
            uid = row.uid,
            readPosition = NeutralReadPosition(
                readAt = 0L,
                maxServerTime = row.readPosition,
                maxNotifySequenceId = 0L,
                maxSequenceId = 0L,
            ),
        )

    
    fun groupKey(g: GroupCryptoKeysModel): NeutralGroupKey =
        
        
        NeutralGroupKey(groupId = g.gid ?: "", rGroup = g.rGroup ?: "", keyVersion = g.keyVersion)

    














    fun identity(u: UserData): NeutralIdentity? {
        val publicKey = u.aciIdentityPublicKey
        val privateKey = u.aciIdentityPrivateKey
        if (publicKey.isNullOrEmpty() || privateKey.isNullOrEmpty()) {
            L.w { "[MigrationV2][Export] identity absent, container will carry none" }
            return null
        }
        return NeutralIdentity(
            aciPublicKey = publicKey,
            aciPrivateKey = privateKey,
            oldAciPublicKey = u.aciIdentityOldPublicKey,
            oldAciPrivateKey = u.aciIdentityOldPrivateKey,
            identityKeyGenTime = u.aciIdentityKeyGenTime,
            favKey = u.favKey,
            favKeyId = u.favKeyId,
            favKeyVersion = u.favKeyVersion,
            proxyShareLink = u.proxyShareLink,
            proxyEnabled = u.proxyEnabled,
            proxyProtectCallIp = u.proxyProtectCallIp,
            recoverySlotKeks = null,
            recoveryStaleSlots = null,
        )
    }

    










    fun credential(u: UserData, handoffId: String): NeutralCredential? {
        val account = u.account
        val baseAuth = u.baseAuth
        val microToken = u.microToken
        val signalingKey = u.signalingKey
        if (account.isNullOrEmpty() || baseAuth.isNullOrEmpty() ||
            microToken.isNullOrEmpty() || signalingKey.isNullOrEmpty() || handoffId.isBlank()
        ) {
            L.w { "[MigrationV2][Export] credential incomplete — container will carry none" }
            return null
        }
        return NeutralCredential(
            account = account,
            baseAuth = baseAuth,
            microToken = microToken,
            signalingKey = signalingKey,
            email = u.email,
            phoneNumber = u.phoneNumber,
            handoffId = handoffId,
        )
    }

    
    
    

    

    









    private fun direction(m: MessageModel, selfUid: String): MessageDirection {
        
        
        if (selfUid.isEmpty()) L.w { "[MigrationV2] direction fallback: no selfUid, classifying by sendType id=${m.id}" }
        return if (isOutgoing(m, selfUid)) MessageDirection.OUTGOING else MessageDirection.INCOMING
    }

    
    internal fun isOutgoing(m: MessageModel, selfUid: String): Boolean =
        if (selfUid.isNotEmpty()) m.fromWho == selfUid else m.sendType > 0

    
    internal fun serverAt(m: MessageModel): Long = m.systemShowTimestamp.takeIf { it != 0L } ?: m.timeStamp

    



    private fun sendState(m: MessageModel, direction: MessageDirection): SendState? = when {
        direction != MessageDirection.OUTGOING -> null
        m.sendType == SEND_TYPE_FAILED -> SendState.FAILED
        m.sendType == SEND_TYPE_SENDING -> SendState.FAILED
        else -> null
    }

    




    private fun platformExtras(m: MessageModel, notify: NotifyInfo?): Map<String, String> =
        if (m.playStatus == PLAY_STATUS_NOT_PLAYED) mapOf(EXTRA_PLAYED to "false") else emptyMap()

    





    private fun screenShot(m: MessageModel): NeutralScreenShot? =
        m.screenShotJson?.takeIf { it.isNotEmpty() }?.let {
            NeutralScreenShot(author = m.fromWho.orEmpty(), sentAt = m.timeStamp)
        }

    



    private fun recipients(m: MessageModel): List<String> =
        m.receiverIds?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, Array<String>::class.java).toList() }.getOrNull() }
            ?: emptyList()

    





    private fun parseNotify(m: MessageModel): NotifyInfo = parseNotifyJson(m.messageText, m.id)

    



    fun parseNotifyJson(messageText: String?, debugId: String): NotifyInfo {
        val raw = messageText?.takeIf { it.isNotEmpty() }
        val json: JsonObject? = raw?.let {
            runCatching { gson.fromJson(it, JsonObject::class.java) }
                .onFailure { e -> L.w { "[MigrationV2][Export] notify parse failed id=$debugId: ${e.message}" } }
                .getOrNull()
        }
        return NotifyInfo(
            displayText = json?.get("showContent")?.takeIf { !it.isJsonNull }?.asString,
            rawSystemType = json?.get("notifyType")?.takeIf { !it.isJsonNull }?.asString,
            rawPayload = raw,
        )
    }

    
    data class NotifyInfo(
        val displayText: String?,
        val rawSystemType: String?,
        val rawPayload: String?,
    )

    private fun quote(readSource: MigrationReadSource, m: MessageModel): NeutralQuote? = m.quoteDatabaseId?.let { qId ->
        readSource.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))?.let { qm ->
            NeutralQuote(
                author = qm.author,
                sentAt = qm.id, 
                textPreview = qm.text.takeIf { it.isNotEmpty() },
                
                attachmentsMeta = readSource.attachment
                    .getAllObjects(DBAttachmentModel.quoteModelDatabaseId.eq(qm.databaseId))
                    .map { attachmentMeta(it) },
            )
        }
    }

    private fun mentions(readSource: MigrationReadSource, m: MessageModel): List<NeutralMention> =
        readSource.mention.getAllObjects(DBMentionModel.messageId.eq(m.id)).map { mm ->
            NeutralMention(
                uid = mm.uid ?: "",
                start = mm.start,
                length = mm.length,
                
                
                type = mm.type,
            )
        }

    private fun reactions(readSource: MigrationReadSource, m: MessageModel): List<NeutralReaction> =
        readSource.reaction.getAllObjects(DBReactionModel.messageId.eq(m.id)).map { rm ->
            NeutralReaction(
                emoji = rm.emoji,
                fromUid = rm.uid ?: "",
                removed = false, 
                sentAt = rm.timeStamp,
            )
        }

    





    private fun sharedContacts(readSource: MigrationReadSource, m: MessageModel): List<NeutralSharedContact> =
        readSource.sharedContact.getAllObjects(DBSharedContactModel.messageId.eq(m.id)).map { sc ->
            NeutralSharedContact(
                givenName = sc.givenName,
                familyName = sc.familyName,
                namePrefix = sc.namePrefix,
                nameSuffix = sc.nameSuffix,
                middleName = sc.middleName,
                displayName = sc.displayName,
                phones = readSource.sharedContactPhone
                    .getAllObjects(DBSharedContactPhoneModel.sharedContactDatabaseId.eq(sc.databaseId.toLong()))
                    .map { ph ->
                        NeutralSharedContactPhone(
                            value = ph.phoneNumber,
                            type = ph.phoneNumberType,
                            label = ph.phoneNumberLabel,
                        )
                    },
            )
        }

    






    private fun forwardContext(readSource: MigrationReadSource, m: MessageModel): NeutralForwardContext? {
        val fcId = m.forwardContextDatabaseId ?: return null
        val fc = readSource.forwardContext.getFirstObject(DBForwardContextModel.databaseId.eq(fcId)) ?: return null
        val topLevel = readSource.forward.getAllObjects(
            DBForwardModel.forwardContextDatabaseId.eq(fcId)
                .and(DBForwardModel.parentForwardModelDatabaseId.isNull)
        )
        if (topLevel.isEmpty()) return null
        return NeutralForwardContext(
            isFromGroup = fc.isFromGroup,
            roots = topLevel.map { forwardOf(readSource, it) },
        )
    }

    private fun forwardOf(readSource: MigrationReadSource, fm: ForwardModel): NeutralForward {
        val nested = readSource.forward
            .getAllObjects(DBForwardModel.parentForwardModelDatabaseId.eq(fm.databaseId))
            .map { forwardOf(readSource, it) }
        return NeutralForward(
            author = fm.author,
            sentAt = forwardSentAt(fm),
            body = fm.text.takeIf { it.isNotEmpty() },
            
            attachmentsMeta = readSource.attachment
                .getAllObjects(DBAttachmentModel.forwardModelDatabaseId.eq(fm.databaseId))
                .map { attachmentMeta(it) },
            nested = nested,
        )
    }

    






    internal fun forwardSentAt(fm: ForwardModel): Long = fm.id.takeIf { it > 0 } ?: fm.serverTimestamp

    






    fun subStructureAttachments(readSource: MigrationReadSource, m: MessageModel): List<OwnedAttachment> {
        val out = ArrayList<OwnedAttachment>()
        
        m.forwardContextDatabaseId?.let { fcId ->
            readSource.forward.getAllObjects(DBForwardModel.forwardContextDatabaseId.eq(fcId)).forEach { fm ->
                readSource.attachment
                    .getAllObjects(DBAttachmentModel.forwardModelDatabaseId.eq(fm.databaseId))
                    .forEach { am ->
                        out += OwnedAttachment(am, AttachmentOwner.FORWARD, fm.author, forwardSentAt(fm), hostMessageId = m.id)
                    }
            }
        }
        
        
        return out
    }

    



    data class OwnedAttachment(
        val model: AttachmentModel,
        val owner: AttachmentOwner,
        val ownerAuthor: String,
        val ownerSentAt: Long,
        
        val hostMessageId: String,
    )


    
    private val gson = Gson()
}
