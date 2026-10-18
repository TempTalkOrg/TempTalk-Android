package com.difft.android.chat.setting.sync

import com.difft.android.SettingSyncJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.jobs.SettingSyncJob
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.For
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Local-first entry for the confidential-mode switch: the room row and the UI change at once,
 * and a [SettingSyncJob] carries the value to the server whenever the network allows.
 *
 * Order inside the room lock is fixed: write the row (abort on failure), record the intent, emit,
 * enqueue. The row goes first so a failed write leaves no intent and no event behind; the enqueue
 * goes last and is asynchronous, which is why [SettingSyncState.recordIntent] bridges the gap.
 */
@Singleton
class ConfidentialModeSyncCoordinator @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val syncState: SettingSyncState,
    private val settingsManager: ConversationSettingsManager,
    private val jobFactory: SettingSyncJobFactory,
) {
    /** Creates the room row when the conversation has none yet (a chat opened before any message). */
    suspend fun setConfidentialMode(forWhat: For, target: Int): Boolean = toggle(forWhat.id, target, createAs = forWhat)

    /** For callers that only hold the id: a missing row is created as the kind the id resolves to. */
    suspend fun setConfidentialMode(roomId: String, target: Int): Boolean = toggle(roomId, target, createAs = null)

    /** @return false when the local row could not be written; nothing else happened then. */
    private suspend fun toggle(roomId: String, target: Int, createAs: For?): Boolean = withContext(Dispatchers.IO) {
        val field = SyncField.CONFIDENTIAL_MODE
        syncState.lockFor(roomId).withLock {
            val newest = syncState.latestIntentTs(roomId, field) ?: 0L
            val ts = max(ServerTimeProvider.nowMillis(), newest + 1)
            try {
                if (dbRoomStore.getRoom(roomId) == null) {
                    val kind = createAs ?: dbRoomStore.resolveFor(roomId)
                    L.i { "[SettingSync] toggle createRoom room=$roomId type=${kind.typeValue}" }
                    dbRoomStore.createRoomIfNotExist(kind)
                }
                dbRoomStore.updateConfidentialMode(roomId, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[SettingSync] toggle localWriteFailed room=$roomId target=$target err=${e.stackTraceToString()}" }
                return@withLock false
            }
            syncState.recordIntent(roomId, field, ts)
            settingsManager.emitConversationSettingUpdate(conversationId = roomId, confidentialMode = target)
            syncState.jobManagerProvider().add(
                jobFactory.create(
                    parameters = null,
                    roomId = roomId,
                    field = field,
                    target = target,
                    ts = ts,
                    phase = SettingSyncJob.Phase.SEND,
                    acceptedVersion = 0,
                )
            )
            L.i { "[SettingSync] toggle enqueued room=$roomId target=$target ts=$ts" }
            true
        }
    }
}
