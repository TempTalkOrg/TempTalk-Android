package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.*
import com.difft.android.setting.mvi.PasscodeTimeoutContract
import com.difft.android.setting.mvi.PasscodeTimeoutViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SetPasscodeTimeoutFragment : Fragment(), SettingsIdiomPage {
    private val model: PasscodeTimeoutViewModel by viewModels()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by model.state.collectAsState()
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Column(Modifier.fillMaxSize().background(DifftTheme.colors.bg).detailPageInsets(containerPadsBottom())) {
                        TitleBar(getString(R.string.settings_screen_lock_timeout), onBackClick = { detailNavigation().pop(this@SetPasscodeTimeoutFragment) }, showBackButton = showsDetailBackButton())
                        PasscodeTimeoutContent(state.selected, onSelect = { model.dispatch(PasscodeTimeoutContract.Intent.Select(it)) })
                    }
                }
            }
        }
    override fun onResume() { super.onResume(); model.dispatch(PasscodeTimeoutContract.Intent.Refresh) }
}
