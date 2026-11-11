package com.difft.android.chat.websocket

import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.di.ChativeHttpClientModule
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
class DeviceActivator @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val chatHttpClient: dagger.Lazy<ChativeHttpClient>,
) {
    suspend fun activate(): BaseResponse<Any> =
        chatHttpClient.get().getService(HttpService::class.java).activateDevice()
}
