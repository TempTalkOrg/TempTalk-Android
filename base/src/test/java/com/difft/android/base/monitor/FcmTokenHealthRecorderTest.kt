package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FcmTokenHealthRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var recorder: FcmTokenHealthRecorder

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("f.preferences_pb") })
        recorder = FcmTokenHealthRecorder(store)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun failSince() = store.data.first()[AppStateKeys.BG_FCM_FAIL_SINCE]
    private suspend fun state() = store.data.first()[AppStateKeys.BG_FCM_STATE]

    @Test
    fun `failure opens a run once and success clears it`() = runTest {
        val a = recorder.beginAttempt()
        recorder.recordFailure(a, nowWall = 1_000L)
        assertEquals(1_000L, failSince())
        assertEquals("fail", state())

        recorder.recordFailure(recorder.beginAttempt(), nowWall = 9_000L) // run keeps its first start
        assertEquals(1_000L, failSince())

        recorder.recordSuccess(recorder.beginAttempt())
        assertNull(failSince())
        assertEquals("ok", state())
    }

    @Test
    fun `T5 an older attempt failing late cannot reopen a recovered run`() = runTest {
        val a = recorder.beginAttempt()
        val b = recorder.beginAttempt()
        recorder.recordSuccess(b)
        recorder.recordFailure(a, nowWall = 5_000L)
        assertNull(failSince())
        assertEquals("ok", state())
    }

    @Test
    fun `T5 ordering ignores the wall clock, so a clock set back does not hide a new failure`() = runTest {
        recorder.recordSuccess(recorder.beginAttempt())
        // Wall clock moved back two weeks: the next attempt's failure is still recorded.
        val fortnight = 14L * 24 * 3_600_000
        recorder.recordFailure(recorder.beginAttempt(), nowWall = 100 * fortnight - fortnight)
        assertEquals(99 * fortnight, failSince())
        assertEquals("fail", state())
    }

    @Test
    fun `no play services is its own state and leaves the failure run alone`() = runTest {
        recorder.recordNoGms()
        assertEquals("nogms", state())
        assertNull(failSince())
    }
}
