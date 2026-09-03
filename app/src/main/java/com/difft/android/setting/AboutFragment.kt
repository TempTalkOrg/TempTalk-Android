package com.difft.android.setting

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.difft.android.BuildConfig
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.call.node.LCallServerNodeActivity
import com.difft.android.network.UrlManager
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import javax.inject.Inject

/**
 * Fragment for About page (using Compose)
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class AboutFragment : Fragment() {

    companion object {
        fun newInstance() = AboutFragment()

        /** Display name shown in parentheses after the version, per distribution channel. */
        private fun channelDisplayName(channel: String): String = when (channel) {
            "google" -> "Google"
            "official" -> "Official"
            "insider" -> "Insider"
            "fdroid" -> "F-Droid"
            else -> channel // fallback: show unknown channel as-is rather than hiding it
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
                DifftTheme {
                    AboutPageView(
                        onBackClick = { activity?.finish() },
                        appVersion = PackageUtil.getAppVersionName() ?: "",
                        channelLabel = channelDisplayName(globalServices.environmentHelper.getChannelName()),
                        buildVersion = PackageUtil.getAppVersionCode().toString(),
                        buildTime = TimeUtils.millis2String(BuildConfig.BUILD_TIME.toLong()),
                        onCheckForUpdateClick = {
                            updateManager.checkUpdate(requireActivity(), true)
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
                        // Call-service diagnostics: insider channel, plus any debug build so
                        // devs can reach the node switcher without an insider-flavor install.
                        showCallServerEntry = isDebugOrInsider(),
                        showBackButton = !isInDualPaneMode()
                    )
                }
            }
        }
    }

    /** Builds that may see diagnostics affordances: insider installs and any debug build. */
    private fun isDebugOrInsider(): Boolean =
        BuildConfig.DEBUG || globalServices.environmentHelper.isInsiderChannel()
}
