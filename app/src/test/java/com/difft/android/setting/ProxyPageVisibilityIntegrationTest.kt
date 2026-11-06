package com.difft.android.setting

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.dualpane.DetailTabHostFragment
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.network.proxy.ProxyConfig
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.proxy.ProxyLinkCodec
import com.difft.android.setting.notificationtest.NotificationPaneWiringTestActivity
import com.difft.android.setting.proxy.ProxyE2eProbeImpl
import com.difft.android.setting.viewmodel.ProxySettingsViewModel
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.FoldableTestApplication_Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

/** Actual encrypted-link Save must retain its prompt without showing a dialog over another page. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h1000dp")
class ProxyPageVisibilityIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()

    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val provider: ProxyConfigProvider = mockk(relaxed = true)
    // Keep the existing interface @Binds module; replace its injected implementation boundary.
    @BindValue @JvmField val probe: ProxyE2eProbeImpl = mockk(relaxed = true)
    // Real, initially idle state avoids the property/function isInCalling mock-name collision.
    @BindValue @JvmField val callState = OnGoingCallStateManager()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var pane: ActivityController<NotificationPaneWiringTestActivity>? = null

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        every { provider.savedShareLink } returns ""
        every { provider.isEnabledByUser } returns false
        every { provider.isEnabled } returns false
        every { provider.isProtectCallIpEnabled } returns false
        hilt.inject()
    }

    @After
    fun tearDown() {
        pane?.pause()?.stop()?.destroy()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun pages(): List<Fragment> =
        (pane!!.get().supportFragmentManager.findFragmentByTag(PaneNavigator.tagFor(PaneNavigator.TAB_ME)) as DetailTabHostFragment).pages()

    private var shownPrompt: android.app.Dialog? = null
    private fun assertPrompt() {
        shownPrompt = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(shownPrompt?.isShowing == true)
        compose.onAllNodesWithText(context.getString(R.string.proxy_passphrase_title)).assertCountEquals(1)
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }

    private fun assertNoPrompt() {
        assertFalse(shownPrompt?.isShowing == true)
        compose.onNodeWithText(context.getString(R.string.proxy_passphrase_title)).assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun `encrypted proxy prompt follows the actual visible owner across tab hide and covered page`() {
        val encryptedLink = ProxyLinkCodec.encodeEncrypted(
            ProxyConfig(
                host = "203.0.113.7",
                port = 8443,
                spkiPinBase64 = "AbC123+/=",
                sni = "cdn.example.com",
                turnSecret = "test-only-turn-secret",
            ),
            "test-only-passphrase",
        )
        assertEquals(ProxyLinkCodec.Mode.ENCRYPTED, ProxyLinkCodec.inspect(encryptedLink))
        pane = Robolectric.buildActivity(NotificationPaneWiringTestActivity::class.java).setup().visible()
        val page = ProxySettingsFragment()
        compose.runOnUiThread {
            val nav = pane!!.get().navigator
            nav.selectTab(PaneNavigator.TAB_ME)
            nav.replaceRoot(PaneNavigator.TAB_ME, page, null)
        }
        idle()
        val vm = ViewModelProvider(page)[ProxySettingsViewModel::class.java]
        assertTrue(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        // Drives the production field -> VM address -> Save -> real encrypted-link inspection.
        compose.onNode(hasSetTextAction()).performTextInput(encryptedLink)
        compose.onNodeWithText(context.getString(R.string.proxy_save)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.proxy_passphrase_title)).fetchSemanticsNodes().size == 1
        }
        assertPrompt()
        assertEquals(encryptedLink, vm.uiState.value.address)
        assertFalse(vm.uiState.value.isSaving)
        verify(exactly = 0) { provider.save(any(), any()) }

        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_CONTACTS) }
        idle()
        assertFalse(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        // Regression: an Activity-window dialog must disappear although this Fragment keeps its view.
        assertNoPrompt()
        assertSame(page, pages().single())
        compose.runOnUiThread { pane!!.get().navigator.selectTab(PaneNavigator.TAB_ME) }
        idle()
        assertSame(vm, ViewModelProvider(page)[ProxySettingsViewModel::class.java])
        assertPrompt()
        assertEquals(encryptedLink, vm.uiState.value.address)

        compose.runOnUiThread { assertTrue(pane!!.get().navigator.push(PaneNavigator.TAB_ME, Fragment())) }
        idle()
        assertFalse(page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        assertNoPrompt()
        compose.runOnUiThread { assertTrue(pane!!.get().navigator.pop(PaneNavigator.TAB_ME)) }
        idle()
        assertSame(page, pages().single())
        assertPrompt()
        verify(exactly = 0) { provider.save(any(), any()) }
    }
}
