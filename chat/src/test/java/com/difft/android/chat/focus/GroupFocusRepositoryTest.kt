package com.difft.android.chat.focus

import com.difft.android.network.BaseResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Version gate, GET-only `featureEnabled`, coalesced GET, local expiry and write error mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupFocusRepositoryTest {

    private val api: FocusApi = mockk()
    private val store: GroupFocusStore = mockk()
    private var persisted = GroupFocusState()
    private val written = mutableListOf<GroupFocusState>()

    init {
        coEvery { store.read() } answers { persisted }
        coEvery { store.write(any()) } answers {
            val state = firstArg<GroupFocusState>()
            written += state
            persisted = state
        }
    }

    private fun TestScope.repository() =
        GroupFocusRepository({ api }, store, backgroundScope, clock = { START + currentTime }).also { runCurrent() }

    private fun snap(version: Int, enabled: Boolean = true, until: Long? = null, vararg gids: String = arrayOf("A")) =
        FocusSnapshot(enabled = enabled, groupIds = if (enabled) gids.toList() else emptyList(), until = until, version = version)

    private fun ok(feature: Boolean, focus: FocusSnapshot) =
        BaseResponse(ver = 1, status = 0, reason = "OK", data = FocusResponse(feature, START, focus))

    private fun failed(status: Int) = BaseResponse<FocusResponse>(ver = 1, status = status, reason = "err", data = null)

    // ---------------------------------------------------------------- version gate

    @Test
    fun `a snapshot replaces the cache only when its version is greater`() = runTest {
        val repo = repository()

        repo.apply(snap(2), "USER")
        assertEquals(2, repo.state.value.focus.version)

        repo.apply(snap(1, enabled = false), "USER")
        assertTrue(repo.state.value.focus.enabled)
        assertEquals(2, repo.state.value.focus.version)

        repo.apply(snap(2, enabled = false), "USER") // local echo of the same version
        assertTrue(repo.state.value.focus.enabled)

        repo.apply(snap(3, enabled = false), "EXPIRED")
        assertFalse(repo.state.value.focus.enabled)
        assertEquals(3, repo.state.value.focus.version)
        assertEquals(listOf(2, 3), written.map { it.focus.version })
    }

    @Test
    fun `duplicate group ids are collapsed`() = runTest {
        val repo = repository()
        repo.apply(snap(1, gids = arrayOf("A", "B", "A")), "USER")
        assertEquals(listOf("A", "B"), repo.state.value.focus.groupIds)
    }

    @Test
    fun `a foreign cache loaded before the account is known is dropped once it is`() = runTest {
        persisted = GroupFocusState(featureEnabled = true, focus = snap(9), ownerUid = "other")
        var me: String? = null
        val repo = GroupFocusRepository({ api }, store, backgroundScope, selfId = { me }, clock = { START + currentTime }).also { runCurrent() }
        repo.ensureLoaded()
        assertEquals(9, repo.state.value.focus.version) // owner unknown: nothing to judge yet

        me = "me"
        coEvery { api.getFocus() } returns ok(true, snap(1))
        repo.requestRefresh("test")
        runCurrent()

        assertEquals("me", repo.state.value.ownerUid)
        assertEquals(1, repo.state.value.focus.version) // the lower own version replaced the foreign one
    }

    // ---------------------------------------------------------------- featureEnabled

    @Test
    fun `featureEnabled comes only from a GET`() = runTest {
        val repo = repository()
        repo.apply(snap(1), "USER")
        assertFalse(repo.featureEnabled)
        assertFalse(repo.effective.value.active)

        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(1))
        repo.requestRefresh("test")
        runCurrent()
        assertTrue(repo.featureEnabled)
        assertTrue(repo.effective.value.active)

        // A write response claiming the feature is off does not flip it.
        coEvery { api.endFocus() } returns ok(feature = false, focus = snap(2, enabled = false))
        repo.end()
        assertTrue(repo.featureEnabled)
        assertFalse(repo.effective.value.active)

        // A later GET does.
        coEvery { api.getFocus() } returns ok(feature = false, focus = snap(2, enabled = false))
        repo.requestRefresh("test")
        runCurrent()
        assertFalse(repo.featureEnabled)
    }

    @Test
    fun `feature switch from a GET applies even when the focus version did not move`() = runTest {
        val repo = repository()
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(0, enabled = false))
        repo.requestRefresh("test")
        runCurrent()
        assertTrue(repo.featureEnabled)
        assertEquals(GroupFocusState(true, FocusSnapshot()), persisted.copy(focus = FocusSnapshot()))
    }

    @Test
    fun `persisted state is loaded before the first mutation and survives a new instance`() = runTest {
        persisted = GroupFocusState(true, snap(5, until = START + 60_000))
        val repo = repository()
        assertTrue(repo.effective.value.active)
        assertEquals(setOf("A"), repo.effective.value.groupIds)

        // An older notify must not roll the loaded state back.
        repo.apply(snap(4, enabled = false), "USER")
        assertTrue(repo.effective.value.active)
        assertTrue(written.isEmpty())
    }

    // ---------------------------------------------------------------- expiry

    @Test
    fun `effective focus ends locally at until and a GET is requested`() = runTest {
        val repo = repository()
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(1, until = START + 1_000))
        repo.requestRefresh("test")
        runCurrent()
        assertTrue(repo.effective.value.active)
        assertTrue(repo.effectiveNow().active)

        advanceTimeBy(999)
        assertTrue(repo.effective.value.active)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(repo.effective.value.active)
        assertFalse(repo.effectiveNow().active)
        // Raw snapshot is untouched: expiry needs no new version.
        assertTrue(repo.state.value.focus.enabled)
        coVerify(atLeast = 2) { api.getFocus() }
    }

    @Test
    fun `changing until re-arms the timer`() = runTest {
        val repo = repository()
        repo.apply(snap(1, until = START + 1_000), "USER")
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(1, until = START + 1_000))
        repo.requestRefresh("enable feature")
        runCurrent()

        repo.apply(snap(2, until = START + 5_000), "USER")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(repo.effective.value.active)

        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(repo.effective.value.active)
    }

    // ---------------------------------------------------------------- coalescer

    @Test
    fun `refresh requests coalesce into one in flight plus one trailing run`() = runTest {
        val repo = repository()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { api.getFocus() } coAnswers {
            calls++
            if (calls == 1) gate.await()
            ok(feature = true, focus = snap(calls))
        }

        repo.requestRefresh("a")
        runCurrent()
        assertEquals(1, calls)

        repo.requestRefresh("b")
        repo.requestRefresh("c")
        repo.requestRefresh("d")
        runCurrent()
        assertEquals(1, calls) // still the first one

        gate.complete(Unit)
        runCurrent()
        assertEquals(2, calls) // exactly one trailing run, none dropped, none multiplied
        assertEquals(2, repo.state.value.focus.version)

        repo.requestRefresh("e")
        runCurrent()
        assertEquals(3, calls)
    }

    @Test
    fun `a failed GET does not wedge the coalescer`() = runTest {
        val repo = repository()
        var calls = 0
        coEvery { api.getFocus() } coAnswers {
            calls++
            if (calls == 1) throw IOException("offline")
            ok(feature = true, focus = snap(1))
        }

        repo.requestRefresh("a")
        runCurrent()
        repo.requestRefresh("b")
        runCurrent()

        assertEquals(2, calls)
        assertTrue(repo.featureEnabled)
    }

    // ---------------------------------------------------------------- writes

    @Test
    fun `start and switch send the right bodies and apply the returned snapshot`() = runTest {
        val repo = repository()
        coEvery { api.openFocus(FocusOpenRequest(listOf("A"), 3600)) } returns ok(true, snap(1, until = START + 3_600_000))
        coEvery { api.openFocus(FocusOpenRequest(listOf("B"), null)) } returns ok(true, snap(2, until = START + 3_600_000, gids = arrayOf("B")))

        val started = repo.start("A", 3600)
        assertIs<FocusResult.Success>(started)
        assertEquals(listOf("A"), repo.state.value.focus.groupIds)

        val switched = repo.switchGroup("B")
        assertIs<FocusResult.Success>(switched)
        assertEquals(listOf("B"), repo.state.value.focus.groupIds)
        assertEquals(START + 3_600_000, repo.state.value.focus.until)
    }

    @Test
    fun `the notify echo of an own write is a no-op`() = runTest {
        val repo = repository()
        coEvery { api.changeUntil(FocusUntilRequest(28800)) } returns ok(true, snap(4, until = START + 28_800_000))
        repo.changeUntil(28800)
        val writes = written.size

        repo.apply(snap(4, until = START + 28_800_000), "USER")

        assertEquals(writes, written.size)
    }

    @Test
    fun `updateOptions sends only the given fields and rejects an empty patch locally`() = runTest {
        val repo = repository()
        coEvery { api.updateOptions(FocusOptionsRequest(foldOthers = false)) } returns
            ok(true, snap(1).copy(foldOthers = false))

        assertIs<FocusResult.Success>(repo.updateOptions(foldOthers = false))
        assertFalse(repo.state.value.focus.foldOthers)

        assertEquals(FocusResult.Failure(FocusError.InvalidParameter), repo.updateOptions())
        coVerify(exactly = 1) { api.updateOptions(any()) }
    }

    @Test
    fun `body status maps to FocusError and 31 and 32 trigger a GET`() = runTest {
        val repo = repository()
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(0, enabled = false))
        coEvery { api.endFocus() } returns failed(1) andThen failed(2) andThen failed(3) andThen failed(31) andThen failed(32) andThen failed(99)

        assertEquals(FocusResult.Failure(FocusError.InvalidParameter), repo.end())
        assertEquals(FocusResult.Failure(FocusError.NoPermission), repo.end())
        assertEquals(FocusResult.Failure(FocusError.NoSuchGroup), repo.end())
        runCurrent()
        coVerify(exactly = 0) { api.getFocus() }

        assertEquals(FocusResult.Failure(FocusError.Conflict), repo.end())
        runCurrent()
        coVerify(exactly = 1) { api.getFocus() }

        assertEquals(FocusResult.Failure(FocusError.FeatureDisabled), repo.end())
        runCurrent()
        coVerify(exactly = 2) { api.getFocus() }

        assertEquals(FocusResult.Failure(FocusError.Unknown(99)), repo.end())
    }

    @Test
    fun `a transport failure is a Network error and leaves the cache alone`() = runTest {
        val repo = repository()
        repo.apply(snap(1), "USER")
        coEvery { api.endFocus() } throws IOException("offline")

        val result = repo.end()

        assertIs<FocusResult.Failure>(result)
        assertIs<FocusError.Network>(result.error)
        assertEquals(1, repo.state.value.focus.version)
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }

    @Test
    fun `a same-version GET re-judges effective after the clock moved backwards`() = runTest {
        var offset = 0L // trusted-clock correction applied on top of the test clock
        persisted = GroupFocusState(featureEnabled = true, focus = snap(2, until = START + 10_000L))
        val repo = GroupFocusRepository({ api }, store, backgroundScope, clock = { START + 20_000L + offset + currentTime }).also { runCurrent() }
        // The device clock ran ahead: the cached running focus derives as ended and no timer is armed.
        assertFalse(repo.effective.value.active)

        offset = -15_000L // a server response re-anchors the clock to the earlier, true time
        coEvery { api.getFocus() } returns ok(true, snap(2, until = START + 10_000L))
        repo.requestRefresh("test")
        runCurrent()

        assertTrue(repo.effective.value.active)
        assertEquals(listOf<GroupFocusState>(), written) // nothing changed, nothing persisted

        advanceTimeBy(5_001L) // the re-armed timer fires at until and flips effective again
        runCurrent()
        assertFalse(repo.effective.value.active)
    }

    // ---------------------------------------------------------------- status 32 and the switch epoch

    private suspend fun TestScope.repositoryWithFeatureOn(): GroupFocusRepository {
        val repo = repository()
        repo.apply(snap(1), "USER")
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(1))
        repo.requestRefresh("init")
        runCurrent()
        assertTrue(repo.featureEnabled)
        return repo
    }

    @Test
    fun `status 32 on a write switches the feature off at once, persists it, and asks the server`() = runTest {
        val repo = repositoryWithFeatureOn()
        assertTrue(repo.effective.value.active)
        coEvery { api.endFocus() } returns failed(32)

        val result = repo.end()

        assertEquals(FocusResult.Failure(FocusError.FeatureDisabled), result)
        assertFalse(repo.featureEnabled) // before any GET answered
        assertFalse(repo.effective.value.active) // entries hide
        assertFalse(persisted.featureEnabled)
        assertEquals(1, persisted.focus.version) // only the switch changed
    }

    @Test
    fun `status 32 on a GET switches the feature off without looping another GET`() = runTest {
        val repo = repositoryWithFeatureOn()
        coEvery { api.getFocus() } returns failed(32)

        repo.requestRefresh("again")
        runCurrent()

        assertFalse(repo.featureEnabled)
        assertFalse(persisted.featureEnabled)
        coVerify(exactly = 2) { api.getFocus() } // the init GET and the one that said 32
    }

    @Test
    fun `an in-flight GET that started feature on cannot switch it back after a 32, the trailing GET decides`() = runTest {
        val repo = repositoryWithFeatureOn()
        val firstGate = CompletableDeferred<BaseResponse<FocusResponse>>()
        val trailingGate = CompletableDeferred<BaseResponse<FocusResponse>>()
        var calls = 0
        coEvery { api.getFocus() } coAnswers {
            calls++
            if (calls == 1) firstGate.await() else trailingGate.await()
        }
        repo.requestRefresh("foreground")
        runCurrent()
        assertEquals(1, calls) // in flight, started while the feature was on

        coEvery { api.endFocus() } returns failed(32)
        repo.end() // another request meets status 32 meanwhile
        assertFalse(repo.featureEnabled)

        firstGate.complete(ok(feature = true, focus = snap(5))) // the old answer arrives late, still saying "on"
        runCurrent()

        assertFalse(repo.featureEnabled) // its feature value is dropped ...
        assertEquals(5, repo.state.value.focus.version) // ... its focus is still applied by version
        assertEquals(2, calls) // the trailing GET started by the 32 is now running

        trailingGate.complete(ok(feature = true, focus = snap(5))) // started after the 32: it decides
        runCurrent()
        assertTrue(repo.featureEnabled)
    }

    @Test
    fun `a successful write response never sets featureEnabled, true or false`() = runTest {
        val repo = repository() // feature off
        coEvery { api.openFocus(any()) } returns ok(feature = true, focus = snap(2))

        assertIs<FocusResult.Success>(repo.start("A", 3600))

        assertFalse(repo.featureEnabled)
        assertEquals(2, repo.state.value.focus.version)
    }

    // ---------------------------------------------------------------- local recompute and local group exit

    @Test
    fun `recomputeLocally re-judges the cached state against the clock and re-arms the timer without a request`() = runTest {
        var offset = 0L
        persisted = GroupFocusState(featureEnabled = true, focus = snap(2, until = START + 10_000L))
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(2, until = START + 10_000L)) // for the expiry refresh only
        val repo = GroupFocusRepository({ api }, store, backgroundScope, clock = { START + offset + currentTime }).also { runCurrent() }
        assertTrue(repo.effective.value.active)

        offset = 20_000L // the process slept through `until`; the timer did not fire
        assertTrue(repo.effective.value.active) // stale until someone re-derives it
        repo.recomputeLocally()
        assertFalse(repo.effective.value.active)

        offset = -5_000L // the trusted clock was corrected backwards
        repo.recomputeLocally()
        assertTrue(repo.effective.value.active)
        coVerify(exactly = 0) { api.getFocus() } // re-deriving never requests anything itself
        assertEquals(listOf<GroupFocusState>(), written)

        advanceTimeBy(15_001L) // the re-armed timer flips it again
        runCurrent()
        assertFalse(repo.effective.value.active)
    }

    @Test
    fun `a successful local leave or disband requests a GET, a failed one does not`() = runTest {
        val repo = repository()
        coEvery { api.getFocus() } returns ok(feature = true, focus = snap(1))

        repo.onLocalGroupExit(failed(500))
        runCurrent()
        coVerify(exactly = 0) { api.getFocus() }

        repo.onLocalGroupExit(BaseResponse<Any>(ver = 1, status = 0, reason = "OK", data = null))
        runCurrent()
        coVerify(exactly = 1) { api.getFocus() }
    }
}
