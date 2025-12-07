package com.difft.android.push

import android.annotation.SuppressLint
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.BindPushTokenRequestBody
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object PushUtil {
    @SuppressLint("CheckResult")
    fun sendRegistrationToServer(tpnID: String?, fcmID: String?, onComplete: (() -> Unit)? = null) {
        val chatHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(application).httpClient()
        val type = if (!TextUtils.isEmpty(fcmID)) {
            "fcm_v2"
        } else if (!TextUtils.isEmpty(tpnID)) {
            "onlytpn"
        } else {
            ""
        }
        chatHttpClient.httpService.fetchBindPushToken(SecureSharedPrefsUtil.getBasicAuth(), type, BindPushTokenRequestBody(tpnID, fcmID))
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({ result ->
                if (result.status == 0) {
                    L.i { "[Push]绑定token成功" }
                }
                onComplete?.invoke()
            }, {
                L.e { "[Push]绑定token失败" + it.message }
                it.printStackTrace()
                onComplete?.invoke()
            })
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun httpClient(): ChativeHttpClient
    }
}