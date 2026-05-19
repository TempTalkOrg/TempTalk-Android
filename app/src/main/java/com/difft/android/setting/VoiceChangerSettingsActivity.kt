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
import androidx.compose.runtime.mutableStateOf
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

    private val options: List<VoicePreset> = VoicePreset.entries.toList()

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

            ItemViews(selectedPreset) { newPreset ->
                if (selectedPreset != newPreset) {
                    selectedPreset = newPreset
                    userManager.update { callVoiceChangerPreset = newPreset.sdkKey }
                }
            }
        }
    }

    @Composable
    private fun ItemViews(
        selectedPreset: VoicePreset,
        onNewOptionSelected: (VoicePreset) -> Unit
    ) {
        val context = LocalContext.current
        // Resolve all palette entries once per composition instead of once per list item.
        val bgItem = remember {
            Color(ContextCompat.getColor(context, com.difft.android.base.R.color.bg_setting_item))
        }
        val labelTextColor = remember {
            Color(ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary))
        }
        val checkmarkTint = remember {
            Color(ContextCompat.getColor(context, com.difft.android.base.R.color.t_secondary))
        }
        val dividerColor = remember {
            Color(ContextCompat.getColor(context, com.difft.android.base.R.color.bg_setting))
        }

        LazyColumn(modifier = Modifier.padding(16.dp)) {
            itemsIndexed(options, key = { _, option -> option.sdkKey }) { index, option ->
                // Keyed on `index` — `options` is a static enum list, but keying by position
                // guarantees the memoized shape invalidates correctly if the list ever
                // changes or items are reordered.
                val backgroundShape = remember(index) {
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
                            .clickable { onNewOptionSelected(option) }
                            .padding(start = 16.dp, end = 16.dp),
                    ) {
                        val isItemSelected = selectedPreset == option

                        Text(
                            text = option.displayText(),
                            modifier = Modifier.weight(1f),
                            color = labelTextColor
                        )

                        if (isItemSelected) {
                            Image(
                                imageVector = ImageVector.vectorResource(id = R.drawable.chat_ic_selected),
                                colorFilter = ColorFilter.tint(checkmarkTint),
                                contentDescription = "Checked",
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }

                    if (index < options.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(dividerColor)
                        )
                    }
                }
            }
        }
    }
}
