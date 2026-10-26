package com.difft.android.base.notification

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins the readable notification state with the stored notification test results (#1350). Every
 * read crosses process boundaries, so all of it runs on the IO dispatcher.
 */
@Singleton
class NotificationTestRepository @Inject constructor(
    private val readinessProvider: NotificationReadinessProvider,
    private val store: NotificationTestStore,
) {

    private val callShownAt = MutableStateFlow(0L)

    /** Epoch ms of the last time the test call screen opened in this process; 0 when never. */
    val callShown: StateFlow<Long> = callShownAt.asStateFlow()

    /** The readiness model including the still-valid test results. */
    suspend fun readReadiness(): NotificationReadiness = withContext(Dispatchers.IO) {
        val input = readinessProvider.readInput()
        NotificationReadinessEvaluator.evaluate(input.copy(testResults = validResults(input)))
    }

    /** The fingerprint of the settings [kind] depends on, read now. */
    suspend fun fingerprint(kind: NotificationTestKind): String = withContext(Dispatchers.IO) {
        NotificationTestFingerprint.of(kind, readinessProvider.readInput(), Build.FINGERPRINT.orEmpty())
    }

    /**
     * Stores [outcome] for [kind] under [fingerprint], the settings the test notification was posted
     * with, so a setting changed before the answer invalidates the result.
     */
    suspend fun record(
        kind: NotificationTestKind,
        outcome: NotificationTestOutcome,
        fingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        store.write(kind, NotificationTestResult(outcome, nowMs, fingerprint))
    }

    /** Fingerprint of the test call just posted; the call screen records its success under it. */
    @Volatile
    private var postedCallFingerprint: String? = null

    fun notePostedCall(fingerprint: String) {
        postedCallFingerprint = fingerprint
    }

    /** Orders the call test's OK and FAIL writes, so a screen that opens at the timeout keeps its OK. */
    private val callWrite = Mutex()

    /**
     * The test call screen opened: raises the in-process signal synchronously, before any disk
     * write, so a test page waiting on [callShown] sees it even at the timeout boundary.
     */
    fun markCallShown(nowMs: Long = System.currentTimeMillis()) {
        callShownAt.value = nowMs
    }

    /** Stores the call test's objective success; call after [markCallShown]. */
    suspend fun recordCallShown(nowMs: Long = System.currentTimeMillis()) {
        callWrite.withLock {
            // A process restarted since the post has no snapshot; the settings read now are the best left.
            val fingerprint = postedCallFingerprint ?: fingerprint(NotificationTestKind.CALL)
            record(NotificationTestKind.CALL, NotificationTestOutcome.OK, fingerprint, nowMs)
        }
    }

    /**
     * Stores the call test's FAIL unless the call screen opened since [postedAt]; false when it did.
     * The check and write share a lock with [recordCallShown], so an OK racing this always lands last.
     */
    suspend fun recordCallTimeout(
        fingerprint: String,
        postedAt: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = callWrite.withLock {
        if (callShownAt.value >= postedAt) return@withLock false
        record(NotificationTestKind.CALL, NotificationTestOutcome.FAIL, fingerprint, nowMs)
        true
    }

    private suspend fun validResults(input: ReadinessInput): NotificationTestResults =
        NotificationTestResults.validOf(store.read(), input, Build.FINGERPRINT.orEmpty(), System.currentTimeMillis())
}
