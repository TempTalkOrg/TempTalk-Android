package com.difft.android.chat.setting

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
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Disappearing-messages time of one conversation; a page of the home detail stack or its shell. */
@AndroidEntryPoint
class ChatArchiveSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    private val target: For? by lazy { conversationTargetArg() }

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        val target = target
        if (target != null) {
            defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> { it.create(target) }
        } else {
            defaultViewModelCreationExtras
        }
    })

    private val archiveWait = PageWait(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val target = target ?: return FrameLayout(inflater.context)
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    MainContent(target, padsBottom)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (target == null) {
            L.w { "[ChatArchiveSettings] page without a conversation" }
            detailNavigation().pop(this)
            return
        }
        waitWhileVisibleTop(chatSettingViewModel.archiveBusy, archiveWait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatSettingViewModel.archiveErrors.collect { ToastUtil.show(it) }
            }
        }
    }

    @Composable
    private fun MainContent(target: For, padsBottom: Boolean) {
        val selectedOption = chatSettingViewModel.messageExpiry.collectAsState()
        val timeList = if (target is For.Group) {
            messageArchiveManager.getGroupDefaultArchiveTimeList()
        } else {
            messageArchiveManager.getDefaultArchiveTimeList()
        }

        Column(Modifier.fillMaxSize().detailPageInsets(padsBottom)) {
            TitleBar(
                titleText = getString(R.string.disappearing_messages),
                onBackClick = { detailNavigation().pop(this@ChatArchiveSettingsFragment) },
            )
            ChatArchiveSettingsContent(
                options = timeList,
                selected = selectedOption.value,
                onSelect = { newOption ->
                    // Re-selecting the current value is a no-op.
                    if (selectedOption.value != newOption) {
                        showTimeChangeDialog(selectedOption.value, newOption) {
                            chatSettingViewModel.updateArchiveTime(newOption)
                        }
                    }
                },
            )
        }
    }

    private fun showTimeChangeDialog(currentTime: Long, newTime: Long, onConfirm: () -> Unit) {
        val isExtending = newTime > currentTime
        val title = getString(if (isExtending) R.string.chat_archive_extend_time_title else R.string.chat_archive_shorten_time_title)
        val message = getString(if (isExtending) R.string.chat_archive_extend_time_message else R.string.chat_archive_shorten_time_message)
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = title,
            message = message,
            confirmText = getString(R.string.chat_dialog_ok),
            cancelText = getString(R.string.chat_dialog_cancel),
            onConfirm = { onConfirm() },
        ))
    }

    companion object {
        fun newInstance(target: For): ChatArchiveSettingsFragment =
            ChatArchiveSettingsFragment().apply { arguments = ConversationTargetArgs.bundle(target) }
    }
}

/** Disappearing-messages time as a detail destination (home detail stack, or its shell Activity). */
class ChatArchiveSettingsDestination(private val target: For) : DetailDestination {
    override fun newFragment(): Fragment = ChatArchiveSettingsFragment.newInstance(target)
    override fun shellIntent(context: Context): Intent = ChatArchiveSettingsActivity.intent(context, target)
    override val subjectId: String get() = target.id
}
