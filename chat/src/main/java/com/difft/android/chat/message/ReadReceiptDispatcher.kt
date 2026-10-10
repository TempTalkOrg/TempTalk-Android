package com.difft.android.chat.message

import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.create
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ReadPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.forEachMessagePaged
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-sender read-receipt accumulator used while paging in-range messages in
 * [ReadReceiptDispatcher.send]. Holds a reference to that sender's current max message (a
 * handful of refs) plus its read-message `timeStamp`s — never the full message objects en
 * masse. (#909 #5)
 */
internal data class SenderReadReceiptAcc(
    var maxMessage: MessageModel,
    val timeStamps: MutableList<Long>
)

/**
 * One per-sender read-receipt Job to emit: the recipient, the (possibly chunked) timestamp
 * list it carries, and the max message whose tuple becomes the Job's [ReadPosition]. The
 * dispatch decision (which Jobs to emit) is computed by
 * [ReadReceiptDispatcher.planReadReceiptJobs] so the large-group skip + chunking branch can be
 * unit-tested without WCDB / the Job factory. (#909 #5, T18)
 */
internal data class ReadReceiptJobPlan(
    val recipientId: String,
    val timeStamps: List<Long>,
    val maxMessage: MessageModel
)

/**
 * Emits the read receipts (to senders) and the read-position sync (to the account's other
 * devices) for everything read in a conversation up to a position. Shared by the chat screen
 * (messages scrolled into view) and the conversation list ("mark as read" / "mark all as read"),
 * so both paths dispatch identical Jobs.
 */
@Singleton
class ReadReceiptDispatcher @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory,
) {

    /**
     * @param currentReadPosition the `systemShowTimestamp` up to which messages count as read.
     * @param readAt the read moment (server axis), sampled once by the caller so the wire
     *   ReadPosition.readAt and the local readTime match (#1020 Phase 2).
     */
    suspend fun send(forWhat: For, currentReadPosition: Long, readAt: Long) = withContext(Dispatchers.IO) {
        try {
            // 判断是否为大群（群人数大于阈值）
            val isLargeGroup = if (forWhat is For.Group) {
                val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
                val memberCount = wcdb.getGroupMemberCount(forWhat.id)
                val isLarge = memberCount > threshold
                if (isLarge) {
                    L.i { "[${forWhat.id}] Large group with $memberCount members (threshold: $threshold), will skip sending read receipts to senders but will send sync message" }
                }
                isLarge
            } else {
                false
            }

            val lastReadPosition = dbRoomStore.getMessageReadPosition(forWhat)
            if (currentReadPosition > lastReadPosition) {
                // #909 #5: page the in-range messages instead of loading them all. Peak memory
                // is one page + per-sender max-message refs + the timestamp longs, never the
                // whole match set of full MessageModel objects.
                val rangeCond = DBMessageModel.roomId.eq(forWhat.id)
                    .and(DBMessageModel.fromWho.notEq(globalServices.myId))
                    .and(DBMessageModel.systemShowTimestamp.between(lastReadPosition, currentReadPosition))

                val perSender = HashMap<String, SenderReadReceiptAcc>()
                var globalMax: MessageModel? = null
                forEachMessagePaged(rangeCond) { msg ->
                    accumulateReadReceipt(perSender, msg)
                    if (globalMax == null || msg.systemShowTimestamp > globalMax!!.systemShowTimestamp) globalMax = msg
                }

                if (perSender.isEmpty()) {
                    L.i { "[${forWhat.id}] No messages to send read receipt for" }
                    return@withContext
                }

                // 1. 小群/单聊：为每个发送者创建Job发送已读回执 大群：跳过这一步
                // #909 #5/TP4: dispatch + chunk decision is computed by the pure
                // planReadReceiptJobs (large group ⇒ empty; otherwise one plan per chunk).
                val receiptJobPlans = planReadReceiptJobs(perSender, isLargeGroup)
                if (isLargeGroup) {
                    L.i { "[${forWhat.id}] Large group: skipping read receipt jobs, will only send sync message" }
                }
                receiptJobPlans.forEach { plan ->
                    L.i { "[Chat][${forWhat.id}] Creating read receipt job for ${plan.recipientId}, messages=${plan.timeStamps.size}" }
                    val maxMessage = plan.maxMessage
                    val readPosition = ReadPosition(
                        forWhat.id.takeIf { forWhat is For.Group },
                        readAt,
                        maxMessage.systemShowTimestamp,
                        maxMessage.notifySequenceId,
                        maxMessage.sequenceId
                    )
                    ApplicationDependencies.getJobManager().add(
                        pushReadReceiptSendJobFactory.create(
                            recipientId = plan.recipientId,
                            forWhat = forWhat,
                            messageTimeStamps = plan.timeStamps,
                            readPosition = readPosition,
                            messageMode = 0,
                            sendReceiptToSender = true,   // 小群发送已读回执
                            sendSyncToSelf = false        // 统一在最后发送同步消息
                        )
                    )
                }

                // 2. 统一发送一次同步消息（无论是否大群）
                // 从所有消息中找到时间戳最大的消息，用于同步已读位置
                val syncMaxMessage = globalMax!!  // non-null: perSender non-empty ⇒ globalMax set
                val syncRecipientId = syncMaxMessage.fromWho
                if (syncRecipientId == null) {
                    // Anomalous (system/corrupt message as the max anchor): a job persisted with an
                    // empty recipientId would survive restart and dispatch to a non-existent recipient.
                    L.w { "[${forWhat.id}] sync-only read-position job skipped: max message ${syncMaxMessage.timeStamp} has null fromWho" }
                } else {
                    val syncReadPosition = ReadPosition(
                        forWhat.id.takeIf { forWhat is For.Group },
                        readAt,
                        syncMaxMessage.systemShowTimestamp,
                        syncMaxMessage.notifySequenceId,
                        syncMaxMessage.sequenceId
                    )
                    L.i { "[${forWhat.id}] Creating sync-only job to sync read position to self's other devices, maxTimestamp=${syncMaxMessage.systemShowTimestamp}" }
                    ApplicationDependencies.getJobManager().add(
                        pushReadReceiptSendJobFactory.create(
                            recipientId = syncRecipientId,
                            forWhat = forWhat,
                            messageTimeStamps = listOf(syncMaxMessage.timeStamp),
                            readPosition = syncReadPosition,
                            messageMode = 0,
                            sendReceiptToSender = false,  // 不发已读回执
                            sendSyncToSelf = true          // 只发同步消息
                        )
                    )
                }
            } else {
                L.d { "[Chat][${forWhat.id}] Read recipient already sent at position: $lastReadPosition, no need to resend." }
            }
        } catch (e: Exception) {
            L.e { "[${forWhat.id}] Error sending read recipient: ${e.stackTraceToString()}" }
        }
    }

    companion object {
        /**
         * Max read-message timestamps carried by a single read-receipt Job. A sender whose
         * in-range timestamp list exceeds this is split across multiple Jobs sharing the same
         * [ReadPosition]. Prevents the #909 OOM from relocating into Job `Data` serialization
         * (a single 1000-long LongArray ≈ 8KB payload — safe). (#909 TP4)
         */
        const val MAX_TIMESTAMPS_PER_RECEIPT = 1000

        /**
         * Folds [msg] into the per-sender accumulator [perSender], updating that sender's
         * running max message and appending its `timeStamp`. Pure (no DB / no I/O) so it can be
         * unit-tested by feeding a message sequence directly. (#909 #5)
         */
        internal fun accumulateReadReceipt(
            perSender: HashMap<String, SenderReadReceiptAcc>,
            msg: MessageModel
        ) {
            val sender = msg.fromWho ?: ""
            val acc = perSender[sender]
            if (acc == null) {
                perSender[sender] = SenderReadReceiptAcc(msg, mutableListOf(msg.timeStamp))
            } else {
                acc.timeStamps += msg.timeStamp
                if (msg.systemShowTimestamp > acc.maxMessage.systemShowTimestamp) acc.maxMessage = msg
            }
        }

        /**
         * Computes the per-sender read-receipt Jobs to emit for an accumulated [perSender] map.
         * - Large group ([isLargeGroup] true): returns empty — no per-sender receipts are sent
         *   (only the sync job, emitted by the caller, runs). (#909 #5 large-group branch)
         * - Otherwise: one plan per sender, with that sender's timestamp list split into chunks
         *   of at most [chunkCap] so a single Job never serializes an unbounded LongArray. All
         *   chunks for a sender share the same max message ⇒ same [ReadPosition]. (#909 TP4)
         *
         * Pure (no DB / no Job factory) so the dispatch + chunking branch is unit-testable. (T18)
         */
        internal fun planReadReceiptJobs(
            perSender: Map<String, SenderReadReceiptAcc>,
            isLargeGroup: Boolean,
            chunkCap: Int = MAX_TIMESTAMPS_PER_RECEIPT
        ): List<ReadReceiptJobPlan> {
            if (isLargeGroup) return emptyList()
            return perSender.flatMap { (recipientId, acc) ->
                acc.timeStamps.chunked(chunkCap).map { chunk ->
                    ReadReceiptJobPlan(recipientId, chunk, acc.maxMessage)
                }
            }
        }
    }
}
