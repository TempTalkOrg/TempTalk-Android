package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.TitleBar
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
            DifftTheme(useSecondaryBackground = true) {
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

            ItemViews(selectedSpeed) { newSpeed ->
                if (selectedSpeed != newSpeed) {
                    selectedSpeed = newSpeed
                    userManager.update { voicePlaybackSpeed = newSpeed }
                }
            }
        }
    }

    @Composable
    private fun ItemViews(
        selectedSpeed: Float,
        onNewOptionSelected: ((Float) -> Unit)? = null
    ) {
        val context = LocalContext.current
        val bgItem = remember {
            Color(
                ContextCompat.getColor(
                    context, com.difft.android.base.R.color.bg_setting_item
                )
            )
        }

        LazyColumn(modifier = Modifier.padding(16.dp)) {
            itemsIndexed(options, key = { _, option -> option.value }) { index, option ->
                val backgroundShape = remember(option) {
                    when (index) {
                        0 -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        options.size - 1 -> RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                        else -> RectangleShape
                    }
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(bgItem, backgroundShape)
                            .clickable {
                                onNewOptionSelected?.invoke(option.value)
                            }
                            .padding(start = 16.dp, end = 16.dp),
                    ) {
                        val isItemSelected = selectedSpeed == option.value
                        val labelTextColor = remember {
                            Color(
                                ContextCompat.getColor(
                                    context, com.difft.android.base.R.color.t_primary
                                )
                            )
                        }

                        Text(
                            text = getString(option.labelResId),
                            modifier = Modifier.weight(1f),
                            color = labelTextColor
                        )

                        if (isItemSelected) {
                            val tint: Color = remember {
                                Color(
                                    ContextCompat.getColor(
                                        context, com.difft.android.base.R.color.t_secondary
                                    )
                                )
                            }
                            Image(
                                imageVector = ImageVector.vectorResource(id = R.drawable.chat_ic_selected),
                                colorFilter = ColorFilter.tint(tint),
                                contentDescription = "Checked",
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }

                    // Add divider between items
                    if (index < options.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    Color(
                                        ContextCompat.getColor(
                                            context,
                                            com.difft.android.base.R.color.bg_setting
                                        )
                                    )
                                )
                        )
                    }
                }
            }
        }
    }
}
