package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import com.difft.android.BuildConfig
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.AppTheme
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.call.node.LCallServerNodeActivity
import com.difft.android.network.UrlManager
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import javax.inject.Inject

@AndroidEntryPoint
class AboutActivity : BaseActivity() {
    @Inject
    lateinit var updateManager: UpdateManager

    @Inject
    lateinit var urlManager: UrlManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, AboutActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme(backgroundColorResId = com.difft.android.base.R.color.bg_setting) {
                AboutPageView(
                    onBackClick = { finish() },
                    appVersion = PackageUtil.getAppVersionName() ?: "",
                    buildVersion = PackageUtil.getAppVersionCode().toString(),
                    buildTime = TimeUtils.millis2String(BuildConfig.BUILD_TIME.toLong()),
                    onCheckForUpdateClick = {
                        updateManager.checkUpdate(this, true)
                    },
                    joinDesktopClick = {
                        val url = urlManager.installationGuideUrl
                        if (!TextUtils.isEmpty(url)) {
                            openExternalBrowser(url)
                        }
                    },
                    callServerUrlNodeClick = {
                        val intent = Intent(this, LCallServerNodeActivity::class.java)
                        this.startActivity(intent)
                    },
                    isInsider = globalServices.environmentHelper.isInsiderChannel()
                )
            }
        }
    }
}