package com.difft.android.setting

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.util.CriticalAlertBypass
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.MessageSoundLabel
import com.difft.android.chat.util.NotificationChannelManager
import com.difft.android.dualpane.DetailTabHostFragment
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.linkeddevices.LinkedDevicesFragment
import com.difft.android.linkeddevices.LinkedDevicesViewModel
import com.difft.android.network.UrlManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import com.difft.android.setting.language.LanguageFragment
import com.difft.android.setting.notificationtest.NotificationPaneWiringTestActivity
import com.difft.android.setting.theme.ThemeFragment
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
import com.difft.android.chat.R as ChatR

/** The six production Me roots under the real pane host, including mixed XML/Compose pages. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h1000dp")
class MeRootPageWiringIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val readiness: NotificationTestRepository = mockk()
    @BindValue @JvmField val messageNotifications: MessageNotificationUtil = mockk(relaxed = true)
    @BindValue @JvmField val channels: NotificationChannelManager = mockk(relaxed = true)
    @BindValue @JvmField val push: PushUtil = mockk()
    @BindValue @JvmField val updates: UpdateManager = mockk(relaxed = true)
    @BindValue @JvmField val urls: UrlManager = mockk(relaxed = true)
    @BindValue @JvmField val chatSelector: SelectChatsUtils = mockk(relaxed = true)
    @BindValue @JvmField val devices: DeviceRepository = mockk()
    @Inject lateinit var users: UserManager
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var pane: ActivityController<NotificationPaneWiringTestActivity>? = null
    private val laptop = DeviceInfo(id = 2, name = "Test laptop", created = 1_700_000_000_000L, lastSeen = 1_700_000_000_100L)

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        coEvery { readiness.readReadiness() } returns NotificationReadiness(items = emptyList())
        coEvery { channels.reconcileCriticalAlertBypass(any()) } returns CriticalAlertBypass.NOT_APPLICABLE
        every { push.fcmInitResult } returns MutableStateFlow(FcmInitResult.Idle)
        coEvery { devices.getDevices() } returns listOf(laptop)
        mockkObject(PackageUtil, MessageSoundLabel, ToastUtil)
        every { PackageUtil.getAppVersionName() } returns "test-version"
        every { PackageUtil.getAppVersionCode() } returns 1
        every { MessageSoundLabel.read(any()) } returns "Default"
        every { ToastUtil.show(any<Int>()) } just Runs
        hilt.inject()
        every { globals.mockGlobalServices.environmentHelper.getChannelName() } returns "official"
        users.update { voicePlaybackSpeed = 1f; callVoiceChangerPreset = "original"; saveToPhotos = false }
    }

    @After
    fun tearDown() {
        pane?.pause()?.stop()?.destroy()
        unmockkObject(PackageUtil, MessageSoundLabel, ToastUtil)
    }

    private fun idle(expectCompose: Boolean = true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (expectCompose) compose.waitForIdle()
    }

    private fun pages(): List<Fragment> =
        (pane!!.get().supportFragmentManager.findFragmentByTag(PaneNavigator.tagFor(PaneNavigator.TAB_ME)) as DetailTabHostFragment).pages()

    private fun launch() {
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        idle(expectCompose = false)
    }

    private fun mount(page: Fragment) {
        compose.runOnUiThread {
            val nav = pane!!.get().navigator
            nav.selectTab(PaneNavigator.TAB_ME)
            nav.replaceRoot(PaneNavigator.TAB_ME, page, null)
        }
        idle()
        assertTrue(page is SettingsIdiomPage)
        assertTrue(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    private fun title(page: Fragment, text: String) {
        if (page is ChatSettingsFragment || page is NotificationSettingsFragment) {
            assertEquals(text, page.requireView().findViewById<TextView>(R.id.tv_title).text.toString())
        } else compose.onNodeWithText(text).assertExists()
    }

    private fun rootCases(): List<Pair<() -> Fragment, Int>> = listOf(
        ({ ChatSettingsFragment() }) to ChatR.string.me_chat_and_call,
        ({ NotificationSettingsFragment() }) to ChatR.string.notification_title,
        ({ AboutFragment() }) to R.string.settings_about,
        ({ LanguageFragment() }) to ChatR.string.me_language,
        ({ ThemeFragment() }) to ChatR.string.me_theme,
        ({ LinkedDevicesFragment() }) to R.string.linked_devices_title,
    )

    /** Actual XML listeners must pad only the missing bottom, preserving top padding. */
    private fun assertXmlInsets(page: Fragment, expectedBottom: Int) {
        if (page !is ChatSettingsFragment && page !is NotificationSettingsFragment) return
        val root = page.requireView()
        val originalTop = root.paddingTop
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 31, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 27)).build())
        }
        assertEquals(originalTop, root.paddingTop)
        assertEquals(expectedBottom, root.paddingBottom)
    }

    @Test
    fun `all six wide Me roots inflate hide retain recreate and hide root Back`() {
        launch()
        assertTrue(pane!!.get().navigator.isDualPane)
        for ((factory, titleRes) in rootCases()) {
            val page = factory()
            mount(page)
            title(page, context.getString(titleRes))
            // The two-pane detail column reaches the window bottom, so the page pads the bar itself.
            assertXmlInsets(page, 27)
            val xmlBack = page.requireView().findViewById<View>(R.id.ib_back)
            if (xmlBack != null) assertEquals(View.GONE, xmlBack.visibility)
            else compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
            idle()
            assertFalse(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            assertSame(page, pages().single())
            compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
            idle()
            assertSame(page, pages().single())
            compose.runOnUiThread { pane!!.recreate() }
            idle()
            val restored = pages().single()
            assertEquals(page.javaClass, restored.javaClass)
            assertNotSame(page, restored)
            title(restored, context.getString(titleRes))
            assertFalse(pane!!.get().isFinishing)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun `all six narrow Me roots expose title Back and pop only the page with actual bottom inset wiring`() {
        launch()
        assertFalse(pane!!.get().navigator.isDualPane)
        for ((factory, titleRes) in rootCases()) {
            val page = factory()
            mount(page)
            title(page, context.getString(titleRes))
            assertXmlInsets(page, 27)
            val xmlBack = page.requireView().findViewById<View>(R.id.ib_back)
            if (xmlBack != null) {
                assertEquals(View.VISIBLE, xmlBack.visibility)
                compose.runOnUiThread { xmlBack.performClick() }
            } else compose.onNodeWithContentDescription("Back").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertEquals(0, pane!!.get().navigator.depth(PaneNavigator.TAB_ME))
            assertFalse(pane!!.get().isFinishing)
        }
    }

    @Test
    fun `linked devices menu and Fragment-owned confirmation dismiss when its actual detail owner hides`() {
        launch()
        val page = LinkedDevicesFragment()
        mount(page)
        compose.waitUntil(5_000) { ViewModelProvider(page)[LinkedDevicesViewModel::class.java].uiState.value.devices.isNotEmpty() }
        compose.onNodeWithText("Test laptop").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.linked_devices_unlink_action)).assertExists()
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        compose.onNodeWithText(context.getString(R.string.linked_devices_unlink_action)).assertDoesNotExist()
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        compose.onNodeWithText("Test laptop").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.linked_devices_unlink_action)).performClick()
        val confirmation = context.getString(R.string.linked_devices_unlink_confirm_title, "Test laptop")
        compose.onNodeWithText(confirmation).assertExists()
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        compose.onNodeWithText(confirmation).assertDoesNotExist()
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        compose.onNodeWithText(confirmation).assertDoesNotExist()
        coVerify(exactly = 0) { devices.removeDevice(any()) }
    }

    @Test
    fun `linked devices delayed fetch failure waits for the actual visible owner and toasts once`() {
        launch()
        val page = LinkedDevicesFragment()
        mount(page)
        val vm = ViewModelProvider(page)[LinkedDevicesViewModel::class.java]
        compose.waitUntil(5_000) { vm.uiState.value.hasLoadedOnce }
        val failure = CompletableDeferred<Unit>()
        var first = true
        coEvery { devices.getDevices() } coAnswers {
            if (first) {
                first = false
                failure.await()
                throw IOException("controlled fetch failure")
            }
            listOf(laptop)
        }
        compose.runOnUiThread {
            vm.refresh(force = true)
            pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS)
        }
        idle()
        compose.runOnUiThread { failure.complete(Unit) }
        idle()
        verify(exactly = 0) { ToastUtil.show(R.string.linked_devices_list_update_failed) }
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        verify(exactly = 1) { ToastUtil.show(R.string.linked_devices_list_update_failed) }
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        verify(exactly = 1) { ToastUtil.show(R.string.linked_devices_list_update_failed) }
        compose.onNodeWithText("Test laptop").assertExists()
    }
}
