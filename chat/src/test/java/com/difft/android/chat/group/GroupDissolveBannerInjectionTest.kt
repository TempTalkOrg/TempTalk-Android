package com.difft.android.chat.group

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.activity.viewModels
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.chat.group.mvi.GroupDissolveBannerContract
import com.difft.android.chat.group.mvi.GroupDissolveBannerViewModel
import com.difft.android.test.GroupTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.difft.app.database.models.GroupModel
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
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@AndroidEntryPoint
class GroupDissolveBannerTestActivity : FragmentActivity() {
    val model: GroupDissolveBannerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        super.onCreate(savedInstanceState)
    }
}


@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = GroupTestApplication_Application::class, sdk = [33])
class GroupDissolveBannerInjectionTest {
    @BindValue @JvmField val logoutBoundary: com.difft.android.base.user.LogoutManager = mockk(relaxed = true)
    @BindValue @JvmField val environmentBoundary: com.difft.android.base.utils.EnvironmentHelper = mockk(relaxed = true)
    @BindValue @JvmField val activityBoundary: com.difft.android.base.activity.ActivityProvider = mockk(relaxed = true)
    @BindValue @JvmField val callToChatBoundary: com.difft.android.call.LCallToChatController = mockk(relaxed = true)
    @BindValue @JvmField val chatToCallBoundary: com.difft.android.chat.call.LChatToCallController = mockk(relaxed = true)
    @BindValue @JvmField val translationBoundary: com.difft.android.chat.translate.backend.TranslateBackend = mockk(relaxed = true)
    @BindValue @JvmField val ticker: CountdownTicker = mockk()
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatcher = TestDispatcherRule()
    @Inject lateinit var dismissals: GroupDissolveDismissals
    private val activities = mutableListOf<ActivityController<GroupDissolveBannerTestActivity>>()

    @Before fun setup() {
        every { ticker.ticks } returns emptyFlow()
        hilt.inject()
    }

    @After fun teardown() {
        activities.forEach { it.get().model.viewModelScope.cancel(); it.pause().stop().destroy() }
    }

    @Test fun `saved group id comes from the real default Hilt factory`() {
        val vm = activity("group-a").model
        assertEquals("group-a", vm.state.value.groupId)
        vm.dispatch(GroupDissolveBannerContract.Intent.UpdateGroup(group("group-a")))
        assertEquals(1, vm.state.value.daysLeft)
    }

    @Test fun `two Hilt owners share dismissal while another group stays visible`() {
        val first = activity("group-a").model
        val second = activity("group-a").model
        val other = activity("group-b").model
        first.dispatch(GroupDissolveBannerContract.Intent.UpdateGroup(group("group-a")))
        second.dispatch(GroupDissolveBannerContract.Intent.UpdateGroup(group("group-a")))
        other.dispatch(GroupDissolveBannerContract.Intent.UpdateGroup(group("group-b")))
        first.dispatch(GroupDissolveBannerContract.Intent.Dismiss)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("group-a" in dismissals.groupIds.value)
        assertNull(second.state.value.daysLeft)
        assertEquals(1, other.state.value.daysLeft)
    }

    private fun activity(gid: String): GroupDissolveBannerTestActivity {
        val intent = Intent().putExtra(GroupChatFragment.ARG_GROUP_ID, gid)
        return Robolectric.buildActivity(GroupDissolveBannerTestActivity::class.java, intent)
            .setup().also(activities::add).get()
    }

    private fun group(gid: String) = GroupModel().apply {
        this.gid = gid
        status = 0
        expireTime = com.difft.android.base.utils.time.ServerTimeProvider.nowMillis() + 86_400_000L
    }
}
