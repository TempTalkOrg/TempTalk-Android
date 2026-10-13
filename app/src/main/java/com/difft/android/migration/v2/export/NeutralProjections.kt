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
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.models.RoomModel

/**
 * Pure projection helpers mapping Android WCDB rows to their neutral shapes, resolving FK sub-rows
 * from a [MigrationReadSource] where the neutral schema folds them into sub-structures. No streaming
 * and no encryption — [NeutralExporter] orchestrates the stream and calls these, so the code is
 * identical whether the source is the live DB or a snapshot.
 */
internal object NeutralProjections {

    /** Android `MessageModel.sendType` values (`SendType`): in-flight / failed outgoing sends. */
    private const val SEND_TYPE_SENDING = 0
    private const val SEND_TYPE_FAILED = 2

    /** `MessageModel.playStatus`: 1 = a voice note the user has not played yet (0 = played). */
    private const val PLAY_STATUS_NOT_PLAYED = 1

    /** Frozen `platformExtras` key for [PLAY_STATUS_NOT_PLAYED]; only "false" is ever carried. */
    private const val EXTRA_PLAYED = "android.played"

    /**
     * Project a [MessageModel] to a [NeutralMessage], folding in quote, forward, mentions and
     * reactions from their FK rows. Attachments are not embedded — they stream as separate records,
     * so this only fills [NeutralMessage.attachmentRefs].
     */
    fun message(
        readSource: MigrationReadSource,
        m: MessageModel,
        selfUid: String = "",
        ladder: ReadLadder = ReadLadder.EMPTY,
        roomReadPosition: Long = 0L,
    ): NeutralMessage {
        // Each ref names a NeutralAttachment.stableId, and an attachment with a null id cannot be
        // referenced, so drop it rather than carry a null ref.
        val attachmentRefs = readSource.attachment
            .getAllObjects(DBAttachmentModel.messageId.eq(m.id))
            .mapNotNull { it.id }

        val isSystem = m.type == MessageModel.TYPE_NOTIFY
        // System (notify) messages overload `messageText` with the notify JSON, whose pre-rendered
        // `showContent` becomes the canonical `displayText`; the raw type and payload travel
        // verbatim. Non-system messages keep `messageText` as `body` and carry no displayText.
        val notify = if (isSystem) parseNotify(m) else null

        val direction = direction(m, selfUid)
        val serverAt = serverAt(m)

        return NeutralMessage(
            stableId = m.id,
            // roomId / fromWho are logical join keys, non-null in practice. Map null to "", which
            // the re-projector also does, so a null source value round-trips identically.
            conversationRef = m.roomId ?: "",
            // roomType is intrinsic per message (single=0 / group=1) and the chat message query
            // filters on it alongside roomId, so it must round-trip or a migrated group shows
            // no messages.
            roomType = m.roomType,
            sender = m.fromWho ?: "",
            direction = direction,
            // System messages never put the notify JSON in `body`; `displayText` carries the
            // always-displayable form instead.
            body = if (isSystem) null else m.messageText,
            sentAt = m.timeStamp,
            serverAt = serverAt,
            receivedAt = m.receivedTimeStamp.takeIf { it != 0L },
            expireTimer = m.expiresInSeconds,
            // Exception anchor only: the ladder covers the common case, so this carries just the rows
            // whose effective start on this device differs from what the ladder derives.
            expireStartedAt = AndroidExpiryAnchor.exception(
                local = AndroidExpiryAnchor.of(m.readTime, serverAt, direction, roomReadPosition),
                derived = ladder.derivedStart(direction, serverAt),
            ),
            contentKind = deriveContentKind(m),
            // The notify type code verbatim, not a generic placeholder or a cross-end semantic enum.
            systemType = notify?.rawSystemType,
            // The server notify JSON verbatim; Android already persists it in messageText.
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

    /**
     * Project an [AttachmentModel] to a [NeutralAttachment].
     *
     * `serverAttachmentId`, `encryptionKey` and `digestBase64` always travel, independent of
     * [bytesPresent]: they are part of every attachment row, the destination reads them
     * unconditionally when rendering, and its download gate needs both key and digest. Omitting them
     * makes the destination NPE on render and leaves a pointer-only attachment unrecoverable.
     *
     * [bytesPresent] controls only whether a byte frame is emitted. False means the source file is
     * legitimately absent — attachments are fetched lazily when a message is viewed — so the record
     * travels [NeutralAttachment.pointerOnly] and the destination re-downloads later.
     */
    fun attachment(
        am: AttachmentModel,
        bytesPresent: Boolean,
        owner: AttachmentOwner = AttachmentOwner.MESSAGE,
        ownerAuthor: String? = null,
        ownerSentAt: Long? = null,
        stableRefOverride: String? = null,
        // The stableId of the message this row is rendered under. Forward-owned rows have no
        // `messageId` of their own, so their host has to be handed in by whoever walked the message.
        hostMessageRef: String? = null,
    ): NeutralAttachment = NeutralAttachment(
        // Map a null PK to "" so it round-trips against the re-projector's same default. Forward and
        // quote rows can have no `id`, so the exporter passes their authorityId as the override.
        stableId = stableRefOverride ?: am.id ?: "",
        // messageRef is the host message for every owner kind and is never empty: the receiver
        // locates the message by it and files the attachment under that message's album. A
        // forward-owned row has no `messageId` of its own, so the host comes from the walk and
        // ownerAuthor/ownerSentAt say which node of its forward tree the row belongs to.
        messageRef = hostMessageRef ?: am.messageId ?: "",
        contentType = am.contentType,
        size = am.size.toLong(),
        width = am.width.takeIf { it > 0 },
        height = am.height.takeIf { it > 0 },
        durationMs = am.totalTime,
        // Wire flags bitmask verbatim, carrying the VOICE(1) and GIF(4) bits.
        flags = am.flags,
        amplitudes = am.amplitudes?.takeIf { it.isNotEmpty() },
        fileName = am.fileName?.takeIf { it.isNotEmpty() },
        // Identity and credentials always travel; the destination reads them unconditionally. Byte
        // frames are tracked by `pointerOnly` below, keyed by `stableId` and paired positionally on
        // read, so there is no separate contentRef.
        // 0 is the local "never uploaded" sentinel, which the wire carries as absent (iOS does the same).
        serverAttachmentId = am.authorityId?.takeIf { it != NO_SERVER_ATTACHMENT_ID },
        encryptionKey = encodeKey(am.key),
        // The destination's download gate needs both key and digest, so a pointer-only attachment
        // cannot be re-downloaded or verified without this.
        digestBase64 = encodeKey(am.digest),
        pointerOnly = !bytesPresent,
        owner = owner,
        ownerAuthor = ownerAuthor,
        ownerSentAt = ownerSentAt,
    )

    /**
     * The addressable identity of an attachment row: its `id`, or its `authorityId` (the re-download
     * id) when the row has none. Null when the row has neither. Forward rows that arrived from
     * another platform routinely have no `id`, so the byte record and the preview meta must both
     * derive their stableId from this one rule or the meta cannot name its record.
     */
    fun attachmentStableRef(am: AttachmentModel): String? =
        am.id?.takeIf { it.isNotEmpty() } ?: am.authorityId?.takeIf { it != 0L }?.toString()

    /** Preview meta for a forward/quote attachment row; ties to its byte record by stableId. */
    fun attachmentMeta(am: AttachmentModel): NeutralAttachmentMeta = NeutralAttachmentMeta(
        stableId = attachmentStableRef(am) ?: "",
        contentType = am.contentType,
        fileName = am.fileName?.takeIf { it.isNotEmpty() },
    )

    /** Base64 (NO_WRAP) of a non-empty attachment key; null when absent (so it round-trips as null). */
    private fun encodeKey(key: ByteArray?): String? =
        key?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /**
     * Project a [RoomModel] to [NeutralConversationLocal]: pinned state, the account's own read
     * ladder, and others' watermarks.
     *
     * [ladder] is built once per room by [com.difft.android.migration.v2.readtime.ReadLadderBuilder]
     * from that room's per-message read times, since Android stores the ladder expanded rather than
     * as rows; it supplies both the watermark (`RoomModel.readPosition` plus the readAt Android has
     * no column for) and the rungs.
     *
     * [readInfoRows] are this room's rows of others' read positions, passed in by the caller so the
     * projection stays source-agnostic. They drive the outgoing-message "read by" indicator after
     * restore and carry only `maxServerTime`; an empty list carries none.
     */
    fun conversationLocal(
        r: RoomModel,
        readInfoRows: List<ReadInfoModel>,
        ladder: ReadLadder = ReadLadder.EMPTY,
        selfUid: String = "",
    ): NeutralConversationLocal = NeutralConversationLocal(
        conversationRef = r.roomId,
        // roomType is intrinsic (single=0 / group=1) and is carried rather than recomputed: the
        // post-login group full-refresh that would re-set it arrives too late, so a migrated group
        // would render as a 1-on-1 until then.
        roomType = r.roomType,
        pinnedTime = r.pinnedTime?.takeIf { it > 0 },
        // The builder folds `RoomModel.readPosition` into the watermark and supplies the readAt
        // Android has no column for, so both halves come from the ladder.
        readPosition = ladder.watermark,
        readPositions = ladder.rows,
        // The account's own row must not travel here: its state is the watermark and the ladder, and
        // a self row would restore as a phantom "read by me" participant.
        participantReadPositions = readInfoRows.filterNot { it.uid == selfUid }.map { participantReadPosition(it) },
    )

    /**
     * Project one `read_info` row to [NeutralParticipantReadPosition]. Android persists only
     * `readPosition` per {roomId, uid}, which is that participant's `maxServerTime` watermark; the
     * other [NeutralReadPosition] fields have no column and stay 0.
     */
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

    /** Project a [GroupCryptoKeysModel] to [NeutralGroupKey]. */
    fun groupKey(g: GroupCryptoKeysModel): NeutralGroupKey =
        // gid and rGroup are non-null in practice for a stored key row; map null to "" to keep the
        // neutral shape's non-null contract and match the re-projector's default.
        NeutralGroupKey(groupId = g.gid ?: "", rGroup = g.rGroup ?: "", keyVersion = g.keyVersion)

    /**
     * Project the account's identity material to [NeutralIdentity]. Unlike the other projections this
     * reads [UserData] (secure storage) rather than a WCDB row — the one non-DB record the container
     * carries. Returns null when either half of the ACI identity pair is absent, in which case the
     * destination generates a fresh identity at login.
     *
     * Values are copied verbatim; in particular [NeutralIdentity.identityKeyGenTime] must carry the
     * original generation time, because 0 or a re-stamped `now` silently changes the old-key decrypt
     * fallback window.
     *
     * Never log key material — the absent-identity warning below deliberately carries no values.
     *
     * `recoverySlotKeks` / `recoveryStaleSlots` export as null: cold recovery has not landed on
     * Android, so there are no slot KEKs to carry.
     */
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

    /**
     * Project the account's session credential to [NeutralCredential]. Emitted only by a same-device
     * no-login handoff send; every other send path leaves the record out.
     *
     * Returns null when any of `account` / `baseAuth` / `microToken` / `signalingKey` is empty or
     * [handoffId] is blank, which aborts the send before a snapshot is taken: handing over half a
     * credential would leave the new app with a session the cold-start verifier rejects while the old
     * app has already wiped.
     *
     * Never log any part of the returned value — it is a live account credential.
     */
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

    // Remarks are not migrated: the target re-syncs friend and non-friend remarks from the server
    // after login. The record type is gone from the contract, so an inbound container carrying one
    // takes the codec's unknown-type skip path.

    // ── sub-structure resolvers ──────────────────────────────────────────────────────────

    /**
     * Who sent the message, decided the same way the chat UI decides it: by author identity.
     *
     * `sendType` is NOT a direction signal, however much it reads like one. Android's receive path
     * commits incoming rows at `SendType.Sent(1)` (`MessageContentProcessor`), so a `sendType > 0`
     * test calls every received message outgoing. Android-to-Android hid that — the renderer keys
     * off `myId == fromWho` and never consults `direction`, and the importer's inverse rule turned
     * the mislabel back into the same row — but a peer that trusts the field, as it is entitled to,
     * shows the whole history as sent by the migrating user.
     */
    private fun direction(m: MessageModel, selfUid: String): MessageDirection {
        // No account to compare against — an exporter bug, not data. The fallback below is wrong for
        // received rows but keeps the export running, so say so.
        if (selfUid.isEmpty()) L.w { "[MigrationV2] direction fallback: no selfUid, classifying by sendType id=${m.id}" }
        return if (isOutgoing(m, selfUid)) MessageDirection.OUTGOING else MessageDirection.INCOMING
    }

    /** [direction] without the diagnostic, for passes that classify every row a second time. */
    internal fun isOutgoing(m: MessageModel, selfUid: String): Boolean =
        if (selfUid.isNotEmpty()) m.fromWho == selfUid else m.sendType > 0

    /** serverAt is required, so fall back to sentAt and never hand importers a 0 sort key. */
    internal fun serverAt(m: MessageModel): Long = m.systemShowTimestamp.takeIf { it != 0L } ?: m.timeStamp

    /**
     * Only outgoing messages carry a sendState; null means SENT. An in-flight row exports FAILED
     * because the send cannot resume on the new device, and FAILED lets the user retry.
     */
    private fun sendState(m: MessageModel, direction: MessageDirection): SendState? = when {
        direction != MessageDirection.OUTGOING -> null
        m.sendType == SEND_TYPE_FAILED -> SendState.FAILED
        m.sendType == SEND_TYPE_SENDING -> SendState.FAILED
        else -> null
    }

    /**
     * Android's one platform-private extra: an un-played voice note (`playStatus == 1`). It is a pure
     * UI badge with no counterpart on iOS or Desktop, so it travels namespaced rather than as a
     * first-class field, and the default (played) carries nothing.
     */
    private fun platformExtras(m: MessageModel, notify: NotifyInfo?): Map<String, String> =
        if (m.playStatus == PLAY_STATUS_NOT_PLAYED) mapOf(EXTRA_PLAYED to "false") else emptyMap()

    /**
     * The screenshot-notice marker, projected from Android's `screenShotJson` column. Android builds
     * the wire `RealSource` self-referentially (the notice's own uid + timestamp) and its renderer only
     * tests presence, so the carried pair restates the message's own identity — which is exactly why
     * reconstructing it on import is lossless.
     */
    private fun screenShot(m: MessageModel): NeutralScreenShot? =
        m.screenShotJson?.takeIf { it.isNotEmpty() }?.let {
            NeutralScreenShot(author = m.fromWho.orEmpty(), sentAt = m.timeStamp)
        }

    /**
     * The recipient roster snapshot Android stores as a JSON array string on its own outgoing group
     * messages. Empty (not null) when absent, so importers have one shape to handle.
     */
    private fun recipients(m: MessageModel): List<String> =
        m.receiverIds?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, Array<String>::class.java).toList() }.getOrNull() }
            ?: emptyList()

    /**
     * Parse the notify JSON Android stores in `MessageModel.messageText` for a system message,
     * mirroring `WCDBExtensions.previewContent()`: `showContent` is the rendered display string and
     * `notifyType` the raw type. Best-effort — a malformed payload yields null parts, and the raw
     * payload still travels verbatim.
     */
    private fun parseNotify(m: MessageModel): NotifyInfo = parseNotifyJson(m.messageText, m.id)

    /**
     * Parse the notify JSON and keep the raw payload verbatim. Shared with the test re-projector so a
     * system message's displayText and systemType stay byte-identical across a round-trip.
     */
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

    /** Parsed pieces of a system message's notify JSON. */
    data class NotifyInfo(
        val displayText: String?,
        val rawSystemType: String?,
        val rawPayload: String?,
    )

    private fun quote(readSource: MigrationReadSource, m: MessageModel): NeutralQuote? = m.quoteDatabaseId?.let { qId ->
        readSource.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))?.let { qm ->
            NeutralQuote(
                author = qm.author,
                sentAt = qm.id, // QuoteModel.id is the quoted message's sentAt timestamp (composite identity).
                textPreview = qm.text.takeIf { it.isNotEmpty() },
                // Fold in quote preview attachment meta (rows tagged quoteModelDatabaseId).
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
                // The neutral `type` is the wire proto value, which is what the live receive path
                // already stores in MentionModel.type.
                type = mm.type,
            )
        }

    private fun reactions(readSource: MigrationReadSource, m: MessageModel): List<NeutralReaction> =
        readSource.reaction.getAllObjects(DBReactionModel.messageId.eq(m.id)).map { rm ->
            NeutralReaction(
                emoji = rm.emoji,
                fromUid = rm.uid ?: "",
                removed = false, // Android stores active reactions as rows; removal = row delete (no tombstone).
                sentAt = rm.timeStamp,
            )
        }

    /**
     * Fold a message's contact-card content into neutral shapes. It lives in two tables —
     * `SharedContactModel` for the name parts and N `SharedContactPhoneModel` rows for the phones.
     * Read via [readSource] rather than the global-`wcdb`-bound `sharedContacts()` extension, so the
     * projection stays source-agnostic. Applies to normal and confidential cards alike.
     */
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

    /**
     * Resolve a combined-forward context. It holds N parallel top-level forwards — sibling
     * `ForwardModel` rows with a null `parentForwardModelDatabaseId` — each itself a recursive
     * [NeutralForward] tree. All of them are carried as parallel [NeutralForwardContext.roots] in
     * source order; folding them into the first root collapses the card on round-trip. Returns null
     * when there is no context or no top-level forward.
     */
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
            // Fold in forward-internal attachment meta (rows tagged forwardModelDatabaseId).
            attachmentsMeta = readSource.attachment
                .getAllObjects(DBAttachmentModel.forwardModelDatabaseId.eq(fm.databaseId))
                .map { attachmentMeta(it) },
            nested = nested,
        )
    }

    /**
     * The forwarded message's own sentAt. A [ForwardModel] carries two timestamps: [ForwardModel.id]
     * is the wire `Forward.id`, the original message's sentAt, and is what every platform keys a
     * forward node by; [ForwardModel.serverTimestamp] is the server's receipt time and is often 0 on
     * a forwarded sub-node. Exporting the latter produced `sentAt=0` nodes that iOS refuses whole.
     * The fallback only matters for a row whose id was never set.
     */
    internal fun forwardSentAt(fm: ForwardModel): Long = fm.id.takeIf { it > 0 } ?: fm.serverTimestamp

    /**
     * Collect every forward-owned attachment row for a message, tagged with its host message and its
     * portable owner identity, so the exporter can emit them as byte-carrying records. Quotes are
     * meta-only and are never collected here. Owner identity must use the same `{author, sentAt}`
     * composite [forwardOf] writes and the importer re-links by — [forwardSentAt], never the raw
     * server timestamp, or the attachment hangs on nothing.
     */
    fun subStructureAttachments(readSource: MigrationReadSource, m: MessageModel): List<OwnedAttachment> {
        val out = ArrayList<OwnedAttachment>()
        // Forward-internal attachments — walk the whole forward tree of this message.
        m.forwardContextDatabaseId?.let { fcId ->
            readSource.forward.getAllObjects(DBForwardModel.forwardContextDatabaseId.eq(fcId)).forEach { fm ->
                readSource.attachment
                    .getAllObjects(DBAttachmentModel.forwardModelDatabaseId.eq(fm.databaseId))
                    .forEach { am ->
                        out += OwnedAttachment(am, AttachmentOwner.FORWARD, fm.author, forwardSentAt(fm), hostMessageId = m.id)
                    }
            }
        }
        // Quote preview attachments are not collected: quotes travel meta-only via
        // NeutralQuote.attachmentsMeta, and importers rebuild the thumbnails in a backfill pass.
        return out
    }

    /**
     * A forward-owned attachment row, the message it is rendered under, and its portable owner
     * identity within that message's forward tree.
     */
    data class OwnedAttachment(
        val model: AttachmentModel,
        val owner: AttachmentOwner,
        val ownerAuthor: String,
        val ownerSentAt: Long,
        /** stableId of the host message — what [NeutralAttachment.messageRef] carries. */
        val hostMessageId: String,
    )


    /** Local Gson for parsing the notify JSON; pure object → no Hilt/globalServices dependency. */
    private val gson = Gson()
}
