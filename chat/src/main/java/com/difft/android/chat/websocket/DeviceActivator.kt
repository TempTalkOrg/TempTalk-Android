package com.difft.android.chat.websocket

import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.di.ChativeHttpClientModule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-activates a device the server disabled for prolonged inactivity (the 451 handshake path).
 *
 * Application-scoped rather than a ViewModel method: the activation prompt is raised from the
 * socket layer and shown by an app-wide presenter that has no ViewModel of its own.
 */
@Singleton
class DeviceActivator @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val chatHttpClient: dagger.Lazy<ChativeHttpClient>,
) {
    suspend fun activate(): BaseResponse<Any> =
        chatHttpClient.get().getService(HttpService::class.java).activateDevice()
}
