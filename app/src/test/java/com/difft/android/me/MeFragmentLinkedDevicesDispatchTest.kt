package com.difft.android.me

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.linkeddevices.LinkedDevicesActivity
import com.difft.android.linkeddevices.LinkedDevicesFragment
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host double capturing the two dispatch arms. */
class FakeDualPaneHostActivity : FragmentActivity(), DualPaneHost {
    var dualPane = false
    var showDetailCalls = 0
    var shownFragment: Fragment? = null
    override val isDualPaneMode: Boolean get() = dualPane
    override fun showDetailFragment(fragment: Fragment, tag: String?) {
        showDetailCalls++
        shownFragment = fragment
    }
}

/**
 * MeFragment settings-row dual-pane dispatch. [dispatchLinkedDevicesRow] mirrors the shared
 * [MeFragment.navigateToDetailOrActivity] (the @AndroidEntryPoint MeFragment can't be launched
 * without a Hilt harness); the dispatched targets are the real production units.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeFragmentLinkedDevicesDispatchTest {

    private lateinit var controller: ActivityController<FakeDualPaneHostActivity>
    private lateinit var host: FakeDualPaneHostActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FakeDualPaneHostActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        host = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    /** Mirrors MeFragment.navigateToDetailOrActivity (pre-existing shared row dispatch). */
    private fun dispatchLinkedDevicesRow() {
        val paneHost = host as DualPaneHost
        if (paneHost.isDualPaneMode) {
            paneHost.showDetailFragment(LinkedDevicesFragment.newInstance())
        } else {
            LinkedDevicesActivity.startActivity(host)
        }
    }

    @Test
    fun `I11 dual-pane shows LinkedDevicesFragment inline and starts no activity`() {
        host.dualPane = true
        dispatchLinkedDevicesRow()
        assertEquals(1, host.showDetailCalls)
        assertTrue(host.shownFragment is LinkedDevicesFragment)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test
    fun `I12 phone starts LinkedDevicesActivity and shows no inline fragment`() {
        host.dualPane = false
        dispatchLinkedDevicesRow()
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(LinkedDevicesActivity::class.java.name, intent.component?.className)
        assertEquals(0, host.showDetailCalls)
        assertNull(host.shownFragment)
    }
}
