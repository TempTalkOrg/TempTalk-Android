package com.difft.android.chat.jobs

import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.common.SendType
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.DurableJob
import com.difft.android.chat.jobmanager.persistence.StrictWrite
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.OrderingTerm
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import org.difft.app.database.WCDB
import org.difft.app.database.clearRoomSendStatusIfNoFailure
import org.difft.app.database.clearRoomSendingStatusIfNoSending
import org.difft.app.database.roomIdsWithNonNoneSendStatus
import org.difft.app.database.roomIdsWithSendingStatusFlagged
import org.difft.app.database.roomIdsWithStaleSendingOutgoing
import org.difft.app.database.writeRoomSendStatusFor
import org.difft.app.database.models.DBJobConstraintModel
import org.difft.app.database.models.DBJobSpecModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.JobConstraintModel
import org.difft.app.database.models.JobSpecModel
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
class WcdbJobStorage @Inject constructor(
    private val wcdb: WCDB
) {

    

    

    @WorkerThread
    fun insertJobs(fullSpecs: List<FullSpec>) {
        if (fullSpecs.all { it.jobSpec.isMemoryOnly }) return
        try {
            wcdb.db.runTransaction {
                fullSpecs.filterNot { it.jobSpec.isMemoryOnly }.forEach { spec ->
                    wcdb.jobSpec.insertOrIgnoreObject(spec.jobSpec.toModel())
                    spec.constraintSpecs
                        .filterNot { it.isMemoryOnly }
                        .forEach { c ->
                            wcdb.jobConstraint.insertOrIgnoreObject(c.toModel())
                        }
                }
                true
            }
        } catch (e: Exception) {
            
            L.e { "[JobStorage] insertJobs failed count=${fullSpecs.size}: ${e.stackTraceToString()}" }
        }
    }

    
    @WorkerThread
    fun insertJobsStrict(fullSpecs: List<FullSpec>): StrictWrite {
        if (fullSpecs.any { it.isMemoryOnly }) return StrictWrite.RejectedMemoryOnly
        var entered = false
        return try {
            wcdb.db.runTransaction {
                entered = true
                fullSpecs.forEach { spec ->
                    wcdb.jobSpec.insertOrIgnoreObject(spec.jobSpec.toModel())
                    spec.constraintSpecs.forEach { wcdb.jobConstraint.insertOrIgnoreObject(it.toModel()) }
                }
                true
            }
            StrictWrite.Committed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[JobStorage] event=strict_write_failed entered=$entered ${e.stackTraceToString()}" }
            
            if (entered) StrictWrite.Unknown("transaction_exception") else StrictWrite.Failed("before_transaction")
        }
    }

    @WorkerThread
    fun findDurableJob(id: String): DurableJob = try {
        
        val row = wcdb.jobSpec.getFirstObject(DBJobSpecModel.jobSpecId.eq(id))
        if (row == null) DurableJob.Absent
        else DurableJob.Found(row.toJobSpec(), wcdb.jobConstraint.getAllObjects(DBJobConstraintModel.jobSpecId.eq(id)).map { it.toConstraintSpec() })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[JobStorage] event=strict_read_failed ${e.stackTraceToString()}" }
        DurableJob.ReadFailed("database_exception")
    }

    

    @WorkerThread
    fun updateJobs(jobs: List<JobSpec>) {
        if (jobs.all { it.isMemoryOnly }) return
        try {
            wcdb.db.runTransaction {
                jobs.filterNot { it.isMemoryOnly }.forEach { job ->
                    wcdb.jobSpec.updateRow(
                        arrayOf(
                            Value(job.id),
                            Value(job.factoryKey),
                            Value(job.queueKey),
                            Value(job.createTime),
                            Value(job.nextRunAttemptTime),
                            Value(job.runAttempt),
                            Value(job.maxAttempts),
                            Value(job.lifespan),
                            Value(job.serializedData),
                            Value(job.isRunning)
                        ),
                        arrayOf(
                            DBJobSpecModel.jobSpecId,
                            DBJobSpecModel.factoryKey,
                            DBJobSpecModel.queueKey,
                            DBJobSpecModel.createTime,
                            DBJobSpecModel.nextRunAttemptTime,
                            DBJobSpecModel.runAttempt,
                            DBJobSpecModel.maxAttempts,
                            DBJobSpecModel.lifespan,
                            DBJobSpecModel.serializedData,
                            DBJobSpecModel.isRunning
                        ),
                        DBJobSpecModel.jobSpecId.eq(job.id)
                    )
                }
                true
            }
        } catch (e: Exception) {
            
            L.e { "[JobStorage] updateJobs failed count=${jobs.size}: ${e.stackTraceToString()}" }
        }
    }

    
    @WorkerThread
    fun updateJobRunningState(id: String, isRunning: Boolean) {
        wcdb.jobSpec.updateValue(
            Value(isRunning),
            DBJobSpecModel.isRunning,
            DBJobSpecModel.jobSpecId.eq(id)
        )
    }

    
    @WorkerThread
    fun updateJobAfterRetry(
        id: String,
        isRunning: Boolean,
        runAttempt: Int,
        nextRunAttemptTime: Long,
        serializedData: String
    ) {
        wcdb.jobSpec.updateRow(
            arrayOf(
                Value(isRunning),
                Value(runAttempt),
                Value(nextRunAttemptTime),
                Value(serializedData)
            ),
            arrayOf(
                DBJobSpecModel.isRunning,
                DBJobSpecModel.runAttempt,
                DBJobSpecModel.nextRunAttemptTime,
                DBJobSpecModel.serializedData
            ),
            DBJobSpecModel.jobSpecId.eq(id)
        )
    }

    
    @WorkerThread
    fun updateAllJobsToBePending() {
        wcdb.jobSpec.updateValue(
            Value(false),
            DBJobSpecModel.isRunning
        )
    }

    

    @WorkerThread
    fun deleteJobs(ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            wcdb.db.runTransaction {
                val idsArray = ids.toTypedArray()
                wcdb.jobSpec.deleteObjects(DBJobSpecModel.jobSpecId.`in`(*idsArray))
                wcdb.jobConstraint.deleteObjects(DBJobConstraintModel.jobSpecId.`in`(*idsArray))
                true
            }
        } catch (e: Exception) {
            L.e { "[JobStorage] deleteJobs failed count=${ids.size}: ${e.stackTraceToString()}" }
            
            throw e
        }
        
        
        
        val remaining = try {
            wcdb.jobSpec.getValue(DBJobSpecModel.jobSpecId.count())?.int ?: -1
        } catch (e: Exception) {
            L.w { "[JobStorage] deleteJobs remaining-count query failed: ${e.stackTraceToString()}" }
            -1
        }
        L.i { "[JobStorage] deleted ${ids.size}, jobSpec remaining=$remaining" }
    }

    

    


    @WorkerThread
    fun getAllJobSpecs(): List<JobSpec> = try {
        wcdb.jobSpec.prepareSelect()
            .select(*DBJobSpecModel.allBindingFields())
            .orderBy(
                OrderingTerm(DBJobSpecModel.createTime).order(Order.Asc),
                OrderingTerm(DBJobSpecModel.jobSpecId).order(Order.Asc)
            )
            .allObjects()
            .map { it.toJobSpec() }
    } catch (e: Exception) {
        
        L.e { "[JobStorage] getAllJobSpecs failed: ${e.stackTraceToString()}" }
        emptyList()
    }

    
    @WorkerThread
    fun getAllConstraintSpecs(): List<ConstraintSpec> = try {
        wcdb.jobConstraint.allObjects.map { it.toConstraintSpec() }
    } catch (e: Exception) {
        
        L.e { "[JobStorage] getAllConstraintSpecs failed: ${e.stackTraceToString()}" }
        emptyList()
    }

    

    




    @WorkerThread
    fun sweepStaleSendingMessages(): Int = try {
        val pendingSendJobs = wcdb.jobSpec.getValue(
            DBJobSpecModel.jobSpecId.count(),
            DBJobSpecModel.factoryKey.eq(PushTextSendJob.KEY)
        )?.int ?: 0
        if (pendingSendJobs > 0) {
            L.i { "[JobStorage] sweepStaleSendingMessages: skip, $pendingSendJobs PushTextSendJob in flight" }
            return 0
        }
        val before = wcdb.message.getValue(
            DBMessageModel.databaseId.count(),
            DBMessageModel.sendType.eq(SendType.Sending.rawValue)
        )?.int ?: 0
        if (before == 0) {
            L.i { "[JobStorage] sweepStaleSendingMessages: nothing to do" }
            return 0
        }
        
        
        val roomsToFlag = wcdb.roomIdsWithStaleSendingOutgoing()
        
        
        
        
        
        
        wcdb.message.updateValue(
            Value(SendType.SentFailed.rawValue),
            DBMessageModel.sendType,
            DBMessageModel.sendType.eq(SendType.Sending.rawValue)
        )
        wcdb.writeRoomSendStatusFor(roomsToFlag, ROOM_SEND_STATUS_FAILED)
        
        
        
        
        
        
        
        
        val sendingRoomsToClear = wcdb.roomIdsWithSendingStatusFlagged()
        sendingRoomsToClear.forEach { wcdb.clearRoomSendingStatusIfNoSending(it) }
        
        
        
        
        (roomsToFlag + sendingRoomsToClear).distinct()
            .forEach { RoomChangeTracker.trackRoom(it, RoomChangeType.MESSAGE) }
        L.i { "[JobStorage] sweepStaleSendingMessages count=$before rooms=${roomsToFlag.size} sendingCleared=${sendingRoomsToClear.size} Sending->SentFailed" }
        before
    } catch (e: Exception) {
        L.e { "[JobStorage] sweepStaleSendingMessages failed: ${e.stackTraceToString()}" }
        0
    }

    








    @WorkerThread
    fun reconcileFailedRoomTags(): Int = try {
        val flagged = wcdb.roomIdsWithNonNoneSendStatus()
        flagged.forEach { wcdb.clearRoomSendStatusIfNoFailure(it) }
        
        
        flagged.forEach { RoomChangeTracker.trackRoom(it, RoomChangeType.MESSAGE) }
        L.i { "[JobStorage] reconcileFailedRoomTags flagged=${flagged.size}" }
        flagged.size
    } catch (e: Exception) {
        L.e { "[JobStorage] reconcileFailedRoomTags failed: ${e.stackTraceToString()}" }
        0
    }

    

    private fun JobSpec.toModel(): JobSpecModel = JobSpecModel().also {
        it.jobSpecId = id
        it.factoryKey = factoryKey
        it.queueKey = queueKey
        it.createTime = createTime
        it.nextRunAttemptTime = nextRunAttemptTime
        it.runAttempt = runAttempt
        it.maxAttempts = maxAttempts
        it.lifespan = lifespan
        it.serializedData = serializedData
        it.isRunning = isRunning
    }

    private fun JobSpecModel.toJobSpec(): JobSpec = JobSpec(
        id = jobSpecId,
        factoryKey = factoryKey,
        queueKey = queueKey,
        createTime = createTime,
        nextRunAttemptTime = nextRunAttemptTime,
        runAttempt = runAttempt,
        maxAttempts = maxAttempts,
        lifespan = lifespan,
        serializedData = serializedData ?: "",
        isRunning = isRunning,
        
        
        isMemoryOnly = false
    )

    private fun ConstraintSpec.toModel(): JobConstraintModel = JobConstraintModel().also {
        it.jobSpecId = jobSpecId
        it.factoryKey = factoryKey
    }

    private fun JobConstraintModel.toConstraintSpec(): ConstraintSpec = ConstraintSpec(
        jobSpecId = jobSpecId,
        factoryKey = factoryKey,
        isMemoryOnly = false
    )
}
