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

/**
 * Child-row -> domain mappers shared by BOTH read paths:
 *  - the per-message point queries in `WCDBExtensions.kt` (`attachment()`, `mentions()`, …)
 *  - the batch hydration path in [org.difft.app.database.hydration]
 *
 * Single owner on purpose. With one mapper, "batch hydration produces field-for-field the same
 * domain objects as the point queries" is a structural property, not a coincidence of two copies
 * happening to agree — the only thing left that can diverge is WHICH rows each path selects and in
 * what order, which is what the hydration unit tests assert.
 *
 * These are public rather than internal because the test fixtures (`ChildRowCorpus`) and `:chat`
 * tests build the reference sub-data for the equivalence cases through the same mappers.
 *
 * [AttachmentModel.toAttachment] deliberately stays in `WCDBExtensions.kt`: it predates this file,
 * has unrelated callers, and depends on the private `convertAmplitudes` there.
 */

/**
 * The attachment shape used INSIDE a quote. Intentionally NOT [AttachmentModel.toAttachment]:
 *  - `thumbnail` normalises an empty byte array to null (mirrors how quotes are written)
 *  - `id` / `authorityId` fall back to `""` / `0L` instead of `!!`
 *  - `totalTime` / `amplitudes` are not carried
 *
 * Merging the two is a behaviour regression on quote thumbnails.
 */
fun AttachmentModel.toQuotedAttachment(): QuotedAttachment = QuotedAttachment(
    contentType = contentType ?: "",
    fileName = fileName ?: "",
    thumbnail = Attachment(
        id = id ?: "",
        authorityId = authorityId ?: 0L,
        contentType = contentType ?: "",
        key = key,
        size = size,
        thumbnail = thumbnail?.takeIf { it.isNotEmpty() }, // null = no bytes (mirrors write)
        digest = digest,
        fileName = fileName,
        flags = flags,
        width = width,
        height = height,
        path = path,
        status = status
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
