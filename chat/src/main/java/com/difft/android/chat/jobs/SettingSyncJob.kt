package com.difft.android.chat.jobs

import androidx.annotation.VisibleForTesting
import com.difft.android.SettingSyncJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.BackoffUtil
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.FinalOutcome
import com.difft.android.chat.setting.sync.ServerConfigSnapshot
import com.difft.android.chat.setting.sync.SettingSyncState
import com.difft.android.chat.setting.sync.SyncField
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.GetConversationSetRequestBody
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * Carries one local-first setting change ([SyncField]) to the server. One job per user
 * operation; the queue `[SettingSync::<room>::<field>]` runs them FIFO per field, so a job that
 * finds another job in its queue knows it has been superseded.
 *
 * Phases only move forward and are persisted through [persistPhase]: a retry with the minimum
 * positive backoff, which makes the framework re-serialise the job before the next run
 * (`JobController.onRetry` requires a positive interval):
 *
 * - [Phase.SEND]: the intent is not on the server yet. POST once; on acceptance keep only the
 *   echoed version as [acceptedVersion] and move to RECONCILE.
 * - [Phase.RECONCILE]: the SET was accepted (or the server already won). Never SET again; GET the
 *   full snapshot and let [ConversationSettingsApplier.applyFinal] align the row.
 * - [Phase.ROLLBACK]: the SET was rejected or the job expired. Never SET again; GET and align.
 *
 * A job holding an [acceptedVersion] does not vanish on supersession until the local applied
 * version has caught up with it, so a later job's rollback can never undo the accepted write
 * with a stale read replica.
 */
class SettingSyncJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted("roomId") private val roomId: String,
    @Assisted private val field: SyncField,
    @Assisted("target") private val target: Int,
    @Assisted("ts") private val ts: Long,
    @Assisted initialPhase: Phase,
    @Assisted("acceptedVersion") initialAcceptedVersion: Int,
    @ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val dbRoomStore: DBRoomStore,
    private val applier: ConversationSettingsApplier,
    private val syncState: SettingSyncState,
) : Job(parameters ?: buildParameters(roomId, field)) {

    enum class Phase { SEND, RECONCILE, ROLLBACK }

    @VisibleForTesting
    internal var phase: Phase = initialPhase
        private set

    @VisibleForTesting
    internal var acceptedVersion: Int = initialAcceptedVersion
        private set

    @VisibleForTesting
    internal var now: () -> Long = { System.currentTimeMillis() }

    private val queueKey get() = SettingSyncState.queueKey(roomId, field)

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_ROOM_ID, roomId)
        .putString(KEY_FIELD, field.key)
        .putInt(KEY_TARGET, target)
        .putLong(KEY_TS, ts)
        .putString(KEY_PHASE, phase.name)
        .putInt(KEY_ACCEPTED_VERSION, acceptedVersion)
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onFailure() {
        L.w { "[SettingSync] job failed permanently room=$roomId field=${field.key} ts=$ts phase=$phase" }
    }

    override suspend fun run(): Result = try {
        execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[SettingSync] decision=retry room=$roomId phase=$phase err=${e.javaClass.simpleName}" }
        retryLater()
    }

    private suspend fun execute(): Result {
        val room = dbRoomStore.getRoom(roomId) ?: return finish("roomGone")
        if (isSuperseded()) {
            // An accepted write must be reflected in the local applied version before this job
            // disappears, or a later job's rollback could undo it with a stale read. Once any
            // snapshot has carried the version that far, nothing is left to wait for.
            return when {
                acceptedVersion == 0 -> finish("superseded")
                room.conversationConfigVersion >= acceptedVersion -> finish("supersededFloorMet")
                else -> reconcile("supersededWithFloor")
            }
        }
        return when (phase) {
            Phase.SEND -> send()
            Phase.RECONCILE -> reconcile("reconcile")
            Phase.ROLLBACK -> reconcile("rollback")
        }
    }

    private suspend fun send(): Result {
        if (now() - parameters.createTime > EXPIRY_MS) {
            L.w { "[SettingSync] decision=expired room=$roomId ts=$ts" }
            phase = Phase.ROLLBACK
            return persistPhase()
        }
        val room = dbRoomStore.getRoom(roomId) ?: return finish("roomGone")
        if (room.confidentialMode != target) {
            // The server won a conflict after this job was enqueued; it may still have executed our SET.
            L.i { "[SettingSync] decision=serverWon room=$roomId target=$target local=${room.confidentialMode}" }
            phase = Phase.RECONCILE
            return persistPhase()
        }
        L.i { "[SettingSync] decision=send room=$roomId field=${field.key} target=$target ts=$ts attempt=$runAttempt" }
        val response = try {
            httpClient.httpService.fetchConversationSet(
                ConversationSetRequestBody(conversation = roomId, confidentialMode = target)
            )
        } catch (e: NetworkException) {
            if (isRejection(e.errorCode)) {
                L.w { "[SettingSync] decision=rejected room=$roomId code=${e.errorCode}" }
                phase = Phase.ROLLBACK
                return persistPhase()
            }
            L.w { "[SettingSync] decision=retry room=$roomId code=${e.errorCode}" }
            return retryLater()
        } catch (e: IOException) {
            L.w { "[SettingSync] decision=retry room=$roomId err=${e.javaClass.simpleName}" }
            return retryLater()
        }
        return if (response.status == 0) {
            acceptedVersion = response.data?.version ?: 0
            phase = Phase.RECONCILE
            L.i { "[SettingSync] decision=accepted room=$roomId acceptedVersion=$acceptedVersion" }
            persistPhase()
        } else {
            L.w { "[SettingSync] decision=rejected room=$roomId status=${response.status}" }
            phase = Phase.ROLLBACK
            persistPhase()
        }
    }

    /** Bounded GET → applyFinal loop; HTTP success is not alignment, only an outcome is. */
    private suspend fun reconcile(reason: String): Result {
        repeat(MAX_RECONCILE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                if (dbRoomStore.getRoom(roomId) == null) return finish("roomGone")
                if (acceptedVersion == 0 && isSuperseded()) return finish("superseded")
            }
            val snapshot = fetchSnapshot() ?: return finish("absentOnServer")
            val outcome = applier.applyFinal(snapshot, jobId = id, jobTs = ts, minVersion = acceptedVersion)
            L.i { "[SettingSync] decision=$reason room=$roomId v=${snapshot.version} min=$acceptedVersion outcome=$outcome" }
            when (outcome) {
                FinalOutcome.COMPLETED, FinalOutcome.SUPERSEDED -> return Result.success()
                FinalOutcome.NEEDS_RECONCILE -> Unit
            }
        }
        return retryLater()
    }

    private suspend fun fetchSnapshot(): ServerConfigSnapshot? {
        val response = httpClient.httpService.fetchGetConversationSet(GetConversationSetRequestBody(listOf(roomId)))
        if (response.status != 0) throw IOException("conversation/get status=${response.status}")
        val body = response.data?.conversations?.firstOrNull { it.conversation == roomId } ?: return null
        return ServerConfigSnapshot.fromPull(body)
    }

    private suspend fun isSuperseded(): Boolean = syncState.pendingJobs(roomId, field, excludeJobId = id).isNotEmpty()

    private fun finish(decision: String): Result {
        syncState.markFinalized(id, queueKey)
        syncState.clearIntentIf(roomId, field, ts)
        L.i { "[SettingSync] decision=$decision room=$roomId field=${field.key} ts=$ts phase=$phase" }
        return Result.success()
    }

    private fun retryLater(): Result = Result.retry(BackoffUtil.exponentialBackoff(runAttempt + 1, MAX_BACKOFF_MS))

    /** Persists the phase change through the framework's retry path and runs again at once. */
    private fun persistPhase(): Result = Result.retry(PHASE_PERSIST_BACKOFF_MS)

    class Factory : Job.Factory<SettingSyncJob> {
        @dagger.hilt.EntryPoint
        @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
        interface EntryPoint {
            fun settingSyncJobFactory(): SettingSyncJobFactory
        }

        override fun create(parameters: Parameters, data: Data): SettingSyncJob {
            val entryPoint = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            )
            val fieldKey = requireNotNull(data.getString(KEY_FIELD)) { "field missing" }
            return entryPoint.settingSyncJobFactory().create(
                parameters = parameters,
                roomId = requireNotNull(data.getString(KEY_ROOM_ID)) { "roomId missing" },
                field = requireNotNull(SyncField.fromKey(fieldKey)) { "unknown field $fieldKey" },
                target = data.getInt(KEY_TARGET),
                ts = data.getLong(KEY_TS),
                phase = Phase.valueOf(requireNotNull(data.getStringOrDefault(KEY_PHASE, Phase.SEND.name))),
                acceptedVersion = data.getIntOrDefault(KEY_ACCEPTED_VERSION, 0),
            )
        }
    }

    companion object {
        const val KEY = "SettingSyncJob"
        const val KEY_ROOM_ID = "room_id"
        const val KEY_FIELD = "field"
        const val KEY_TARGET = "target"
        const val KEY_TS = "ts"
        const val KEY_PHASE = "phase"
        const val KEY_ACCEPTED_VERSION = "accepted_version"

        /** Smallest interval `JobController.onRetry` accepts; used to persist a phase change. */
        const val PHASE_PERSIST_BACKOFF_MS = 1L

        /** Execution-side expiry; the framework lifespan stays IMMORTAL so expiry always runs the rollback. */
        val EXPIRY_MS: Long = TimeUnit.DAYS.toMillis(30)
        private val MAX_BACKOFF_MS: Long = TimeUnit.SECONDS.toMillis(60)
        private const val MAX_RECONCILE_ATTEMPTS = 2

        /**
         * 4xx the server will keep answering the same way. 401/403 wait for a token refresh;
         * 408, 413, 428 and 429 are transient (timeout, rate limit, proof required).
         */
        fun isRejection(code: Int?): Boolean = code != null &&
            code in HttpURLConnection.HTTP_BAD_REQUEST until HttpURLConnection.HTTP_INTERNAL_ERROR &&
            code !in TRANSIENT_4XX

        private val TRANSIENT_4XX = setOf(
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN,
            HttpURLConnection.HTTP_CLIENT_TIMEOUT,
            HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
            HTTP_PRECONDITION_REQUIRED,
            HTTP_TOO_MANY_REQUESTS,
        )

        private const val HTTP_PRECONDITION_REQUIRED = 428
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private fun buildParameters(roomId: String, field: SyncField): Parameters = Parameters.Builder()
            .setQueue(SettingSyncState.queueKey(roomId, field))
            .setLifespan(Parameters.IMMORTAL)
            .setMaxAttempts(Parameters.UNLIMITED)
            .addConstraint(NetworkConstraint.KEY)
            .build()
    }
}
