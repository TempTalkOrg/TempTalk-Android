package com.difft.android.chat.group

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.chat.databinding.ChatActivityGroupInfoBinding
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract
import com.difft.android.chat.group.mvi.GroupInfoHeaderViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class TestBackOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
}


class OwnerControlledStackHost : Fragment(), DetailStackHost {
    val owner = TestBackOwner()
    override val detailNavigation: DetailNavigation = object : DetailNavigation {
        override fun push(from: Fragment, destination: DetailDestination): Boolean = false
        override fun pop(from: Fragment): Boolean = true
        override fun openConversation(target: ConversationTarget) = Unit
        override fun closeConversation(from: Fragment) = Unit
        override fun canPop(from: Fragment): Boolean = true
    }

    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = owner
}


internal class BinderPage : Fragment() {
    val viewModel: GroupInfoHeaderViewModel = mockk(relaxed = true) {
        every { state } returns MutableStateFlow(GroupInfoHeaderContract.State(isEditing = true))
        every { effect } returns emptyFlow()
    }
    var leaves = 0
    private var binding: ChatActivityGroupInfoBinding? = null
    val binder = GroupInfoHeaderBinder(this, { binding!! }, { viewModel }, { "g1" }) { leaves++ }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ChatActivityGroupInfoBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binder.observe()
    }
}






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupInfoHeaderBinderBackTest {

    private val exitByBack = GroupInfoHeaderContract.Intent.RequestExit(GroupInfoHeaderContract.ExitSource.Back)

    @Test
    fun `edit-mode Back reaches the binder only while its page is the visible top`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light)
        }.setup().get()
        val host = OwnerControlledStackHost()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, host).commitNow()
        val page = BinderPage()
        
        host.childFragmentManager.beginTransaction().add(page, "page").commitNow()
        shadowOf(Looper.getMainLooper()).idle()

        
        activity.onBackPressedDispatcher.onBackPressed()
        verify(exactly = 0) { page.viewModel.dispatch(exitByBack) }

        
        host.owner.registry.currentState = Lifecycle.State.STARTED
        activity.onBackPressedDispatcher.onBackPressed()
        verify(exactly = 1) { page.viewModel.dispatch(exitByBack) }

        
        host.owner.registry.currentState = Lifecycle.State.CREATED
        activity.onBackPressedDispatcher.onBackPressed()
        verify(exactly = 1) { page.viewModel.dispatch(exitByBack) }
    }

    @Test
    fun `a peeked tap reaches the row underneath as a cancel`() {
        val context = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val received = mutableListOf<Int>()
        val row = object : View(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                received += event.actionMasked
                return true
            }
        }
        val root = TapPeekFrameLayout(context).apply {
            addView(row, FrameLayout.LayoutParams(100, 100))
            layout(0, 0, 100, 100)
            row.layout(0, 0, 100, 100)
        }

        root.onPeek = { false }
        root.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        root.onPeek = { true }
        root.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 10f, 10f, 0))

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), received)
    }
}
