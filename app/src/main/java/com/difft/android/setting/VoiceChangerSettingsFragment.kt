package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.R
import com.difft.android.setting.mvi.LocalPreferenceContract
import com.difft.android.setting.mvi.LocalPreferenceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.difft.android.call.data.VoicePreset

@AndroidEntryPoint
class VoiceChangerSettingsFragment : Fragment(), SettingsIdiomPage {
    private val viewModel: LocalPreferenceViewModel by viewModels()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DifftTheme(applyWindowBackground = ownsWindow()) { MainContent() } }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.closeRequested }.distinctUntilChanged().collect { close ->
                    if (close) detailNavigation().pop(this@VoiceChangerSettingsFragment)
                }
            }
        }
    }

    @Composable
    private fun MainContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val selectedPreset = state.voice

        Column(
            Modifier.fillMaxSize().detailPageInsets(containerPadsBottom())
        ) {
            TitleBar(
                titleText = getString(R.string.me_call_voice_changer),
                onBackClick = { detailNavigation().pop(this@VoiceChangerSettingsFragment) }
            )

            OptionList(selectedPreset) { newPreset ->
                viewModel.dispatch(LocalPreferenceContract.Intent.SelectVoice(newPreset))
            }
        }
    }

    @Composable
    private fun OptionList(selected: VoicePreset, onSelect: (VoicePreset) -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                VoicePreset.entries.forEach { option ->
                    DifftSettingsOptionRow(
                        label = option.displayText(),
                        selected = selected == option,
                        onClick = { onSelect(option) },
                    )
                }
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}

object VoiceChangerDestination : DetailDestination {
    override fun newFragment(): Fragment = VoiceChangerSettingsFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, VoiceChangerSettingsActivity::class.java)
}
