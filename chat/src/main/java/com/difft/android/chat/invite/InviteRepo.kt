package com.difft.android.chat.invite

import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InviteRepo @Inject constructor() {
    @ChativeHttpClientModule.Chat
    @Inject
    lateinit var httpClient: ChativeHttpClient

    private val inviteService by lazy {
        httpClient.getService(InviteService::class.java)
    }

    suspend fun getInviteCode(regenerate: Int, short: Int): BaseResponse<GetInviteCodeResponse> =
        inviteService.getInviteCode(SecureSharedPrefsUtil.getToken(), regenerate, short)

    suspend fun queryByInviteCode(inviteCode: String): BaseResponse<QueryInviteCodeResponse> = inviteService.queryByInviteCode(SecureSharedPrefsUtil.getToken(), QueryInviteCodeRequest(inviteCode))

    suspend fun queryByCustomUid(version: Int, customUid: String): BaseResponse<QueryCustomUidResponse> = inviteService.queryByCustomUid(SecureSharedPrefsUtil.getToken(), QueryCustomUidRequest(version, customUid))

}
