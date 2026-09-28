package com.difft.android.setting.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fragment for theme settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class ThemeFragment : Fragment() {

    companion object {
        fun newInstance() = ThemeFragment()
    }

    @Inject
    lateinit var userManager: UserManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { DifftTheme { ThemePage() } }
    }

    // The host applies system-bar insets (BaseActivity does it for the XML root that holds this
    // fragment), so this page must not pad again.
    @Composable
    private fun ThemePage() {
        val selectedMode = remember {
            mutableIntStateOf(
                userManager.getUserData()?.theme ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(DifftTheme.colors.bg),
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.me_theme),
                showBackButton = !isInDualPaneMode(),
                onBackClick = { activity?.finish() },
            )
            ThemeSettingsContent(
                selectedMode = selectedMode.intValue,
                onSelect = { mode ->
                    // Written before applying the mode: setDefaultNightMode recreates the host, and
                    // the checkmark must already sit on the tapped row for the frames until it does.
                    selectedMode.intValue = mode
                    AppCompatDelegate.setDefaultNightMode(mode)
                    userManager.update { this.theme = mode }
                },
            )
        }
    }
}
