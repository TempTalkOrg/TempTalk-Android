package org.difft.app.database

import androidx.annotation.WorkerThread
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.checkThread
import com.difft.android.base.utils.globalServices
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.google.gson.JsonObject
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.core.Table
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.isAnimatedImage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isWhitelistedVideoType
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.DBPendingRemovalContactModel
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.DBSpeechToTextModel
import org.difft.app.database.models.DBTranslateModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.PendingRemovalContactModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiDatabaseEntryPoint {
    val wcdb: WCDB
}

val wcdb: WCDB by lazy {
    EntryPointAccessors.fromApplication<DiDatabaseEntryPoint>(application).wcdb
}

@JvmName("searchContactor")
fun Table<ContactorModel>.search(keyword: String): List<ContactorModel> {
    val like = "%${keyword.uppercase()}%"
    return getAllObjects(
        (DBContactorModel.remark.upper().like(like))
            .or(DBContactorModel.name.upper().like(like))
            .or(DBContactorModel.publicName.upper().like(like))
    )
}

@JvmName("searchGroupMemberContactor")
fun Table<GroupMemberContactorModel>.search(keyword: String): List<GroupMemberContactorModel> {
    val like = "%${keyword.uppercase()}%"
    return getAllObjects(
        DBGroupMemberContactorModel.displayName.upper().like(like)
            .or(DBGroupMemberContactorModel.remark.upper().like(like))
    )
}


private fun PendingRemovalContactModel.deserializeSnapshot(): ContactorModel? =
    snapshotJson?.let { runCatching { globalServices.gson.fromJson(it, ContactorModel::class.java) }.getOrNull() }

fun WCDB.getContactorFromAllTable(id: String): ContactorModel? {
    val fromContactor = contactor.getFirstObject(DBContactorModel.id.eq(id))
    if (fromContactor != null) {
        return fromContactor
    }
    val rows = groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.id.eq(id))
    val fromGroupMember = rows.firstOrNull { it.gid == "" } ?: rows.firstOrNull()
    if (fromGroupMember != null) {
        L.i { "[WCDBExtensions] getContactorFromAllTable id:$id from groupMemberContactor gid=${fromGroupMember.gid}" }
        return fromGroupMember.convertToContactorModel()
    }
    
    return pendingRemovalContact.getFirstObject(DBPendingRemovalContactModel.uid.eq(id))?.deserializeSnapshot()
}










fun WCDB.isKnownContact(uid: String): Boolean =
    contactor.getFirstObject(DBContactorModel.id.eq(uid)) != null


fun WCDB.getMessageById(id: String): MessageModel? =
    message.getFirstObject(DBMessageModel.id.eq(id))

fun WCDB.getContactorsFromAllTable(ids: List<String>, preferredGid: String? = null): List<ContactorModel> {
    val foundContactors = contactor.getAllObjects(DBContactorModel.id.`in`(ids))
    val otherIds = ids.toList() - foundContactors.map { it.id }.toSet()

    if (otherIds.isNotEmpty()) {
        L.i { "[WCDBExtensions] getContactorsFromAllTable ids from groupMemberContactor: $otherIds" }
    }

    val rawGroupMemberRows = groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.id.`in`(otherIds))

    
    
    
    
    val deduplicatedRows = rawGroupMemberRows.groupBy { it.id }.map { (_, rows) ->
        if (rows.size > 1) {
            preferredGid?.let { gid -> rows.firstOrNull { it.gid == gid } }
                ?: rows.firstOrNull { it.gid == "" }
                ?: rows.first()
        } else {
            rows.first()
        }
    }

    val othersFromGroupMember = deduplicatedRows.map { it.convertToContactorModel() }
    val merged = (foundContactors + othersFromGroupMember).distinctBy { it.id }

    
    val stillMissing = ids.toList() - merged.map { it.id }.toSet()
    if (stillMissing.isEmpty()) {
        return merged
    }
    val fromPending = pendingRemovalContact
        .getAllObjects(DBPendingRemovalContactModel.uid.`in`(stillMissing))
        .mapNotNull { it.deserializeSnapshot() }
    return (merged + fromPending).distinctBy { it.id }
}

val GroupModel.members: List<GroupMemberContactorModel>
    get() = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))

fun GroupMemberContactorModel.group(wcdb: WCDB): GroupModel? {
    return wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))
}

fun Table<GroupModel>.searchByNameAndGroupMembers(key: String): List<GroupModel> {
    
    val groupsByName = getAllObjects(
        DBGroupModel.name.like("%$key%").and(DBGroupModel.status.eq(0))
    )
    val contactors = wcdb.groupMemberContactor.search(key)
    val groupGids = contactors.map { it.gid }.filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()
    val groupsByMember = if (groupGids.isNotEmpty()) {
        getAllObjects(DBGroupModel.gid.`in`(*groupGids.toTypedArray()).and(DBGroupModel.status.eq(0)))
    } else {
        emptyList()
    }
    return (groupsByName + groupsByMember).distinctBy { it.gid }
}









fun WCDB.getCommonGroupsCount(userId1: String, userId2: String): Int {
    val user1Groups = groupMemberContactor.getAllObjects(
        DBGroupMemberContactorModel.id.eq(userId1)
    ).map { it.gid }.distinct()
    if (user1Groups.isEmpty()) {
        return 0
    }

    val user2Groups = groupMemberContactor.getAllObjects(
        DBGroupMemberContactorModel.id.eq(userId2)
    ).map { it.gid }.distinct()

    val commonGroupIds = user1Groups.intersect(user2Groups.toSet())
    val commonGroupIdsClean = commonGroupIds.filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()
    L.i { "[getCommonGroupsCount] user1Groups:${user1Groups.size} user2Groups:${user2Groups.size} commonGroupIds:${commonGroupIds.size} commonGroupIdsClean:${commonGroupIdsClean.size}" }

    if (commonGroupIdsClean.isEmpty()) {
        return 0
    }

    return group.getAllObjects(
        DBGroupModel.gid.`in`(*commonGroupIdsClean.toTypedArray())
            .and(DBGroupModel.status.eq(0))
    ).size
}


fun MessageModel.convertToTextMessage(): TextMessage {
    val attachmentModels = wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(id))
    val attachments = if (attachmentModels.isNotEmpty()) {
        attachmentModels.map { am ->
            Attachment(
                
                
                id = am.id.orEmpty(),
                
                
                
                authorityId = am.authorityId ?: NO_SERVER_ATTACHMENT_ID,
                contentType = am.contentType ?: GENERIC_CONTENT_TYPE,
                key = am.key,
                size = am.size,
                thumbnail = am.thumbnail,
                digest = am.digest,
                fileName = am.fileName,
                flags = am.flags,
                width = am.width,
                height = am.height,
                path = null, 
                status = am.status,
                totalTime = am.totalTime,
                amplitudes = convertAmplitudes(am.amplitudes),
                localId = am.localId ?: am.synthesizedLocalId(),
                videoProbeState = am.videoProbeState,
            )
        }
    } else {
        null
    }

    val mentions: List<Mention>? = mentions().takeIf { it.isNotEmpty() }
    val reactions: List<Reaction>? = reactions().takeIf { it.isNotEmpty() }
    val forwardContext: ForwardContext? = forwardContext()
    val quote: Quote? = quote()
    val sharedContacts: List<SharedContact>? = sharedContacts().takeIf { it.isNotEmpty() }
    val translateData: TranslateData? = translateData()
    val speechToTextData: SpeechToTextData? = speechToTextData()
    val screenShot: ScreenShot? = screenShot()


    val fromWhoFor = For.Account(fromWho ?: "")
    val forWhatFor = if (roomType == 0) For.Account(roomId ?: "") else For.Group(roomId ?: "")

    return TextMessage(
        id = id,
        fromWho = fromWhoFor,
        forWhat = forWhatFor,
        systemShowTimestamp = systemShowTimestamp,
        timeStamp = timeStamp,
        receivedTimeStamp = receivedTimeStamp,
        sendType = sendType,
        expiresInSeconds = expiresInSeconds,
        notifySequenceId = notifySequenceId,
        sequenceId = sequenceId,
        mode = mode,
        text = messageText,
        attachments = attachments,
        quote = quote,
        forwardContext = forwardContext,
        mentions = mentions,
        atPersons = atPersons,
        reactions = reactions,
        screenShot = screenShot,
        sharedContact = sharedContacts,
        translateData = translateData,
        speechToTextData = speechToTextData,
        receiverIds = receiverIds,
        criticalAlertType = criticalAlertType,
        isUnsupported = type == MessageModel.TYPE_UNSUPPORTED,
    )
}


const val NO_SERVER_ATTACHMENT_ID = 0L


const val GENERIC_CONTENT_TYPE = "application/octet-stream"







fun AttachmentModel.toAttachment(): Attachment {
    return Attachment(
        
        id = id.orEmpty(),
        authorityId = authorityId ?: NO_SERVER_ATTACHMENT_ID,
        contentType = contentType ?: GENERIC_CONTENT_TYPE,
        key = key,
        size = size,
        thumbnail = thumbnail,
        digest = digest,
        fileName = fileName,
        flags = flags,
        width = width,
        height = height,
        path = null, 
        status = status,
        totalTime = totalTime,
        amplitudes = convertAmplitudes(amplitudes),
        localId = localId ?: synthesizedLocalId(),
        
        
        isForwardCopy = forwardModelDatabaseId != null,
        videoProbeState = videoProbeState,
    )
}

fun Attachment.toAttachmentModel(messageId: String): AttachmentModel {
    return AttachmentModel().also {
        
        
        it.id = id.takeIf { value -> value.isNotEmpty() }
        it.localId = localId
        it.messageId = messageId
        it.authorityId = authorityId
        it.contentType = contentType
        it.key = key ?: ByteArray(0)
        it.size = size
        it.thumbnail = thumbnail ?: ByteArray(0)
        it.digest = digest ?: ByteArray(0)
        it.fileName = fileName ?: ""
        it.flags = flags
        it.width = width
        it.height = height
        it.status = status
    }
}

fun Attachment.toAttachmentModel(forwardDatabaseId: Long): AttachmentModel {
    return AttachmentModel().also {
        
        it.id = id.takeIf { value -> value.isNotEmpty() }
        it.localId = localId
        it.forwardModelDatabaseId = forwardDatabaseId
        it.authorityId = authorityId
        it.contentType = contentType
        it.key = key ?: ByteArray(0)
        it.size = size
        it.thumbnail = thumbnail ?: ByteArray(0)
        it.digest = digest ?: ByteArray(0)
        it.fileName = fileName ?: ""
        it.flags = flags
        it.width = width
        it.height = height
        it.status = status
    }
}




fun MessageModel.attachment(): Attachment? =
    wcdb.attachment.getAllObjects(
        DBAttachmentModel.messageId.eq(id),
        DBAttachmentModel.databaseId.order(Order.Asc),
    ).firstOrNull()?.toAttachment()

fun MessageModel.mentions(): List<Mention> =
    wcdb.mention.getAllObjects(
        DBMentionModel.messageId.eq(id),
        DBMentionModel.databaseId.order(Order.Asc),
    ).map { it.toMention() }

fun MessageModel.reactions(): List<Reaction> =
    wcdb.reaction.getAllObjects(
        DBReactionModel.messageId.eq(id),
        DBReactionModel.databaseId.order(Order.Asc),
    ).map { it.toReaction() }

fun MessageModel.sharedContacts(): List<SharedContact> =
    wcdb.sharedContact.getAllObjects(
        DBSharedContactModel.messageId.eq(id),
        DBSharedContactModel.databaseId.order(Order.Asc),
    ).map { model ->
        val phones = wcdb.sharedContactPhone.getAllObjects(
            DBSharedContactPhoneModel.sharedContactDatabaseId.eq(model.databaseId),
            DBSharedContactPhoneModel.databaseId.order(Order.Asc),
        ).map { it.toSharedContactPhone() }
        SharedContact(model.toSharedContactName(), phones, null, null, null, null)
    }

fun MessageModel.translateData(): TranslateData? =
    wcdb.translate.getFirstObject(
        DBTranslateModel.messageId.eq(id),
        DBTranslateModel.databaseId.order(Order.Asc),
    )?.toTranslateData()

fun MessageModel.speechToTextData(): SpeechToTextData? =
    wcdb.speechToText.getFirstObject(
        DBSpeechToTextModel.messageId.eq(id),
        DBSpeechToTextModel.databaseId.order(Order.Asc),
    )?.toSpeechToTextData()

fun MessageModel.screenShot(): ScreenShot? {
    return screenShotJson?.takeIf { it.isNotEmpty() }?.let {
        runCatching {
            globalServices.gson.fromJson(it, ScreenShot::class.java)
        }.onFailure { e ->
            L.e { "parse screenShot error: ${e.stackTraceToString()}" }
        }.getOrNull()
    }
}

fun MessageModel.quote(): Quote? = quoteDatabaseId?.let { qId ->
    wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))?.let { qm ->
        val attachments = wcdb.attachment
            .getAllObjects(
                DBAttachmentModel.quoteModelDatabaseId.eq(qId),
                DBAttachmentModel.databaseId.order(Order.Asc),
            )
            .map { it.toQuotedAttachment() }
        Quote(
            id = qm.id,
            author = qm.author,
            text = qm.text,
            attachments = attachments.ifEmpty { null }
        )
    }
}

fun ForwardModel.attachments(): List<Attachment> =
    wcdb.attachment.getAllObjects(
        DBAttachmentModel.forwardModelDatabaseId.eq(databaseId),
        DBAttachmentModel.databaseId.order(Order.Asc),
    ).map { it.toAttachment() }

fun ForwardModel.mentions(): List<Mention> =
    wcdb.mention.getAllObjects(
        DBMentionModel.forwardModelDatabaseId.eq(databaseId),
        DBMentionModel.databaseId.order(Order.Asc),
    ).map { it.toMention() }

fun ForwardModel.forwards(): List<Forward> {
    return wcdb.forward.getAllObjects(
        DBForwardModel.parentForwardModelDatabaseId.eq(databaseId),
        DBForwardModel.databaseId.order(Order.Asc),
    ).map { fm ->
        val fwdAttachments = fm.attachments()
        val fwdForwards = fm.forwards()
        val fwdMentions = fm.mentions()
        Forward(
            id = fm.id,
            type = fm.type,
            isFromGroup = fm.isFromGroup,
            author = fm.author,
            text = fm.text,
            attachments = fwdAttachments,
            forwards = fwdForwards,
            mentions = fwdMentions,
            serverTimestamp = fm.serverTimestamp
        )
    }
}

fun MessageModel.forwardContext(): ForwardContext? = forwardContextDatabaseId?.let { fcId ->
    wcdb.forwardContext.getFirstObject(DBForwardContextModel.databaseId.eq(fcId))?.let { fc ->
        val forwards = wcdb.forward.getAllObjects(
            DBForwardModel.forwardContextDatabaseId.eq(fcId)
                .and(DBForwardModel.parentForwardModelDatabaseId.isNull),
            DBForwardModel.databaseId.order(Order.Asc),
        ).map { fm ->
            val fwdAttachments = fm.attachments()
            val fwdForwards = fm.forwards()
            val fwdMentions = fm.mentions()
            Forward(
                id = fm.id,
                type = fm.type,
                isFromGroup = fm.isFromGroup,
                author = fm.author,
                text = fm.text,
                attachments = fwdAttachments,
                forwards = fwdForwards,
                mentions = fwdMentions,
                serverTimestamp = fm.serverTimestamp
            )
        }
        ForwardContext(forwards, fc.isFromGroup)
    }
}






fun WCDB.putMessageIfNotExists(message: Message, roomReadPosition: Long = 0L): Boolean {
    
    
    
    
    var inserted = false
    this.db.runTransaction {
        val existing = this.message.getFirstObject(DBMessageModel.id.eq(message.id))
        if (existing == null) {
            putMessage(message, roomReadPosition)
            inserted = true
        } else {
            L.i { "[Message] message existing:${message.timeStamp}" }
        }
        true 
    }
    return inserted
}

private fun WCDB.putMessage(message: Message, roomReadPosition: Long = 0L) {
    when (message) {
        is TextMessage -> putTextMessage(message, roomReadPosition)
        is NotifyMessage -> putNotifyMessage(message, roomReadPosition)
        else -> throw IllegalArgumentException("Unknown message type ${message::class}")
    }
}

private fun WCDB.putTextMessage(message: TextMessage, roomReadPosition: Long = 0L) {
    val messageModel = insertChildrenAndBuildMessageModel(message, roomReadPosition)
    this.message.insertObject(messageModel)
}






fun WCDB.insertChildrenAndBuildMessageModel(message: TextMessage, roomReadPosition: Long = 0L): MessageModel {
    val quoteDatabaseId = message.quote?.let { quote ->
        val quoteModel = QuoteModel().apply {
            id = quote.id
            author = quote.author
            text = quote.text
        }
        this.quote.insertObject(quoteModel)

        quote.attachments?.forEach { qa ->
            val attachmentModel = AttachmentModel().apply {
                quoteModelDatabaseId = quoteModel.databaseId.toLong() 
                contentType = qa.contentType
                fileName = qa.fileName
                flags = qa.flags
                
                
                val bytes = qa.thumbnail?.thumbnail?.takeIf { it.isNotEmpty() }
                thumbnail = bytes
                size = bytes?.size ?: 0 
                width = qa.thumbnail?.width ?: 0
                height = qa.thumbnail?.height ?: 0
                status = qa.thumbnail?.status ?: AttachmentStatus.SUCCESS.code
            }
            this.attachment.insertObject(attachmentModel)
        }

        quoteModel.databaseId.toLong()
    }

    val forwardContextDatabaseId = message.forwardContext?.let { fc ->
        val fcModel = ForwardContextModel().apply {
            isFromGroup = fc.isFromGroup
        }
        this.forwardContext.insertObject(fcModel)

        fc.forwards?.forEach { fwd ->
            insertForward(fwd, forwardContextDatabaseId = fcModel.databaseId.toLong(), parentForwardModelDatabaseId = null)
        }
        fcModel.databaseId.toLong()
    }

    message.attachments?.forEach { attachment ->
        val attachmentModel = attachment.toAttachmentModel(message.id)
        this.attachment.insertObject(attachmentModel)
    }

    message.mentions?.forEach { mention ->
        val mentionModel = MentionModel().apply {
            messageId = message.id
            forwardModelDatabaseId = null
            start = mention.start
            length = mention.length
            uid = mention.uid
            type = mention.type
        }
        this.mention.insertObject(mentionModel)
    }

    message.reactions?.forEach { reaction ->
        val reactionModel = ReactionModel().apply {
            messageId = message.id
            emoji = reaction.emoji
            uid = reaction.uid
            timeStamp = reaction.originTimestamp
        }
        this.reaction.insertObject(reactionModel)
    }

    message.sharedContact?.forEach { sc ->
        val scModel = SharedContactModel().apply {
            messageId = message.id
            givenName = sc.name?.givenName
            familyName = sc.name?.familyName
            namePrefix = sc.name?.prefix
            nameSuffix = sc.name?.suffix
            middleName = sc.name?.middleName
            displayName = sc.name?.displayName
        }
        this.sharedContact.insertObject(scModel)

        sc.phone?.forEach { phone ->
            val phoneModel = SharedContactPhoneModel().apply {
                sharedContactDatabaseId = scModel.databaseId.toLong()
                phoneNumber = phone.value
                phoneNumberType = phone.type
                phoneNumberLabel = phone.label
            }
            this.sharedContactPhone.insertObject(phoneModel)
        }
    }

    message.translateData?.let { td ->
        val translateModel = TranslateModel().apply {
            messageId = message.id
            translateStatus = td.translateStatus.status
            translatedContentCN = td.translatedContentCN
            translatedContentEN = td.translatedContentEN
        }
        this.translate.insertObject(translateModel)
    }

    message.speechToTextData?.let { td ->
        val speechToTextModel = SpeechToTextModel().apply {
            messageId = message.id
            convertStatus = td.convertStatus.status
            speechToTextContent = td.speechToTextContent
        }
        this.speechToText.insertObject(speechToTextModel)
    }
    val messageType = when {
        message.isUnsupported -> MessageModel.TYPE_UNSUPPORTED
        message.isAttachmentMessage() -> MessageModel.TYPE_ATTACHMENT
        else -> MessageModel.TYPE_TEXT
    }
    
    
    val calculatedReadTime = when {
        message.fromWho.id == globalServices.myId -> message.systemShowTimestamp
        roomReadPosition > 0 && message.systemShowTimestamp <= roomReadPosition -> roomReadPosition
        else -> 0L
    }
    val messageModel = MessageModel().apply {
        id = message.id
        fromWho = message.fromWho.id
        roomId = message.forWhat.id
        roomType = message.forWhat.typeValue
        systemShowTimestamp = message.systemShowTimestamp
        timeStamp = message.timeStamp
        receivedTimeStamp = message.receivedTimeStamp
        sendType = message.sendType
        expiresInSeconds = message.expiresInSeconds
        notifySequenceId = message.notifySequenceId
        sequenceId = message.sequenceId
        mode = message.mode
        atPersons = message.atPersons
        messageText = message.text ?: ""
        type = messageType
        playStatus = message.playStatus
        receiverIds = message.receiverIds
        criticalAlertType = message.criticalAlertType
        readTime = calculatedReadTime
        this.quoteDatabaseId = quoteDatabaseId
        this.forwardContextDatabaseId = forwardContextDatabaseId
        this.screenShotJson = message.screenShot?.let { globalServices.gson.toJson(it) }
    }
    return messageModel
}

private fun WCDB.putNotifyMessage(message: NotifyMessage, roomReadPosition: Long = 0L) {
    
    val calculatedReadTime = if (roomReadPosition > 0 && message.systemShowTimestamp <= roomReadPosition) {
        roomReadPosition
    } else {
        0L
    }
    val messageModel = MessageModel().apply {
        id = message.id
        fromWho = message.fromWho.id
        roomId = message.forWhat.id
        roomType = message.forWhat.typeValue
        systemShowTimestamp = message.systemShowTimestamp
        timeStamp = message.timeStamp
        receivedTimeStamp = message.receivedTimeStamp
        sendType = message.sendType
        expiresInSeconds = message.expiresInSeconds
        notifySequenceId = message.notifySequenceId
        sequenceId = message.sequenceId
        mode = message.mode
        messageText = message.notifyContent
        type = 2 
        readTime = calculatedReadTime
    }

    this.message.insertObject(messageModel)
}

fun WCDB.insertForward(
    forward: Forward,
    forwardContextDatabaseId: Long? = null,
    parentForwardModelDatabaseId: Long? = null
) {
    val forwardModel = ForwardModel().apply {
        id = forward.id
        type = forward.type
        isFromGroup = forward.isFromGroup
        author = forward.author
        text = forward.text ?: ""
        serverTimestamp = forward.serverTimestamp
        this.parentForwardModelDatabaseId = parentForwardModelDatabaseId
        this.forwardContextDatabaseId = forwardContextDatabaseId
    }
    this.forward.insertObject(forwardModel)

    forward.attachments?.forEach { attachment ->
        val attachmentModel = attachment.toAttachmentModel(forwardModel.databaseId.toLong())
        attachmentModel.forwardModelDatabaseId = forwardModel.databaseId.toLong()
        this.attachment.insertObject(attachmentModel)
    }

    forward.mentions?.forEach { mention ->
        val mentionModel = MentionModel().apply {
            forwardModelDatabaseId = forwardModel.databaseId.toLong()
            start = mention.start
            length = mention.length
            uid = mention.uid
            type = mention.type
        }
        this.mention.insertObject(mentionModel)
    }

    forward.forwards?.forEach { subForward ->
        insertForward(subForward, forwardContextDatabaseId, parentForwardModelDatabaseId = forwardModel.databaseId.toLong())
    }
}


fun MessageModel.delete() {
    deleteRelatedDataForMessage()
    deletePendingMessagesTargetingThis()
    wcdb.message.deleteObjects(DBMessageModel.databaseId.eq(databaseId))
    RoomChangeTracker.trackRoom(roomId ?: "", RoomChangeType.MESSAGE)
}






private fun MessageModel.deletePendingMessagesTargetingThis() {
    try {
        wcdb.pendingMessageNew.deleteObjects(DBPendingMessageModelNew.originalMessageTimeStamp.eq(timeStamp))
    } catch (e: Exception) {
        L.e { "[Message] delete pending rows failed timestamp=$timeStamp: ${e.stackTraceToString()}" }
    }
}









fun messageCount(condition: Expression): Long =
    wcdb.message.getValue(DBMessageModel.databaseId.count(), condition)?.long ?: 0L








@WorkerThread
fun maxMessageDatabaseId(roomId: String): Int =
    wcdb.message.getValue(DBMessageModel.databaseId.max(), DBMessageModel.roomId.eq(roomId))?.int ?: 0









suspend fun deleteMessagesPaged(
    condition: Expression,
    pageSize: Long = 100,
    commitAllowed: (() -> Boolean)? = null,
) = withContext(Dispatchers.IO) {
    while (true) {
        if (commitAllowed?.invoke() == false) return@withContext
        val page = wcdb.message.getAllObjects(condition, null, pageSize)
        if (page.isEmpty()) break
        page.forEach {
            if (commitAllowed?.invoke() == false) return@withContext
            it.delete()
        }
        if (page.size < pageSize) break
        delay(100)
    }
}













suspend fun forEachMessagePaged(
    condition: Expression,
    pageSize: Long = 200,
    action: (MessageModel) -> Unit
) = withContext(Dispatchers.IO) {
    var cursorId = Long.MIN_VALUE
    while (true) {
        val page = wcdb.message.prepareSelect()
            .select(*DBMessageModel.allBindingFields())
            .where(condition.and(DBMessageModel.databaseId.gt(cursorId)))
            .orderBy(DBMessageModel.databaseId.order(Order.Asc))
            .limit(pageSize)
            .allObjects()
        if (page.isEmpty()) break
        page.forEach(action)
        cursorId = page.last().databaseId.toLong()
        if (page.size < pageSize) break
        yield()
    }
}







fun MessageModel.deleteRelatedDataForMessage() {
    try {
        deleteOwnAttachmentFiles()
        wcdb.attachment.deleteObjects(DBAttachmentModel.messageId.eq(id))
        wcdb.mention.deleteObjects(DBMentionModel.messageId.eq(id))
        wcdb.reaction.deleteObjects(DBReactionModel.messageId.eq(id))

        val sharedContacts = wcdb.sharedContact.getAllObjects(DBSharedContactModel.messageId.eq(id))
        sharedContacts.forEach { sc ->
            wcdb.sharedContactPhone.deleteObjects(DBSharedContactPhoneModel.sharedContactDatabaseId.eq(sc.databaseId))
        }
        wcdb.sharedContact.deleteObjects(DBSharedContactModel.messageId.eq(id))

        forwardContextDatabaseId?.let { fcId ->
            val forwardModels = wcdb.forward.getAllObjects(DBForwardModel.forwardContextDatabaseId.eq(fcId))
            forwardModels.forEach { fm ->
                fm.deleteForwardRelatedData()
            }
            wcdb.forward.deleteObjects(DBForwardModel.forwardContextDatabaseId.eq(fcId))
            wcdb.forwardContext.deleteObjects(DBForwardContextModel.databaseId.eq(fcId))
        }

        quoteDatabaseId?.let { qId ->
            wcdb.attachment.deleteObjects(DBAttachmentModel.quoteModelDatabaseId.eq(qId))
            wcdb.quote.deleteObjects(DBQuoteModel.databaseId.eq(qId))
        }

        wcdb.translate.deleteObjects(DBTranslateModel.messageId.eq(id))
        wcdb.speechToText.deleteObjects(DBSpeechToTextModel.messageId.eq(id))
    } catch (e: Exception) {
        L.e { "deleteRelatedDataForMessage Error:${e.stackTraceToString()}" }
    }
}






private fun MessageModel.deleteOwnAttachmentFiles() {
    val rowLocalIds = wcdb.attachment
        .getOneColumnString(DBAttachmentModel.localId, DBAttachmentModel.messageId.eq(id))
    messageAttachmentDirectoryKeys(id, rowLocalIds, LegacyAttachmentAddresses.isWindowOpen)
        .forEach { FileUtil.deleteMessageFile(it) }
}








internal fun messageAttachmentDirectoryKeys(
    messageId: String,
    rowLocalIds: List<String?>,
    legacyWindowOpen: Boolean
): List<String> {
    val keys = rowLocalIds.mapNotNull { it?.takeIf { localId -> localId.isNotEmpty() } }
    return if (legacyWindowOpen) (keys + messageId).distinct() else keys.distinct()
}










internal fun forwardAttachmentDirectoryKeys(attachments: List<Attachment>): List<String> =
    attachments.mapNotNull { it.localId.takeIf { localId -> localId.isNotEmpty() } }

private fun ForwardModel.deleteForwardRelatedData() {
    val attachments = this.attachments()
    forwardAttachmentDirectoryKeys(attachments).forEach { FileUtil.deleteMessageFile(it) }
    val legacyAuthorityIds = attachments.asSequence().map { it.authorityId }.filter { it != 0L }.distinct().toList()
    wcdb.attachment.deleteObjects(DBAttachmentModel.forwardModelDatabaseId.eq(databaseId))
    wcdb.mention.deleteObjects(DBMentionModel.forwardModelDatabaseId.eq(databaseId))
    
    
    
    
    legacyAuthorityIds.forEach { reclaimLegacyAttachmentDirectory(it) }

    val subForwards = wcdb.forward.getAllObjects(DBForwardModel.parentForwardModelDatabaseId.eq(databaseId))
    subForwards.forEach { sf ->
        sf.deleteForwardRelatedData()
    }
    wcdb.forward.deleteObjects(DBForwardModel.parentForwardModelDatabaseId.eq(databaseId))

    wcdb.forward.deleteObjects(DBForwardModel.databaseId.eq(databaseId))
}






private fun reclaimLegacyAttachmentDirectory(authorityId: Long) {
    try {
        if (wcdb.attachment.getFirstObject(DBAttachmentModel.authorityId.eq(authorityId)) != null) return
        val key = authorityId.toString()
        if (wcdb.message.getFirstObject(DBMessageModel.id.eq(key)) != null) return
        FileUtil.deleteMessageFile(key)
    } catch (e: Exception) {
        L.e { "[Attachment] reclaim legacy dir failed authorityId=$authorityId: ${e.stackTraceToString()}" }
    }
}









fun MessageModel.convertToConfidentialPlaceholder() {
    if (type == MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER) return
    deleteRelatedDataForMessage()
    type = MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER
    messageText = ""
    quoteDatabaseId = null
    forwardContextDatabaseId = null
    wcdb.message.updateRow(
        arrayOf(
            Value(type),
            Value(messageText),
            quoteDatabaseId.dbValue(),
            forwardContextDatabaseId.dbValue()
        ),
        arrayOf(
            DBMessageModel.type,
            DBMessageModel.messageText,
            DBMessageModel.quoteDatabaseId,
            DBMessageModel.forwardContextDatabaseId
        ),
        DBMessageModel.databaseId.eq(databaseId)
    )
    L.i { "[Confidential] convertToConfidentialPlaceholder done: id=$id, databaseId=$databaseId" }
    RoomChangeTracker.trackRoom(roomId ?: "", RoomChangeType.MESSAGE)
}

fun MessageModel.isAttachmentMessage(): Boolean {
    return type == 1
}

fun MessageModel.isTextMessage(): Boolean {
    return type == 0
}

fun MessageModel.isNotifyMessage(): Boolean {
    return type == 2
}

fun MessageModel.isUnsupportedMessage(): Boolean {
    return type == MessageModel.TYPE_UNSUPPORTED
}

fun MessageModel.previewContent(): String {
    val from = fromWho ?: ""
    val senderName = if (roomType == 1) (wcdb.getContactorFromAllTable(from)?.getDisplayNameForUI()
        ?: from) + ": " else ""
    
    return if (isUnsupportedMessage()) {
        senderName + ResUtils.getString(R.string.chat_message_unsupported)
    } else if (mode == 1) {
        senderName + ResUtils.getString(R.string.chat_message_confidential_message)
    } else {
        if (isAttachmentMessage()) {
            val attachment = attachment()
            if (attachment?.isAnimatedImage() == true) {
                senderName + ResUtils.getString(R.string.chat_message_gif)
            } else if (attachment?.isImage() == true) {
                senderName + ResUtils.getString(R.string.chat_message_image)
            } else if (isWhitelistedVideoType(attachment?.contentType)) {
                senderName + ResUtils.getString(R.string.chat_message_video)
            } else if (attachment?.isAudioMessage() == true || attachment?.isAudioFile() == true) {
                senderName + ResUtils.getString(R.string.chat_message_audio)
            } else {
                senderName + ResUtils.getString(R.string.chat_message_attachment)
            }
        } else if (isTextMessage()) {
            if (sharedContacts().isNotEmpty()) {
                senderName + ResUtils.getString(R.string.chat_message_contact_card)
            } else if (forwardContext() != null) {
                senderName + "[" + ResUtils.getString(R.string.chat_history) + "]"
            } else {
                
                senderName + messageText.orEmpty()
            }
        } else if (isNotifyMessage()) {
            globalServices.gson.fromJson(messageText, JsonObject::class.java)?.get("showContent")?.asString.orEmpty()
        } else {
            ""
        }
    }
}

fun RoomModel.updateRoomUnreadState(readPosition: Long = this.readPosition) {

    if (readPosition < this.readPosition) return

    val unreadMessagesIds = wcdb.message.getOneColumnString(
        DBMessageModel.id,
        DBMessageModel.roomId.eq(roomId)
            .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
            .and(DBMessageModel.fromWho.upper().notEq(globalServices.myId.uppercase()))
            .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))
    )

    val unreadMessageNum = unreadMessagesIds.size

    L.d { "update room unread state:$roomName $unreadMessageNum" }

    val mentionType = if (unreadMessageNum == 0) {
        MENTIONS_TYPE_NONE
    } else {
        wcdb.mention.getOneColumnString(
            DBMentionModel.uid, DBMentionModel.messageId.`in`(unreadMessagesIds)
                .and(DBMentionModel.uid.`in`(globalServices.myId, MENTIONS_ALL_ID))
        ).let { mentionsList ->
            when {
                mentionsList.any { it == globalServices.myId } -> 2
                mentionsList.any { it == MENTIONS_ALL_ID } -> 1
                else -> -1
            }
        }
    }

    
    val newCriticalAlertType = if (this.criticalAlertType == difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT) {
        val hasUnreadCriticalAlert = wcdb.message.getValue(
            DBMessageModel.criticalAlertType,
            DBMessageModel.roomId.eq(roomId)
                .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
                .and(DBMessageModel.criticalAlertType.eq(difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT))
        )?.int ?: 0

        if (hasUnreadCriticalAlert > 0) {
            difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
        } else {
            difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
        }
    } else {
        this.criticalAlertType
    }

    wcdb.room.updateRow(
        arrayOf(Value(readPosition), Value(unreadMessageNum), Value(mentionType), Value(newCriticalAlertType)),
        arrayOf(DBRoomModel.readPosition, DBRoomModel.unreadMessageNum, DBRoomModel.mentionType, DBRoomModel.criticalAlertType),
        DBRoomModel.roomId.eq(roomId)
    )
}






fun RoomModel.resetRoomUnreadState() {
    if (unreadMessageNum != 0) {
        L.d { "reset room unread state:$roomName" }
        wcdb.room.updateRow(
            arrayOf(Value(0), Value(MENTIONS_TYPE_NONE), Value(difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE)),
            arrayOf(DBRoomModel.unreadMessageNum, DBRoomModel.mentionType, DBRoomModel.criticalAlertType),
            DBRoomModel.roomId.eq(roomId)
        )
    }
}




fun Any?.dbValue() = Value(this)

fun RoomModel.updateRoomNameAndAvatar() {
    if (this.roomType == 0) {
        val contactorModel = wcdb.getContactorFromAllTable(this.roomId)
        if (contactorModel != null) {
            val roomName = contactorModel.getDisplayNameForUI()
            val avatar = contactorModel.avatar
            wcdb.room.updateRow(
                arrayOf(
                    roomName.dbValue(),
                    avatar.dbValue(),
                ),
                arrayOf(
                    DBRoomModel.roomName,
                    DBRoomModel.roomAvatarJson
                ),
                DBRoomModel.roomId.eq(this.roomId)
            )
        }
    } else {
        val groupModel = wcdb.group.getFirstObject(DBGroupModel.gid.eq(this.roomId))
        if (groupModel != null) {
            wcdb.room.updateRow(
                arrayOf(
                    groupModel.name.dbValue(),
                    groupModel.avatar.dbValue()
                ),
                arrayOf(DBRoomModel.roomName, DBRoomModel.roomAvatarJson),
                DBRoomModel.roomId.eq(this.roomId)
            )
        }
    }
}












fun ContactorModel.covertToGroupMemberContactorModel(): GroupMemberContactorModel {
    if (this.avatar != null) {
        L.w { "[WCDBExtensions] covertToGroupMemberContactorModel id:${this.id} avatar will be lost" }
    }
    val memberContactor = GroupMemberContactorModel()
    memberContactor.gid = ""
    memberContactor.id = this.id
    memberContactor.displayName = this.getDisplayNameWithoutRemarkForUI()
    memberContactor.remark = this.remark
    memberContactor.remarkAvatar = this.remarkAvatar
    return memberContactor
}


fun GroupMemberContactorModel.convertToContactorModel(): ContactorModel {
    checkThread()
    val contactor = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(this.id)) ?: ContactorModel()
    contactor.updateFrom(this)
    return contactor
}


fun List<GroupMemberContactorModel>.convertToContactorModels(): List<ContactorModel> {
    checkThread()
    val contactorIds = this.map { it.id }
    val contactorsFromDb = wcdb.contactor.getAllObjects(DBContactorModel.id.`in`(contactorIds))

    return this.map { member ->
        val contactor = contactorsFromDb.find { it.id == member.id } ?: ContactorModel()
        contactor.updateFrom(member)
        contactor
    }
}

private fun ContactorModel.updateFrom(member: GroupMemberContactorModel) {
    this.id = member.id ?: ""
    this.groupMemberContactor = member
}

private fun convertAmplitudes(amplitudesString: String?) = runCatching {
    if (amplitudesString.isNullOrEmpty()) return@runCatching null
    globalServices.gson.fromJson(amplitudesString, Array<Float>::class.java)?.toList()
}.onFailure {
    L.e { "parse amplitudes error:" + it.stackTraceToString() }
}.getOrNull()

fun WCDB.getReadInfoList(roomId: String): List<ReadInfoModel> {
    return wcdb.readInfo.getAllObjects(DBReadInfoModel.roomId.eq(roomId)).toList()
}










fun WCDB.isGroupMember(gid: String, userId: String): Boolean {
    return groupMemberContactor.getFirstObject(
        DBGroupMemberContactorModel.gid.eq(gid)
            .and(DBGroupMemberContactorModel.id.eq(userId))
    ) != null
}

fun WCDB.getGroupMemberCount(gid: String): Int {
    return groupMemberContactor.getValue(
        DBGroupMemberContactorModel.databaseId.count(),
        DBGroupMemberContactorModel.gid.eq(gid)
    )?.int ?: 0
}






fun WCDB.updateGroupMembersReadPosition(gid: String, readPosition: Long) {
    val memberIds = groupMemberContactor.getOneColumnString(
        DBGroupMemberContactorModel.id,
        DBGroupMemberContactorModel.gid.eq(gid)
            .and(DBGroupMemberContactorModel.id.notEq(globalServices.myId))
    )

    if (memberIds.isEmpty()) return

    val readInfoModels = memberIds.map { uid ->
        ReadInfoModel().apply {
            this.roomId = gid
            this.uid = uid
            this.readPosition = readPosition
        }
    }

    readInfo.insertOrReplaceObjects(readInfoModels)
}

fun clearInvalidGroupMembers() {
    
    wcdb.group.deleteObjects(DBGroupModel.status.notEq(0))
    val allGroupIds = wcdb.group.allObjects.map { it.gid }
    if (allGroupIds.isNotEmpty()) {
        wcdb.groupMemberContactor.deleteObjects(
            DBGroupMemberContactorModel.gid.notEq("")
                .and(DBGroupMemberContactorModel.gid.notIn(*allGroupIds.toTypedArray()))
        )
    }
}



data class PaginatedMessageSearchResult(
    val messages: List<MessageModel>,
    val hasMore: Boolean,
    val lastTimestamp: Long?
)






fun WCDB.loadPaginatedMessagesByKeyword(
    keyword: String,
    limit: Int = 20,
    cursorTimestamp: Long? = null,
    conversationId: String? = null  
): PaginatedMessageSearchResult {
    var baseCondition = DBMessageModel.messageText.upper().like("%${keyword.uppercase()}%")
        .and(DBMessageModel.type.`in`(MessageModel.TYPE_TEXT, MessageModel.TYPE_ATTACHMENT))
        .and(DBMessageModel.mode.notEq(1))          

    if (conversationId != null) {
        baseCondition = baseCondition.and(DBMessageModel.roomId.eq(conversationId))
    }

    if (cursorTimestamp != null) {
        baseCondition = baseCondition.and(DBMessageModel.systemShowTimestamp.lt(cursorTimestamp))
    }

    
    val fetched = message.getAllObjects(
        baseCondition,
        DBMessageModel.systemShowTimestamp.order(Order.Desc),
        (limit + 1).toLong()
    )

    val hasMore = fetched.size > limit
    val resultMessages = if (hasMore) fetched.dropLast(1) else fetched

    return PaginatedMessageSearchResult(
        messages = resultMessages,
        hasMore = hasMore,
        lastTimestamp = resultMessages.lastOrNull()?.systemShowTimestamp
    )
}

