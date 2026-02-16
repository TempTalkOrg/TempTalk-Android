package com.difft.android.chat.invite

import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import io.reactivex.rxjava3.core.Single
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

    fun getInviteCode(regenerate: Int, short: Int): Single<BaseResponse<GetInviteCodeResponse>> =
        inviteService.getInviteCode(SecureSharedPrefsUtil.getToken(), regenerate, short)

    fun queryByInviteCode(inviteCode: String): Single<BaseResponse<QueryInviteCodeResponse>> = inviteService.queryByInviteCode(SecureSharedPrefsUtil.getToken(), QueryInviteCodeRequest(inviteCode))

    fun queryByCustomUid(version: Int, customUid: String): Single<BaseResponse<QueryCustomUidResponse>> = inviteService.queryByCustomUid(SecureSharedPrefsUtil.getToken(), QueryCustomUidRequest(version, customUid))

}