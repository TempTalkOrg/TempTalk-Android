package com.difft.android.linkeddevices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * Tablet dual-pane Compose host for the Linked Devices screen: a thin ComposeView shell; the back
 * button is hidden in dual-pane mode.
 */
@AndroidEntryPoint
class LinkedDevicesFragment : Fragment() {

    companion object {
        fun newInstance() = LinkedDevicesFragment()
    }

    private val viewModel: LinkedDevicesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DifftTheme {
                LinkedDevicesScreen(
                    viewModel,
                    showBackButton = !isInDualPaneMode(),
                    onBack = { activity?.finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopExpecting() // stop the expect-more poll timer while off-screen
    }
}
