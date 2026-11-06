package com.difft.android.setting.notificationtest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R as ChatR
import com.difft.android.setting.BackgroundConnectionHelpLink
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract
import com.difft.android.setting.notificationtest.mvi.NotificationTestViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared hosted/shell page; the Fragment ViewModel owns the live run across view recreation. */
@AndroidEntryPoint
class NotificationTestFragment : Fragment(), SettingsIdiomPage {
    private val viewModel: NotificationTestViewModel by viewModels()
    private val visible = mutableStateOf(false)

    fun matchesReturn(route: NotificationTestReturnRoute): Boolean = viewModel.matchesReturn(route)

    fun hasActiveRun(): Boolean = viewModel.state.value.running != null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    Column(Modifier.fillMaxSize().detailPageInsets(containerPadsBottom())) {
                        TitleBar(titleText = getString(ChatR.string.notification_test_entry), showBackButton = showsDetailBackButton(),
                            onBackClick = { detailNavigation().pop(this@NotificationTestFragment) })
                        NotificationTestContent(state = state, onIntent = viewModel::dispatch, dialogsVisible = visible.value)
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val owner = detailBackOwner()
        visible.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                visible.value = true
                viewModel.dispatch(NotificationTestContract.Intent.Resume)
            }
            override fun onStop(owner: LifecycleOwner) { visible.value = false }
            override fun onDestroy(owner: LifecycleOwner) { visible.value = false }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is NotificationTestContract.Effect.OpenFix -> openFix(effect.fix)
                        NotificationTestContract.Effect.OpenGuide -> requireActivity().openExternalBrowser(
                            BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS),
                        )
                    }
                }
            }
        }
    }

    private fun openFix(fix: NotificationFix) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val blocked = fix == NotificationFix.MESSAGE_CATEGORY && withContext(Dispatchers.IO) { NotificationFixJump.isMessageGroupBlocked(appContext) }
            if (!visible.value) return@launch
            if (!NotificationFixJump.open(requireActivity(), fix, blocked)) {
                L.w { "[NotificationTest] fix_failed fix=$fix" }
                ToastUtil.show(ChatR.string.notification_settings_open_fail)
            }
        }
    }

    companion object {
        fun newInstance(shell: Boolean = false) = NotificationTestFragment().apply {
            arguments = Bundle().apply { putBoolean(NotificationTestViewModel.SHELL, shell) }
        }
    }
}
