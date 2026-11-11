package com.difft.android.setting.language

import android.content.Intent
import android.os.Bundle
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.base.utils.LanguageData
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess





@AndroidEntryPoint
class LanguageFragment : Fragment(), SettingsIdiomPage {

    companion object {
        fun newInstance() = LanguageFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { DifftTheme(applyWindowBackground = ownsWindow()) { LanguagePage() } }
    }

    
    @Composable
    private fun LanguagePage() {
        val context = LocalContext.current
        Column(
            Modifier
                .fillMaxSize()
                .detailPageInsets(containerPadsBottom())
                .background(DifftTheme.colors.bg),
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.me_language),
                showBackButton = showsDetailBackButton(),
                onBackClick = { detailNavigation().pop(this@LanguageFragment) },
            )
            
            
            LanguageSettingsContent(
                languages = LanguageUtils.getLanguageList(context),
                onSelect = ::onLanguageSelected,
            )
        }
    }

    private fun onLanguageSelected(languageData: LanguageData) {
        val context = requireContext()
        if (LanguageUtils.isCurrentChoice(context, languageData.locale)) return

        val title = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_required)
        val content = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_tips)
        val ok = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart)
        val cancel = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_cancel)

        val dialog = dismissWithView(ComposeDialogManager.showMessageDialog(
            context = context,
            title = title,
            message = content,
            confirmText = ok,
            cancelText = cancel,
            onConfirm = {
                LanguageUtils.saveLanguage(context, languageData.locale)

                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(this)
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            },
            onCancel = {}
        ))
        dismissWhenLeavingVisibleTop { dialog?.dismiss() }
    }

}
