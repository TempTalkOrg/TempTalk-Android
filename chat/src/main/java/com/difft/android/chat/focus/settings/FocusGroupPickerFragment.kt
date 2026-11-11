package com.difft.android.chat.focus.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.focus.FocusActionLauncher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject






internal interface FocusGroupPickerHost {
    
    fun onPickerFinished(gid: String?)
}






@AndroidEntryPoint
class FocusGroupPickerFragment : Fragment(), SettingsIdiomPage {

    private val viewModel: FocusGroupPickerViewModel by viewModels()

    @Inject
    lateinit var launcher: FocusActionLauncher

    
    fun cancel() {
        viewModel.dispatch(FocusGroupPickerContract.Intent.Cancel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.dispatch(FocusGroupPickerContract.Intent.Load)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    FocusGroupPickerScreen(
                        state = state,
                        onQueryChange = { viewModel.dispatch(FocusGroupPickerContract.Intent.QueryChanged(it)) },
                        onSelect = { viewModel.dispatch(FocusGroupPickerContract.Intent.Select(it)) },
                        onCancel = { viewModel.dispatch(FocusGroupPickerContract.Intent.Cancel) },
                        onDone = { viewModel.dispatch(FocusGroupPickerContract.Intent.Done) },
                        modifier = Modifier.detailPageInsets(padsBottom, ime = true),
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is FocusGroupPickerContract.Effect.Finish -> finish(effect.gid)
                    }
                }
            }
        }
    }

    private fun finish(gid: String?) {
        val host = activity as? FocusGroupPickerHost
        when {
            host != null -> host.onPickerFinished(gid)
            gid == null -> leave()
            else -> launcher.applyPickedGroup(requireActivity(), gid) { settled -> if (settled) leave() }
        }
    }

    private fun leave() {
        
        if (!isAdded) return
        hideDetailKeyboardBeforeLeaving()
        detailNavigation().pop(this)
    }
}
