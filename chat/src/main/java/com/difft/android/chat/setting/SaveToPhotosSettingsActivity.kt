package com.difft.android.chat.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.map
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

    /**
     * Save to photos option data class
     * @param value The value to store in database (null: follow global, 0: never, 1: always)
     * @param labelResId The string resource ID for the option label
     */
    private data class SaveToPhotosOption(
        val value: Int?,
        val labelResId: Int
    )

    private val options = listOf(
        SaveToPhotosOption(null, R.string.save_to_photos_default),
        SaveToPhotosOption(1, R.string.save_to_photos_always),
        SaveToPhotosOption(0, R.string.save_to_photos_never)
    )

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
            DifftTheme(useSecondaryBackground = true) {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    @Composable
    private fun MainContent() {
        // Get current saveToPhotos value from ViewModel
        val selectedValue = chatSettingViewModel.conversationSet
            .map { it?.saveToPhotos }
            .collectAsState(initial = null)

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.me_save_to_photos),
                onBackClick = { finish() }
            )

            ItemViews(selectedValue.value) { newValue ->
                if (selectedValue.value != newValue) {
                    chatSettingViewModel.setSaveToPhotos(newValue)
                }
            }

            ExplainView()
        }
    }

    @Composable
    private fun ExplainView() {
        Text(
            text = getString(R.string.me_automatically_save_photos),
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
            fontSize = 12.sp,
            color = Color(
                ContextCompat.getColor(
                    LocalContext.current, com.difft.android.base.R.color.t_secondary
                )
            )
        )
    }

    @Composable
    private fun ItemViews(
        selectedValue: Int?,
        onNewOptionSelected: ((Int?) -> Unit)? = null
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
            itemsIndexed(options, key = { _, option -> option.value ?: -1 }) { index, option ->
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
                        val isItemSelected = selectedValue == option.value
                        val labelTextColor = remember {
                            Color(
                                ContextCompat.getColor(
                                    context, com.difft.android.base.R.color.t_primary
                                )
                            )
                        }

                        Text(
                            text = getOptionLabel(option),
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

    /**
     * Get the display label for an option.
     * For the Default option, dynamically show (On/Off) based on global setting.
     */
    private fun getOptionLabel(option: SaveToPhotosOption): String {
        return if (option.value == null) {
            // Default option - show dynamic status based on global setting
            val globalEnabled = userManager.getUserData()?.saveToPhotos == true
            val statusText = if (globalEnabled) {
                getString(R.string.save_to_photos_on)
            } else {
                getString(R.string.save_to_photos_off)
            }
            getString(R.string.save_to_photos_default, statusText)
        } else {
            getString(option.labelResId)
        }
    }
}