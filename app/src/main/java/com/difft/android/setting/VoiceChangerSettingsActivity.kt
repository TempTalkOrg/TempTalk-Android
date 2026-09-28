package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.call.data.VoicePreset
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Global voice-changer preference for calls. Writes to [UserManager.update]
 * ({@code callVoiceChangerPreset}); call entry points read it on start so
 * users don't have to pick the preset every call.
 *
 * The preference is local-only (never synced to the server) per product spec.
 */
@AndroidEntryPoint
class VoiceChangerSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, VoiceChangerSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        var selectedPreset by remember {
            mutableStateOf(
                VoicePreset.fromSdkKey(
                    userManager.getUserData()?.callVoiceChangerPreset
                        ?: VoicePreset.ORIGINAL.sdkKey
                )
            )
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.me_call_voice_changer),
                onBackClick = { finish() }
            )

            OptionList(selectedPreset) { newPreset ->
                if (selectedPreset != newPreset) {
                    selectedPreset = newPreset
                    userManager.update { callVoiceChangerPreset = newPreset.sdkKey }
                }
            }
        }
    }

    @Composable
    private fun OptionList(selected: VoicePreset, onSelect: (VoicePreset) -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                VoicePreset.entries.forEach { option ->
                    DifftSettingsOptionRow(
                        label = option.displayText(),
                        selected = selected == option,
                        onClick = { onSelect(option) },
                    )
                }
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
