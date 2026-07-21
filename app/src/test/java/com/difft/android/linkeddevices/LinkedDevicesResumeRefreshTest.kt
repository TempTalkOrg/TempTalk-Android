package com.difft.android.linkeddevices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.user.LogoutManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Refresh-on-resume contract and the no-persistence boundary. Degrade: the real hosts are
 * `@AndroidEntryPoint` and the repo ships no Hilt test harness, so non-Hilt host doubles (onResume
 * bodies identical to the production hosts) drive the real Robolectric lifecycle with a directly-
 * constructed VM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkedDevicesResumeRefreshTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val repo: DeviceRepository = mockk()
    private val logoutManager: LogoutManager = mockk(relaxed = true)
    private val countStore: LinkedDevicesCountStore = mockk(relaxed = true)

    private fun buildViewModel() = LinkedDevicesViewModel(repo, logoutManager, countStore)

    /** Activity host double — onResume body identical to [LinkedDevicesActivity]. */
    class ResumeHostActivity : FragmentActivity() {
        lateinit var viewModel: LinkedDevicesViewModel
        override fun onResume() {
            super.onResume()
            viewModel.refresh()
        }
    }

    /** Fragment host double — onResume body identical to [LinkedDevicesFragment]. */
    class ResumeHostFragment(private val vm: LinkedDevicesViewModel) : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = FrameLayout(requireContext())

        override fun onResume() {
            super.onResume()
            vm.refresh()
        }
    }

    // Resuming the (phone) host twice re-invokes getDevices on each resume.
    @Test
    fun `I7 activity host refreshes on every resume`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        val controller = Robolectric.buildActivity(ResumeHostActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.create()
        try {
            controller.get().viewModel = vm
            controller.start().resume() // onResume #1 -> refresh #1
            controller.pause().resume() // onResume #2 -> refresh #2
            coVerify(atLeast = 2) { repo.getDevices() }
        } finally {
            runCatching { controller.destroy() }
        }
    }

    // A hosted fragment re-invokes getDevices when it resumes after a stop/resume cycle.
    @Test
    fun `I8 fragment host refreshes on every resume`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        try {
            val activity = controller.get()
            val fragment = ResumeHostFragment(vm)
            activity.supportFragmentManager.beginTransaction()
                .add(fragment, "linked-devices").commitNow() // RESUMED activity -> onResume #1
            controller.pause().stop().start().resume()        // fragment onResume #2
            coVerify(atLeast = 2) { repo.getDevices() }
        } finally {
            runCatching { controller.destroy() }
        }
    }

    // A fresh ViewModel reads nothing until a resume drives refresh(): empty state, no fetch.
    @Test
    fun `I10 fresh ViewModel is empty and issues no fetch before resume`() {
        val vm = buildViewModel()
        val state = vm.uiState.value
        assertTrue(state.devices.isEmpty())
        assertFalse(state.isLoading)
        coVerify(exactly = 0) { repo.getDevices() }
    }
}
