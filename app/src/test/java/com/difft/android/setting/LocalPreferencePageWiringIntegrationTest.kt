package com.difft.android.setting

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.call.data.VoicePreset
import com.difft.android.dualpane.DetailTabHostFragment
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.setting.mvi.LocalPreferenceContract
import com.difft.android.setting.mvi.LocalPreferenceViewModel
import com.difft.android.setting.notificationtest.NotificationPaneWiringTestActivity
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.FoldableTestApplication_Application
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import javax.inject.Inject
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR


@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h1000dp")
class LocalPreferencePageWiringIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @Inject lateinit var users: UserManager
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var pane: ActivityController<NotificationPaneWiringTestActivity>? = null
    private var shell: ActivityController<out FragmentActivity>? = null

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        hilt.inject()
        users.update {
            voicePlaybackSpeed = 1f
            callVoiceChangerPreset = VoicePreset.ORIGINAL.sdkKey
            notificationContentDisplayType = NotificationContentDisplayType.NAME_AND_CONTENT.value
        }
    }

    @After
    fun tearDown() {
        pane?.pause()?.stop()?.destroy()
        shell?.pause()?.stop()?.destroy()
        unmockkObject(AppStartup)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun pages(): List<Fragment> =
        (pane!!.get().supportFragmentManager.findFragmentByTag(PaneNavigator.tagFor(PaneNavigator.TAB_ME)) as DetailTabHostFragment).pages()

    private fun mountLeaf(page: Fragment): Fragment {
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        val root = Fragment()
        compose.runOnUiThread {
            val nav = pane!!.get().navigator
            nav.selectTab(PaneNavigator.TAB_ME)
            nav.replaceRoot(PaneNavigator.TAB_ME, root, null)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnUiThread { assertTrue(pane!!.get().navigator.push(PaneNavigator.TAB_ME, page)) }
        idle()
        assertEquals(2, pages().size)
        return root
    }

    private fun model(page: Fragment): LocalPreferenceViewModel = ViewModelProvider(page)[LocalPreferenceViewModel::class.java]

    
    private fun assertSelected(label: String) {
        compose.onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true).assertCountEquals(1)
        val check = compose.onNodeWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
        val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
        assertTrue(abs(check - text) <= 2f * context.resources.displayMetrics.density)
    }

    private fun <T : FragmentActivity> launchShell(type: Class<T>): T {
        val controller = Robolectric.buildActivity(type).setup().visible()
        shell = controller
        idle()
        return controller.get()
    }

    @Test
    fun `hosted speed control stores selection retains its VM through recreation and Back pops only the leaf`() {
        val page = VoicePlaybackSpeedSettingsFragment()
        mountLeaf(page)
        val vm = model(page)
        val label = context.getString(ChatR.string.voice_speed_1_5x)
        compose.onNodeWithText(label).performClick()
        assertEquals(1.5f, users.getUserData()?.voicePlaybackSpeed)
        assertEquals(1.5f, vm.state.value.speed)
        assertSelected(label)
        compose.runOnUiThread { pane!!.recreate() }
        idle()
        assertSame(vm, model(pages().last()))
        assertSelected(label)
        compose.onNodeWithContentDescription("Back").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pages().size)
        assertFalse(pane!!.get().isFinishing)
    }

    @Test
    fun `hosted voice control stores the SDK key retains selection and Back keeps the parent`() {
        val page = VoiceChangerSettingsFragment()
        val root = mountLeaf(page)
        val vm = model(page)
        val preset = VoicePreset.UNCLE
        compose.onNodeWithText(preset.displayText()).performClick()
        assertEquals(preset.sdkKey, users.getUserData()?.callVoiceChangerPreset)
        assertEquals(preset, vm.state.value.voice)
        assertSelected(preset.displayText())
        compose.onNodeWithContentDescription("Back").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(root), pages())
        assertFalse(pane!!.get().isFinishing)
    }

    @Test
    fun `hosted display selection stores truth before its close effect pops the leaf`() {
        val page = NotificationContentDisplaySettingsFragment()
        val root = mountLeaf(page)
        val vm = model(page)
        compose.onNodeWithText(context.getString(ChatR.string.notification_only_name)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(NotificationContentDisplayType.NAME_ONLY.value, users.getUserData()?.notificationContentDisplayType)
        assertEquals(NotificationContentDisplayType.NAME_ONLY.value, vm.state.value.displayType)
        assertEquals(listOf(root), pages())
        assertFalse(pane!!.get().isFinishing)
    }

    @Test
    fun `hosted display leaf still pops when the host is recreated before its queued pop drains`() {
        val page = NotificationContentDisplaySettingsFragment()
        mountLeaf(page)
        compose.runOnUiThread {
            model(page).dispatch(LocalPreferenceContract.Intent.SelectDisplay(NotificationContentDisplayType.NAME_ONLY.value))
            
            pane!!.recreate()
        }
        idle()
        assertEquals(NotificationContentDisplayType.NAME_ONLY.value, users.getUserData()?.notificationContentDisplayType)
        assertEquals(1, pages().size)
        assertTrue(pages().single() !is NotificationContentDisplaySettingsFragment)
        assertFalse(pane!!.get().isFinishing)
    }

    @Test
    fun `speed shell uses the actual control and title Back finishes its shell`() {
        val activity = launchShell(VoicePlaybackSpeedSettingsActivity::class.java)
        val page = activity.supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container)!!
        compose.onNodeWithText(context.getString(ChatR.string.voice_speed_2x)).performClick()
        assertEquals(2f, users.getUserData()?.voicePlaybackSpeed)
        assertEquals(2f, model(page).state.value.speed)
        assertFalse(activity.isFinishing)
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `voice shell stores the preset and title Back finishes its shell`() {
        val activity = launchShell(VoiceChangerSettingsActivity::class.java)
        val page = activity.supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container)!!
        compose.onNodeWithText(VoicePreset.GODDESS.displayText()).performClick()
        assertEquals(VoicePreset.GODDESS.sdkKey, users.getUserData()?.callVoiceChangerPreset)
        assertEquals(VoicePreset.GODDESS, model(page).state.value.voice)
        assertFalse(activity.isFinishing)
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `display shell closes even when the actual already-selected option is tapped`() {
        val activity = launchShell(NotificationContentDisplaySettingsActivity::class.java)
        compose.onNodeWithText(context.getString(ChatR.string.notification_display_name_and_content)).performClick()
        idle()
        assertEquals(NotificationContentDisplayType.NAME_AND_CONTENT.value, users.getUserData()?.notificationContentDisplayType)
        assertTrue(activity.isFinishing)
    }
}
