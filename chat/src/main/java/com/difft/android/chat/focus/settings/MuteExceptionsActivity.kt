package com.difft.android.chat.focus.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.compose.systemBarsWithCutoutPadding
import com.difft.android.base.ui.sidechrome.sideChromePadding
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch


@AndroidEntryPoint
class MuteExceptionsActivity : BaseActivity() {

    private val groupId: String by lazy { intent.getStringExtra(KEY_GROUP_ID).orEmpty() }

    private val viewModel: MuteExceptionsViewModel by viewModels()

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Group(groupId))
        }
    })

    override fun participatesInSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (groupId.isEmpty()) {
            finish()
            return
        }
        setContent {
            DifftTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                StillNotifyScreen(
                    title = getString(R.string.focus_still_notify),
                    state = state.items,
                    onToggle = { item, on -> viewModel.dispatch(MuteExceptionsContract.Intent.Toggle(item, on)) },
                    onBack = { finish() },
                    modifier = Modifier.systemBarsWithCutoutPadding().sideChromePadding(),
                )
            }
        }
        lifecycleScope.launch {
            chatSettingViewModel.conversationSet.filterNotNull().collect { set ->
                viewModel.dispatch(MuteExceptionsContract.Intent.ConversationChanged(set.toMuteOptions()))
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is MuteExceptionsContract.Effect.Write -> write(effect)
                    }
                }
            }
        }
    }

    private fun write(effect: MuteExceptionsContract.Effect.Write) {
        val job = chatSettingViewModel.setConversationConfigs(
            activity = this,
            conversation = groupId,
            allowMentionMe = effect.on.takeIf { effect.item == StillNotifyItem.MENTION_ME },
            allowMentionAll = effect.on.takeIf { effect.item == StillNotifyItem.MENTION_ALL },
            allowGroupCall = effect.on.takeIf { effect.item == StillNotifyItem.GROUP_CALL },
        )
        lifecycleScope.launch {
            job.join()
            
            chatSettingViewModel.conversationSet.value?.let {
                viewModel.dispatch(MuteExceptionsContract.Intent.ConversationChanged(it.toMuteOptions()))
            }
            viewModel.dispatch(MuteExceptionsContract.Intent.WriteFinished(effect.item))
        }
    }

    private fun com.difft.android.network.responses.ConversationSetResponseBody.toMuteOptions() =
        MuteOptions(allowMentionMe, allowMentionAll, allowGroupCall)

    companion object {
        private const val KEY_GROUP_ID = GroupFocusSettingsViewModel.KEY_GROUP_ID

        fun start(activity: Activity, groupId: String) {
            activity.startActivity(Intent(activity, MuteExceptionsActivity::class.java).putExtra(KEY_GROUP_ID, groupId))
        }
    }
}
