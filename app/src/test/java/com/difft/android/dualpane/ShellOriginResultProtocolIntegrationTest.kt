package com.difft.android.dualpane

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellPop
import com.difft.android.base.utils.pane.ShellNavigation
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.difft.android.base.R as BaseR


@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34])
class ShellOriginResultProtocolIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    private val controllers = mutableListOf<ActivityController<out Activity>>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        hilt.inject()
    }

    @After fun cleanup() {
        ShellOriginParentTestActivity.beforeShellCreate = null
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        unmockkObject(AppStartup)
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    private fun parent(intent: Intent = Intent(context, ShellOriginParentTestActivity::class.java)) =
        Robolectric.buildActivity(ShellOriginParentTestActivity::class.java, intent).setup().also {
            controllers += it
            it.get().supportFragmentManager.executePendingTransactions()
            idle()
        }

    private fun child(intent: Intent) = Robolectric.buildActivity(ShellOriginChildTestActivity::class.java, intent)
        .setup().also {
            controllers += it
            it.get().supportFragmentManager.executePendingTransactions()
            idle()
        }

    private fun page(activity: FragmentActivity): Fragment =
        requireNotNull(activity.supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container))

    private fun push(activity: ShellOriginProtocolFixtureActivity, subject: String?, plain: Boolean = false): Boolean =
        ShellNavigation(page(activity)).push(page(activity), object : DetailDestination {
            override val subjectId: String? = subject
            override fun newFragment(): Fragment = Fragment()
            override fun shellIntent(context: Context): Intent = Intent(context,
                if (plain) ShellOriginPlainTestActivity::class.java else ShellOriginChildTestActivity::class.java)
        })

    @Test fun `real child pop two survives parent recreation and closes parent exactly once`() {
        val parent = parent()
        assertTrue(push(parent.get(), "group-a"))
        val launch = assertNotNull(shadowOf(parent.get()).nextStartedActivityForResult)
        assertNotNull(launch.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        assertEquals("group-a", launch.intent.getStringExtra(DetailShellPop.EXTRA_SUBJECT_ID))
        assertFalse(push(parent.get(), "group-a"))
        assertNull(shadowOf(parent.get()).nextStartedActivityForResult)
        parent.recreate()
        parent.get().supportFragmentManager.executePendingTransactions()
        idle()
        assertFalse(push(parent.get(), "another-group"), "pending launch must survive recreation")
        val child = child(launch.intent)
        assertTrue(ShellNavigation(page(child.get())).pop(page(child.get()), 2))
        val result = shadowOf(child.get())
        assertEquals(DetailShellPop.RESULT_POP_MORE, result.resultCode)
        assertEquals(launch.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID),
            result.resultIntent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        parent.get().activityResultRegistry.dispatchResult(launch.requestCode, result.resultCode, result.resultIntent)
        idle()
        assertTrue(parent.get().isFinishing)
        assertEquals(1, parent.get().finishCalls)
        parent.get().activityResultRegistry.dispatchResult(launch.requestCode, result.resultCode, result.resultIntent)
        idle()
        assertEquals(1, parent.get().finishCalls)
        
        assertFalse(DetailShellPop.consume(parent.get(), result.resultCode, result.resultIntent))
        assertEquals(1, parent.get().finishCalls)
        assertNotEquals(DetailShellPop.RESULT_POP_MORE, shadowOf(parent.get()).resultCode)
    }

    @Test fun `wrong request or subject delivered by real registry never closes the parent`() {
        for (wrongRequest in listOf(true, false)) {
            val parent = parent()
            assertTrue(push(parent.get(), "group-a"))
            val launch = assertNotNull(shadowOf(parent.get()).nextStartedActivityForResult)
            val child = child(launch.intent)
            ShellNavigation(page(child.get())).pop(page(child.get()), 2)
            val result = Intent(shadowOf(child.get()).resultIntent)
            result.putExtra(if (wrongRequest) DetailShellPop.EXTRA_REQUEST_ID else DetailShellPop.EXTRA_SUBJECT_ID,
                if (wrongRequest) "different-launch" else "different-group")
            parent.get().activityResultRegistry.dispatchResult(launch.requestCode, DetailShellPop.RESULT_POP_MORE, result)
            idle()
            assertFalse(parent.get().isFinishing)
            assertEquals(0, parent.get().finishCalls)
        }
    }

    @Test fun `late normal cancellation of A cannot release B and B pop still completes in foreground`() {
        val parent = parent()
        assertTrue(push(parent.get(), "first-group"))
        val first = assertNotNull(shadowOf(parent.get()).nextStartedActivityForResult)
        val firstChild = child(first.intent)
        ShellNavigation(page(firstChild.get())).pop(page(firstChild.get()), 1)
        val firstResult = shadowOf(firstChild.get())
        assertEquals(Activity.RESULT_CANCELED, firstResult.resultCode)
        assertEquals(first.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID),
            firstResult.resultIntent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        parent.get().activityResultRegistry.dispatchResult(first.requestCode, firstResult.resultCode, firstResult.resultIntent)
        idle()
        assertFalse(parent.get().isFinishing)
        assertTrue(push(parent.get(), "second-group"))
        val second = assertNotNull(shadowOf(parent.get()).nextStartedActivityForResult)
        assertNotEquals(first.requestCode, second.requestCode, "each request needs an independent registry key")
        assertNotEquals(first.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID),
            second.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        val secondChild = child(second.intent)
        parent.get().activityResultRegistry.dispatchResult(first.requestCode, firstResult.resultCode, firstResult.resultIntent)
        idle()
        assertFalse(DetailShellPop.consume(parent.get(), firstResult.resultCode, firstResult.resultIntent))
        assertFalse(push(parent.get(), "third-group"), "late A must not release pending B")
        ShellNavigation(page(secondChild.get())).pop(page(secondChild.get()), 2)
        parent.get().activityResultRegistry.dispatchResult(second.requestCode,
            shadowOf(secondChild.get()).resultCode, shadowOf(secondChild.get()).resultIntent)
        idle()
        assertEquals(1, parent.get().finishCalls)
        assertTrue(parent.get().isFinishing)
    }

    @Test fun `restored result delivered inside register consumes once and retained old mapping cannot affect B`() {
        val original = parent()
        assertTrue(push(original.get(), "first-group"))
        val first = assertNotNull(shadowOf(original.get()).nextStartedActivityForResult)
        val firstChild = child(first.intent)
        ShellNavigation(page(firstChild.get())).pop(page(firstChild.get()), 1)
        val firstResult = shadowOf(firstChild.get())
        val activityState = Bundle()
        val registryState = Bundle()
        original.pause().stop().saveInstanceState(activityState)
        original.get().activityResultRegistry.onSaveInstanceState(registryState)
        original.destroy()
        controllers.remove(original)
        
        
        ShellOriginParentTestActivity.beforeShellCreate = { activity ->
            activity.activityResultRegistry.onRestoreInstanceState(registryState)
            assertTrue(activity.activityResultRegistry.dispatchResult(first.requestCode,
                firstResult.resultCode, firstResult.resultIntent))
        }
        val restored = Robolectric.buildActivity(ShellOriginParentTestActivity::class.java,
            Intent(context, ShellOriginParentTestActivity::class.java)).create(activityState).start().resume().visible()
        controllers += restored
        restored.get().supportFragmentManager.executePendingTransactions()
        idle()
        assertFalse(restored.get().isFinishing)
        assertFalse(DetailShellPop.consume(restored.get(), firstResult.resultCode, firstResult.resultIntent))
        assertTrue(push(restored.get(), "second-group"), "synchronous register callback must clear pending A")
        val second = assertNotNull(shadowOf(restored.get()).nextStartedActivityForResult)
        assertNotEquals(first.requestCode, second.requestCode)
        
        
        assertTrue(restored.get().activityResultRegistry.dispatchResult(first.requestCode,
            firstResult.resultCode, firstResult.resultIntent))
        idle()
        assertFalse(push(restored.get(), "third-group"), "orphan A must not release pending B")
        val secondChild = child(second.intent)
        ShellNavigation(page(secondChild.get())).pop(page(secondChild.get()), 2)
        restored.get().activityResultRegistry.dispatchResult(second.requestCode,
            shadowOf(secondChild.get()).resultCode, shadowOf(secondChild.get()).resultIntent)
        idle()
        assertEquals(1, restored.get().finishCalls)
    }

    @Test fun `longer chain propagates parents own inherited identity instead of child identity`() {
        val grandparent = parent()
        assertTrue(push(grandparent.get(), "parent-subject"))
        val parentLaunch = assertNotNull(shadowOf(grandparent.get()).nextStartedActivityForResult)
        val parent = parent(parentLaunch.intent)
        assertTrue(push(parent.get(), "child-subject"))
        val childLaunch = assertNotNull(shadowOf(parent.get()).nextStartedActivityForResult)
        val child = child(childLaunch.intent)
        ShellNavigation(page(child.get())).pop(page(child.get()), 3)
        parent.get().activityResultRegistry.dispatchResult(childLaunch.requestCode,
            shadowOf(child.get()).resultCode, shadowOf(child.get()).resultIntent)
        idle()
        val propagated = shadowOf(parent.get())
        assertEquals(1, parent.get().finishCalls)
        assertEquals(1, propagated.resultIntent.getIntExtra(DetailShellPop.EXTRA_REMAINING, 0))
        assertEquals(parentLaunch.intent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID),
            propagated.resultIntent.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        assertEquals("parent-subject", propagated.resultIntent.getStringExtra(DetailShellPop.EXTRA_SUBJECT_ID))
        grandparent.get().activityResultRegistry.dispatchResult(parentLaunch.requestCode,
            propagated.resultCode, propagated.resultIntent)
        idle()
        assertEquals(1, grandparent.get().finishCalls)
    }

    @Test fun `reusable and task intents keep direct launch flags and create no pending protocol request`() {
        for (flags in listOf(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_MULTIPLE_TASK, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) {
            val parent = parent()
            val parentPage = page(parent.get())
            assertTrue(ShellNavigation(parentPage).push(parentPage, object : DetailDestination {
                override fun newFragment(): Fragment = Fragment()
                override fun shellIntent(context: Context) = Intent(context, ShellOriginChildTestActivity::class.java).addFlags(flags)
            }))
            val reused = assertNotNull(shadowOf(parent.get()).nextStartedActivity)
            assertEquals(flags, reused.flags)
            assertFalse(reused.hasExtra(DetailShellPop.EXTRA_REQUEST_ID))
            assertTrue(push(parent.get(), "fresh-child"), "direct reuse must not create a pending result request")
            val fresh = assertNotNull(shadowOf(parent.get()).nextStartedActivity)
            assertNotNull(fresh.getStringExtra(DetailShellPop.EXTRA_REQUEST_ID))
        }
    }

    @Test fun `ordinary activity stops the chain and does not lend its inherited request to a child`() {
        val parent = parent()
        assertTrue(push(parent.get(), "ordinary-subject", plain = true))
        val plainIntent = assertNotNull(shadowOf(parent.get()).nextStartedActivity)
        assertFalse(plainIntent.hasExtra(DetailShellPop.EXTRA_REQUEST_ID))
        val plain = Robolectric.buildActivity(ShellOriginPlainTestActivity::class.java, plainIntent).setup()
        controllers += plain
        plain.get().supportFragmentManager.executePendingTransactions()
        val plainPage = page(plain.get())
        assertTrue(ShellNavigation(plainPage).push(plainPage, object : DetailDestination {
            override val subjectId = "child-subject"
            override fun newFragment(): Fragment = Fragment()
            override fun shellIntent(context: Context) = Intent(context, ShellOriginChildTestActivity::class.java)
        }))
        val childIntent = assertNotNull(shadowOf(plain.get()).nextStartedActivity)
        assertFalse(childIntent.hasExtra(DetailShellPop.EXTRA_REQUEST_ID))
        val child = child(childIntent)
        ShellNavigation(page(child.get())).pop(page(child.get()), 2)
        assertNotEquals(DetailShellPop.RESULT_POP_MORE, shadowOf(child.get()).resultCode)
        assertFalse(plain.get().isFinishing)
        assertFalse(parent.get().isFinishing)
        assertTrue(ShellNavigation(plainPage).pop(plainPage, 2))
        assertNotEquals(DetailShellPop.RESULT_POP_MORE, shadowOf(plain.get()).resultCode)
        assertFalse(parent.get().activityResultRegistry.dispatchResult(-1,
            shadowOf(plain.get()).resultCode, shadowOf(plain.get()).resultIntent))
        idle()
        assertFalse(parent.get().isFinishing)
        assertEquals(0, parent.get().finishCalls)
        assertTrue(push(parent.get(), "next-protocol-child"), "ordinary boundaries create no pending protocol launch")
    }
}
