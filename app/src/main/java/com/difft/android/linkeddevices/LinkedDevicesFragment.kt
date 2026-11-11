package com.difft.android.linkeddevices

import android.os.Bundle
import com.difft.android.base.utils.pane.SettingsIdiomPage
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import com.difft.android.R
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
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





@AndroidEntryPoint
class LinkedDevicesFragment : Fragment(), SettingsIdiomPage {

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
            DifftTheme(applyWindowBackground = ownsWindow()) {
                Box(Modifier.detailPageInsets(containerPadsBottom())) {
                    LinkedDevicesScreen(
                    viewModel,
                    onUnlinkConfirm = ::showUnlinkConfirm,
                    visibleOwner = detailBackOwner(),
                    showBackButton = showsDetailBackButton(),
                    onBack = { detailNavigation().pop(this@LinkedDevicesFragment) },
                )
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopExpecting() 
    }
    



    private fun showUnlinkConfirm(device: DeviceUiState) {
        val context = requireContext()
        val name = device.displayName ?: context.getString(R.string.linked_devices_unnamed)
        val dialog = dismissWithView(ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.linked_devices_unlink_confirm_title, name),
            message = context.getString(R.string.linked_devices_unlink_confirm_body),
            confirmText = context.getString(R.string.linked_devices_unlink_action),
            cancelText = context.getString(R.string.linked_devices_unlink_cancel),
            confirmButtonColor = Color(ContextCompat.getColor(context, com.difft.android.base.R.color.error)),
            onConfirm = { viewModel.unlink(device.id) },
        ))
        dismissWhenLeavingVisibleTop { dialog?.dismiss() }
    }
}
