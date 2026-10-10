package com.difft.android.chat.translate.model

import android.content.Context
import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.FakeTranslateBackend
import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.TranslateLang
import com.difft.android.chat.translate.backend.TranslateBackendException
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * [TranslateModelStore] admission, backoff and single-in-flight behaviour: at most one download
 * is in flight, a failure opens a backoff window, and the settings retry both clears that
 * window and tears down a wedged attempt.
 *
 * Seams:
 *  - the translation engine sits behind `TranslateBackend`, so [FakeTranslateBackend] scripts every
 *    download outcome directly — no Google dependency is reachable from `:chat` at all
 *  - the probe is constructed in a field initialiser -> `mockkConstructor`
 *  - `appScope` is a top-level property -> `mockkStatic` on `ExtensionsKt`
 *
 * NOT covered here, and deliberately: the progress watchdog's stuck detection (T22 / T23 / T21b)
 * measures elapsed time with `System.currentTimeMillis()`, so a 60 s no-progress window cannot be
 * reached on a virtual clock — it needs either a clock seam in production or an instrumented run.
 *
 * What each case asserts:
 *  - T19  a trigger inside the backoff window does not start a download
 *  - T20  VPN_UP clears the backoff and starts one
 *  - T21  a second trigger while one is in flight is a no-op (single-slot admission)
 *  - T21c cancel() releases the slot, drops the DownloadManager row and resets the state
 *  - T21d a download the backend abandons releases the slot instead of wedging it forever
 *  - T21e requiredModels is the single mapping, and isReady(EN) follows the zh model
 *  - T24c syncOnStart with no active conversation touches the backend zero times
 *  - a backend that cannot translate makes the whole store inert
 *  - the backend's failure kinds map onto the store's retry reasons
 *  - concurrent ensureDownloaded calls never lose a required model
 *  - awaitReady asks the device before downloading, joins an in-flight download, reports a
 *    failed one as not ready, gives up when the one it waits on gets stuck, restarts one that
 *    is already stuck, and keeps waiting on a slow one however long it takes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateModelStoreTest {

    private val zh = ModelId("zh")

    /**
     * The store's `appScope` gets its OWN scheduler, deliberately not `runTest`'s: the progress
     * watchdog is an unbounded `while (isActive) { delay(1s) ... }` loop, and `runTest`'s end-of-test
     * "advance until idle" over a shared scheduler would spin on it until the heap ran out.
     */
    private val storeScheduler = TestCoroutineScheduler()
    private val storeScope = TestScope(UnconfinedTestDispatcher(storeScheduler))

    private lateinit var backend: FakeTranslateBackend
    private lateinit var dbRoomStore: DBRoomStore
    private lateinit var appState: RecordingPreferencesDataStore
    private lateinit var context: Context
    private lateinit var store: TranslateModelStore

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns storeScope

        mockkConstructor(TranslateModelDownloadProbe::class)
        every { anyConstructed<TranslateModelDownloadProbe>().isAvailable() } returns true
        every { anyConstructed<TranslateModelDownloadProbe>().snapshot() } returns null
        every { anyConstructed<TranslateModelDownloadProbe>().remove(any()) } returns Unit

        backend = FakeTranslateBackend()

        context = mockk(relaxed = true)
        every { context.getSystemService(any<String>()) } returns null
        appState = RecordingPreferencesDataStore()
        dbRoomStore = mockk(relaxed = true)
        every { dbRoomStore.getActiveTranslateTargets() } returns setOf(1)

        store = TranslateModelStore(context, appState, dbRoomStore, Gson(), backend)
    }

    @After
    fun tearDown() {
        // Kills the watchdog before the mocks it polls are torn down.
        storeScope.cancel()
        unmockkAll()
    }

    /** Runs everything the store queued at the current virtual time, without advancing the clock. */
    private fun settle() = storeScheduler.runCurrent()

    private fun failsWith(kind: TranslateBackendException.Kind) {
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Fails(kind)
    }

    // --- T21e ---------------------------------------------------------------

    @Test
    fun `T21e requiredModels is the single mapping and both targets resolve to zh`() {
        assertEquals(setOf(zh), store.requiredModels(TranslateLang.ZH))
        assertEquals(setOf(zh), store.requiredModels(TranslateLang.EN))
    }

    @Test
    fun `T21e isReady follows the required model, so a downloaded zh makes EN ready too`() =
        runTest {
            assertFalse("nothing is ready before a download", store.isReady(TranslateLang.EN))

            backend.modelOnDevice = true
            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
            settle()

            assertTrue("EN pivots through the zh model", store.isReady(TranslateLang.EN))
            assertTrue(store.isReady(TranslateLang.ZH))
            assertTrue(store.isReady(zh))
            assertEquals(ModelState.Ready, store.state.value[zh])
        }

    // --- T24c ---------------------------------------------------------------

    @Test
    fun `T24c no conversation with the setting on means the backend is never touched`() = runTest {
        every { dbRoomStore.getActiveTranslateTargets() } returns emptySet()

        store.syncOnStart()

        // syncOnStart resolves the required set on Dispatchers.IO, so wait for the query itself.
        verify(timeout = 5_000) { dbRoomStore.getActiveTranslateTargets() }
        settle()
        assertEquals(0, backend.isModelDownloadedCount)
        assertEquals(0, backend.downloadCount)
    }

    // --- unsupported backend ------------------------------------------------

    @Test
    fun `a build with no translate backend never queries the database or downloads`() = runTest {
        backend.isSupported = false

        store.syncOnStart()
        store.onForeground()
        store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
        store.retryNow(TranslateLang.ZH)
        settle()

        verify(exactly = 0) { dbRoomStore.getActiveTranslateTargets() }
        assertEquals(0, backend.downloadCount)
        assertEquals(0, backend.isModelDownloadedCount)
        assertEquals(emptyMap<ModelId, ModelState>(), store.state.value)
        assertFalse(store.isReady(TranslateLang.ZH))
    }

    // --- T19 / T20 ----------------------------------------------------------

    @Test
    fun `T19 a trigger inside the backoff window does not start a download`() = runTest {
        failsWith(TranslateBackendException.Kind.NETWORK)

        store.ensureDownloaded(zh, DownloadTrigger.NETWORK_AVAILABLE)
        settle()
        assertEquals(1, backend.downloadCount)
        assertTrue(store.state.value[zh] is ModelState.Failed)

        // The 1 minute backoff is now armed; a plain network trigger must respect it.
        store.ensureDownloaded(zh, DownloadTrigger.NETWORK_AVAILABLE)
        settle()

        assertEquals(1, backend.downloadCount)
    }

    @Test
    fun `T20 VPN_UP clears the backoff and starts a new attempt`() = runTest {
        failsWith(TranslateBackendException.Kind.NETWORK)
        store.ensureDownloaded(zh, DownloadTrigger.NETWORK_AVAILABLE)
        settle()

        // A VPN coming up is exactly the event that can make a previously impossible download work.
        store.ensureDownloaded(zh, DownloadTrigger.VPN_UP)
        settle()

        assertEquals(2, backend.downloadCount)
    }

    @Test
    fun `the settings screen's retry also clears the backoff`() = runTest {
        failsWith(TranslateBackendException.Kind.NETWORK)
        store.ensureDownloaded(zh, DownloadTrigger.NETWORK_AVAILABLE)
        settle()

        store.retryNow(TranslateLang.ZH)
        settle()

        assertEquals(2, backend.downloadCount)
    }

    // --- failure classification ---------------------------------------------

    @Test
    fun `the backend's failure kinds decide the reason the settings screen renders`() = runTest {
        val expected = mapOf(
            TranslateBackendException.Kind.NETWORK to FailureReason.NO_NETWORK,
            TranslateBackendException.Kind.NO_SPACE to FailureReason.NO_SPACE,
            TranslateBackendException.Kind.DOWNLOAD_MANAGER_DISABLED to FailureReason.DM_DISABLED,
            TranslateBackendException.Kind.OTHER to FailureReason.SDK_ERROR,
            TranslateBackendException.Kind.UNSUPPORTED to FailureReason.SDK_ERROR,
            TranslateBackendException.Kind.CANCELLED to FailureReason.SDK_ERROR,
        )
        expected.forEach { (kind, reason) ->
            failsWith(kind)
            // SETTINGS_RETRY clears the window the previous iteration armed.
            store.retryNow(TranslateLang.ZH)
            settle()

            assertEquals("kind=$kind", reason, (store.state.value[zh] as ModelState.Failed).reason)
        }
    }

    // --- T21 ----------------------------------------------------------------

    @Test
    fun `T21 a second trigger while one is in flight is a no-op`() = runTest {
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Never

        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        store.ensureDownloaded(zh, DownloadTrigger.FOREGROUND)
        store.ensureDownloaded(zh, DownloadTrigger.NETWORK_AVAILABLE)
        settle()

        assertEquals(1, backend.downloadCount)
        assertTrue(store.state.value[zh] is ModelState.Downloading)
    }

    @Test
    fun `an already-downloaded model short-circuits to Ready without a download`() = runTest {
        backend.modelOnDevice = true

        store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
        settle()

        assertEquals(0, backend.downloadCount)
        assertEquals(ModelState.Ready, store.state.value[zh])
    }

    @Test
    fun `a missing DownloadManager is reported as DM_DISABLED rather than retried`() = runTest {
        every { anyConstructed<TranslateModelDownloadProbe>().isAvailable() } returns false

        store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
        settle()

        assertEquals(0, backend.downloadCount)
        assertEquals(
            FailureReason.DM_DISABLED,
            (store.state.value[zh] as ModelState.Failed).reason,
        )
    }

    // --- T21c ---------------------------------------------------------------

    @Test
    fun `T21c cancel drops the DownloadManager row, releases the slot and resets the state`() =
        runTest {
            backend.downloadOutcome = FakeTranslateBackend.Outcome.Never
            every { anyConstructed<TranslateModelDownloadProbe>().snapshot() } returns
                TranslateModelDownloadProbe.Snapshot(77L, 2, 0, 10L, 100L)

            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
            settle()
            store.cancel(zh)
            settle()

            verify(exactly = 1) { anyConstructed<TranslateModelDownloadProbe>().remove(77L) }
            assertEquals(ModelState.NotStarted, store.state.value[zh])

            // The slot is free again: a fresh trigger starts a second attempt.
            every { anyConstructed<TranslateModelDownloadProbe>().snapshot() } returns null
            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_RETRY)
            settle()
            assertEquals(2, backend.downloadCount)
        }

    // --- T21d ---------------------------------------------------------------

    @Test
    fun `T21d a download the backend abandons releases the slot instead of wedging it`() =
        runTest {
            // ML Kit ends some download failures by cancelling its Task rather than completing it,
            // which the backend surfaces as a coroutine cancellation. Without the store treating
            // that as "attempt over", the single in-flight slot would be held for the rest of the
            // process's life.
            backend.downloadOutcome = FakeTranslateBackend.Outcome.Cancelled

            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
            settle()

            assertEquals(ModelState.NotStarted, store.state.value[zh])

            backend.downloadOutcome = FakeTranslateBackend.Outcome.Never
            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_RETRY)
            settle()

            assertEquals(2, backend.downloadCount)
        }

    // --- awaitReady ---------------------------------------------------------

    @Test
    fun `awaitReady trusts the device over an empty cache and downloads nothing`() = runTest {
        // Cold start with no auto-translate room: syncOnStart never populated the cache.
        backend.modelOnDevice = true
        assertFalse(store.isReady(TranslateLang.ZH))

        assertTrue(store.awaitReady(TranslateLang.ZH))

        assertEquals(0, backend.downloadCount)
        assertTrue(store.isReady(TranslateLang.ZH))
        assertEquals(ModelState.Ready, store.state.value[zh])
    }

    @Test
    fun `awaitReady joins a download another trigger already started`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(gate)
        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        assertEquals(1, backend.downloadCount)

        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertFalse("must wait for the in-flight download", ready.isCompleted)

        gate.complete(Unit)
        settle()

        assertTrue(ready.isCompleted)
        assertTrue(ready.getCompleted())
        assertEquals("the in-flight download is joined, not duplicated", 1, backend.downloadCount)
    }

    @Test
    fun `awaitReady starts the download itself and reports its failure`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(gate)

        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertEquals(1, backend.downloadCount)
        assertFalse(ready.isCompleted)

        gate.completeExceptionally(TranslateBackendException(TranslateBackendException.Kind.NETWORK))
        settle()

        assertTrue(ready.isCompleted)
        assertFalse(ready.getCompleted())
        assertTrue(store.state.value[zh] is ModelState.Failed)
    }

    @Test
    fun `awaitReady gives up when the download it waits on gets stuck`() = runTest {
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Never
        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertFalse(ready.isCompleted)

        // The watchdog's no-progress window runs on the wall clock, so flip the state directly.
        stateFlow().value = mapOf(zh to ModelState.Downloading(10L, 100L, stuck = true))
        settle()

        assertTrue(ready.isCompleted)
        assertFalse(ready.getCompleted())
        assertEquals(1, backend.downloadCount)
    }

    @Test
    fun `awaitReady on an already stuck download restarts it and waits for the new attempt`() = runTest {
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Never
        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        assertEquals(1, backend.downloadCount)
        stateFlow().value = mapOf(zh to ModelState.Downloading(0L, -1L, stuck = true))

        // The retry (a manual translate) must not answer "stuck" from the stale attempt.
        val gate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(gate)
        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertFalse("waits on the restarted attempt", ready.isCompleted)
        assertEquals("the stuck attempt is torn down and started over", 2, backend.downloadCount)
        val restarted = store.state.value[zh]
        assertTrue(restarted is ModelState.Downloading && !restarted.stuck)

        gate.complete(Unit)
        settle()
        assertTrue(ready.isCompleted)
        assertTrue(ready.getCompleted())
    }

    @Test
    fun `a restarted attempt survives the replaced job unwinding`() = runTest {
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Never
        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        stateFlow().value = mapOf(zh to ModelState.Downloading(0L, -1L, stuck = true))

        // The engine restarts the stuck attempt on a manual translate; the replaced job then unwinds
        // (cancellation handler + finally) after the new one has taken the slot.
        val gate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(gate)
        store.ensureDownloaded(zh, DownloadTrigger.MANUAL_TRANSLATE)
        settle()
        assertEquals(2, backend.downloadCount)
        assertTrue("the new attempt's state is intact", store.state.value[zh] is ModelState.Downloading)

        // The wait that follows must join the new attempt, not find an empty slot and start a third.
        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertFalse(ready.isCompleted)
        assertEquals("no duplicate download", 2, backend.downloadCount)

        gate.complete(Unit)
        settle()
        assertTrue(ready.getCompleted())
    }

    @Test
    fun `a waiter on a replaced attempt follows the new one instead of failing`() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.HeldUntil(oldGate)
        val first = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()
        assertFalse(first.isCompleted)
        assertEquals(1, backend.downloadCount)

        // Flip to stuck and restart within one dispatch, so the first waiter's collector only ever
        // sees the conflated NotStarted; its job is then cancelled underneath it.
        val newGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(newGate)
        val second = storeScope.async {
            stateFlow().value = mapOf(zh to ModelState.Downloading(0L, -1L, stuck = true))
            store.awaitReady(TranslateLang.ZH)
        }
        settle()
        assertEquals(2, backend.downloadCount)

        // The replaced attempt ends (as cancelled) after the new one took the slot.
        oldGate.completeExceptionally(CancellationException("replaced"))
        settle()
        assertFalse("the first waiter follows the restarted attempt", first.isCompleted)
        assertFalse(second.isCompleted)

        newGate.complete(Unit)
        settle()
        assertTrue(first.getCompleted())
        assertTrue(second.getCompleted())
    }

    @Test
    fun `a replaced attempt's own error does not overwrite the new attempt`() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.HeldUntil(oldGate)
        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        stateFlow().value = mapOf(zh to ModelState.Downloading(0L, -1L, stuck = true))

        val newGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(newGate)
        store.ensureDownloaded(zh, DownloadTrigger.MANUAL_TRANSLATE)
        settle()
        assertEquals(2, backend.downloadCount)
        assertTrue(store.state.value[zh] is ModelState.Downloading)

        // The dropped row surfaces as an SDK error on the replaced attempt, after the new one started.
        oldGate.completeExceptionally(TranslateBackendException(TranslateBackendException.Kind.OTHER))
        settle()
        assertTrue("new attempt still downloading", store.state.value[zh] is ModelState.Downloading)

        newGate.complete(Unit)
        settle()
        assertTrue(store.state.value[zh] is ModelState.Ready)
    }

    @Test
    fun `a replaced attempt's own success does not overwrite the new attempt`() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.HeldUntil(oldGate)
        store.ensureDownloaded(zh, DownloadTrigger.ENTER_CONVERSATION)
        settle()
        stateFlow().value = mapOf(zh to ModelState.Downloading(0L, -1L, stuck = true))

        val newGate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(newGate)
        store.ensureDownloaded(zh, DownloadTrigger.MANUAL_TRANSLATE)
        settle()
        assertEquals(2, backend.downloadCount)

        // The replaced Task finishes late; only the attempt that owns the slot may publish Ready.
        oldGate.complete(Unit)
        settle()
        assertTrue("new attempt still downloading", store.state.value[zh] is ModelState.Downloading)
        assertFalse(store.isReady(TranslateLang.ZH))

        newGate.complete(Unit)
        settle()
        assertTrue(store.state.value[zh] is ModelState.Ready)
    }

    @Test
    fun `awaitReady keeps waiting on a slow download and succeeds when it lands`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.downloadOutcome = FakeTranslateBackend.Outcome.Gated(gate)
        val ready = storeScope.async { store.awaitReady(TranslateLang.ZH) }
        settle()

        // Far past any plausible wall-clock cap: only progress, not elapsed time, ends the wait.
        storeScheduler.advanceTimeBy(SLOW_DOWNLOAD_MS)
        settle()
        assertFalse("still waiting on the in-flight download", ready.isCompleted)
        assertTrue(store.state.value[zh] is ModelState.Downloading)

        gate.complete(Unit)
        settle()

        assertTrue(ready.isCompleted)
        assertTrue(ready.getCompleted())
        assertEquals(1, backend.downloadCount)
    }

    // --- logout -------------------------------------------------------------

    @Test
    fun `clearOnLogout drops readiness and state but never deletes the device model`() =
        runTest {
            backend.modelOnDevice = true
            store.ensureDownloaded(zh, DownloadTrigger.SETTINGS_SELECT)
            settle()
            assertTrue(store.isReady(TranslateLang.ZH))

            store.clearOnLogout()
            settle()

            assertFalse(store.isReady(TranslateLang.ZH))
            assertEquals(emptyMap<ModelId, ModelState>(), store.state.value)
        }

    // --- requiredCache ------------------------------------------------------

    /**
     * The required-model set grows from several triggers at once — a conversation opening, the
     * settings screen, the network watcher — so the add has to be atomic; a plain read-modify-write
     * silently drops one of two concurrent additions, and the dropped model is then never retried by
     * the network watcher.
     *
     * The downloads are parked on a scheduler this test never advances, so only the set update runs.
     * The set has no public reader, hence the reflection.
     */
    @Test
    fun `concurrent ensureDownloaded calls never lose a required model`() {
        every { appScope } returns TestScope(StandardTestDispatcher(TestCoroutineScheduler()))

        val models = (0 until MODEL_COUNT).map { ModelId("m$it") }
        val start = CountDownLatch(1)
        val threads = models.chunked(MODEL_COUNT / THREAD_COUNT).map { chunk ->
            Thread {
                start.await()
                chunk.forEach { store.ensureDownloaded(it, DownloadTrigger.ENTER_CONVERSATION) }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(JOIN_MS) }

        assertEquals(models.toSet(), requiredCache())
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(): MutableStateFlow<Map<ModelId, ModelState>> {
        val field = TranslateModelStore::class.java.getDeclaredField("_state").apply { isAccessible = true }
        return field.get(store) as MutableStateFlow<Map<ModelId, ModelState>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun requiredCache(): Set<ModelId> {
        val field = TranslateModelStore::class.java.getDeclaredField("requiredCache")
            .apply { isAccessible = true }
        return (field.get(store) as AtomicReference<Set<ModelId>>).get()
    }

    private companion object {
        const val MODEL_COUNT = 200
        const val THREAD_COUNT = 8
        const val JOIN_MS = 10_000L
        const val SLOW_DOWNLOAD_MS = 5 * 60_000L
    }
}
