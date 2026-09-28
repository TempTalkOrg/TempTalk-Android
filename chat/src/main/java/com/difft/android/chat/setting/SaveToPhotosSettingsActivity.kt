package com.difft.android.chat.setting

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
import com.difft.android.base.ui.TitleBar
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.base.user.UserManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import javax.inject.Inject

/**
 * Activity for conversation-level save to photos settings.
 * Provides three options:
 * - Default: Follow global setting (null)
 * - Always: Always save to photos (1)
 * - Never: Never save to photos (0)
 */
@AndroidEntryPoint
class SaveToPhotosSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    companion object {
        private const val EXTRA_KEY_TARGET_ID = "EXTRA_KEY_TARGET_ID"
        private const val EXTRA_KEY_IS_GROUP = "EXTRA_KEY_IS_GROUP"

        fun start(activity: Activity, target: For) {
            val intent = Intent(activity, SaveToPhotosSettingsActivity::class.java)
            intent.putExtra(EXTRA_KEY_TARGET_ID, target.id)
            intent.putExtra(EXTRA_KEY_IS_GROUP, target is For.Group)
            activity.startActivity(intent)
        }
    }

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        val targetId = intent.getStringExtra(EXTRA_KEY_TARGET_ID)
        val isGroup = intent.getBooleanExtra(EXTRA_KEY_IS_GROUP, false)
        if (targetId != null) {
            val target = if (isGroup) For.Group(targetId) else For.Account(targetId)
            defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
                it.create(target)
            }
        } else {
            defaultViewModelCreationExtras
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Validate required parameters
        val targetId = intent.getStringExtra(EXTRA_KEY_TARGET_ID)
        if (targetId.isNullOrEmpty()) {
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
    }

    @Composable
    private fun MainContent() {
        val selectedValue = chatSettingViewModel.saveToPhotos.collectAsState()

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.me_save_to_photos),
                onBackClick = { finish() }
            )

            SaveToPhotosSettingsContent(
                selected = selectedValue.value,
                defaultLabel = defaultOptionLabel(),
                onSelect = { newValue ->
                    if (selectedValue.value != newValue) {
                        chatSettingViewModel.setSaveToPhotos(newValue)
                    }
                },
            )
        }
    }

    /** Label of the "follow the global setting" option, carrying that setting's current state. */
    private fun defaultOptionLabel(): String {
        val globalEnabled = userManager.getUserData()?.saveToPhotos == true
        val statusText = if (globalEnabled) {
            getString(R.string.save_to_photos_on)
        } else {
            getString(R.string.save_to_photos_off)
        }
        return getString(R.string.save_to_photos_default, statusText)
    }
}
