package com.difft.android.chat.setting.sync

import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobs.SettingSyncJob
import io.mockk.coEvery
import io.mockk.mockk
import org.difft.app.database.models.RoomModel

internal const val ROOM = "+10000000001"
internal const val MY_ID = "+19999999999"

internal val serializer = JsonDataSerializer()

/** A pending [SettingSyncJob] spec as the queue would hold it; only `ts` matters to readers. */
internal fun pendingSpec(id: String, ts: Long, room: String = ROOM): JobSpec = JobSpec(
    id = id,
    factoryKey = SettingSyncJob.KEY,
    queueKey = SettingSyncState.queueKey(room, SyncField.CONFIDENTIAL_MODE),
    createTime = ts,
    nextRunAttemptTime = 0,
    runAttempt = 0,
    maxAttempts = Job.Parameters.UNLIMITED,
    lifespan = Job.Parameters.IMMORTAL,
    serializedData = serializer.serialize(Data.Builder().putLong(SettingSyncJob.KEY_TS, ts).build()),
    isRunning = false,
    isMemoryOnly = false,
)

/** Mute/block start at a sentinel so a snapshot's 0 counts as a change; expiry/anchor start null. */
internal fun room(confidential: Int = 0, version: Int = 0, id: String = ROOM): RoomModel = RoomModel().apply {
    roomId = id
    confidentialMode = confidential
    conversationConfigVersion = version
    muteStatus = 5
    blockStatus = 5
}

internal fun snapshot(
    version: Int,
    confidential: Int? = 1,
    mute: Int? = 0,
    block: Int? = 0,
    expiry: Long? = null,
    anchor: Long? = null,
    room: String = ROOM,
) = ServerConfigSnapshot(
    conversation = room,
    version = version,
    muteStatus = mute,
    blockStatus = block,
    confidentialMode = confidential,
    messageExpiry = expiry,
    messageClearAnchor = anchor,
)

/** Real [SettingSyncState] over a mocked [JobManager] whose queue content the test controls. */
internal class FakeQueue {
    val jobManager: JobManager = mockk(relaxed = true)
    var elapsed = 0L
    val state = SettingSyncState(serializer).also {
        it.jobManagerProvider = { jobManager }
        it.elapsedRealtime = { elapsed }
    }

    fun queue(vararg specs: JobSpec) {
        coEvery { jobManager.findJobsInQueue(any()) } returns specs.toList()
    }
}
