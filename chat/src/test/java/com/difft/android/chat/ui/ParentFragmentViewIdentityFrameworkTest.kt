package com.difft.android.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParentFragmentViewIdentityFrameworkTest {

    class ChildFragment : Fragment() {
        var parentViewAtViewCreated: View? = null
        var parentViewAtDestroyView: View? = null
        var onDestroyViewCalled = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = View(requireContext())

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            parentViewAtViewCreated = parentFragment?.view
        }

        override fun onDestroyView() {
            super.onDestroyView()
            onDestroyViewCalled = true
            parentViewAtDestroyView = parentFragment?.view
        }
    }

    
    class ParentFragment : Fragment() {
        val child = ChildFragment()

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val root = FragmentContainerView(requireContext()).apply { id = CONTAINER_ID }
            childFragmentManager.commitNow { add(CONTAINER_ID, child) }
            return root
        }

        companion object {
            const val CONTAINER_ID = 0x0f0f01
        }
    }

    @Test
    fun `FA3 parentFragment view is non-null and reference-identical across the child view lifecycle`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val parent = ParentFragment()

        activity.supportFragmentManager.commitNow {
            add(android.R.id.content, parent)
        }

        val parentView = parent.view
        assertNotNull("parent view must exist once the parent is added", parentView)
        assertSame(
            "child must observe the parent's view at onViewCreated",
            parentView,
            parent.child.parentViewAtViewCreated
        )

        controller.pause().stop().destroy()

        assertSame(
            "child's onDestroyView must run before the parent's view is torn down",
            parentView,
            parent.child.parentViewAtDestroyView
        )
    }
}
