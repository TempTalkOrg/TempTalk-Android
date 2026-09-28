package com.difft.android.migration.v2.importer

import android.util.Base64
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.RealSource
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.common.SendType
import com.difft.android.migration.v2.PerConversationTally
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.model.SendState
import com.difft.android.migration.v2.readtime.ReadLadder
import com.difft.android.migration.v2.restore.AttachmentFileNaming
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.RoomModel
import java.util.UUID

/**
 * Consumes the neutral record stream and materializes the entities into a target WCDB instance via
 * [WcdbWriteTarget]. The target is a fresh staging DB, distinct from the live one.
 *
 * Local autoincrement PKs are not portable and the neutral model carries none. Each sub-structure
 * folded into a [NeutralMessage] is re-exploded into its own target row, the target assigns a fresh
 * PK, and the message's FK columns are set to those PKs before the message row is inserted; forward
 * trees reconnect children the same way. Attachments, mentions and reactions reference their message
 * by its portable stable string id, so they need no remap. No source local PK is ever trusted.
 *
 * Records are consumed one at a time and writes are grouped into transactions of [BATCH_SIZE], so a
 * large import commits incrementally rather than as one giant transaction.
 *
 * Attachment bytes are not streamed here — the reader writes them through its own byte target — but
 * the on-disk location is this importer's concern. The app re-derives an attachment's path at load
 * time from `attachment/<localId>/`, so the importer mints a fresh [AttachmentModel.localId] per
 * restored attachment; nothing about the address is carried in the container. A byte frame arrives
 * keyed only by `contentRef` while the fileName lives on the record, which always precedes its
 * frame, so the importer records an [AttachmentPlacement] per contentRef as each record is processed
 * and the byte target consults [placementFor]. A record with no fileName, or a pointer-only one,
 * records no placement but still gets a localId, so a later re-download has an address to write to.
 */
class NeutralImporter(
    private val target: WcdbWriteTarget,
    /**
     * Resolves an attachment directory from its key — the same function the app uses at load time, so
     * restored bytes land where the app looks for them. The key is the attachment's `localId` and the
     * importer appends the fileName. Injectable so tests can supply a fake off-device.
     */
    private val messageAttachmentDir: (ownerKey: String) -> String = FileUtil::getMessageAttachmentFilePath,
    /**
     * The migrating account's own uid, used by [RoomStateRecomputer] to exclude own-sent messages from
     * the unread count — the role `globalServices.myId` plays on the live path, which a net-install
     * restore has not established yet. Blank means no exclusion, which over-counts but never under-counts.
     */
    private val selfUid: String = "",
    /**
     * Preview-string source for the post-import room-state recompute. Injectable so tests can supply a
     * fake without the resources chain, which is unavailable off-device.
     */
    private val previewStrings: RoomStateRecomputer.PreviewStrings = RoomStateRecomputer.PreviewStrings.ResUtilsBacked,
    /**
     * Sink for the account identity record. Identity material is never written to the staging DB: it
     * goes straight to the restore orchestrator, which installs it into secure storage before promote
     * and therefore before login would publish one. Null drops the record, which is correct for
     * callers that only materialize DB rows.
     */
    private val identitySink: ((NeutralIdentity) -> Unit)? = null,
    /**
     * Sink for the session-credential record (same-device no-login handoff). Memory-only, like
     * [identitySink] and for the same reason: it is live credential material, never a DB row. Null
     * drops the record, which is correct for every consumer that is not a credential-handoff restore.
     */
    private val credentialSink: ((NeutralCredential) -> Unit)? = null,
    /**
     * Called with the running total of committed records after every batch — the figure a peer may
     * read as progress. Not per record: one call per transaction is what the count actually means.
     */
    private val onImported: (Long) -> Unit = {},
) {

    /** Pending inserts buffered before a transaction flush. */
    private val pending = ArrayList<() -> Unit>(BATCH_SIZE)
    private var imported = 0

    /** Local Gson for synthesizing a minimal notify JSON when only displayText travels. */
    private val gson = Gson()

    /**
     * Device number written into a rebuilt screenshot RealSource. The value is inert — Android's
     * renderer only tests presence and the wire field is not read back — but the struct requires one.
     */
    private val DEFAULT_DEVICE_ID = 1

    /**
     * Owner identity to reconstructed local PK, populated as each message re-explodes its forward and
     * quote rows. A sub-structure attachment record arrives after its owning message, so its FK target
     * is already here by the time the attachment is enqueued. Keyed by the portable `{author, sentAt}`
     * composite, since local PKs are not portable.
     */
    private val forwardPkByIdentity = HashMap<OwnerKey, Long>()

    private data class OwnerKey(val author: String, val sentAt: Long)

    /**
     * `contentRef` to canonical on-disk [AttachmentPlacement]. Populated synchronously in
     * [enqueueAttachment], not inside the deferred transaction lambda, because the byte frame arrives
     * immediately after the record and before the next flush.
     */
    private val placementByContentRef = HashMap<String, AttachmentPlacement>()

    /**
     * The canonical relative on-disk placement of a restored attachment's bytes,
     * `<localId>/<fileName>`. The byte target writes into this relative subtree under the staging
     * attachment dir, so the promote dir-move lands them where the app reconstructs the path at load
     * time.
     *
     * @param ownerKey the directory key: the attachment's freshly minted `localId`.
     */
    data class AttachmentPlacement(val ownerKey: String, val fileName: String) {
        /** Canonical relative path `<ownerKey>/<fileName>` under the attachment dir. */
        val relativePath: String get() = "$ownerKey/$fileName"
    }

    /**
     * The canonical placement recorded for [contentRef], or null if none (no byte frame expected,
     * or the owner key was unresolvable). The byte target calls this when opening the destination
     * stream for a byte frame so it can write into the canonical `<ownerKey>/<fileName>` subtree.
     */
    fun placementFor(contentRef: String): AttachmentPlacement? = placementByContentRef[contentRef]

    /**
     * Diagnostic: message rows inserted per conversationRef, and the conversationRefs upserted as
     * rooms. Accumulated from the same inserts performed below, so the import log can be diffed
     * against the export log to pinpoint a drop stage. Counts only, never message bodies.
     */
    private val importedMsgByConversation = HashMap<String, Int>()

    /** Conversations of the messages in the batch being written; tallied only once the batch commits. */
    private val batchConvRefs = ArrayList<String>()
    private val roomsUpserted = HashSet<String>()

    /**
     * Messages whose [ContentKind] decoded to [ContentKind.UNKNOWN] are skipped whole; their stableIds
     * are recorded here so their attachment records are skipped too and their byte frames discarded
     * via [isSkippedBytes]. The frame must still be consumed to keep the stream position — the byte
     * target swallows it instead of writing a file.
     */
    private val skippedMessageIds = HashSet<String>()
    private val skippedContentRefs = HashSet<String>()
    private var skippedUnknownKind = 0
    private var skippedUnknownOwner = 0
    private var skippedCascadeAttachments = 0
    private var recordFailures = 0

    /**
     * Attachment refs whose byte frame never landed, collected until [finish]. The row is inserted
     * optimistically with the "bytes present" status while its frame is still pending in the batch,
     * so the correction can only be applied once the inserts have flushed.
     */
    private val bytesAbsentRefs = LinkedHashSet<String>()
    private var bytesAbsent = 0

    /** True when [contentRef]'s bytes belong to a skipped record and must be discarded, not written. */
    fun isSkippedBytes(contentRef: String): Boolean = contentRef in skippedContentRefs

    /**
     * The bytes behind [contentRef] — an attachment record's stableId — never reached disk: the frame
     * was empty, could not be opened, or failed mid-write. A skipped record's frame is discarded by
     * design and needs no correction; anything else has its row demoted in [finish], so the app
     * re-downloads from the pointer instead of opening a file that is not there. Idempotent.
     */
    fun onAttachmentBytesAbsent(contentRef: String) {
        if (contentRef in skippedContentRefs) return
        bytesAbsentRefs.add(contentRef)
    }

    /**
     * Handle one decoded [NeutralRecord]. Call once per record in stream order; call [finish] after
     * the stream ends to flush the final partial batch.
     *
     * A single record failing to map or enqueue is counted and skipped; it never aborts the import.
     * Whole-import failure is reserved for structural errors, which the reader raises, not this.
     */
    fun onRecord(record: NeutralRecord) {
        try {
            when (record) {
                is NeutralRecord.Message -> {
                    // An unknown contentKind is a designed silent skip with cascade: never a
                    // placeholder row, never an abort.
                    if (record.value.contentKind == ContentKind.UNKNOWN) {
                        skippedMessageIds.add(record.value.stableId)
                        skippedUnknownKind++
                        return
                    }
                    enqueueMessage(record.value)
                }
                is NeutralRecord.Attachment -> {
                    // Cascade: attachment of a skipped message → skip the record, discard the bytes.
                    if (record.value.messageRef in skippedMessageIds) {
                        skippedContentRefs.add(record.value.stableId)
                        skippedCascadeAttachments++
                        return
                    }
                    // An owner this build does not know (Gson decodes it to null; QUOTE is reserved and
                    // never emitted) is skipped with its bytes, not failed.
                    @Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
                    if ((record.value.owner as AttachmentOwner?) == null) {
                        skippedContentRefs.add(record.value.stableId)
                        skippedUnknownOwner++
                        L.w { "[MigrationV2][Import] attachment id=${record.value.stableId} has an unknown owner — skipped" }
                        return
                    }
                    enqueueAttachment(record.value)
                }
                is NeutralRecord.ConversationLocal -> enqueueConversation(record.value)
                is NeutralRecord.GroupKey -> enqueue { target.insertGroupKey(groupKey(record.value)) }
                // Routed out of the DB path entirely — no enqueue, no staging write. Never logged:
                // the record is key material.
                is NeutralRecord.Identity -> identitySink?.invoke(record.value)
                // Same memory-only routing as Identity, same reason: credential material never
                // reaches the staging DB, and is never logged.
                is NeutralRecord.Credential -> credentialSink?.invoke(record.value)
            }
        } catch (e: Exception) {
            recordFailures++
            L.w { "[MigrationV2][Import] record skipped after mapping failure: ${e.stackTraceToString()}" }
        }
    }

    /** Flush any remaining buffered inserts. MUST be called once after the stream is consumed. */
    fun finish() {
        flush()
        applyBytesAbsent()
        applyReadLadders()
        // Derived room state (preview, unread, lastActiveTime) is recomputed here because the import
        // bypasses WCDBUpdateService. Best-effort: a failure here must not discard the imported rows.
        try {
            RoomStateRecomputer.recompute(target, selfUid, previewStrings)
        } catch (e: Exception) {
            L.e { "[MigrationV2][Import] room-state recompute failed, rows kept: ${e.stackTraceToString()}" }
        }
        L.i {
            "[MigrationV2][Import] done imported=$imported skippedUnknownKind=$skippedUnknownKind " +
                "skippedUnknownOwner=$skippedUnknownOwner " +
                "skippedCascadeAttachments=$skippedCascadeAttachments bytesAbsent=$bytesAbsent " +
                "recordFailures=$recordFailures"
        }
        // Per-conversation diagnostic from the same inserts above. Diff it against the export line
        // and the displayed conversations to see whether one dropped at export, import or display.
        L.i {
            "[MigrationV2][Import] per-conversation roomsUpserted=${roomsUpserted.size} " +
                "msgByConversation=${PerConversationTally.format(importedMsgByConversation)}"
        }
    }

    /**
     * Demote every row whose bytes never landed to the pointer-only status. Runs after the inserts
     * have flushed, because the row is written in a batch that was still pending when its frame
     * failed. Best-effort per row: a failing update must not discard the import.
     */
    private fun applyBytesAbsent() {
        bytesAbsentRefs.forEach { ref ->
            try {
                target.markAttachmentBytesAbsent(ref)
                bytesAbsent++
            } catch (e: Exception) {
                L.w { "[MigrationV2][Import] attachment id=$ref bytes-absent mark failed: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * Per-conversation read ladders, kept from their ConversationLocal records until [finish] can
     * expand them over the message rows.
     */
    private val laddersByRoom = LinkedHashMap<String, ReadLadder>()

    /**
     * Messages that arrived carrying an explicit `expireStartedAt`, the explicit 0 included. Design
     * §2.5-5 reconciles this against the exporter's `exceptions=` figure: the two must be equal
     * (barring rows counted in `recordFailures`), or a container lost anchors in transit.
     */
    private var exceptionsApplied = 0

    /**
     * Expand each conversation's ladder onto its incoming message rows, reproducing what the live
     * read path does on every watermark arrival (`DBMessageStore.updateMessageReadTime`).
     *
     * Rungs are applied by ascending `readAt` so the write-once guard leaves each row with the
     * smallest covering read time — the ladder lookup rule, expressed as a sequence of writes. The
     * watermark goes last, giving the rows between the top rung and the watermark their fallback. The
     * update never touches the NOT_STARTED sentinel or a row that already carries an anchor.
     *
     * Best-effort per room, like the room-state recompute: a failing room must not discard the import.
     */
    private fun applyReadLadders() {
        var rooms = 0
        var rows = 0
        var failures = 0
        laddersByRoom.forEach { (roomId, ladder) ->
            try {
                ladder.rows.sortedBy { it.readAt }.forEach { row ->
                    target.markReadUpTo(roomId, row.maxServerTime, row.readAt, selfUid)
                    rows++
                }
                val watermark = ladder.watermark
                val watermarkReadAt = ladder.effectiveWatermarkReadAt()
                if (watermark != null && watermarkReadAt != null) {
                    target.markReadUpTo(roomId, watermark.maxServerTime, watermarkReadAt, selfUid)
                }
                rooms++
            } catch (e: Exception) {
                failures++
                L.w { "[MigrationV2][Import] read ladder skipped for one room: ${e.stackTraceToString()}" }
            }
        }
        L.i {
            "[MigrationV2][Import] read ladders applied rooms=$rooms rows=$rows " +
                "exceptionsApplied=$exceptionsApplied failures=$failures"
        }
    }

    // ── enqueue + flush ───────────────────────────────────────────────────────────────────

    private fun enqueue(op: () -> Unit) {
        pending.add(op)
        if (pending.size >= BATCH_SIZE) flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        try {
            target.runInTransaction {
                batch.forEach { it() }
            }
            imported += batch.size
            commitTallies()
            onImported(imported.toLong())
        } catch (e: Exception) {
            // One bad write rolled the whole batch back. Replay it one record per transaction so the
            // fault stays with that record instead of taking the rest of the batch down with it.
            L.w { "[MigrationV2][Import] batch of ${batch.size} rolled back, replaying singly: ${e.message}" }
            batchConvRefs.clear()
            batch.forEach { op ->
                try {
                    target.runInTransaction(op)
                    imported++
                    commitTallies()
                } catch (single: Exception) {
                    recordFailures++
                    batchConvRefs.clear()
                    L.w { "[MigrationV2][Import] record skipped after write failure: ${single.stackTraceToString()}" }
                }
            }
            onImported(imported.toLong())
        }
    }

    /** Moves the committed batch's per-conversation counts into the diagnostic tally. */
    private fun commitTallies() {
        batchConvRefs.forEach { importedMsgByConversation[it] = (importedMsgByConversation[it] ?: 0) + 1 }
        batchConvRefs.clear()
    }

    // ── message + folded sub-structures ─────────────────────────────────────────────────────

    private fun enqueueMessage(n: NeutralMessage) {
        // Counted here rather than inside the deferred write: a rolled-back batch replays every op
        // singly, so a tally kept in the lambda would count each surviving row twice. A row that
        // then fails to write is reported separately as recordFailures.
        if (n.expireStartedAt != null) exceptionsApplied++
        enqueueMessageWrite(n)
    }

    private fun enqueueMessageWrite(n: NeutralMessage) = enqueue {
        // 1. Re-explode quote → fresh QuoteModel row; capture new PK for FK + owner-identity map.
        val quotePk: Long? = n.quote?.let { q ->
            val pk = target.insertQuote(QuoteModel().apply {
                id = q.sentAt          // QuoteModel.id == quoted message sentAt (composite identity).
                author = q.author
                text = q.textPreview ?: ""
            })
            // Quote attachments travel meta-only, with no records or byte frames. Materialize the
            // meta as byte-less attachment rows so the preview metadata survives; thumbnails are the
            // post-import backfill's job.
            q.attachmentsMeta.forEach { meta ->
                target.insertAttachment(AttachmentModel().apply {
                    id = meta.stableId
                    quoteModelDatabaseId = pk
                    contentType = meta.contentType
                    fileName = meta.fileName ?: ""
                })
            }
            pk
        }

        // 2. Re-explode forward context → ForwardContext + Forward rows; capture context PK for FK.
        //    A merged-forward holds N PARALLEL top-level forwards (roots); each is inserted as a
        //    top-level row (parentPk=null), NOT folded into the first root, so the card's
        //    `forwards.size` round-trips as N. isFromGroup is restored onto the ForwardContextModel.
        val forwardContextPk: Long? = n.forwardContext?.let { fc ->
            val fcPk = target.insertForwardContext(ForwardContextModel().apply { isFromGroup = fc.isFromGroup })
            var rows = 0
            fc.roots.forEach { root ->
                rows += insertForwardTree(root, forwardContextPk = fcPk, parentPk = null)
            }
            // Diagnostic: without a real autoincrement PK written back, the message-to-forward FK
            // cannot reconnect on read-back, which surfaces a target write-back gap.
            L.i { "[MigrationV2][Import] forward msg=${n.stableId} fcPk=$fcPk roots=${fc.roots.size} rows=$rows" }
            fcPk
        }

        // 3. Insert the message with FKs remapped to the freshly-assigned target PKs.
        target.insertMessage(MessageModel().apply {
            id = n.stableId
            roomId = n.conversationRef
            // roomType is filtered on alongside roomId by the chat query; carry it or a group is empty.
            roomType = n.roomType
            fromWho = n.sender
            // System messages rebuild `messageText` from the raw notify payload for same-platform
            // fidelity; a container carrying only displayText gets a minimal synthesized notify JSON
            // so that text still renders. Non-system messages use body.
            messageText = if (n.contentKind == ContentKind.SYSTEM) systemMessageText(n) else n.body
            timeStamp = n.sentAt
            systemShowTimestamp = n.serverAt
            receivedTimeStamp = n.receivedAt ?: 0L
            expiresInSeconds = n.expireTimer
            type = messageType(n)
            // `sendState` describes an outgoing send and nothing else, so an incoming message takes
            // the terminal delivered state like any received one — never Sending(0), which is what an
            // absent state maps to if read as a zero and which renders a spinner that never resolves.
            sendType = if (n.direction == MessageDirection.OUTGOING && n.sendState == SendState.FAILED) {
                SendType.SentFailed.rawValue
            } else {
                SendType.Sent.rawValue
            }
            mode = if (n.confidential) 1 else 0
            playStatus = if (n.platformExtras[EXTRA_PLAYED] == "false") PLAY_STATUS_NOT_PLAYED else 0
            // readTime is the expiry anchor column. An explicit anchor wins; an explicit 0 becomes the
            // NOT_STARTED sentinel, which no archive branch matches and the ladder expansion skips; an
            // absent anchor leaves an outgoing row at its send time and an incoming row at 0, where
            // the ladder expansion in finish() fills it in.
            readTime = when {
                n.expireStartedAt == null -> if (n.direction == MessageDirection.OUTGOING) n.serverAt else 0L
                n.expireStartedAt > 0L -> n.expireStartedAt
                else -> READ_TIME_NOT_STARTED
            }
            sequenceId = n.sequenceId ?: 0L
            notifySequenceId = n.notifySequenceId ?: 0L
            criticalAlertType = n.criticalAlertType ?: 0
            quoteDatabaseId = quotePk
            forwardContextDatabaseId = forwardContextPk
            // Derived, not carried: the send path builds this column by joining mention uids with
            // ';' and no reader consumes it, so rebuilding from the first-class `mentions` keeps the
            // row faithful without spending contract space on a redundant copy.
            atPersons = n.mentions.takeIf { it.isNotEmpty() }?.joinToString(";") { it.uid }
            // Rebuild Android's own column shapes from the first-class fields. They must not ride in
            // `platformExtras`, whose `android.` prefix every other platform ignores.
            receiverIds = n.recipients.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
            screenShotJson = n.screenShot?.let {
                // The renderer only tests presence and the wire value is self-referential, so
                // rebuilding RealSource from the carried pair reproduces the original exactly.
                gson.toJson(ScreenShot(RealSource(it.author, DEFAULT_DEVICE_ID, it.sentAt, n.serverAt)))
            }
        })
        // Diagnostic: tally the message against its conversation once the batch commits (see flush).
        batchConvRefs.add(n.conversationRef)

        // 4. Mentions + reactions reference the message by stable string id (no PK remap).
        n.mentions.forEach { mn ->
            target.insertMention(MentionModel().apply {
                messageId = n.stableId
                forwardModelDatabaseId = null
                start = mn.start
                length = mn.length
                uid = mn.uid
                // Same domain as the live row: the wire proto value, which re-sending and forwarding
                // feed back into the proto. Unknown reads as INTERNAL.
                type = mn.type ?: MENTION_TYPE_INTERNAL
            })
        }
        // Android persists active reactions only — cancellation is a row delete, with no tombstone —
        // so a tombstoned reaction from another platform would become a phantom active row.
        n.reactions.filterNot { it.removed }.forEach { r ->
            target.insertReaction(ReactionModel().apply {
                messageId = n.stableId
                emoji = r.emoji
                uid = r.fromUid
                timeStamp = r.sentAt
            })
        }

        // 5. Contact cards (see SharedContactImport.kt).
        target.insertSharedContactsOf(n)
    }

    /**
     * Reconstruct the Android notify `messageText` JSON for a system message. Prefers
     * [NeutralMessage.systemPayload], the server notify JSON verbatim, which the read-back path
     * re-derives `showContent` from. When the exporter could not provide one, synthesize a minimal
     * `{"showContent": …, "notifyType": …}` so the rendered text still shows, falling back to the
     * bare displayText.
     */
    private fun systemMessageText(n: NeutralMessage): String? {
        n.systemPayload?.takeIf { it.isNotEmpty() }?.let { return it }
        val displayText = n.displayText ?: return n.body
        val json = JsonObject().apply {
            addProperty("showContent", displayText)
            n.systemType?.toIntOrNull()?.let { addProperty("notifyType", it) }
        }
        return gson.toJson(json)
    }

    /**
     * Recursively insert a forward tree, reconnecting parent→child via the freshly-assigned target
     * PKs. Each [NeutralForward] becomes one [ForwardModel] row; its [NeutralForward.nested] become
     * child rows whose `parentForwardModelDatabaseId` is this node's new PK.
     */
    private fun insertForwardTree(node: NeutralForward, forwardContextPk: Long, parentPk: Long?): Int {
        val myPk = target.insertForward(ForwardModel().apply {
            id = node.sentAt
            author = node.author
            text = node.body ?: ""
            serverTimestamp = node.sentAt
            forwardContextDatabaseId = forwardContextPk
            parentForwardModelDatabaseId = parentPk
        })
        // Record this forward node's PK by its portable {author, sentAt} so a forward-owned
        // attachment record (arriving later in the stream) can re-link via forwardModelDatabaseId.
        forwardPkByIdentity[OwnerKey(node.author, node.sentAt)] = myPk
        var count = 1
        node.nested.forEach { child ->
            count += insertForwardTree(child, forwardContextPk = forwardContextPk, parentPk = myPk)
        }
        return count
    }

    private fun enqueueAttachment(n: NeutralAttachment) {
        // Compute the owner key and placement eagerly, outside the deferred transaction lambda: the
        // byte frame arrives immediately after this record and before the next flush, so
        // placementByContentRef must be ready now.
        // Every restored attachment gets a fresh localId and owns the directory `attachment/<localId>/`,
        // the app's single addressing rule. It is generated here and never carried in the container,
        // because localId is local identity and each platform owns its own on-disk layout.
        val localId = UUID.randomUUID().toString()
        // One name for the row and the bytes: the reader resolves `directory + fileName`, so what the
        // byte target writes under must be exactly what the row says.
        val rowFileName = attachmentFileName(n, localId)
        if (!n.pointerOnly) {
            placementByContentRef[n.stableId] = AttachmentPlacement(localId, rowFileName)
        }

        enqueue {
            // Forward owners are re-linked by the portable {author, sentAt} captured when the owning
            // message re-exploded its forward rows; that message always precedes its sub-structure
            // attachments in the stream.
            var fwdFk: Long? = null
            var msgRef: String? = null
            when (n.owner) {
                AttachmentOwner.MESSAGE -> msgRef = n.messageRef
                AttachmentOwner.FORWARD -> {
                    fwdFk = ownerPk(forwardPkByIdentity, n)
                    if (fwdFk == null) L.w {
                        "[MigrationV2][Import] forward attachment id=${n.stableId} owner not found author=${n.ownerAuthor} sentAt=${n.ownerSentAt}"
                    }
                }
            }
            target.insertAttachment(AttachmentModel().apply {
                id = n.stableId
                messageId = msgRef
                forwardModelDatabaseId = fwdFk
                contentType = n.contentType
                size = n.size.toInt()
                width = n.width ?: 0
                height = n.height ?: 0
                totalTime = n.durationMs
                flags = n.flags
                amplitudes = n.amplitudes
                fileName = rowFileName
                // Pointer-only attachments carry no bytes, so restore the server pointer: authorityId
                // and key let the destination fetch and decrypt.
                authorityId = n.serverAttachmentId
                n.encryptionKey?.let { key = Base64.decode(it, Base64.NO_WRAP) }
                // digest restored alongside key: the download/decrypt gate requires both non-null.
                n.digestBase64?.let { digest = Base64.decode(it, Base64.NO_WRAP) }
                // Open-vs-download signal: bytes present means downloaded.
                status = if (n.pointerOnly) 0 else ATTACHMENT_STATUS_SUCCESS
                // The row's own address. Set even when pointer-only: a later re-download writes its
                // bytes to this same directory, so the address must exist before the file does.
                this.localId = localId
            })
        }
    }

    /**
     * The attachment's file name, which is also its on-disk name — the same rule the live receive path
     * applies to an incoming pointer: the carried name when usable, else the server attachment id,
     * else the portable id.
     *
     * A blank name must never reach the row. The reader resolves `directory + fileName`, so a blank
     * name resolves to the directory itself: it exists, so nothing re-downloads, and the ciphertext
     * lives at `<directory>.encrypt` for writer and reader alike so playback works — yet every decoder
     * handed that path fails, leaving a voice bubble with no waveform and no duration. A name the byte
     * target would have to substitute on disk is replaced the same way, so the row and the file can
     * never disagree.
     */
    private fun attachmentFileName(n: NeutralAttachment, localId: String): String {
        val carried = n.fileName?.trim().orEmpty()
        if (carried.isNotEmpty() && FileUtil.isFileNameValid(carried) && AttachmentFileNaming.isUsable(carried)) {
            return carried
        }
        val fallback = listOfNotNull(n.serverAttachmentId?.toString(), n.stableId)
            .firstOrNull { it.isNotBlank() && AttachmentFileNaming.isUsable(it) }
            ?: localId
        L.i { "[MigrationV2][Import] attachment id=${n.stableId} fileName absent or unusable — stored as $fallback" }
        return fallback
    }

    /** Resolve a forward owner PK from its `{author, sentAt}` identity, or null if absent. */
    private fun ownerPk(map: Map<OwnerKey, Long>, n: NeutralAttachment): Long? {
        val author = n.ownerAuthor ?: return null
        val sentAt = n.ownerSentAt ?: return null
        return map[OwnerKey(author, sentAt)]
    }

    private fun enqueueConversation(n: com.difft.android.migration.v2.model.NeutralConversationLocal) {
        // Normalized here rather than in finish(), where the record is no longer in hand. Applying it
        // has to wait for the messages, which arrive after this record.
        laddersByRoom[n.conversationRef] = ReadLadder.fromWire(n)
        enqueue {
            target.upsertRoom(RoomModel().apply {
                roomId = n.conversationRef
                // roomType is intrinsic and carried, so a migrated group renders as a group rather
                // than a 1-on-1. Derived fields are recomputed post-import by RoomStateRecomputer.
                roomType = n.roomType
                pinnedTime = n.pinnedTime
                readPosition = n.readPosition?.maxServerTime ?: 0L
            })
            roomsUpserted.add(n.conversationRef)
            // others' read positions (read_info); ReadInfoImport.kt
            target.insertParticipantReadPositionsOf(n, selfUid)
        }
    }

    private fun groupKey(n: com.difft.android.migration.v2.model.NeutralGroupKey) =
        GroupCryptoKeysModel().apply {
            gid = n.groupId
            rGroup = n.rGroup
            keyVersion = n.keyVersion
        }

    // ── enum / type mapping (inverse of NeutralProjections) ─────────────────────────────────

    private fun messageType(n: NeutralMessage): Int = when (n.contentKind) {
        ContentKind.SYSTEM -> MessageModel.TYPE_NOTIFY
        ContentKind.FILE, ContentKind.IMAGE, ContentKind.VIDEO, ContentKind.VOICE -> MessageModel.TYPE_ATTACHMENT
        else -> MessageModel.TYPE_TEXT
    }

    companion object {
        /** Inserts per transaction. */
        const val BATCH_SIZE = 500

        /** `DataMessage.Mention.Type.INTERNAL` — the wire default when the container carries none. */
        private const val MENTION_TYPE_INTERNAL = 0

        /** `AttachmentStatus.SUCCESS.code` — bytes present ⇒ downloaded (render shows "open"). */
        private const val ATTACHMENT_STATUS_SUCCESS = 3

        /**
         * `MessageModel.readTime` sentinel for a message the source says has explicitly not started
         * its expiry countdown. Both archive branches test `> 0` or `== 0`, so a negative value
         * expires by neither, and only a real read event (`DBMessageStore.updateMessageReadTime`,
         * whose guard is `readTime <= 0`) releases it.
         */
        private const val READ_TIME_NOT_STARTED = -1L

        /** `MessageModel.playStatus`: 1 = an unplayed voice note. */
        private const val PLAY_STATUS_NOT_PLAYED = 1

        /** Frozen `platformExtras` key for an unplayed voice note; only "false" is ever carried. */
        private const val EXTRA_PLAYED = "android.played"
    }
}
