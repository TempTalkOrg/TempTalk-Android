package com.difft.android.migration.v2.export

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.base.utils.FileUtil
import com.difft.android.migration.v2.PerConversationTally
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.AttachmentBytes
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.readtime.ReadLadder
import com.difft.android.migration.v2.readtime.ReadLadderBuilder
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReadInfoModel
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.migration.v2.container.RecordJsonCodec
import com.difft.android.migration.v2.container.TransferProgress
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take

/**
 * Streams the must-migrate entities from a [MigrationReadSource], projecting each to a
 * [NeutralRecord]. Emits a cold [Flow] so the container writer pulls records on demand, keeping
 * memory constant; messages are paged by ascending `databaseId` in batches of [PAGE_SIZE].
 *
 * Emission order is group keys, then per conversation a ConversationLocal followed by its messages,
 * each message immediately followed by its attachment records — so the writer can put an
 * attachment's bytes right after its record. Non-friend remarks are not exported; the target
 * re-syncs them from the server after login.
 *
 * Attachment bytes: every type is stored at rest as the ciphertext `<path>.encrypt`, and the
 * exporter carries that file verbatim — the destination decrypts on read with the migrated
 * `encryptionKey`, so the ciphertext travels self-contained. Attachments are downloaded lazily, so
 * an absent file is expected rather than an error: those travel `pointerOnly` with the server
 * pointer and no byte frame, and the destination re-downloads. Contact and group avatars are not
 * `AttachmentModel` rows and never enter this export.
 */
interface NeutralExporter {

    /**
     * A cold [Flow] of every must-migrate [NeutralRecord]. Collecting it performs the WCDB reads;
     * collect it inside a coroutine on an IO dispatcher (the container writer already hops to IO).
     */
    fun export(): Flow<NeutralRecord>

    /**
     * Byte-source bound to this exporter's attachment refs. Pass to the container writer so it
     * can stream attachment bytes. Resolution uses the same `contentRef` (attachment stableId)
     * the [export] flow assigns to each [NeutralRecord.Attachment].
     */
    fun attachmentByteSource(): AttachmentByteSource

    /** The attachment-bytes term of [preCount], kept for callers that only want that. */
    suspend fun estimateCarriedAttachmentBytes(): Long

    /**
     * Cheap pre-count of exactly what [export] will emit, carried in the manifest so both ends can
     * draw a determinate bar with an exact endpoint. Uses the same row enumeration and byte-plan
     * logic as [export] but reads no file contents and builds no projections.
     */
    suspend fun preCount(): ContainerTotals

    companion object {
        /** Message page size for streaming reads. */
        const val PAGE_SIZE = 500L
    }
}

/**
 * Where an attachment's bytes come from at export time, recorded per stableId during the record pass
 * so the byte source reproduces the same decision. The at-rest ciphertext `<path>.encrypt` is carried
 * verbatim, so no plaintext ever touches the container.
 */
private data class BytePlan(val encryptPath: String)

@Singleton
class NeutralExporterImpl @Inject constructor(
    private val readSource: MigrationReadSource,
) : NeutralExporter {

    /**
     * The exporting account's own uid. An in-flight outgoing row (`sendType == Sending`) is
     * indistinguishable from an incoming one by `sendType` alone, so the projection uses
     * `fromWho == selfUid` to classify it as outgoing and export it as `sendState=FAILED`. A property
     * rather than a constructor parameter because Hilt cannot inject a String; the empty default
     * falls back to sendType-only classification.
     */
    var selfUid: String = ""

    /**
     * Whether attachment BYTES travel. When false every attachment still travels as a record with its
     * server pointer (`pointerOnly`), so the destination renders it and can re-download it while the
     * server still has it — only the bytes stay behind. Voice notes (the VOICE flag on an audio
     * attachment) are the exception and are always carried: they are the message, not a file attached
     * to one. [preCount] follows the same rule, so the manifest totals and both progress bars stay
     * honest about what is actually sent.
     */
    var includeAttachments: Boolean = true

    /**
     * Resolves the canonical attachment directory `attachment/<ownerKey>/`, mirroring the app's
     * load-time reconstruction. Injectable so tests can supply a temp-dir fake, since the production
     * chain calls `getExternalFilesDir`, which is unavailable off-device.
     */
    internal var attachmentDir: (String) -> String = FileUtil::getMessageAttachmentFilePath

    // Populated as the export flow walks attachments, so the byte-source can reproduce each
    // attachment's byte plan. Bounded by the attachment count (refs + small plans, no bytes).
    private val refToPlan = HashMap<String, BytePlan>()

    override fun export(): Flow<NeutralRecord> = flow {
        L.i { "[MigrationV2][Export] start" }
        refToPlan.clear()

        // 1. GroupKeys — small key-value table, read whole.
        var groupKeyCount = 0
        readSource.groupCryptoKeys.allObjects.forEach { g ->
            currentCoroutineContext().ensureActive()
            emit(NeutralRecord.GroupKey(NeutralProjections.groupKey(g)))
            groupKeyCount++
        }

        // 2. ConversationLocal — pinned state + the account's own read ladder per room.
        var conversationCount = 0
        // Diagnostic: conversationRefs emitted as ConversationLocal, diffed against the
        // per-conversation message tally to spot rooms with no messages exported.
        val conversationRefSet = HashSet<String>()
        // Rooms the destination would delete the moment it imported them, so carrying them is pure
        // loss: `cleanEmptyRooms` sweeps a non-self room with an empty `lastDisplayContent` once its
        // idle window elapses, and the migrated room inherits the source's `lastActiveTime` so it
        // arrives already past that window. An empty preview means the real content is already gone.
        // Skipping at the source keeps the retention clock a purely local concept.
        val skippedEmptyRooms = HashSet<String>()
        val rooms = readSource.room.allObjects
        rooms.forEach { r ->
            // Saved Messages is never swept (cleanEmptyRooms excludes myId), so an empty self room
            // is still worth carrying for its pinned state / read watermark.
            if (r.roomId != selfUid && r.lastDisplayContent.isNullOrEmpty()) skippedEmptyRooms.add(r.roomId)
        }
        val roomReadPositions = rooms.associate { it.roomId to it.readPosition }
        // The ladder has to be complete before the first ConversationLocal is emitted, so it takes
        // its own pass over the messages ahead of the emitting one below.
        val ladders = buildReadLadders(skippedEmptyRooms, roomReadPositions)
        rooms.forEach { r ->
            currentCoroutineContext().ensureActive()
            if (r.roomId in skippedEmptyRooms) return@forEach
            // Others' read positions for this room drive the outgoing "read by" indicator after
            // restore. Queried here and folded in, so the projection stays source-agnostic.
            val readInfoRows = readSource.readInfo.getAllObjects(DBReadInfoModel.roomId.eq(r.roomId))
            emit(
                NeutralRecord.ConversationLocal(
                    NeutralProjections.conversationLocal(r, readInfoRows, ladders[r.roomId] ?: ReadLadder.EMPTY, selfUid)
                )
            )
            conversationCount++
            conversationRefSet.add(r.roomId)
        }

        // 3. Messages — paged by ascending databaseId, each followed by its attachment records.
        var messageCount = 0
        var attachmentCount = 0
        var pointerOnlyCount = 0
        // Rows whose effective expiry start differs from what the ladder derives, so they travel an
        // explicit anchor. The import log reports the same figure for reconciliation.
        var readTimeExceptions = 0
        // Diagnostic: per-conversation tally accumulated from the messages emitted below, not a
        // separate query, so the export/import/displayed comparison is exact.
        val messagesByConversation = HashMap<String, Int>()
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val sub = SubStructurePage.load(readSource, page)
            for (m in page) {
                currentCoroutineContext().ensureActive()
                // Drop the messages of a skipped empty room too — importing them would recreate the
                // very room we chose not to carry, and they are the expired-marker rows themselves.
                if (m.roomId in skippedEmptyRooms) continue
                val roomId = m.roomId ?: ""
                val neutral = NeutralProjections.message(
                    readSource,
                    m,
                    selfUid,
                    ladders[roomId] ?: ReadLadder.EMPTY,
                    roomReadPositions[roomId] ?: 0L,
                )
                if (neutral.expireStartedAt != null) readTimeExceptions++
                emit(NeutralRecord.Message(neutral))
                messageCount++
                val convRef = m.roomId ?: ""
                messagesByConversation[convRef] = (messagesByConversation[convRef] ?: 0) + 1
                // Emit one Attachment record per attachment row. Decide byte carriage here and
                // record the BytePlan so the byte-source reproduces the exact decision.
                // (a) Normal message attachments — bytes live under attachment/<messageId>/.
                for (am in sub.attachmentsOf(m)) {
                    val neutral = projectNormalAttachment(am, m.id)
                    if (neutral.pointerOnly) pointerOnlyCount++
                    emit(NeutralRecord.Attachment(neutral))
                    attachmentCount++
                }
                // (b) Forward/quote-owned attachments. These rows have a null messageId; their bytes
                //     live under attachment/<authorityId>/<fileName> for single and merged forwards
                //     alike, because Record2MessageFactory keys the forward ChatMessage.id on the
                //     attachment's authorityId, not the host message id. They travel tagged with the
                //     owner's portable {author, sentAt} so the importer can re-link them.
                for (oa in sub.ownedAttachmentsOf(m)) {
                    val neutral = projectOwnedAttachment(oa)
                    if (neutral.pointerOnly) pointerOnlyCount++
                    emit(NeutralRecord.Attachment(neutral))
                    attachmentCount++
                }
            }
            // databaseId is an Int since #913; the cursor paginates as Long, so widen it.
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }

        L.i {
            "[MigrationV2][Export] done groupKeys=$groupKeyCount " +
                "conversations=$conversationCount messages=$messageCount attachments=$attachmentCount " +
                "pointerOnly=$pointerOnlyCount (bytes-carried=${attachmentCount - pointerOnlyCount}) " +
                "skippedEmptyRooms=${skippedEmptyRooms.size}"
        }
        L.i {
            "[MigrationV2][Export] read ladders rooms=${ladders.size} " +
                "rows=${ladders.values.sumOf { it.rows.size }} exceptions=$readTimeExceptions"
        }
        // Per-conversation diagnostic: which conversations had messages exported, computed from
        // the messages emitted above.
        L.i {
            "[MigrationV2][Export] per-conversation rooms=${conversationRefSet.size} " +
                "msgCounts=${PerConversationTally.format(messagesByConversation)}"
        }
    }

    /**
     * One paged pass over the messages, collecting per room the `(serverAt, readTime)` pairs of its
     * incoming rows and folding them into a canonical ladder.
     *
     * Android has no ladder table — it writes the batch `readAt` onto every covered row — so the
     * rungs have to be recovered from the rows themselves. Only two longs per read incoming message
     * are held, never a row, so the pass stays bounded the way the emitting one is.
     */
    private suspend fun buildReadLadders(
        skippedEmptyRooms: Set<String>,
        roomReadPositions: Map<String, Long>,
    ): Map<String, ReadLadder> {
        val readsByRoom = HashMap<String, MutableList<ReadLadderBuilder.IncomingRead>>()
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            for (m in page) {
                val roomId = m.roomId ?: continue
                if (roomId in skippedEmptyRooms) continue
                // Own-sent rows carry a send-time anchor in the same column, not a read moment.
                if (NeutralProjections.isOutgoing(m, selfUid)) continue
                if (m.readTime <= 0L) continue
                readsByRoom.getOrPut(roomId) { ArrayList() }
                    .add(ReadLadderBuilder.IncomingRead(NeutralProjections.serverAt(m), m.readTime))
            }
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }
        return roomReadPositions.filterKeys { it !in skippedEmptyRooms }
            .mapValues { (roomId, readPosition) -> ReadLadderBuilder.build(readsByRoom[roomId].orEmpty(), readPosition) }
    }

    override suspend fun estimateCarriedAttachmentBytes(): Long = preCount().attachmentBytes

    override suspend fun preCount(): ContainerTotals = walk().let {
        ContainerTotals(recordCount = it.records, attachmentCount = it.carried, attachmentBytes = it.carriedBytes)
    }

    /**
     * What the pairing page shows before a send. Attachment figures come from the same walk as
     * [preCount]; record bytes are the count times the average size of a sample of encoded messages —
     * the largest record kind, so the figure errs high — falling back to
     * [TransferProgress.RECORD_WEIGHT_BYTES] when nothing could be sampled.
     */
    suspend fun estimate(): ExportEstimate {
        val w = walk()
        val perRecord = sampleMessageBytes() ?: TransferProgress.RECORD_WEIGHT_BYTES
        return ExportEstimate(
            recordCount = w.records,
            recordBytes = w.records * perRecord,
            attachmentBytes = w.carriedBytes,
            voiceNoteBytes = w.voiceNoteBytes,
        )
    }

    /** Average encoded size of the first [ESTIMATE_SAMPLE_MESSAGES] messages, or null when there are none. */
    private suspend fun sampleMessageBytes(): Long? {
        val codec = RecordJsonCodec()
        var sampled = 0
        var bytes = 0L
        runCatching {
            export().filter { it is NeutralRecord.Message }.take(ESTIMATE_SAMPLE_MESSAGES).collect { record ->
                sampled++
                bytes += codec.encodeRecord(record).toByteArray(Charsets.UTF_8).size
            }
        }.onFailure { e ->
            L.w { "[MigrationV2][Export] record sampling failed, using the nominal weight: ${e.message}" }
        }
        return if (sampled == 0) null else bytes / sampled
    }

    private class Walk(val records: Int, val carried: Int, val carriedBytes: Long, val voiceNoteBytes: Long)

    private suspend fun walk(): Walk {
        // Mirrors export(): the same rooms and their messages are dropped, so on a frozen snapshot
        // the count is exact — the bar's endpoint depends on it.
        var records = readSource.groupCryptoKeys.allObjects.size
        val skippedEmptyRooms = HashSet<String>()
        readSource.room.allObjects.forEach { r ->
            if (r.roomId != selfUid && r.lastDisplayContent.isNullOrEmpty()) skippedEmptyRooms.add(r.roomId) else records++
        }
        var carried = 0
        var carriedBytes = 0L
        var voiceNoteBytes = 0L
        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readSource.message.getAllObjects(
                DBMessageModel.databaseId.gt(cursor),
                DBMessageModel.databaseId.order(Order.Asc),
                NeutralExporter.PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val sub = SubStructurePage.load(readSource, page)
            for (m in page) {
                currentCoroutineContext().ensureActive()
                if (m.roomId in skippedEmptyRooms) continue
                records++
                // (a) Normal message attachments: one record per row, bytes only where a file exists.
                sub.attachmentsOf(m).forEach { am ->
                    records++
                    val len = planFileLength(resolveBytePlan(am, legacyOwnerKey = m.id))
                    if (len > 0L) {
                        carried++
                        carriedBytes += len
                        if (isVoiceNote(am)) voiceNoteBytes += len
                    }
                }
                // (b) Forward-owned attachments — same address probing as export.
                sub.ownedAttachmentsOf(m).forEach { oa ->
                    records++
                    val len = planFileLength(
                        resolveBytePlan(oa.model, legacyOwnerKey = oa.model.authorityId?.toString()),
                    )
                    if (len > 0L) {
                        carried++
                        carriedBytes += len
                    }
                }
            }
            cursor = page.last().databaseId.toLong()
            if (page.size < NeutralExporter.PAGE_SIZE) break
        }
        L.i {
            "[MigrationV2][Export] preCount records=$records carriedAttachments=$carried " +
                "carriedBytes=$carriedBytes voiceNoteBytes=$voiceNoteBytes includeAttachments=$includeAttachments " +
                "skippedEmptyRooms=${skippedEmptyRooms.size}"
        }
        return Walk(records, carried, carriedBytes, voiceNoteBytes)
    }

    /** Length the plan's `.encrypt` file contributes; 0 for a null plan or a vanished file. */
    private fun planFileLength(plan: BytePlan?): Long =
        plan?.let { File(it.encryptPath).takeIf { f -> f.exists() }?.length() } ?: 0L

    // #935: attachment rows are deliberately NOT filtered by a null `id` or null/0 `authorityId`.
    // There are no orphan rows — every insert point sets an owner FK — and a row with neither id is a
    // legitimate quote-preview thumbnail carrying an inline BLOB and no addressable file. Filtering
    // those out leaves the migrated QuoteModel with no attachment, so the target shows no quote media
    // preview. Every row is emitted; one with no local file simply becomes pointer-only.

    /**
     * Project a normal message attachment row and record its byte plan keyed by its contentRef. Bytes
     * resolve at the row's own `attachment/<localId>/` address, falling back to the legacy
     * `attachment/<messageId>/` one for a file the on-device relocation has not moved yet.
     */
    internal fun projectNormalAttachment(am: AttachmentModel, messageId: String?): NeutralAttachment {
        // A normal row can also have a blank `id` (seen on sent gif rows), so it needs the same
        // authorityId fallback the forward path applies. Without it the byte source cannot key the
        // plan, and the writer emits an empty frame while the record still claims bytes are present —
        // leaving the destination a 0-byte file instead of a re-download from the pointer.
        val stableRef = stableAttachmentRef(am)
        val plan = if (stableRef == null) null else resolveBytePlan(am, legacyOwnerKey = messageId)
        if (plan != null && stableRef != null) refToPlan[stableRef] = plan
        return NeutralProjections.attachment(
            am,
            bytesPresent = plan != null,
            stableRefOverride = stableRef,
        )
    }

    /**
     * The addressable identity of an attachment row (see [NeutralProjections.attachmentStableRef]).
     * Null when the row has NEITHER `id` nor `authorityId` — such a row cannot be addressed by a byte
     * frame at all, so it must travel pointer-only rather than as a phantom carried attachment.
     */
    private fun stableAttachmentRef(am: AttachmentModel): String? = NeutralProjections.attachmentStableRef(am)

    /**
     * Project a forward-owned attachment row and record its byte plan. A row with no `id` still has a
     * stable identity in its `authorityId`, used as both the plan key and the record's contentRef so
     * the writer and the byte source agree.
     */
    internal fun projectOwnedAttachment(oa: NeutralProjections.OwnedAttachment): NeutralAttachment {
        val plan = resolveBytePlan(oa.model, legacyOwnerKey = oa.model.authorityId?.toString())
        val stableRef = stableAttachmentRef(oa.model)
        if (plan != null && stableRef != null) refToPlan[stableRef] = plan
        return NeutralProjections.attachment(
            oa.model,
            bytesPresent = plan != null,
            owner = oa.owner,
            ownerAuthor = oa.ownerAuthor,
            ownerSentAt = oa.ownerSentAt,
            stableRefOverride = stableRef,
            hostMessageRef = oa.hostMessageId,
        )
    }

    /**
     * Decide where an attachment's bytes come from, or null when they are legitimately absent and the
     * attachment travels pointer-only.
     *
     * The current address is `attachment/<localId>/<fileName>`, one directory per copy. An exporter
     * cannot assume the on-device relocation has run, so it probes that first and then the legacy
     * `attachment/<legacyOwnerKey>/<fileName>` — the host `message.id` for normal rows, the
     * attachment's own `authorityId` for forward rows.
     *
     * Read-only by design: the exporter probes but never relocates, since an export must not mutate
     * live device state.
     */
    private fun resolveBytePlan(
        am: AttachmentModel,
        legacyOwnerKey: String?,
    ): BytePlan? {
        val fileName = am.fileName?.takeIf { it.isNotEmpty() } ?: return null
        // The at-rest file is ciphertext under the row's key. A send whose upload failed leaves that
        // file behind but never records the key (#1378), so carrying it would hand the destination a
        // file it can never decrypt, shown as present. Pointer-only instead: it lands as expired.
        if (!EncryptedAttachmentAccess.isUsableKey(am.key)) return null
        // Attachments left behind by choice: no byte plan → pointer-only, voice notes excepted.
        if (!includeAttachments && !isVoiceNote(am)) return null
        // Current address first: the attachment's own localId.
        am.localId?.takeIf { it.isNotEmpty() }?.let { localId ->
            planForPath(attachmentDir(localId) + fileName)?.let { return it }
        }
        // Legacy address: a file the relocation has not moved yet (or a row with no localId).
        legacyOwnerKey?.takeIf { it.isNotEmpty() }?.let { key ->
            planForPath(attachmentDir(key) + fileName)?.let { return it }
        }
        return null // bytes absent → pointer-only
    }

    /** The bubble kind, not the file kind: the VOICE flag on an audio attachment. */
    private fun isVoiceNote(am: AttachmentModel): Boolean =
        am.flags == FLAG_VOICE && am.contentType?.contains("audio") == true

    /**
     * Resolve a [BytePlan] for a concrete on-disk base [path], or null when no at-rest ciphertext is
     * present — meaning the bytes were never downloaded, so the attachment travels pointer-only.
     */
    private fun planForPath(path: String): BytePlan? {
        // Presence alone is not enough: a truncated or zero-length at-rest file cannot be decrypted,
        // so shipping it would migrate an attachment that can never render and consume the pointer
        // that would have re-downloaded it. Same predicate the reader uses for a usable local copy.
        if (!EncryptedAttachmentAccess.hasEncrypted(path)) return null
        return BytePlan(EncryptedAttachmentAccess.encryptedFile(path).absolutePath)
    }


    override fun attachmentByteSource(): AttachmentByteSource = AttachmentByteSource { ref ->
        val plan = refToPlan[ref]
        if (plan == null) {
            // Pointer-only attachments carry no plan and are never asked for, so a null here means a
            // ref expected to have bytes lost its plan.
            L.w { "[MigrationV2][Export] no byte plan for attachment ref=$ref" }
            null
        } else {
            // Carry the at-rest ciphertext verbatim; the destination decrypts on read with the
            // migrated encryptionKey.
            val file = File(plan.encryptPath)
            if (!file.exists()) {
                L.w { "[MigrationV2][Export] at-rest ciphertext vanished ref=$ref" }
                null
            } else {
                AttachmentBytes(length = file.length(), stream = FileInputStream(file))
            }
        }
    }
}

/** Wire-proto attachment flag bit 0: a voice note. */
private const val FLAG_VOICE = 1

/** Messages sampled to estimate the per-record size shown on the pairing page. */
private const val ESTIMATE_SAMPLE_MESSAGES = 50
