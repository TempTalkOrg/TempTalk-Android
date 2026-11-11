package org.difft.app.database

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.QuotedAttachment
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
import java.util.UUID











fun AttachmentModel.synthesizedLocalId(): String =
    UUID.nameUUIDFromBytes("attachment-row-$databaseId".toByteArray(Charsets.UTF_8)).toString()









fun AttachmentModel.toQuotedAttachment(): QuotedAttachment = QuotedAttachment(
    contentType = contentType ?: "",
    fileName = fileName ?: "",
    thumbnail = Attachment(
        id = id ?: "",
        authorityId = authorityId ?: 0L,
        contentType = contentType ?: "",
        key = key,
        size = size,
        thumbnail = thumbnail?.takeIf { it.isNotEmpty() }, 
        digest = digest,
        fileName = fileName,
        flags = flags,
        width = width,
        height = height,
        path = null, 
        status = status,
        
        localId = localId ?: synthesizedLocalId()
    ),
    flags = flags
)

fun MentionModel.toMention(): Mention = Mention(
    start = start,
    length = length,
    uid = uid,
    type = type
)

fun ReactionModel.toReaction(): Reaction = Reaction(
    emoji = emoji,
    uid = uid ?: "",
    originTimestamp = timeStamp
)

fun TranslateModel.toTranslateData(): TranslateData = TranslateData(
    translateStatus = TranslateStatus.fromIntOrDefault(translateStatus),
    translatedContentCN = translatedContentCN,
    translatedContentEN = translatedContentEN
)

fun SpeechToTextModel.toSpeechToTextData(): SpeechToTextData = SpeechToTextData(
    convertStatus = SpeechToTextStatus.fromIntOrDefault(convertStatus),
    speechToTextContent = speechToTextContent
)

fun SharedContactModel.toSharedContactName(): SharedContactName = SharedContactName(
    givenName,
    familyName,
    namePrefix,
    nameSuffix,
    middleName,
    displayName
)

fun SharedContactPhoneModel.toSharedContactPhone(): SharedContactPhone = SharedContactPhone(
    value = phoneNumber,
    type = phoneNumberType,
    label = phoneNumberLabel
)
