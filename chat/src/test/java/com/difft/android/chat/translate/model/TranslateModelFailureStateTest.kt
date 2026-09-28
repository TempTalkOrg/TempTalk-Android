package com.difft.android.chat.translate.model

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.ModelId
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Backoff schedule and the memory-authoritative failure bookkeeping behind the translation-model
 * download.
 *
 * Pure JVM: [backoffDelayMs] is deliberately Android-free, and the state object only needs a
 * `DataStore<Preferences>` (the in-memory recording fake) plus a test-controlled `appScope`.
 *
 * What each case asserts:
 *  - T17  the backoff schedule for failCount 0..99
 *  - T18  three failures then a success resets the record
 *  - T18b the in-memory value is new BEFORE the asynchronous app_state write happens
 *  - T21  (partial) a stuck download records a reason without consuming a backoff step
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateModelFailureStateTest {

    private val model = ModelId("zh")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: RecordingPreferencesDataStore

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        dataStore = RecordingPreferencesDataStore()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun state() = TranslateModelFailureState(dataStore, Gson())

    // --- T17 ----------------------------------------------------------------

    @Test
    fun `T17 backoff schedule is 1m 1m 5m 30m 2h 24h capped`() {
        assertEquals(60_000L, backoffDelayMs(0))
        assertEquals(60_000L, backoffDelayMs(1))
        assertEquals(300_000L, backoffDelayMs(2))
        assertEquals(1_800_000L, backoffDelayMs(3))
        assertEquals(7_200_000L, backoffDelayMs(4))
        assertEquals(86_400_000L, backoffDelayMs(5))
        assertEquals(86_400_000L, backoffDelayMs(99))
    }

    // --- T18 ----------------------------------------------------------------

    @Test
    fun `T18 three failures then a success clears the record`() = runTest(testDispatcher) {
        val state = state()
        state.ensureLoaded()

        repeat(3) { state.onFailure(model, FailureReason.NO_NETWORK) }
        assertEquals(3, state.failCount(model))
        assertTrue(state.nextRetryAtMs(model) > System.currentTimeMillis())

        state.onSuccess(model)

        assertEquals(0, state.failCount(model))
        assertEquals(0L, state.nextRetryAtMs(model))
    }

    @Test
    fun `T18 a reloaded state sees the persisted failure count`() = runTest(testDispatcher) {
        val first = state()
        first.ensureLoaded()
        first.onFailure(model, FailureReason.NO_SPACE)
        first.onFailure(model, FailureReason.NO_SPACE)

        // The asynchronous write already landed in the fake store (appScope is the test scope).
        val reloaded = state()
        reloaded.ensureLoaded()

        assertEquals(2, reloaded.failCount(model))
    }

    // --- T18b ---------------------------------------------------------------

    @Test
    fun `T18b the in-memory record is authoritative before the disk write`() = runTest(testDispatcher) {
        // A store that never answers its first read: the write actor stays suspended for the whole
        // test, so anything readable here came from memory, not from disk.
        val silentStore = RecordingPreferencesDataStore(initial = null)
        val state = TranslateModelFailureState(silentStore, Gson())

        val nextRetryAtMs = state.onFailure(model, FailureReason.SDK_ERROR)

        assertEquals(1, state.failCount(model))
        assertEquals(nextRetryAtMs, state.nextRetryAtMs(model))
        assertEquals(0, silentStore.writeCount.get())
    }

    // --- T21 (stuck) --------------------------------------------------------

    @Test
    fun `a stuck download records a reason without consuming a backoff step`() = runTest(testDispatcher) {
        val state = state()
        state.ensureLoaded()

        state.onStuck(model)

        assertEquals(0, state.failCount(model))
        assertEquals(0L, state.nextRetryAtMs(model))
    }

    // --- load / clear -------------------------------------------------------

    @Test
    fun `an unreadable blob starts clean instead of throwing`() = runTest(testDispatcher) {
        dataStore.emit(
            mutablePreferencesOf(AppStateKeys.TRANSLATE_MODEL_DOWNLOAD_STATE to "{not json")
        )

        val state = state()
        state.ensureLoaded()

        assertEquals(0, state.failCount(model))
    }

    @Test
    fun `clear drops every record`() = runTest(testDispatcher) {
        val state = state()
        state.ensureLoaded()
        state.onFailure(model, FailureReason.NO_NETWORK)

        state.clear()

        assertEquals(0, state.failCount(model))
    }
}
