package com.difft.android.chat.setting.translate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.TranslateSettingsViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
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
 * translation pack the selected target needs.
 */
@AndroidEntryPoint
class TranslateSettingsActivity : BaseActivity() {

    companion object {
        private const val EXTRA_KEY_TARGET_ID = "EXTRA_KEY_TARGET_ID"
        private const val EXTRA_KEY_IS_GROUP = "EXTRA_KEY_IS_GROUP"

        fun start(activity: Activity, target: For) {
            val intent = Intent(activity, TranslateSettingsActivity::class.java)
            intent.putExtra(EXTRA_KEY_TARGET_ID, target.id)
            intent.putExtra(EXTRA_KEY_IS_GROUP, target is For.Group)
            activity.startActivity(intent)
        }
    }

    @Inject
    lateinit var translateBackend: TranslateBackend

    private val viewModel: TranslateSettingsViewModel by viewModels(extrasProducer = {
        val targetId = intent.getStringExtra(EXTRA_KEY_TARGET_ID)
        val isGroup = intent.getBooleanExtra(EXTRA_KEY_IS_GROUP, false)
        if (targetId != null) {
            val target = if (isGroup) For.Group(targetId) else For.Account(targetId)
            defaultViewModelCreationExtras.withCreationCallback<TranslateSettingsViewModelFactory> {
                it.create(target)
            }
        } else {
            defaultViewModelCreationExtras
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getStringExtra(EXTRA_KEY_TARGET_ID).isNullOrEmpty()) {
            finish()
            return
        }

        // Every entry point to this screen is already hidden on a build that cannot translate, so
        // reaching here means something routed past that. Close rather than render a screen whose
        // every control is inert.
        if (!translateBackend.isSupported) {
            L.w { "[Translate] settings screen opened on a build without a translate backend" }
            finish()
            return
        }

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                MainContent()
            }
        }
        setContentView(composeView)

        collectEffects()
    }

    private fun collectEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is TranslateSettingsContract.Effect.ShowToast -> ToastUtil.show(effect.res)
                    }
                }
            }
        }
    }

    @Composable
    private fun MainContent() {
        val state = viewModel.state.collectAsState()

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TitleBar(
                titleText = getString(R.string.chat_translate_setting_title),
                onBackClick = { finish() },
            )

            TranslateSettingsContent(
                state = state.value,
                onSelect = { viewModel.dispatch(TranslateSettingsContract.Intent.Select(it)) },
                onRetry = { viewModel.dispatch(TranslateSettingsContract.Intent.RetryDownload) },
                onCancel = { viewModel.dispatch(TranslateSettingsContract.Intent.CancelDownload) },
            )
        }
    }
}
