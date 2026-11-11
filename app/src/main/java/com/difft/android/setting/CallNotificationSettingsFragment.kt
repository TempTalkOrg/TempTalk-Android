package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


@AndroidEntryPoint
class CallNotificationSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var stateReader: NotificationSettingsStateReader

    private var refreshJob: Job? = null
    private var uiState by mutableStateOf<NotificationSettingsUiState?>(null)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Column(Modifier.fillMaxSize().detailPageInsets(containerPadsBottom())) {
                        TitleBar(
                            titleText = stringResource(R.string.notification_calls),
                            onBackClick = { detailNavigation().pop(this@CallNotificationSettingsFragment) },
                        )
                        CallNotificationSettingsContent(
                            state = uiState,
                            actions = NotificationSubpageActions(
                                onFix = { openNotificationFix(it, stateReader) },
                                onGuide = {
                                    requireActivity().openExternalBrowser(
                                        BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS)
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }

    override fun onResume() {
        super.onResume()
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            stateReader.readOrNull(page = "calls")?.let { uiState = it }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        refreshJob = null
    }
}
