package com.difft.android.migration.v2.importer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.SharedContactModel

/**
 * Recompute the derived room-level state for every migrated room after all message rows are imported.
 * The migration writes message rows straight to the target DB, bypassing
 * [org.difft.app.database.WCDBUpdateService], which is what normally fills a room's
 * `lastDisplayContent`, `lastActiveTime` and `unreadMessageNum` when a live message arrives. Without
 * this pass a conversation that never receives a post-login live message keeps an empty
 * `lastDisplayContent` and is filtered out of the conversation list. The same holds for `sendStatus`
 * and `criticalAlertType`, which the live path writes only from its own message pipeline and never
 * backfills. The list must be correct from migrated state alone, with no dependency on the post-login
 * full-refresh.
 *
 * These fields are recomputed rather than carried: a rendered preview string would freeze the source's
 * locale and format. The branch logic and string keys here mirror
 * [org.difft.app.database.WCDBExtensions.previewContent] and `updateRoomUnreadState` exactly, so a
 * migrated preview matches a live one.
 *
 * Those canonical extensions read the global live `wcdb` singleton and `globalServices.myId`, neither
 * of which is the staging DB, and during a net-install restore `myId` is not established yet. So this
 * recomputer mirrors their logic over the importer's [RoomStateReadWrite] target and uses [selfUid] in
 * place of `myId`.
 */
object RoomStateRecomputer {

    /** The `WCDBUpdateService` archive-system-message actionType ("Earlier messages expired"). */
    private const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012

    /**
     * Recompute and persist derived state for every room that has at least one message: the preview of
     * the latest message, its `lastActiveTime`, the unread count and mention type, `sendStatus` from any
     * failed outgoing row, and `criticalAlertType` from any unread alert row. Rooms with no messages are
     * left at their empty defaults.
     *
     * @param selfUid the migrating account's own uid, used to exclude own-sent messages from the unread
     *   count. Blank means no exclusion, which over-counts but never under-counts.
     * @param strings preview-label source; injectable so tests supply a fake without the resources
     *   chain, which is unavailable off-device.
     */
    fun recompute(
        target: RoomStateReadWrite,
        selfUid: String,
        strings: PreviewStrings = PreviewStrings.ResUtilsBacked,
    ) {
        val roomIds = target.roomIdsWithMessages()
        var updated = 0
        var failed = 0
        roomIds.forEach { roomId ->
            try {
                if (recomputeRoom(target, roomId, selfUid, strings)) updated++
            } catch (e: Exception) {
                failed++
                L.w { "[MigrationV2][Import] room-state recompute failed roomId=$roomId: ${e.stackTraceToString()}" }
            }
        }
        L.i { "[MigrationV2][Import] room-state recomputed rooms=$updated failed=$failed" }
    }

    /** One room's derived state; false when the room has no message to derive from. */
    private fun recomputeRoom(
        target: RoomStateReadWrite,
        roomId: String,
        selfUid: String,
        strings: PreviewStrings,
    ): Boolean {
        val latest = target.latestMessagePreviewInfo(roomId) ?: return false
        // For an "Earlier messages expired" system message the live path clears lastDisplayContent
        // and keeps lastActiveTime; mirror it so a migrated archived room renders like a live one.
        val isArchive = isArchiveExpiredSystemMessage(latest)
        val lastDisplayContent = if (isArchive) "" else previewContent(latest, strings)
        val lastActiveTime = latest.systemShowTimestamp

        // Count messages strictly newer than the read watermark that are neither own-sent nor
        // notify/confidential-placeholder — the same predicate as updateRoomUnreadState.
        val readPosition = target.roomReadPosition(roomId)
        val unreadIds = target.unreadMessageIds(roomId, readPosition, selfUid)
        val unreadNum = unreadIds.size
        val mentionType = if (unreadNum == 0) {
            MENTIONS_TYPE_NONE
        } else {
            mentionRollup(target.mentionUidsForMessages(unreadIds, selfUid))
        }

        // Aggregate, like the live writers: one failed real outgoing row tags the room, a later
        // success does not mask it. The alert tag needs the alert to still be unread.
        val sendStatus = if (target.hasFailedOutgoingMessage(roomId)) ROOM_SEND_STATUS_FAILED else ROOM_SEND_STATUS_NONE
        val criticalAlertType = if (unreadNum > 0 && target.hasCriticalAlert(unreadIds)) {
            CRITICAL_ALERT_TYPE_ALERT
        } else {
            CRITICAL_ALERT_TYPE_NONE
        }

        target.updateRoomDerivedState(
            roomId,
            RoomDerivedState(
                lastDisplayContent = lastDisplayContent,
                lastActiveTime = lastActiveTime,
                unreadMessageNum = unreadNum,
                mentionType = mentionType,
                sendStatus = sendStatus,
                criticalAlertType = criticalAlertType,
            ),
        )
        return true
    }

    /**
     * The conversation-list preview for the latest message, mirroring
     * [org.difft.app.database.WCDBExtensions.previewContent]'s branch order and string keys, minus the
     * live-only group sender-name prefix, which would be empty since contacts are not migrated.
     */
    private fun previewContent(m: LatestMessagePreviewInfo, strings: PreviewStrings): String = when {
        m.type == MessageModel.TYPE_UNSUPPORTED -> strings.unsupported()

        m.mode == 1 -> strings.confidential()

        m.type == MessageModel.TYPE_ATTACHMENT -> when {
            m.attachmentContentType?.contains("image") == true -> strings.image()
            m.attachmentContentType?.contains("video") == true -> strings.video()
            // isAudioMessage (flags==1) OR isAudioFile (flags==0): either audio contentType is "audio".
            m.attachmentContentType?.contains("audio") == true -> strings.audio()
            else -> strings.attachment()
        }

        m.type == MessageModel.TYPE_TEXT -> when {
            m.hasSharedContact -> strings.contactCard()
            m.hasForwardContext -> "[" + strings.chatHistory() + "]"
            else -> m.messageText.orEmpty()
        }

        m.type == MessageModel.TYPE_NOTIFY -> m.notifyShowContent.orEmpty()

        else -> ""
    }

    /** Mirror of `WCDBExtensions.updateRoomUnreadState` mention rollup: @me wins over @all. */
    private fun mentionRollup(mentionUids: List<String>): Int = when {
        mentionUids.any { it == SELF_MENTION_SENTINEL } -> MENTIONS_TYPE_ME
        mentionUids.any { it == MENTIONS_ALL_ID } -> MENTIONS_TYPE_ALL
        else -> MENTIONS_TYPE_NONE
    }

    /**
     * Mirror of `WCDBUpdateService.isArchiveExpiredSystemMessage`: a notify message whose JSON
     * `data.actionType` is the "Earlier messages expired" code. Best-effort: a non-notify message or a
     * malformed payload is NOT an archive message.
     */
    private fun isArchiveExpiredSystemMessage(m: LatestMessagePreviewInfo): Boolean {
        if (m.type != MessageModel.TYPE_NOTIFY) return false
        return m.notifyActionType == NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED
    }

    /**
     * Sentinel the read surface substitutes for the migrating account's own uid in the mention rollup
     * (so the recomputer need not re-thread `selfUid` into [mentionRollup]). The read surface maps a
     * @me mention to this value when the mentioned uid equals `selfUid`.
     */
    const val SELF_MENTION_SENTINEL = "__SELF__"

    /**
     * The conversation-list preview LABELS the recomputer needs — the exact `chat_message_*` strings
     * `previewContent()` uses. Abstracted so the on-device path uses the real localized resources via
     * [ResUtilsBacked], while JVM/Robolectric tests inject a fake (the `ApplicationHelper.instance` /
     * `application.resources` chain `ResUtils` reads is not initialized off-device — calling it there
     * throws). All defaults map 1:1 to the live `previewContent()` string keys.
     */
    interface PreviewStrings {
        fun unsupported(): String
        fun confidential(): String
        fun image(): String
        fun video(): String
        fun audio(): String
        fun attachment(): String
        fun contactCard(): String
        fun chatHistory(): String

        /** Production source — the real localized `chat_message_*` strings (same keys as the live preview). */
        object ResUtilsBacked : PreviewStrings {
            override fun unsupported() = ResUtils.getString(com.difft.android.base.R.string.chat_message_unsupported)
            override fun confidential() = ResUtils.getString(com.difft.android.base.R.string.chat_message_confidential_message)
            override fun image() = ResUtils.getString(com.difft.android.base.R.string.chat_message_image)
            override fun video() = ResUtils.getString(com.difft.android.base.R.string.chat_message_video)
            override fun audio() = ResUtils.getString(com.difft.android.base.R.string.chat_message_audio)
            override fun attachment() = ResUtils.getString(com.difft.android.base.R.string.chat_message_attachment)
            override fun contactCard() = ResUtils.getString(com.difft.android.base.R.string.chat_message_contact_card)
            override fun chatHistory() = ResUtils.getString(com.difft.android.base.R.string.chat_history)
        }
    }
}

/**
 * The narrow read+update surface [RoomStateRecomputer] needs against the importer's target DB.
 * Implemented by every [WcdbWriteTarget] over its own tables, so the recompute runs identically on the
 * staging DB and on the in-memory test fake.
 *
 * All reads are scoped to the DB the importer just wrote, never the global live `wcdb` that the
 * canonical extensions read.
 */
interface RoomStateReadWrite {

    /** Distinct `roomId`s that have at least one message row (the rooms whose derived state to recompute). */
    fun roomIdsWithMessages(): List<String>

    /** The room's persisted read watermark (`RoomModel.readPosition`); 0 when none. */
    fun roomReadPosition(roomId: String): Long

    /** Preview info for the room's latest message (max `systemShowTimestamp`); null when the room has none. */
    fun latestMessagePreviewInfo(roomId: String): LatestMessagePreviewInfo?

    /**
     * Ids of messages strictly newer than [readPosition] that count as unread — mirror of
     * `updateRoomUnreadState`'s predicate: `systemShowTimestamp > readPosition` AND NOT own-sent
     * ([selfUid], case-insensitive) AND `type NOT IN (TYPE_NOTIFY, TYPE_CONFIDENTIAL_PLACEHOLDER)`.
     */
    fun unreadMessageIds(roomId: String, readPosition: Long, selfUid: String): List<String>

    /**
     * For the unread [messageIds], the mention uids that matter for the room mention badge — mirror of
     * `updateRoomUnreadState`'s mention query (`uid IN (myId, MENTIONS_ALL_ID)`). A mention of [selfUid]
     * is returned as [RoomStateRecomputer.SELF_MENTION_SENTINEL] so the rollup is selfUid-agnostic.
     */
    fun mentionUidsForMessages(messageIds: List<String>, selfUid: String): List<String>

    /**
     * Whether the room holds a failed real outgoing message — mirror of `RoomSendStatusQueries`'
     * scope: `sendType == SEND_TYPE_FAILED` AND `type NOT IN (TYPE_NOTIFY, TYPE_CONFIDENTIAL_PLACEHOLDER)`.
     */
    fun hasFailedOutgoingMessage(roomId: String): Boolean

    /** Whether any of the (unread) [messageIds] carries a critical alert. */
    fun hasCriticalAlert(messageIds: List<String>): Boolean

    /** Persist the recomputed derived state onto the room row (the live path's room-update write). */
    fun updateRoomDerivedState(roomId: String, state: RoomDerivedState)
}

/** The room columns [RoomStateRecomputer] derives from the imported rows; one write per room. */
data class RoomDerivedState(
    val lastDisplayContent: String,
    val lastActiveTime: Long,
    val unreadMessageNum: Int,
    val mentionType: Int,
    val sendStatus: Int,
    val criticalAlertType: Int,
)

/**
 * What [RoomStateRecomputer] needs about a room's latest message — a flattened read DTO, so its preview
 * branches need no further DB reads. Each [WcdbWriteTarget] resolves the per-message presence flags
 * when it builds this.
 *
 * @param mode 1 means confidential.
 * @param messageText the raw body, or the notify JSON for a NOTIFY message.
 * @param systemShowTimestamp the cross-platform sort and active-time watermark.
 * @param hasForwardContext true when the message owns a forward context (a chat-history card).
 * @param notifyActionType for a NOTIFY message, the `data.actionType` code, used to detect an archive
 *   message.
 */
data class LatestMessagePreviewInfo(
    val id: String,
    val type: Int,
    val mode: Int,
    val messageText: String?,
    val systemShowTimestamp: Long,
    val attachmentContentType: String? = null,
    val hasSharedContact: Boolean = false,
    val hasForwardContext: Boolean = false,
    val notifyShowContent: String? = null,
    val notifyActionType: Int? = null,
)

/**
 * Table-agnostic implementation of the [RoomStateReadWrite] reads, computed from the target's already
 * imported rows. Every [WcdbWriteTarget] delegates its read methods here, so the logic lives in one
 * place and runs identically on the staging DB and the in-memory fake.
 *
 * The staging DB is a single-device net-install backup with a bounded row count, so iterating in memory
 * is acceptable and avoids re-deriving WCDB query syntax per target.
 */
internal object RoomStateSupport {

    /** Mirror of `MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER` (excluded from unread, like notify). */
    private const val TYPE_CONFIDENTIAL_PLACEHOLDER = MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER

    private val gson = Gson()

    fun roomIdsWithMessages(messages: List<MessageModel>): List<String> =
        messages.mapNotNull { it.roomId?.takeIf { id -> id.isNotEmpty() } }.distinct()

    /** Latest message of [roomId] by `systemShowTimestamp` (the cross-platform sort authority). */
    fun latestMessagePreviewInfo(
        roomId: String,
        messages: List<MessageModel>,
        attachments: List<AttachmentModel>,
        sharedContacts: List<SharedContactModel>,
        forwardContexts: List<ForwardContextModel>,
    ): LatestMessagePreviewInfo? {
        val latest = messages.filter { it.roomId == roomId }.maxByOrNull { it.systemShowTimestamp } ?: return null
        val fcPk = forwardContextPk(latest)
        return previewInfoOf(
            latest,
            attachmentContentType = attachments.firstOrNull { it.messageId == latest.id }?.contentType,
            hasSharedContact = sharedContacts.any { it.messageId == latest.id },
            hasForwardContext = fcPk != null && forwardContexts.any { it.databaseId.toLong() == fcPk },
        )
    }

    /** The forward-context FK, or null for none (0 / null on the row). */
    fun forwardContextPk(m: MessageModel): Long? = m.forwardContextDatabaseId?.takeIf { it != 0L }

    /** The preview record for [latest]; the three lookups are the caller's, however it queries. */
    fun previewInfoOf(
        latest: MessageModel,
        attachmentContentType: String?,
        hasSharedContact: Boolean,
        hasForwardContext: Boolean,
    ): LatestMessagePreviewInfo {
        val notify = if (latest.type == MessageModel.TYPE_NOTIFY) parseNotify(latest.messageText) else null
        return LatestMessagePreviewInfo(
            id = latest.id,
            type = latest.type,
            mode = latest.mode,
            messageText = latest.messageText,
            systemShowTimestamp = latest.systemShowTimestamp,
            attachmentContentType = attachmentContentType,
            hasSharedContact = hasSharedContact,
            hasForwardContext = hasForwardContext,
            notifyShowContent = notify?.first,
            notifyActionType = notify?.second,
        )
    }

    /** Mirror of `updateRoomUnreadState`'s unread predicate, computed in memory. */
    fun unreadMessageIds(
        roomId: String,
        readPosition: Long,
        selfUid: String,
        messages: List<MessageModel>,
    ): List<String> = messages.filter { m ->
        m.roomId == roomId &&
            m.systemShowTimestamp > readPosition &&
            m.type != MessageModel.TYPE_NOTIFY &&
            m.type != TYPE_CONFIDENTIAL_PLACEHOLDER &&
            // Own-sent exclusion, case-insensitive like the live query. A blank selfUid excludes
            // nothing, which over-counts but never under-counts.
            (selfUid.isEmpty() || !(m.fromWho ?: "").equals(selfUid, ignoreCase = true))
    }.map { it.id }

    /**
     * Mirror of `updateRoomUnreadState`'s mention query: mentions on the unread [messageIds] whose uid is
     * the self uid or @all. A self mention is mapped to [RoomStateRecomputer.SELF_MENTION_SENTINEL] so the
     * recomputer's rollup stays selfUid-agnostic.
     */
    fun mentionUidsForMessages(
        messageIds: List<String>,
        selfUid: String,
        mentions: List<MentionModel>,
    ): List<String> {
        val idSet = messageIds.toHashSet()
        return mentions.filter { it.messageId != null && idSet.contains(it.messageId) }
            .mapNotNull { mn ->
                val uid = mn.uid ?: return@mapNotNull null
                when {
                    selfUid.isNotEmpty() && uid.equals(selfUid, ignoreCase = true) ->
                        RoomStateRecomputer.SELF_MENTION_SENTINEL
                    uid == MENTIONS_ALL_ID -> MENTIONS_ALL_ID
                    else -> null
                }
            }
    }

    /** Mirror of `RoomSendStatusQueries.failedOutgoingCondition`, computed in memory. */
    fun hasFailedOutgoingMessage(roomId: String, messages: List<MessageModel>): Boolean = messages.any { m ->
        m.roomId == roomId &&
            m.sendType == MessageModel.SEND_TYPE_FAILED &&
            m.type != MessageModel.TYPE_NOTIFY &&
            m.type != TYPE_CONFIDENTIAL_PLACEHOLDER
    }

    /** Whether any of [messageIds] carries a critical alert (`MessageModel.criticalAlertType`). */
    fun hasCriticalAlert(messageIds: List<String>, messages: List<MessageModel>): Boolean {
        val idSet = messageIds.toHashSet()
        return messages.any { it.id in idSet && it.criticalAlertType == CRITICAL_ALERT_TYPE_ALERT }
    }

    /** Parse a notify message's JSON → (showContent, data.actionType). Best-effort; nulls on failure. */
    private fun parseNotify(messageText: String?): Pair<String?, Int?> {
        val raw = messageText?.takeIf { it.isNotEmpty() } ?: return null to null
        val json: JsonObject = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: return null to null
        val showContent = json.get("showContent")?.takeIf { !it.isJsonNull }?.asString
        val actionType = runCatching {
            json.getAsJsonObject("data")?.get("actionType")?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
        return showContent to actionType
    }
}
