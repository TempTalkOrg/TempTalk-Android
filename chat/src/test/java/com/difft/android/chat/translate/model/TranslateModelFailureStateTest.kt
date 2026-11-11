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

        
        val reloaded = state()
        reloaded.ensureLoaded()

        assertEquals(2, reloaded.failCount(model))
    }

    

    @Test
    fun `T18b the in-memory record is authoritative before the disk write`() = runTest(testDispatcher) {
        
        
        val silentStore = RecordingPreferencesDataStore(initial = null)
        val state = TranslateModelFailureState(silentStore, Gson())

        val nextRetryAtMs = state.onFailure(model, FailureReason.SDK_ERROR)

        assertEquals(1, state.failCount(model))
        assertEquals(nextRetryAtMs, state.nextRetryAtMs(model))
        assertEquals(0, silentStore.writeCount.get())
    }

    

    @Test
    fun `a stuck download records a reason without consuming a backoff step`() = runTest(testDispatcher) {
        val state = state()
        state.ensureLoaded()

        state.onStuck(model)

        assertEquals(0, state.failCount(model))
        assertEquals(0L, state.nextRetryAtMs(model))
    }

    

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
