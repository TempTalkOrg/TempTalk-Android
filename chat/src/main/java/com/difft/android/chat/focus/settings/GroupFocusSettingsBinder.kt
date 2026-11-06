package com.difft.android.chat.focus.settings

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.FocusDialogs
import com.difft.android.chat.focus.GroupNotifySettingsReader
import com.difft.android.chat.focus.showFocusError
import com.difft.android.chat.group.GroupNotificationSettingsActivity
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Effect
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Intent
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.network.requests.MuteStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FocusUiEntryPoint {
    fun focusActionLauncher(): FocusActionLauncher
    fun groupNotifySettingsReader(): GroupNotifySettingsReader
}

/**
 * Hosts the focus / mute / notification section in the group settings page. Renders the section
 * state, feeds the group's conversation config into [GroupFocusSettingsViewModel], and maps its
 * effects to navigation, dialogs and the existing conversation write path. [setup] runs once per
 * view (the group info page is a Fragment): collection follows [lifecycleOwnerProvider], dialogs and
 * navigation use [activityProvider]. The ViewModels are deferred providers because they are lazy.
 */
internal class GroupFocusSettingsBinder(
    private val activityProvider: () -> AppCompatActivity,
    private val lifecycleOwnerProvider: () -> LifecycleOwner,
    private val composeViewProvider: () -> ComposeView,
    private val groupIdProvider: () -> String,
    private val viewModelProvider: () -> GroupFocusSettingsViewModel,
    private val chatSettingViewModelProvider: () -> ChatSettingViewModel,
) {
    private val activity get() = activityProvider()
    private val groupId get() = groupIdProvider()
    private val viewModel get() = viewModelProvider()
    private val chatSettingViewModel get() = chatSettingViewModelProvider()

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(activity.applicationContext, FocusUiEntryPoint::class.java)
    }

    fun setup() {
        composeViewProvider().apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = false) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    GroupFocusSettingsSection(state = state, onIntent = viewModel::dispatch)
                }
            }
        }
        val owner = lifecycleOwnerProvider()
        owner.lifecycleScope.launch {
            chatSettingViewModel.conversationSet.filterNotNull().collect { set ->
                viewModel.dispatch(Intent.ConversationChanged(set.isMuted, set.allowMentionMe, set.allowMentionAll, set.allowGroupCall))
            }
        }
        // Resume re-reads what other screens change: the notification level and the sound.
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.dispatch(Intent.Refresh)
        })
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect(::handle)
            }
        }
    }

    private fun handle(effect: Effect) {
        val launcher = entryPoint.focusActionLauncher()
        when (effect) {
            is Effect.RequestFocus -> launcher.requestFocus(activity, effect.gid, effect.groupName)
            Effect.EndFocus -> launcher.endFocus(activity)
            Effect.ChangeEndTime -> launcher.changeEndTime(activity)
            Effect.OpenOthersStillNotify -> FocusOthersNotifyActivity.start(activity)
            Effect.OpenMuteExceptions -> MuteExceptionsActivity.start(activity, groupId)
            Effect.OpenNotificationLevel -> GroupNotificationSettingsActivity.start(activity, groupId)
            is Effect.OpenSound -> entryPoint.groupNotifySettingsReader().openSoundSettings(activity, effect.gid, effect.groupName)
            is Effect.WriteMute -> writeMute(effect.muted)
            is Effect.ShowPausedDialog -> showPausedDialog(effect.kind, launcher)
            Effect.ShowError -> showFocusError()
        }
    }

    /** Controlled: the conversation flow moves the switch once the server accepted; a failure leaves it. */
    private fun writeMute(muted: Boolean) {
        val job = chatSettingViewModel.setConversationConfigs(
            activity = activity,
            conversation = groupId,
            muteStatus = MuteStatus.of(muted).value,
        )
        val host = activity
        lifecycleOwnerProvider().lifecycleScope.launch {
            val token = ComposeDialogManager.showWait(host, "")
            try {
                job.join()
            } finally {
                ComposeDialogManager.dismissWait(token)
            }
        }
    }

    private fun showPausedDialog(kind: PausedDialog, launcher: FocusActionLauncher) {
        when (kind) {
            PausedDialog.MUTE_BY_FOCUS -> FocusDialogs.mutePausedByFocus(activity) { launcher.openNotificationSettings(activity) }
            PausedDialog.NOTIFY_BY_FOCUS -> FocusDialogs.notifyPausedByFocus(activity) { launcher.openNotificationSettings(activity) }
            PausedDialog.NOTIFY_BY_MUTE -> FocusDialogs.notifyPausedByMute(activity) { writeMute(false) }
        }
    }
}
