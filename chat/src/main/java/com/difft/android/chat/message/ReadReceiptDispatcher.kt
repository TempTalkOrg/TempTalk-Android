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







internal data class SenderReadReceiptAcc(
    var maxMessage: MessageModel,
    val timeStamps: MutableList<Long>
)








internal data class ReadReceiptJobPlan(
    val recipientId: String,
    val timeStamps: List<Long>,
    val maxMessage: MessageModel
)







@Singleton
class ReadReceiptDispatcher @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory,
) {

    




    suspend fun send(forWhat: For, currentReadPosition: Long, readAt: Long) = withContext(Dispatchers.IO) {
        try {
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
                            sendReceiptToSender = true,
                            sendSyncToSelf = false
                        )
                    )
                }

                
                val syncMaxMessage = globalMax!!  
                val syncRecipientId = syncMaxMessage.fromWho
                if (syncRecipientId == null) {
                    
                    
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
                            sendReceiptToSender = false,
                            sendSyncToSelf = true
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
        





        const val MAX_TIMESTAMPS_PER_RECEIPT = 1000

        




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
