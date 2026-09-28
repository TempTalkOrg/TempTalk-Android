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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity for global voice playback speed settings.
 * Provides three speed options: 1×, 1.5×, 2×
 */
@AndroidEntryPoint
class VoicePlaybackSpeedSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, VoicePlaybackSpeedSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    /**
     * Voice playback speed option
     * @param value The speed value (1.0f, 1.5f, 2.0f)
     * @param labelResId The string resource ID for the option label
     */
    private data class SpeedOption(
        val value: Float,
        val labelResId: Int
    )

    private val options = listOf(
        SpeedOption(1.0f, R.string.voice_speed_1x),
        SpeedOption(1.5f, R.string.voice_speed_1_5x),
        SpeedOption(2.0f, R.string.voice_speed_2x)
    )

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
        // Get current speed from UserManager
        var selectedSpeed by remember {
            mutableFloatStateOf(userManager.getUserData()?.voicePlaybackSpeed ?: 1.0f)
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.me_voice_playback_speed),
                onBackClick = { finish() }
            )

            OptionList(selectedSpeed) { newSpeed ->
                if (selectedSpeed != newSpeed) {
                    selectedSpeed = newSpeed
                    userManager.update { voicePlaybackSpeed = newSpeed }
                }
            }
        }
    }

    @Composable
    private fun OptionList(selectedSpeed: Float, onSelect: (Float) -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                options.forEach { option ->
                    DifftSettingsOptionRow(
                        label = getString(option.labelResId),
                        selected = selectedSpeed == option.value,
                        onClick = { onSelect(option.value) },
                    )
                }
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
