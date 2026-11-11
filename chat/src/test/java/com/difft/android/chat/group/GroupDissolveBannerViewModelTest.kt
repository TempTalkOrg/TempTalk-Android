package com.difft.android.chat.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.chat.group.mvi.GroupDissolveBannerContract.Intent
import com.difft.android.chat.group.mvi.GroupDissolveBannerViewModel
import com.difft.android.test.TestDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class GroupDissolveBannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val dispatcherRule = TestDispatcherRule(dispatcher)
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val models = mutableListOf<GroupDissolveBannerViewModel>()
    private var now = NOW

    @Before fun setup() {
        mockkObject(ServerTimeProvider)
        every { ServerTimeProvider.nowMillis() } answers { now }
    }

    @After fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        unmockkObject(ServerTimeProvider)
    }

    @Test fun `group row clearing immediately hides without waiting for a tick`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.UpdateGroup(group(NOW + 2 * DAY)))
        assertEquals(2, vm.state.value.daysLeft)
        vm.dispatch(Intent.UpdateGroup(group(null)))
        assertNull(vm.state.value.daysLeft)
        vm.dispatch(Intent.UpdateGroup(group(NOW + DAY)))
        assertEquals(1, vm.state.value.daysLeft)
        vm.dispatch(Intent.UpdateGroup(group(NOW + DAY).apply { status = 1 }))
        assertNull(vm.state.value.daysLeft)
    }

    @Test fun `other groups cannot replace this group deadline and missing row hides`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.UpdateGroup(group(NOW + DAY)))
        vm.dispatch(Intent.UpdateGroup(group(null).apply { gid = "other" }))
        assertEquals(1, vm.state.value.daysLeft)
        vm.dispatch(Intent.UpdateGroup(null))
        assertNull(vm.state.value.daysLeft)
    }

    @Test fun `call bar hides then restores banner without recording dismissal`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.UpdateGroup(group(NOW + DAY)))
        vm.dispatch(Intent.SetJoinCallBarVisible(true))
        assertNull(vm.state.value.daysLeft)
        vm.dispatch(Intent.UpdateGroup(group(NOW + 2 * DAY)))
        assertNull(vm.state.value.daysLeft)
        vm.dispatch(Intent.SetJoinCallBarVisible(false))
        assertEquals(2, vm.state.value.daysLeft)
    }

    @Test fun `dismissal survives deadline changes and reopening and propagates to another window`() = runTest(dispatcher) {
        val store = GroupDissolveDismissals()
        val first = model(store)
        val second = model(store)
        runCurrent()
        listOf(first, second).forEach { it.dispatch(Intent.UpdateGroup(group(NOW + DAY))) }
        first.dispatch(Intent.Dismiss)
        assertNull(first.state.value.daysLeft)
        
        second.dispatch(Intent.UpdateGroup(group(NOW + 10 * DAY)))
        assertNull(second.state.value.daysLeft)
        runCurrent()
        assertNull(second.state.value.daysLeft)
        second.dispatch(Intent.UpdateGroup(group(NOW + 10 * DAY)))
        assertNull(second.state.value.daysLeft)
        val reopened = model(store)
        
        reopened.dispatch(Intent.UpdateGroup(group(NOW + DAY)))
        assertTrue(reopened.state.value.dismissed)
        assertNull(reopened.state.value.daysLeft)
    }

    @Test fun `injected tick refreshes ceil days and hides after deadline with no new row`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.UpdateGroup(group(NOW + DAY + 1)))
        assertEquals(2, vm.state.value.daysLeft)
        now += 2
        ticks.emit(Unit)
        runCurrent()
        assertEquals(1, vm.state.value.daysLeft)
        now = NOW + DAY + 1
        ticks.emit(Unit)
        runCurrent()
        assertNull(vm.state.value.daysLeft)
    }

    @Test fun `lifecycle refresh rechecks time immediately after being away`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.dispatch(Intent.UpdateGroup(group(NOW + 1)))
        now++
        vm.dispatch(Intent.Refresh)
        assertNull(vm.state.value.daysLeft)
    }

    private fun model(store: GroupDissolveDismissals = GroupDissolveDismissals()): GroupDissolveBannerViewModel {
        val ticker = mockk<CountdownTicker>()
        every { ticker.ticks } returns ticks
        return GroupDissolveBannerViewModel(
            SavedStateHandle(mapOf(GroupChatFragment.ARG_GROUP_ID to GID)), store, ticker,
        ).also(models::add)
    }

    private fun group(expiry: Long?) = GroupModel().apply { gid = GID; expireTime = expiry; status = 0 }
    private companion object {
        const val GID = "banner-group"
        const val NOW = 1_800_000_000_000L
        const val DAY = 86_400_000L
    }
}
