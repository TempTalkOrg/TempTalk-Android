package com.difft.android.network.repos

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetHotMsgParams
import com.difft.android.network.requests.GetReadPositionParams
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InitNetRepo @Inject constructor(
    @ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient
) {
    suspend fun getHotMsg(param: GetHotMsgParams) = httpClient.getService(InitService::class.java).getHotMsg(param)

    suspend fun getConversationMsg() = httpClient.getService(InitService::class.java).getConversationMsg()

    suspend fun getReadPosition(params: GetReadPositionParams) = httpClient.getService(InitService::class.java).getReadPosition(params)

}