package com.difft.android.network.config

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.PackageUtil
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object UserAgentManager {
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val environmentHelper: EnvironmentHelper
    }

    private val environmentHelper: EnvironmentHelper = EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application).environmentHelper

    /**
     * like: TempTalk/1.9.0 (Android 36; Google Pixel 6; Build 370644; Channel google; AppId org.difft.chative)
     */
    fun getUserAgent(): String = "${productName}/${appVersionName} (Android ${SDK_INT}; ${mode()}; Build ${appVersionCode}; Channel ${environmentHelper.getChannelName()}; AppId ${com.difft.android.base.utils.application.packageName})"

    private val productName
        get() = when {
            environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT) -> "TempTalkTest"
            else -> "TempTalk"
        }

    private val appVersionName
        get() = PackageUtil.getAppVersionName()

    private val appVersionCode
        get() = PackageUtil.getAppVersionCode()

    private var MODEL: String = ""

    private fun mode(): String {
        if (MODEL.isNotBlank()) return MODEL
        val manufacturer = Build.MANUFACTURER
        val mode = Build.MODEL
        MODEL = if (mode.startsWith(manufacturer)) mode else "$manufacturer $mode"
        return MODEL
    }
}