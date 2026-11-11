package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.*
import com.difft.android.setting.viewmodel.SetCustomIdContract
import com.difft.android.setting.viewmodel.SetCustomIdViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SetCustomIdFragment : Fragment(), SettingsIdiomPage {
    companion object {
        fun newInstance(initialId: String) = SetCustomIdFragment().apply { arguments = Bundle().apply { putString("id", initialId) } }
    }
    private val model: SetCustomIdViewModel by viewModels()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.dispatch(SetCustomIdContract.Intent.Initialize(arguments?.getString("id").orEmpty()))
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        waitWhileVisibleTop(model.state.map { it.busy }, PageWait(this))
        (view as ComposeView).setContent {
            val state by model.state.collectAsState()
            
            var input by remember(model) { mutableStateOf(model.state.value.input) }
            DifftTheme(applyWindowBackground = ownsWindow()) {
                Column(Modifier.fillMaxSize().background(DifftTheme.colors.bg).detailPageInsets(containerPadsBottom(), ime = true)) {
                    TitleBar(stringResource(R.string.settings_contact_ID_title), onBackClick = { detailNavigation().pop(this@SetCustomIdFragment) }, showBackButton = showsDetailBackButton())
                    
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DifftSettingsDefaults.PageInset).padding(top = DifftSettingsDefaults.FirstGroupSpacing), verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.GroupSpacing)) {
                        DifftClearableTextField(
                            value = input, enabled = !state.busy && !state.completed,
                            onValueChange = { input = it; model.dispatch(SetCustomIdContract.Intent.ChangeInput(it)) },
                            onClear = { input = ""; model.dispatch(SetCustomIdContract.Intent.ChangeInput("")) },
                            hint = stringResource(R.string.settings_contact_ID_input_hint),
                            style = DifftTextFieldStyle.Form, isError = state.error != null, supportingText = state.error,
                            showCounter = true, maxLength = 20, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.settings_contact_ID_valid_tip), style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
                        DifftButton(stringResource(R.string.settings_contact_ID_button_save), onClick = { model.dispatch(SetCustomIdContract.Intent.Save) }, enabled = !state.busy && !state.completed && state.valid, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map { it.completed }.collect { completed ->
                    if (completed) detailNavigation().pop(this@SetCustomIdFragment)
                }
            }
        }
    }
}
