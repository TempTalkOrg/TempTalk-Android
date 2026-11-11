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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.sidechrome.SideChromeSettings
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.density.DisplayDensityNormalizer
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.ui.theme.ChatThemeSetting
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject







@AndroidEntryPoint
class ThemeFragment : Fragment(), SettingsIdiomPage {

    companion object {
        fun newInstance() = ThemeFragment()
    }

    @Inject
    lateinit var userManager: UserManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        
        StatusBarSetting.isHidden(requireContext())
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DifftTheme(applyWindowBackground = ownsWindow()) { ThemePage() } }
        }
    }

    
    
    @Composable
    private fun ThemePage() {
        val activity = requireActivity()
        val actions = remember(activity) { AppearanceActions(activity) }
        val selectedMode = remember {
            mutableIntStateOf(
                userManager.getUserData()?.theme ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        
        
        val displaySize = remember { mutableStateOf(DisplayDensityNormalizer.displaySize(activity)) }
        val navigationPlacement = remember { mutableStateOf(SideChromeSettings.placement(activity)) }
        
        val configuration = LocalConfiguration.current
        val navigationAvailable = remember(configuration) { NavigationPlacementAvailability.isAvailable(activity) }
        val textSize by TextSizeUtil.textSizeState.collectAsState()
        val statusBarHidden by StatusBarSetting.hidden.collectAsState()
        Column(
            Modifier
                .fillMaxSize()
                .detailPageInsets(containerPadsBottom())
                .background(DifftTheme.colors.bg),
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.me_appearance),
                showBackButton = showsDetailBackButton(),
                onBackClick = { detailNavigation().pop(this@ThemeFragment) },
            )
            val chatTheme by ChatThemeSetting.theme.collectAsState()
            ThemeSettingsContent(
                chatTheme = chatTheme,
                onSelectChatTheme = ChatThemeSetting::update,
                selectedMode = selectedMode.intValue,
                onSelect = { mode ->
                    
                    
                    selectedMode.intValue = mode
                    AppCompatDelegate.setDefaultNightMode(mode)
                    userManager.update { this.theme = mode }
                },
                largeText = textSize == TextSizeUtil.TEXT_SIZE_LAGER,
                onLargeTextChange = actions::setLargeText,
                displaySize = displaySize.value,
                onDisplaySizeChange = { size -> if (actions.setDisplaySize(size)) displaySize.value = size },
                navigationPlacement = navigationPlacement.value.takeIf { navigationAvailable },
                onNavigationPlacementChange = { placement ->
                    if (actions.setNavigationPlacement(placement)) navigationPlacement.value = placement
                },
                statusBarHidden = statusBarHidden,
                onStatusBarHiddenChange = actions::setStatusBarHidden,
            )
        }
    }
}
