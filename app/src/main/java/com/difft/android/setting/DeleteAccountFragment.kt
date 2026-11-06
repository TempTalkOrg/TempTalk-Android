package com.difft.android.setting

import android.os.Bundle
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.pane.*
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.setting.mvi.DeleteAccountContract
import com.difft.android.setting.mvi.DeleteAccountViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class DeleteAccountFragment : Fragment(), SettingsIdiomPage {
    private val model: DeleteAccountViewModel by viewModels()
    private val deleteWait = PageWait(this)
    private val deleteWaitRequested = MutableStateFlow(false)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        deleteWaitRequested.value = false
        waitWhileVisibleTop(combine(model.state, deleteWaitRequested) { state, requested -> state.busy && requested }, deleteWait)
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        (view as ComposeView).setContent {
            val state by model.state.collectAsState()
            // Synchronous IME echo; SavedStateHandle owns the draft across view recreation.
            var input by remember(model) { mutableStateOf(model.state.value.input) }
            DifftTheme(applyWindowBackground = ownsWindow()) {
                Column(Modifier.fillMaxSize().background(DifftTheme.colors.bg).detailPageInsets(containerPadsBottom(), ime = true)) {
                    TitleBar(stringResource(R.string.me_delete_account), onBackClick = { detailNavigation().pop(this@DeleteAccountFragment) }, showBackButton = showsDetailBackButton())
                    // Top inset as padding, not a spacer: spacedBy would add the group gap after a spacer.
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DifftSettingsDefaults.PageInset).padding(top = DifftSettingsDefaults.FirstGroupSpacing), verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.GroupSpacing)) {
                        Text(stringResource(R.string.me_delete_account_tips1), style = DifftTheme.typography.p2, color = DifftTheme.colors.textPrimary)
                        Text(stringResource(R.string.me_delete_account_tips2, state.suffix), style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
                        DifftClearableTextField(value = input, enabled = !state.busy, onValueChange = { input = it; model.dispatch(DeleteAccountContract.Intent.ChangeInput(it)) }, onClear = { input = ""; model.dispatch(DeleteAccountContract.Intent.ChangeInput("")) }, style = DifftTextFieldStyle.Form, modifier = Modifier.fillMaxWidth())
                        DifftButton(stringResource(R.string.me_delete), enabled = !state.busy && state.input.trim() == state.suffix, onClick = { confirmDelete() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Busy survives a fold, but the confirmation's modal claim is never mounted again.
            model.state.collect { if (!it.busy) deleteWait.dismiss() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effect.collect { when (it) {
                    is DeleteAccountContract.Effect.Failed -> ToastUtil.showLong(it.message ?: getString(R.string.operation_failed))
                } }
            }
        }
    }

    private fun confirmDelete() {
        if (model.state.value.busy) return
        val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireContext(), title = getString(R.string.me_delete_dialog_title), message = getString(R.string.me_delete_dialog_content),
            confirmText = getString(R.string.me_delete_dialog_ok), cancelText = getString(R.string.me_delete_dialog_cancel),
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)),
            onConfirm = {
                if (!detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || model.state.value.busy) return@showMessageDialog
                deleteWaitRequested.value = true
                val originalWait = deleteWait
                model.dispatch(DeleteAccountContract.Intent.Confirm { originalWait.dismiss() })
            },
        ))
        dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
    }
}
