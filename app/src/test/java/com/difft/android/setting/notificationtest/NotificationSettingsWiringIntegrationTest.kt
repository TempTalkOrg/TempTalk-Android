package com.difft.android.setting.notificationtest

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestClock
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestOwner
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.setting.BackgroundConnectionSettingsFragment
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Dialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Phase
import com.difft.android.setting.notificationtest.mvi.NotificationTestViewModel
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.FoldableTestApplication_Application
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import javax.inject.Inject
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR

/** Real Hilt pages, real host lifecycle, real shell entry points, and real Compose controls. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h1000dp")
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsWiringIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()

    @BindValue @JvmField val repository: NotificationTestRepository = mockk()
    @BindValue @JvmField val poster: NotificationTestPoster = mockk()
    @BindValue @JvmField val device: NotificationTestDevice = mockk()
    @BindValue @JvmField val runs = NotificationTestRuns()
    @BindValue @JvmField val clock: NotificationTestClock = mockk()
    // TestUserInfoModule replaces StorageBindings and supplies only the legacy UserManager alias.
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val messageServiceManager: MessageServiceManager = mockk(relaxed = true)
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatchers.testDispatcher)
    @BindValue @JvmField val lifetime = NotificationTestLifetime(repository, poster, runs, cleanupScope)
    @Inject lateinit var users: UserManager

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var pane: ActivityController<NotificationPaneWiringTestActivity>? = null
    private var shell: ActivityController<NotificationTestActivity>? = null
    private var plain: ActivityController<NotificationPlainWiringTestActivity>? = null

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        coEvery { repository.readReadiness() } returns NotificationReadiness(items = emptyList())
        coEvery { repository.fingerprint(any()) } returns "posted-fingerprint"
        coEvery { repository.record(any(), any(), any(), any()) } just Runs
        coEvery { repository.cancelCall(any()) } just Runs
        every { repository.callShownRun } returns MutableStateFlow(null)
        coEvery { poster.post(any()) } returns true
        coEvery { poster.cancel(any(), any()) } returns Unit
        every { device.isDndOn() } returns false
        every { clock.nowMs() } answers { 100_000L + dispatchers.testDispatcher.scheduler.currentTime }
        // BaseActivity startup accounting and toast rendering are unrelated application services.
        mockkObject(AppStartup, ToastUtil)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        every { ToastUtil.showLong(any<String>()) } just Runs
        every { ToastUtil.show(any<Int>()) } just Runs
        hilt.inject()
    }

    @After
    fun tearDown() {
        pane?.pause()?.stop()?.destroy()
        shell?.pause()?.stop()?.destroy()
        plain?.pause()?.stop()?.destroy()
        dispatchers.testDispatcher.scheduler.runCurrent()
        cleanupScope.cancel()
        unmockkObject(AppStartup, ToastUtil)
    }

    private fun idle() {
        dispatchers.testDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun model(page: NotificationTestFragment): NotificationTestViewModel =
        ViewModelProvider(page)[NotificationTestViewModel::class.java]

    private fun shellPage(): NotificationTestFragment =
        shell!!.get().supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container) as NotificationTestFragment

    private fun startBanner(page: NotificationTestFragment): NotificationTestViewModel {
        val vm = model(page)
        compose.onNodeWithTag(testButtonTag(NotificationTestKind.BANNER)).performClick()
        compose.waitUntil(5_000) { vm.state.value.dialog is Dialog.Intro }
        compose.onNode(hasText(context.getString(ChatR.string.notification_test_start)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { vm.state.value.phase == Phase.COUNTDOWN }
        return vm
    }

    private fun advanceBannerToQuestion(vm: NotificationTestViewModel) {
        dispatchers.testDispatcher.scheduler.advanceTimeBy(5_000)
        dispatchers.testDispatcher.scheduler.runCurrent()
        compose.waitUntil(5_000) { vm.state.value.phase == Phase.POSTED }
        dispatchers.testDispatcher.scheduler.advanceTimeBy(3_000)
        dispatchers.testDispatcher.scheduler.runCurrent()
        compose.waitUntil(5_000) { vm.state.value.dialog is Dialog.Question }
        idle()
    }

    private fun liveRoute(vm: NotificationTestViewModel): NotificationTestReturnRoute =
        NotificationTestReturnRoute(vm.owner.ownerId, vm.state.value.runId!!, NotificationTestKind.BANNER)

    @Test
    fun `real detail owner hides a timed question in another tab and recreated page answers the same run once`() {
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnUiThread {
            val nav = pane!!.get().navigator
            nav.selectTab(PaneNavigator.TAB_ME)
            nav.replaceRoot(PaneNavigator.TAB_ME, NotificationTestFragment.newInstance(), null)
        }
        idle()
        val page = pane!!.get().notificationPage()
        val vm = startBanner(page)
        val route = liveRoute(vm)
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        assertFalse(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        advanceBannerToQuestion(vm)
        compose.onNodeWithText(context.getString(ChatR.string.notification_test_banner_question)).assertDoesNotExist()

        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        compose.onAllNodesWithText(context.getString(ChatR.string.notification_test_banner_question)).assertCountEquals(1)
        compose.runOnUiThread { pane!!.recreate() }
        idle()
        val restored = pane!!.get().notificationPage()
        assertNotSame(page, restored)
        assertSame(vm, model(restored))
        assertTrue(restored.matchesReturn(route))
        compose.onAllNodesWithText(context.getString(ChatR.string.notification_test_banner_question)).assertCountEquals(1)
        compose.onNodeWithText(context.getString(ChatR.string.notification_test_yes)).performClick()
        compose.waitUntil(5_000) { !restored.hasActiveRun() }
        coVerify(exactly = 1) { repository.record(NotificationTestKind.BANNER, NotificationTestOutcome.OK, "posted-fingerprint", any()) }
    }

    @Test
    fun `two-pane Me root test page shows no back arrow and leaves system Back to the host`() {
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnUiThread {
            val nav = pane!!.get().navigator
            nav.selectTab(PaneNavigator.TAB_ME)
            nav.replaceRoot(PaneNavigator.TAB_ME, NotificationTestFragment.newInstance(), null)
        }
        idle()
        val page = pane!!.get().notificationPage()
        assertTrue(pane!!.get().navigator.isDualPane)
        assertFalse(page.detailNavigation().canPop(page))
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.runOnUiThread { pane!!.get().onBackPressedDispatcher.onBackPressed() }
        idle()
        // No page callback consumed Back, so the host's own handling (here the Activity default) ran.
        assertTrue(pane!!.get().isFinishing)
    }

    @Test
    fun `real shell onNewIntent retains an exact live owner across recreation and replaces an idle stale owner`() {
        shell = Robolectric.buildActivity(NotificationTestActivity::class.java).setup().visible()
        idle()
        val first = shellPage()
        val vm = startBanner(first)
        val route = liveRoute(vm)
        compose.runOnUiThread { shell!!.newIntent(route.applyTo(NotificationTestActivity.createIntent(shell!!.get()))) }
        idle()
        assertSame(first, shellPage())
        assertSame(vm, model(shellPage()))
        assertEquals(route.runId, vm.state.value.runId)
        assertEquals(NotificationTestOwner.Host.SHELL, vm.owner.host)
        coVerify(exactly = 0) { poster.cancel(any(), any()) }

        compose.runOnUiThread { shell!!.recreate() }
        idle()
        val recreated = shellPage()
        assertNotSame(first, recreated)
        assertSame(vm, model(recreated))
        compose.runOnUiThread { shell!!.newIntent(route.applyTo(NotificationTestActivity.createIntent(shell!!.get()))) }
        idle()
        assertSame(recreated, shellPage())
        compose.onNodeWithTag(testButtonTag(NotificationTestKind.BANNER)).performClick()
        compose.waitUntil(5_000) { !recreated.hasActiveRun() }
        compose.runOnUiThread { shell!!.newIntent(route.applyTo(NotificationTestActivity.createIntent(shell!!.get()))) }
        idle()
        assertNotSame(recreated, shellPage())
        assertNotEquals(vm.owner.ownerId, model(shellPage()).owner.ownerId)
        assertFalse(shellPage().hasActiveRun())
        coVerify(exactly = 2) { poster.cancel(route.runId, NotificationTestKind.BANNER) }
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `real shell stale cleanup cannot replace a newer active run started while cleanup was suspended`() {
        shell = Robolectric.buildActivity(NotificationTestActivity::class.java).setup().visible()
        idle()
        val page = shellPage()
        val stale = NotificationTestReturnRoute(UUID.randomUUID().toString(), UUID.randomUUID().toString(), NotificationTestKind.BANNER)
        val cleanup = CompletableDeferred<Unit>()
        coEvery { poster.cancel(stale.runId, stale.kind) } coAnswers { cleanup.await() }
        compose.runOnUiThread { shell!!.newIntent(stale.applyTo(NotificationTestActivity.createIntent(shell!!.get()))) }
        idle()
        val vm = startBanner(page)
        val current = vm.state.value.runId
        compose.runOnUiThread { cleanup.complete(Unit) }
        idle()
        assertSame(page, shellPage())
        assertEquals(current, model(shellPage()).state.value.runId)
        assertTrue(shellPage().hasActiveRun())
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `real shell stale cleanup cannot replay over a replacement page`() {
        shell = Robolectric.buildActivity(NotificationTestActivity::class.java).setup().visible()
        idle()
        val stale = NotificationTestReturnRoute(UUID.randomUUID().toString(), UUID.randomUUID().toString(), NotificationTestKind.BANNER)
        val cleanup = CompletableDeferred<Unit>()
        coEvery { poster.cancel(stale.runId, stale.kind) } coAnswers { cleanup.await() }
        val replacement = NotificationTestFragment.newInstance(shell = true)
        compose.runOnUiThread {
            shell!!.newIntent(stale.applyTo(NotificationTestActivity.createIntent(shell!!.get())))
            shell!!.get().supportFragmentManager.beginTransaction()
                .replace(BaseR.id.detail_shell_container, replacement).commitNow()
        }
        idle()
        compose.runOnUiThread { cleanup.complete(Unit) }
        idle()
        assertSame(replacement, shellPage())
        assertFalse(replacement.hasActiveRun())
    }

    @Test
    fun `real shell cold stale return opens idle and malformed newIntent cannot create a run`() {
        val stale = NotificationTestReturnRoute(UUID.randomUUID().toString(), UUID.randomUUID().toString(), NotificationTestKind.BANNER)
        shell = Robolectric.buildActivity(NotificationTestActivity::class.java, stale.applyTo(NotificationTestActivity.createIntent(context))).setup().visible()
        idle()
        val page = shellPage()
        assertFalse(page.hasActiveRun())
        assertFalse(page.matchesReturn(stale))
        coVerify(exactly = 1) { poster.cancel(stale.runId, stale.kind) }
        compose.runOnUiThread { shell!!.newIntent(Intent(context, NotificationTestActivity::class.java).addCategory(NotificationTestReturnRoute.CATEGORY)) }
        idle()
        assertSame(page, shellPage())
        dispatchers.testDispatcher.scheduler.advanceTimeBy(8_000)
        idle()
        coVerify(exactly = 0) { poster.post(any()) }
        coVerify(exactly = 0) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `real background fragment resumes from battery settings and rereads changed system state`() {
        val power = context.getSystemService(PowerManager::class.java)!!
        val connectivity = context.getSystemService(ConnectivityManager::class.java)!!
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        plain = Robolectric.buildActivity(NotificationPlainWiringTestActivity::class.java).setup().visible()
        // ActivityManager shadows are per instance; mutate the service this actual Activity owns.
        val activityManager = plain!!.get().getSystemService(ActivityManager::class.java)!!
        shadowOf(activityManager).setBackgroundRestricted(true)
        val page = BackgroundConnectionSettingsFragment()
        compose.runOnUiThread {
            plain!!.get().supportFragmentManager.beginTransaction().replace(BaseR.id.detail_shell_container, page).commitNow()
        }
        idle()
        compose.onNode(hasText(context.getString(ChatR.string.battery_optimization_check)) and hasText(context.getString(ChatR.string.battery_optimization_not_ignored))).assertExists()
        compose.onNode(hasText(context.getString(ChatR.string.background_restriction_check)) and hasText(context.getString(ChatR.string.background_restriction_restricted))).assertExists()
        compose.onNodeWithText(context.getString(ChatR.string.battery_optimization_check)).performClick()
        val opened = shadowOf(plain!!.get()).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, opened.action)
        assertEquals("package:${context.packageName}", opened.data.toString())
        compose.runOnUiThread {
            plain!!.pause().stop()
            shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
            shadowOf(activityManager).setBackgroundRestricted(false)
            shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
            plain!!.start().resume().visible()
        }
        idle()
        assertSame(page, plain!!.get().supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container))
        compose.onNode(hasText(context.getString(ChatR.string.battery_optimization_check)) and hasText(context.getString(ChatR.string.battery_optimization_ignored))).assertExists()
        compose.onNode(hasText(context.getString(ChatR.string.battery_optimization_check)) and hasText(context.getString(ChatR.string.battery_optimization_not_ignored))).assertDoesNotExist()
        compose.onNode(hasText(context.getString(ChatR.string.background_restriction_check)) and hasText(context.getString(ChatR.string.background_restriction_unrestricted))).assertExists()
        compose.onNode(hasText(context.getString(ChatR.string.data_saver_check)) and hasText(context.getString(ChatR.string.data_saver_unrestricted))).assertExists()
    }

    @Test
    fun `real background fragment redirects an unmet requirement and keeps both stored and widget truth off`() {
        every { messageServiceManager.checkBackgroundConnectionRequirements() } returns false
        val stored = users.getUserData()?.autoStartMessageService
        plain = Robolectric.buildActivity(NotificationPlainWiringTestActivity::class.java).setup().visible()
        compose.runOnUiThread {
            plain!!.get().supportFragmentManager.beginTransaction()
                .replace(BaseR.id.detail_shell_container, BackgroundConnectionSettingsFragment()).commitNow()
        }
        idle()
        compose.onNodeWithTag("background_connection_switch").assertIsOff()
        compose.onNodeWithTag("background_connection_switch").performClick().assertIsOff()
        assertEquals(stored, users.getUserData()?.autoStartMessageService)
        verify(exactly = 0) { messageServiceManager.startService() }
        // The switch sends the user to the first failing requirement (battery optimization under Robolectric) instead of refusing.
        verify(exactly = 1) {
            ToastUtil.showLong(context.getString(ChatR.string.background_connection_fix_first, context.getString(ChatR.string.battery_optimization_check)))
        }
    }
}
