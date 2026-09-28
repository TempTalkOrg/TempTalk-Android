package com.difft.android.linkeddevices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
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
 * Refresh-on-resume contract and the no-persistence boundary. Degrade: the real host
 * ([LinkedDevicesFragment], the only production host on both form factors) is `@AndroidEntryPoint`
 * and the repo ships no Hilt test harness, so a non-Hilt fragment double (onResume body identical
 * to the production host) drives the real Robolectric lifecycle with a directly-constructed VM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkedDevicesResumeRefreshTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val repo: DeviceRepository = mockk()
    private val countStore: LinkedDevicesCountStore = mockk(relaxed = true)

    private fun buildViewModel() = LinkedDevicesViewModel(repo, countStore)

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
