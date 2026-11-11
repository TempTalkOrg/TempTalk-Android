package com.difft.android.setting

import android.content.Intent
import android.os.Bundle
import com.difft.android.base.utils.pane.SettingsIdiomPage
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.isVisibleTopNow
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.difft.android.BuildConfig
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.call.node.LCallServerNodeActivity
import com.difft.android.network.UrlManager
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import javax.inject.Inject





@AndroidEntryPoint
class AboutFragment : Fragment(), SettingsIdiomPage {
    private lateinit var prepareWait: PageWait
    private lateinit var uploadWait: PageWait
    private lateinit var updateWait: PageWait
    private var prepareBusy = MutableStateFlow(false)
    private var uploadBusy = MutableStateFlow(false)
    private var updateBusy = MutableStateFlow(false)
    private var updateCheck: Job? = null
    private var logPreparation: Job? = null


    companion object {
        fun newInstance() = AboutFragment()

        
        private fun channelDisplayName(channel: String): String = when (channel) {
            "google" -> "Google"
            "official" -> "Official"
            "insider" -> "Insider"
            "fdroid" -> "F-Droid"
            else -> channel 
        }
    }

    @Inject
    lateinit var updateManager: UpdateManager

    @Inject
    lateinit var urlManager: UrlManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Box(Modifier.detailPageInsets(containerPadsBottom())) {
                    AboutPageView(
                        onBackClick = { detailNavigation().pop(this@AboutFragment) },
                        appVersion = PackageUtil.getAppVersionName() ?: "",
                        channelLabel = channelDisplayName(globalServices.environmentHelper.getChannelName()),
                        buildVersion = PackageUtil.getAppVersionCode().toString(),
                        buildTime = TimeUtils.millis2String(BuildConfig.BUILD_TIME.toLong()),
                        onCheckForUpdateClick = {
                            if (updateCheck?.isActive != true) updateCheck = updateManager.checkUpdate(this@AboutFragment, updateBusy, updateWait)
                        },
                        joinDesktopClick = {
                            val url = urlManager.installationGuideUrl
                            if (!TextUtils.isEmpty(url)) {
                                requireContext().openExternalBrowser(url)
                            }
                        },
                        callServerUrlNodeClick = {
                            val intent = Intent(requireContext(), LCallServerNodeActivity::class.java)
                            startActivity(intent)
                        },
                        
                        
                        showCallServerEntry = isDebugOrInsider(),
                        showBackButton = showsDetailBackButton()
                    )
                }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prepareWait = PageWait(this)
        uploadWait = PageWait(this)
        updateWait = PageWait(this)
        prepareBusy = MutableStateFlow(false)
        uploadBusy = MutableStateFlow(false)
        updateBusy = MutableStateFlow(false)
        waitWhileVisibleTop(prepareBusy, prepareWait)
        waitWhileVisibleTop(uploadBusy, uploadWait)
        waitWhileVisibleTop(updateBusy, updateWait)
    }

    
    private fun isDebugOrInsider(): Boolean =
        BuildConfig.DEBUG || globalServices.environmentHelper.isInsiderChannel()
}
