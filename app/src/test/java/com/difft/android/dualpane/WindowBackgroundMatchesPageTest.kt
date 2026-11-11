package com.difft.android.dualpane

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.BaseActivity
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
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
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR


class WindowTestSettingsPage : Fragment(), SettingsIdiomPage

@AndroidEntryPoint
class WindowTestSettingsShellActivity : ShellOriginProtocolFixtureActivity() {
    override fun createPage(): Fragment = WindowTestSettingsPage()
}


@AndroidEntryPoint
class WindowTestRootColourActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(rootTheme)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, BaseR.color.bg1)) })
    }

    override fun shouldApplyOrientationPolicy(): Boolean = false

    companion object {
        var rootTheme = androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar
    }
}






@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34])
class WindowBackgroundMatchesPageTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()

    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    private val controllers = mutableListOf<ActivityController<out Activity>>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        hilt.inject()
    }

    @After fun cleanup() {
        WindowTestRootColourActivity.rootTheme = androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        unmockkObject(AppStartup)
    }

    private fun <T : Activity> open(type: Class<T>): T =
        Robolectric.buildActivity(type, Intent(context, type)).setup().also {
            controllers += it
            shadowOf(Looper.getMainLooper()).idle()
        }.get()

    private fun Activity.windowColour(): Int? = (window.decorView.background as? ColorDrawable)?.color

    private fun colour(res: Int) = ContextCompat.getColor(context, res)

    @Test
    fun `a shell page takes the detail background`() {
        assertEquals(colour(BaseR.color.bg1), open(ShellOriginChildTestActivity::class.java).windowColour())
    }

    @Test
    fun `a shell settings page takes the settings background`() {
        assertEquals(colour(BaseR.color.bg), open(WindowTestSettingsShellActivity::class.java).windowColour())
    }

    @Test
    fun `a padded page gives the window its root colour`() {
        assertEquals(colour(BaseR.color.bg1), open(WindowTestRootColourActivity::class.java).windowColour())
    }

    @Test
    fun `a translucent host keeps its see-through window`() {
        WindowTestRootColourActivity.rootTheme = ChatR.style.Theme_TT_TransparentActivity
        val activity = open(WindowTestRootColourActivity::class.java)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.windowColour())
    }
}
