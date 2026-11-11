package com.difft.android.chat.group

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.chat.databinding.ChatFragmentGroupChatBinding
import com.difft.android.chat.group.mvi.GroupDissolveBannerViewModel
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "en")
class GroupDissolveBannerHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(FragmentActivity::class.java)
    private val owner = Owner()
    private val data = MutableStateFlow(ChatUIData(null, null))
    private lateinit var binding: ChatFragmentGroupChatBinding
    private lateinit var model: GroupDissolveBannerViewModel

    @Before fun setup() {
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        controller.setup()
        val activity = controller.get()
        val inflater = LayoutInflater.from(activity.applicationContext).cloneInContext(activity)
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                if (name == FragmentContainerView::class.java.name) FragmentContainerView(context).apply {
                    id = attrs.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", View.NO_ID)
                } else null
            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
        binding = ChatFragmentGroupChatBinding.inflate(inflater)
        binding.root.setUseWindowTypes(false)
        binding.fragmentContainerViewHeader.layoutParams.height = 100
        binding.fragmentContainerViewInput.layoutParams.height = 60
        binding.topInsetSpacer.layoutParams.height = 24
        binding.root.setViewTreeLifecycleOwner(owner)
        activity.setContentView(binding.root)
        val ticker = mockk<CountdownTicker>()
        every { ticker.ticks } returns emptyFlow()
        model = GroupDissolveBannerViewModel(
            SavedStateHandle(mapOf(GroupChatFragment.ARG_GROUP_ID to GID)), GroupDissolveDismissals(), ticker,
        )
    }

    @After fun teardown() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        model.viewModelScope.cancel()
        controller.pause().stop().destroy()
    }

    @Test fun `installer anchors to the header and floats over unchanged message bounds behind input overlays`() {
        layout()
        val before = bounds(binding.fragmentContainerViewContents)
        val inputBefore = bounds(binding.fragmentContainerViewInput)
        installGroupDissolveBanner(owner, binding, model, data)
        data.value = ChatUIData(null, group())
        settle()
        val overlay = (0 until binding.root.childCount).map(binding.root::getChildAt).filterIsInstance<ComposeView>().single()
        val params = overlay.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(binding.fragmentContainerViewHeader.id, params.topToBottom)
        assertEquals(binding.fragmentContainerViewHeader.bottom, overlay.top)
        assertTrue(overlay.measuredHeight > 0)
        assertEquals(before, bounds(binding.fragmentContainerViewContents))
        assertEquals(inputBefore, bounds(binding.fragmentContainerViewInput))
        assertTrue(binding.root.indexOfChild(overlay) > binding.root.indexOfChild(binding.fragmentContainerViewContents))
        assertTrue(binding.root.indexOfChild(overlay) < binding.root.indexOfChild(binding.fragmentContainerViewInput))
        assertTrue(binding.root.indexOfChild(overlay) < binding.root.indexOfChild(binding.clVoiceRecord))
        assertTrue(binding.root.indexOfChild(overlay) < binding.root.indexOfChild(binding.includeFullInput.root))
        data.value = ChatUIData(null, group().apply { expireTime = null })
        settle()
        assertNull(model.state.value.daysLeft)
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertDoesNotExist()
        assertEquals(before, bounds(binding.fragmentContainerViewContents))
    }

    @Test fun `host stops collecting while stopped and replays the latest group on restart`() {
        installGroupDissolveBanner(owner, binding, model, data)
        data.value = ChatUIData(null, group())
        settle()
        assertEquals(1, model.state.value.daysLeft)
        owner.registry.currentState = Lifecycle.State.CREATED
        shadowOf(Looper.getMainLooper()).idle()
        data.value = ChatUIData(null, group().apply { expireTime = null })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, model.state.value.daysLeft)
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle()
        assertNull(model.state.value.daysLeft)
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertDoesNotExist()
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        layout()
        compose.waitForIdle()
    }

    private fun layout() {
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, 400, 800)
    }

    private fun bounds(view: View) = listOf(view.left, view.top, view.right, view.bottom)
    private fun group() = GroupModel().apply {
        gid = GID; status = 0; expireTime = ServerTimeProvider.nowMillis() + 86_400_000L
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private companion object { const val GID = "host-group" }
}
