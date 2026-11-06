package com.difft.android.setting.notificationtest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.difft.android.IndexActivity
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestClock
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.dualpane.DualPaneReconciler
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract
import com.difft.android.setting.notificationtest.mvi.NotificationTestViewModel
import com.difft.android.test.FoldableTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * Calls the real IndexActivity.onNewIntent on a constructed, uncreated Activity, borrowing a real
 * navigator/Fragment stack from the Hilt pane host. This isolates entry-point generation, Intent
 * identity, cleanup and queued presentation; it does not claim full Index startup/lifecycle or
 * Android task-stack/device coverage. No handler or navigation decision is copied into the test.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h1000dp")
@OptIn(ExperimentalCoroutinesApi::class)
class IndexNotificationReturnEntryIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()

    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val repository: NotificationTestRepository = mockk()
    @BindValue @JvmField val poster: NotificationTestPoster = mockk()
    @BindValue @JvmField val device: NotificationTestDevice = mockk()
    @BindValue @JvmField val runs = NotificationTestRuns()
    @BindValue @JvmField val clock: NotificationTestClock = mockk()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.testDispatcher)
    @BindValue @JvmField val lifetime = NotificationTestLifetime(repository, poster, runs, cleanupScope)
    @Inject lateinit var returns: NotificationTestReturns

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var pane: ActivityController<NotificationPaneWiringTestActivity>? = null
    private lateinit var entry: ActivityController<IndexActivity>
    private val nav: PaneNavigator get() = pane!!.get().navigator

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        coEvery { repository.readReadiness() } returns NotificationReadiness(items = emptyList())
        coEvery { repository.fingerprint(any()) } returns "entry-fingerprint"
        coEvery { repository.record(any(), any(), any(), any()) } just Runs
        coEvery { repository.cancelCall(any()) } just Runs
        every { repository.callShownRun } returns MutableStateFlow(null)
        coEvery { poster.post(any()) } returns true
        coEvery { poster.cancel(any(), any()) } returns Unit
        every { device.isDndOn() } returns false
        every { clock.nowMs() } returns 100_000L
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        hilt.inject()
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()

        // get() attaches a real Activity but deliberately does not invoke its large onCreate.
        entry = Robolectric.buildActivity(IndexActivity::class.java)
        assertEquals(Lifecycle.State.INITIALIZED, entry.get().lifecycle.currentState)
        ReflectionHelpers.setField(entry.get(), "navigator", nav)
        ReflectionHelpers.setField(entry.get(), "paneReconciler", mockk<DualPaneReconciler>(relaxed = true))
        entry.get().notificationTestReturns = returns
    }

    @After
    fun tearDown() {
        // An uncreated Activity has no lifecycle to destroy. Cancel its actual entry coroutine scope.
        if (::entry.isInitialized) entry.get().lifecycleScope.cancel()
        pane?.let { controller ->
            if (controller.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (controller.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
        }
        dispatchers.testDispatcher.scheduler.runCurrent()
        cleanupScope.cancel()
        unmockkObject(AppStartup)
    }

    private fun idle(composeExpected: Boolean = false) {
        dispatchers.testDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        if (composeExpected) compose.waitForIdle()
    }

    private fun sentinel(marker: String) = Fragment().apply {
        arguments = Bundle().apply { putString("entry_test_marker", marker) }
    }

    private fun mount(page: Fragment) {
        compose.runOnUiThread { assertTrue(nav.showMePage(page)) }
        idle(composeExpected = page is NotificationTestFragment)
    }

    private fun deliver(intent: Intent) {
        compose.runOnUiThread { entry.newIntent(intent) }
        assertSame(intent, entry.get().intent) // Production onNewIntent must call setIntent.
        assertEquals(Lifecycle.State.INITIALIZED, entry.get().lifecycle.currentState)
    }

    private fun intent(route: NotificationTestReturnRoute): Intent =
        route.applyTo(Intent(context, IndexActivity::class.java))

    private fun stale(kind: NotificationTestKind = NotificationTestKind.BANNER) =
        NotificationTestReturnRoute(UUID.randomUUID().toString(), UUID.randomUUID().toString(), kind)

    private fun suspendCleanup(route: NotificationTestReturnRoute): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { poster.cancel(route.runId, route.kind) } coAnswers {
            entered.complete(Unit)
            release.await()
            Unit
        }
        return entered to release
    }

    /** Observes real fragment creation so a briefly inserted, then overwritten fallback cannot pass. */
    private fun recordNotificationCreations(): MutableList<Fragment> {
        val created = mutableListOf<Fragment>()
        pane!!.get().supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                    if (fragment is NotificationTestFragment) created += fragment
                }
            },
            true,
        )
        return created
    }

    private fun assertCoveredLiveReturnAndDuplicate() {
        val page = NotificationTestFragment.newInstance()
        mount(page)
        val vm = ViewModelProvider(page)[NotificationTestViewModel::class.java]
        compose.runOnUiThread { vm.dispatch(NotificationTestContract.Intent.Start(NotificationTestKind.BANNER)) }
        compose.waitUntil(5_000) { vm.state.value.dialog is NotificationTestContract.Dialog.Intro }
        compose.runOnUiThread { vm.dispatch(NotificationTestContract.Intent.ConfirmStart) }
        val route = NotificationTestReturnRoute(vm.owner.ownerId, vm.state.value.runId!!, NotificationTestKind.BANNER)
        val coveredBy = sentinel("covered-leaf")
        compose.runOnUiThread { assertTrue(nav.push(PaneNavigator.TAB_ME, coveredBy)) }
        idle()
        compose.runOnUiThread { nav.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        assertEquals(listOf(page, coveredBy), nav.retainedMePages())
        deliver(intent(route))
        idle()
        assertEquals(PaneNavigator.TAB_ME, nav.currentTab)
        assertEquals(listOf(page, coveredBy), nav.retainedMePages())
        assertSame(vm, ViewModelProvider(page)[NotificationTestViewModel::class.java])
        assertEquals(route.runId, vm.state.value.runId)
        assertTrue(page.matchesReturn(route))
        val committedVersion = nav.requestVersion
        deliver(intent(route))
        idle()
        assertEquals(committedVersion, nav.requestVersion) // Duplicate was consumed before another presentation.
        assertEquals(listOf(page, coveredBy), nav.retainedMePages())
        coVerify(exactly = 0) { poster.cancel(any(), any()) }
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `actual Index entry retains a covered live owner and consumes duplicate once in wide layout`() {
        assertTrue(nav.isDualPane)
        assertCoveredLiveReturnAndDuplicate()
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun `actual Index entry presents the retained live Me stack without popping its cover in narrow layout`() {
        assertFalse(nav.isDualPane)
        assertCoveredLiveReturnAndDuplicate()
        assertTrue(nav.detailCoversList.value)
    }

    @Test
    fun `actual Index stale CALL return cleans only its token and creates an idle hosted page once`() {
        val previous = sentinel("before-stale-call")
        mount(previous)
        val route = stale(NotificationTestKind.CALL)
        deliver(intent(route))
        idle(composeExpected = true)
        val idlePage = nav.retainedMePages().single() as NotificationTestFragment
        assertNotSame(previous, idlePage)
        assertFalse(idlePage.hasActiveRun())
        assertFalse(idlePage.matchesReturn(route))
        val idleState = ViewModelProvider(idlePage)[NotificationTestViewModel::class.java].state.value
        assertNull(idleState.running)
        assertNull(idleState.runId)
        assertNull(idleState.phase)
        assertNull(idleState.postedFingerprint)
        coVerify(exactly = 1) { poster.cancel(route.runId, route.kind) }
        coVerify(exactly = 1) { repository.cancelCall(route.runId) }
        coVerify(exactly = 0) { poster.post(any()) }
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
        val version = nav.requestVersion
        deliver(intent(route))
        idle()
        assertEquals(version, nav.requestVersion)
        assertSame(idlePage, nav.retainedMePages().single())
        coVerify(exactly = 1) { poster.cancel(route.runId, route.kind) }
    }

    @Test
    fun `newer ordinary onNewIntent invalidates an older return suspended in cleanup`() {
        val original = sentinel("ordinary-intent-wins")
        mount(original)
        compose.runOnUiThread { nav.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        val version = nav.requestVersion
        val route = stale()
        val (entered, release) = suspendCleanup(route)
        deliver(intent(route))
        assertTrue(entered.isCompleted)
        val ordinary = Intent(context, IndexActivity::class.java)
        deliver(ordinary) // Real generic no-payload path returns without unrelated startup dependencies.
        compose.runOnUiThread { release.complete(Unit) }
        idle()
        assertSame(ordinary, entry.get().intent)
        assertEquals(version, nav.requestVersion)
        assertEquals(PaneNavigator.TAB_CONTACTS, nav.currentTab)
        assertEquals(listOf(original), nav.retainedMePages())
    }

    @Test
    fun `newer local Me opening invalidates the request version captured before cleanup`() {
        mount(sentinel("old-root"))
        val created = recordNotificationCreations()
        val route = stale()
        val (entered, release) = suspendCleanup(route)
        deliver(intent(route))
        assertTrue(entered.isCompleted)
        val newer = sentinel("new-local-root")
        compose.runOnUiThread { assertTrue(nav.showMePage(newer)) }
        idle()
        compose.runOnUiThread { release.complete(Unit) }
        idle()
        assertEquals(listOf(newer), nav.retainedMePages())
        assertTrue(created.isEmpty())
    }

    @Test
    fun `queued Index return is discarded before fragment creation when a newer root wins before commit`() {
        mount(sentinel("state-saved-root"))
        val created = recordNotificationCreations()
        compose.runOnUiThread { pane!!.pause().stop() }
        assertTrue(pane!!.get().supportFragmentManager.isStateSaved)
        val route = stale()
        deliver(intent(route))
        idle()
        coVerify(exactly = 1) { poster.cancel(route.runId, route.kind) }
        assertTrue(created.isEmpty())
        val newer = sentinel("newer-queued-root")
        compose.runOnUiThread { assertTrue(nav.showMePage(newer)) }
        compose.runOnUiThread { pane!!.start().resume().visible() }
        idle()
        assertEquals(listOf(newer), nav.retainedMePages())
        assertTrue(created.isEmpty()) // Stronger than the final root: no obsolete fallback ever mounted.
    }

    @Test
    fun `actual Index malformed notification category is a no-op after setting its Intent`() {
        val original = sentinel("malformed-route-root")
        mount(original)
        compose.runOnUiThread { nav.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        val version = nav.requestVersion
        val malformed = Intent(context, IndexActivity::class.java).addCategory(NotificationTestReturnRoute.CATEGORY)
        deliver(malformed)
        idle()
        assertEquals(version, nav.requestVersion)
        assertEquals(PaneNavigator.TAB_CONTACTS, nav.currentTab)
        assertEquals(listOf(original), nav.retainedMePages())
        coVerify(exactly = 0) { poster.cancel(any(), any()) }
    }
}
