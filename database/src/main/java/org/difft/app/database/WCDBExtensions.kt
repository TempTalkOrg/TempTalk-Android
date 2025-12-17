package org.difft.app.database

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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.core.Table
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Card
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.CardModel
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBCardModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
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

fun WCDB.getContactorFromAllTable(id: String): ContactorModel? {
    return contactor.getFirstObject(DBContactorModel.id.eq(id))
        ?: groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(id))
            ?.convertToContactorModel()
}

fun WCDB.getContactorsFromAllTable(ids: List<String>): List<ContactorModel> {
    val foundContactors = contactor.getAllObjects(DBContactorModel.id.`in`(ids))
    val otherIds = ids.toList() - foundContactors.map { it.id }.toSet()

    val othersFromGroupMember =
        groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.id.`in`(otherIds))
            .map { it.convertToContactorModel() }
    return (foundContactors + othersFromGroupMember).distinctBy { it.id }
}

val GroupModel.members: List<GroupMemberContactorModel>
    get() = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))

fun GroupMemberContactorModel.group(wcdb: WCDB): GroupModel? {
    return wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))
}

fun Table<GroupModel>.searchByNameAndGroupMembers(key: String): List<GroupModel> {
    // 1. Query groups by name (case-insensitive search)
    // Use LOWER in your schema or conditions for case-insensitive if needed.
    val groupsByName = getAllObjects(
        DBGroupModel.name.like("%$key%").and(DBGroupModel.status.eq(0))
    )
    // 2. Query group member contactors who match the key in any of the specified fields
    val contactors = wcdb.groupMemberContactor.search(key)
    // 3. Extract distinct GIDs from these contactors
    val groupGids = contactors.map { it.gid }.filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()
    // 4. Query groups by these GIDs
    val groupsByMember = if (groupGids.isNotEmpty()) {
        getAllObjects(DBGroupModel.gid.`in`(*groupGids.toTypedArray()).and(DBGroupModel.status.eq(0)))
    } else {
        emptyList()
    }
    // 5. Combine and deduplicate by `gid`
    return (groupsByName + groupsByMember).distinctBy { it.gid }
}

/**
 * Get the count of common groups between two users.
 * This method efficiently queries the database to find groups where both users are members.
 *
 * @param userId1 The first user's ID
 * @param userId2 The second user's ID
 * @return The count of active common groups
 */
fun WCDB.getCommonGroupsCount(userId1: String, userId2: String): Int {
    // First, get groups where the first user is a member
    val user1Groups = groupMemberContactor.getAllObjects(
        DBGroupMemberContactorModel.id.eq(userId1)
    ).map { it.gid }.distinct()
    if (user1Groups.isEmpty()) {
        return 0
    }

    // Then, get groups where the second user is a member and intersect with first user's groups
    val user2Groups = groupMemberContactor.getAllObjects(
        DBGroupMemberContactorModel.id.eq(userId2)
    ).map { it.gid }.distinct()

    val commonGroupIds = user1Groups.intersect(user2Groups.toSet())
    val commonGroupIdsClean = commonGroupIds.filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()
    L.i { "[getCommonGroupsCount] user1Groups:${user1Groups.size} user2Groups:${user2Groups.size} commonGroupIds:${commonGroupIds.size} commonGroupIdsClean:${commonGroupIdsClean.size}" }

    if (commonGroupIdsClean.isEmpty()) {
        return 0
    }

    // Finally, count only active groups
    return group.getAllObjects(
        DBGroupModel.gid.`in`(*commonGroupIdsClean.toTypedArray())
            .and(DBGroupModel.status.eq(0))
    ).size
}


fun MessageModel.convertToTextMessage(): TextMessage {
    // Retrieve all attachments for this message
    val attachmentModels = wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(id))
    val attachments = if (attachmentModels.isNotEmpty()) {
        attachmentModels.map { am ->
            Attachment(
                id = am.id,
                authorityId = am.authorityId,
                contentType = am.contentType,
                key = am.key,
                size = am.size,
                thumbnail = am.thumbnail,
                digest = am.digest,
                fileName = am.fileName,
                flags = am.flags,
                width = am.width,
                height = am.height,
                path = am.path,
                status = am.status,
                totalTime = am.totalTime,
                amplitudes = convertAmplitudes(am.amplitudes),
            )
        }
    } else {
        null
    }

    val card: Card? = card()
    val mentions: List<Mention>? = mentions().takeIf { it.isNotEmpty() }
    val reactions: List<Reaction>? = reactions().takeIf { it.isNotEmpty() }
    val forwardContext: ForwardContext? = forwardContext()
    val quote: Quote? = quote()
    val sharedContacts: List<SharedContact>? = sharedContacts().takeIf { it.isNotEmpty() }
    val translateData: TranslateData? = translateData()
    val speechToTextData: SpeechToTextData? = speechToTextData()

    // Construct the `For` objects.
    // Adjust the logic to match how you define and use `For` in your codebase.
    // For example:
    // For a user: For(fromWho, For.Type.USER)
    // For a room: For(roomId, if (roomType == 1) For.Type.GROUP else For.Type.ONE_TO_ONE)

    val fromWhoFor = For.Account(fromWho) // replace 0 with the correct type value for a user
    val forWhatFor = if (roomType == 0) For.Account(roomId) else For.Group(roomId)// if roomType == 1 is group, else one-to-one

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
        card = card,
        mentions = mentions,
        atPersons = atPersons,
        reactions = reactions,
        screenShot = null, // No screenshot data present in the given schema
        sharedContact = sharedContacts,
        readInfo = readInfo,
        translateData = translateData,
        speechToTextData = speechToTextData,
        receiverIds = receiverIds,
        criticalAlertType = criticalAlertType,
    )
}

fun AttachmentModel.toAttachment(): Attachment {
    return Attachment(
        id = id,
        authorityId = authorityId,
        contentType = contentType,
        key = key,
        size = size,
        thumbnail = thumbnail,
        digest = digest,
        fileName = fileName,
        flags = flags,
        width = width,
        height = height,
        path = path,
        status = status,
        totalTime = totalTime,
        amplitudes = convertAmplitudes(amplitudes),
    )
}

fun Attachment.toAttachmentModel(messageId: String): AttachmentModel {
    return AttachmentModel().also {
        it.id = id
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
        it.path = path
        it.status = status
    }
}

fun Attachment.toAttachmentModel(forwardDatabaseId: Long): AttachmentModel {
    return AttachmentModel().also {
        it.id = id
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
        it.path = path
        it.status = status
    }
}

fun MessageModel.attachment(): Attachment? {
    return wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(id))
        .firstOrNull()?.let {
            Attachment(
                id = it.id,
                authorityId = it.authorityId,
                contentType = it.contentType,
                key = it.key,
                size = it.size,
                thumbnail = it.thumbnail,
                digest = it.digest,
                fileName = it.fileName,
                flags = it.flags,
                width = it.width,
                height = it.height,
                path = it.path,
                status = it.status,
                totalTime = it.totalTime,
                amplitudes = convertAmplitudes(it.amplitudes),
            )
        }
}

fun MessageModel.card(): Card? = cardModelDatabaseId?.let {
    wcdb.card.getFirstObject(DBCardModel.databaseId.eq(it))?.let { cm ->
        Card(
            cardId = cm.cardId,
            appId = cm.appId,
            version = cm.version,
            creator = cm.creator,
            timestamp = cm.timestamp,
            content = cm.content,
            contentType = cm.contentType,
            type = cm.type,
            fixedWidth = cm.fixedWidth
        )
    }
}

fun MessageModel.mentions(): List<Mention> {
    return wcdb.mention.getAllObjects(DBMentionModel.messageId.eq(id)).map {
        Mention(
            start = it.start,
            length = it.length,
            uid = it.uid,
            type = it.type
        )
    }
}

fun MessageModel.reactions(): List<Reaction> {
    return wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(id)).map {
        Reaction(
            emoji = it.emoji,
            uid = it.uid,
            originTimestamp = it.timeStamp
        )
    }
}

fun MessageModel.sharedContacts(): List<SharedContact> {
    return wcdb.sharedContact.getAllObjects(DBSharedContactModel.messageId.eq(id)).map { model ->
        val name = SharedContactName(
            model.givenName,
            model.familyName,
            model.namePrefix,
            model.nameSuffix,
            model.middleName,
            model.displayName
        )
        val phones = wcdb.sharedContactPhone.getAllObjects(
            DBSharedContactPhoneModel.sharedContactDatabaseId.eq(model.databaseId)
        ).map {
            SharedContactPhone(
                value = it.phoneNumber,
                type = it.phoneNumberType,
                label = it.phoneNumberLabel
            )
        }
        SharedContact(name, phones, null, null, null, null)
    }
}

fun MessageModel.translateData(): TranslateData? {
    return wcdb.translate.getFirstObject(DBTranslateModel.messageId.eq(id))?.let {
        TranslateData(
            translateStatus = TranslateStatus.fromIntOrDefault(it.translateStatus),
            translatedContentCN = it.translatedContentCN,
            translatedContentEN = it.translatedContentEN
        )
    }
}

fun MessageModel.speechToTextData(): SpeechToTextData? {
    return wcdb.speechToText.getFirstObject(DBSpeechToTextModel.messageId.eq(id))?.let {
        SpeechToTextData(
            convertStatus = SpeechToTextStatus.fromIntOrDefault(it.convertStatus),
            speechToTextContent = it.speechToTextContent,
        )
    }
}

fun MessageModel.quote(): Quote? = quoteDatabaseId?.let { qId ->
    wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))?.let { qm ->
        Quote(
            id = qm.id,
            author = qm.author,
            text = qm.text,
            attachments = null
        )
    }
}

fun ForwardModel.attachments(): List<Attachment> {
    return wcdb.attachment.getAllObjects(DBAttachmentModel.forwardModelDatabaseId.eq(databaseId)).map {
        Attachment(
            id = it.id,
            authorityId = it.authorityId,
            contentType = it.contentType,
            key = it.key,
            size = it.size,
            thumbnail = it.thumbnail,
            digest = it.digest,
            fileName = it.fileName,
            flags = it.flags,
            width = it.width,
            height = it.height,
            path = it.path,
            status = it.status,
            totalTime = it.totalTime,
            amplitudes = convertAmplitudes(it.amplitudes),
        )
    }
}

fun ForwardModel.card(): Card? = cardModelDatabaseId?.let {
    wcdb.card.getFirstObject(DBCardModel.databaseId.eq(it))?.let { cm ->
        Card(
            cardId = cm.cardId,
            appId = cm.appId,
            version = cm.version,
            creator = cm.creator,
            timestamp = cm.timestamp,
            content = cm.content,
            contentType = cm.contentType,
            type = cm.type,
            fixedWidth = cm.fixedWidth
        )
    }
}

fun ForwardModel.mentions(): List<Mention> {
    return wcdb.mention.getAllObjects(DBMentionModel.forwardModelDatabaseId.eq(databaseId)).map {
        Mention(
            start = it.start,
            length = it.length,
            uid = it.uid,
            type = it.type
        )
    }
}

fun ForwardModel.forwards(): List<Forward> {
    return wcdb.forward.getAllObjects(DBForwardModel.parentForwardModelDatabaseId.eq(databaseId)).map { fm ->
        val fwdAttachments = fm.attachments()
        val fwdForwards = fm.forwards()
        val fwdCard = fm.card()
        val fwdMentions = fm.mentions()
        Forward(
            id = fm.id,
            type = fm.type,
            isFromGroup = fm.isFromGroup,
            author = fm.author,
            text = fm.text,
            attachments = fwdAttachments,
            forwards = fwdForwards,
            card = fwdCard,
            mentions = fwdMentions,
            serverTimestamp = fm.serverTimestamp
        )
    }
}

fun MessageModel.forwardContext(): ForwardContext? = forwardContextDatabaseId?.let { fcId ->
    wcdb.forwardContext.getFirstObject(DBForwardContextModel.databaseId.eq(fcId))?.let { fc ->
        val forwards = wcdb.forward.getAllObjects(
            DBForwardModel.forwardContextDatabaseId.eq(fcId)
                .and(DBForwardModel.parentForwardModelDatabaseId.isNull)
        ).map { fm ->
            val fwdAttachments = fm.attachments()
            val fwdForwards = fm.forwards()
            val fwdCard = fm.card()
            val fwdMentions = fm.mentions()
            Forward(
                id = fm.id,
                type = fm.type,
                isFromGroup = fm.isFromGroup,
                author = fm.author,
                text = fm.text,
                attachments = fwdAttachments,
                forwards = fwdForwards,
                card = fwdCard,
                mentions = fwdMentions,
                serverTimestamp = fm.serverTimestamp
            )
        }
        ForwardContext(forwards, fc.isFromGroup)
    }
}

// ---------------------------
// Extension functions on WCDB for CRUD Operations
// ---------------------------

fun WCDB.putMessageIfNotExists(message: Message) {
    val existing = this.message.getFirstObject(DBMessageModel.id.eq(message.id))
    if (existing == null) {
        putMessage(message)
    }
}

fun WCDB.putOrUpdateMessage(message: Message) {
    val existing = this.message.getFirstObject(DBMessageModel.id.eq(message.id))
    if (existing == null) {
        putMessage(message)
    } else {
        existing.deleteRelatedDataForMessage()
        this.message.deleteObjects(DBMessageModel.id.eq(message.id))
        putMessage(message)
    }
}

private fun WCDB.putMessage(message: Message) {
    when (message) {
        is TextMessage -> putTextMessage(message)
        is NotifyMessage -> putNotifyMessage(message)
        else -> throw IllegalArgumentException("Unknown message type ${message::class}")
    }
    RoomChangeTracker.trackRoom(message.forWhat.id, RoomChangeType.MESSAGE)
}

private fun WCDB.putTextMessage(message: TextMessage) {
    val messageModel = convertToMessageModel(message)
    this.message.insertObject(messageModel)
    L.d { "observerMessagesChanges: Inserted message: ${messageModel.messageText}, time stamp is ${System.currentTimeMillis()}" }
}

fun WCDB.convertToMessageModel(message: TextMessage): MessageModel {
    val cardModelDatabaseId = message.card?.let { card ->
        val uniqueId = card.uniqueId ?: return@let null
        val cardModel = CardModel().apply {
            this.uniqueId = uniqueId
            cardId = card.cardId ?: ""
            appId = card.appId ?: ""
            version = card.version
            creator = card.creator
            timestamp = card.timestamp
            content = card.content
            contentType = card.contentType
            type = card.type
            fixedWidth = card.fixedWidth
        }
        this.card.insertObject(cardModel)
        cardModel.databaseId
    }

    val quoteDatabaseId = message.quote?.let { quote ->
        val quoteModel = QuoteModel().apply {
            id = quote.id
            author = quote.author
            text = quote.text
        }
        this.quote.insertObject(quoteModel)
        quoteModel.databaseId
    }

    val forwardContextDatabaseId = message.forwardContext?.let { fc ->
        val fcModel = ForwardContextModel().apply {
            isFromGroup = fc.isFromGroup
        }
        this.forwardContext.insertObject(fcModel)

        fc.forwards?.forEach { fwd ->
            insertForward(fwd, forwardContextDatabaseId = fcModel.databaseId, parentForwardModelDatabaseId = null)
        }
        fcModel.databaseId
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
                sharedContactDatabaseId = scModel.databaseId
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
    val messageType = if (message.isAttachmentMessage()) 1 else if (message.recall != null) 3 else 0
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
        readInfo = message.readInfo
        messageText = message.text ?: ""
        type = messageType
        playStatus = message.playStatus
        receiverIds = message.receiverIds
        criticalAlertType = message.criticalAlertType
        this.quoteDatabaseId = quoteDatabaseId
        this.forwardContextDatabaseId = forwardContextDatabaseId
        this.cardModelDatabaseId = cardModelDatabaseId
    }
    return messageModel
}

private fun WCDB.putNotifyMessage(message: NotifyMessage) {
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
        type = 2 // Notify
    }

    this.message.insertObject(messageModel)
}

fun WCDB.insertForward(
    forward: Forward,
    forwardContextDatabaseId: Long? = null,
    parentForwardModelDatabaseId: Long? = null
) {
    val cardModelDatabaseId = forward.card?.let { card ->
        val uniqueId = card.uniqueId ?: return@let null
        val cardModel = CardModel().apply {
            this.uniqueId = uniqueId
            cardId = card.cardId ?: ""
            appId = card.appId ?: ""
            version = card.version
            creator = card.creator
            timestamp = card.timestamp
            content = card.content
            contentType = card.contentType
            type = card.type
            fixedWidth = card.fixedWidth
        }
        this.card.insertObject(cardModel)
        cardModel.databaseId
    }

    val forwardModel = ForwardModel().apply {
        id = forward.id
        type = forward.type
        isFromGroup = forward.isFromGroup
        author = forward.author
        text = forward.text ?: ""
        serverTimestamp = forward.serverTimestamp
        this.cardModelDatabaseId = cardModelDatabaseId
        this.parentForwardModelDatabaseId = parentForwardModelDatabaseId
        this.forwardContextDatabaseId = forwardContextDatabaseId
    }
    this.forward.insertObject(forwardModel)

    forward.attachments?.forEach { attachment ->
        val attachmentModel = attachment.toAttachmentModel(forwardModel.databaseId)
        attachmentModel.forwardModelDatabaseId = forwardModel.databaseId
        this.attachment.insertObject(attachmentModel)
    }

    forward.mentions?.forEach { mention ->
        val mentionModel = MentionModel().apply {
            forwardModelDatabaseId = forwardModel.databaseId
            start = mention.start
            length = mention.length
            uid = mention.uid
            type = mention.type
        }
        this.mention.insertObject(mentionModel)
    }

    forward.forwards?.forEach { subForward ->
        insertForward(subForward, forwardContextDatabaseId, parentForwardModelDatabaseId = forwardModel.databaseId)
    }
}

fun deleteOldRecallMessages() {
    val oldRecallMessages = wcdb.message.getAllObjects(DBMessageModel.type.eq(3))
    oldRecallMessages.forEach {
        it.delete()
    }
}

fun MessageModel.delete() {
    deleteRelatedDataForMessage()
    wcdb.message.deleteObjects(DBMessageModel.databaseId.eq(databaseId))
    RoomChangeTracker.trackRoom(roomId, RoomChangeType.MESSAGE)
}

fun MessageModel.deleteRelatedDataForMessage() {
    try {
        FileUtil.deleteMessageFile(id)
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

        cardModelDatabaseId?.let {
            wcdb.card.deleteObjects(DBCardModel.databaseId.eq(it))
        }

        wcdb.pendingMessageNew.deleteObjects(DBPendingMessageModelNew.originalMessageTimeStamp.eq(this.timeStamp))
    } catch (e: Exception) {
        e.printStackTrace()
        L.e { "deleteRelatedDataForMessage Error:${e.stackTraceToString()}" }
    }
}

private fun ForwardModel.deleteForwardRelatedData() {
    this.attachments().firstOrNull()?.let {
        FileUtil.deleteMessageFile(it.authorityId.toString())
    }
    wcdb.attachment.deleteObjects(DBAttachmentModel.forwardModelDatabaseId.eq(databaseId))
    wcdb.mention.deleteObjects(DBMentionModel.forwardModelDatabaseId.eq(databaseId))

    val subForwards = wcdb.forward.getAllObjects(DBForwardModel.parentForwardModelDatabaseId.eq(databaseId))
    subForwards.forEach { sf ->
        sf.deleteForwardRelatedData()
    }
    wcdb.forward.deleteObjects(DBForwardModel.parentForwardModelDatabaseId.eq(databaseId))

    cardModelDatabaseId?.let { cardId ->
        wcdb.card.deleteObjects(DBCardModel.databaseId.eq(cardId))
    }

    wcdb.forward.deleteObjects(DBForwardModel.databaseId.eq(databaseId))
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

fun MessageModel.previewContent(): String {
    val senderName = if (roomType == 1) (wcdb.getContactorFromAllTable(fromWho)?.getDisplayNameForUI()
        ?: fromWho) + ": " else ""
    return if (mode == 1) {
        senderName + ResUtils.getString(R.string.chat_message_confidential_message)
    } else {
        if (isAttachmentMessage()) {
            val attachment = attachment()
            if (attachment?.isImage() == true) {
                senderName + ResUtils.getString(R.string.chat_message_image)
            } else if (attachment?.isVideo() == true) {
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
                senderName + messageText
            }
        } else if (isNotifyMessage()) {
            Gson().fromJson(messageText, JsonObject::class.java)?.get("showContent")?.asString.orEmpty()
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
            .and(DBMessageModel.type.notEq(2))
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

    // 如果当前有 critical alert 高亮，检查是否还有未读的 critical alert 消息
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

/**
 * Extension function for easy way of Value(this)
 */
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

/**
 * Converts [ContactorModel] into [GroupMemberContactorModel].
 */
fun ContactorModel.covertToGroupMemberContactorModel(): GroupMemberContactorModel {
    val memberContactor = GroupMemberContactorModel()
    memberContactor.gid = "" //special gid for temp friend contactor, that requested and not accept state
    memberContactor.id = this.id
    memberContactor.displayName = this.getDisplayNameForUI()
    memberContactor.remark = this.remark
    return memberContactor
}

//涉及耗时操作，请异步处理
fun GroupMemberContactorModel.convertToContactorModel(): ContactorModel {
    checkThread()
    val contactor = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(this.id)) ?: ContactorModel()
    contactor.updateFrom(this)
    return contactor
}

//涉及耗时操作，请异步处理
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
    this.id = member.id
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

/**
 * 获取群成员数量
 */
fun WCDB.getGroupMemberCount(gid: String): Int {
    return groupMemberContactor.getValue(
        DBGroupMemberContactorModel.databaseId.count(),
        DBGroupMemberContactorModel.gid.eq(gid)
    )?.int ?: 0
}

/**
 * 批量更新群成员的已读位置
 * @param gid 群组ID
 * @param readPosition 已读位置（时间戳）
 */
fun WCDB.updateGroupMembersReadPosition(gid: String, readPosition: Long) {
    // 获取所有群成员（排除自己）
    val memberIds = groupMemberContactor.getOneColumnString(
        DBGroupMemberContactorModel.id,
        DBGroupMemberContactorModel.gid.eq(gid)
            .and(DBGroupMemberContactorModel.id.notEq(globalServices.myId))
    )

    if (memberIds.isEmpty()) return

    // 批量插入或替换已读信息
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
    val allGroupIds = wcdb.group.allObjects.map { it.gid }
    if (allGroupIds.isNotEmpty()) {
        wcdb.groupMemberContactor.deleteObjects(
            DBGroupMemberContactorModel.gid.notEq("")
                .and(DBGroupMemberContactorModel.gid.notIn(*allGroupIds.toTypedArray()))
        )
    }
}

