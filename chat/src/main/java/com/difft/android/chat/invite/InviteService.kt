package com.difft.android.chat.invite

import com.difft.android.network.BaseResponse
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface InviteService {
    @POST("v3/accounts/inviteCode")
    fun getInviteCode(
        @Header("Authorization") token: String,
        @Query("regenerate") regenerate: Int = 0,
        @Query("short") short: Int = 0
    ): Single<BaseResponse<GetInviteCodeResponse>>


    @PUT("v3/accounts/querybyInviteCode")
    fun queryByInviteCode(
        @Header("Authorization") token: String,
        @Body body: QueryInviteCodeRequest
    ): Single<BaseResponse<QueryInviteCodeResponse>>

}