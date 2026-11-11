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
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.chat.R
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import javax.inject.Inject





@AndroidEntryPoint
class SaveToPhotosSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var userManager: UserManager

    private val target: For? by lazy { conversationTargetArg() }

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        val target = target
        if (target != null) {
            defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> { it.create(target) }
        } else {
            defaultViewModelCreationExtras
        }
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (target == null) return FrameLayout(inflater.context)
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    MainContent(padsBottom)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (target == null) {
            L.w { "[SaveToPhotosSettings] page without a conversation" }
            detailNavigation().pop(this)
        }
    }

    @Composable
    private fun MainContent(padsBottom: Boolean) {
        val selectedValue = chatSettingViewModel.saveToPhotos.collectAsState()
        Column(Modifier.fillMaxSize().detailPageInsets(padsBottom)) {
            TitleBar(
                titleText = getString(R.string.me_save_to_photos),
                onBackClick = { detailNavigation().pop(this@SaveToPhotosSettingsFragment) },
            )
            SaveToPhotosSettingsContent(
                selected = selectedValue.value,
                defaultLabel = defaultOptionLabel(),
                onSelect = { newValue ->
                    if (selectedValue.value != newValue) chatSettingViewModel.setSaveToPhotos(newValue)
                },
            )
        }
    }

    
    private fun defaultOptionLabel(): String {
        val globalEnabled = userManager.getUserData()?.saveToPhotos == true
        val statusText = getString(if (globalEnabled) R.string.save_to_photos_on else R.string.save_to_photos_off)
        return getString(R.string.save_to_photos_default, statusText)
    }

    companion object {
        fun newInstance(target: For): SaveToPhotosSettingsFragment =
            SaveToPhotosSettingsFragment().apply { arguments = ConversationTargetArgs.bundle(target) }
    }
}


class SaveToPhotosSettingsDestination(private val target: For) : DetailDestination {
    override fun newFragment(): Fragment = SaveToPhotosSettingsFragment.newInstance(target)
    override fun shellIntent(context: Context): Intent = SaveToPhotosSettingsActivity.intent(context, target)
    override val subjectId: String get() = target.id
}
