package com.difft.android.chat.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.launchOutlivingPage
import com.difft.android.base.utils.pane.onOperationEnded
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.PrivateConfigsRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The global default notification for groups. A page of the home detail stack or its shell.
 * The write (server, then the stored profile) outlives the page under [OPERATION_KEY]; the shown
 * option is read from the stored profile whenever no write runs, so a page restored after a fold
 * shows the outcome. Pages that show this value re-read it on resume.
 */
@AndroidEntryPoint
class GroupGlobalNotificationSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    private val writeWait = PageWait(this)
    private val currentType = mutableIntStateOf(0)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val padsBottom = containerPadsBottom()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Column(Modifier.fillMaxSize().detailPageInsets(padsBottom)) {
                        TitleBar(
                            titleText = getString(R.string.notification_group_chat_notification),
                            onBackClick = { detailNavigation().pop(this@GroupGlobalNotificationSettingsFragment) },
                        )
                        OptionList(currentType.intValue, ::selectType)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        waitWhileVisibleTop(PageOperations.running(OPERATION_KEY), writeWait)
        onOperationEnded(OPERATION_KEY) { currentType.intValue = userManager.getUserData()?.globalNotification ?: 0 }
    }

    private fun selectType(newType: Int) {
        if (newType == currentType.intValue || PageOperations.running(OPERATION_KEY).value) return
        val http = httpClient
        val users = userManager
        launchOutlivingPage {
            PageOperations.track(OPERATION_KEY) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        http.httpService.fetchSetProfile(
                            profileRequestBody = ProfileRequestBody(privateConfigs = PrivateConfigsRequestBody(globalNotification = newType))
                        )
                    }
                    if (response.isSuccess()) {
                        users.update { this.globalNotification = newType }
                        L.i { "[GlobalNotificationSettings] set type=$newType result=success" }
                    } else {
                        onPage { ToastUtil.show(response.reason ?: it.getString(R.string.operation_failed)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e { "[GlobalNotificationSettings] set global notification failed: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.show(it.getString(R.string.operation_failed)) }
                }
            }
        }
    }

    @Composable
    private fun OptionList(selected: Int, onNewOptionSelected: (Int) -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                GlobalNotificationOptionRows(selected, onNewOptionSelected)
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }

    companion object {
        const val OPERATION_KEY = "global-notification"

        fun newInstance(): GroupGlobalNotificationSettingsFragment = GroupGlobalNotificationSettingsFragment()
    }
}

/** The global group-notification default as a detail destination (home stack, or its shell). */
data object GroupGlobalNotificationSettingsDestination : DetailDestination {
    override fun newFragment(): Fragment = GroupGlobalNotificationSettingsFragment.newInstance()
    override fun shellIntent(context: Context): Intent = GroupGlobalNotificationSettingsActivity.intent(context)
}
