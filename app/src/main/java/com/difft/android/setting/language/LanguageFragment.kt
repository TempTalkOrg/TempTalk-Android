package com.difft.android.setting.language

import android.content.Intent
import android.os.Bundle
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

/**
 * Fragment for language settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class LanguageFragment : Fragment() {

    companion object {
        fun newInstance() = LanguageFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { DifftTheme { LanguagePage() } }
    }

    // The host applies system-bar insets, so this page must not pad again.
    @Composable
    private fun LanguagePage() {
        val context = LocalContext.current
        Column(
            Modifier
                .fillMaxSize()
                .background(DifftTheme.colors.bg),
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.me_language),
                showBackButton = !isInDualPaneMode(),
                onBackClick = { activity?.finish() },
            )
            // Resolved on every composition: the list carries its own selected flag, and the choice
            // only ever changes by restarting the process.
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

        ComposeDialogManager.showMessageDialog(
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
        )
    }
}
