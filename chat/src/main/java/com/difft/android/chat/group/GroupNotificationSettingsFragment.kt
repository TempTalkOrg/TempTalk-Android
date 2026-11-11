package com.difft.android.chat.group

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
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
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException









@AndroidEntryPoint
class GroupNotificationSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var groupRepo: GroupRepo

    private val groupId: String by lazy { arguments?.getString(ARG_GROUP_ID).orEmpty() }
    private val operationKey: String get() = "group-notification:$groupId"
    private val writeWait = PageWait(this)
    private val store: SelfNotificationStore = WcdbSelfNotificationStore

    private val globalNotificationType = mutableIntStateOf(0)
    private val currentType = mutableIntStateOf(0)
    private val isDefaultEnabled = mutableStateOf(true)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (groupId.isEmpty()) return FrameLayout(inflater.context)
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
        if (groupId.isEmpty()) {
            L.w { "[GroupNotificationSettings] page without a group id" }
            detailNavigation().pop(this)
            return
        }
        waitWhileVisibleTop(PageOperations.running(operationKey), writeWait)
        onOperationEnded(operationKey) { readValues() }
    }

    override fun onResume() {
        super.onResume()
        if (groupId.isNotEmpty() && view != null) readValues()
    }

    
    private fun readValues() {
        globalNotificationType.intValue = userManager.getUserData()?.globalNotification ?: 0
        val groupId = groupId
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val values = withContext(Dispatchers.IO) { store.read(groupId, globalServices.myId) }
                isDefaultEnabled.value = values?.useGlobal ?: false
                currentType.intValue = values?.notification ?: 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettings] read group member failed: ${e.stackTraceToString()}" }
            }
        }
    }

    




    private fun changeSettings(request: ChangeSelfSettingsInGroupReq, onAccepted: () -> Unit) {
        if (PageOperations.running(operationKey).value) return
        val groupId = groupId
        val repo = groupRepo
        val store = store
        val key = operationKey
        launchOutlivingPage {
            val accepted = PageOperations.track(key) {
                try {
                    
                    val response = withContext(Dispatchers.IO) {
                        changeSelfNotification(repo, store, groupId, globalServices.myId, request)
                    }
                    if (response.status == 0) {
                        L.i { "[GroupNotificationSettings] update useGlobal=${request.useGlobal} notification=${request.notification} result=success" }
                        true
                    } else {
                        onPage { ToastUtil.show(response.reason ?: it.getString(R.string.operation_failed)) }
                        false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e { "[GroupNotificationSettings] update failed: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.show(it.getString(R.string.operation_failed)) }
                    false
                }
            }
            if (accepted) onPage { onAccepted() }
        }
    }

    @Composable
    private fun MainContent(padsBottom: Boolean) {
        Column(Modifier.fillMaxSize().detailPageInsets(padsBottom)) {
            TitleBar(
                titleText = getString(R.string.notification),
                onBackClick = { detailNavigation().pop(this@GroupNotificationSettingsFragment) },
            )
            GroupNotificationSettingsContent(
                globalNotificationType = globalNotificationType.intValue,
                isDefaultEnabled = isDefaultEnabled.value,
                currentType = currentType.intValue,
                onDefaultSettingClick = {
                    detailNavigation().push(this@GroupNotificationSettingsFragment, GroupGlobalNotificationSettingsDestination)
                },
                onSwitchChanged = { newValue ->
                    
                    changeSettings(ChangeSelfSettingsInGroupReq(notification = null, useGlobal = newValue)) {
                        isDefaultEnabled.value = newValue
                    }
                },
                onOptionSelected = { option ->
                    
                    changeSettings(ChangeSelfSettingsInGroupReq(notification = option, useGlobal = false)) {
                        currentType.intValue = option
                    }
                },
            )
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        fun newInstance(groupId: String): GroupNotificationSettingsFragment =
            GroupNotificationSettingsFragment().apply { arguments = Bundle().apply { putString(ARG_GROUP_ID, groupId) } }
    }
}


class GroupNotificationSettingsDestination(private val groupId: String) : DetailDestination {
    override fun newFragment(): Fragment = GroupNotificationSettingsFragment.newInstance(groupId)
    override fun shellIntent(context: Context): Intent = GroupNotificationSettingsActivity.intent(context, groupId)
    override val subjectId: String get() = groupId
}
