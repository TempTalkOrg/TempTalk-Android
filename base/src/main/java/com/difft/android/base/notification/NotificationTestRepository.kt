package com.difft.android.base.notification

import android.os.Build
import com.difft.android.base.log.lumberjack.L
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext





@Singleton
class NotificationTestRepository @Inject constructor(
    private val readinessProvider: NotificationReadinessProvider,
    private val store: NotificationTestStore,
    private val clock: NotificationTestClock,
) {

    
    suspend fun readReadiness(): NotificationReadiness = withContext(Dispatchers.IO) {
        val input = readinessProvider.readInput()
        NotificationReadinessEvaluator.evaluate(
            input.copy(testResults = validResults(input), seenCategories = store.readSeenCategories()),
        )
    }

    
    suspend fun fingerprint(kind: NotificationTestKind): String = withContext(Dispatchers.IO) {
        NotificationTestFingerprint.of(kind, readinessProvider.readInput(), Build.FINGERPRINT.orEmpty())
    }

    
    suspend fun markCategorySeen(channelId: String) = withContext(Dispatchers.IO) {
        store.markCategorySeen(channelId)
    }

    



    suspend fun record(
        kind: NotificationTestKind,
        outcome: NotificationTestOutcome,
        fingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        store.write(kind, NotificationTestResult(outcome, nowMs, fingerprint))
    }

    private val callWrite = Mutex()
    private val shownRun = MutableStateFlow<String?>(null)
    val callShownRun: StateFlow<String?> = shownRun.asStateFlow()

    suspend fun prepareCall(runId: String, fingerprint: String, postedAt: Long) = withContext(Dispatchers.IO) {
        callWrite.withLock {
            store.prepareCall(NotificationTestCallTicket(runId, postedAt, fingerprint, postedAt + NotificationTestCallTicket.LIFETIME_MS))
        }
    }

    suspend fun recordCallShown(runId: String, nowMs: Long = clock.nowMs()): NotificationTestCallUpdate =
        updateCall(runId, nowMs, shown = true)

    suspend fun recordCallTimeout(runId: String, nowMs: Long = clock.nowMs()): NotificationTestCallUpdate =
        updateCall(runId, nowMs, shown = false)

    suspend fun cancelCall(runId: String) = withContext(Dispatchers.IO) {
        callWrite.withLock { store.cancelCall(runId) }
    }

    private suspend fun updateCall(runId: String, nowMs: Long, shown: Boolean): NotificationTestCallUpdate =
        withContext(Dispatchers.IO) {
            callWrite.withLock {
                try {
                    val result = store.updateCall(runId, nowMs, shown)
                    if (shown && result in setOf(NotificationTestCallUpdate.RECORDED, NotificationTestCallUpdate.ALREADY_SHOWN)) {
                        shownRun.value = runId
                    }
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w {
                        "[NotificationTest] persistence_failed stage=call_result error=${e.javaClass.simpleName}"
                    }
                    NotificationTestCallUpdate.PERSISTENCE_FAILED
                }
            }
        }

    private suspend fun validResults(input: ReadinessInput): NotificationTestResults =
        NotificationTestResults.validOf(store.read(), input, Build.FINGERPRINT.orEmpty(), System.currentTimeMillis())
}
