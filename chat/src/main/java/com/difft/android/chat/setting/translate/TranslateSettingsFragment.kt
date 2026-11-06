package com.difft.android.chat.setting.translate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.TranslateSettingsViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.setting.ConversationTargetArgs
import com.difft.android.chat.setting.conversationTargetArg
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsViewModel
import com.difft.android.chat.translate.backend.TranslateBackend
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Conversation-level auto-translate settings: off / Chinese / English, plus the state of the
 * translation pack the selected target needs. A page of the home detail stack or its shell.
 */
@AndroidEntryPoint
class TranslateSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var translateBackend: TranslateBackend

    private val target: For? by lazy { conversationTargetArg() }

    private val viewModel: TranslateSettingsViewModel by viewModels(extrasProducer = {
        val target = target
        if (target != null) {
            defaultViewModelCreationExtras.withCreationCallback<TranslateSettingsViewModelFactory> { it.create(target) }
        } else {
            defaultViewModelCreationExtras
        }
    })

    // Every entry point is hidden on a build that cannot translate; reaching here means something
    // routed past that, so the page leaves instead of showing inert controls.
    private val usable: Boolean get() = target != null && translateBackend.isSupported

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!usable) return FrameLayout(inflater.context)
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    MainContent(padsBottom)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!usable) {
            L.w { "[Translate] settings page left hasTarget=${target != null} supported=${translateBackend.isSupported}" }
            detailNavigation().pop(this)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is TranslateSettingsContract.Effect.ShowToast -> ToastUtil.show(effect.res)
                    }
                }
            }
        }
    }

    @Composable
    private fun MainContent(padsBottom: Boolean) {
        val state = viewModel.state.collectAsState()
        Column(Modifier.fillMaxSize().detailPageInsets(padsBottom)) {
            TitleBar(
                titleText = getString(R.string.chat_translate_setting_title),
                onBackClick = { detailNavigation().pop(this@TranslateSettingsFragment) },
            )
            TranslateSettingsContent(
                state = state.value,
                onSelect = { viewModel.dispatch(TranslateSettingsContract.Intent.Select(it)) },
                onRetry = { viewModel.dispatch(TranslateSettingsContract.Intent.RetryDownload) },
                onCancel = { viewModel.dispatch(TranslateSettingsContract.Intent.CancelDownload) },
            )
        }
    }

    companion object {
        fun newInstance(target: For): TranslateSettingsFragment =
            TranslateSettingsFragment().apply { arguments = ConversationTargetArgs.bundle(target) }
    }
}

/** Translate settings as a detail destination (home detail stack, or its shell Activity). */
class TranslateSettingsDestination(private val target: For) : DetailDestination {
    override fun newFragment(): Fragment = TranslateSettingsFragment.newInstance(target)
    override fun shellIntent(context: Context): Intent = TranslateSettingsActivity.intent(context, target)
    override val subjectId: String get() = target.id
}
